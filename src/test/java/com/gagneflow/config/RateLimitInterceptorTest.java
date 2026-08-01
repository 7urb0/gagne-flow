package com.gagneflow.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("RateLimitInterceptor 限流拦截器测试")
class RateLimitInterceptorTest {

    private RateLimitInterceptor interceptor;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        interceptor = new RateLimitInterceptor();
        ReflectionTestUtils.setField(interceptor, "stringRedisTemplate", stringRedisTemplate);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    // ============================================================
    // 路径识别
    // ============================================================

    @Nested
    @DisplayName("路径识别")
    class PathResolutionTests {

        @Test
        @DisplayName("未注册路径 → 不限流，直接放行")
        void unregisteredPath_shouldBypass() throws Exception {
            request.setRequestURI("/api/unknown/path");
            assertTrue(interceptor.preHandle(request, response, null));
            verify(stringRedisTemplate, never()).execute(any(), ArgumentMatchers.<String>anyList(), any());
        }

        @Test
        @DisplayName("/api/chat_stream → 触发限流检查")
        void chatStreamPath_shouldTriggerCheck() throws Exception {
            request.setRequestURI("/api/chat_stream");
            // 使用显式类型参数避免 varargs 匹配问题
            when(stringRedisTemplate.execute(
                    ArgumentMatchers.<DefaultRedisScript<String>>any(),
                    ArgumentMatchers.<List<String>>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any()
            )).thenReturn("OK:9");

            assertTrue(interceptor.preHandle(request, response, null));
        }

        @Test
        @DisplayName("/api/lesson_plan → 触发 lesson 限流")
        void lessonPlanPath_shouldTriggerCheck() throws Exception {
            request.setRequestURI("/api/lesson_plan");
            when(stringRedisTemplate.execute(
                    ArgumentMatchers.<DefaultRedisScript<String>>any(),
                    ArgumentMatchers.<List<String>>any(),
                    ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any()
            )).thenReturn("OK:1");

            assertTrue(interceptor.preHandle(request, response, null));
        }

        @Test
        @DisplayName("/api/auth/login → 触发 login 限流")
        void loginPath_shouldTriggerCheck() throws Exception {
            request.setRequestURI("/api/auth/login");
            request.setRemoteAddr("192.168.1.1");
            when(stringRedisTemplate.execute(
                    ArgumentMatchers.<DefaultRedisScript<String>>any(),
                    ArgumentMatchers.<List<String>>any(),
                    ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any()
            )).thenReturn("OK:4");

            assertTrue(interceptor.preHandle(request, response, null));
        }
    }

    // ============================================================
    // 限流逻辑
    // ============================================================

    @Nested
    @DisplayName("限流核心逻辑")
    class RateLimitCoreTests {

        @Test
        @DisplayName("Lua 返回 OK: → 放行请求")
        void luaReturnsOk_shouldPass() throws Exception {
            request.setRequestURI("/api/chat_stream");
            when(stringRedisTemplate.execute(
                    ArgumentMatchers.<DefaultRedisScript<String>>any(),
                    ArgumentMatchers.<List<String>>any(),
                    ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any()
            )).thenReturn("OK:5");

            assertTrue(interceptor.preHandle(request, response, null));
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("Lua 返回 BLOCKED: → 拦截请求，返回 429")
        void luaReturnsBlocked_shouldReturn429() throws Exception {
            request.setRequestURI("/api/rag/query");
            when(stringRedisTemplate.execute(
                    ArgumentMatchers.<DefaultRedisScript<String>>any(),
                    ArgumentMatchers.<List<String>>any(),
                    ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any()
            )).thenReturn("BLOCKED:15");

            assertFalse(interceptor.preHandle(request, response, null));
            assertEquals(429, response.getStatus());
            String content = response.getContentAsString();
            assertTrue(content.contains("429"));
            assertTrue(content.contains("retry_after"));
        }

        @Test
        @DisplayName("Lua 返回 null → 降级放行")
        void luaReturnsNull_shouldFallbackPass() throws Exception {
            request.setRequestURI("/api/chat_stream");
            when(stringRedisTemplate.execute(
                    ArgumentMatchers.<DefaultRedisScript<String>>any(),
                    ArgumentMatchers.<List<String>>any(),
                    ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any()
            )).thenReturn(null);

            assertTrue(interceptor.preHandle(request, response, null));
        }

        @Test
        @DisplayName("Redis 异常 → 降级放行，不阻断正常流量")
        void redisException_shouldFallbackPass() throws Exception {
            request.setRequestURI("/api/chat_stream");
            when(stringRedisTemplate.execute(
                    ArgumentMatchers.<DefaultRedisScript<String>>any(),
                    ArgumentMatchers.<List<String>>any(),
                    ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any()
            )).thenThrow(new RuntimeException("Redis connection refused"));

            assertTrue(interceptor.preHandle(request, response, null));
            assertEquals(200, response.getStatus());
        }
    }
}
