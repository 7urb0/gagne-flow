package com.gagneflow.repository;

import com.gagneflow.entity.LtmFact;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 长期记忆事实的 MySQL 仓库，用于 Redis 丢失后的降级重建与删除联动。
 */
public interface LtmFactRepository extends JpaRepository<LtmFact, String> {

    List<LtmFact> findByUserIdAndSessionId(Long userId, String sessionId);

    void deleteByUserIdAndSessionId(Long userId, String sessionId);
}
