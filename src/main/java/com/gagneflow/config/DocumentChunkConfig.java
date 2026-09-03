package com.gagneflow.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix="document.chunk")
public class DocumentChunkConfig {
    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkConfig.class);

    private int maxSize = 800;
    private int overlap = 100;

    @PostConstruct
    public void validate() {
        if (maxSize <= 0) {
            logger.warn("document.chunk.max-size={} 无效，重置为默认值 800", maxSize);
            this.maxSize = 800;
        }
        if (overlap < 0) {
            logger.warn("document.chunk.overlap={} 无效（负数），重置为 0", overlap);
            this.overlap = 0;
        }
        if (overlap >= maxSize) {
            logger.warn("document.chunk.overlap({}) >= max-size({}) 会导致分片死循环，调整 overlap={}",
                overlap, maxSize, maxSize / 5);
            this.overlap = maxSize / 5;
        }
        logger.info("文档分片配置: maxSize={}, overlap={}", this.maxSize, this.overlap);
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public void setOverlap(int overlap) {
        this.overlap = overlap;
    }

    public int getMaxSize() {
        return this.maxSize;
    }

    public int getOverlap() {
        return this.overlap;
    }
}
