package com.gagneflow.service.prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.gagneflow.entity.PromptVersion;
import com.gagneflow.repository.PromptVersionRepository;
import com.gagneflow.service.document.PromptLoader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromptRegistry {
    private static final Logger logger = LoggerFactory.getLogger(PromptRegistry.class);
    private static final Pattern VERSION_DIR_PATTERN = Pattern.compile("^v(\\d+)$");

    private final PromptVersionRepository repo;
    private final PromptLoader fileLoader;

    @Value("${gagneflow.prompts.path:agent-config/prompts}")
    private String promptsBasePath;

    public PromptRegistry(PromptVersionRepository repo, PromptLoader fileLoader) {
        this.repo = repo;
        this.fileLoader = fileLoader;
    }

    @PostConstruct
    void seedFromFiles() {
        Path baseDir = Paths.get(promptsBasePath);
        if (!Files.isDirectory(baseDir)) {
            logger.info("Prompt 目录不存在，跳过 seed: {}", baseDir.toAbsolutePath());
            return;
        }

        try (Stream<Path> entries = Files.list(baseDir)) {
            entries.filter(Files::isDirectory).forEach(versionDir -> {
                String dirName = versionDir.getFileName().toString();
                Matcher m = VERSION_DIR_PATTERN.matcher(dirName);
                if (!m.matches()) {
                    logger.debug("跳过非版本目录: {}", dirName);
                    return;
                }

                int versionNumber = Integer.parseInt(m.group(1));
                try (Stream<Path> files = Files.walk(versionDir)) {
                    files.filter(Files::isRegularFile)
                         .filter(f -> f.toString().endsWith(".md"))
                         .forEach(file -> this.seedFile(file, versionNumber));
                } catch (IOException e) {
                    logger.warn("扫描版本目录失败: {} -> {}", versionDir, e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.warn("Prompt 目录扫描失败: {}", e.getMessage());
        }

        logger.info("PromptRegistry 初始化完成, 已注册 {} 个 prompt 名称",
                repo.findAll().stream().map(PromptVersion::getPromptName).distinct().count());
    }

    private void seedFile(Path file, int versionNumber) {
        String fileName = file.getFileName().toString();
        String promptName = fileName.replace(".md", "");
        Path relative = Paths.get(promptsBasePath).relativize(file);

        try {
            // 传入去 .md 的相对路径，与 PromptLoader.preloadPrompt 的 cache key 一致
            String loaderKey = relative.toString().replace('\\', '/').replace(".md", "");
            String content = fileLoader.load(loaderKey);
            if (content == null || content.isBlank()) {
                logger.warn("Prompt 文件内容为空: {}", relative);
                return;
            }

            // 已存在时比对内容: 文件更新则同步 DB(否则跳过, 保证幂等且文件修改能生效)
            if (repo.existsByPromptNameAndVersionNumber(promptName, versionNumber)) {
                repo.findByPromptNameAndVersionNumber(promptName, versionNumber).ifPresent(existing -> {
                    if (!content.equals(existing.getContent())) {
                        existing.setContent(content);
                        existing.setDescription("从文件系统同步: " + relative);
                        repo.save(existing);
                        logger.info("Prompt 文件已更新, 同步 DB: {} v{}", promptName, versionNumber);
                    } else {
                        logger.trace("Prompt 已存在且未变化, 跳过: {} v{}", promptName, versionNumber);
                    }
                });
                return;
            }

            PromptVersion pv = new PromptVersion(promptName, versionNumber,
                    content, "从文件系统导入: " + relative);
            pv.setActive(false);
            repo.save(pv);
            logger.info("Seed prompt: {} v{} ({} chars)", promptName, versionNumber, content.length());
        } catch (Exception e) {
            logger.warn("Seed prompt 失败: {} -> {}", relative, e.getMessage());
        }

        // 确保最大版本号为 active
        repo.findByPromptNameOrderByVersionNumberDesc(promptName).stream()
                .findFirst()
                .ifPresent(latest -> {
                    if (!latest.isActive()) {
                        latest.setActive(true);
                        repo.save(latest);
                        logger.info("设置活跃版本: {} v{}", promptName, latest.getVersionNumber());
                    }
                });
    }

    // === 查询 API ===

    public String getContent(String promptName) {
        return repo.findByPromptNameAndActiveTrue(promptName)
                .map(PromptVersion::getContent)
                .orElseGet(() -> loadFallback(promptName));
    }

    public String getContent(String promptName, int versionNumber) {
        return repo.findByPromptNameAndVersionNumber(promptName, versionNumber)
                .map(PromptVersion::getContent)
                .orElseGet(() -> loadFallback(promptName));
    }

    public int getActiveVersionNumber(String promptName) {
        return repo.findByPromptNameAndActiveTrue(promptName)
                .map(PromptVersion::getVersionNumber)
                .orElse(1);
    }

    public List<PromptVersion> listVersions(String promptName) {
        return repo.findByPromptNameOrderByVersionNumberDesc(promptName);
    }

    /** 列出所有已注册的 prompt 名称(从 DB 去重查询, 不硬编码) */
    public List<String> listPromptNames() {
        List<String> names = repo.findDistinctPromptNames();
        if (names == null || names.isEmpty()) {
            logger.warn("prompt_versions 表为空, 请检查启动 seed 是否执行");
        }
        return names != null ? names : List.of();
    }

    // === 管理 API ===
    @Transactional
    public PromptVersion activate(String promptName, int versionNumber) {
        // 取消所有活跃
        repo.findByPromptNameAndActiveTrue(promptName).ifPresent(current -> {
            current.setActive(false);
            repo.save(current);
        });
        // 设置新活跃
        PromptVersion target = repo.findByPromptNameAndVersionNumber(promptName, versionNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Prompt " + promptName + " v" + versionNumber + " 不存在"));
        target.setActive(true);
        repo.save(target);
        logger.info("切换活跃 Prompt: {} → v{}", promptName, versionNumber);
        return target;
    }

    private String loadFallback(String promptName) {
        // v1 迁移后文件位于 v1/addrf/ 或 v1/ 下，PromptLoader 缓存 key 带版本前缀；
        // 依次尝试候选路径，命中任一即返回
        String[] candidates = {
            "v1/addrf/" + promptName,
            "addrf/" + promptName,
            "v1/" + promptName,
            promptName
        };
        for (String candidate : candidates) {
            try {
                String content = fileLoader.load(candidate);
                if (content != null && !content.isBlank()) {
                    return content;
                }
            } catch (Exception ignored) {
                // 继续尝试下一个候选路径
            }
        }
        logger.warn("Fallback 加载 prompt 失败: {}", promptName);
        return "你是一个教育AI助手，请根据输入生成教案内容。";
    }
}
