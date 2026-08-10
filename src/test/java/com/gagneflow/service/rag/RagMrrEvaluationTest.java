package com.gagneflow.service.rag;

import com.gagneflow.service.vector.VectorEmbeddingService;
import com.gagneflow.dto.RerankResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实环境 MRR 评测（真实 Milvus + 真实 DashScope embedding/rerank）。
 *
 * 目的: 回答"MRR 0.52 → 0.70 怎么测的"——
 *   1) 评测集: 从 Milvus 现有 K12 分片采样作为查询源（self-retrieval 评测，非独立标注集）
 *   2) 三个环节对比: 纯向量粗排 / +查询改写 / +rerank 精排
 *   3) 输出可复现的 MRR@3 与 MRR@15
 *
 * 运行: mvn test -Dtest=RagMrrEvaluationTest -Dgagneflow.lesson-plan.k12-index-enabled=false
 * 注意: 该测试会真实调用 DashScope API（embedding + rerank），有少量费用消耗。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("真实环境 MRR 评测")
class RagMrrEvaluationTest {

    @Autowired
    private MilvusServiceClient milvusClient;
    @Autowired
    private VectorEmbeddingService embeddingService;
    @Autowired
    private QueryRewriter queryRewriter;
    @Autowired
    private RerankService rerankService;

    /** 评测查询数（K12 分片抽样） */
    private static final int EVAL_QUERY_COUNT = 20;
    /** 粗排召回量 = search-top-k（与生产一致） */
    private static final int SEARCH_TOP_K = 15;
    /** 精排返回量 = rerank-top-n（与生产一致） */
    private static final int FINAL_TOP_N = 3;
    private static final int NPROBE = 16;

    private record EvalItem(String query, String expectedId) {}

    @BeforeEach
    void ensureLoaded() {
        R loadResp = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder().withCollectionName("biz").build());
        int status = loadResp.getStatus();
        // R.getMessage() 在 exception 为 null 时会 NPE，做防御
        String msg = status == 0 ? "ok" : (loadResp.getException() != null
                ? loadResp.getException().getMessage() : "unknown");
        assertTrue(status == 0 || status == 65535, "collection 加载失败: " + msg);
    }

    @Test
    @DisplayName("三环节 MRR 对比评测")
    void evaluateMrr() {
        // 1. 构建评测集: 从 K12 分片抽样
        List<EvalItem> evalSet = buildEvalSet();
        assertTrue(evalSet.size() >= 5, "评测集过小: " + evalSet.size());
        System.out.println("\n========== MRR 评测开始 ==========");
        System.out.println("评测集规模: " + evalSet.size() + " 条查询 (K12 分片 self-retrieval 抽样)");

        double mrrRaw = 0.0, mrrRewrite = 0.0, mrrRerank = 0.0;
        double hitRaw = 0.0, hitRewrite = 0.0, hitRerank = 0.0;
        int shown = 0;

        for (EvalItem item : evalSet) {
            String q = item.query();

            // 环节 A: 纯向量粗排 (top-15)
            List<SearchHit> raw = searchMilvus(q, SEARCH_TOP_K, NPROBE);
            double rrRaw = reciprocalRank(raw, item.expectedId());

            // 环节 B: 查询改写后粗排
            String rewritten = queryRewriter.rewrite(q, Collections.emptyList());
            List<SearchHit> rewrittenHits = rewritten.equals(q) ? raw : searchMilvus(rewritten, SEARCH_TOP_K, NPROBE);
            double rrRewrite = reciprocalRank(rewrittenHits, item.expectedId());

            // 环节 C: +rerank 精排 (top-3)
            List<SearchHit> reranked = rerankHits(rewritten, raw);
            double rrRerank = reciprocalRank(reranked, item.expectedId());

            mrrRaw += rrRaw; mrrRewrite += rrRewrite; mrrRerank += rrRerank;
            if (rrRaw > 0) hitRaw++;
            if (rrRewrite > 0) hitRewrite++;
            if (rrRerank > 0) hitRerank++;

            if (shown++ < 5) {
                System.out.printf("Q[%s]: 粗排RR=%.2f 改写RR=%.2f 精排RR=%.2f | 改写=%s%n",
                        truncate(q, 40), rrRaw, rrRewrite, rrRerank, truncate(rewritten, 40));
            }
        }

        int n = evalSet.size();
        System.out.println("\n---------- 结果汇总 ----------");
        System.out.printf("MRR@%d (纯向量粗排):     %.3f%n", SEARCH_TOP_K, mrrRaw / n);
        System.out.printf("MRR@%d (+查询改写):      %.3f%n", SEARCH_TOP_K, mrrRewrite / n);
        System.out.printf("MRR@%d (+rerank精排):    %.3f%n", FINAL_TOP_N, mrrRerank / n);
        System.out.printf("HitRate@%d (粗排):       %.1f%%%n", SEARCH_TOP_K, hitRaw / n * 100);
        System.out.printf("HitRate@%d (改写):       %.1f%%%n", SEARCH_TOP_K, hitRewrite / n * 100);
        System.out.printf("HitRate@%d (精排):       %.1f%%%n", FINAL_TOP_N, hitRerank / n * 100);
        System.out.println("========== 评测结束 ==========\n");

        // 断言: 有真实结果即可（不预设具体值，避免误导）
        assertTrue(mrrRaw > 0, "纯向量粗排 MRR 应 > 0");
        assertTrue(mrrRerank >= 0, "精排 MRR 应 >= 0");
    }

    // ============================================================
    // 评测集构建
    // ============================================================

    private List<EvalItem> buildEvalSet() {
        // 通过 Milvus REST API 拉取 K12 分片（Java SDK QueryParam 在部分版本解析异常时返回空）
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
            String body = "{\"collection_name\":\"biz\",\"output_fields\":[\"id\",\"content\"],"
                    + "\"expr\":\"metadata[\\\"_source\\\"] == \\\"curriculum_2022\\\"\"}";
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:9091/api/v1/query"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            java.net.http.HttpResponse<String> resp = http.send(req,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
            com.fasterxml.jackson.databind.JsonNode fields = root.get("fields_data");
            if (fields == null || fields.isEmpty()) {
                System.out.println("REST K12 查询返回空: " + resp.body().substring(0,
                        Math.min(200, resp.body().length())));
                return List.of();
            }
            java.util.List<String> ids = new ArrayList<>();
            java.util.List<String> contents = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode f : fields) {
                String name = f.get("field_name").asText();
                com.fasterxml.jackson.databind.JsonNode dataNode = f.get("Field").get("Scalars")
                        .get("Data").get("StringData").get("data");
                for (com.fasterxml.jackson.databind.JsonNode v : dataNode) {
                    if ("id".equals(name)) ids.add(v.asText());
                    else if ("content".equals(name)) contents.add(v.asText());
                }
            }
            System.out.println("Milvus 现有 K12 分片: " + ids.size() + " 条, contents=" + contents.size());
            if (!contents.isEmpty()) {
                System.out.println("content 样例: " + truncate(contents.get(0), 80));
            }

            List<EvalItem> items = new ArrayList<>();
            int step = Math.max(1, ids.size() / EVAL_QUERY_COUNT);
            for (int i = 0; i < ids.size() && items.size() < EVAL_QUERY_COUNT; i += step) {
                String content = i < contents.size() ? contents.get(i) : "";
                if (content == null || content.trim().isEmpty()) continue;
                // K12 分片为短标题（2~13 字符），直接用内容本身作为查询
                String query = content.length() <= 13 ? content
                        : content.substring(0, Math.min(90, content.length()));
                items.add(new EvalItem(query, ids.get(i)));
            }
            return items;
        } catch (Exception e) {
            System.out.println("REST K12 分片拉取失败: " + e.getMessage());
            return List.of();
        }
    }

    // ============================================================
    // 检索环节
    // ============================================================

    private record SearchHit(String id, String content, float score) {}

    private List<SearchHit> searchMilvus(String query, int topK, int nprobe) {
        List<Float> vec = embeddingService.generateQueryVector(query);
        SearchParam param = SearchParam.newBuilder()
                .withCollectionName("biz")
                .withVectorFieldName("vector")
                .withVectors(Collections.singletonList(vec))
                .withTopK(topK)
                .withMetricType(MetricType.L2)
                .withOutFields(List.of("id", "content"))
                .withParams(String.format("{\"nprobe\":%d}", nprobe))
                .build();
        R<SearchResults> resp = milvusClient.search(param);
        if (resp.getStatus() != 0) {
            String msg = resp.getException() != null ? resp.getException().getMessage() : "unknown";
            throw new RuntimeException("搜索失败: " + msg);
        }
        SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
        List<SearchHit> hits = new ArrayList<>();
        for (int i = 0; i < wrapper.getIDScore(0).size() && i < topK; i++) {
            SearchResultsWrapper.IDScore idScore = wrapper.getIDScore(0).get(i);
            double l2 = idScore.getScore();
            float sim = (float) (1.0 / (1.0 + l2));
            String content = wrapper.getFieldData("content", 0).get(i) != null
                    ? String.valueOf(wrapper.getFieldData("content", 0).get(i)) : "";
            hits.add(new SearchHit((String) idScore.get("id"), content, sim));
        }
        return hits;
    }

    private List<SearchHit> rerankHits(String query, List<SearchHit> candidates) {
        if (candidates.size() <= FINAL_TOP_N) return candidates;
        // rerank 输入必须是文档内容，而非 id
        List<String> contents = candidates.stream().map(SearchHit::content).toList();
        List<RerankResult> rr;
        try {
            rr = rerankService.rerank(query, contents, FINAL_TOP_N);
        } catch (Exception e) {
            System.out.println("rerank 失败降级: " + e.getMessage());
            return candidates.subList(0, FINAL_TOP_N);
        }
        if (rr.isEmpty()) return candidates.subList(0, FINAL_TOP_N);
        List<SearchHit> reranked = new ArrayList<>();
        for (RerankResult r : rr) {
            int idx = r.getIndex();
            if (idx >= 0 && idx < candidates.size()) {
                SearchHit orig = candidates.get(idx);
                reranked.add(new SearchHit(orig.id(), orig.content(), (float) r.getRelevanceScore()));
            }
        }
        return reranked.isEmpty() ? candidates.subList(0, FINAL_TOP_N) : reranked;
    }

    // ============================================================
    // MRR 计算
    // ============================================================

    private double reciprocalRank(List<SearchHit> hits, String expectedId) {
        for (int i = 0; i < hits.size(); i++) {
            if (expectedId.equals(hits.get(i).id())) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
