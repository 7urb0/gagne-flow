package com.gagneflow.constant;

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
     */
    public static Long resolveUserId(Long userId, String sessionId) {
        if (userId != null && userId > 0L) return userId;
        if (sessionId != null && !sessionId.isEmpty()) {
            return (long) Math.abs(sessionId.hashCode()) + 1_000_000L;
        }
        return DEFAULT_USER_ID;
    }
}
