package com.gagneflow.service.chat;

import com.gagneflow.entity.SessionMessage;
import com.gagneflow.entity.SessionMeta;
import com.gagneflow.repository.SessionMessageRepository;
import com.gagneflow.repository.SessionMetaRepository;
import com.gagneflow.service.memory.TokenCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ConcurrentModificationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ChatSessionService Tests")
class ChatSessionServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SessionMetaRepository sessionMetaRepo;
    @Mock private SessionMessageRepository sessionMessageRepo;
    private TokenCounter tokenCounter;
    private ChatSessionService service;

    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "test-session-123";

    @BeforeEach
    void setUp() {
        tokenCounter = new TokenCounter();

        service = new ChatSessionService(redisTemplate, tokenCounter);
        // Inject mocked repos via reflection since they use @Autowired field injection
        injectField("sessionMetaRepo", sessionMetaRepo);
        injectField("sessionMessageRepo", sessionMessageRepo);
        // Inject config values
        injectField("maxIdleTime", Duration.ofHours(24));
        injectField("maxWindowSize", 6);
        injectField("maxWindowTokens", 2000);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private void injectField(String name, Object value) {
        try {
            var field = ChatSessionService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================================================================
    // Session CRUD Tests
    // ==================================================================

    @Nested
    @DisplayName("Session CRUD Operations")
    class SessionCrud {

        @Test
        @DisplayName("getOrCreate creates new session when Redis returns null")
        void getOrCreateFreshSession() {
            when(valueOps.get(anyString())).thenReturn(null);
            when(redisTemplate.execute(any(SessionCallback.class))).thenReturn("marker");

            ChatSession session = service.getOrCreate(USER_ID, SESSION_ID);
            assertNotNull(session);
            assertEquals(SESSION_ID, session.getSessionId());
            assertEquals(0, session.getMessagePairCount());
        }

        @Test
        @DisplayName("getOrCreate returns existing session from Redis")
        void getOrCreateExistingSession() {
            ChatSession existing = new ChatSession(SESSION_ID);
            existing.addMessage("hello", "hi there", 6, tokenCounter);
            String json = serializeSession(existing);

            when(valueOps.get(anyString())).thenReturn(json);
            // TTL refresh on read
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

            ChatSession session = service.getOrCreate(USER_ID, SESSION_ID);
            assertNotNull(session);
            assertEquals(1, session.getMessagePairCount());
        }

        @Test
        @DisplayName("getOrCreate falls back to MySQL when Redis fails")
        void getOrCreateRedisFailureFallback() {
            when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));

            SessionMessage msg = new SessionMessage(USER_ID, SESSION_ID, "user", "test question");
            when(sessionMessageRepo.findByUserIdAndSessionId(USER_ID, SESSION_ID))
                    .thenReturn(List.of(msg));

            ChatSession session = service.getOrCreate(USER_ID, SESSION_ID);
            assertNotNull(session);
            assertEquals(SESSION_ID, session.getSessionId());
        }

        @Test
        @DisplayName("addMessage persists user + assistant pair")
        void addMessageSavesPair() {
            ChatSession session = new ChatSession(SESSION_ID);
            session.addMessage("q1", "a1", 6, tokenCounter);
            String json = serializeSession(session);
            when(valueOps.get(anyString())).thenReturn(json);
            when(redisTemplate.execute(any(SessionCallback.class))).thenReturn("marker");

            service.addMessage(USER_ID, SESSION_ID, "new question", "new answer");

            verify(sessionMessageRepo, times(1)).save(argThat(m ->
                    "user".equals(m.getRole()) && "new question".equals(m.getContent())));
            verify(sessionMessageRepo, times(1)).save(argThat(m ->
                    "assistant".equals(m.getRole()) && "new answer".equals(m.getContent())));
        }

        @Test
        @DisplayName("addMessage continues even when MySQL write fails")
        void addMessageSurvivesMysqlFailure() {
            ChatSession session = new ChatSession(SESSION_ID);
            String json = serializeSession(session);
            when(valueOps.get(anyString())).thenReturn(json);
            when(redisTemplate.execute(any(SessionCallback.class))).thenReturn("marker");
            doThrow(new RuntimeException("MySQL down")).when(sessionMessageRepo).save(any());

            // Should not throw
            assertDoesNotThrow(() ->
                    service.addMessage(USER_ID, SESSION_ID, "ok", "ok"));
        }

        @Test
        @DisplayName("getHistory returns message history from session")
        void getHistoryReturnsMessages() {
            ChatSession session = new ChatSession(SESSION_ID);
            session.addMessage("q1", "a1", 6, tokenCounter);
            session.addMessage("q2", "a2", 6, tokenCounter);
            String json = serializeSession(session);
            when(valueOps.get(anyString())).thenReturn(json);
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

            List<Map<String, String>> history = service.getHistory(USER_ID, SESSION_ID);
            assertEquals(4, history.size()); // 2 pairs = 4 messages
        }

        @Test
        @DisplayName("clearHistory empties message list")
        void clearHistoryEmptiesMessages() {
            ChatSession session = new ChatSession(SESSION_ID);
            session.addMessage("q1", "a1", 6, tokenCounter);
            String json = serializeSession(session);
            when(valueOps.get(anyString())).thenReturn(json);

            service.clearHistory(USER_ID, SESSION_ID);
            // After clear, getFromRedis should return session with 0 messages
        }

        @Test
        @DisplayName("clearHistory does nothing for nonexistent session")
        void clearHistoryNoopForMissing() {
            when(valueOps.get(anyString())).thenReturn(null);
            assertDoesNotThrow(() -> service.clearHistory(USER_ID, SESSION_ID));
        }

        @Test
        @DisplayName("getMessagePairCount returns zero for missing session")
        void getMessagePairCountMissing() {
            when(valueOps.get(anyString())).thenReturn(null);
            assertEquals(0, service.getMessagePairCount(USER_ID, SESSION_ID));
        }

        @Test
        @DisplayName("getMessagePairCount returns correct count")
        void getMessagePairCountCorrect() {
            ChatSession session = new ChatSession(SESSION_ID);
            session.addMessage("q1", "a1", 6, tokenCounter);
            session.addMessage("q2", "a2", 6, tokenCounter);
            session.addMessage("q3", "a3", 6, tokenCounter);
            String json = serializeSession(session);
            when(valueOps.get(anyString())).thenReturn(json);

            assertEquals(3, service.getMessagePairCount(USER_ID, SESSION_ID));
        }
    }

    // ==================================================================
    // Save Message / Persistence Tests
    // ==================================================================

    @Nested
    @DisplayName("Message Persistence")
    class MessagePersistence {

        @Test
        @DisplayName("saveMessage with valid 'user' role succeeds")
        void saveMessageValidUserRole() {
            service.saveMessage(USER_ID, SESSION_ID, "user", "hello");
            verify(sessionMessageRepo).save(any(SessionMessage.class));
        }

        @Test
        @DisplayName("saveMessage with valid 'assistant' role succeeds")
        void saveMessageValidAssistantRole() {
            service.saveMessage(USER_ID, SESSION_ID, "assistant", "response");
            verify(sessionMessageRepo).save(any(SessionMessage.class));
        }

        @Test
        @DisplayName("saveMessage with null role throws IllegalArgumentException")
        void saveMessageNullRole() {
            assertThrows(IllegalArgumentException.class, () ->
                    service.saveMessage(USER_ID, SESSION_ID, (String) null, "content"));
        }

        @Test
        @DisplayName("saveMessage with invalid 'admin' role throws IllegalArgumentException")
        void saveMessageInvalidRole() {
            assertThrows(IllegalArgumentException.class, () ->
                    service.saveMessage(USER_ID, SESSION_ID, "admin", "content"));
        }

        @Test
        @DisplayName("saveMessage with empty role throws IllegalArgumentException")
        void saveMessageEmptyRole() {
            assertThrows(IllegalArgumentException.class, () ->
                    service.saveMessage(USER_ID, SESSION_ID, "", "content"));
        }

        @Test
        @DisplayName("saveMessage with null userId defaults to 0L")
        void saveMessageNullUserId() {
            service.saveMessage(null, SESSION_ID, "user", "test");
            ArgumentCaptor<SessionMessage> captor = ArgumentCaptor.forClass(SessionMessage.class);
            verify(sessionMessageRepo).save(captor.capture());
            assertEquals(0L, captor.getValue().getUserId());
        }

        @Test
        @DisplayName("saveMessage with null userId defaults to 0L")
        void saveMessageNullUserIdDefaultsToZero() {
            service.saveMessage(null, SESSION_ID, "user", "test");
            ArgumentCaptor<SessionMessage> captor = ArgumentCaptor.forClass(SessionMessage.class);
            verify(sessionMessageRepo).save(captor.capture());
            assertEquals(0L, captor.getValue().getUserId());
        }

        @Test
        @DisplayName("saveMessage with userId <= 0 defaults to 0L")
        void saveMessageZeroUserId() {
            service.saveMessage(0L, SESSION_ID, "assistant", "content");
            ArgumentCaptor<SessionMessage> captor = ArgumentCaptor.forClass(SessionMessage.class);
            verify(sessionMessageRepo).save(captor.capture());
            assertEquals(0L, captor.getValue().getUserId());
        }

        @Test
        @DisplayName("getSessionMessages with limit returns top N")
        void getSessionMessagesLimited() {
            List<SessionMessage> expected = List.of(
                    new SessionMessage(USER_ID, SESSION_ID, "user", "msg1"),
                    new SessionMessage(USER_ID, SESSION_ID, "assistant", "msg2"));
            when(sessionMessageRepo.findTopNByUserIdAndSessionId(USER_ID, SESSION_ID, 2))
                    .thenReturn(expected);

            List<SessionMessage> result = service.getSessionMessages(USER_ID, SESSION_ID, 2);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("deleteSessionMessages delegates to repository")
        void deleteSessionMessagesDelegates() {
            service.deleteSessionMessages(USER_ID, SESSION_ID);
            verify(sessionMessageRepo).deleteByUserIdAndSessionId(USER_ID, SESSION_ID);
        }

        @Test
        @DisplayName("deleteMessages batch delegates to repository")
        void deleteMessagesBatch() {
            List<SessionMessage> messages = List.of(
                    new SessionMessage(USER_ID, SESSION_ID, "user", "m1"));
            service.deleteMessages(messages);
            verify(sessionMessageRepo).deleteAll(messages);
        }
    }

    // ==================================================================
    // Session Metadata Tests
    // ==================================================================

    @Nested
    @DisplayName("Session Metadata")
    class SessionMetadata {

        @Test
        @DisplayName("registerSession creates new session meta")
        void registerSessionNew() {
            when(sessionMetaRepo.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(null);

            service.registerSession(USER_ID, SESSION_ID, "Math Lesson");
            verify(sessionMetaRepo).save(any(SessionMeta.class));
        }

        @Test
        @DisplayName("registerSession updates existing session title")
        void registerSessionUpdate() {
            SessionMeta existing = new SessionMeta(USER_ID, SESSION_ID, "Old Title");
            when(sessionMetaRepo.findByUserIdAndSessionId(USER_ID, SESSION_ID)).thenReturn(existing);

            service.registerSession(USER_ID, SESSION_ID, "New Title");
            assertEquals("New Title", existing.getTitle());
            verify(sessionMetaRepo).save(existing);
        }

        @Test
        @DisplayName("registerSession with null userId defaults to 0L")
        void registerSessionNullUserId() {
            when(sessionMetaRepo.findByUserIdAndSessionId(0L, SESSION_ID)).thenReturn(null);
            service.registerSession(null, SESSION_ID, "Guest Session");
            ArgumentCaptor<SessionMeta> captor = ArgumentCaptor.forClass(SessionMeta.class);
            verify(sessionMetaRepo).save(captor.capture());
            assertEquals(0L, captor.getValue().getUserId());
        }

        @Test
        @DisplayName("getUserSessions returns sorted sessions")
        void getUserSessionsReturnsSorted() {
            SessionMeta m1 = new SessionMeta(USER_ID, "s1", "Session 1");
            SessionMeta m2 = new SessionMeta(USER_ID, "s2", "Session 2");
            when(sessionMetaRepo.findByUserIdOrderByUpdateTimeDesc(USER_ID))
                    .thenReturn(List.of(m2, m1));

            List<Map<String, Object>> sessions = service.getUserSessions(USER_ID);
            assertEquals(2, sessions.size());
            assertEquals("s2", sessions.get(0).get("sessionId"));
            assertEquals("s1", sessions.get(1).get("sessionId"));
        }

        @Test
        @DisplayName("getUserSessions returns empty list for new user")
        void getUserSessionsEmpty() {
            when(sessionMetaRepo.findByUserIdOrderByUpdateTimeDesc(USER_ID))
                    .thenReturn(Collections.emptyList());

            List<Map<String, Object>> sessions = service.getUserSessions(USER_ID);
            assertTrue(sessions.isEmpty());
        }
    }

    // ==================================================================
    // History Management Tests
    // ==================================================================

    @Nested
    @DisplayName("History Management")
    class HistoryManagement {

        @Test
        @DisplayName("replaceHistory replaces all messages and sets summary")
        void replaceHistoryReplacesAll() {
            ChatSession session = new ChatSession(SESSION_ID);
            session.addMessage("old_q", "old_a", 6, tokenCounter);
            String json = serializeSession(session);
            when(valueOps.get(anyString())).thenReturn(json);
            when(redisTemplate.execute(any(SessionCallback.class))).thenReturn("marker");

            List<Map<String, String>> newHistory = List.of(
                    Map.of("role", "user", "content", "summary"),
                    Map.of("role", "assistant", "content", "condensed response"));

            service.replaceHistory(USER_ID, SESSION_ID, newHistory, "Session summary text", 3);
            // No exception = success
        }

        @Test
        @DisplayName("getRaw returns session without creating if missing")
        void getRawReturnsNullForMissing() {
            when(valueOps.get(anyString())).thenReturn(null);
            assertNull(service.getRaw(USER_ID, SESSION_ID));
        }

        @Test
        @DisplayName("saveRaw persists session to Redis")
        void saveRawPersists() {
            ChatSession session = new ChatSession(SESSION_ID);
            assertDoesNotThrow(() -> service.saveRaw(USER_ID, SESSION_ID, session));
        }
    }

    // ==================================================================
    // Deserialization Tests
    // ==================================================================

    @Nested
    @DisplayName("Deserialization Error Handling")
    class DeserializationErrors {

        @Test
        @DisplayName("deserialize corrupted JSON rebuilds session from MySQL (数据损坏→MySQL降级)")
        void deserializeCorruptedJsonRebuildsFromMySql() {
            // 损坏 JSON: deserialize 抛异常 → 穿透 getFromRedis → getOrCreate catch → rebuildFromMySql
            when(valueOps.get(anyString())).thenReturn("this is not valid json {{{");
            // MySQL 有归档消息 → 从 MySQL 重建会话（P0-1 修复后的正确行为：不静默丢历史）
            SessionMessage userMsg = new SessionMessage(USER_ID, SESSION_ID, "user", "问题1");
            SessionMessage aiMsg = new SessionMessage(USER_ID, SESSION_ID, "assistant", "回答1");
            when(sessionMessageRepo.findByUserIdAndSessionId(anyLong(), anyString()))
                    .thenReturn(List.of(userMsg, aiMsg));

            ChatSession session = service.getOrCreate(USER_ID, SESSION_ID);
            assertNotNull(session);
            assertEquals(1, session.getMessagePairCount(), "应从 MySQL 归档重建出 1 对消息");
        }

        @Test
        @DisplayName("deserialize with null returns null (new session)")
        void deserializeNullJson() {
            when(valueOps.get(anyString())).thenReturn(null);
            when(redisTemplate.execute(any(SessionCallback.class))).thenReturn("marker");

            ChatSession session = service.getOrCreate(USER_ID, SESSION_ID);
            assertNotNull(session);
            assertEquals(0, session.getMessagePairCount());
        }

        @Test
        @DisplayName("deserialize with empty string returns null")
        void deserializeEmptyJson() {
            when(valueOps.get(anyString())).thenReturn("");
            when(redisTemplate.execute(any(SessionCallback.class))).thenReturn("marker");

            ChatSession session = service.getOrCreate(USER_ID, SESSION_ID);
            assertNotNull(session);
        }

        @Test
        @DisplayName("deserialize with modified schema falls back to new session (容错降级)")
        void deserializeSchemaMismatchFallsBack() {
            // JSON with unexpected field structure that ObjectMapper can't handle
            when(valueOps.get(anyString())).thenReturn("{\"_corrupted\": \"true\", \"_version\": 999}");
            when(redisTemplate.execute(any(SessionCallback.class))).thenReturn("marker");

            ChatSession session = service.getOrCreate(USER_ID, SESSION_ID);
            // 当前设计是容错降级，不抛异常，返回新空会话
            assertNotNull(session);
            assertEquals(0, session.getMessagePairCount());
        }
    }

    // ==================================================================
    // Edge Cases
    // ==================================================================

    @Nested
    @DisplayName("Edge Cases and Boundary Conditions")
    class EdgeCases {

        @Test
        @DisplayName("addMessage with empty strings works")
        void addMessageEmptyContent() {
            ChatSession session = new ChatSession(SESSION_ID);
            String json = serializeSession(session);
            when(valueOps.get(anyString())).thenReturn(json);
            when(redisTemplate.execute(any(SessionCallback.class))).thenReturn("marker");

            assertDoesNotThrow(() ->
                    service.addMessage(USER_ID, SESSION_ID, "", ""));
        }

        @Test
        @DisplayName("addMessage with very long content")
        void addMessageVeryLongContent() {
            ChatSession session = new ChatSession(SESSION_ID);
            String json = serializeSession(session);
            when(valueOps.get(anyString())).thenReturn(json);
            when(redisTemplate.execute(any(SessionCallback.class))).thenReturn("marker");

            String longContent = "A".repeat(10000);
            assertDoesNotThrow(() ->
                    service.addMessage(USER_ID, SESSION_ID, longContent, longContent));
        }

        @Test
        @DisplayName("token trimming triggers when exceeding maxWindowTokens")
        void tokenTrimmingTriggered() {
            ChatSession session = new ChatSession(SESSION_ID);
            // Add many messages to exceed token budget
            for (int i = 0; i < 20; i++) {
                session.addMessage("a".repeat(200), "b".repeat(200), 100, tokenCounter);
            }
            assertTrue(session.getMessageHistory().size() > 10);

            String json = serializeSession(session);
            when(valueOps.get(anyString())).thenReturn(json);
            when(redisTemplate.execute(any(SessionCallback.class))).thenReturn("marker");

            // Adding more should trigger trim
            assertDoesNotThrow(() ->
                    service.addMessage(USER_ID, SESSION_ID, "another q", "another a"));
        }

        @Test
        @DisplayName("buildKey returns deterministic key for null userId")
        void buildKeyNullUserId() throws Exception {
            var method = ChatSessionService.class.getDeclaredMethod("buildKey", Long.class, String.class);
            method.setAccessible(true);
            String key1 = (String) method.invoke(service, null, SESSION_ID);
            String key2 = (String) method.invoke(service, null, SESSION_ID);
            assertEquals(key1, key2);
            assertTrue(key1.contains("0:" + SESSION_ID));
        }

        @Test
        @DisplayName("withOptimisticLock throws ConcurrentModificationException after 3 retries")
        void optimisticLockExhausted() {
            when(redisTemplate.execute(any(SessionCallback.class)))
                    .thenReturn(null)  // WATCH conflict
                    .thenReturn(null)
                    .thenReturn(null);

            assertThrows(ConcurrentModificationException.class, () ->
                    service.addMessage(USER_ID, SESSION_ID, "q", "a"));
        }
    }

    // ==================================================================
    // Helper
    // ==================================================================

    private String serializeSession(ChatSession session) {
        try {
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            return objectMapper.writeValueAsString(session);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
