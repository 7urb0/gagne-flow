package com.gagneflow.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.gagneflow.config.security.CurrentUser;
import com.gagneflow.dto.ChatRequest;
import com.gagneflow.dto.SseMessage;
import com.gagneflow.service.chat.ChatService;
import com.gagneflow.constant.UserConstants;
import com.gagneflow.service.chat.ChatSessionService;
import com.gagneflow.service.memory.ConversationMemoryManager;
import com.gagneflow.service.memory.TokenCounter;
import com.gagneflow.service.metrics.PipelineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * 智能对话控制器 — 拆分后精简版（L3 修复）
 * 职责：流式对话 / 线程池状态 / 内部摘要触发
 * 已迁移：RAG → RagController / 教案 → LessonController / 会话 → SessionController
 */
@RestController
@RequestMapping("/api")
public class ChatController {
    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private ChatService chatService;
    @Autowired
    private ChatSessionService chatSessionService;
    @Autowired
    private ConversationMemoryManager memoryManager;
    @Autowired(required = false)
    private ToolCallbackProvider tools;
    @Autowired(required = false)
    private TokenCounter tokenCounter;
    @Autowired(required = false)
    private PipelineMetrics pipelineMetrics;
    @Autowired
    private ThreadPoolExecutor executor;
    @Autowired
    private DashScopeApi dashScopeApi;

    private static final int SUMMARY_TRIGGER_THRESHOLD = 5;
    private static final int MIN_NEW_PAIRS_FOR_SUMMARY = 3;

    /**
     * L5修复: 将匿名用户从共享 0L 改为按 sessionId 生成伪唯一标识
     * 不同匿名会话之间互不干扰，同一会话内保持一致性
     * 已登录用户不受影响，直接返回真实 userId
     */
    private Long resolveUserId(Long userId, String sessionId) {
        return UserConstants.resolveUserId(userId, sessionId);
    }

    @GetMapping(value = {"/chat/pool-status"})
    public ResponseEntity<Map<String, Object>> poolStatus() {
        LinkedHashMap<String, Object> s = new LinkedHashMap<>();
        s.put("activeThreads", this.executor.getActiveCount());
        s.put("poolSize", this.executor.getPoolSize());
        s.put("queueSize", this.executor.getQueue().size());
        s.put("completedTasks", this.executor.getCompletedTaskCount());
        return ResponseEntity.ok(s);
    }

    @PostMapping(value = {"/chat_stream"}, produces = {"text/event-stream;charset=UTF-8"})
    public SseEmitter chatStream(@RequestBody ChatRequest request, @CurrentUser Long userId) {
        SseEmitter emitter = new SseEmitter(Long.valueOf(300000L));
        Long uid = resolveUserId(userId, request.getId());
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            this.sendAndComplete(emitter, SseMessage.error("问题内容不能为空"));
            return emitter;
        }
        this.executor.execute(() -> {
            SecurityContextHolder.getContext().setAuthentication(
                    SecurityContextHolder.getContext().getAuthentication());
            ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
            heartbeatExecutor.scheduleAtFixedRate(() -> {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                } catch (IOException e) {
                    heartbeatExecutor.shutdownNow();
                }
            }, 30L, 30L, TimeUnit.SECONDS);
            try {
                ConversationMemoryManager.ConversationContext ctx =
                        this.memoryManager.buildFullContext(uid, request.getId(), request.getQuestion(), 3);
                List<Map<String, String>> history = ctx.getHistory();
                String longTermCtx = ctx.getLongTermContext();
                DashScopeChatModel chatModel = this.chatService.createStandardChatModel(this.dashScopeApi);
                String systemPrompt = this.chatService.buildSystemPrompt(history, longTermCtx);
                ReactAgent agent = this.chatService.createReactAgent(chatModel, systemPrompt);
                StringBuilder fullAnswerBuilder = new StringBuilder();
                Flux<?> stream = agent.stream(request.getQuestion());
                AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
                emitter.onCompletion(() -> {
                    Disposable s = subscriptionRef.get();
                    if (s != null) s.dispose();
                });
                emitter.onTimeout(() -> {
                    Disposable s = subscriptionRef.get();
                    if (s != null) s.dispose();
                });
                Disposable subscription = stream.subscribe(output -> {
                    try {
                        if (output instanceof StreamingOutput so
                                && so.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
                            String chunk = so.message().getText();
                            if (chunk != null && !chunk.isEmpty()) {
                                fullAnswerBuilder.append(chunk);
                                emitter.send(SseEmitter.event().name("message")
                                        .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                            }
                        }
                    } catch (IOException e) {
                        logger.trace("SSE send failed (client likely disconnected): {}", e.getMessage());
                    }
                }, error -> {
                    try {
                        String errMsg = error != null ? ((Throwable) error).getMessage() : "未知错误";
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.error(errMsg != null ? errMsg : "未知错误"), MediaType.APPLICATION_JSON));
                    } catch (IOException e) {
                        logger.warn("SSE error event send failed (client likely disconnected)");
                    }
                    heartbeatExecutor.shutdownNow(); // 2026-08-19 修复: 流错误时也关闭心跳
                    emitter.completeWithError((Throwable) error);
                }, () -> {
                    try {
                        heartbeatExecutor.shutdownNow(); // 2026-08-19 修复: 流完成时关闭心跳(原 finally 立即关导致心跳从未生效)
                        String fullAnswer = fullAnswerBuilder.toString();
                        this.chatSessionService.saveMessage(uid, request.getId(), "user", request.getQuestion());
                        this.chatSessionService.saveMessage(uid, request.getId(), "assistant", fullAnswer);
                        this.tryTriggerSummary(uid, request.getId());
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });
                subscriptionRef.set(subscription);
            } catch (Exception e) {
                this.sendAndComplete(emitter, SseMessage.error(
                        e.getMessage() != null ? e.getMessage() : "未知错误"));
            } finally {
                // 2026-08-19: 心跳已在 onComplete/onError 中关闭; 此处兜底防泄漏(正常路径回调已关, 幂等)
                heartbeatExecutor.shutdownNow();
            }
        });
        return emitter;
    }

    /** P1修复: 摘要一致性 — 先删 MySQL 再更 Redis，删除失败则跳过 */
    private void tryTriggerSummary(Long userId, String sessionId) {
        com.gagneflow.service.chat.ChatSession session = this.chatSessionService.getRaw(userId, sessionId);
        if (session == null) return;
        int pairCount = session.getMessagePairCount();
        if (pairCount <= SUMMARY_TRIGGER_THRESHOLD) return;
        int since = pairCount - session.getLastSummaryPairCount();
        if (since < MIN_NEW_PAIRS_FOR_SUMMARY) return;
        try {
            List<Map<String, String>> full = session.getMessageHistory();
            int sumPairs = Math.min(session.getLastSummaryPairCount() + 3, pairCount);
            int sumCount = sumPairs * 2;
            if (full.size() < sumCount) return;
            ArrayList<Map<String, String>> toSummarize = new ArrayList<>(full.subList(0, sumCount));
            ArrayList<Map<String, String>> remaining = new ArrayList<>(full.subList(sumCount, full.size()));
            String summary = this.chatService.generateConversationSummary(toSummarize);
            if (summary == null || summary.trim().isEmpty()) return;
            if (summary.length() > 600) {
                logger.warn("摘要过长({}字)，截断至500字: {}", summary.length(), sessionId);
                summary = com.gagneflow.service.chat.ChatService.truncateAtSentenceBoundary(summary, 500);
            }
            if (this.tokenCounter != null) {
                int origTokens = this.tokenCounter.estimate(
                        toSummarize.stream().map(m -> m.getOrDefault("content", ""))
                                .reduce("", String::concat));
                int summaryTokens = this.tokenCounter.estimate(summary);
                double ratio = (double) summaryTokens / (double) Math.max(origTokens, 1);
                logger.info("摘要压缩比: {}/{} tokens = {:.1%}", summaryTokens, origTokens, ratio);
                if (ratio > 0.5) {
                    logger.warn("摘要压缩比过高({:.1%})，跳过: {}", ratio, sessionId);
                    return;
                }
            }
            ArrayList<Map<String, String>> newHistory = new ArrayList<>();
            HashMap<String, String> sm = new HashMap<>();
            sm.put("role", "system");
            sm.put("content", "[历史对话摘要] " + summary);
            newHistory.add(sm);
            newHistory.addAll(remaining);
            // Step 1: 清理 MySQL 旧消息（失败则跳过，保证一致性）
            List<com.gagneflow.entity.SessionMessage> msgs =
                    this.chatSessionService.getSessionMessages(userId, sessionId);
            int deleteCount = Math.min(sumCount, msgs.size());
            if (deleteCount > 0) {
                try {
                    this.chatSessionService.deleteMessages(msgs.subList(0, deleteCount));
                } catch (Exception e) {
                    logger.warn("MySQL 旧消息清理失败，跳过本次摘要以保证数据一致性");
                    return;
                }
            }
            // Step 2: 原子更新 Redis
            this.chatSessionService.replaceHistory(userId, sessionId, newHistory, summary, sumPairs);
            com.gagneflow.service.chat.ChatSession updated =
                    this.chatSessionService.getRaw(userId, sessionId);
            if (this.tokenCounter != null && updated != null) {
                updated.setTotalTokens(this.tokenCounter.estimate(updated.buildFullText()));
                this.chatSessionService.saveRaw(userId, sessionId, updated);
            }
            this.memoryManager.onSummaryGenerated(userId, sessionId, summary);
            if (this.pipelineMetrics != null && this.tokenCounter != null) {
                int origTokens = this.tokenCounter.estimate(
                        toSummarize.stream().map(m -> m.getOrDefault("content", ""))
                                .reduce("", String::concat));
                int sumTokens = this.tokenCounter.estimate(summary);
                this.pipelineMetrics.recordSummaryCompression(sessionId, origTokens, sumTokens,
                        (double) sumTokens / (double) Math.max(origTokens, 1));
            }
        } catch (Exception e) {
            logger.error("对话总结失败: {}", sessionId, e);
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, String data) {
        try {
            HashMap<String, String> payload = new HashMap<>();
            payload.put("type", eventName);
            payload.put("content", data != null ? data : "");
            emitter.send(SseEmitter.event().name("message").data(payload));
        } catch (IOException e) {
            logger.trace("SSE sendEvent failed (client likely disconnected): {}", e.getMessage());
        }
    }

    private void sendAndComplete(SseEmitter emitter, SseMessage msg) {
        try {
            emitter.send(SseEmitter.event().name("message").data(msg, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
