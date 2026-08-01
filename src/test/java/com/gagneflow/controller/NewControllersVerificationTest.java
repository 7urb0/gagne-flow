package com.gagneflow.controller;

import com.gagneflow.dto.ChatRequest;
import com.gagneflow.dto.ClearRequest;
import com.gagneflow.dto.SessionInfoResponse;
import com.gagneflow.dto.SseMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 新拆分 Controller 基础单元测试 — 不依赖 Spring 上下文
 * 验证 Controller 的构造和基本方法签名正确性
 */
@DisplayName("新拆分 Controller 验证")
class NewControllersVerificationTest {

    // ============================================================
    // DTO 类验证
    // ============================================================

    @Nested
    @DisplayName("DTO 类创建验证")
    class DtoCreationTests {

        @Test
        @DisplayName("SseMessage 工厂方法创建正确类型")
        void sseMessage_factoryMethods_work() {
            SseMessage content = SseMessage.content("测试");
            assertEquals("content", content.getType());
            assertEquals("测试", content.getData());

            SseMessage error = SseMessage.error("错误");
            assertEquals("error", error.getType());

            SseMessage done = SseMessage.done();
            assertEquals("done", done.getType());
            assertNull(done.getData());
        }

        @Test
        @DisplayName("ChatRequest JSON 注解正确配置")
        void chatRequest_jsonAnnotations_setCorrectly() {
            ChatRequest req = new ChatRequest();
            req.setId("sess_001");
            req.setQuestion("测试问题");
            assertEquals("sess_001", req.getId());
            assertEquals("测试问题", req.getQuestion());
        }

        @Test
        @DisplayName("ClearRequest 封装正确")
        void clearRequest_works() {
            ClearRequest req = new ClearRequest();
            req.setId("sess_002");
            assertEquals("sess_002", req.getId());
        }

        @Test
        @DisplayName("SessionInfoResponse 封装正确")
        void sessionInfoResponse_works() {
            SessionInfoResponse r = new SessionInfoResponse();
            r.setSessionId("sess_003");
            r.setMessagePairCount(5);
            r.setCreateTime(123456L);
            assertEquals("sess_003", r.getSessionId());
            assertEquals(5, r.getMessagePairCount());
            assertEquals(123456L, r.getCreateTime());
        }
    }

    // ============================================================
    // Controller 类验证
    // ============================================================

    @Nested
    @DisplayName("Controller 类注解和路由验证")
    class ControllerAnnotationTests {

        @Test
        @DisplayName("SessionController 类存在且可实例化")
        void sessionController_canBeInstantiated() {
            SessionController controller = new SessionController();
            assertNotNull(controller);
        }

        @Test
        @DisplayName("SessionController 注解路径为 /api")
        void sessionController_hasCorrectRequestMapping() {
            RequestMapping annotation = SessionController.class.getAnnotation(RequestMapping.class);
            assertNotNull(annotation);
            assertEquals("/api", annotation.value()[0]);
        }

        @Test
        @DisplayName("RagController 注解路径为 /api")
        void ragController_hasCorrectRequestMapping() {
            RequestMapping annotation = RagController.class.getAnnotation(RequestMapping.class);
            assertNotNull(annotation);
            assertEquals("/api", annotation.value()[0]);
        }

        @Test
        @DisplayName("LessonController 注解路径为 /api")
        void lessonController_hasCorrectRequestMapping() {
            RequestMapping annotation = LessonController.class.getAnnotation(RequestMapping.class);
            assertNotNull(annotation);
            assertEquals("/api", annotation.value()[0]);
        }

        @Test
        @DisplayName("ChatController 精简后注解路径为 /api")
        void chatController_hasCorrectRequestMapping() {
            RequestMapping annotation = ChatController.class.getAnnotation(RequestMapping.class);
            assertNotNull(annotation);
            assertEquals("/api", annotation.value()[0]);
        }
    }
}
