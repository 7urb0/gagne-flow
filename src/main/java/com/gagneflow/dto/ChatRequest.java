package com.gagneflow.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对话请求 DTO，供 ChatController 和 SessionController 使用。
 */
public class ChatRequest {
    @JsonProperty("Id")
    @JsonAlias({"id", "Id"})
    private String id;

    @JsonProperty("Question")
    @JsonAlias({"question", "Question"})
    private String question;

    @JsonProperty("Id")
    public void setId(String id) {
        this.id = id;
    }

    @JsonProperty("Question")
    public void setQuestion(String question) {
        this.question = question;
    }

    public String getId() {
        return this.id;
    }

    public String getQuestion() {
        return this.question;
    }
}
