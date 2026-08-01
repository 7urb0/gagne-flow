package com.gagneflow.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.gagneflow.config.security.CurrentUser;
import com.gagneflow.constant.UserConstants;
import com.gagneflow.dto.ApiResponse;
import com.gagneflow.dto.ChatRequest;
import com.gagneflow.dto.ClearRequest;
import com.gagneflow.dto.SessionInfoResponse;
import com.gagneflow.entity.SessionMessage;
import com.gagneflow.service.chat.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理控制器 — 从 ChatController 拆分（L3 修复）
 * 职责：会话历史查询、清除、注册、消息获取
 */
@RestController
@RequestMapping("/api")
public class SessionController {
    private static final Logger logger = LoggerFactory.getLogger(SessionController.class);
    @Autowired
    private ChatSessionService chatSessionService;
    @Autowired
    private com.gagneflow.service.memory.LongTermMemoryService longTermMemoryService;

    private Long resolveUserId(Long userId, String sessionId) {
        return UserConstants.resolveUserId(userId, sessionId);
    }

    @PostMapping(value = {"/chat/clear"})
    public ResponseEntity<ApiResponse<String>> clearChatHistory(
            @RequestBody ClearRequest request, @CurrentUser Long userId) {
        Long uid = resolveUserId(userId, request.getId());
        if (request.getId() == null || request.getId().isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
        }
        this.chatSessionService.deleteSessionMessages(uid, request.getId());
        // P0: 联动清理长期记忆 (Redis ltm:* + MySQL ltm_fact)，避免已删除会话的事实残留被后续检索引用
        try {
            this.longTermMemoryService.clearSessionFacts(uid, request.getId());
        } catch (Exception e) {
            // 不影响主流程，仅告警
            logger.warn("清理会话长期记忆失败: sessionId={}, 原因: {}", request.getId(), e.getMessage());
        }
        return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
    }

    @GetMapping(value = {"/chat/session/{sessionId}"})
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(
            @PathVariable String sessionId, @CurrentUser Long userId) {
        Long uid = resolveUserId(userId, sessionId);
        int pairCount = this.chatSessionService.getMessagePairCount(uid, sessionId);
        SessionInfoResponse r = new SessionInfoResponse();
        r.setSessionId(sessionId);
        r.setMessagePairCount(pairCount);
        r.setCreateTime(System.currentTimeMillis());
        return ResponseEntity.ok(ApiResponse.success(r));
    }

    @GetMapping(value = {"/chat/history"})
    public ResponseEntity<List<Map<String, Object>>> getUserHistory(@CurrentUser Long userId) {
        Long uid = userId != null ? userId : UserConstants.DEFAULT_USER_ID;
        return ResponseEntity.ok(this.chatSessionService.getUserSessions(uid));
    }

    @PostMapping(value = {"/chat/history/register"})
    public ResponseEntity<?> registerHistory(
            @RequestBody Map<String, String> body, @CurrentUser Long userId) {
        String sessionId = body.get("sessionId");
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
        }
        Long uid = resolveUserId(userId, sessionId);
        this.chatSessionService.registerSession(uid, sessionId,
                body.getOrDefault("title", ""));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping(value = {"/chat/messages/{sessionId}"})
    public ResponseEntity<List<Map<String, String>>> getMessages(
            @PathVariable String sessionId, @CurrentUser Long userId) {
        Long uid = resolveUserId(userId, sessionId);
        List<SessionMessage> msgs = this.chatSessionService.getSessionMessages(uid, sessionId);
        ArrayList<Map<String, String>> result = new ArrayList<>();
        for (SessionMessage m : msgs) {
            result.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }
        return ResponseEntity.ok(result);
    }
}
