package com.gagneflow.service.document;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PromptLoader {
    private static final Logger logger = LoggerFactory.getLogger(PromptLoader.class);
    @Value(value="${gagneflow.prompts.path:agent-config/prompts}")
    private String promptsPath;
    private final Map<String, String> cache = new ConcurrentHashMap<String, String>();

    @PostConstruct
    public void init() {
        logger.info("PromptLoader 启动, 路径: {}", (Object)this.promptsPath);
        // 动态扫描目录下所有 .md, 避免硬编码列表漏新增模板
        // 兼容 v1/*.md 与 v1/addrf/*.md 两种结构, cache key 为去掉 .md 的相对路径
        List<String> promptNames = this.scanPromptNames();
        if (promptNames.isEmpty()) {
            logger.warn("Prompt 目录未扫描到任何 .md 文件: {}", this.promptsPath);
            return;
        }
        for (String name : promptNames) {
            this.preloadPrompt(name);
        }
        logger.info("PromptLoader 初始化完成, 已加载 {} 个提示词", (Object)this.cache.size());
    }

    /** 扫描 prompts 目录下所有 .md 文件, 返回相对路径 key(如 v1/planner、v1/addrf/addrf_analysis) */
    private List<String> scanPromptNames() {
        Path baseDir = Paths.get(this.promptsPath);
        if (!Files.isDirectory(baseDir)) {
            logger.warn("Prompt 目录不存在: {}", baseDir.toAbsolutePath());
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(baseDir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .map(p -> baseDir.relativize(p).toString().replace('\\', '/').replace(".md", ""))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.warn("Prompt 目录扫描失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void preloadPrompt(String name) {
        try {
            String content = this.loadFromFile(name);
            this.cache.put(name, content);
            logger.info("加载提示词: {} ({} 字符)", (Object)name, (Object)content.length());
        }
        catch (IOException e) {
            throw new IllegalStateException(String.format("提示词文件缺失: %s/%s.md。请检查 agent-config/prompts/ 目录下文件是否完整。", this.promptsPath, name), e);
        }
    }

    public String load(String name) {
        String content = this.cache.get(name);
        if (content == null) {
            throw new IllegalStateException("提示词 '" + name + "' 未在启动时加载, 请检查提示词文件是否存在于 " + this.promptsPath + " 目录。");
        }
        return content;
    }

    private String loadFromFile(String name) throws IOException {
        Path path = Paths.get(this.promptsPath, name + ".md");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
