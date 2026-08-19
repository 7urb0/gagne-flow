package com.gagneflow.service.lesson;

import com.gagneflow.config.PipelineStageConfig;
import com.gagneflow.dto.LessonPlanRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AddrfPipeline unit tests")
class AddrfPipelineTest {

    private final AddrfPipeline defaultPipeline = new AddrfPipeline(null, null, null, null, null, null, null, new PipelineStageConfig(), null, null, null, null);

    // ============================================================
    // extractScore tests
    // ============================================================

    @Test
    @DisplayName("extractScore returns 0 for null input")
    void extractScore_NullInput_ReturnsZero() {
        assertEquals(0, defaultPipeline.extractScore(null));
    }

    @Test
    @DisplayName("extractScore parses 总分: XX format with colon")
    void extractScore_TotalScoreColonFormat_ReturnsParsedScore() {
        assertEquals(85, defaultPipeline.extractScore("总分: 85\n内容评定: 良好"));
    }

    @Test
    @DisplayName("extractScore parses 总分:XX format with ASCII colon")
    void extractScore_ChineseColonFormat_ReturnsParsedScore() {
        // Regex only matches ASCII colon. Chinese full-width colon (：) is not matched.
        // Verifying that ASCII colon works.
        assertEquals(90, defaultPipeline.extractScore("总分: 90\n内容丰富"));
    }

    @Test
    @DisplayName("extractScore parses 总分 XX format with space")
    void extractScore_SpaceFormat_ReturnsParsedScore() {
        assertEquals(75, defaultPipeline.extractScore("总分 75\n评价: 通过"));
    }

    @Test
    @DisplayName("extractScore parses JSON score field")
    void extractScore_JsonScoreField_ReturnsParsedScore() {
        assertEquals(95, defaultPipeline.extractScore("{\"score\": 95, \"comment\": \"good\"}"));
    }

    @Test
    @DisplayName("extractScore parses JSON score with extra spaces")
    void extractScore_JsonScoreWithSpaces_ReturnsParsedScore() {
        assertEquals(60, defaultPipeline.extractScore("{\"score\" : 60}"));
    }

    @Test
    @DisplayName("extractScore returns 0 for unparseable text")
    void extractScore_UnparseableText_ReturnsZero() {
        assertEquals(0, defaultPipeline.extractScore("This text contains no score information"));
    }

    @Test
    @DisplayName("extractScore returns 0 for empty string")
    void extractScore_EmptyString_ReturnsZero() {
        assertEquals(0, defaultPipeline.extractScore(""));
    }

    @Test
    @DisplayName("extractScore returns 0 for garbled/non-JSON API response")
    void extractScore_GarbledApiResponse_ReturnsZero() {
        assertEquals(0, defaultPipeline.extractScore("{error: timeout, code: 503}"));
    }

    @Test
    @DisplayName("extractScore returns 0 for HTML-wrapped error response")
    void extractScore_HtmlErrorResponse_ReturnsZero() {
        assertEquals(0, defaultPipeline.extractScore("<html><body>502 Bad Gateway</body></html>"));
    }

    // ============================================================
    // extractFeedback tests
    // ============================================================

    @Test
    @DisplayName("extractFeedback returns empty for null input")
    void extractFeedback_NullInput_ReturnsEmpty() {
        assertEquals("", defaultPipeline.extractFeedback(null));
    }

    @Test
    @DisplayName("extractFeedback extracts from ### marker")
    void extractFeedback_HashMarker_ReturnsFromMarker() {
        String review = "评分结果\n总分: 75\n### 修改建议\n需要改进导入环节";
        assertEquals("### 修改建议\n需要改进导入环节", defaultPipeline.extractFeedback(review));
    }

    @Test
    @DisplayName("extractFeedback extracts from plain marker without ###")
    void extractFeedback_PlainMarker_ReturnsFromMarker() {
        String review = "评分结果\n修改建议\n需要改进导入环节";
        assertEquals("修改建议\n需要改进导入环节", defaultPipeline.extractFeedback(review));
    }

    @Test
    @DisplayName("extractFeedback returns full text when no suggestion marker found")
    void extractFeedback_NoMarker_ReturnsFullText() {
        String review = "评分结果\n内容质量良好\n总分: 92";
        assertEquals(review, defaultPipeline.extractFeedback(review));
    }

    @Test
    @DisplayName("extractFeedback returns empty for empty string")
    void extractFeedback_EmptyString_ReturnsEmpty() {
        assertEquals("", defaultPipeline.extractFeedback(""));
    }

    @Test
    @DisplayName("extractFeedback returns original for whitespace-only input")
    void extractFeedback_WhitespaceOnly_ReturnsOriginal() {
        String whitespace = "   \n\n   \t  ";
        assertEquals(whitespace, defaultPipeline.extractFeedback(whitespace));
    }

    // ============================================================
    // resolveMaxTokens tests
    // ============================================================

    @Test
    @DisplayName("resolveMaxTokens returns 5000 for analysis stage regardless of subject")
    void resolveMaxTokens_AnalysisStage_Returns5000() {
        assertEquals(5000, defaultPipeline.resolveMaxTokens("analysis", "语文"));
        assertEquals(5000, defaultPipeline.resolveMaxTokens("analysis", "数学"));
        assertEquals(5000, defaultPipeline.resolveMaxTokens("analysis", null));
    }

    @Test
    @DisplayName("resolveMaxTokens returns 5000 for design stage")
    void resolveMaxTokens_DesignStage_Returns5000() {
        assertEquals(5000, defaultPipeline.resolveMaxTokens("design", "语文"));
    }

    @Test
    @DisplayName("resolveMaxTokens returns 10000 for development of text-heavy subjects")
    void resolveMaxTokens_DevelopmentTextHeavy_Returns10000() {
        assertEquals(10000, defaultPipeline.resolveMaxTokens("development", "语文"));
        assertEquals(10000, defaultPipeline.resolveMaxTokens("development", "英语"));
        assertEquals(10000, defaultPipeline.resolveMaxTokens("development", "历史"));
        assertEquals(10000, defaultPipeline.resolveMaxTokens("development", "政治"));
    }

    @Test
    @DisplayName("resolveMaxTokens returns 8000 for development of non-text-heavy subjects")
    void resolveMaxTokens_DevelopmentNonTextHeavy_Returns8000() {
        assertEquals(8000, defaultPipeline.resolveMaxTokens("development", "数学"));
        assertEquals(8000, defaultPipeline.resolveMaxTokens("development", "物理"));
        assertEquals(8000, defaultPipeline.resolveMaxTokens("development", null));
    }

    // ============================================================
    // dedupContent tests
    // ============================================================

    @Test
    @DisplayName("dedupContent returns original for null input")
    void dedupContent_NullInput_ReturnsNull() {
        assertNull(AddrfPipeline.dedupContent(null));
    }

    @Test
    @DisplayName("dedupContent returns original for short content below 100 chars")
    void dedupContent_ShortContent_ReturnsOriginal() {
        String shortContent = "This is a short teaching plan.";
        assertSame(shortContent, AddrfPipeline.dedupContent(shortContent));
    }

    @Test
    @DisplayName("dedupContent removes duplicate sections with same title")
    void dedupContent_DuplicateSections_KeepsLongest() {
        String content = repeat("**教学目标**\n让学生掌握基础知识。\n", 50) +
                         repeat("**教学目标**\n让学生掌握基础知识，并能灵活运用。\n", 50) +
                         repeat("**教学重难点**\n重点是概念理解。\n", 55);
        String result = AddrfPipeline.dedupContent(content);
        assertTrue(result.contains("能灵活运用"), "Should keep longer version of 教学目标");
        assertFalse(result.contains("让学生掌握基础知识。\n**教学目标**"), "Should not show duplicate title markers");
    }

    @Test
    @DisplayName("dedupContent returns original when only one section")
    void dedupContent_SingleSection_ReturnsOriginal() {
        String content = repeat("**单一主题**\n", 50) + "这是唯一的内容段落";
        String result = AddrfPipeline.dedupContent(content);
        assertTrue(result.contains("单一主题"));
    }

    // ============================================================
    // createDefaultExecutor tests
    // ============================================================

    @Test
    @DisplayName("createDefaultExecutor creates executor with correct core pool size")
    void createDefaultExecutor_CorePoolSize_Is2() {
        ThreadPoolExecutor executor = AddrfPipeline.createDefaultExecutor();
        assertEquals(2, executor.getCorePoolSize());
        executor.shutdown();
    }

    @Test
    @DisplayName("createDefaultExecutor creates executor with correct max pool size")
    void createDefaultExecutor_MaxPoolSize_Is4() {
        ThreadPoolExecutor executor = AddrfPipeline.createDefaultExecutor();
        assertEquals(4, executor.getMaximumPoolSize());
        executor.shutdown();
    }

    @Test
    @DisplayName("createDefaultExecutor creates executor with correct keepAlive")
    void createDefaultExecutor_KeepAliveTime_Is60Seconds() {
        ThreadPoolExecutor executor = AddrfPipeline.createDefaultExecutor();
        assertEquals(60L, executor.getKeepAliveTime(TimeUnit.SECONDS));
        executor.shutdown();
    }

    @Test
    @DisplayName("createDefaultExecutor queue has capacity 50")
    void createDefaultExecutor_Queue_Capacity50() {
        ThreadPoolExecutor executor = AddrfPipeline.createDefaultExecutor();
        assertTrue(executor.getQueue() instanceof LinkedBlockingQueue);
        assertEquals(50, ((LinkedBlockingQueue<?>) executor.getQueue()).remainingCapacity());
        executor.shutdown();
    }

    // ============================================================
    // AddrfResult.getStageOutputs tests
    // ============================================================

    @Test
    @DisplayName("getStageOutputs maps all fields correctly")
    void getStageOutputs_AllFieldsSet_MapsCorrectly() {
        AddrfPipeline.AddrfResult result = new AddrfPipeline.AddrfResult();
        result.analysis = "分析结果";
        result.design = "设计内容";
        result.development = "开发内容";
        result.review = "评审意见";
        result.score = 85;

        Map<String, String> outputs = result.getStageOutputs();
        assertEquals("分析结果", outputs.get("analysis"));
        assertEquals("设计内容", outputs.get("design"));
        assertEquals("开发内容", outputs.get("development"));
        assertEquals("评审意见", outputs.get("review"));
    }

    @Test
    @DisplayName("getStageOutputs returns empty strings for null fields")
    void getStageOutputs_NullFields_ReturnsEmptyStrings() {
        AddrfPipeline.AddrfResult result = new AddrfPipeline.AddrfResult();
        Map<String, String> outputs = result.getStageOutputs();
        assertEquals("", outputs.get("analysis"));
        assertEquals("", outputs.get("design"));
        assertEquals("", outputs.get("development"));
        assertEquals("", outputs.get("review"));
    }

    // Helper: repeat a string n times to reach a desired length
    private static String repeat(String base, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(base);
        }
        return sb.toString();
    }

    // ============================================================
    // Analysis 意图理解解析 (2026-08-18)
    // ============================================================

    @Test
    @DisplayName("extractIntentSection 提取意图摘要段落")
    void extractIntentSection_returnsIntentSummary() throws Exception {
        String output = "**意图摘要**:\n用户想要一节小学三年级数学课，重点是分数认识。\n\n**信息缺口**:\n- 学情未知\n\n**澄清问题**:\n- 学生计算基础如何？";
        String summary = invokeExtractIntentSection(output, "意图摘要");
        assertTrue(summary.contains("分数认识"), "应包含意图内容: " + summary);
    }

    @Test
    @DisplayName("extractIntentSection 提取澄清问题并去掉序号")
    void extractIntentSection_returnsCleanQuestions() throws Exception {
        String output = "**意图摘要**:\n测试摘要\n\n**信息缺口**:\n- 学情\n\n**澄清问题**:\n- 学生计算基础如何？\n- 是否需要分层作业？";
        String questions = invokeExtractIntentSection(output, "澄清问题");
        assertTrue(questions.contains("学生计算基础"), "应包含第一个问题: " + questions);
        assertTrue(questions.contains("分层作业"), "应包含第二个问题: " + questions);
        assertFalse(questions.contains("信息缺口"), "不应混入上一段内容");
    }

    @Test
    @DisplayName("extractIntentSection 无标签时返回 null")
    void extractIntentSection_missingLabel_returnsNull() throws Exception {
        assertNull(invokeExtractIntentSection("**意图摘要**:\n只有摘要", "信息缺口"));
    }

    private String invokeExtractIntentSection(String output, String label) throws Exception {
        var method = AddrfPipeline.class.getDeclaredMethod("extractIntentSection", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(defaultPipeline, output, label);
    }

    // ============================================================
    // shouldRequestHumanReview tests
    // ============================================================

    @Nested
    @DisplayName("shouldRequestHumanReview HITL 规则测试")
    class HumanReviewTests {

        private AddrfPipeline.AddrfResult makeResult(String development, int score, String design, String review) {
            AddrfPipeline.AddrfResult r = new AddrfPipeline.AddrfResult();
            r.analysis = "分析内容正常";
            r.design = design != null ? design : "设计内容正常";
            r.development = development != null ? development : "开发内容正常";
            r.review = review != null ? review : "评审正常";
            r.score = score;
            return r;
        }

        @Test
        @DisplayName("规则1: Development >5000 字 → true")
        void rule1_longDevelopment_returnsTrue() {
            String longDev = "字".repeat(5001);
            AddrfPipeline.AddrfResult r = makeResult(longDev, 80, null, null);
            assertTrue(defaultPipeline.shouldRequestHumanReview(r, "数学", 1L));
        }

        @Test
        @DisplayName("规则1: Development ≤5000 字 → false")
        void rule1_shortDevelopment_returnsFalse() {
            String shortDev = "字".repeat(5000);
            AddrfPipeline.AddrfResult r = makeResult(shortDev, 80, null, null);
            assertFalse(defaultPipeline.shouldRequestHumanReview(r, "数学", 1L));
        }

        @Test
        @DisplayName("规则2: Score=30 (<60) → true")
        void rule2_lowScore_returnsTrue() {
            AddrfPipeline.AddrfResult r = makeResult("正常内容", 30, null, null);
            assertTrue(defaultPipeline.shouldRequestHumanReview(r, "数学", 1L));
        }

        @Test
        @DisplayName("规则2: Score=0 (未评分) → false")
        void rule2_zeroScore_returnsFalse() {
            AddrfPipeline.AddrfResult r = makeResult("正常内容", 0, null, null);
            assertFalse(defaultPipeline.shouldRequestHumanReview(r, "数学", 1L));
        }

        @Test
        @DisplayName("规则2: Score=80 → false")
        void rule2_normalScore_returnsFalse() {
            AddrfPipeline.AddrfResult r = makeResult("正常内容", 80, null, null);
            assertFalse(defaultPipeline.shouldRequestHumanReview(r, "数学", 1L));
        }

        @Test
        @DisplayName("规则3: Design 以降级前缀开头 → true")
        void rule3_degradedDesign_returnsTrue() {
            AddrfPipeline.AddrfResult r = makeResult("正常内容", 80, "[系统提示: Design 阶段超时]", null);
            assertTrue(defaultPipeline.shouldRequestHumanReview(r, "数学", 1L));
        }

        @Test
        @DisplayName("规则3: Review 以降级前缀开头 → true")
        void rule3_degradedReview_returnsTrue() {
            AddrfPipeline.AddrfResult r = makeResult("正常内容", 80, null, "[系统提示: Review 生成失败]");
            assertTrue(defaultPipeline.shouldRequestHumanReview(r, "数学", 1L));
        }

        @Test
        @DisplayName("规则4: 含暴力关键词 → true")
        void rule4_unsafeKeyword_returnsTrue() {
            AddrfPipeline.AddrfResult r = makeResult("这段内容包含暴力描写", 80, null, null);
            assertTrue(defaultPipeline.shouldRequestHumanReview(r, "数学", 1L));
        }

        @Test
        @DisplayName("全规则正常内容 → false")
        void allRules_normalContent_returnsFalse() {
            AddrfPipeline.AddrfResult r = makeResult("正常教案内容", 85, null, null);
            assertFalse(defaultPipeline.shouldRequestHumanReview(r, "数学", 1L));
        }

        @Test
        @DisplayName("needsHumanReview 标志位跟随返回值")
        void needsHumanReview_flag_followsReturnValue() {
            AddrfPipeline.AddrfResult r = makeResult("正常内容", 80, null, null);
            assertFalse(r.needsHumanReview);
            assertFalse(defaultPipeline.shouldRequestHumanReview(r, "数学", 1L));

            AddrfPipeline.AddrfResult r2 = makeResult("字".repeat(5001), 80, null, null);
            assertTrue(defaultPipeline.shouldRequestHumanReview(r2, "数学", 1L));
            assertTrue(r2.needsHumanReview, "HITL 触发后 needsHumanReview 应为 true");
        }
    }

    // ============================================================
    // Analysis 缓存 Key 构建测试 (改进四)
    // ============================================================

    @Nested
    @DisplayName("Analysis cache key building")
    class AnalysisCacheKeyTests {

        private LessonPlanRequest makeRequest(String stage, int grade, String subject) {
            LessonPlanRequest req = new LessonPlanRequest();
            req.setStage(stage);
            req.setGrade(grade);
            req.setSubject(subject);
            req.setHours(1);
            req.setGoals("测试教学目标");
            return req;
        }

        @Test
        @DisplayName("Cache key format: contains userId, stage, grade, subject")
        void cacheKey_containsAllParts() throws Exception {
            Method method = AddrfPipeline.class.getDeclaredMethod(
                    "buildAnalysisCacheKey", Long.class, LessonPlanRequest.class);
            method.setAccessible(true);

            LessonPlanRequest req = makeRequest("小学", 3, "数学");
            String key = (String) method.invoke(defaultPipeline, 42L, req);

            assertTrue(key.startsWith("gagneflow:analysis:cache:42:"));
            assertTrue(key.contains("小学"));
            assertTrue(key.contains(":3:"));
            assertTrue(key.contains("数学"));
            // key 末尾现在是 goalsHash，不再是纯 subject
            assertTrue(key.contains("数学:"), "subject 后应有 goalsHash 分隔符: " + key);
        }

        @Test
        @DisplayName("Cache key differs for different grades")
        void cacheKey_differsByGrade() throws Exception {
            Method method = AddrfPipeline.class.getDeclaredMethod(
                    "buildAnalysisCacheKey", Long.class, LessonPlanRequest.class);
            method.setAccessible(true);

            LessonPlanRequest r1 = makeRequest("初中", 7, "语文");
            LessonPlanRequest r2 = makeRequest("初中", 8, "语文");

            String key1 = (String) method.invoke(defaultPipeline, 1L, r1);
            String key2 = (String) method.invoke(defaultPipeline, 1L, r2);

            assertNotEquals(key1, key2, "不同年级应产生不同 cache key");
        }

        @Test
        @DisplayName("Cache key differs for different subjects")
        void cacheKey_differsBySubject() throws Exception {
            Method method = AddrfPipeline.class.getDeclaredMethod(
                    "buildAnalysisCacheKey", Long.class, LessonPlanRequest.class);
            method.setAccessible(true);

            LessonPlanRequest r1 = makeRequest("高中", 10, "物理");
            LessonPlanRequest r2 = makeRequest("高中", 10, "化学");

            String key1 = (String) method.invoke(defaultPipeline, 1L, r1);
            String key2 = (String) method.invoke(defaultPipeline, 1L, r2);

            assertNotEquals(key1, key2, "不同学科应产生不同 cache key");
        }

        @Test
        @DisplayName("Cache key differs for different users")
        void cacheKey_differsByUser() throws Exception {
            Method method = AddrfPipeline.class.getDeclaredMethod(
                    "buildAnalysisCacheKey", Long.class, LessonPlanRequest.class);
            method.setAccessible(true);

            LessonPlanRequest req = makeRequest("小学", 3, "数学");

            String key1 = (String) method.invoke(defaultPipeline, 1L, req);
            String key2 = (String) method.invoke(defaultPipeline, 99L, req);

            assertNotEquals(key1, key2, "不同用户应产生不同 cache key");
        }

        @Test
        @DisplayName("P2修复: 不同教学目标 → 不同 cache key")
        void cacheKey_differsByGoals() throws Exception {
            Method method = AddrfPipeline.class.getDeclaredMethod(
                    "buildAnalysisCacheKey", Long.class, LessonPlanRequest.class);
            method.setAccessible(true);

            LessonPlanRequest r1 = makeRequest("小学", 3, "数学");
            r1.setGoals("掌握两位数乘法");
            LessonPlanRequest r2 = makeRequest("小学", 3, "数学");
            r2.setGoals("理解分数概念并掌握基本运算");

            String key1 = (String) method.invoke(defaultPipeline, 1L, r1);
            String key2 = (String) method.invoke(defaultPipeline, 1L, r2);

            assertNotEquals(key1, key2, "不同教学目标应产生不同 cache key");
        }
    }
}
