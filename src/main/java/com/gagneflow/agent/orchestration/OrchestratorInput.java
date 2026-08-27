package com.gagneflow.agent.orchestration;

import java.util.List;
import java.util.Map;

/**
 * 对话多 Agent 编排输入(阶段一 supervisor 编排壳)。
 * 与现有单 ReactAgent 路径的输入对齐: 问题 + 会话上下文, 不携带流式回调。
 */
public record OrchestratorInput(
        String question,
        Long userId,
        String sessionId,
        List<Map<String, String>> history,
        String longTermContext) {

    public OrchestratorInput {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        history = history == null ? List.of() : history;
        longTermContext = longTermContext == null ? "" : longTermContext;
    }
}