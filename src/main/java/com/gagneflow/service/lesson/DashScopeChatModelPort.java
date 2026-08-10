package com.gagneflow.service.lesson;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * ChatModelPort 的 DashScope 适配器（2026-08-07 新增）。
 * 非 Spring Bean: 由调用方（LessonController）手动 new 包装，
 * 避免依赖 DashScopeChatModel 的自动配置 Bean，保持现有构建逻辑零变化。
 */
public class DashScopeChatModelPort implements ChatModelPort {

    private final DashScopeChatModel delegate;

    public DashScopeChatModelPort(DashScopeChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return this.delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return this.delegate.stream(prompt);
    }
}
