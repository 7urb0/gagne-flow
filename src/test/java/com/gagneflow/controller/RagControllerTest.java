package com.gagneflow.controller;

import com.gagneflow.dto.ChatRequest;
import com.gagneflow.service.chat.ChatSessionService;
import com.gagneflow.service.rag.RagService;
import com.gagneflow.service.vector.VectorSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RagController SSE 端点单元测试")
class RagControllerTest {

    private RagController newController(RagService rag, ChatSessionService session) {
        RagController c = new RagController();
        ReflectionTestUtils.setField(c, "ragService", rag);
        ReflectionTestUtils.setField(c, "chatSessionService", session);
        ThreadPoolExecutor exec = mock(ThreadPoolExecutor.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(exec).execute(any(Runnable.class));
        ReflectionTestUtils.setField(c, "executor", exec);
        return c;
    }

    private ChatRequest newRequest() {
        ChatRequest r = new ChatRequest();
        r.setId("sid1");
        r.setQuestion("勾股定理怎么教");
        return r;
    }

    @Test
    void ragQuery_serviceNull_returnsEmitterWithoutCallingQueryStream() {
        ChatSessionService session = mock(ChatSessionService.class);
        RagController c = newController(null, session);
        SseEmitter emitter = c.ragQuery(newRequest(), 1L);
        assertNotNull(emitter, "ragService 为 null 时也应返回 emitter（错误事件）");
        verifyNoInteractions(session);
    }

    @Test
    void ragQuery_happyPath_streamsAndSavesMessages() {
        RagService rag = mock(RagService.class);
        ChatSessionService session = mock(ChatSessionService.class);
        when(session.getHistory(anyLong(), anyString())).thenReturn(List.of());
        when(session.getSessionMessages(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        doAnswer(inv -> {
            RagService.StreamCallback cb = inv.getArgument(3);
            cb.onComplete("完整教案回答", "思考过程");
            return null;
        }).when(rag).queryStream(anyString(), anyLong(), anyList(), any(RagService.StreamCallback.class));

        RagController c = newController(rag, session);
        SseEmitter emitter = c.ragQuery(newRequest(), 1L);
        assertNotNull(emitter);
        verify(rag).queryStream(eq("勾股定理怎么教"), anyLong(), anyList(), any(RagService.StreamCallback.class));
        verify(session).saveMessage(anyLong(), anyString(), eq("user"), eq("勾股定理怎么教"));
        verify(session).saveMessage(anyLong(), anyString(), eq("assistant"), eq("完整教案回答"));
    }

    @Test
    void ragQuery_historyFromRedis_skipsMysqlFallback() {
        RagService rag = mock(RagService.class);
        ChatSessionService session = mock(ChatSessionService.class);
        when(session.getHistory(anyLong(), anyString()))
                .thenReturn(List.of(Map.of("role", "user", "content", "历史消息")));
        doAnswer(inv -> { ((RagService.StreamCallback) inv.getArgument(3)).onComplete("ok", ""); return null; })
                .when(rag).queryStream(anyString(), anyLong(), anyList(), any(RagService.StreamCallback.class));

        RagController c = newController(rag, session);
        c.ragQuery(newRequest(), 1L);
        verify(session, never()).getSessionMessages(anyLong(), anyString(), anyInt());
    }

    @Test
    void ragQuery_emptyHistory_fallsBackToMysql() {
        RagService rag = mock(RagService.class);
        ChatSessionService session = mock(ChatSessionService.class);
        when(session.getHistory(anyLong(), anyString())).thenReturn(List.of());
        when(session.getSessionMessages(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of());
        doAnswer(inv -> { ((RagService.StreamCallback) inv.getArgument(3)).onComplete("ok", ""); return null; })
                .when(rag).queryStream(anyString(), anyLong(), anyList(), any(RagService.StreamCallback.class));

        RagController c = newController(rag, session);
        c.ragQuery(newRequest(), 1L);
        verify(session).getSessionMessages(anyLong(), anyString(), eq(50));
    }
}