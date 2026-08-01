package com.gagneflow.service.chat;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.gagneflow.service.memory.TokenCounter;

public class ChatSession
implements Serializable {
    private static final long serialVersionUID = 1L;
    private String sessionId;
    private List<Map<String, String>> messageHistory = new ArrayList<Map<String, String>>();
    private int messagePairCount;
    private int totalTokens;
    private String summary;
    private int lastSummaryPairCount;
    private Instant createTime;
    private Instant lastAccessTime;

    public ChatSession() {
    }

    public ChatSession(String sessionId) {
        this.sessionId = sessionId;
        this.createTime = Instant.now();
        this.lastAccessTime = Instant.now();
    }

    public synchronized void addMessage(String userQuestion, String aiAnswer, int maxWindowSize, TokenCounter tokenCounter) {
        HashMap<String, String> userMsg = new HashMap<String, String>();
        userMsg.put("role", "user");
        userMsg.put("content", userQuestion);
        this.messageHistory.add(userMsg);
        HashMap<String, String> assistantMsg = new HashMap<String, String>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", aiAnswer);
        this.messageHistory.add(assistantMsg);
        if (tokenCounter != null) {
            this.totalTokens = tokenCounter.estimate(this.buildFullText());
        }
        this.messagePairCount = this.messageHistory.size() / 2;
        this.lastAccessTime = Instant.now();
    }

    public String buildFullText() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> msg : this.messageHistory) {
            sb.append(msg.getOrDefault("content", "")).append("\n");
        }
        return sb.toString();
    }

    public void clearHistory() {
        this.messageHistory.clear();
        this.messagePairCount = 0;
        this.totalTokens = 0;
        this.summary = null;
        this.lastSummaryPairCount = 0;
        this.lastAccessTime = Instant.now();
    }

    public void setHistory(List<Map<String, String>> newHistory) {
        this.messageHistory = new ArrayList<Map<String, String>>(newHistory);
        this.messagePairCount = this.messageHistory.size() / 2;
        this.totalTokens = 0;
        this.lastAccessTime = Instant.now();
    }

    public void addDirect(String role, String content) {
        HashMap<String, String> msg = new HashMap<String, String>();
        msg.put("role", role);
        msg.put("content", content);
        this.messageHistory.add(msg);
        this.messagePairCount = this.messageHistory.size() / 2;
        this.lastAccessTime = Instant.now();
    }

    public String getSessionId() {
        return this.sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public List<Map<String, String>> getMessageHistory() {
        return new ArrayList<Map<String, String>>(this.messageHistory);
    }

    public int getMessagePairCount() {
        return this.messagePairCount;
    }

    public int getTotalTokens() {
        return this.totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getSummary() {
        return this.summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public int getLastSummaryPairCount() {
        return this.lastSummaryPairCount;
    }

    public void setLastSummaryPairCount(int lastSummaryPairCount) {
        this.lastSummaryPairCount = lastSummaryPairCount;
    }

    public Instant getCreateTime() {
        return this.createTime;
    }

    public Instant getLastAccessTime() {
        return this.lastAccessTime;
    }
}
