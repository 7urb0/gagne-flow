package com.gagneflow.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("JwtAuthFilter 过滤器测试")
class JwtAuthFilterTest {

    private JwtAuthFilter filter;
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret",
                "gagneflow-test-secret-key-32-bytes!");
        // 不注入 stringRedisTemplate — isTokenRevoked 会走到 catch 分支返回 false

        filter = new JwtAuthFilter();
        ReflectionTestUtils.setField(filter, "jwtUtil", jwtUtil);
    }

    @Nested
    @DisplayName("正常认证流程")
    class NormalAuthFlowTests {

        @Test
        @DisplayName("有效 Bearer token → 设置 SecurityContext")
        void validBearerToken_shouldSetAuthentication() throws Exception {
            String token = jwtUtil.generateToken(42L, "testuser");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer " + token);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertNotNull(SecurityContextHolder.getContext().getAuthentication());
            assertEquals(42L, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            verify(chain, times(1)).doFilter(request, response);
        }

        @Test
        @DisplayName("有效 token 后 SecurityContext 包含 userId 作为 principal")
        void validToken_principalShouldBeUserId() throws Exception {
            String token = jwtUtil.generateToken(99L, "admin");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer " + token);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertEquals(99L,
                    SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        }
    }

    @Nested
    @DisplayName("拒绝场景")
    class RejectionFlowTests {

        @Test
        @DisplayName("无 Authorization header → 不设认证 → 继续链")
        void noAuthHeader_shouldNotSetAuthentication() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(chain, times(1)).doFilter(request, response);
        }

        @Test
        @DisplayName("无效 token 格式 → 不设认证")
        void invalidTokenFormat_shouldNotSetAuthentication() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer garbage-token");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("Refresh token 不能��于 API 访问")
        void refreshToken_shouldNotSetAuthentication() throws Exception {
            String refreshToken = jwtUtil.generateRefreshToken(1L);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer " + refreshToken);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("Authorization header 不以 Bearer 开头 → 不设认证")
        void nonBearerHeader_shouldNotSetAuthentication() throws Exception {
            String token = jwtUtil.generateToken(1L, "user");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Basic " + token);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("Authorization header 为 null → 不设认证")
        void nullAuthHeader_shouldNotSetAuthentication() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    @Nested
    @DisplayName("黑名单检查")
    class BlacklistCheckTests {

        @Test
        @DisplayName("已撤销 token → 不设认证")
        void revokedToken_shouldNotSetAuthentication() throws Exception {
            // 构造一个 jwtUtil mock 返回 isTokenRevoked=true
            JwtUtil mockJwt = mock(JwtUtil.class);
            String token = "valid.but.revoked.token";
            when(mockJwt.validateToken(token)).thenReturn(true);
            when(mockJwt.isRefreshToken(token)).thenReturn(false);
            when(mockJwt.isTokenRevoked(token)).thenReturn(true);
            when(mockJwt.parseToken(token)).thenReturn(
                    jwtUtil.parseToken(jwtUtil.generateToken(1L, "user")));

            JwtAuthFilter filter2 = new JwtAuthFilter();
            ReflectionTestUtils.setField(filter2, "jwtUtil", mockJwt);

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer " + token);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter2.doFilterInternal(request, response, chain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }
}
