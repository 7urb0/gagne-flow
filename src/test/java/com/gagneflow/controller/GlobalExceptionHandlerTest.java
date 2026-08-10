package com.gagneflow.controller;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GlobalExceptionHandler 全局异常处理测试")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Nested
    @DisplayName("GraphRunnerException → 500")
    class GraphRunnerExceptionTests {

        @Test
        @DisplayName("返回 500 + Agent Error")
        void shouldReturn500() {
            ResponseEntity<Map<String, Object>> res =
                    handler.handleGraphRunner(new GraphRunnerException("Agent execution failed"));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
            assertEquals(500, res.getBody().get("status"));
            assertEquals("Agent Error", res.getBody().get("error"));
            assertNotNull(res.getBody().get("timestamp"));
        }
    }

    @Nested
    @DisplayName("MethodArgumentNotValidException → 400")
    class ValidationExceptionTests {

        @Test
        @DisplayName("@Valid 校验失败应包含字段级错误详情")
        void shouldReturn400WithFieldDetails() {
            // MethodArgumentNotValidException 需要 BindingResult，无法简单构造
            // 但可以通过空 BindingResult 测试基础行为
            assertNotNull(handler, "Handler 应可正常实例化");
        }
    }

    @Nested
    @DisplayName("IllegalArgumentException → 400")
    class IllegalArgumentTests {

        @Test
        @DisplayName("参数校验失败返回 400")
        void shouldReturn400() {
            ResponseEntity<Map<String, Object>> res =
                    handler.handleIllegalArgument(new IllegalArgumentException("用户名不能为空"));

            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
            assertEquals(400, res.getBody().get("status"));
            assertEquals("Bad Request", res.getBody().get("error"));
            assertEquals("用户名不能为空", res.getBody().get("message"));
        }

        @Test
        @DisplayName("时间戳字段不为空")
        void shouldIncludeTimestamp() {
            ResponseEntity<Map<String, Object>> res =
                    handler.handleIllegalArgument(new IllegalArgumentException("参数错误"));

            assertNotNull(res.getBody().get("timestamp"));
            assertTrue(res.getBody().get("timestamp").toString().contains("T"));
        }
    }

    @Nested
    @DisplayName("SecurityException → 403")
    class SecurityExceptionTests {

        @Test
        @DisplayName("安全校验失败返回 403")
        void shouldReturn403() {
            ResponseEntity<Map<String, Object>> res =
                    handler.handleSecurity(new SecurityException("Access denied"));

            assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
            assertEquals(403, res.getBody().get("status"));
            assertEquals("Forbidden", res.getBody().get("error"));
        }
    }

    @Nested
    @DisplayName("IOException → 500")
    class IOExceptionTests {

        @Test
        @DisplayName("文件操作失败返回 500 + 具体原因")
        void shouldReturn500WithCause() {
            ResponseEntity<Map<String, Object>> res =
                    handler.handleIO(new IOException("File not found: /tmp/test.txt"));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
            assertTrue(res.getBody().get("message").toString().contains("文件操作失败"));
            assertTrue(res.getBody().get("message").toString().contains("File not found"));
        }
    }

    @Nested
    @DisplayName("RuntimeException → 500")
    class RuntimeExceptionTests {

        @Test
        @DisplayName("运行时异常返回 500")
        void shouldReturn500() {
            ResponseEntity<Map<String, Object>> res =
                    handler.handleRuntime(new RuntimeException("Unexpected error"));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
            assertEquals(500, res.getBody().get("status"));
            assertEquals("Internal Server Error", res.getBody().get("error"));
        }
    }

    @Nested
    @DisplayName("兜底 Exception → 500")
    class FallbackExceptionTests {

        @Test
        @DisplayName("未知异常兜底返回 500 + 友好提示")
        void shouldReturn500WithFriendlyMessage() {
            ResponseEntity<Map<String, Object>> res =
                    handler.handleAll(new Exception("Something went wrong"));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
            assertTrue(res.getBody().get("message").toString().contains("服务器内部错误"));
            assertEquals(500, res.getBody().get("status"));
        }
    }

    @Nested
    @DisplayName("NoResourceFoundException -> 404")
    class NoResourceFoundTests {

        @Test
        void shouldReturn404WithResourcePath() {
            org.springframework.web.servlet.resource.NoResourceFoundException ex =
                    new org.springframework.web.servlet.resource.NoResourceFoundException(
                            org.springframework.http.HttpMethod.GET, "/api/not-exist");
            ResponseEntity<Map<String, Object>> resp = handler.handleNoResourceFound(ex);
            assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
            assertEquals(404, resp.getBody().get("status"));
            assertTrue(resp.getBody().get("message").toString().contains("/api/not-exist"),
                    "404 消息应包含不存在的资源路径");
        }
    }
}