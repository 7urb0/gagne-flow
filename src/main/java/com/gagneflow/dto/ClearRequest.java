package com.gagneflow.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 清除对话历史请求 DTO。
 */
public class ClearRequest {
    @JsonProperty("id")
    private String id;

    @JsonProperty("id")
    public void setId(String id) {
        this.id = id;
    }

    public String getId() {
        return this.id;
    }
}
