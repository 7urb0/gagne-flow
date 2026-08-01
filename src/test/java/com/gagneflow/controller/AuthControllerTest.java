package com.gagneflow.controller;

import java.lang.reflect.Field;
import java.util.Map;

import com.gagneflow.config.security.JwtUtil;
import com.gagneflow.entity.User;
import com.gagneflow.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AuthController 认证控制器测试")
class AuthControllerTest {

    private AuthController controller;
    private UserService userService;
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() throws Exception {
        userService = mock(UserService.class);
        jwtUtil = mock(JwtUtil.class);

        controller = new AuthController();
        injectField("userService", userService);
        injectField("jwtUtil", jwtUtil);
    }

    private void injectField(String name, Object value) throws Exception {
        Field field = AuthController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    @Nested
    @DisplayName("register")
    class RegisterTests {

        @Test
        @DisplayName("正常注册 → 200 + message")
        void validInput_shouldReturn200() {
            when(userService.register("newuser", "password123"))
                    .thenReturn(new User("newuser", "hashed"));

            ResponseEntity<?> res = controller.register(Map.of(
                    "username", "newuser", "password", "password123"));

            assertEquals(HttpStatus.OK, res.getStatusCode());
            Map<?, ?> body = (Map<?, ?>) res.getBody();
            assertEquals("注册成功", body.get("message"));
        }

        @Test
        @DisplayName("空用户名 → 400")
        void emptyUsername_shouldReturn400() {
            ResponseEntity<?> res = controller.register(Map.of(
                    "username", "", "password", "password123"));

            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
            Map<?, ?> body = (Map<?, ?>) res.getBody();
            assertTrue(body.get("error").toString().contains("不能为空"));
        }

        @Test
        @DisplayName("密码不足 6 位 → 400")
        void shortPassword_shouldReturn400() {
            ResponseEntity<?> res = controller.register(Map.of(
                    "username", "user", "password", "12345"));

            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
            Map<?, ?> body = (Map<?, ?>) res.getBody();
            assertTrue(body.get("error").toString().contains("6个字符"));
        }

        @Test
        @DisplayName("用户名已存在 → 400")
        void duplicateUsername_shouldReturn400() {
            when(userService.register("existing", "password123"))
                    .thenThrow(new IllegalArgumentException("用户名已存在"));

            ResponseEntity<?> res = controller.register(Map.of(
                    "username", "existing", "password", "password123"));

            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        }
    }

    @Nested
    @DisplayName("login")
    class LoginTests {

        @Test
        @DisplayName("正确凭据 → 200 + token + refreshToken")
        void validCredentials_shouldReturnTokens() {
            User user = new User("testuser", "hash");
            user.setId(42L);
            when(userService.findByUsername("testuser")).thenReturn(user);
            when(userService.passwordMatches("password123", user)).thenReturn(true);
            when(jwtUtil.generateToken(42L, "testuser")).thenReturn("access-token-xxx");
            when(jwtUtil.generateRefreshToken(42L)).thenReturn("refresh-token-xxx");

            ResponseEntity<?> res = controller.login(Map.of(
                    "username", "testuser", "password", "password123"));

            assertEquals(HttpStatus.OK, res.getStatusCode());
            Map<?, ?> body = (Map<?, ?>) res.getBody();
            assertEquals("access-token-xxx", body.get("token"));
            assertEquals("refresh-token-xxx", body.get("refreshToken"));
            assertEquals("testuser", body.get("username"));
        }

        @Test
        @DisplayName("用户不存在 → 401")
        void userNotFound_shouldReturn401() {
            when(userService.findByUsername("ghost")).thenReturn(null);

            ResponseEntity<?> res = controller.login(Map.of(
                    "username", "ghost", "password", "password123"));

            assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
            Map<?, ?> body = (Map<?, ?>) res.getBody();
            assertEquals("用户名或密码错误", body.get("error"));
        }

        @Test
        @DisplayName("密码错误 → 401")
        void wrongPassword_shouldReturn401() {
            User user = new User("testuser", "hash");
            when(userService.findByUsername("testuser")).thenReturn(user);
            when(userService.passwordMatches("wrong", user)).thenReturn(false);

            ResponseEntity<?> res = controller.login(Map.of(
                    "username", "testuser", "password", "wrong"));

            assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        }

        @Test
        @DisplayName("空用户名 → 400")
        void emptyCredentials_shouldReturn400() {
            ResponseEntity<?> res = controller.login(Map.of(
                    "username", "", "password", ""));

            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        }
    }

    @Nested
    @DisplayName("refresh")
    class RefreshTests {

        @Test
        @DisplayName("有效 refresh token → 200 + 新 access token")
        void validRefreshToken_shouldReturnNewAccessToken() {
            String refreshToken = "valid-refresh-token";
            when(jwtUtil.validateToken(refreshToken)).thenReturn(true);
            when(jwtUtil.parseToken(refreshToken)).thenReturn(
                    io.jsonwebtoken.Jwts.claims().subject("testuser")
                            .add("userId", 42L).add("type", "refresh").build());
            when(jwtUtil.generateToken(42L, "testuser")).thenReturn("new-access-token");

            ResponseEntity<?> res = controller.refresh(Map.of("refreshToken", refreshToken));

            assertEquals(HttpStatus.OK, res.getStatusCode());
            Map<?, ?> body = (Map<?, ?>) res.getBody();
            assertEquals("new-access-token", body.get("token"));
        }

        @Test
        @DisplayName("无效 refresh token → 401")
        void invalidRefreshToken_shouldReturn401() {
            when(jwtUtil.validateToken("bad-token")).thenReturn(false);

            ResponseEntity<?> res = controller.refresh(Map.of("refreshToken", "bad-token"));

            assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        }

        @Test
        @DisplayName("非 refresh 类型 token → 401")
        void nonRefreshTypeToken_shouldReturn401() {
            when(jwtUtil.validateToken("access-style-token")).thenReturn(true);
            when(jwtUtil.parseToken("access-style-token")).thenReturn(
                    io.jsonwebtoken.Jwts.claims().subject("testuser")
                            .add("type", "access").build());

            ResponseEntity<?> res = controller.refresh(Map.of(
                    "refreshToken", "access-style-token"));

            assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        }
    }

    @Nested
    @DisplayName("logout")
    class LogoutTests {

        @Test
        @DisplayName("有效 Bearer token → 200 + 注销成功")
        void validToken_shouldRevokeAndReturn200() {
            when(jwtUtil.validateToken("valid-token")).thenReturn(true);

            ResponseEntity<?> res = controller.logout("Bearer valid-token");

            assertEquals(HttpStatus.OK, res.getStatusCode());
            Map<?, ?> body = (Map<?, ?>) res.getBody();
            assertEquals("已成功注销", body.get("message"));
            verify(jwtUtil).revokeToken("valid-token");
        }

        @Test
        @DisplayName("无 Authorization header → 400")
        void noAuthHeader_shouldReturn400() {
            ResponseEntity<?> res = controller.logout(null);

            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
            Map<?, ?> body = (Map<?, ?>) res.getBody();
            assertTrue(body.get("error").toString().contains("Authorization"));
        }

        @Test
        @DisplayName("无效 token → 401")
        void invalidToken_shouldReturn401() {
            when(jwtUtil.validateToken("bad-token")).thenReturn(false);

            ResponseEntity<?> res = controller.logout("Bearer bad-token");

            assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
            verify(jwtUtil, never()).revokeToken(anyString());
        }
    }
}
