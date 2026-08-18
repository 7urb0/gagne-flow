package com.gagneflow.service.chat;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.transaction.annotation.Transactional;
import com.gagneflow.entity.SessionMessage;
import com.gagneflow.entity.SessionMeta;
import com.gagneflow.repository.SessionMessageRepository;
import com.gagneflow.repository.SessionMetaRepository;
import com.gagneflow.service.memory.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChatSessionService {
    private static final Logger logger = LoggerFactory.getLogger(ChatSessionService.class);
    private static final String SESSION_KEY_PREFIX = "gagneflow:chat:session:";
    private static final Object VOID_MARKER = new Object();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TokenCounter tokenCounter;
    @Autowired
    private SessionMetaRepository sessionMetaRepo;
    @Autowired
    private SessionMessageRepository sessionMessageRepo;
    @Value(value="${gagneflow.session.max-idle:24h}")
    private Duration maxIdleTime;
    @Value(value="${gagneflow.session.max-window-size:6}")
    private int maxWindowSize;
    @Value(value="${gagneflow.memory.max-window-tokens:2000}")
    private int maxWindowTokens;

    public ChatSessionService(StringRedisTemplate redisTemplate, TokenCounter tokenCounter) {
        this.redisTemplate = redisTemplate;
        this.tokenCounter = tokenCounter;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule((Module)new JavaTimeModule());
    }

    private String buildKey(Long userId, String sessionId) {
        long uid = userId != null ? userId : 0L;
        return SESSION_KEY_PREFIX + uid + ":" + sessionId;
    }

    public ChatSession getOrCreate(Long userId, String sessionId) {
        try {
            ChatSession session = this.getFromRedis(userId, sessionId);
            if (session == null) {
                session = this.withOptimisticLock(userId, sessionId, s -> s);
            }
            return session;
        }
        catch (Exception e) {
            // Redis 故障降级: 尝试从 MySQL 读取会话历史重建会话
            logger.warn("Redis 读取失败，降级至 MySQL 重建会话: sessionId={}", sessionId, e);
            return this.rebuildFromMySql(userId, sessionId);
        }
    }

    @Transactional
    public void addMessage(Long userId, String sessionId, String userQuestion, String aiAnswer) {
        this.withOptimisticLock(userId, sessionId, session -> {
            session.addMessage(userQuestion, aiAnswer, this.maxWindowSize, this.tokenCounter);
            this.trimByTokenBudget((ChatSession)session);
            return null;
        });
        // 修复BUG 2: 双写到 MySQL，保证 Redis 与 MySQL 数据一致性
        try {
            this.saveMessage(userId, sessionId, "user", userQuestion);
            this.saveMessage(userId, sessionId, "assistant", aiAnswer);
        } catch (Exception e) {
            logger.error("MySQL \u6d88\u606f\u6301\u4e45\u5316\u5931\u8d25 (Redis \u5df2\u5199\u5165): userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    private void trimByTokenBudget(ChatSession session) {
        List<Map<String, String>> history = session.getMessageHistory();
        if (history.isEmpty()) {
            return;
        }
        int removeCount = 0;
        int tokens = session.getTotalTokens();
        int stuckCounter = 0;
        while (tokens > this.maxWindowTokens && removeCount < history.size()) {
            int est = this.tokenCounter.estimate(history.get(removeCount).getOrDefault("content", ""));
            tokens -= est;
            removeCount++;
            // 修复C-11: 防止 token 估计返回 0 导致无限循环
            if (est <= 0) stuckCounter++;
            else stuckCounter = 0;
            if (stuckCounter >= 10) break;
        }
        // 边界修复: 单条消息超长导致全部移除时, 保底保留最后一条(最新消息), 防止上下文清空失忆
        if (removeCount >= history.size()) {
            removeCount = history.size() - 1;
            tokens = this.tokenCounter.estimate(history.get(removeCount).getOrDefault("content", ""));
        }
        if (removeCount > 0) {
            ArrayList<Map<String, String>> trimmed = new ArrayList<Map<String, String>>(history.subList(removeCount, history.size()));
            session.setHistory(trimmed);
            session.setTotalTokens(Math.max(0, tokens));
        }
    }

    public List<Map<String, String>> getHistory(Long userId, String sessionId) {
        return this.getOrCreate(userId, sessionId).getMessageHistory();
    }

    public void clearHistory(Long userId, String sessionId) {
        ChatSession session = this.getFromRedis(userId, sessionId);
        if (session != null) {
            session.clearHistory();
            this.saveToRedis(userId, sessionId, session);
        }
    }

    public int getMessagePairCount(Long userId, String sessionId) {
        ChatSession session = this.getFromRedis(userId, sessionId);
        return session != null ? session.getMessagePairCount() : 0;
    }

    public void replaceHistory(Long userId, String sessionId, List<Map<String, String>> newHistory, String summary, int lastSummaryPairCount) {
        this.withOptimisticLock(userId, sessionId, session -> {
            session.clearHistory();
            for (Map msg : newHistory) {
                session.addDirect((String)msg.get("role"), (String)msg.get("content"));
            }
            session.setSummary(summary);
            session.setLastSummaryPairCount(lastSummaryPairCount);
            return null;
        });
    }

    public ChatSession getRaw(Long userId, String sessionId) {
        return this.getFromRedis(userId, sessionId);
    }

    public void saveRaw(Long userId, String sessionId, ChatSession session) {
        this.saveToRedis(userId, sessionId, session);
    }

    private ChatSession rebuildFromMySql(Long userId, String sessionId) {
        try {
            long uid = userId != null && userId > 0L ? userId : 0L;
            List<SessionMessage> msgs = this.sessionMessageRepo.findByUserIdAndSessionId(uid, sessionId);
            if (msgs == null || msgs.isEmpty()) {
                logger.info("MySQL 无会话历史，创建新空会话: sessionId={}", sessionId);
                return new ChatSession(sessionId);
            }
            ChatSession session = new ChatSession(sessionId);
            for (SessionMessage msg : msgs) {
                // 逐条按 role/content 重建，避免把 role 当问题文本 (addMessage 是一对消息的语义)
                session.addDirect(msg.getRole(), msg.getContent());
            }
            logger.info("MySQL 会话重建成功: sessionId={}, 重建 {} 条消息", sessionId, msgs.size());
            return session;
        }
        catch (Exception e2) {
            logger.error("MySQL 会话重建失败，返回新空会话: sessionId={}", sessionId, e2);
            return new ChatSession(sessionId);
        }
    }

    private ChatSession getFromRedis(Long userId, String sessionId) {
        try {
            String key = this.buildKey(userId, sessionId);
            String json = (String)this.redisTemplate.opsForValue().get((Object)key);
            // key不存在时返回 null，连接异常时向上抛出 (getOrCreate 中降级处理)
            ChatSession session = this.deserialize(json);
            // L1修复: 每次读取会话时刷新 TTL，避免长时间空闲导致的会话过期丢失
            // 写入操作(addMessage/withOptimisticLock)已有 SET 命令自动刷新 TTL
            // 此处补充读取路径的 TTL 刷新，确保 HTTP GET /chat/history 等只读操作也能续期
            if (session != null) {
                try {
                    this.redisTemplate.expire(key, this.maxIdleTime);
                } catch (Exception refreshEx) {
                    logger.trace("刷新会话 TTL 失败 (不影响读取): {}", refreshEx.getMessage());
                }
            }
            return session;
        }
        catch (Exception e) {
            // 数据损坏异常 (deserialize 抛出的"会话数据已损坏") → 穿透到 getOrCreate 走 MySQL 降级重建，
            // 避免静默丢弃 Redis 中的历史消息 (P0-1)
            if (e instanceof RuntimeException && e.getMessage() != null && e.getMessage().contains("会话数据已损坏")) {
                throw (RuntimeException) e;
            }
            logger.error("\u4ece Redis \u8bfb\u53d6\u4f1a\u8bdd\u5931\u8d25: {}", (Object)sessionId, (Object)e);
            return null;
        }
    }

    private void saveToRedis(Long userId, String sessionId, ChatSession session) {
        try {
            this.redisTemplate.opsForValue().set(this.buildKey(userId, sessionId), this.serialize(session), this.maxIdleTime);
        }
        catch (Exception e) {
            logger.error("\u4fdd\u5b58\u4f1a\u8bdd\u5230 Redis \u5931\u8d25: {}", (Object)sessionId, (Object)e);
        }
    }

    public void registerSession(Long userId, String sessionId, String title) {
        long uid = userId != null && userId > 0L ? userId : 0L;
        SessionMeta existing = this.sessionMetaRepo.findByUserIdAndSessionId(uid, sessionId);
        if (existing != null) {
            existing.setTitle(title);
            existing.setUpdateTime(Instant.now());
            this.sessionMetaRepo.save(existing);
        } else {
            this.sessionMetaRepo.save(new SessionMeta(uid, sessionId, title));
        }
    }

    public List<Map<String, Object>> getUserSessions(Long userId) {
        long uid = userId != null && userId > 0L ? userId : 0L;
        List<SessionMeta> metas = this.sessionMetaRepo.findByUserIdOrderByUpdateTimeDesc(uid);
        ArrayList<Map<String, Object>> sessions = new ArrayList<Map<String, Object>>();
        for (SessionMeta m : metas) {
            HashMap<String, Object> item = new HashMap<String, Object>();
            item.put("sessionId", m.getSessionId());
            item.put("title", m.getTitle());
            item.put("time", m.getUpdateTime().toEpochMilli());
            sessions.add(item);
        }
        return sessions;
    }

    private <T> T withOptimisticLock(Long userId, final String sessionId, final Function<ChatSession, T> operation) {
        final String key = this.buildKey(userId, sessionId);
        for (int retry = 0; retry < 3; ++retry) {
            Object result = this.redisTemplate.execute(new SessionCallback<T>(){

                public T execute(RedisOperations ops) {
                    ops.watch((Object)key);
                    String json = (String)ops.opsForValue().get((Object)key);
                    ChatSession session = ChatSessionService.this.deserialize(json);
                    if (session == null) {
                        session = new ChatSession(sessionId);
                    }
                    Object value = operation.apply(session);
                    ops.multi();
                    ops.opsForValue().set(key, ChatSessionService.this.serialize(session), ChatSessionService.this.maxIdleTime);
                    return ops.exec() != null ? (T)(value != null ? value : VOID_MARKER) : null;
                }
            });
            if (result != null) {
                return (T)(result == VOID_MARKER ? null : result);
            }
            logger.warn("\u4f1a\u8bdd {} WATCH \u51b2\u7a81\uff0c\u91cd\u8bd5 {}/3", (Object)sessionId, (Object)(retry + 1));
        }
        throw new ConcurrentModificationException("\u4f1a\u8bdd " + key + " \u5e76\u53d1\u51b2\u7a81\uff0c\u91cd\u8bd53\u6b21\u540e\u4ecd\u5931\u8d25");
    }

    /**
     * Save a chat message to MySQL persistence.
     * Extracted from ChatController to fix layering violation (F06).
     */
    public void saveMessage(Long userId, String sessionId, String role, String content) {
        long uid = userId != null && userId > 0L ? userId : 0L;
        // 修复BUG 4: 校验 role 字段，确保与 Redis ChatSession 的约定一致
        if (role == null || (!"user".equals(role) && !"assistant".equals(role))) {
            throw new IllegalArgumentException("Role must be 'user' or 'assistant', got: " + role);
        }
        sessionMessageRepo.save(new SessionMessage(uid, sessionId, role, content));
    }

    /**
     * Get messages for a session from MySQL.
     */
    public List<SessionMessage> getSessionMessages(Long userId, String sessionId) {
        long uid = userId != null && userId > 0L ? userId : 0L;
        return sessionMessageRepo.findByUserIdAndSessionId(uid, sessionId);
    }

    /**
     * Get top N messages for a session from MySQL.
     */
    public List<SessionMessage> getSessionMessages(Long userId, String sessionId, int limit) {
        long uid = userId != null && userId > 0L ? userId : 0L;
        return sessionMessageRepo.findTopNByUserIdAndSessionId(uid, sessionId, limit);
    }

    /**
     * Delete all messages for a session from MySQL.
     */
    public void deleteSessionMessages(Long userId, String sessionId) {
        long uid = userId != null && userId > 0L ? userId : 0L;
        sessionMessageRepo.deleteByUserIdAndSessionId(uid, sessionId);
    }

    /**
     * Delete a batch of messages from MySQL (used during summary trimming).
     */
    public void deleteMessages(List<SessionMessage> messages) {
        sessionMessageRepo.deleteAll(messages);
    }

    private String serialize(ChatSession session) {
        try {
            return this.objectMapper.writeValueAsString((Object)session);
        }
        catch (Exception e) {
            throw new RuntimeException("\u5e8f\u5217\u5316 ChatSession \u5931\u8d25", e);
        }
    }

    private ChatSession deserialize(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return (ChatSession)this.objectMapper.readValue(json, ChatSession.class);
        }
        catch (Exception e) {
            String preview = json.length() > 200 ? json.substring(0, 200) : json;
            logger.error("会话数据损坏，无法反序列化。预览: {}", preview, e);
            throw new RuntimeException("会话数据已损坏，无法恢复", e);
        }
    }
}
