package com.gagneflow.config.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("JwtUtil 安全组件测试")
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret",
                "gagneflow-test-secret-key-32-bytes!");
        ReflectionTestUtils.setField(jwtUtil, "stringRedisTemplate", stringRedisTemplate);
        // 跳过 @PostConstruct 校验
    }

    // ============================================================
    // generateToken / generateRefreshToken
    // ============================================================

    @Nested
    @DisplayName("Token 生成")
    class TokenGenerationTests {

        @Test
        @DisplayName("generateToken 生成可解析的 access token")
        void generateToken_shouldCreateParsableAccessToken() {
            String token = jwtUtil.generateToken(42L, "testuser");
            assertNotNull(token);
            assertTrue(token.length() > 50);

            Claims claims = jwtUtil.parseToken(token);
            assertEquals("testuser", claims.getSubject());
            assertEquals(42L, claims.get("userId", Long.class));
            assertEquals("access", claims.get("type"));
            assertNotNull(claims.get("jti", String.class));
        }

        @Test
        @DisplayName("generateRefreshToken 生成 refresh 类型的 token")
        void generateRefreshToken_shouldCreateRefreshTypeToken() {
            String token = jwtUtil.generateRefreshToken(42L);
            assertNotNull(token);
            assertTrue(token.length() > 50);

            Claims claims = jwtUtil.parseToken(token);
            assertEquals("refresh", claims.get("type"));
            assertEquals(42L, claims.get("userId", Long.class));
        }

        @Test
        @DisplayName("access token 和 refresh token 的 type 字段不同")
        void accessToken_shouldHaveDifferentTypeThanRefreshToken() {
            String access = jwtUtil.generateToken(1L, "user");
            String refresh = jwtUtil.generateRefreshToken(1L);

            assertNotEquals(
                    jwtUtil.parseToken(access).get("type"),
                    jwtUtil.parseToken(refresh).get("type"));
        }
    }

    // ============================================================
    // validateToken
    // ============================================================

    @Nested
    @DisplayName("Token 验证")
    class TokenValidationTests {

        @Test
        @DisplayName("validateToken 对有效 token 返回 true")
        void validateToken_validToken_shouldReturnTrue() {
            String token = jwtUtil.generateToken(1L, "user");
            assertTrue(jwtUtil.validateToken(token));
        }

        @Test
        @DisplayName("validateToken 对 null 返回 false")
        void validateToken_nullToken_shouldReturnFalse() {
            assertFalse(jwtUtil.validateToken(null));
        }

        @Test
        @DisplayName("validateToken 对乱码字符串返回 false")
        void validateToken_garbageString_shouldReturnFalse() {
            assertFalse(jwtUtil.validateToken("not-a-valid-jwt-token-string"));
        }

        @Test
        @DisplayName("validateToken 对空字符串返回 false")
        void validateToken_emptyString_shouldReturnFalse() {
            assertFalse(jwtUtil.validateToken(""));
        }
    }

    // ============================================================
    // isRefreshToken
    // ============================================================

    @Nested
    @DisplayName("Token 类型判断")
    class TokenTypeTests {

        @Test
        @DisplayName("isRefreshToken 对 refresh token 返回 true")
        void isRefreshToken_refreshToken_shouldReturnTrue() {
            String token = jwtUtil.generateRefreshToken(1L);
            assertTrue(jwtUtil.isRefreshToken(token));
        }

        @Test
        @DisplayName("isRefreshToken 对 access token 返回 false")
        void isRefreshToken_accessToken_shouldReturnFalse() {
            String token = jwtUtil.generateToken(1L, "user");
            assertFalse(jwtUtil.isRefreshToken(token));
        }

        @Test
        @DisplayName("isRefreshToken 对无效 token 返回 false（不抛异常）")
        void isRefreshToken_invalidToken_shouldReturnFalse() {
            assertFalse(jwtUtil.isRefreshToken("invalid-token"));
        }
    }

    // ============================================================
    // revokeToken / isTokenRevoked
    // ============================================================

    @Nested
    @DisplayName("Token 撤销黑名单")
    class TokenRevocationTests {

        @Test
        @DisplayName("revokeToken 将有效 token 加入黑名单")
        void revokeToken_validToken_shouldAddToBlacklist() {
            String token = jwtUtil.generateToken(1L, "user");
            jwtUtil.revokeToken(token);

            verify(valueOps, times(1)).set(
                    startsWith("gagneflow:jwt:blacklist:"),
                    eq("revoked"),
                    any(Duration.class));
        }

        @Test
        @DisplayName("revokeToken 对 null 不抛异常")
        void revokeToken_nullToken_shouldNotThrow() {
            assertDoesNotThrow(() -> jwtUtil.revokeToken(null));
        }

        @Test
        @DisplayName("isTokenRevoked 对已撤销 token 返回 true")
        void isTokenRevoked_revokedToken_shouldReturnTrue() {
            String token = jwtUtil.generateToken(1L, "user");
            doNothing().when(valueOps).set(anyString(), eq("revoked"), any(Duration.class));
            jwtUtil.revokeToken(token);

            when(stringRedisTemplate.hasKey(anyString())).thenReturn(true);
            assertTrue(jwtUtil.isTokenRevoked(token));
        }

        @Test
        @DisplayName("isTokenRevoked 对未撤销 token 返回 false")
        void isTokenRevoked_activeToken_shouldReturnFalse() {
            String token = jwtUtil.generateToken(1L, "user");
            when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
            assertFalse(jwtUtil.isTokenRevoked(token));
        }

        @Test
        @DisplayName("isTokenRevoked Redis 不可用时降级放行返回 false")
        void isTokenRevoked_redisDown_shouldReturnFalse() {
            String token = jwtUtil.generateToken(1L, "user");
            when(stringRedisTemplate.hasKey(anyString()))
                    .thenThrow(new RuntimeException("Redis connection refused"));
            assertFalse(jwtUtil.isTokenRevoked(token));
        }
    }

    // ============================================================
    // parseToken 异常路径
    // ============================================================

    @Nested
    @DisplayName("Token 解析异常路径")
    class TokenParseErrorTests {

        @Test
        @DisplayName("parseToken 对 null 抛出异常")
        void parseToken_nullToken_shouldThrow() {
            assertThrows(Exception.class, () -> jwtUtil.parseToken(null));
        }

        @Test
        @DisplayName("parseToken 对不同密钥签发的 token 抛出异常")
        void parseToken_differentKeyToken_shouldThrow() {
            JwtUtil otherJwt = new JwtUtil();
            ReflectionTestUtils.setField(otherJwt, "secret",
                    "another-secret-key-32-bytes-here!");
            String token = otherJwt.generateToken(1L, "user");

            assertThrows(Exception.class, () -> jwtUtil.parseToken(token));
        }
    }
}
