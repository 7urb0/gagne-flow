package com.gagneflow.service.chat;

import com.gagneflow.service.memory.TokenCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatSessionTest {

    private ChatSession session;
    private TokenCounter tokenCounter;

    @BeforeEach
    void setUp() {
        session = new ChatSession("test-session-001");
        tokenCounter = new TokenCounter();
    }

    @Test
    void newSession_shouldHaveCorrectInitialState() {
        assertEquals("test-session-001", session.getSessionId());
        assertEquals(0, session.getMessagePairCount());
        assertEquals(0, session.getTotalTokens());
        assertTrue(session.getMessageHistory().isEmpty());
        assertNotNull(session.getCreateTime());
        assertNotNull(session.getLastAccessTime());
    }

    @Test
    void addMessage_shouldIncreasePairCount() {
        session.addMessage("问题1", "回答1", tokenCounter);
        assertEquals(1, session.getMessagePairCount());
        assertEquals(2, session.getMessageHistory().size());
    }

    @Test
    void addMessage_shouldStoreRoles() {
        session.addMessage("你好", "你好！有什么可以帮助你的？", tokenCounter);

        List<Map<String, String>> history = session.getMessageHistory();
        assertEquals("user", history.get(0).get("role"));
        assertEquals("你好", history.get(0).get("content"));
        assertEquals("assistant", history.get(1).get("role"));
    }

    @Test
    void addMultipleMessages_shouldTrackPairs() {
        session.addMessage("Q1", "A1", tokenCounter);
        session.addMessage("Q2", "A2", tokenCounter);
        session.addMessage("Q3", "A3", tokenCounter);

        assertEquals(3, session.getMessagePairCount());
        assertEquals(6, session.getMessageHistory().size());
    }

    @Test
    void buildFullText_shouldConcatenateAllContent() {
        session.addMessage("Q1", "A1", tokenCounter);
        session.addMessage("Q2", "A2", tokenCounter);

        String fullText = session.buildFullText();

        assertTrue(fullText.contains("Q1"));
        assertTrue(fullText.contains("A1"));
        assertTrue(fullText.contains("Q2"));
        assertTrue(fullText.contains("A2"));
    }

    @Test
    void clearHistory_shouldResetAllState() {
        session.addMessage("Q1", "A1", tokenCounter);
        session.setSummary("摘要内容");

        session.clearHistory();

        assertEquals(0, session.getMessagePairCount());
        assertEquals(0, session.getTotalTokens());
        assertTrue(session.getMessageHistory().isEmpty());
        assertNull(session.getSummary());
        assertEquals(0, session.getLastSummaryPairCount());
    }

    @Test
    void setHistory_shouldReplaceAndUpdateCount() {
        session.addMessage("Q1", "A1", tokenCounter);

        List<Map<String, String>> newHistory = List.of(
                Map.of("role", "user", "content", "新问题"),
                Map.of("role", "assistant", "content", "新回答")
        );
        session.setHistory(newHistory);

        assertEquals(1, session.getMessagePairCount());
        assertEquals("新问题", session.getMessageHistory().get(0).get("content"));
    }

    @Test
    void addDirect_shouldAppendSingleMessage() {
        session.addDirect("system", "系统提示");

        assertEquals(1, session.getMessageHistory().size());
        assertEquals("system", session.getMessageHistory().get(0).get("role"));
        assertEquals("系统提示", session.getMessageHistory().get(0).get("content"));
    }

    @Test
    void getMessageHistory_shouldReturnDefensiveCopy() {
        session.addMessage("Q1", "A1", tokenCounter);
        List<Map<String, String>> history = session.getMessageHistory();

        // 修改返回的列表不应影响内部状态
        history.clear();
        assertEquals(2, session.getMessageHistory().size());
    }
}
