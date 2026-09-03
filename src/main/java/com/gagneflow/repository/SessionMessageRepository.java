package com.gagneflow.repository;

import java.time.Instant;
import java.util.List;
import com.gagneflow.entity.SessionMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface SessionMessageRepository
extends JpaRepository<SessionMessage, Long> {
    @Query(value="SELECT m FROM SessionMessage m WHERE m.userId = ?1 AND m.sessionId = ?2 ORDER BY m.createTime ASC")
    public List<SessionMessage> findByUserIdAndSessionId(Long var1, String var2);

    @Query(value="SELECT * FROM session_message WHERE user_id = ?1 AND session_id = ?2 ORDER BY create_time DESC LIMIT ?3", nativeQuery=true)
    public List<SessionMessage> findTopNByUserIdAndSessionId(Long var1, String var2, int var3);

    @Transactional
    public void deleteByUserIdAndSessionId(Long var1, String var2);

    @Transactional
    @Query(value="DELETE FROM SessionMessage m WHERE m.userId = ?1 AND m.sessionId = ?2 AND m.createTime < ?3")
    public void deleteByUserIdAndSessionIdAndCreateTimeBefore(Long var1, String var2, Instant var3);

    @Query(value="SELECT m FROM SessionMessage m WHERE m.userId = ?1 AND m.role = ?2 AND m.content LIKE CONCAT(?3, '%') ORDER BY m.createTime DESC")
    public List<SessionMessage> findLatestByUserIdAndRoleAndContentPrefix(Long var1, String var2, String var3, Pageable var4);
}
