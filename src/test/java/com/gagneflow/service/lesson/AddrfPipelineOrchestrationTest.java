package com.gagneflow.service.lesson;

import com.gagneflow.config.PipelineStageConfig;
import com.gagneflow.dto.LessonPlanRequest;
import com.gagneflow.service.document.SubjectFormatLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
        when(ft.format(any(), any(), any(), any())).thenReturn("<html>test</html>");
        return ft;
    }

    private AddrfPipeline newPipeline() {
        return new AddrfPipeline(null, null, null, null, null, mockFormatTool(), mockSfl(),
                new PipelineStageConfig(), null, null);
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
                newRequest(), port, null, "quick", null, "", 1L);

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
                newRequest(), port, null, "quick", null, "", 1L);
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
                new PipelineStageConfig(), redis, null);
        ChatModelPort port = mock(ChatModelPort.class);
        when(port.stream(any(Prompt.class)))
                .thenAnswer(inv -> Flux.just(text("stage output")));

        AddrfPipeline.AddrfResult result = pipeline.execute(
                newRequest(), port, null, "quick", null, "", 1L);
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
                newRequest(), port, null, "quick", new ConcurrentHashMap<>(), "", 1L);
        pipeline.awaitReview(result, 10);

        assertNotNull(result.analysis);
        assertNotNull(result.design);
        assertNotNull(result.development);
        assertNotNull(result.html, "format should produce html");
    }
}