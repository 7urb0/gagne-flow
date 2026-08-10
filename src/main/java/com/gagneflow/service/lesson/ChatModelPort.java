package com.gagneflow.service.lesson;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * LLM 调用端口抽象（2026-08-07 新增）。
 * 目的: 解耦 AddrfPipeline 与具体模型实现（DashScopeChatModel），
 * 使五阶段编排主流程可单测（mock 接口即可，无需真实模型/API）。
 * 生产实现: {@link DashScopeChatModelPort}（包装 DashScopeChatModel）。
 */
public interface ChatModelPort {

    /** 同步调用: 返回完整响应 */
    ChatResponse call(Prompt prompt);

    /** 流式调用: 返回响应流（对接 SSE） */
    Flux<ChatResponse> stream(Prompt prompt);
}
