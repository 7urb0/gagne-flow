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
}
