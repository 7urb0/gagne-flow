package com.gagneflow.constant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 用户相关常量。
 */
public final class UserConstants {

    private UserConstants() {
        throw new UnsupportedOperationException("常量类不可实例化");
    }

    /**
     * 未认证用户的默认标识。
     * 匿名用户应通过 sessionId（ChatController）或 IP + User-Agent 指纹区分，
     * 而非共用此固定 ID。
     */
    public static final Long DEFAULT_USER_ID = 0L;

    /**
     * 解析用户 ID：已登录用户返回真实 ID，匿名用户根据 sessionId 生成稳定的伪 ID。
     * 同一 sessionId 始终映射到同一伪 ID，避免不同匿名会话数据混淆。
     * <p>
     * 2026-08-23 P3-6: 原实现用 {@code sessionId.hashCode()}（32 位）取绝对值，
     * 存在两类风险：① 不同 sessionId 哈希碰撞会归并到同一伪 ID 造成数据串扰；
     * ② Integer.MIN_VALUE 取绝对值溢出为负数。改为 SHA-256 截取 64 位，碰撞面显著降低且恒为正。
     */
    public static Long resolveUserId(Long userId, String sessionId) {
        if (userId != null && userId > 0L) return userId;
        if (sessionId != null && !sessionId.isEmpty()) {
            return (long) (sha256ToPositive64(sessionId) & Long.MAX_VALUE) + 1_000_000L;
        }
        return DEFAULT_USER_ID;
    }

    private static long sha256ToPositive64(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xFF);
            }
            return value;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 由 JDK 保证存在，理论不可达；兜底回退到更强散列（仍优于 hashCode）
            return (long) input.hashCode() * 0x9E3779B97F4A7C15L;
        }
    }
}
