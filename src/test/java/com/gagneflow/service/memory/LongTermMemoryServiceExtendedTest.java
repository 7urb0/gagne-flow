package com.gagneflow.service.memory;

import com.gagneflow.service.vector.VectorEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.*;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LongTermMemoryService Extended Tests")
class LongTermMemoryServiceExtendedTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private VectorEmbeddingService embeddingService;
    @Mock private com.gagneflow.repository.LtmFactRepository ltmFactRepository;

    private LongTermMemoryService service;

    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "session-123";

    @BeforeEach
    void setUp() {
        service = new LongTermMemoryService(redisTemplate, embeddingService, ltmFactRepository);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
    }

    private LongTermMemoryService.MemoryFact fact(String text) {
        LongTermMemoryService.MemoryFact f = new LongTermMemoryService.MemoryFact();
        f.setFact(text);
        return f;
    }

    @Nested
    @DisplayName("Fact Storage and Retrieval")
    class FactStorage {

        @Test
        @DisplayName("storeFacts persists fact to Redis set and detail key")
        void storeFactPersists() {
            service.storeFacts(USER_ID, SESSION_ID, List.of(fact("用户是小学三年级语文教师")));

            verify(setOps).add(anyString(), anyString());
            // detail key + 向量缓存 key 各一次
            verify(valueOps, times(2)).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("storeFacts with null userId defaults to 0")
        void storeFactNullUserId() {
            service.storeFacts(null, SESSION_ID, List.of(fact("测试事实")));
            verify(setOps).add(contains(":session-123"), anyString());
        }

        @Test
        @DisplayName("storeFacts with empty list does nothing")
        void storeFactEmptyList() {
            service.storeFacts(USER_ID, SESSION_ID, Collections.emptyList());
            verify(setOps, never()).add(anyString(), anyString());
        }

        @Test
        @DisplayName("storeFacts with null list does nothing")
        void storeFactNullList() {
            service.storeFacts(USER_ID, SESSION_ID, null);
            verify(setOps, never()).add(anyString(), anyString());
        }

        @Test
        @DisplayName("storeFacts pre-computes and caches vector")
        void storeFactCachesVector() throws Exception {
            when(embeddingService.generateEmbedding(anyString()))
                    .thenReturn(List.of(0.1f, 0.2f, 0.3f));

            service.storeFacts(USER_ID, SESSION_ID, List.of(fact("用户偏好小组讨论")));

            verify(valueOps, atLeast(2)).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("storeFacts persists to MySQL as Redis loss fallback (P0)")
        void storeFactPersistsToMySql() {
            service.storeFacts(USER_ID, SESSION_ID, List.of(fact("用户偏好小组讨论")));

            verify(ltmFactRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("storeFacts MySQL write failure does not break main flow (P0)")
        void storeFactMySqlFailureTolerated() {
            doThrow(new RuntimeException("db down"))
                    .when(ltmFactRepository).saveAll(anyList());

            service.storeFacts(USER_ID, SESSION_ID, List.of(fact("用户偏好小组讨论")));

            verify(setOps).add(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Fact Search")
    class FactSearch {

        @Test
        @DisplayName("searchFacts with no facts returns empty list")
        void searchFactsNoneExist() {
            when(setOps.members(anyString())).thenReturn(Collections.emptySet());

            List<LongTermMemoryService.MemoryFact> facts =
                    service.searchFacts(USER_ID, SESSION_ID, "anything", 3);
            assertNotNull(facts);
            assertTrue(facts.isEmpty());
        }

        @Test
        @DisplayName("searchFacts returns empty when Redis members null")
        void searchFactsNullMembers() {
            when(setOps.members(anyString())).thenReturn(null);

            List<LongTermMemoryService.MemoryFact> facts =
                    service.searchFacts(USER_ID, SESSION_ID, "test", 3);
            assertNotNull(facts);
            assertTrue(facts.isEmpty());
        }

        @Test
        @DisplayName("searchFacts falls back to keyword search when embedding fails")
        void keywordFallbackOnEmbeddingFailure() {
            when(setOps.members(anyString())).thenReturn(Set.of("fact-001"));
            // detail key 命中, vector key 未命中 (multiGet 中 null 表示 key 不存在)
            when(valueOps.multiGet(anyList()))
                    .thenReturn(Collections.singletonList(null))
                    .thenReturn(Collections.singletonList("海淀区小学语文教师"));
            when(embeddingService.generateQueryVector(anyString()))
                    .thenThrow(new RuntimeException("Embedding API unavailable"));
            when(valueOps.get("gagneflow:ltm:detail:fact-001"))
                    .thenReturn("海淀区小学语文教师");

            List<LongTermMemoryService.MemoryFact> facts =
                    service.searchFacts(USER_ID, SESSION_ID, "海淀区", 3);
            // 不应抛异常，且关键词降级应命中
            assertNotNull(facts);
        }

        @Test
        @DisplayName("searchFacts uses cached vectors when available")
        void searchFactsUsesCachedVectors() throws Exception {
            String vecJson = "[0.1,0.2,0.3]";
            when(setOps.members(anyString())).thenReturn(Set.of("fact-001"));
            when(valueOps.multiGet(anyList()))
                    .thenReturn(List.of(vecJson))           // vec keys
                    .thenReturn(List.of("用户喜欢互动教学")); // detail keys
            when(embeddingService.generateQueryVector(anyString()))
                    .thenReturn(List.of(0.1f, 0.2f, 0.3f));

            List<LongTermMemoryService.MemoryFact> facts =
                    service.searchFacts(USER_ID, SESSION_ID, "互动教学", 3);
            assertNotNull(facts);
            // 缓存命中, 不再调用事实向量化
            verify(embeddingService, never())
                    .generateEmbedding(anyString());
        }
    }

    @Nested
    @DisplayName("Session Cleanup")
    class SessionCleanup {

        @Test
        @DisplayName("clearSessionFacts deletes detail and vector keys")
        void clearSessionFacts() {
            when(setOps.members(anyString())).thenReturn(Set.of("fact-001", "fact-002"));

            service.clearSessionFacts(USER_ID, SESSION_ID);

            verify(redisTemplate, atLeastOnce()).delete("gagneflow:ltm:detail:fact-001");
            verify(redisTemplate, atLeastOnce()).delete("gagneflow:ltm:vec:fact-002");
            verify(redisTemplate, atLeastOnce()).delete(anyString());
        }

        @Test
        @DisplayName("clearSessionFacts deletes MySQL persistence copy (P0)")
        void clearSessionFactsDeletesMySql() {
            when(setOps.members(anyString())).thenReturn(Set.of());

            service.clearSessionFacts(USER_ID, SESSION_ID);

            verify(ltmFactRepository).deleteByUserIdAndSessionId(USER_ID, SESSION_ID);
        }
    }

    @Nested
    @DisplayName("User Preference")
    class UserPreference {

        @Test
        @DisplayName("storeUserPreference saves to hash")
        void storePreference() {
            service.storeUserPreference(USER_ID, "语文", "三年级");

            verify(hashOps).putAll(eq("gagneflow:ltm:1:prefs"), anyMap());
        }
    }
}
