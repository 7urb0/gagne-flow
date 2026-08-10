package com.gagneflow.service.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VectorIndexService.validateLessonPlanStructure 规则硬校验测试。
 * 验证反哺教案入口的客观校验器: 结构完整性 + 字数下限（纯规则，不依赖 LLM 评分）。
 */
@DisplayName("VectorIndexService 反哺教案规则校验测试")
class VectorIndexServiceValidateTest {

    /** 构造一段结构完整的教案纯文本（命中全部 4 个核心要素，超过 500 字） */
    private String buildCompleteLessonPlan() {
        StringBuilder sb = new StringBuilder();
        sb.append("教学目标: 学生能够掌握分数的基本概念和加减法运算。\n");
        sb.append("教学重难点: 重点为分数加减法的通分过程，难点为异分母分数的转化。\n");
        sb.append("教学过程: 本课通过情境导入、探究新知、巩固练习三个环节展开教学。\n");
        sb.append("教学评估: 通过课堂练习与课后作业对学习效果进行评价。\n");
        // 填充到 500 字以上
        while (sb.length() < 550) {
            sb.append("教师引导学生观察生活情境中的分数现象，讨论分数的实际含义与用法，"
                    + "通过小组合作完成分数加减法的演算练习，教师巡回指导并纠正错误。");
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("结构完整性校验")
    class StructureValidationTests {

        @Test
        @DisplayName("结构完整的高分教案通过校验")
        void completeLessonPlanPasses() {
            String result = VectorIndexService.validateLessonPlanStructure(buildCompleteLessonPlan());
            assertNull(result, "完整教案应通过校验，实际原因: " + result);
        }

        @Test
        @DisplayName("缺少核心要素的教案不通过校验")
        void missingCoreElementsFails() {
            StringBuilder sb = new StringBuilder();
            // 只有"教学过程"一个要素，缺教学目标/教学重难点/教学评估
            sb.append("教学过程: 导入新课，讲授知识点，组织练习，课堂小结。\n");
            while (sb.length() < 550) {
                sb.append("教师讲解本节课的核心知识内容，学生进行相应的练习活动，"
                        + "教师在巡视过程中给予个别指导，最后进行课堂总结。");
            }
            String result = VectorIndexService.validateLessonPlanStructure(sb.toString());
            assertNotNull(result, "缺少核心要素的教案应被拒绝");
            assertTrue(result.contains("结构不完整"), "失败原因应说明结构问题，实际: " + result);
        }

        @Test
        @DisplayName("只有 2 个核心要素的教案不通过校验（需至少 3 个）")
        void twoElementsFails() {
            StringBuilder sb = new StringBuilder();
            sb.append("教学目标: 学生掌握面积计算公式。\n");
            sb.append("教学过程: 本课通过直观演示引导学生理解面积概念。\n");
            while (sb.length() < 550) {
                sb.append("学生观察教师演示的图形变换过程，理解面积公式的推导逻辑，"
                        + "并通过练习加深对公式的记忆与应用。");
            }
            String result = VectorIndexService.validateLessonPlanStructure(sb.toString());
            assertNotNull(result, "只有 2 个要素的教案应被拒绝");
        }

        @Test
        @DisplayName("null 输入不通过校验")
        void nullInputFails() {
            String result = VectorIndexService.validateLessonPlanStructure(null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("空字符串不通过校验")
        void emptyInputFails() {
            String result = VectorIndexService.validateLessonPlanStructure("");
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("字数下限校验")
    class LengthValidationTests {

        @Test
        @DisplayName("纯文本过短（< 500 字）不通过校验")
        void tooShortFails() {
            String shortText = "教学目标: 掌握加法。教学重难点: 进位。教学过程: 讲授+练习。教学评估: 小测。";
            assertTrue(shortText.length() < 500);
            String result = VectorIndexService.validateLessonPlanStructure(shortText);
            assertNotNull(result, "过短教案应被拒绝");
            assertTrue(result.contains("过短"), "失败原因应说明字数问题，实际: " + result);
        }
    }
}
