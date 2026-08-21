package com.gagneflow.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gagneflow.entity.SessionMessage;
import com.gagneflow.repository.SessionMessageRepository;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 教案检索工具：返回当前用户最近生成的一份教案。
 * 读取链路：Redis 长驻存档（7 天，gagneflow:lesson:latest:{uid}）优先，
 * 未命中降级查 MySQL session_message 表（role=assistant 且 content 以 &lt;!DOCTYPE html&gt; 开头的最新一条）。
 * 只返回 pdfUrl + sessionId + 截断预览，避免把完整 HTML 塞进模型上下文。
 */
@Component
public class LessonPlanTools {
    private static final Logger logger = LoggerFactory.getLogger(LessonPlanTools.class);
    public static final String TOOL_GET_LATEST_LESSON_PLAN = "getLatestLessonPlan";
    /** Redis 教案长驻存档 key 前缀（最新一份，供 getLatestLessonPlan 工具检索） */
    public static final String REDIS_KEY_PREFIX = "gagneflow:lesson:latest:";
    /** Redis 教案按 sid 维度存档 key 前缀（供 PDF 下载按会话取回，与对话存储完全隔离） */
    public static final String REDIS_PLAN_KEY_PREFIX = "gagneflow:lesson:plan:";
    /** 教案 HTML 判定前缀（与 LessonController PDF 下载过滤条件一致） */
    public static final String HTML_PREFIX = "<!DOCTYPE html>";
    /** 教案 PDF 下载路径前缀 */
    public static final String PDF_PATH_PREFIX = "/api/lesson_plan/pdf/";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final int PREVIEW_LENGTH = 200;

    private final SessionMessageRepository sessionMessageRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("${gagneflow.lesson.archive-ttl-days:7}")
    private long archiveTtlDays = 7L;

    @Autowired
    public LessonPlanTools(SessionMessageRepository sessionMessageRepository,
                           StringRedisTemplate stringRedisTemplate) {
        this.sessionMessageRepository = sessionMessageRepository;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Tool(description="获取当前用户最近生成的一份教案。当用户提到上次的教案、之前生成的教案、重新发我一份教案时使用")
    public String getLatestLessonPlan() {
        try {
            Long userId = getCurrentUserId();
            if (userId == null || userId <= 0L) {
                return noLessonPlanJson();
            }
            // 1. 优先读 Redis 长驻存档（若实现 B）
            Archive archive = readRedisArchive(userId);
            if (archive != null) {
                return buildHitJson(archive.sessionId, archive.html);
            }
            // 2. Redis 未命中/不可用 → 查 MySQL：该用户最近一条教案 HTML
            List<SessionMessage> msgs = this.sessionMessageRepository
                    .findLatestByUserIdAndRoleAndContentPrefix(
                            userId, ROLE_ASSISTANT, HTML_PREFIX, PageRequest.of(0, 1));
            if (msgs == null || msgs.isEmpty()) {
                return noLessonPlanJson();
            }
            SessionMessage latest = msgs.get(0);
            if (latest.getSessionId() == null || latest.getSessionId().isEmpty()
                    || latest.getContent() == null || latest.getContent().isEmpty()) {
                return noLessonPlanJson();
            }
            return buildHitJson(latest.getSessionId(), latest.getContent());
        } catch (Exception e) {
            logger.error("[工具错误] getLatestLessonPlan 执行失败", e);
            return String.format("{\"status\":\"error\",\"message\":\"获取最近教案失败: %s\"}", e.getMessage());
        }
    }

    /**
     * 将最近生成的教案写入 Redis 长驻存档（7 天，可配 gagneflow.lesson.archive-ttl-days）。
     * 由 LessonController 在成功持久化教案 HTML 后调用，供 getLatestLessonPlan 优先读取。
     */
    public void archiveLatestLessonPlan(Long userId, String sessionId, String html) {
        try {
            if (userId == null || sessionId == null || sessionId.isEmpty()
                    || html == null || html.isEmpty()) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("html", html);
            String json = this.objectMapper.writeValueAsString(payload);
            this.stringRedisTemplate.opsForValue()
                    .set(REDIS_KEY_PREFIX + userId, json, Duration.ofDays(this.archiveTtlDays));
            logger.debug("[教案存档] 已写入 Redis: uid={}, sessionId={}, ttl={}天",
                    userId, sessionId, this.archiveTtlDays);
        } catch (Exception e) {
            logger.warn("[教案存档] Redis 写入失败(不影响主流程): {}", e.getMessage());
        }
    }

    /**
     * 将教案 HTML 按 sessionId 维度独立存档（与对话存储物理隔离），
     * 供 PDF 下载接口按会话精确取回。TTL 同长驻存档。
     */
    public void archiveLessonPlan(Long userId, String sessionId, String html) {
        try {
            if (userId == null || sessionId == null || sessionId.isEmpty()
                    || html == null || html.isEmpty()) {
                return;
            }
            this.stringRedisTemplate.opsForValue()
                    .set(REDIS_PLAN_KEY_PREFIX + userId + ":" + sessionId, html,
                            Duration.ofDays(this.archiveTtlDays));
            logger.debug("[教案存档] 已按 sid 写入 Redis: uid={}, sessionId={}, ttl={}天",
                    userId, sessionId, this.archiveTtlDays);
        } catch (Exception e) {
            logger.warn("[教案存档] 按 sid Redis 写入失败(不影响主流程): {}", e.getMessage());
        }
    }

    /**
     * 按 sessionId 从独立教案存档取回 HTML（供 PDF 下载）。
     * 仅读取 gagneflow:lesson:plan:{uid}:{sid}，绝不触碰对话会话存储。
     */
    public String getLessonPlanHtml(Long userId, String sessionId) {
        try {
            if (this.stringRedisTemplate == null || userId == null
                    || sessionId == null || sessionId.isEmpty()) {
                return null;
            }
            return this.stringRedisTemplate.opsForValue()
                    .get(REDIS_PLAN_KEY_PREFIX + userId + ":" + sessionId);
        } catch (Exception e) {
            logger.warn("[教案存档] 按 sid Redis 读取失败: {}", e.getMessage());
            return null;
        }
    }

    private Archive readRedisArchive(Long userId) {
        try {
            if (this.stringRedisTemplate == null) {
                return null;
            }
            String json = this.stringRedisTemplate.opsForValue().get(REDIS_KEY_PREFIX + userId);
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode node = this.objectMapper.readTree(json);
            if (node == null || !node.hasNonNull("sessionId") || !node.hasNonNull("html")) {
                return null;
            }
            String sessionId = node.get("sessionId").asText();
            String html = node.get("html").asText();
            if (sessionId == null || sessionId.isEmpty() || html == null || html.isEmpty()) {
                return null;
            }
            return new Archive(sessionId, html);
        } catch (Exception e) {
            logger.debug("[教案存档] Redis 读取失败，降级查 MySQL: {}", e.getMessage());
            return null;
        }
    }

    /** 命中返回：只回 pdfUrl + sessionId + 截断预览，绝不含全量 HTML（防注入 + 省 token） */
    private String buildHitJson(String sessionId, String html) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("sessionId", sessionId);
            payload.put("pdfUrl", PDF_PATH_PREFIX + sessionId);
            String preview = html.length() > PREVIEW_LENGTH ? html.substring(0, PREVIEW_LENGTH) : html;
            payload.put("preview", preview);
            return this.objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            logger.warn("构建教案返回 JSON 失败: {}", e.getMessage());
            return "{\"status\":\"error\",\"message\":\"教案序列化失败\"}";
        }
    }

    private String noLessonPlanJson() {
        return "{\"status\":\"no_lesson_plan\",\"message\":\"未找到最近生成的教案\"}";
    }

    /** 复制 InternalDocsTools.getCurrentUserId() 模式：Authentication.principal 为 Long 时取之，否则 0L。包级可见便于测试。 */
    Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Long) {
                return (Long) auth.getPrincipal();
            }
        } catch (Exception e) {
            logger.trace("无法获取当前用户: {}", e.getMessage());
        }
        return 0L;
    }

    private static class Archive {
        final String sessionId;
        final String html;

        Archive(String sessionId, String html) {
            this.sessionId = sessionId;
            this.html = html;
        }
    }
}
