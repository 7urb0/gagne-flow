package com.gagneflow.dto;

public class DocumentChunk {
    private String content;
    private int startIndex;
    private int endIndex;
    private int chunkIndex;
    private String title;

    public DocumentChunk() {
    }

    public DocumentChunk(String content, int startIndex, int endIndex, int chunkIndex) {
        this.content = content;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.chunkIndex = chunkIndex;
    }

    public String toString() {
        return "DocumentChunk{chunkIndex=" + this.chunkIndex + ", title='" + this.title + "', contentLength=" + (this.content != null ? this.content.length() : 0) + ", startIndex=" + this.startIndex + ", endIndex=" + this.endIndex + "}";
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setStartIndex(int startIndex) {
        this.startIndex = startIndex;
    }

    public void setEndIndex(int endIndex) {
        this.endIndex = endIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return this.content;
    }

    public int getStartIndex() {
        return this.startIndex;
    }

    public int getEndIndex() {
        return this.endIndex;
    }

    public int getChunkIndex() {
        return this.chunkIndex;
    }

    public String getTitle() {
        return this.title;
    }
}
