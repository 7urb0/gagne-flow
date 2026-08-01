package com.gagneflow.config;

import io.milvus.client.MilvusServiceClient;
import jakarta.annotation.PreDestroy;
import com.gagneflow.client.MilvusClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {
    private static final Logger logger = LoggerFactory.getLogger(MilvusConfig.class);
    @Autowired
    private MilvusClientFactory milvusClientFactory;
    private MilvusServiceClient milvusClient;

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        logger.info("\u6b63\u5728\u521d\u59cb\u5316 Milvus \u5ba2\u6237\u7aef...");
        this.milvusClient = this.milvusClientFactory.createClient();
        logger.info("Milvus \u5ba2\u6237\u7aef\u521d\u59cb\u5316\u5b8c\u6210");
        return this.milvusClient;
    }

    @PreDestroy
    public void cleanup() {
        if (this.milvusClient != null) {
            logger.info("\u6b63\u5728\u5173\u95ed Milvus \u5ba2\u6237\u7aef\u8fde\u63a5...");
            this.milvusClient.close();
            logger.info("Milvus \u5ba2\u6237\u7aef\u8fde\u63a5\u5df2\u5173\u95ed");
        }
    }
}
