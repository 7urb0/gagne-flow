package com.gagneflow.repository;

import java.util.List;
import java.util.Optional;

import com.gagneflow.entity.PromptVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PromptVersionRepository extends JpaRepository<PromptVersion, Long> {

    Optional<PromptVersion> findByPromptNameAndActiveTrue(String promptName);

    Optional<PromptVersion> findByPromptNameAndVersionNumber(String promptName, int versionNumber);

    List<PromptVersion> findByPromptNameOrderByVersionNumberDesc(String promptName);

    boolean existsByPromptNameAndVersionNumber(String promptName, int versionNumber);

    int countByPromptName(String promptName);

    /** 查询所有已注册的 prompt 名称(去重, 升序) */
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT p.promptName FROM PromptVersion p ORDER BY p.promptName")
    List<String> findDistinctPromptNames();
}
