package com.gagneflow.service.lesson;

import com.gagneflow.config.PipelineStageConfig;
import com.gagneflow.dto.LessonPlanRequest;
import com.gagneflow.service.document.SubjectFormatLoader;
import com.gagneflow.service.metrics.PipelineMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("AddrfPipeline five-stage orchestration")
class AddrfPipelineOrchestrationTest {

    private SubjectFormatLoader mockSfl() {
        SubjectFormatLoader sfl = mock(SubjectFormatLoader.class);
        when(sfl.getAnalysisExtra(any())).thenReturn("");
        when(sfl.getDesignExtra(any())).thenReturn("");
        when(sfl.getDevelopmentInstructions(any())).thenReturn("");
        when(sfl.getStageInstructions(any())).thenReturn("");
        return sfl;
    }

    private FormatTool mockFormatTool() {
        FormatTool ft = mock(FormatTool.class);
        when(ft.format(any(), any(), any(), any(), any())).thenReturn("<html>test</html>");
        when(ft.formatDirect(anyString(), any())).thenReturn("<html>direct</html>");
        return ft;
    }

    private AddrfPipeline newPipeline() {
        return newPipelineWith(mockFormatTool());
    }

    private AddrfPipeline newPipelineWith(FormatTool ft) {
        // personalizationService: mock 返回空(默认不注入个性化, 保持原行为)
        PersonalizationContextService pcs = mock(PersonalizationContextService.class);
        when(pcs.getContext(any(), any(), any())).thenReturn("");
        return new AddrfPipeline(null, null, null, null, null, ft, mockSfl(),
                new PipelineStageConfig(), pcs, null, null, null);
    }

    private LessonPlanRequest newRequest() {
        LessonPlanRequest req = new LessonPlanRequest();
        req.setStage("xiao xue");
        req.setGrade(3);
        req.setSubject("shu xue");
        req.setHours(1);
        req.setGoals("zhang wo fen shu gai nian");
        return req;
    }

    private ChatResponse text(String content) {
        return ChatResponse.builder().generations(List.of(
                new Generation(new AssistantMessage("总分: 85\n" + content)))).build();
    }

    @Test
    void executeHappyPath_producesAllStages() {
        AddrfPipeline pipeline = newPipeline();
        ChatModelPort port = mock(ChatModelPort.class);
        when(port.stream(any(Prompt.class)))
                .thenAnswer(inv -> Flux.just(text("stage output")));

        AddrfPipeline.AddrfResult result = pipeline.execute(
                newRequest(), port, null, "quick", null, "", 1L, "session-test");

        assertNotNull(result.analysis, "analysis should be produced");
        assertNotNull(result.design, "design should be produced");
        assertNotNull(result.development, "development should be produced");
        pipeline.awaitReview(result, 10);
        assertNotNull(result.review, "review should be produced async");
        verify(port, atLeast(2)).stream(any(Prompt.class));
    }

    @Test
    void execute_streamFails_fallsBackToCall() {
        AddrfPipeline pipeline = newPipeline();
        ChatModelPort port = mock(ChatModelPort.class);
        when(port.stream(any(Prompt.class))).thenThrow(new RuntimeException("stream failed"));
        when(port.call(any(Prompt.class))).thenReturn(text("call fallback"));

        AddrfPipeline.AddrfResult result = pipeline.execute(
                newRequest(), port, null, "quick", null, "", 1L, "session-test");
        pipeline.awaitReview(result, 10);

        assertNotNull(result.analysis, "call fallback should produce analysis");
        verify(port, atLeast(1)).call(any(Prompt.class));
    }

    @Test
    void execute_analysisCacheHit_skipsAnalysisCall() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn("cached analysis");

        AddrfPipeline pipeline = new AddrfPipeline(null, null, null, null, null, mockFormatTool(), mockSfl(),
                new PipelineStageConfig(), mock(PersonalizationContextService.class), null, redis, null);
        ChatModelPort port = mock(ChatModelPort.class);
        when(port.stream(any(Prompt.class)))
                .thenAnswer(inv -> Flux.just(text("stage output")));

        AddrfPipeline.AddrfResult result = pipeline.execute(
                newRequest(), port, null, "quick", null, "", 1L, "session-test");
        pipeline.awaitReview(result, 10);

        assertNotNull(result.analysis);
        assertEquals("cached analysis", result.analysis, "analysis should hit cache");
        verify(port, atLeast(1)).stream(any(Prompt.class));
    }

    @Test
    void execute_quickMode_noCopilotBlocking() {
        AddrfPipeline pipeline = newPipeline();
        ChatModelPort port = mock(ChatModelPort.class);
        when(port.stream(any(Prompt.class)))
                .thenAnswer(inv -> Flux.just(text("quick output")));

        AddrfPipeline.AddrfResult result = pipeline.execute(
                newRequest(), port, null, "quick", new ConcurrentHashMap<>(), "", 1L, "session-test");
        pipeline.awaitReview(result, 10);

        assertNotNull(result.analysis);
        assertNotNull(result.design);
        assertNotNull(result.development);
        assertNotNull(result.html, "format should produce html");
    }

    @Test
    void executeQuick_singleCall_noReview_noBackfill() {
        AddrfPipeline pipeline = newPipeline();
        ChatModelPort port = mock(ChatModelPort.class);
        when(port.stream(any(Prompt.class)))
                .thenAnswer(inv -> Flux.just(text("## 教学目标\n- 知识: 分数\n## 教学过程\n- 例题: 1+1")));

        // 快速直出: 一次调用 + 只推 stage:format, 不产生 review/HITL/评分窗口
        AddrfPipeline.AddrfResult result = pipeline.executeQuick(
                newRequest(), port, null, "", 1L, "session-quick");

        assertNotNull(result.html, "quick should produce html via format");
        assertNotNull(result.development, "quick should store markdown in development");
        assertFalse(result.needsHumanReview, "quick 不触发 HITL");
        assertFalse(result.needsSafetyReview, "quick 不触发安全类阻断");
        assertEquals(0, result.score, "quick 不评 LLM 分");
        assertFalse(result.scheduleBackfill, "quick 不回灌");
        // 单次调用直出, 不拆 Analysis/Design/Development, 也不走 Review 二次生成
        verify(port, times(1)).stream(any(Prompt.class));
    }

    @Test
    void executeQuick_directHtml_noMarkdownFallback() {
        FormatTool ft = mockFormatTool();
        AddrfPipeline pipeline = newPipelineWith(ft);
        ChatModelPort port = mock(ChatModelPort.class);
        when(port.stream(any(Prompt.class)))
                .thenAnswer(inv -> Flux.just(text("<h2>教学目标</h2><p>知识: 分数</p><table><tr><td>1</td></tr></table>")));

        // LLM 遵 HTML 约束 -> 走 formatDirect, 不降级 MD
        AddrfPipeline.AddrfResult result = pipeline.executeQuick(
                newRequest(), port, null, "", 1L, "session-quick-html");

        assertNotNull(result.html, "quick direct html should produce html");
        verify(ft, never()).format(any(), any(), any(), any(), any());
        verify(ft, times(1)).formatDirect(anyString(), any());
        assertEquals(0, pipeline.getQuickMarkdownFallbackCount(), "直出HTML不应计入MD降级");
    }

    @Test
    void executeQuick_markdownFallback_recordsMetric() {
        // 2026-08-23: MD 降级应对 PipelineMetrics.recordQuickFallback() 计一次
        PipelineMetrics pm = mock(PipelineMetrics.class);
        FormatTool ft = mockFormatTool();
        AddrfPipeline pipeline = newPipelineWith(ft);
        ReflectionTestUtils.setField(pipeline, "pipelineMetrics", pm);
        ChatModelPort port = mock(ChatModelPort.class);
        // 含 MD 特征(行首 # + 裸 **) -> 触发降级到 MD 路径
        when(port.stream(any(Prompt.class)))
                .thenAnswer(inv -> Flux.just(text("## 教学目标\n- **重点**: 分数\n")));

        AddrfPipeline.AddrfResult result = pipeline.executeQuick(
                newRequest(), port, null, "", 1L, "session-quick-md");

        assertNotNull(result.html, "MD 降级仍应产出 html");
        assertEquals(1, pipeline.getQuickMarkdownFallbackCount(), "应计一次 MD 降级");
        verify(pm, times(1)).recordQuickFallback();
        verify(ft, times(1)).format(any(), any(), any(), any(), any());
        verify(ft, never()).formatDirect(anyString(), any());
    }
}