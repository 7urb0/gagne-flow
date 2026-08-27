package com.gagneflow;

import com.gagneflow.config.PipelineStageConfig;
import com.gagneflow.dto.LessonPlanRequest;
import com.gagneflow.service.lesson.AddrfPipeline;
import com.gagneflow.service.lesson.FormatTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试（无 Spring 上下文加载）：
 * 验证 PipelineStageConfig、AddrfPipeline、FormatTool 等核心组件
 * 在手动构造下的行为正确性。
 *
 * 注意：完整的 @SpringBootTest 集成需要 MySQL/Redis/Milvus 环境，
 * 在实际部署环境中可通过 Testcontainers 或在 CI 中运行。
 */
@DisplayName("Integration tests (standalone)")
class GagneFlowIntegrationTest {

    @Test
    @DisplayName("PipelineStageConfig has default stages configured")
    void pipelineStageConfigHasDefaultStages() {
        PipelineStageConfig config = new PipelineStageConfig();
        assertNotNull(config.getStages());
        assertFalse(config.getStages().isEmpty(),
            "Pipeline stages should not be empty");
        assertTrue(config.getStages().contains("analysis"),
            "analysis stage must be present");
        assertTrue(config.getStages().contains("design"),
            "design stage must be present");
        assertTrue(config.getStages().contains("development"),
            "development stage must be present");
    }

    @Test
    @DisplayName("FormatTool can render template without throwing")
    void formatToolRendersTemplate() {
        FormatTool formatTool = new FormatTool();
        String html = formatTool.format("分析内容", "设计内容", "开发内容", "评审意见");
        assertNotNull(html, "HTML output should not be null");
        assertTrue(html.length() > 50, "HTML output should be substantial");
        assertTrue(html.contains("分析内容"), "HTML should contain analysis content");
        // 2026-08-22: Review 不再写入教案正文(由 SSE stage:review 独立下发)
        assertFalse(html.contains("评审意见"), "HTML should NOT contain review content");
    }

    @Test
    @DisplayName("AddrfPipeline extractScore works with various formats")
    void addrfPipelineExtractScore() {
        AddrfPipeline pipeline = new AddrfPipeline(null, null, null, null, null, null, null,
            new PipelineStageConfig(), null, null, null, null);

        assertEquals(85, pipeline.extractScore("总分: 85\n内容良好"));
        assertEquals(75, pipeline.extractScore("总分 75\n评价通过"));
        assertEquals(95, pipeline.extractScore("{\"score\": 95}"));
        assertEquals(0, pipeline.extractScore(null));
        assertEquals(0, pipeline.extractScore("无分数信息"));
        assertEquals(0, pipeline.extractScore(""));
    }

    @Test
    @DisplayName("LessonPlanRequest validation works")
    void lessonPlanRequestValidation() {
        LessonPlanRequest req = new LessonPlanRequest();
        req.setStage("小学");
        req.setGrade(3);
        req.setSubject("数学");
        req.setHours(1);
        req.setGoals("掌握两位数乘法");

        assertEquals("小学", req.getStage());
        assertEquals(3, req.getGrade());
        assertEquals("数学", req.getSubject());
        assertEquals(1, req.getHours());
        assertEquals("掌握两位数乘法", req.getGoals());
    }

    @Test
    @DisplayName("AddrfPipeline.dedupContent removes duplicate sections")
    void addrfPipelineDedupContent() throws Exception {
        String content = "**教学目标**\n让学生掌握基础知识。\n\n"
                + "**教学目标**\n让学生掌握基础知识，并能灵活运用。\n\n"
                + "**教学重难点**\n重点是概念理解。\n";
        var method = AddrfPipeline.class.getDeclaredMethod("dedupContent", String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(null, content);
        assertTrue(result.contains("能灵活运用"), "应保留较长的版本");
    }
}
