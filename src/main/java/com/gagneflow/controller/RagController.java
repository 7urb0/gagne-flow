package com.gagneflow.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.gagneflow.config.security.CurrentUser;
import com.gagneflow.constant.UserConstants;
import com.gagneflow.dto.ChatRequest;
import com.gagneflow.dto.SseMessage;
import com.gagneflow.entity.SessionMessage;
import com.gagneflow.service.chat.ChatSessionService;
import com.gagneflow.service.rag.RagService;
import com.gagneflow.service.vector.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 检索控制器 — 从 ChatController 拆分（L3 修复）
 * 职责：处理 /api/rag/query 的 RAG 检索请求
 */
@RestController
@RequestMapping("/api")
public class RagController {
    private static final Logger logger = LoggerFactory.getLogger(RagController.class);

    @Autowired(required = false)
    private RagService ragService;
    @Autowired
    private ChatSessionService chatSessionService;
    @Autowired
    private ThreadPoolExecutor executor;

    private Long resolveUserId(Long userId, String sessionId) {
        return UserConstants.resolveUserId(userId, sessionId);
    }

    @PostMapping(value = {"/rag/query"}, produces = {"text/event-stream;charset=UTF-8"})
    public SseEmitter ragQuery(final @RequestBody ChatRequest request, @CurrentUser Long userId) {
        final SseEmitter emitter = new SseEmitter(Long.valueOf(300000L));
        Long uid = resolveUserId(userId, request.getId());
        if (this.ragService == null) {
            this.sendAndComplete(emitter, SseMessage.error("RAG 服务未启用"));
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
                // 修复13.1: 优先从 Redis 会话缓存读取历史，避免每次查询 MySQL
                List<Map<String, String>> history = this.chatSessionService.getHistory(uid, request.getId());
                // 仅当 Redis 无数据时，降级到 MySQL 读取
                if (history.isEmpty()) {
                    List<SessionMessage> msgs = this.chatSessionService.getSessionMessages(uid, request.getId(), 50);
                    Collections.reverse(msgs);
                    history = new ArrayList<>();
                    for (SessionMessage m : msgs) {
                        history.add(Map.of("role", m.getRole(), "content", m.getContent()));
                    }
                }
                this.ragService.queryStream(request.getQuestion(), uid, history, new RagService.StreamCallback() {
                    @Override
                    public void onSearchResults(List<VectorSearchService.SearchResult> results) {
                        sendEvent(emitter, "search_results",
                                "找到 " + results.size() + " 条参考资料");
                    }

                    @Override
                    public void onContentChunk(String chunk) {
                        sendEvent(emitter, "content", chunk);
                    }

                    @Override
                    public void onReasoningChunk(String chunk) {
                        sendEvent(emitter, "reasoning", chunk);
                    }

                    @Override
                    public void onComplete(String fullContent, String fullReasoning) {
                        chatSessionService.saveMessage(uid, request.getId(), "user", request.getQuestion());
                        chatSessionService.saveMessage(uid, request.getId(), "assistant", fullContent);
                        sendAndComplete(emitter, SseMessage.done());
                    }

                    @Override
                    public void onError(Exception e) {
                        sendAndComplete(emitter, SseMessage.error(
                                e.getMessage() != null ? e.getMessage() : "RAG查询失败"));
                    }
                });
            } catch (Exception e) {
                this.sendAndComplete(emitter, SseMessage.error(
                        e.getMessage() != null ? e.getMessage() : "未知错误"));
            } finally {
                heartbeatExecutor.shutdown();
            }
        });
        return emitter;
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
