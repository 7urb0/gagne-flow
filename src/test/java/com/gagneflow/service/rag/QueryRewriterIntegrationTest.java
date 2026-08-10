package com.gagneflow.service.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryRewriter 集成测试 — 覆盖规则改写和 LLM 改写的触发条件判断
 * 纯逻辑测试，不依赖外部 API
 */
@DisplayName("QueryRewriter 集成测试")
class QueryRewriterIntegrationTest {

    private QueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        // H-9修复: 构造注入 DashScopeApi（单测 mock，不触发真实连接）
        rewriter = new QueryRewriter(org.mockito.Mockito.mock(
                com.alibaba.cloud.ai.dashscope.api.DashScopeApi.class));
        ReflectionTestUtils.setField(rewriter, "enabled", true);
        ReflectionTestUtils.setField(rewriter, "llmEnabled", false); // 默认关闭 LLM
    }

    // ============================================================
    // 规则改写测试
    // ============================================================

    @Test
    @DisplayName("短查询 + 有历史 → 拼接上一条用户消息")
    void shortQuery_withHistory_shouldAppendLastUserMessage() {
        List<Map<String, String>> history = new ArrayList<>();
        history.add(Map.of("role", "user", "content", "三年级数学分数怎么教"));
        history.add(Map.of("role", "assistant", "content", "建议从分蛋糕引入"));

        String result = rewriter.rewrite("还有吗", history);
        assertTrue(result.contains("三年级数学分数怎么教"));
        assertTrue(result.contains("还有吗"));
    }

    @Test
    @DisplayName("包含引号术语 → 提取为关键词")
    void quotedTerms_shouldExtractAsKeywords() {
        String result = rewriter.rewrite("「分数概念」的教学方法有哪些", List.of());
        assertTrue(result.contains("分数概念"), "应提取引号内术语");
    }

    @Test
    @DisplayName("包含年级信息 → 提取为关键词")
    void gradeInfo_shouldExtractAsKeywords() {
        String result = rewriter.rewrite("二年级数学教案", List.of());
        assertTrue(result.contains("二年级") || result.contains("年级"));
    }

    @Test
    @DisplayName("禁用时返回原始查询")
    void disabled_shouldReturnOriginal() {
        ReflectionTestUtils.setField(rewriter, "enabled", false);
        String result = rewriter.rewrite("数学教案设计", List.of());
        assertEquals("数学教案设计", result);
    }

    @Test
    @DisplayName("正常长度查询无历史 → 保持原样")
    void normalQuery_noHistory_shouldReturnOriginal() {
        String result = rewriter.rewrite("如何设计小学数学分数教案", List.of());
        assertEquals("如何设计小学数学分数教案", result);
    }

    // ============================================================
    // LLM 改写触发条件测试
    // ============================================================

    @Test
    @DisplayName("LLM 关闭时不触发 LLM 改写")
    void llmDisabled_shouldUseRulesOnly() {
        ReflectionTestUtils.setField(rewriter, "llmEnabled", false);
        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "三年级分数教学")
        );

        // 指代词查询 — LLM 关闭，应走规则改写而非 LLM
        String result = rewriter.rewrite("上次说的那个", history);
        assertNotNull(result);
        // 规则改写：append last user message
        assertTrue(result.contains("三年级分数教学"));
    }

    @Test
    @DisplayName("extractKeywords 不提取空引号")
    void extractKeywords_emptyQuotes_ignored() {
        String result = rewriter.extractKeywords("「」空的内容");
        assertFalse(result.contains("「"));
    }

    @Test
    @DisplayName("LLM 改写结果与原查询无关联时判定无效（防跑偏丢弃原查询）")
    void isValidRewrite_unrelated_shouldReject() throws Exception {
        java.lang.reflect.Method m = QueryRewriter.class.getDeclaredMethod("isValidRewrite", String.class, String.class);
        m.setAccessible(true);
        // 场景: 独立完整查询被 LLM 用历史话题覆盖（公共子串 < 2）
        assertFalse((Boolean) m.invoke(rewriter, "完全独立的完整查询", "三年级分数怎么教"));
    }

    @Test
    @DisplayName("LLM 改写保留核心词时判定有效（指代消解正常场景）")
    void isValidRewrite_related_shouldAccept() throws Exception {
        java.lang.reflect.Method m = QueryRewriter.class.getDeclaredMethod("isValidRewrite", String.class, String.class);
        m.setAccessible(true);
        assertTrue((Boolean) m.invoke(rewriter, "它的内角和是多少", "三角形的内角和是多少"));
        assertTrue((Boolean) m.invoke(rewriter, "上次说的那个分数导入方法", "三年级数学分数教学的导入方法 其他例子"));
        assertTrue((Boolean) m.invoke(rewriter, "怎么教", "五年级小数乘法 怎么教"));
    }
}
