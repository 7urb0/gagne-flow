package com.gagneflow.config.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthFilter.class);

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            // 1. 签名 + 过期校验
            if (!this.jwtUtil.validateToken(token)) {
                chain.doFilter(request, response);
                return;
            }
            // 2. 拒绝 refresh token 用于 API 访问
            if (this.jwtUtil.isRefreshToken(token)) {
                chain.doFilter(request, response);
                return;
            }
            // 3. P0修复: 检查 Token 是否已被撤销 (Redis 黑名单)
            if (this.jwtUtil.isTokenRevoked(token)) {
                logger.warn("JWT token 已被撤销: {}", this.maskToken(token));
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"Token 已被注销，请重新登录\",\"code\":401}");
                return;
            }
            // 4. 设置 SecurityContext
            Claims claims = this.jwtUtil.parseToken(token);
            Long userId = claims.get("userId", Long.class);
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }

    /** 日志安全: 只展示 token 前后 4 位 */
    private String maskToken(String token) {
        if (token == null || token.length() <= 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
