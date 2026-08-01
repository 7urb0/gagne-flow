package com.gagneflow.controller;

import io.jsonwebtoken.Claims;
import java.util.Map;
import com.gagneflow.config.security.JwtUtil;
import com.gagneflow.entity.User;
import com.gagneflow.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private UserService userService;

    @PostMapping("/api/auth/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        if (username.isEmpty() || password.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名和密码不能为空"));
        }
        if (password.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "密码至少需要6个字符"));
        }
        try {
            this.userService.register(username, password);
            return ResponseEntity.ok(Map.of("message", "注册成功", "username", username));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        if (username.isEmpty() || password.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名和密码不能为空"));
        }
        User user = this.userService.findByUsername(username);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        if (!this.userService.passwordMatches(password, user)) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        String token = this.jwtUtil.generateToken(user.getId(), user.getUsername());
        String refreshToken = this.jwtUtil.generateRefreshToken(user.getId());
        return ResponseEntity.ok(Map.of("token", token, "refreshToken", refreshToken,
            "username", user.getUsername()));
    }

    @PostMapping("/api/auth/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.getOrDefault("refreshToken", "");
        if (!this.jwtUtil.validateToken(refreshToken)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or expired refresh token"));
        }
        Claims claims = this.jwtUtil.parseToken(refreshToken);
        if (!"refresh".equals(claims.get("type"))) {
            return ResponseEntity.status(401).body(Map.of("error", "Not a refresh token"));
        }
        String username = claims.getSubject();
        Long userId = claims.get("userId", Long.class);
        String newToken = this.jwtUtil.generateToken(userId, username);
        return ResponseEntity.ok(Map.of("token", newToken));
    }

    /**
     * P0修复: Token 主动注销端点。
     * 将当前 access token 加入 Redis 黑名单 (TTL 对齐 token 剩余有效期)。
     * 客户端收到登出成功后应清除本地 token 存储。
     */
    @PostMapping("/api/auth/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false)
                                     String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 Authorization header"));
        }
        String token = authHeader.substring(7);
        if (!this.jwtUtil.validateToken(token)) {
            return ResponseEntity.status(401).body(Map.of("error", "Token 无效或已过期"));
        }
        try {
            this.jwtUtil.revokeToken(token);
            return ResponseEntity.ok(Map.of("message", "已成功注销"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "注销失败: " + e.getMessage()));
        }
    }
}
