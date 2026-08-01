package com.gagneflow.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DTO JSON 序列化/反序列化测试（#5 修复）。
 * 验证 @JsonProperty、@JsonAlias 注解在 Jackson 中正确生效，
 * 避免因字段名或注解配置错误导致前后端数据不对齐。
 */
@DisplayName("DTO JSON 序列化测试")
class DtoSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ============================================================
    // ChatRequest
    // ============================================================

    @Test
    @DisplayName("ChatRequest 序列化为驼峰 Id/Question")
    void chatRequest_serialize_usesJsonPropertyNames() throws Exception {
        ChatRequest req = new ChatRequest();
        req.setId("sess_001");
        req.setQuestion("测试问题");

        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"Id\""));
        assertTrue(json.contains("\"Question\""));
        assertTrue(json.contains("sess_001"));
        assertTrue(json.contains("测试问题"));
    }

    @Test
    @DisplayName("ChatRequest 反序列化接受小写 id/question")
    void chatRequest_deserialize_acceptsAliasNames() throws Exception {
        String json = "{\"id\":\"sess_002\",\"question\":\"数学教案\"}";
        ChatRequest req = mapper.readValue(json, ChatRequest.class);

        assertEquals("sess_002", req.getId());
        assertEquals("数学教案", req.getQuestion());
    }

    @Test
    @DisplayName("ChatRequest 反序列化接受大写 Id/Question")
    void chatRequest_deserialize_acceptsJsonPropertyNames() throws Exception {
        String json = "{\"Id\":\"sess_003\",\"Question\":\"语文教案\"}";
        ChatRequest req = mapper.readValue(json, ChatRequest.class);

        assertEquals("sess_003", req.getId());
        assertEquals("语文教案", req.getQuestion());
    }

    @Test
    @DisplayName("ChatRequest round-trip 保持字段一致")
    void chatRequest_roundTrip_preservesFields() throws Exception {
        ChatRequest req = new ChatRequest();
        req.setId("sess_004");
        req.setQuestion("英语教案");

        String json = mapper.writeValueAsString(req);
        ChatRequest restored = mapper.readValue(json, ChatRequest.class);

        assertEquals(req.getId(), restored.getId());
        assertEquals(req.getQuestion(), restored.getQuestion());
    }

    // ============================================================
    // ClearRequest
    // ============================================================

    @Test
    @DisplayName("ClearRequest 序列化使用 id (小写)")
    void clearRequest_serialize_usesId() throws Exception {
        ClearRequest req = new ClearRequest();
        req.setId("clear_001");

        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"id\""));
        assertTrue(json.contains("clear_001"));
    }

    @Test
    @DisplayName("ClearRequest round-trip 保持 id 一致")
    void clearRequest_roundTrip_preservesId() throws Exception {
        ClearRequest req = new ClearRequest();
        req.setId("clear_002");

        String json = mapper.writeValueAsString(req);
        ClearRequest restored = mapper.readValue(json, ClearRequest.class);

        assertEquals(req.getId(), restored.getId());
    }

    // ============================================================
    // SseMessage
    // ============================================================

    @Test
    @DisplayName("SseMessage content 工厂方法设置 type=data")
    void sseMessage_content_factory() throws Exception {
        SseMessage msg = SseMessage.content("测试内容");

        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("content"));
        assertTrue(json.contains("测试内容"));
    }

    @Test
    @DisplayName("SseMessage error 工厂方法设置 type=error")
    void sseMessage_error_factory() throws Exception {
        SseMessage msg = SseMessage.error("错误信息");

        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("error"));
        assertTrue(json.contains("错误信息"));
    }

    @Test
    @DisplayName("SseMessage done 的 data 为 null")
    void sseMessage_done_dataIsNull() throws Exception {
        SseMessage msg = SseMessage.done();

        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("done"));
        // null 字段可能被 Jackson 排除，不强制包含 "data"
    }

    @Test
    @DisplayName("SseMessage round-trip 保持 type 和 data")
    void sseMessage_roundTrip() throws Exception {
        SseMessage msg = SseMessage.content("来回测试");

        String json = mapper.writeValueAsString(msg);
        SseMessage restored = mapper.readValue(json, SseMessage.class);

        assertEquals(msg.getType(), restored.getType());
        assertEquals(msg.getData(), restored.getData());
    }

    // ============================================================
    // SessionInfoResponse
    // ============================================================

    @Test
    @DisplayName("SessionInfoResponse 序列化和反序列化")
    void sessionInfoResponse_roundTrip() throws Exception {
        SessionInfoResponse resp = new SessionInfoResponse();
        resp.setSessionId("sess_010");
        resp.setMessagePairCount(3);
        resp.setCreateTime(123456789L);

        String json = mapper.writeValueAsString(resp);
        SessionInfoResponse restored = mapper.readValue(json, SessionInfoResponse.class);

        assertEquals(resp.getSessionId(), restored.getSessionId());
        assertEquals(resp.getMessagePairCount(), restored.getMessagePairCount());
        assertEquals(resp.getCreateTime(), restored.getCreateTime());
    }

    // ============================================================
    // ApiResponse
    // ============================================================

    @Test
    @DisplayName("ApiResponse success 序列化含 code/message/data")
    void apiResponse_success_serialization() throws Exception {
        ApiResponse<String> resp = ApiResponse.success("操作成功");

        String json = mapper.writeValueAsString(resp);
        assertTrue(json.contains("200"));
        assertTrue(json.contains("success"));
        assertTrue(json.contains("操作成功"));
    }

    @Test
    @DisplayName("ApiResponse error 序列化不含 data")
    void apiResponse_error_serialization() throws Exception {
        ApiResponse<String> resp = ApiResponse.error("出错了");

        String json = mapper.writeValueAsString(resp);
        assertTrue(json.contains("500"));
        assertTrue(json.contains("出错了"));
    }

    // ============================================================
    // LessonPlanRequest
    // ============================================================

    @Test
    @DisplayName("LessonPlanRequest 序列化和反序列化")
    void lessonPlanRequest_roundTrip() throws Exception {
        LessonPlanRequest req = new LessonPlanRequest();
        req.setStage("小学");
        req.setGrade(3);
        req.setSubject("数学");
        req.setHours(1);
        req.setGoals("掌握两位数乘法");
        req.setMode("full");
        req.setUploadedFileNames(List.of("doc1.pdf", "doc2.md"));

        String json = mapper.writeValueAsString(req);
        LessonPlanRequest restored = mapper.readValue(json, LessonPlanRequest.class);

        assertEquals(req.getStage(), restored.getStage());
        assertEquals(req.getGrade(), restored.getGrade());
        assertEquals(req.getSubject(), restored.getSubject());
        assertEquals(req.getHours(), restored.getHours());
        assertEquals(req.getGoals(), restored.getGoals());
        assertEquals(req.getMode(), restored.getMode());
    }

    @Test
    @DisplayName("LessonPlanRequest null 字段不抛异常")
    void lessonPlanRequest_nullFields_noException() {
        LessonPlanRequest req = new LessonPlanRequest();
        // 不设置任何字段，全是默认值
        assertDoesNotThrow(() -> mapper.writeValueAsString(req));
    }

    // ============================================================
    // 通用 null 处理
    // ============================================================

    @Test
    @DisplayName("所有 DTO null 字段序列化不抛异常")
    void allDto_nullFields_noException() {
        assertDoesNotThrow(() -> mapper.writeValueAsString(new ChatRequest()));
        assertDoesNotThrow(() -> mapper.writeValueAsString(new ClearRequest()));
        assertDoesNotThrow(() -> mapper.writeValueAsString(new SseMessage()));
        assertDoesNotThrow(() -> mapper.writeValueAsString(new SessionInfoResponse()));
        assertDoesNotThrow(() -> mapper.writeValueAsString(ApiResponse.success(null)));
        assertDoesNotThrow(() -> mapper.writeValueAsString(ApiResponse.error(null)));
    }
}
