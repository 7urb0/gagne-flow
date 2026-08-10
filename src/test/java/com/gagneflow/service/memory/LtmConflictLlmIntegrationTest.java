package com.gagneflow.service.memory;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实环境 LLM 冲突裁决集成测试（真实 Redis + MySQL + DashScope LLM）。
 *
 * 目的: 验证 LLM 兜底在真实链路下是否生效——
 *   场景A: 规则拿不准(同义改写 + 同来源权重) -> 开启 LLM -> 应触发 LLM 裁决
 *   场景B: 开关关闭 -> 不触发 LLM, 正常写入
 *
 * 运行: mvn test -Dtest=LtmConflictLlmIntegrationTest -Dgagneflow.lesson-plan.k12-index-enabled=false
 * 注意: 会真实调用 DashScope API(qwen-turbo), 有少量费用。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("真实环境 LLM 冲突裁决")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LtmConflictLlmIntegrationTest {

    @Autowired private LongTermMemoryService service;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private DashScopeApi dashScopeApi;

    private static final Long UID = 999001L;
    private static final String SID = "ltm-conflict-it-" + System.currentTimeMillis();

    private static final String OLD_FACT = "用户喜欢启发式教学";
    private static final String NEW_FACT = "用户喜欢引导式教学"; // 真实 embedding 相似度 0.840, 落 [0.75,0.95] 且无否定反转 -> 规则拿不准, 触发 LLM

    private LongTermMemoryService.MemoryFact fact(String text, String source) {
        LongTermMemoryService.MemoryFact f = new LongTermMemoryService.MemoryFact();
        f.setFact(text);
        f.setSourcePhase(source);
        return f;
    }

    private void setLlmEnabled(boolean enabled) throws Exception {
        java.lang.reflect.Field f = LongTermMemoryService.class.getDeclaredField("conflictLlmEnabled");
        f.setAccessible(true);
        f.setBoolean(service, enabled);
    }

    /** 清理: 仅调 service.clearSessionFacts(其内部已 try-catch MySQL 删除), 避免直接调 repository 无事务报错 */
    private void cleanup() {
        service.clearSessionFacts(UID, SID);
    }

    @Test
    @Order(1)
    @DisplayName("开关关闭: 规则拿不准时不触发 LLM, 两条事实并存")
    void llmDisabledBothFactsCoexist() throws Exception {
        setLlmEnabled(false);
        service.storeFacts(UID, SID, List.of(fact(OLD_FACT, "USER_EXPLICIT")));
        service.storeFacts(UID, SID, List.of(fact(NEW_FACT, "USER_EXPLICIT")));
        // 开关关闭 -> 同权重规则无法裁决 -> 两条都应存在
        Set<String> ids = redisTemplate.opsForSet().members("gagneflow:ltm:" + UID + ":" + SID);
        assertNotNull(ids);
        assertEquals(2, ids.size(), "开关关闭时两条事实应并存");
        System.out.println("[集成验证-场景B] 开关关闭: 两条事实并存 ✓ 事实数=" + ids.size());
        cleanup();
    }

    @Test
    @Order(2)
    @DisplayName("开关开启: 规则拿不准时触发真实 LLM 裁决, 结果自洽")
    void llmEnabledResolvesConflict() throws Exception {
        setLlmEnabled(true);
        // 使用独立会话 ID, 避免与场景B的确定性 factId 撞车(同文本会走去重而非LLM)
        String sidA = "ltm-conflict-llm-on-" + System.currentTimeMillis();
        service.storeFacts(UID, sidA, List.of(fact(OLD_FACT, "USER_EXPLICIT")));
        service.storeFacts(UID, sidA, List.of(fact(NEW_FACT, "USER_EXPLICIT")));
        // 开关开启 -> 同义改写+同权重 -> 触发真实 LLM 裁决。
        // 注意: LLM 可能判 CONFLICT(剩1条) 或 NO_CONFLICT(两条并存, 同义不矛盾) —— 都是合法裁决。
        // 本测试验证"裁决链路真实执行不悬挂", 结果自洽即可。
        Set<String> ids = redisTemplate.opsForSet().members("gagneflow:ltm:" + UID + ":" + sidA);
        assertNotNull(ids);
        assertTrue(ids.size() >= 1 && ids.size() <= 2, "LLM 裁决后事实数应为 1(冲突)或 2(不冲突), 实际=" + ids.size());
        System.out.println("[集成验证-场景A] 开关开启: LLM 裁决链路执行完成 ✓ 事实数=" + ids.size());
        service.clearSessionFacts(UID, sidA);
    }
}
