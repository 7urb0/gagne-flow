package com.gagneflow.config;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {
    private static final Logger logger = LoggerFactory.getLogger(ExecutorConfig.class);
    @Value(value="${gagneflow.pool.core-size:10}")
    private int coreSize;
    @Value(value="${gagneflow.pool.max-size:50}")
    private int maxSize;
    @Value(value="${gagneflow.pool.queue-capacity:200}")
    private int queueCapacity;
    private ThreadPoolExecutor executor;

    @Bean
    public ThreadPoolExecutor sharedExecutor() {
        this.executor = new ThreadPoolExecutor(this.coreSize, this.maxSize, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(this.queueCapacity), new ThreadPoolExecutor.CallerRunsPolicy());
        logger.info("\u5171\u4eab\u7ebf\u7a0b\u6c60\u521d\u59cb\u5316: core={}, max={}, queue={}", new Object[]{this.coreSize, this.maxSize, this.queueCapacity});
        return this.executor;
    }

    @PreDestroy
    public void shutdown() {
        if (this.executor != null) {
            logger.info("\u6b63\u5728\u5173\u95ed\u5171\u4eab\u7ebf\u7a0b\u6c60... (active={}, queue={})", (Object)this.executor.getActiveCount(), (Object)this.executor.getQueue().size());
            this.executor.shutdown();
            try {
                if (!this.executor.awaitTermination(120L, TimeUnit.SECONDS)) {
                    this.executor.shutdownNow();
                }
            }
            catch (InterruptedException e) {
                this.executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
