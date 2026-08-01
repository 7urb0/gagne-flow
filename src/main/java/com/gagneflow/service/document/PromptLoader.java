package com.gagneflow.service.document;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        logger.info("PromptLoader \u542f\u52a8\uff0c\u8def\u5f84: {}", (Object)this.promptsPath);
        this.preloadPrompt("v1/decision_guide");
        this.preloadPrompt("v1/planner");
        this.preloadPrompt("v1/executor");
        this.preloadPrompt("v1/retrieval");
        this.preloadPrompt("v1/review");
        this.preloadPrompt("v1/supervisor");
        this.preloadPrompt("v1/addrf/addrf_analysis");
        this.preloadPrompt("v1/addrf/addrf_design");
        this.preloadPrompt("v1/addrf/addrf_development");
        this.preloadPrompt("v1/addrf/addrf_review");
        logger.info("PromptLoader \u521d\u59cb\u5316\u5b8c\u6210\uff0c\u5df2\u52a0\u8f7d {} \u4e2a\u63d0\u793a\u8bcd", (Object)this.cache.size());
    }

    private void preloadPrompt(String name) {
        try {
            String content = this.loadFromFile(name);
            this.cache.put(name, content);
            logger.info("\u52a0\u8f7d\u63d0\u793a\u8bcd: {} ({} \u5b57\u7b26)", (Object)name, (Object)content.length());
        }
        catch (IOException e) {
            throw new IllegalStateException(String.format("\u63d0\u793a\u8bcd\u6587\u4ef6\u7f3a\u5931: %s/%s.md\u3002\u8bf7\u68c0\u67e5 agent-config/prompts/ \u76ee\u5f55\u4e0b\u6587\u4ef6\u662f\u5426\u5b8c\u6574\u3002", this.promptsPath, name), e);
        }
    }

    public String load(String name) {
        String content = this.cache.get(name);
        if (content == null) {
            throw new IllegalStateException("\u63d0\u793a\u8bcd '" + name + "' \u672a\u5728\u542f\u52a8\u65f6\u52a0\u8f7d\uff0c\u8bf7\u68c0\u67e5 preloadPrompt \u5217\u8868\u662f\u5426\u5305\u542b\u6b64\u540d\u79f0\u3002");
        }
        return content;
    }

    private String loadFromFile(String name) throws IOException {
        Path path = Paths.get(this.promptsPath, name + ".md");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
