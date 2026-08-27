package com.gagneflow.agent.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gagneflow.agent.tool.InternalDocsTools;
import com.gagneflow.service.chat.ChatService;
import com.gagneflow.service.memory.TokenCounter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AgentOrchestrator 单测: 全部通过函数式接口注入 fake LLM/执行器, 不触发真实调用。
 * 覆盖: 快捷路径 / 完整编排 / 强制 FETCH / 复核回溯 / 规划降级 / 模板缺失兜底 / 指标记录。
 */
class AgentOrchestratorTest {

    private ChatService chatService;
    private InternalDocsTools internalDocsTools;
    private AgentOrchestrator orchestrator;
    private final AtomicInteger execCalls = new AtomicInteger();
    private final java.util.List<String> execInputs = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        this.chatService = mock(ChatService.class);
        when(this.chatService.buildSystemPrompt(anyList(), any())).thenReturn("SYS_PROMPT");
        this.internalDocsTools = mock(InternalDocsTools.class);
        when(this.internalDocsTools.queryInternalDocs(anyString())).thenReturn(
                "{\"status\": \"ok\", \"data\": [{\"content\": \"课程标准片段\"}]}");
        this.orchestrator = new AgentOrchestrator(
                this.chatService, null, this.internalDocsTools,
                new TokenCounter(), "orchestrated", "nonexistent-prompt-dir");
        this.orchestrator.setLlmOverride(prompt -> "{\"decision\":\"EXECUTE\",\"step\":\"直接作答\"}");
        this.execCalls.set(0);
        this.execInputs.clear();
        this.orchestrator.setExecutorOverride((sys, input) -> {
            this.execCalls.incrementAndGet();
            this.execInputs.add(input);
            return "这是执行结果内容";
        });
    }

    private OrchestratorInput input(String q) {
        return new OrchestratorInput(q, 1L, "sess", List.of(), "");
    }

    @Test
    @DisplayName("简单问答(无计划关键词) -> 快捷路径 [EXECUTE], 不触发规划 LLM")
    void simpleQuestion_shortcutPath() {
        this.orchestrator.setLlmOverride(prompt -> {
            throw new AssertionError("快捷路径不应调用规划 LLM");
        });
        OrchestratorResult r = this.orchestrator.run(input("请问什么是电路?"));

        assertTrue(r.path().contains("EXECUTE"));
        assertFalse(r.path().contains("PLAN"), "快捷路径不应含 PLAN");
        assertEquals(1, this.execCalls.get());
        assertTrue(r.reviewPassed());
        assertNotNull(r.answer());
    }

    @Test
    @DisplayName("复杂任务 -> [PLAN, EXECUTE, REVIEW, FINISH], 复核 PASS")
    void complexTask_fullPath_passReview() {
        this.orchestrator.setLlmOverride(prompt -> prompt.contains("Review Agent")
                ? "{\"verdict\":\"PASS\",\"reason\":\"达标\"}"
                : "{\"decision\":\"EXECUTE\"}");
        OrchestratorResult r = this.orchestrator.run(input("请设计一堂数学课教案"));

        assertEquals(List.of("PLAN", "EXECUTE", "REVIEW", "FINISH"), r.path());
        assertTrue(r.reviewPassed());
        assertEquals(1, this.execCalls.get());
    }

    @Test
    @DisplayName("含检索关键词 -> 触发 FETCH, 执行器输入包含检索提炼上下文")
    void retrievalHint_triggersFetch() {
        this.orchestrator.setLlmOverride(prompt -> prompt.contains("Review Agent")
                ? "{\"verdict\":\"PASS\"}"
                : "{\"decision\":\"EXECUTE\"}");
        OrchestratorResult r = this.orchestrator.run(input("帮我设计教案, 需要查一下课程标准资料"));

        assertTrue(r.path().contains("FETCH"));
        assertTrue(r.path().indexOf("FETCH") < r.path().indexOf("EXECUTE"));
        assertTrue(this.execInputs.get(0).contains("参考资料"),
                "执行器输入应包含检索提炼上下文");
    }

    @Test
    @DisplayName("复核 FAIL -> 回溯重执行 1 次后 PASS(带审查意见)")
    void reviewFail_backtracksWithFixHint() {
        AtomicInteger reviewCalls = new AtomicInteger();
        this.orchestrator.setLlmOverride(prompt -> {
            if (prompt.contains("Review Agent")) {
                return reviewCalls.incrementAndGet() == 1
                        ? "{\"verdict\":\"FAIL\",\"reason\":\"教学目标不可衡量\"}"
                        : "{\"verdict\":\"PASS\"}";
            }
            return "{\"decision\":\"EXECUTE\"}";
        });
        OrchestratorResult r = this.orchestrator.run(input("请设计一节语文课教案"));

        assertEquals(List.of("PLAN", "EXECUTE", "REVIEW", "FIX", "REVIEW", "FINISH"), r.path());
        assertTrue(r.reviewPassed());
        assertEquals(2, this.execCalls.get(), "FAIL 后应回溯重执行一次");
        assertTrue(this.execInputs.get(1).contains("审查未通过"),
                "回溯输入的审查意见应传给执行器");
    }

    @Test
    @DisplayName("规划 LLM 返回空/异常 -> 降级 EXECUTE 不抛异常")
    void planFailure_degradesToExecute() {
        this.orchestrator.setLlmOverride(prompt -> "");
        OrchestratorResult r = this.orchestrator.run(input("请优化这份问卷"));

        assertTrue(r.path().contains("EXECUTE"));
        assertEquals(1, this.execCalls.get());
    }

    @Test
    @DisplayName("模板目录缺失 -> 内置兜底生效, 规划/复核仍可运行")
    void missingPromptDir_fallbackWorks() {
        this.orchestrator.setLlmOverride(prompt -> {
            assertTrue(prompt.contains("PLAN"), "兜底必须含决策类型定义");
            return prompt.contains("Review Agent") ? "{\"verdict\":\"PASS\"}" : "{\"decision\":\"EXECUTE\"}";
        });
        OrchestratorResult r = this.orchestrator.run(input("请设计一堂综合实践课教案"));
        assertTrue(r.path().contains("REVIEW"));
    }

    @Test
    @DisplayName("metrics 记录调用/路径分布")
    void metricsRecorded() {
        this.orchestrator.run(input("请设计一节历史课教案"));
        assertEquals(1, this.orchestrator.metrics().invocationCount());
        assertEquals(1, this.orchestrator.metrics().fullPathCount());
        assertEquals(0, this.orchestrator.metrics().shortcutPathCount());
        assertTrue(this.orchestrator.metrics().totalElapsedMs() >= 0);
    }

    @Test
    @DisplayName("mode=single 时 isEnabled=false")
    void singleMode_notEnabled() {
        AgentOrchestrator single = new AgentOrchestrator(
                this.chatService, null, this.internalDocsTools,
                new TokenCounter(), "single", "nonexistent");
        assertFalse(single.isEnabled());
    }

    @Test
    @DisplayName("真实模板目录下 retrieval 模板接线进 supervisor 规划上下文")
    void realPromptDir_retrievalGuideInjected() {
        AgentOrchestrator real = new AgentOrchestrator(
                this.chatService, null, this.internalDocsTools,
                new TokenCounter(), "orchestrated", "agent-config/prompts/v1");
        real.setExecutorOverride((sys, input) -> "结果");
        java.util.concurrent.atomic.AtomicReference<String> planPrompt = new java.util.concurrent.atomic.AtomicReference<>();
        real.setLlmOverride(prompt -> {
            if (prompt.contains("检索节点能力说明")) {
                planPrompt.set(prompt);
                return "{\"decision\":\"EXECUTE\"}";
            }
            return "{\"verdict\":\"PASS\"}";
        });
        real.run(input("请设计一节数学课教案"));

        assertNotNull(planPrompt.get(), "应触发 supervisor 规划 LLM");
        assertTrue(planPrompt.get().contains("Retrieval Agent"),
                "retrieval 模板应接线进规划上下文");
    }
}