package com.gagneflow.dto;

/**
 * SSE 事件消息载体，用于 ChatController/RagController/LessonController 的流式推送。
 * 原为 ChatController 内部类，拆分 Controller 后提取为独立 DTO。
 */
public class SseMessage {
    private String type;
    private String data;

    public static SseMessage content(String d) {
        SseMessage m = new SseMessage();
        m.type = "content";
        m.data = d;
        return m;
    }

    public static SseMessage error(String d) {
        SseMessage m = new SseMessage();
        m.type = "error";
        m.data = d;
        return m;
    }

    public static SseMessage done() {
        SseMessage m = new SseMessage();
        m.type = "done";
        m.data = null;
        return m;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getType() {
        return this.type;
    }

    public String getData() {
        return this.data;
    }
}
