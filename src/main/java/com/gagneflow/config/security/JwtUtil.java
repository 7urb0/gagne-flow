package com.gagneflow.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * JWT 工具类 (P0修复: 增加 Token 主动撤销机制 + 移除不安全的默认 secret)。
 * Redis 黑名单 key 格式: gagneflow:jwt:blacklist:{token_short_hash}
 * TTL 与 access token 有效期对齐 (1h)，到期自动清理。
 */
@Component
public class JwtUtil {
    private static final Logger logger = LoggerFactory.getLogger(JwtUtil.class);
    private static final long ACCESS_EXPIRATION = 3600000L;   // 1 小时
    private static final long REFRESH_EXPIRATION = 604800000L; // 7 天
    private static final int MIN_KEY_BYTES = 32;
    private static final String BLACKLIST_PREFIX = "gagneflow:jwt:blacklist:";

    @Value("${gagneflow.auth.jwt-secret}")
    private String secret;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void init() {
        if (this.secret == null || this.secret.isBlank()) {
            logger.error("JWT_SECRET 环境变量未配置！");
            throw new IllegalStateException("JWT_SECRET 环境变量未配置！请设置至少 32 字节（256 bits）的 JWT 密钥。\n"
                + "生成命令: openssl rand -base64 32\n"
                + "配置方式: export JWT_SECRET=<生成的密钥>");
        }
        int keyLength = this.secret.getBytes(StandardCharsets.UTF_8).length;
        if (keyLength < MIN_KEY_BYTES) {
            logger.error("JWT_SECRET 密钥长度不足！当前 {} 字节，要求至少 {} 字节", keyLength, MIN_KEY_BYTES);
            throw new IllegalStateException(String.format(
                "JWT_SECRET 密钥长度不足！当前 %d 字节，要求至少 %d 字节 (256 bits)。\n"
                + "请使用更长的密钥。生成命令: openssl rand -base64 32", keyLength, MIN_KEY_BYTES));
        }
        logger.info("JWT 密钥校验通过，长度: {} 字节", keyLength);
    }

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(this.secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Long userId, String username) {
        return Jwts.builder()
            .subject(username)
            .claim("userId", userId)
            .claim("type", "access")
            .claim("jti", java.util.UUID.randomUUID().toString())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + ACCESS_EXPIRATION))
            .signWith(this.getKey())
            .compact();
    }

    public String generateRefreshToken(Long userId) {
        return Jwts.builder()
            .claim("userId", userId)
            .claim("type", "refresh")
            .claim("jti", java.util.UUID.randomUUID().toString())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + REFRESH_EXPIRATION))
            .signWith(this.getKey())
            .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
            .verifyWith(this.getKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            this.parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isRefreshToken(String token) {
        try {
            Claims claims = this.parseToken(token);
            return "refresh".equals(claims.get("type"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * P0修复: 将 Token 加入 Redis 黑名单，实现主动撤销。
     * Blacklist TTL = token 剩余有效时间，到期自动清理。
     * 降级策略: Redis 不可用时仅记录日志，不影响登出流程。
     */
    public void revokeToken(String token) {
        try {
            Claims claims = this.parseToken(token);
            Date expiration = claims.getExpiration();
            if (expiration == null || expiration.before(new Date())) {
                return; // token 已过期，无需撤销
            }
            long ttlMs = expiration.getTime() - System.currentTimeMillis();
            if (ttlMs <= 0) {
                return;
            }
            String jti = claims.get("jti", String.class);
            String blackKey = BLACKLIST_PREFIX + (jti != null ? jti : this.shortHash(token));
            this.stringRedisTemplate.opsForValue().set(
                blackKey, "revoked", Duration.ofMillis(ttlMs));
            logger.info("JWT token 已撤销: jti={}, ttl={}s", jti, ttlMs / 1000);
        } catch (Exception e) {
            logger.error("JWT token 撤销失败 (Redis 可能不可用): {}", e.getMessage());
        }
    }

    /**
     * P0修复: 检查 Token 是否已被撤销。
     * JwtAuthFilter 在 validateToken 通过后调用此方法。
     */
    public boolean isTokenRevoked(String token) {
        try {
            Claims claims = this.parseToken(token);
            String jti = claims.get("jti", String.class);
            if (jti == null) {
                return false; // 旧 token 无 jti，不检查
            }
            return Boolean.TRUE.equals(
                this.stringRedisTemplate.hasKey(BLACKLIST_PREFIX + jti));
        } catch (Exception e) {
            // Redis 不可用时降级放行
            logger.warn("JWT 黑名单检查失败 (降级放行): {}", e.getMessage());
            return false;
        }
    }

    private String shortHash(String token) {
        if (token == null || token.length() < 10) {
            return "unknown";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {  // 前 8 字节 = 16 hex 字符
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(token.hashCode());
        }
    }
}
