package com.gagneflow.agent.orchestration;

import java.util.List;

/**
 * 编排执行结果。path 记录实际路由路径(如 [EXECUTE] 或 [PLAN, FETCH, EXECUTE, REVIEW, FINISH]),
 * 供转正指标的可观测性统计使用。
 */
public final class OrchestratorResult {

    private final String answer;
    private final List<String> path;
    private final boolean reviewPassed;
    private final int tokenEstimate;
    private final long elapsedMs;

    public OrchestratorResult(String answer, List<String> path, boolean reviewPassed,
                              int tokenEstimate, long elapsedMs) {
        this.answer = answer;
        this.path = List.copyOf(path);
        this.reviewPassed = reviewPassed;
        this.tokenEstimate = tokenEstimate;
        this.elapsedMs = elapsedMs;
    }

    public String answer() {
        return this.answer;
    }

    public List<String> path() {
        return this.path;
    }

    public boolean reviewPassed() {
        return this.reviewPassed;
    }

    public int tokenEstimate() {
        return this.tokenEstimate;
    }

    public long elapsedMs() {
        return this.elapsedMs;
    }

    @Override
    public String toString() {
        return "OrchestratorResult{path=" + this.path + ", reviewPassed=" + this.reviewPassed
                + ", tokenEstimate=" + this.tokenEstimate + ", elapsedMs=" + this.elapsedMs + "}";
    }
}