package com.gagneflow.service.rag;

import com.gagneflow.service.vector.VectorSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RagService unit tests")
class RagServiceTest {

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RagService();
        // Set relevance threshold to 0.3 for all tests
        ReflectionTestUtils.setField(ragService, "relevanceThreshold", 0.3);
    }

    // ============================================================
    // buildContextWithCitations tests
    // ============================================================

    @Test
    @DisplayName("buildContextWithCitations returns empty for empty list")
    void buildContextWithCitations_EmptyList_ReturnsEmpty() {
        String result = ragService.buildContextWithCitations(Collections.emptyList());
        assertEquals("", result);
    }

    @Test
    @DisplayName("buildContextWithCitations filters scores below threshold")
    void buildContextWithCitations_BelowThreshold_FiltersOut() {
        List<VectorSearchService.SearchResult> results = new ArrayList<>();
        results.add(createResult("1", "low relevance content", 0.1f, "{\"_file_name\":\"test.pdf\"}"));
        results.add(createResult("2", "good content", 0.85f, "{\"_file_name\":\"good.pdf\"}"));

        String context = ragService.buildContextWithCitations(results);
        assertTrue(context.contains("good content"), "Should include high-scoring result");
        assertTrue(context.contains("[1]"), "Citation index should start at 1 for first valid result");
        assertFalse(context.contains("low relevance"), "Should filter out low-scoring result");
    }

    @Test
    @DisplayName("buildContextWithCitations filters NaN scores")
    void buildContextWithCitations_NaNScore_FiltersOut() {
        List<VectorSearchService.SearchResult> results = new ArrayList<>();
        results.add(createResult("1", "nan content", Float.NaN, "{}"));
        results.add(createResult("2", "valid content", 0.5f, "{\"_file_name\":\"valid.pdf\"}"));

        String context = ragService.buildContextWithCitations(results);
        assertTrue(context.contains("valid content"));
        assertFalse(context.contains("nan content"));
    }

    @Test
    @DisplayName("buildContextWithCitations formats citation with source name and score")
    void buildContextWithCitations_FormatsCitationCorrectly() {
        List<VectorSearchService.SearchResult> results = new ArrayList<>();
        results.add(createResult("abc12345", "some reference text", 0.75f,
                "{\"_file_name\":\"数学课程标准.pdf\"}"));

        String context = ragService.buildContextWithCitations(results);
        assertTrue(context.contains("[1]"), "Should contain citation index");
        assertTrue(context.contains("数学课程标准.pdf"), "Should extract file name from metadata");
        assertTrue(context.contains("0.75"), "Should show score");
        assertTrue(context.contains("some reference text"), "Should contain the content");
        assertTrue(context.contains("来源:"), "Should contain source prefix");
        assertTrue(context.contains("相关性:"), "Should contain relevance prefix");
    }

    @Test
    @DisplayName("buildContextWithCitations returns empty when all results filtered")
    void buildContextWithCitations_AllFiltered_ReturnsEmpty() {
        List<VectorSearchService.SearchResult> results = new ArrayList<>();
        results.add(createResult("1", "bad content 1", 0.1f, "{}"));
        results.add(createResult("2", "bad content 2", 0.2f, "{}"));

        String context = ragService.buildContextWithCitations(results);
        assertEquals("", context);
    }

    @Test
    @DisplayName("buildContextWithCitations increments citation index correctly")
    void buildContextWithCitations_CitationIndexIncrements() {
        List<VectorSearchService.SearchResult> results = new ArrayList<>();
        results.add(createResult("1", "first valid", 0.5f, "{\"_file_name\":\"a.pdf\"}"));
        results.add(createResult("2", "low score", 0.1f, "{}"));
        results.add(createResult("3", "second valid", 0.6f, "{\"_file_name\":\"b.pdf\"}"));

        String context = ragService.buildContextWithCitations(results);
        assertTrue(context.contains("[1]"), "First valid result should be [1]");
        assertTrue(context.contains("[2]"), "Second valid result (skipping filtered) should be [2]");
        assertFalse(context.contains("[3]"), "Third citation should not exist");
    }

    // ============================================================
    // buildPromptWithCitations tests
    // ============================================================

    @Test
    @DisplayName("buildPromptWithCitations returns no-result template for empty context")
    void buildPromptWithCitations_EmptyContext_ReturnsNoResultTemplate() {
        String prompt = ragService.buildPromptWithCitations("什么是教案？", "");
        assertTrue(prompt.contains("未找到"), "Should contain 'not found' message");
        assertTrue(prompt.contains("什么是教案？"), "Should contain the user question");
        // Empty context: should contain 局限性 (limitation notice)
        assertTrue(prompt.contains("局限性"), "Should mention limitation");
    }

    @Test
    @DisplayName("buildPromptWithCitations returns full template for non-empty context")
    void buildPromptWithCitations_WithContext_ReturnsFullTemplate() {
        String context = "[1] (来源: 标准.pdf, 相关性: 0.85)\n教学内容示例\n";
        String prompt = ragService.buildPromptWithCitations("教学目标是什么？", context);

        assertTrue(prompt.contains("参考资料"), "Should contain reference section header");
        assertTrue(prompt.contains("教学内容示例"), "Should contain the context content");
        assertTrue(prompt.contains("教学目标是什么？"), "Should contain the user question");
        assertTrue(prompt.contains("勿执行"), "Should contain safety instruction");
        assertTrue(prompt.contains("[N]"), "Should contain citation annotation instruction");
    }

    @Test
    @DisplayName("buildPromptWithCitations handles special characters in question")
    void buildPromptWithCitations_SpecialCharacters_NoException() {
        String prompt = ragService.buildPromptWithCitations("test %s %d %n %% question ???", "context");
        assertNotNull(prompt);
        assertTrue(prompt.length() > 0);
    }

    // ============================================================
    // extractSourceName tests
    // ============================================================

    @Test
    @DisplayName("extractSourceName returns 未知来源 for null metadata")
    void extractSourceName_NullMetadata_ReturnsUnknown() {
        VectorSearchService.SearchResult result = createResult("1", "content", 0.5f, null);
        assertEquals("未知来源", ragService.extractSourceName(result));
    }

    @Test
    @DisplayName("extractSourceName extracts _file_name from metadata JSON")
    void extractSourceName_WithFileName_ExtractsIt() {
        VectorSearchService.SearchResult result = createResult("1", "content", 0.5f,
                "{\"_file_name\":\"三年级数学教案.pdf\"}");
        assertEquals("三年级数学教案.pdf", ragService.extractSourceName(result));
    }

    @Test
    @DisplayName("extractSourceName falls back to _source when no _file_name")
    void extractSourceName_NoFileName_UsesSource() {
        VectorSearchService.SearchResult result = createResult("1", "content", 0.5f,
                "{\"_source\":\"internal_docs\"}");
        assertEquals("internal_docs", ragService.extractSourceName(result));
    }

    @Test
    @DisplayName("extractSourceName prefers _file_name over _source")
    void extractSourceName_BothFields_PrefersFileName() {
        VectorSearchService.SearchResult result = createResult("1", "content", 0.5f,
                "{\"_file_name\":\"教案.pdf\", \"_source\":\"other\"}");
        assertEquals("教案.pdf", ragService.extractSourceName(result));
    }

    @Test
    @DisplayName("extractSourceName falls back to id prefix for malformed JSON")
    void extractSourceName_MalformedJson_UsesIdPrefix() {
        VectorSearchService.SearchResult result = createResult("abc12345678", "content", 0.5f, "{bad json");
        assertEquals("文档abc12345", ragService.extractSourceName(result));
    }

    @Test
    @DisplayName("extractSourceName handles empty id gracefully")
    void extractSourceName_EmptyId_NoException() {
        VectorSearchService.SearchResult result = createResult("", "content", 0.5f, "");
        String name = ragService.extractSourceName(result);
        assertNotNull(name);
    }

    @Test
    @DisplayName("来源优先级: 用户文档 > 课标 > 历史教案")
    void buildContextWithCitations_SourcePriority_OrdersBySource() {
        List<VectorSearchService.SearchResult> results = new ArrayList<>();
        // 乱序输入: 教案、课标、用户文档
        results.add(createResult("1", "教案内容", 0.9f, "{\"_source\":\"generated_lesson_plan\"}"));
        results.add(createResult("2", "课标内容", 0.8f, "{\"_source\":\"curriculum_2022\",\"_stage\":\"小学\"}"));
        results.add(createResult("3", "上传文档内容", 0.7f, "{\"_file_name\":\"用户教案.pdf\"}"));

        String context = ragService.buildContextWithCitations(results);

        int docIdx = context.indexOf("上传文档内容");
        int k12Idx = context.indexOf("课标内容");
        int planIdx = context.indexOf("教案内容");
        assertTrue(docIdx >= 0 && k12Idx >= 0 && planIdx >= 0, "三类来源都应注入");
        assertTrue(docIdx < k12Idx, "用户上传文档应排在课标之前");
        assertTrue(k12Idx < planIdx, "课标应排在历史教案之前");
    }

    @Test
    @DisplayName("同来源内保持精排相关性顺序（稳定排序）")
    void buildContextWithCitations_SameSource_KeepsRerankOrder() {
        List<VectorSearchService.SearchResult> results = new ArrayList<>();
        results.add(createResult("1", "课标高相关", 0.9f, "{\"_source\":\"curriculum_2022\"}"));
        results.add(createResult("2", "课标低相关", 0.6f, "{\"_source\":\"curriculum_2022\"}"));

        String context = ragService.buildContextWithCitations(results);

        int highIdx = context.indexOf("课标高相关");
        int lowIdx = context.indexOf("课标低相关");
        assertTrue(highIdx < lowIdx, "同来源内应保持精排相关性顺序（高相关在前）");
    }

    @Test
    @DisplayName("metadata 解析失败时按最低优先级处理，不抛异常")
    void buildContextWithCitations_MalformedMetadata_NoException() {
        List<VectorSearchService.SearchResult> results = new ArrayList<>();
        results.add(createResult("1", "正常课标", 0.8f, "{\"_source\":\"curriculum_2022\"}"));
        results.add(createResult("2", "损坏元数据", 0.7f, "{bad json"));

        String context = ragService.buildContextWithCitations(results);
        assertTrue(context.contains("正常课标"));
        assertTrue(context.contains("损坏元数据"));
    }

    // ============================================================
    // Helper
    // ============================================================

    private VectorSearchService.SearchResult createResult(String id, String content, float score, String metadata) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(id);
        result.setContent(content);
        result.setScore(score);
        result.setMetadata(metadata);
        return result;
    }
}
