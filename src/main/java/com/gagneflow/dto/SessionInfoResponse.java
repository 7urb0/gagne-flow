package com.gagneflow.dto;

/**
 * 会话基本信息响应 DTO。
 */
public class SessionInfoResponse {
    private String sessionId;
    private int messagePairCount;
    private long createTime;

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setMessagePairCount(int messagePairCount) {
        this.messagePairCount = messagePairCount;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public String getSessionId() {
        return this.sessionId;
    }

    public int getMessagePairCount() {
        return this.messagePairCount;
    }

    public long getCreateTime() {
        return this.createTime;
    }
}
