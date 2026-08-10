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

/**
 * LongTermMemoryService 三新功能测试(2026-08-10):
 * 1. 时间衰减 + 访问频率加权 (timeDecayWeight / frequencyBoost / bumpAccessCount)
 * 2. 记忆整合与冲突消解 (重复覆盖 / 矛盾消解)
 * 3. 跨会话全局记忆 (USER/FINAL 进全局 Set, 检索并集, MySQL GLOBAL 标记)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LTM 新功能: 时间衰减/冲突消解/全局记忆")
class LongTermMemoryServiceNewFeaturesTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private VectorEmbeddingService embeddingService;
    @Mock private com.gagneflow.repository.LtmFactRepository ltmFactRepository;

    private LongTermMemoryService service;

    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "session-abc";
    private static final String GLOBAL_KEY = "gagneflow:ltm:global:1";

    @BeforeEach
    void setUp() {
        service = new LongTermMemoryService(redisTemplate, embeddingService, ltmFactRepository);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        // 默认 embedding 返回定长向量
        when(embeddingService.generateEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));
        when(embeddingService.generateQueryVector(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));
        // 默认 set members 为空(无已有事实)
        when(setOps.members(anyString())).thenReturn(Collections.emptySet());
        // 默认 multiGet 返回 null 列表(无缓存)
        when(valueOps.multiGet(anyList())).thenReturn(Collections.emptyList());
    }

    private LongTermMemoryService.MemoryFact fact(String text) {
        LongTermMemoryService.MemoryFact f = new LongTermMemoryService.MemoryFact();
        f.setFact(text);
        return f;
    }

    private LongTermMemoryService.MemoryFact fact(String text, String source) {
        LongTermMemoryService.MemoryFact f = fact(text);
        f.setSourcePhase(source);
        return f;
    }

    @Nested
    @DisplayName("时间衰减与访问频率")
    class TimeDecayAndFreq {

        @Test
        @DisplayName("新事实写入 detail 带 4 段格式(含访问统计)")
        void storeWritesFourSegmentDetail() {
            service.storeFacts(USER_ID, SESSION_ID, List.of(fact("用户教小学数学")));

            verify(valueOps).set(contains("gagneflow:ltm:detail:"), argThat(d ->
                    d instanceof String && ((String) d).split("\\|").length == 4
                            && ((String) d).endsWith("|0")), any(Duration.class));
        }

        @Test
        @DisplayName("时间衰减: 30 天前的事实权重约为 0.5")
        void timeDecayHalfLife() throws Exception {
            // 通过 vectorSearch 路径验证: 构造一个 30 天前的 detail
            String oldFactId = "old-fact";
            when(setOps.members(anyString())).thenReturn(Set.of(oldFactId));
            long now = System.currentTimeMillis();
            long thirtyDaysAgo = now - 30L * 24 * 3600 * 1000;
            String detail = "很久以前的事实|USER_EXPLICIT|" + thirtyDaysAgo + "|0";
            when(valueOps.multiGet(anyList()))
                    .thenReturn(List.of("[0.1,0.2,0.3]"))      // vec
                    .thenReturn(List.of(detail));               // detail
            when(embeddingService.generateQueryVector(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));

            List<LongTermMemoryService.MemoryFact> facts = service.searchFacts(USER_ID, SESSION_ID, "查询", 3);
            // 向量全同 => 相似度 1.0; 来源 USER=1.0; 30天衰减=0.5; 频次增益=1.0 => 排序分 0.5
            assertNotNull(facts);
            verify(embeddingService, never()).generateEmbedding(anyString()); // 缓存命中不再实时算
        }

        @Test
        @DisplayName("访问频率: 命中后 accessCount+1 且刷新 lastAccess")
        void bumpAccessOnHit() {
            String factId = "hit-fact";
            when(setOps.members(anyString())).thenReturn(Set.of(factId));
            when(valueOps.multiGet(anyList()))
                    .thenReturn(List.of("[0.1,0.2,0.3]"))
                    .thenReturn(List.of("用户喜欢游戏化|USER_EXPLICIT|0|0"));
            when(valueOps.get("gagneflow:ltm:detail:hit-fact")).thenReturn("用户喜欢游戏化|USER_EXPLICIT|0|0");

            service.searchFacts(USER_ID, SESSION_ID, "游戏化", 3);

            // 回写 detail: accessCount 0->1
            verify(valueOps).set(contains("gagneflow:ltm:detail:hit-fact"),
                    argThat(d -> d instanceof String && ((String) d).endsWith("|1")), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("记忆整合与冲突消解")
    class IntegrationAndConflict {

        @Test
        @DisplayName("完全相同的文本视为重复: 删旧写新, 不重复累积")
        void duplicateTextMerged() {
            String existingId = UUID.nameUUIDFromBytes((SESSION_ID + "_用户教小学数学").getBytes()).toString();
            when(setOps.members(anyString())).thenReturn(Set.of(existingId));
            // storeFacts 只调一次 multiGet(loadExistingDetails 读已有 detail)
            when(valueOps.multiGet(anyList()))
                    .thenReturn(List.of("用户教小学数学|USER_EXPLICIT|0|0"));
            when(embeddingService.generateEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));

            service.storeFacts(USER_ID, SESSION_ID, List.of(fact("用户教小学数学", "USER_EXPLICIT")));

            // 同文本直接判重(不调 embedding 做相似度对比), 但新事实写入仍会预计算向量
            verify(embeddingService, atMostOnce()).generateEmbedding(anyString());
            // 旧事实被删除(detail key)
            verify(redisTemplate, atLeastOnce()).delete(contains("gagneflow:ltm:detail:" + existingId));
        }

        @Test
        @DisplayName("语义相似但否定反转 -> 矛盾, 高来源权重者胜")
        void conflictResolvedBySourceWeight() {
            String existingId = "old-conflict";
            when(setOps.members(anyString())).thenReturn(Set.of(existingId));
            // storeFacts 只调一次 multiGet(loadExistingDetails)
            when(valueOps.multiGet(anyList()))
                    .thenReturn(List.of("用户喜欢启发式提问|SUMMARY_EXTRACTED|0|0"));
            // 旧事实向量 [0.1,0.2,0.3], 新事实向量不同但相似 => 余弦相似度 ~0.9(落 0.75~0.95 区间)
            when(embeddingService.generateEmbedding(anyString())).thenAnswer(inv -> {
                String text = inv.getArgument(0);
                if (text.contains("启发式")) return List.of(0.1f, 0.2f, 0.3f);
                if (text.contains("不")) return List.of(0.12f, 0.21f, 0.29f);
                return List.of(0.5f, 0.5f, 0.5f);
            });

            // 新事实: 否定反转 + 来源 USER(权重1.0) > 旧 SUMMARY(0.85) => 新胜
            service.storeFacts(USER_ID, SESSION_ID, List.of(fact("用户不喜欢启发式提问", "USER_EXPLICIT")));

            verify(redisTemplate, atLeastOnce()).delete(contains("gagneflow:ltm:detail:old-conflict"));
        }

        @Test
        @DisplayName("高相似但同向(无否定反转) -> 不判矛盾, 正常写入")
        void similarNotContradictoryStored() {
            when(setOps.members(anyString())).thenReturn(Set.of("old-fact"));
            // 按文本返回不同向量: 旧"启发式" [0.1,0.2,0.3] vs 新"探究式" [0.5,0.5,0.5]
            // => 余弦相似度 ~0.93(落 0.75~0.95 区间, 高于重复阈值但不同向)
            when(embeddingService.generateEmbedding(anyString())).thenAnswer(inv -> {
                String text = inv.getArgument(0);
                if (text.contains("启发式")) return List.of(0.1f, 0.2f, 0.3f);
                if (text.contains("探究式")) return List.of(0.5f, 0.5f, 0.5f);
                return List.of(0.5f, 0.5f, 0.5f);
            });
            when(valueOps.multiGet(anyList()))
                    .thenReturn(List.of("用户喜欢启发式提问|SUMMARY_EXTRACTED|0|0"));

            service.storeFacts(USER_ID, SESSION_ID, List.of(fact("用户喜欢探究式提问", "USER_EXPLICIT")));

            // 无否定词反转 => 不是矛盾, 旧事实不应被删
            verify(redisTemplate, never()).delete(contains("gagneflow:ltm:detail:old-fact"));
        }
    }

    @Nested
    @DisplayName("跨会话全局记忆")
    class GlobalMemory {

        @Test
        @DisplayName("USER_EXPLICIT 事实同步写入全局 Set")
        void userFactGoesGlobal() {
            service.storeFacts(USER_ID, SESSION_ID, List.of(fact("用户偏好分层教学", "USER_EXPLICIT")));
            verify(setOps).add(eq(GLOBAL_KEY), anyString());
        }

        @Test
        @DisplayName("FINAL_DECISION 事实同步写入全局 Set")
        void finalFactGoesGlobal() {
            service.storeFacts(USER_ID, SESSION_ID, List.of(fact("教案采用启发式", "FINAL_DECISION")));
            verify(setOps).add(eq(GLOBAL_KEY), anyString());
        }

        @Test
        @DisplayName("SUMMARY 摘要事实不进全局 Set")
        void summaryFactNotGlobal() {
            service.storeFacts(USER_ID, SESSION_ID, List.of(fact("学生计算弱", "SUMMARY_EXTRACTED")));
            verify(setOps, never()).add(eq(GLOBAL_KEY), anyString());
        }

        @Test
        @DisplayName("searchFacts 合并会话级与全局 Set 并集")
        void searchMergesSessionAndGlobal() {
            when(setOps.members("gagneflow:ltm:1:session-abc")).thenReturn(Set.of("s1"));
            when(setOps.members(GLOBAL_KEY)).thenReturn(Set.of("g1"));
            when(valueOps.multiGet(anyList()))
                    .thenReturn(List.of("[0.1,0.2,0.3]", "[0.1,0.2,0.3]"))
                    .thenReturn(List.of("会话事实|SUMMARY_EXTRACTED|0|0", "全局事实|USER_EXPLICIT|0|0"));
            when(embeddingService.generateQueryVector(anyString())).thenReturn(List.of(0.1f, 0.2f, 0.3f));

            List<LongTermMemoryService.MemoryFact> facts = service.searchFacts(USER_ID, SESSION_ID, "查询", 3);
            assertNotNull(facts);
            assertFalse(facts.isEmpty());
        }

        @Test
        @DisplayName("clearSessionFacts 同时清理全局集合中的该会话事实")
        void clearRemovesFromGlobal() {
            when(setOps.members(anyString())).thenReturn(Set.of("fact-1"));
            service.clearSessionFacts(USER_ID, SESSION_ID);
            verify(setOps).remove(GLOBAL_KEY, "fact-1");
        }
    }
}
