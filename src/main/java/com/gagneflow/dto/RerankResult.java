package com.gagneflow.dto;

/**
 * DashScope Rerank API 重排结果。
 * 从 RerankService 内部类提取为独立 DTO，遵循 SRP。
 */
public class RerankResult {
    private int index;
    private String document;
    private double relevanceScore;
    private boolean degraded = false;

    public RerankResult() {
    }

    public RerankResult(int index, String document, double relevanceScore) {
        this.index = index;
        this.document = document;
        this.relevanceScore = relevanceScore;
    }

    @Override
    public String toString() {
        String doc = this.document != null && this.document.length() > 80
                ? this.document.substring(0, 80) + "..."
                : this.document;
        return String.format("RerankResult{index=%d, score=%.4f, doc=%s}",
                this.index, this.relevanceScore, doc);
    }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }

    public String getDocument() { return document; }
    public void setDocument(String document) { this.document = document; }

    public boolean isDegraded() { return degraded; }
    public void setDegraded(boolean degraded) { this.degraded = degraded; }

    public double getRelevanceScore() { return relevanceScore; }
    public void setRelevanceScore(double relevanceScore) { this.relevanceScore = relevanceScore; }
}
