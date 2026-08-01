package com.gagneflow.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Redis 集中式滑动窗口限流拦截器 (P0修复: 替换 ConcurrentHashMap 为 Redis Lua 原子化操作)。
 * <p>
 * 原实现在多实例部署时各 Pod 独立计数，实际限流失效。
 * 新实现使用 Redis ZSET + Lua 脚本保证原子性，所有 Pod 共享同一计数器。
 * Redis 不可用时降级放行，避免误拦正常请求。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final int LOGIN_LIMIT = 5;
    private static final int CHAT_LIMIT = 10;
    private static final int RAG_LIMIT = 30;
    private static final int LESSON_LIMIT = 2;
    private static final long WINDOW_SECS = 60L;
    private static final String RATE_KEY_PREFIX = "gagneflow:rl:";

    /**
     * 限流总开关 (测试环境可关闭，避免集成测试被 429 干扰)
     */
    @org.springframework.beans.factory.annotation.Value("${gagneflow.rate-limit.enabled:true}")
    private boolean enabled = true;

    /**
     * Lua 滑动窗口限流脚本:
     *   KEYS[1]   = 限流 key
     *   ARGV[1]   = 窗口大小 (秒)
     *   ARGV[2]   = 限制次数
     *   ARGV[3]   = 当前毫秒时间戳 (score)
     *   ARGV[4]   = 唯一 member (时间戳-线程ID)
     *   ARGV[5]   = 窗口起始毫秒时间戳 (用于计算剩余时间)
     * 返回: "OK:R" = 通过, 剩余 R 次; "BLOCKED:S" = 被限, S 秒后恢复
     */
    private static final String LUA_SCRIPT = ""
        + "local key = KEYS[1]\n"
        + "local window_s = tonumber(ARGV[1])\n"
        + "local limit   = tonumber(ARGV[2])\n"
        + "local now_ms  = tonumber(ARGV[3])\n"
        + "local member  = ARGV[4]\n"
        + "local win_start = tonumber(ARGV[5])\n"
        + "redis.call('ZREMRANGEBYSCORE', key, 0, win_start)\n"
        + "local cnt = redis.call('ZCARD', key)\n"
        + "if cnt < limit then\n"
        + "    redis.call('ZADD', key, now_ms, member)\n"
        + "    redis.call('EXPIRE', key, window_s)\n"
        + "    return 'OK:' .. (limit - cnt - 1)\n"
        + "else\n"
        + "    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')\n"
        + "    local reset = window_s\n"
        + "    if oldest and #oldest >= 2 then\n"
        + "        reset = math.ceil((tonumber(oldest[2]) - win_start) / 1000)\n"
        + "        if reset < 1 then reset = 1 end\n"
        + "    end\n"
        + "    return 'BLOCKED:' .. reset\n"
        + "end";

    private final DefaultRedisScript<String> luaScript;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public RateLimitInterceptor() {
        this.luaScript = new DefaultRedisScript<>();
        this.luaScript.setScriptText(LUA_SCRIPT);
        this.luaScript.setResultType(String.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // 限流总开关: 关闭时直接放行
        if (!this.enabled) {
            return true;
        }
        String path = request.getRequestURI();
        String keySuffix = this.resolveKeySuffix(request, path);
        if (keySuffix == null) {
            return true;
        }
        int limit = this.resolveLimit(path);

        try {
            String redisKey = RATE_KEY_PREFIX + keySuffix;
            long nowMs = System.currentTimeMillis();
            long windowStartMs = nowMs - WINDOW_SECS * 1000;
            String member = nowMs + "-" + UUID.randomUUID().toString().substring(0, 8);

            String result = this.stringRedisTemplate.execute(
                this.luaScript,
                List.of(redisKey),
                String.valueOf(WINDOW_SECS),
                String.valueOf(limit),
                String.valueOf(nowMs),
                member,
                String.valueOf(windowStartMs)
            );

            if (result == null) {
                logger.warn("[RL] Lua script returned null, fallback pass: path={}", path);
                return true;
            }

            if (result.startsWith("OK:")) {
                return true;
            }

            // 被限流: BLOCKED:resetSeconds
            String resetStr = result.substring("BLOCKED:".length());
            long resetSec = Long.parseLong(resetStr);
            logger.warn("[RL] 限流触发: path={}, keySuffix={}, limit={}, retry={}s",
                path, keySuffix, limit, resetSec);

            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(String.format(
                "{\"error\":\"请求过于频繁，请 %d 秒后重试\",\"code\":429,\"retry_after\":%d}",
                resetSec, resetSec));
            return false;

        } catch (Exception e) {
            // Redis 不可用时降级放行，避免误拦正常流量
            logger.error("[RL] Redis 不可用，限流降级放行: path={}, reason={}", path, e.getMessage());
            return true;
        }
    }

    private String resolveKeySuffix(HttpServletRequest request, String path) {
        Long uid = this.getUserId();
        String ident = uid != null ? "user:" + uid : "ip:" + request.getRemoteAddr();
        if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) {
            return "login:" + ident;
        }
        if (path.equals("/api/chat_stream")) {
            return "chat:" + ident;
        }
        if (path.equals("/api/lesson_plan")) {
            return "lesson:" + ident;
        }
        if (path.equals("/api/rag/query")) {
            return "rag:" + ident;
        }
        return null;
    }

    private Long getUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Long) {
                return (Long) auth.getPrincipal();
            }
        } catch (Exception e) {
            logger.trace("[RL] cannot resolve user: {}", e.getMessage());
        }
        return null;
    }

    private int resolveLimit(String path) {
        if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) {
            return LOGIN_LIMIT;
        }
        if (path.equals("/api/chat_stream")) {
            return CHAT_LIMIT;
        }
        if (path.equals("/api/lesson_plan")) {
            return LESSON_LIMIT;
        }
        if (path.equals("/api/rag/query")) {
            return RAG_LIMIT;
        }
        return Integer.MAX_VALUE;
    }
}
