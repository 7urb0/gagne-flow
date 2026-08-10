package com.gagneflow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gagneflow.repository.UserRepository;
import io.milvus.client.MilvusServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Auth Integration Tests")
class AuthIntegrationTest {

    // 测试环境无真实 Milvus 容器，用 mock 替代避免 @SpringBootTest 上下文加载失败
    @MockBean private MilvusServiceClient milvusClient;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    private ObjectMapper mapper = new ObjectMapper();

    private static final String TEST_USER = "auth_test_user";
    private static final String TEST_PASS = "Secure123";

    @BeforeEach
    void cleanup() {
        userRepository.deleteAll();
    }

    /** Helper: register a user and return the response body as JSON tree */
    private com.fasterxml.jackson.databind.JsonNode loginUser() throws Exception {
        // Register
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "username", TEST_USER, "password", TEST_PASS))));

        // Login
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "username", TEST_USER, "password", TEST_PASS))))
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("register new user returns success")
        void registerNewUser() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "username", "new_teacher",
                                    "password", "SecurePass123"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("注册成功"));
        }

        @Test
        @DisplayName("register with short password returns 400")
        void registerShortPassword() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "username", "teacher", "password", "123"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("register with empty username returns 400")
        void registerEmptyUsername() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "username", "", "password", "123456"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("register duplicate username returns 400")
        void registerDuplicate() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "username", "dup", "password", "ValidPass1"))));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "username", "dup", "password", "ValidPass1"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("register with missing password field returns 400")
        void registerMissingPassword() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "username", "teacher"))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Login")
    class Login {

        @BeforeEach
        void setup() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                            "username", TEST_USER, "password", TEST_PASS))));
        }

        @Test
        @DisplayName("login with correct credentials returns tokens")
        void loginSuccess() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "username", TEST_USER, "password", TEST_PASS))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isString())
                    .andExpect(jsonPath("$.refreshToken").isString())
                    .andExpect(jsonPath("$.username").value(TEST_USER));
        }

        @Test
        @DisplayName("login with wrong password returns 401")
        void loginWrongPassword() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "username", TEST_USER, "password", "WrongPass"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("login with nonexistent user returns 401")
        void loginNonexistent() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "username", "nobody", "password", "anything"))))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Token Refresh")
    class TokenRefresh {

        @Test
        @DisplayName("refresh with valid token returns new token")
        void refreshValid() throws Exception {
            var loginJson = loginUser();
            String refreshToken = loginJson.get("refreshToken").asText();

            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "refreshToken", refreshToken))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isString());
        }

        @Test
        @DisplayName("refresh with invalid token returns 401")
        void refreshInvalid() throws Exception {
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "refreshToken", "invalid_token_here"))))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Authenticated Access Control")
    class AuthenticatedAccess {

        @Test
        @DisplayName("authenticated request to protected endpoint succeeds")
        void authenticatedRequest() throws Exception {
            var loginJson = loginUser();
            String token = loginJson.get("token").asText();

            mockMvc.perform(get("/api/chat/history")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("unauthenticated request to protected endpoint returns 401")
        void unauthenticatedRequest() throws Exception {
            mockMvc.perform(get("/api/chat/history"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("request with malformed token returns 401")
        void malformedToken() throws Exception {
            mockMvc.perform(get("/api/chat/history")
                            .header("Authorization", "NotBearer malformed"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("request with invalid token returns 401")
        void invalidToken() throws Exception {
            String fakeToken = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI5OTkifQ.invalid";

            mockMvc.perform(get("/api/chat/history")
                            .header("Authorization", "Bearer " + fakeToken))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("public endpoints accessible without authentication")
        void publicEndpoints() throws Exception {
            // 验证公开端点无需鉴权即可访问。
            // 测试环境无 Redis/DashScope，health 可能返回 503(DOWN)，
            // 但不应返回 401/403（鉴权拦截），故断言"非未授权"而非固定 200。
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(result -> assertNotEquals(401, result.getResponse().getStatus()))
                    .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
        }
    }

    @Nested
    @DisplayName("Rate Limiting")
    class RateLimiting {

        /**
         * 限流行为已由 RateLimitInterceptorTest 单测完整覆盖 (OK/BLOCKED/null/exception 四场景)。
         * 集成测试 profile 关闭限流 (gagneflow.rate-limit.enabled=false) 以避免 429 干扰其他用例，
         * 故此处不再重复验证。
         */
        @Test
        @DisplayName("rate limiting is disabled in test profile")
        void rateLimitDisabledInTestProfile() throws Exception {
            String body = mapper.writeValueAsString(Map.of(
                    "username", "ratelimit_test",
                    "password", "Pass1234"
            ));

            int tooMany = 0;
            for (int i = 0; i < 5; i++) {
                int status = mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
                if (status == 429) tooMany++;
            }
            assertEquals(0, tooMany, "test profile 应关闭限流");
        }
    }
}
