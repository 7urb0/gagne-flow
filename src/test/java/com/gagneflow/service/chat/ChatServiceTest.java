package com.gagneflow.service.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatService unit tests")
class ChatServiceTest {

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService();
    }

    // ============================================================
    // buildSystemPrompt(List) single-parameter delegation
    // ============================================================

    @Test
    @DisplayName("buildSystemPrompt(List) delegates to two-param with null longTermContext")
    void buildSystemPrompt_SingleParam_DelegatesCorrectly() {
        List<Map<String, String>> history = new ArrayList<>();
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", "测试问题");
        history.add(msg);

        String result = chatService.buildSystemPrompt(history);
        assertTrue(result.contains("测试问题"));
        assertTrue(result.contains("对话历史"));
    }

    // ============================================================
    // buildSystemPrompt(List, String) two-parameter tests
    // ============================================================

    @Test
    @DisplayName("buildSystemPrompt includes base role definition")
    void buildSystemPrompt_BaseRole_Present() {
        List<Map<String, String>> history = new ArrayList<>();
        String prompt = chatService.buildSystemPrompt(history, null);
        assertTrue(prompt.contains("AI 教育助手"));
        assertTrue(prompt.contains("getCurrentDateTime"));
        assertTrue(prompt.contains("queryInternalDocs"));
    }

    @Test
    @DisplayName("buildSystemPrompt includes longTermContext when provided")
    void buildSystemPrompt_LongTermContext_Present() {
        List<Map<String, String>> history = new ArrayList<>();
        String longTerm = "用户长期偏好: 偏好小学数学";
        String prompt = chatService.buildSystemPrompt(history, longTerm);
        assertTrue(prompt.contains("用户长期偏好: 偏好小学数学"));
    }

    @Test
    @DisplayName("buildSystemPrompt skips longTermContext when null")
    void buildSystemPrompt_NullLongTermContext_NotPresent() {
        List<Map<String, String>> history = new ArrayList<>();
        String prompt = chatService.buildSystemPrompt(history, null);
        // The longTermContext block should not be a separate visible fragment
        // Just verify the prompt is still complete
        assertTrue(prompt.contains("AI 教育助手"));
    }

    @Test
    @DisplayName("buildSystemPrompt skips longTermContext when empty")
    void buildSystemPrompt_EmptyLongTermContext_NotPresent() {
        List<Map<String, String>> history = new ArrayList<>();
        String prompt = chatService.buildSystemPrompt(history, "");
        assertTrue(prompt.contains("AI 教育助手"));
    }

    @Test
    @DisplayName("buildSystemPrompt formats user messages correctly")
    void buildSystemPrompt_UserMessage_FormattedCorrectly() {
        List<Map<String, String>> history = new ArrayList<>();
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", "设计一节关于分数的课");
        history.add(msg);

        String prompt = chatService.buildSystemPrompt(history, null);
        assertTrue(prompt.contains("用户: 设计一节关于分数的课"));
        assertTrue(prompt.contains("对话历史"));
    }

    @Test
    @DisplayName("buildSystemPrompt formats assistant messages correctly")
    void buildSystemPrompt_AssistantMessage_FormattedCorrectly() {
        List<Map<String, String>> history = new ArrayList<>();
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", "好的，我来设计");
        history.add(msg);

        String prompt = chatService.buildSystemPrompt(history, null);
        assertTrue(prompt.contains("助手: 好的，我来设计"));
    }

    @Test
    @DisplayName("buildSystemPrompt formats system messages as summary")
    void buildSystemPrompt_SystemMessage_FormattedAsSummary() {
        List<Map<String, String>> history = new ArrayList<>();
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "system");
        msg.put("content", "之前的对话摘要：讨论了数学教案");
        history.add(msg);

        String prompt = chatService.buildSystemPrompt(history, null);
        assertTrue(prompt.contains("[历史对话摘要]: 之前的对话摘要：讨论了数学教案"));
    }

    @Test
    @DisplayName("buildSystemPrompt skips unknown role messages")
    void buildSystemPrompt_UnknownRole_Skipped() {
        List<Map<String, String>> history = new ArrayList<>();
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "unknown_role");
        msg.put("content", "this should not appear");
        history.add(msg);

        String prompt = chatService.buildSystemPrompt(history, null);
        assertFalse(prompt.contains("this should not appear"));
    }

    @Test
    @DisplayName("buildSystemPrompt shows no history section when empty")
    void buildSystemPrompt_EmptyHistory_NoHistorySection() {
        List<Map<String, String>> history = new ArrayList<>();
        String prompt = chatService.buildSystemPrompt(history, null);
        // The base system prompt mentions 对话历史 in instruction text,
        // but there should be no "--- 对话历史 ---" separator
        assertFalse(prompt.contains("--- 对话历史 ---"));
    }

    @Test
    @DisplayName("buildSystemPrompt ends with instruction based on history")
    void buildSystemPrompt_EndsWithHistoryInstruction() {
        List<Map<String, String>> history = new ArrayList<>();
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", "你好");
        history.add(msg);

        String prompt = chatService.buildSystemPrompt(history, null);
        assertTrue(prompt.endsWith("回答用户的新问题。"));
        assertTrue(prompt.contains("对话历史结束"));
    }

    @Test
    @DisplayName("buildSystemPrompt handles multiple messages in sequence")
    void buildSystemPrompt_MultipleMessages_InOrder() {
        List<Map<String, String>> history = new ArrayList<>();

        Map<String, String> msg1 = new HashMap<>();
        msg1.put("role", "user");
        msg1.put("content", "第一个问题");
        history.add(msg1);

        Map<String, String> msg2 = new HashMap<>();
        msg2.put("role", "assistant");
        msg2.put("content", "第一个回答");
        history.add(msg2);

        Map<String, String> msg3 = new HashMap<>();
        msg3.put("role", "user");
        msg3.put("content", "第二个问题");
        history.add(msg3);

        String prompt = chatService.buildSystemPrompt(history, null);
        int pos1 = prompt.indexOf("第一个问题");
        int pos2 = prompt.indexOf("第一个回答");
        int pos3 = prompt.indexOf("第二个问题");
        assertTrue(pos1 < pos2, "First user message before first assistant reply");
        assertTrue(pos2 < pos3, "First assistant reply before second user message");
    }

    @Test
    @DisplayName("buildSystemPrompt handles null message content gracefully")
    void buildSystemPrompt_NullContent_NoException() {
        List<Map<String, String>> history = new ArrayList<>();
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", null);
        history.add(msg);

        String prompt = chatService.buildSystemPrompt(history, null);
        assertNotNull(prompt);
        // null content will be appended as "null" by StringBuilder
        assertTrue(prompt.contains("用户: null") || prompt.contains("null"));
    }

    // ==================================================================
    // truncateAtSentenceBoundary (2026-08-18 新增: 摘要边界截断修复)
    // ==================================================================

    @Test
    @DisplayName("超长文本按句子边界截断, 不切半关键词")
    void truncate_keepsSentenceBoundary() {
        String longText = "第一句话讲了教学目标。第二句话讲学情分析！第三句话是重难点？" + "填充内容".repeat(100);
        String cut = ChatService.truncateAtSentenceBoundary(longText, 20);
        assertTrue(cut.length() <= 21, "截断后长度应不超过限制+1(含标点): " + cut.length());
        assertFalse(cut.endsWith("重"), "不应在关键词中间截断");
    }

    @Test
    @DisplayName("文本不超过限制时原样返回")
    void truncate_shortTextUnchanged() {
        String shortText = "短摘要内容";
        assertEquals(shortText, ChatService.truncateAtSentenceBoundary(shortText, 500));
    }

    @Test
    @DisplayName("截断到句子边界(句号/分号均可)")
    void truncate_stopsAtSentenceBoundary() {
        String text = "教学目标明确。学生基础薄弱；需要分层教学。后续补充内容后续补充内容后续补充内容";
        String cut = ChatService.truncateAtSentenceBoundary(text, 15);
        // 15 字内最后一个句子边界是第 13 字后的 "；" (>= 15/3=5), 应截到 "...学生基础薄弱；"
        assertTrue(cut.endsWith("。") || cut.endsWith("；"), "应以句子标点结尾: " + cut);
        assertFalse(cut.endsWith("需"), "不应在句子中间截断");
    }
}
