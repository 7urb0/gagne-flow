package com.gagneflow.service.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
// Gson 是 Milvus Java SDK 的传递依赖，InsertParam.Field 的 metadata 参数接受 com.google.gson.JsonObject 类型
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
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
    @Value(value="${gagneflow.lesson-plan.k12-index-enabled:false}")
    private boolean enabled;

    @EventListener(value={ApplicationReadyEvent.class})
    public void init() {
        if (!this.enabled || this.milvusClient == null || this.embeddingService == null) {
            logger.info("K12 \u5411\u91cf\u7d22\u5f15\u8df3\u8fc7: enabled={}, milvus={}, embedding={}", new Object[]{this.enabled, this.milvusClient != null, this.embeddingService != null});
            return;
        }
        // 幂等保护: 自建知识点已入库则跳过, 防止重启后重复灌入/复活已删除分片
        try {
            QueryParam qp = QueryParam.newBuilder()
                    .withCollectionName("biz")
                    .withExpr("metadata[\"_source\"] == \"k12_curriculum\"")
                    .withOutFields(java.util.List.of("id"))
                    .build();
            R<io.milvus.grpc.QueryResults> qr = this.milvusClient.query(qp);
            if (qr.getData() != null) {
                QueryResultsWrapper wrapper = new QueryResultsWrapper(qr.getData());
                if (wrapper.getRowCount() > 0) {
                    logger.info("K12 \u81ea\u5efa\u77e5\u8bc6\u70b9\u5df2\u5b58\u5728 Milvus ({} \u6761), \u8df3\u8fc7\u9884\u7f6e, \u907f\u514d\u91cd\u542f\u590d\u6d3b\u5df2\u5220\u5206\u7247", wrapper.getRowCount());
                    return;
                }
            }
        } catch (Exception e) {
            logger.warn("K12 \u5e42\u7b49\u68c0\u67e5\u5931\u8d25, \u7ee7\u7eed\u5c1d\u8bd5\u63d2\u5165: {}", e.getMessage());
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
        if (!node.isObject()) {
            if (node.isArray()) {
                for (JsonNode item : node) {
                    this.extractTexts(item, prefix, texts, meta);
                }
            }
            return;
        }
        StringBuilder sb = new StringBuilder(prefix);
        if (node.has("name")) {
            sb.append(node.get("name").asText()).append(" ");
        }
        if (node.has("grade")) {
            sb.append(node.get("grade").asText()).append(" ");
        }
        String context = sb.toString().trim();
        if (node.has("章节") && node.get("章节").isArray()) {
            for (JsonNode ch : node.get("章节")) {
                if (!ch.isObject() || !ch.has("name")) {
                    continue;
                }
                String chapterName = ch.get("name").asText();
                String req = ch.has("内容要求") ? ch.get("内容要求").asText() : "";
                JsonNode kps = ch.get("知识点");
                if (kps != null && kps.isArray() && kps.size() > 0) {
                    for (JsonNode kp : kps) {
                        String text = (context + " " + chapterName + "：" + req + " 知识点：" + kp.asText()).trim();
                        if (!text.isEmpty()) {
                            texts.add(text);
                        }
                    }
                } else {
                    String text = (context + " " + chapterName + "：" + req).trim();
                    if (!text.isEmpty()) {
                        texts.add(text);
                    }
                }
            }
        }
        for (JsonNode child : node) {
            this.extractTexts(child, context, texts, meta);
        }
    }
}
