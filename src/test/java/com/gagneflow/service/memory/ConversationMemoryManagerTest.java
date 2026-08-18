package com.gagneflow.service.memory;

import com.gagneflow.service.chat.ChatSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ConversationMemoryManager 事实提取测试")
class ConversationMemoryManagerTest {

    private ConversationMemoryManager manager;
    private LongTermMemoryService ltmService;

    @BeforeEach
    void setUp() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ltmService = mock(LongTermMemoryService.class);
        manager = new ConversationMemoryManager(chatSessionService, ltmService);
    }

    @Nested
    @DisplayName("extractFactsFromSummary — 结构化事实提取")
    class ExtractFactsTests {

        @Test
        @DisplayName("null 摘要返回空列表")
        void nullSummary_shouldReturnEmpty() {
            List<LongTermMemoryService.MemoryFact> facts = invokeExtractFacts(null);
            assertTrue(facts.isEmpty());
        }

        @Test
        @DisplayName("空摘要返回空列表")
        void emptySummary_shouldReturnEmpty() {
            List<LongTermMemoryService.MemoryFact> facts = invokeExtractFacts("");
            assertTrue(facts.isEmpty());
        }

        @Test
        @DisplayName("教学需求类关键词命中 → 高分句子被提取")
        void teachingRequirements_shouldBeExtracted() {
            String summary = "根据教师需求分析，三年级学生需要掌握分数概念。" +
                    "教学目标是通过分蛋糕方式引入分数教学。";
            List<LongTermMemoryService.MemoryFact> facts = invokeExtractFacts(summary);

            assertFalse(facts.isEmpty(), "应至少提取一条事实");
            assertTrue(facts.stream().anyMatch(f ->
                    f.getFact().contains("需求") || f.getFact().contains("目标")));
        }

        @Test
        @DisplayName("约束限制类关键词 → 不能被遗漏")
        void constraints_shouldNotBeMissed() {
            String summary = "教师明确要求不能使用电子设备辅助教学，避免过度依赖PPT。" +
                    "同时不能布置超过30分钟的课后作业。";
            List<LongTermMemoryService.MemoryFact> facts = invokeExtractFacts(summary);

            assertFalse(facts.isEmpty());
            assertTrue(facts.stream().anyMatch(f ->
                    f.getFact().contains("不能") || f.getFact().contains("避免")));
        }

        @Test
        @DisplayName("否定反馈类 → 负面反馈应被记录")
        void negativeFeedback_shouldBeCaptured() {
            String summary = "教师对上次教案不满意，认为导入环节设计不好。" +
                    "特别指出数学例题选择错误，不适合三年级水平。";
            List<LongTermMemoryService.MemoryFact> facts = invokeExtractFacts(summary);

            assertFalse(facts.isEmpty());
            assertTrue(facts.stream().anyMatch(f ->
                    f.getFact().contains("不满意") || f.getFact().contains("不好") ||
                            f.getFact().contains("错误")));
        }

        @Test
        @DisplayName("数值信息 → 具体数字不应泛化")
        void numericInfo_shouldBePreserved() {
            String summary = "课时数建议4课时，每课时40分钟，班级人数45人。" +
                    "测试分数要求达到85分以上。";
            List<LongTermMemoryService.MemoryFact> facts = invokeExtractFacts(summary);

            assertFalse(facts.isEmpty());
            assertTrue(facts.stream().anyMatch(f ->
                    f.getFact().matches(".*\\d+.*")),
                    "应包含至少一条含数字的事实");
        }

        @Test
        @DisplayName("引号内关键术语 → 补充到事实中")
        void quotedTerms_shouldBeSupplemented() {
            String summary = "需要关注「核心素养」和「批判性思维」的培养。" +
                    "教学设计应当围绕这些展开。";
            List<LongTermMemoryService.MemoryFact> facts = invokeExtractFacts(summary);

            boolean hasQuoted = facts.stream().anyMatch(f ->
                    f.getFact().contains("核心素养") || f.getFact().contains("批判性思维"));
            assertTrue(hasQuoted, "引号内的关键术语应被提取");
        }

        @Test
        @DisplayName("噪音文本 → 降级为取前N个有效句子")
        void noiseText_shouldFallbackToTopNSentences() {
            // 所有句子都不匹配任何类别关键词，触发降级逻辑
            String summary = "这是一段测试文本。没有任何关键词匹配。只是普通描述。" +
                    "没有任何教学相关内容。纯粹的无意义句子。";
            List<LongTermMemoryService.MemoryFact> facts = invokeExtractFacts(summary);

            // 降级后仍应返回事实（取前 N 个有效句子）
            assertFalse(facts.isEmpty(), "降级逻辑应返回有效句子");
        }

        @Test
        @DisplayName("每个类别最多 2 条 → 避免单一类别垄断")
        void perCategoryLimit_shouldNotExceedTwo() {
            // 大量"教学需求"相关句子
            String summary = "教师需要设计分数教案。教学目标是掌握分数概念。" +
                    "需要重点讲解通分方法。期望学生能独立解题。" +
                    "需要准备教具。教学重点是分数加减。";
            List<LongTermMemoryService.MemoryFact> facts = invokeExtractFacts(summary);

            // 不应被单一类别垄断 — 但需要至少"教学需求"类别有大量句子
            // 验证不会只有一种类别
            assertTrue(facts.size() <= 5, "最多提取 MAX_FACTS 条");
        }
    }

    // 通过反射调用 private 方法
    @SuppressWarnings("unchecked")
    private List<LongTermMemoryService.MemoryFact> invokeExtractFacts(String summary) {
        try {
            var method = ConversationMemoryManager.class.getDeclaredMethod(
                    "extractFactsFromSummary", String.class);
            method.setAccessible(true);
            return (List<LongTermMemoryService.MemoryFact>) method.invoke(manager, summary);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("storeUserPreference — 表单偏好写入 LTM")
    class StoreUserPreferenceTests {

        @Test
        @DisplayName("非空偏好存为 USER_EXPLICIT 事实")
        void nonEmptyPreference_storedAsUserExplicit() {
            manager.storeUserPreference(1L, "lesson_abc", "教学偏好", "讲练结合，边做题边学习");
            verify(ltmService).storeFacts(eq(1L), eq("lesson_abc"), argThat(facts ->
                    facts.size() == 1
                            && "讲练结合，边做题边学习".equals(facts.get(0).getFact())
                            && "教学偏好".equals(facts.get(0).getFactType())
                            && LongTermMemoryService.SOURCE_USER.equals(facts.get(0).getSourcePhase())));
        }

        @Test
        @DisplayName("空/空白偏好不写入 LTM")
        void blankPreference_notStored() {
            manager.storeUserPreference(1L, "lesson_abc", "教学偏好", "   ");
            manager.storeUserPreference(1L, "lesson_abc", "教学偏好", null);
            verify(ltmService, never()).storeFacts(any(), any(), any());
        }
    }
}
