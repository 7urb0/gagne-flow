package com.gagneflow.controller;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.collection.ShowCollectionsParam;
import com.gagneflow.service.vector.VectorEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class GagneFlowHealthIndicator
implements HealthIndicator {
    private static final Logger logger = LoggerFactory.getLogger(GagneFlowHealthIndicator.class);
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired(required=false)
    private MilvusServiceClient milvusClient;
    @Autowired
    private VectorEmbeddingService embeddingService;

    public Health health() {
        Health.Builder builder = new Health.Builder();
        boolean healthy = true;
        try {
            this.redisTemplate.opsForValue().get((Object)"health:ping");
            builder.withDetail("redis", (Object)"UP");
        }
        catch (Exception e) {
            builder.withDetail("redis", (Object)"DOWN");
            healthy = false;
        }
        if (this.milvusClient != null) {
            try {
                this.milvusClient.showCollections(ShowCollectionsParam.newBuilder().build());
                builder.withDetail("milvus", (Object)"UP");
            }
            catch (Exception e) {
                builder.withDetail("milvus", (Object)("DOWN - " + e.getMessage()));
                healthy = false;
            }
        } else {
            builder.withDetail("milvus", (Object)"DISABLED");
        }
        try {
            this.embeddingService.generateEmbedding("health-check");
            builder.withDetail("dashscope", (Object)"UP");
        }
        catch (Exception e) {
            builder.withDetail("dashscope", (Object)("DOWN - " + e.getMessage()));
            healthy = false;
        }
        if (healthy) {
            builder.up();
        } else {
            builder.down();
        }
        return builder.build();
    }
}
