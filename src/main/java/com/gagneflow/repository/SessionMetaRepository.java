package com.gagneflow.repository;

import java.util.List;
import com.gagneflow.entity.SessionMeta;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionMetaRepository
extends JpaRepository<SessionMeta, Long> {
    public List<SessionMeta> findByUserIdOrderByUpdateTimeDesc(Long var1);

    public SessionMeta findByUserIdAndSessionId(Long var1, String var2);
}
