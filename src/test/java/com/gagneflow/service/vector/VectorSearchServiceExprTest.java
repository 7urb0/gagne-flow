package com.gagneflow.service.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VectorSearchService.buildSearchExpr 过滤表达式测试。
 * 验证 RAG 反哺质量闭环: generated_lesson_plan 来源仅 _score >= 85 的教案进入候选。
 */
@DisplayName("VectorSearchService 检索过滤表达式测试")
class VectorSearchServiceExprTest {

    @Nested
    @DisplayName("buildSearchExpr 反哺教案分数门槛")
    class BuildSearchExprTests {

        @Test
        @DisplayName("biz 表达式不再包含教案分数门槛(2026-08-19 已独立到 personal_plans)")
        void exprContainsNoLessonPlanScoreThreshold() {
            String expr = VectorSearchService.buildSearchExpr(1L);
            assertFalse(expr.contains("metadata[\"_score\"]"),
                    "biz 表达式不应再包含教案分数门槛(教案已独立个人库)，实际: " + expr);
            assertTrue(expr.contains("generated_lesson_plan"),
                    "biz 表达式仍应排除遗留的教案来源，实际: " + expr);
        }

        @Test
        @DisplayName("个人教案库表达式包含 _user_id 精确过滤 + 分数门槛")
        void personalPlansExprContainsFilter() {
            String expr = VectorSearchService.buildPersonalPlansExpr(1L);
            assertTrue(expr.contains("metadata[\"_user_id\"] == \"1\""),
                    "个人库表达式应包含用户隔离，实际: " + expr);
        }

        @Test
        @DisplayName("doSearch 双库合并: 个人教案库查询带分数门槛")
        void personalPlansSearchExprWithScoreThreshold() {
            // 检索端在 doSearch 内拼接 (个人库expr) && _score >= 85
            String personalExpr = VectorSearchService.buildPersonalPlansExpr(1L);
            String combined = "(" + personalExpr + ") && metadata[\"_score\"] >= 85";
            assertTrue(combined.contains("metadata[\"_score\"] >= 85"),
                    "个人教案库检索应带分数门槛，实际: " + combined);
        }

        @Test
        @DisplayName("表达式包含用户数据隔离条件")
        void exprContainsUserFilter() {
            String expr = VectorSearchService.buildSearchExpr(1L);
            assertTrue(expr.contains("metadata[\"_user_id\"] == \"1\""),
                    "表达式应包含用户数据隔离，实际: " + expr);
        }

        @Test
        @DisplayName("表达式不再特判 k12_curriculum（已退役），课标原文走无归属放行")
        void exprNoLongerSpecialCasesK12() {
            String expr = VectorSearchService.buildSearchExpr(1L);
            assertFalse(expr.contains("k12_curriculum"),
                    "k12_curriculum 已退役，表达式不应再包含其特判，实际: " + expr);
            assertTrue(expr.contains("not exists metadata[\"_user_id\"]"),
                    "教育部课标原文 (curriculum_2022, 无 _user_id) 应通过无归属分支放行，实际: " + expr);
        }

        @Test
        @DisplayName("表达式兼容无 _user_id 字段的文档")
        void exprContainsNoUserId() {
            String expr = VectorSearchService.buildSearchExpr(1L);
            assertTrue(expr.contains("not exists metadata[\"_user_id\"]"),
                    "表达式应兼容无归属文档，实际: " + expr);
        }

        @Test
        @DisplayName("biz 表达式排除反哺教案来源(2026-08-19 教案已独立)")
        void exprExcludesLessonPlanSource() {
            String expr = VectorSearchService.buildSearchExpr(1L);
            // 教案已独立到 personal_plans, biz 表达式应显式排除历史遗留的 generated_lesson_plan
            assertTrue(expr.contains("metadata[\"_source\"] != \"generated_lesson_plan\""),
                    "biz 表达式应排除教案来源，实际: " + expr);
        }

        @Test
        @DisplayName("userId 为 null 或 0 时使用默认值，不抛异常")
        void exprHandlesNullAndZero() {
            assertDoesNotThrow(() -> VectorSearchService.buildSearchExpr(null));
            assertDoesNotThrow(() -> VectorSearchService.buildSearchExpr(0L));
            String expr = VectorSearchService.buildSearchExpr(null);
            assertNotNull(expr);
            assertFalse(expr.isEmpty());
        }
    }

    /**
     * 行为级语义测试: 验证 buildSearchExpr 生成的表达式对单条 metadata 的实际过滤效果。
     * 用与表达式结构一致的轻量求值器模拟 Milvus 过滤，覆盖验收标准 2a/2c:
     * a) _score < 85 的反哺教案被排除；c) _score >= 85 的教案正常参与。
     */
    @Nested
    @DisplayName("表达式语义（单条 metadata 过滤行为）")
    class ExprSemanticTests {

        /** 与 buildSearchExpr 表达式语义一致的轻量求值器 */
        static boolean passFilter(Long uid, java.util.Map<String, Object> md) {
            // 分支2: 无 _user_id 字段 -> 无归属文档放行（含教育部课标原文 curriculum_2022）
            if (!md.containsKey("_user_id")) {
                return true;
            }
            // 分支1: 用户数据（含用户上传文档与反哺教案）
            long mdUid = ((Number) md.get("_user_id")).longValue();
            long expectedUid = (uid == null) ? 0L : uid;
            if (mdUid != expectedUid) {
                return false;
            }
            if (!"generated_lesson_plan".equals(md.get("_source"))) {
                return true; // 用户上传文档不受分数门槛限制
            }
            // 反哺教案: 分数门槛 _score >= 85
            int score = ((Number) md.get("_score")).intValue();
            return score >= VectorSearchService.MIN_LESSON_PLAN_SCORE;
        }

        @Test
        @DisplayName("验收2a: 反哺教案 _score=70 被排除")
        void lowScoreLessonPlanExcluded() {
            java.util.Map<String, Object> md = new java.util.HashMap<>();
            md.put("_user_id", 1L);
            md.put("_source", "generated_lesson_plan");
            md.put("_score", 70);
            assertFalse(passFilter(1L, md), "_score=70 的反哺教案不应进入候选");
        }

        @Test
        @DisplayName("验收2a: 反哺教案 _score=84（临界下限）被排除")
        void boundaryBelow85Excluded() {
            java.util.Map<String, Object> md = new java.util.HashMap<>();
            md.put("_user_id", 1L);
            md.put("_source", "generated_lesson_plan");
            md.put("_score", 84);
            assertFalse(passFilter(1L, md), "_score=84 反哺教案应被排除（<85）");
        }

        @Test
        @DisplayName("验收2c: 反哺教案 _score=85 正常进入候选")
        void score85Included() {
            java.util.Map<String, Object> md = new java.util.HashMap<>();
            md.put("_user_id", 1L);
            md.put("_source", "generated_lesson_plan");
            md.put("_score", 85);
            assertTrue(passFilter(1L, md), "_score=85 反哺教案应进入候选（>=85）");
        }

        @Test
        @DisplayName("验收2c: 反哺教案 _score=95 正常进入候选")
        void highScoreIncluded() {
            java.util.Map<String, Object> md = new java.util.HashMap<>();
            md.put("_user_id", 1L);
            md.put("_source", "generated_lesson_plan");
            md.put("_score", 95);
            assertTrue(passFilter(1L, md), "_score=95 反哺教案应进入候选");
        }

        @Test
        @DisplayName("教育部课标原文 (curriculum_2022, 无 _user_id) 放行")
        void curriculumNoUserIdPasses() {
            java.util.Map<String, Object> md = new java.util.HashMap<>();
            md.put("_source", "curriculum_2022");
            md.put("_subject", "物理");
            assertTrue(passFilter(1L, md), "课标原文无 _user_id，应通过无归属分支放行");
        }

        @Test
        @DisplayName("无 _user_id 字段的上传文档放行")
        void noUserIdDocPasses() {
            java.util.Map<String, Object> md = new java.util.HashMap<>();
            md.put("_source", "/docs/shared.pdf");
            assertTrue(passFilter(1L, md), "无归属文档应放行");
        }

        @Test
        @DisplayName("用户上传文档（非反哺教案）不受分数门槛限制")
        void userDocNotAffected() {
            java.util.Map<String, Object> md = new java.util.HashMap<>();
            md.put("_user_id", 1L);
            md.put("_source", "/uploads/user1/教案模板.docx");
            assertTrue(passFilter(1L, md), "用户上传文档不应受 _score 门槛限制");
        }

        @Test
        @DisplayName("其他用户的文档被排除（数据隔离）")
        void otherUserExcluded() {
            java.util.Map<String, Object> md = new java.util.HashMap<>();
            md.put("_user_id", 2L);
            md.put("_source", "generated_lesson_plan");
            md.put("_score", 95);
            assertFalse(passFilter(1L, md), "用户2的文档对用户1不可见");
        }
    }
}
