package com.gagneflow.service.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
// Gson 是 Milvus Java SDK 的传递依赖，InsertParam.Field 的 metadata 参数接受 com.google.gson.JsonObject 类型
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.gagneflow.service.vector.VectorEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class K12VectorInitializer {
    private static final Logger logger = LoggerFactory.getLogger(K12VectorInitializer.class);
    @Autowired(required=false)
    private MilvusServiceClient milvusClient;
    @Autowired(required=false)
    private VectorEmbeddingService embeddingService;
    @Value(value="${gagneflow.k12.path:lesson-plan-docs/k12_curriculum.json}")
    private String k12Path;
    @Value(value="${gagneflow.lesson-plan.k12-index-enabled:true}")
    private boolean enabled;

    @EventListener(value={ApplicationReadyEvent.class})
    public void init() {
        if (!this.enabled || this.milvusClient == null || this.embeddingService == null) {
            logger.info("K12 \u5411\u91cf\u7d22\u5f15\u8df3\u8fc7: enabled={}, milvus={}, embedding={}", new Object[]{this.enabled, this.milvusClient != null, this.embeddingService != null});
            return;
        }
        try {
            logger.info("\u5f00\u59cb\u9884\u7f6e K12 \u8bfe\u7a0b\u6807\u51c6\u5230 Milvus...");
            String json = Files.readString(Paths.get(this.k12Path, new String[0]), StandardCharsets.UTF_8);
            JsonNode root = new ObjectMapper().readTree(json);
            ArrayList<String> texts = new ArrayList<String>();
            ArrayList<Map<String, Object>> metadata = new ArrayList<Map<String, Object>>();
            this.extractTexts(root, "", texts, metadata);
            int batchSize = 50;
            int total = 0;
            for (int i = 0; i < texts.size(); i += batchSize) {
                int end = Math.min(i + batchSize, texts.size());
                List<String> batch = texts.subList(i, end);
                ArrayList<List<Float>> vectors = new ArrayList<List<Float>>();
                for (String text : batch) {
                    List<Float> vec = this.embeddingService.generateEmbedding(text);
                    vectors.add(vec);
                }
                ArrayList<String> batchIds = new ArrayList<String>();
                ArrayList<String> batchContents = new ArrayList<String>();
                ArrayList<List> batchVectors = new ArrayList<List>();
                ArrayList<JsonObject> batchMetas = new ArrayList<JsonObject>();
                for (int j = i; j < end; ++j) {
                    batchIds.add(UUID.nameUUIDFromBytes(("k12_" + j).getBytes()).toString());
                    batchContents.add((String)batch.get(j - i));
                    batchVectors.add((List)vectors.get(j - i));
                    JsonObject meta = new JsonObject();
                    meta.addProperty("_source", "k12_curriculum");
                    meta.addProperty("_type", "curriculum_standard");
                    meta.addProperty("chunkIndex", (Number)j);
                    meta.addProperty("totalChunks", (Number)texts.size());
                    batchMetas.add(meta);
                }
                // 修复BUG 7: 插入前确保 Collection 已加载
                try {
                    R loadResp = this.milvusClient.loadCollection(
                        LoadCollectionParam.newBuilder().withCollectionName("biz").build());
                    if (loadResp.getStatus() != 0 && loadResp.getStatus() != 65535) {
                        logger.warn("加载 collection 'biz' 时出现警告: {}", loadResp.getMessage());
                    }
                } catch (Exception loadEx) {
                    logger.warn("加载 collection 失败（尝试继续插入）: {}", loadEx.getMessage());
                }
                InsertParam insertParam = InsertParam.newBuilder().withCollectionName("biz").withFields(Arrays.asList(new InsertParam.Field("id", batchIds), new InsertParam.Field("content", batchContents), new InsertParam.Field("vector", batchVectors), new InsertParam.Field("metadata", batchMetas))).build();
                R insertResp = this.milvusClient.insert(insertParam);
                if (insertResp.getStatus() != 0) {
                    logger.error("K12 \u6570\u636e\u63d2\u5165\u5931\u8d25: {}", (Object)insertResp.getMessage());
                    continue;
                }
                logger.info("K12 \u7d22\u5f15\u8fdb\u5ea6: {}/{} \u6761", (Object)(total += batch.size()), (Object)texts.size());
            }
            logger.info("K12 \u8bfe\u7a0b\u6807\u51c6\u5411\u91cf\u7d22\u5f15\u5b8c\u6210\uff0c\u5171 {} \u6761", (Object)total);
        }
        catch (Exception e) {
            logger.warn("K12 \u5411\u91cf\u7d22\u5f15\u5931\u8d25\uff08\u5e94\u7528\u4ecd\u53ef\u8fd0\u884c\uff0c\u5c06\u4f7f\u7528 JSON \u964d\u7ea7\u67e5\u8be2\uff09: {}", (Object)e.getMessage());
        }
    }

    private void extractTexts(JsonNode node, String prefix, List<String> texts, List<Map<String, Object>> meta) {
        block7: {
            block6: {
                String context;
                if (!node.isObject()) break block6;
                StringBuilder sb = new StringBuilder(prefix);
                if (node.has("name")) {
                    sb.append(node.get("name").asText()).append(" ");
                }
                if (node.has("grade")) {
                    sb.append(node.get("grade").asText()).append(" ");
                }
                if (node.has("\u9636\u6bb5")) {
                    sb.append(node.get("\u9636\u6bb5").asText()).append(" ");
                }
                if (!(context = sb.toString().trim()).isEmpty()) {
                    texts.add(context);
                }
                for (JsonNode child : node) {
                    this.extractTexts(child, context, texts, meta);
                }
                break block7;
            }
            if (!node.isArray()) break block7;
            for (JsonNode item : node) {
                this.extractTexts(item, prefix, texts, meta);
            }
        }
    }
}
