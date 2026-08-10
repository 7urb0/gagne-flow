package com.gagneflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 长期记忆事实的 MySQL 持久化副本。
 * Redis 中的 ltm:detail:* 是运行态主存储（TTL 30 天），本表是 Redis 丢失后的重建来源，
 * 仿照 ChatSessionService.rebuildFromMySql() 的降级思路，避免服务器迁移/扩容导致记忆全丢。
 */
@Entity
@Table(name = "ltm_fact", indexes = {
        @Index(name = "idx_ltm_session", columnList = "userId,sessionId,createTime")})
public class LtmFact {

    @Id
    @Column(length = 64)
    private String factId;          // 与 Redis factId 一致: UUID.nameUUIDFromBytes(sessionId_fact)

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String factText;

    @Column(length = 32)
    private String factType;        // 类别: 教学需求/学生情况/偏好/学科年级/约束/否定/数值

    @Column(length = 32)
    private String sourcePhase;     // 来源阶段: SUMMARY_EXTRACTED / USER_EXPLICIT / FINAL_DECISION

    @Column(nullable = false)
    private Instant createTime = Instant.now();

    /** 最近一次被检索命中的时间(epoch ms), 用于时间衰减加权; 0 表示未记录 */
    @Column(nullable = false)
    private Long lastAccessTime = 0L;

    /** 被检索命中次数, 用于访问频率增益 */
    @Column(nullable = false)
    private Integer accessCount = 0;

    public LtmFact() {
    }

    public LtmFact(String factId, Long userId, String sessionId, String factText,
                   String factType, String sourcePhase) {
        this.factId = factId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.factText = factText;
        this.factType = factType;
        this.sourcePhase = sourcePhase;
    }

    public String getFactId() { return factId; }
    public void setFactId(String factId) { this.factId = factId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getFactText() { return factText; }
    public void setFactText(String factText) { this.factText = factText; }

    public String getFactType() { return factType; }
    public void setFactType(String factType) { this.factType = factType; }

    public String getSourcePhase() { return sourcePhase; }
    public void setSourcePhase(String sourcePhase) { this.sourcePhase = sourcePhase; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public Long getLastAccessTime() { return lastAccessTime; }
    public void setLastAccessTime(Long lastAccessTime) { this.lastAccessTime = lastAccessTime; }

    public Integer getAccessCount() { return accessCount; }
    public void setAccessCount(Integer accessCount) { this.accessCount = accessCount; }
}
