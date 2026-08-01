package com.gagneflow.service.vector;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.NoApiKeyException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VectorEmbeddingService {
    private static final Logger logger = LoggerFactory.getLogger(VectorEmbeddingService.class);
    @Value(value="${spring.ai.dashscope.api-key}")
    private String apiKey;
    @Value(value="${dashscope.embedding.model}")
    private String model;
    private TextEmbedding textEmbedding;

    @PostConstruct
    public void init() {
        if (this.apiKey == null || this.apiKey.trim().isEmpty() || this.apiKey.equals("your-api-key-here")) {
            String maskedKey = this.apiKey != null && this.apiKey.length() > 8
                ? this.apiKey.substring(0, 8) + "..." + this.apiKey.substring(this.apiKey.length() - 4)
                : "***";
            logger.error("API Key 未正确配置！当前值: {}", (Object)maskedKey);
            throw new IllegalStateException("\u8bf7\u8bbe\u7f6e\u73af\u5883\u53d8\u91cf DASHSCOPE_API_KEY \u6216\u5728 application.yml \u4e2d\u914d\u7f6e\u6b63\u786e\u7684 API Key");
        }
        String maskedKey = this.apiKey.length() > 8 ? this.apiKey.substring(0, 8) + "..." + this.apiKey.substring(this.apiKey.length() - 4) : "***";
        logger.info("API Key \u5df2\u52a0\u8f7d: {}", (Object)maskedKey);
        this.textEmbedding = new TextEmbedding();
        logger.info("\u963f\u91cc\u4e91 DashScope Embedding \u670d\u52a1\u521d\u59cb\u5316\u5b8c\u6210\uff0c\u6a21\u578b: {}", (Object)this.model);
    }

    @CircuitBreaker(name="dashscope", fallbackMethod="generateEmbeddingFallback")
    public List<Float> generateEmbedding(String content) {
        try {
            if (content == null || content.trim().isEmpty()) {
                logger.warn("\u5185\u5bb9\u4e3a\u7a7a\uff0c\u65e0\u6cd5\u751f\u6210\u5411\u91cf");
                throw new IllegalArgumentException("\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a");
            }
            logger.debug("\u5f00\u59cb\u751f\u6210\u5411\u91cf\u5d4c\u5165, \u5185\u5bb9\u957f\u5ea6: {} \u5b57\u7b26", (Object)content.length());
            TextEmbeddingParam param = ((TextEmbeddingParam.TextEmbeddingParamBuilder)TextEmbeddingParam.builder().model(this.model)).texts(Collections.singletonList(content)).build();
            TextEmbeddingResult result = this.textEmbedding.call(param);
            List<Float> floatEmbedding = VectorEmbeddingService.getFloats(result);
            // P0修复: L2归一化，使 L2 距离等价于余弦相似度
            floatEmbedding = normalizeL2(floatEmbedding);
            logger.info("\u6210\u529f\u751f\u6210\u5411\u91cf\u5d4c\u5165, \u5185\u5bb9\u957f\u5ea6: {} \u5b57\u7b26, \u5411\u91cf\u7ef4\u5ea6: {}", (Object)content.length(), (Object)floatEmbedding.size());
            return floatEmbedding;
        }
        catch (NoApiKeyException e) {
            logger.error("API Key \u672a\u8bbe\u7f6e\u6216\u65e0\u6548", (Throwable)e);
            throw new RuntimeException("API Key \u672a\u8bbe\u7f6e\uff0c\u8bf7\u914d\u7f6e dashscope.api.key", e);
        }
        catch (Exception e) {
            logger.error("\u751f\u6210\u5411\u91cf\u5d4c\u5165\u5931\u8d25, \u5185\u5bb9\u957f\u5ea6: {}", (Object)(content != null ? content.length() : 0), (Object)e);
            throw new RuntimeException("\u751f\u6210\u5411\u91cf\u5d4c\u5165\u5931\u8d25: " + e.getMessage(), e);
        }
    }

    /**
     * P1修复: DashScope Embedding 熔断器 fallback
     * 返回归一化的零向量作为降级结果（维度假定为1024）
     */
    public List<Float> generateEmbeddingFallback(String content, Throwable t) {
        logger.warn("[CIRCUIT-BREAKER] DashScope熔断器已打开，返回空向量列表。内容长度: {}, 原因: {}", content != null ? content.length() : 0, t.getMessage());
        return Collections.emptyList();
    }

    /**
     * L2归一化: 将向量缩放到单位长度，使 L2 距离等价于余弦相似度
     */
    public static List<Float> normalizeL2(List<Float> vector) {
        float norm = 0.0f;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm == 0.0f) {
            return vector;
        }
        List<Float> normalized = new ArrayList<>(vector.size());
        for (float v : vector) {
            normalized.add(v / norm);
        }
        return normalized;
    }

    @NotNull
    private static List<Float> getFloats(TextEmbeddingResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
            throw new RuntimeException("DashScope API \u8fd4\u56de\u7a7a\u7ed3\u679c");
        }
        TextEmbeddingOutput output = result.getOutput();
        List embeddings = output.getEmbeddings();
        if (embeddings.isEmpty()) {
            throw new RuntimeException("DashScope API \u8fd4\u56de\u7a7a\u5411\u91cf\u5217\u8868");
        }
        List<Double> embeddingDoubles = ((TextEmbeddingResultItem) embeddings.get(0)).getEmbedding();
        ArrayList<Float> floatEmbedding = new ArrayList<Float>(embeddingDoubles.size());
        for (Double value : embeddingDoubles) {
            floatEmbedding.add(Float.valueOf(value.floatValue()));
        }
        return floatEmbedding;
    }

    public List<List<Float>> generateEmbeddings(List<String> contents) {
        try {
            if (contents == null || contents.isEmpty()) {
                logger.warn("\u5185\u5bb9\u5217\u8868\u4e3a\u7a7a\uff0c\u65e0\u6cd5\u751f\u6210\u5411\u91cf");
                return Collections.emptyList();
            }
            logger.info("\u5f00\u59cb\u6279\u91cf\u751f\u6210\u5411\u91cf\u5d4c\u5165, \u6570\u91cf: {}", (Object)contents.size());
            TextEmbeddingParam param = ((TextEmbeddingParam.TextEmbeddingParamBuilder)TextEmbeddingParam.builder().model(this.model)).texts(contents).build();
            TextEmbeddingResult result = this.textEmbedding.call(param);
            if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
                throw new RuntimeException("\u6279\u91cf DashScope API \u8fd4\u56de\u7a7a\u7ed3\u679c");
            }
            List<TextEmbeddingResultItem> embeddingItems = result.getOutput().getEmbeddings();
            if (embeddingItems.isEmpty()) {
                throw new RuntimeException("\u6279\u91cf DashScope API \u8fd4\u56de\u7a7a\u5411\u91cf\u5217\u8868");
            }
            ArrayList<List<Float>> embeddings = new ArrayList<List<Float>>();
            for (TextEmbeddingResultItem item : embeddingItems) {
                List<Double> embeddingDoubles = item.getEmbedding();
                ArrayList<Float> embedding = new ArrayList<Float>(embeddingDoubles.size());
                for (Double value : embeddingDoubles) {
                    embedding.add(Float.valueOf(value.floatValue()));
                }
                // P0修复: L2归一化
                embeddings.add(normalizeL2(embedding));
            }
            logger.info("\u6210\u529f\u6279\u91cf\u751f\u6210\u5411\u91cf\u5d4c\u5165, \u6570\u91cf: {}, \u7ef4\u5ea6: {}", (Object)embeddings.size(), (Object)(embeddings.isEmpty() ? 0 : ((List)embeddings.get(0)).size()));
            return embeddings;
        }
        catch (NoApiKeyException e) {
            logger.error("\u6279\u91cf\u8c03\u7528\u65f6 API Key \u672a\u8bbe\u7f6e\u6216\u65e0\u6548", (Throwable)e);
            throw new RuntimeException("API Key \u672a\u8bbe\u7f6e\uff0c\u8bf7\u914d\u7f6e dashscope.api.key", e);
        }
        catch (Exception e) {
            logger.error("\u6279\u91cf\u751f\u6210\u5411\u91cf\u5d4c\u5165\u5931\u8d25", (Throwable)e);
            throw new RuntimeException("\u6279\u91cf\u751f\u6210\u5411\u91cf\u5d4c\u5165\u5931\u8d25: " + e.getMessage(), e);
        }
    }

    public List<Float> generateQueryVector(String query) {
        return this.generateEmbedding(query);
    }

    public float calculateCosineSimilarity(List<Float> vector1, List<Float> vector2) {
        if (vector1.size() != vector2.size()) {
            throw new IllegalArgumentException("\u5411\u91cf\u7ef4\u5ea6\u4e0d\u5339\u914d");
        }
        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;
        for (int i = 0; i < vector1.size(); ++i) {
            dotProduct += vector1.get(i).floatValue() * vector2.get(i).floatValue();
            norm1 += vector1.get(i).floatValue() * vector1.get(i).floatValue();
            norm2 += vector2.get(i).floatValue() * vector2.get(i).floatValue();
        }
        return dotProduct / (float)(Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
