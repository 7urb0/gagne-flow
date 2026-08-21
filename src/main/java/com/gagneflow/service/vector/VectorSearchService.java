package com.gagneflow.service.vector;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import com.gagneflow.dto.RerankResult;
import com.gagneflow.service.rag.RerankService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VectorSearchService {
    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    /**
     * 反哺教案参与检索的最低质量分数门槛 (RAG 反哺质量闭环)。
     * 低于该分数的 generated_lesson_plan 不参与检索，宁缺毋滥；
     * k12_curriculum 与无 _user_id 的上传文档不受影响（无 _score 字段）。
     */
    static final int MIN_LESSON_PLAN_SCORE = 85;

    @Autowired
    private MilvusServiceClient milvusClient;
    @Autowired
    private VectorEmbeddingService embeddingService;
    @Autowired
    private RerankService rerankService;
    @Value(value="${dashscope.rerank.search-top-k:15}")
    private int searchTopK;
    @Value(value="${dashscope.rerank.top-n:3}")
    private int rerankTopN;
    @Value(value="${milvus.search.nprobe:16}")
    private int nprobe;
    @Value(value="${milvus.search.nprobe-max:64}")
    private int nprobeMax;
    @Value(value="${milvus.search.nprobe-adaptive:true}")
    private boolean nprobeAdaptive;

    @CircuitBreaker(name="milvus", fallbackMethod="searchSimilarDocumentsFallback")
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        // 2026-08-19: 教案反哺独立到 personal_plans 后, 去重探针查个人教案库(仅当前用户, 防同用户重复教案)
        return searchSimilarLessonPlans(query, topK, 0L);
    }

    /** 2026-08-19: 个人教案库去重探针 - 仅查指定用户的教案(_user_id 精确匹配), 跨用户教案不互相去重.
     *  Also guarded by milvus circuit-breaker (review 2026-08-19: 探针也真实打 Milvus, 熔断时降级为"不去重", 由调用方 try-catch 兜底). */
    @CircuitBreaker(name="milvus", fallbackMethod="searchSimilarLessonPlansFallback")
    public List<SearchResult> searchSimilarLessonPlans(String query, int topK, Long userId) {
        String expr = "metadata[\"_source\"] == \"generated_lesson_plan\"";
        if (userId != null && userId > 0L) {
            expr = "(" + buildPersonalPlansExpr(userId) + ") && metadata[\"_source\"] == \"generated_lesson_plan\"";
        }
        return searchCollection(
                com.gagneflow.constant.MilvusConstants.PERSONAL_PLANS_COLLECTION,
                query, topK, this.nprobe,
                this.embeddingService.generateQueryVector(query),
                expr);
    }

    /** Milvus 熔断器 fallback (去重探针): 返回空代表"无已存在相似教案", 即不阻止本次回灌 */
    public List<SearchResult> searchSimilarLessonPlansFallback(String query, int topK, Long userId, Throwable t) {
        logger.warn("[CIRCUIT-BREAKER] Milvus\u7194\u65ad\u5668\u5df2\u6253\u5f00(\u53bb\u91cd\u63a2\u9488), \u8fd4\u56de\u7a7a\u7ed3\u679c(\u4e0d\u963b\u6b62\u56de\u704c)\u3002\u67e5\u8be2: {}, \u539f\u56e0: {}", query, t.getMessage());
        return Collections.emptyList();
    }

    /**
     * P1修复: Milvus 熔断器 fallback — 返回空结果并记录降级日志
     */
    public List<SearchResult> searchSimilarDocumentsFallback(String query, int topK, Throwable t) {
        logger.warn("[CIRCUIT-BREAKER] Milvus\u7194\u65ad\u5668\u5df2\u6253\u5f00\uff0c\u8fd4\u56de\u7a7a\u641c\u7d22\u7ed3\u679c\u3002\u67e5\u8be2: {}, \u539f\u56e0: {}", query, t.getMessage());
        return Collections.emptyList();
    }

    private List<SearchResult> searchWithAdaptiveNprobe(String query, int topK) {
        return this.searchWithAdaptiveNprobe(query, topK, 0L);
    }

    private List<SearchResult> searchWithAdaptiveNprobe(String query, int topK, Long userId) {
        // P2修复: 简化nprobe自适应循环逻辑，使用明确的边界检查
        int curNprobe = this.nprobe;
        List<SearchResult> results = this.doSearch(query, topK, curNprobe, userId);
        while (results.size() < topK && this.nprobeAdaptive && curNprobe < this.nprobeMax) {
            int prevNprobe = curNprobe;
            curNprobe = Math.min(curNprobe * 2, this.nprobeMax);
            logger.info("nprobe \u81ea\u9002\u5e94: {} \u2192 {}, \u5f53\u524d\u53ec\u56de: {}", new Object[]{prevNprobe, curNprobe, results.size()});
            results = this.doSearch(query, topK, curNprobe, userId);
        }
        return results;
    }

    private List<SearchResult> doSearch(String query, int topK, int nprobeVal, Long userId) {
        // 2026-08-19: 双 collection 查询合并 - biz(课标+上传文档) + personal_plans(个人教案)
        ArrayList<SearchResult> results = new ArrayList<>();
        List<Float> queryVector = this.embeddingService.generateQueryVector(query);
        // 1. 公共知识库 biz 检索
        results.addAll(searchCollection("biz", query, topK, nprobeVal, queryVector, buildSearchExpr(userId)));
        // 2. 个人教案库 personal_plans 检索(仅登录用户; 教案必须 _score >= 门槛才进候选, 宁缺毋滥)
        if (userId != null && userId > 0L) {
            try {
                results.addAll(searchCollection(
                        com.gagneflow.constant.MilvusConstants.PERSONAL_PLANS_COLLECTION,
                        query, topK, nprobeVal, queryVector,
                        String.format("(%s) && metadata[\"_score\"] >= %d",
                                buildPersonalPlansExpr(userId), MIN_LESSON_PLAN_SCORE)));
            } catch (Exception e) {
                logger.warn("[ADDRF] \u4e2a\u4eba\u6559\u6848\u5e93\u68c0\u7d22\u5931\u8d25(\u4e0d\u5f71\u54cd biz \u7ed3\u679c): {}", e.getMessage());
            }
        }
        // 3. 按相似度降序合并
        results.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return results;
    }

    /** \u5355 collection \u68c0\u7d22\uff08\u590d\u7528\u539f doSearch \u5185\u6838\uff09 */
    private List<SearchResult> searchCollection(String collectionName, String query, int topK, int nprobeVal,
                                                List<Float> queryVector, String expr) {
        // P0\u4fee\u590d: \u4f7f\u7528 L2 \u8ddd\u79bb\uff0c\u4e0e\u7d22\u5f15\u7aef MetricType \u4e00\u81f4
        SearchParam.Builder builder = SearchParam.newBuilder().withCollectionName(collectionName).withVectorFieldName("vector").withVectors(Collections.singletonList(queryVector)).withTopK(Integer.valueOf(topK)).withMetricType(MetricType.L2).withOutFields(List.of("id", "content", "metadata")).withParams(String.format("{\"nprobe\":%d}", nprobeVal));
        if (expr != null && !expr.isBlank()) {
            builder.withExpr(expr);
        }
        R searchResponse = this.milvusClient.search(builder.build());
        if (searchResponse.getStatus() != 0) {
            throw new RuntimeException("\u5411\u91cf\u641c\u7d22\u5931\u8d25(" + collectionName + "): " + searchResponse.getMessage());
        }
        SearchResultsWrapper wrapper = new SearchResultsWrapper(((SearchResults)searchResponse.getData()).getResults());
        ArrayList<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < wrapper.getRowRecords(0).size(); ++i) {
            SearchResult result = new SearchResult();
            result.setId((String)((SearchResultsWrapper.IDScore)wrapper.getIDScore(0).get(i)).get("id"));
            result.setContent((String)wrapper.getFieldData("content", 0).get(i));
            double l2Score = ((SearchResultsWrapper.IDScore)wrapper.getIDScore(0).get(i)).getScore();
            // L2\u8ddd\u79bb\u8f6c\u76f8\u4f3c\u5ea6: score = 1/(1+distance), \u8ddd\u79bb\u8d8a\u5c0f\u76f8\u4f3c\u5ea6\u8d8a\u9ad8
            result.setScore((float)(1.0 / (1.0 + l2Score)));
            Object metaObj = wrapper.getFieldData("metadata", 0).get(i);
            if (metaObj != null) {
                result.setMetadata(metaObj.toString());
            }
            results.add(result);
        }
        return results;
    }

    /**
     * 构造 Milvus 检索过滤表达式 (RAG 反哺质量闭环)。
     * 质量分层: generated_lesson_plan 来源仅 _score >= MIN_LESSON_PLAN_SCORE 的教案进入候选；
     * 用户上传文档 (有 _user_id)、教育部课标原文 (curriculum_2022, 无 _user_id) 不受影响。
     * 注意: 表达式中的 _score 为数值比较，与写入端 metadata.put("_score", score) 类型一致。
     */
    static String buildSearchExpr(Long userId) {
        String uid = String.valueOf(userId != null ? userId : 0L);
        // 2026-08-19: 教案反哺已独立到 personal_plans collection, biz 不再包含 generated_lesson_plan
        // biz 检索 = 本人上传文档 + 公共课标/无归属文档, 无需再拼教案分数门槛
        return String.format(
                "(metadata[\"_user_id\"] == \"%s\" && metadata[\"_source\"] != \"generated_lesson_plan\")"
                        + " || not exists metadata[\"_user_id\"]",
                uid);
    }

    /** 2026-08-19: 个人教案库检索过滤 - 仅本人教案(_user_id 精确匹配) */
    static String buildPersonalPlansExpr(Long userId) {
        String uid = String.valueOf(userId != null ? userId : 0L);
        return String.format("metadata[\"_user_id\"] == \"%s\"", uid);
    }

    public List<SearchResult> searchWithRerank(String query) {
        return this.searchWithRerank(query, 0L);
    }

    /** 2026-08-19: 外部入口亦加熔断(InternalDocsTools 通过 this 调 4 参版不触发代理, 需在入口先拦截) */
    @CircuitBreaker(name="milvus", fallbackMethod="searchWithRerankLongFallback")
    public List<SearchResult> searchWithRerank(String query, Long userId) {
        return this.searchWithRerank(query, this.searchTopK, this.rerankTopN, userId);
    }

    public List<SearchResult> searchWithRerank(String query, int searchTopK, int finalTopK) {
        return this.searchWithRerank(query, searchTopK, finalTopK, 0L);
    }

    /**
     * 2026-08-19: Milvus 熔断器挂在真实检索链路入口（修复: 原挂在已无生产调用的 searchSimilarDocuments 上）。
     * 生产调用链: searchWithRerank → searchWithAdaptiveNprobe → doSearch(双库) → searchCollection。
     * 熔断打开时经 fallback 返回空候选, RagService/InternalDocsTools 走"未找到文档"或 k12 fallback 降级。
     */
    @CircuitBreaker(name="milvus", fallbackMethod="searchWithRerankFallback")
    public List<SearchResult> searchWithRerank(String query, int searchTopK, int finalTopK, Long userId) {
        logger.info("\u5f00\u59cb\u641c\u7d22+\u91cd\u6392\u6d41\u7a0b, \u67e5\u8be2: {}, \u7c97\u6392\u53ec\u56de: {}, \u7cbe\u6392\u8fd4\u56de: {}, userId: {}", new Object[]{query, searchTopK, finalTopK, userId});
        List<SearchResult> candidates = this.searchWithAdaptiveNprobe(query, searchTopK, userId);
        if (candidates.isEmpty()) {
            logger.warn("\u5411\u91cf\u68c0\u7d22\u65e0\u7ed3\u679c\uff0c\u8df3\u8fc7\u91cd\u6392");
            return candidates;
        }
        if (candidates.size() <= finalTopK) {
            logger.info("\u5019\u9009\u6570\u91cf({})\u4e0d\u8d85\u8fc7\u6700\u7ec8\u9700\u6c42({})\uff0c\u8df3\u8fc7\u91cd\u6392\uff0c\u76f4\u63a5\u8fd4\u56de", (Object)candidates.size(), (Object)finalTopK);
            return candidates;
        }
        List<SearchResult> reranked = this.rerankResults(query, candidates, finalTopK);
        logger.info("\u641c\u7d22+\u91cd\u6392\u5b8c\u6210: \u4ece {} \u4e2a\u5019\u9009\u4e2d\u7cbe\u6392\u8fd4\u56de\u524d {} \u4e2a", (Object)candidates.size(), (Object)reranked.size());
        return reranked;
    }

    /** Milvus 熔断器 fallback: 真实检索链路(Milvus 不可用)降级为空候选, 由调用方走"未找到文档"/k12 fallback */
    public List<SearchResult> searchWithRerankFallback(String query, int searchTopK, int finalTopK, Long userId, Throwable t) {
        logger.warn("[CIRCUIT-BREAKER] Milvus\u7194\u65ad\u5668\u5df2\u6253\u5f00(\u771f\u5b9e\u68c0\u7d22\u94fe\u8def), \u8fd4\u56de\u7a7a\u7ed3\u679c\u3002\u67e5\u8be2: {}, \u539f\u56e0: {}", query, t.getMessage());
        return Collections.emptyList();
    }

    /** Milvus 熔断器 fallback (2 参入口对应的降级, 供 InternalDocsTools 路径) */
    public List<SearchResult> searchWithRerankLongFallback(String query, Long userId, Throwable t) {
        logger.warn("[CIRCUIT-BREAKER] Milvus\u7194\u65ad\u5668\u5df2\u6253\u5f00(\u5de5\u5177\u68c0\u7d22), \u8fd4\u56de\u7a7a\u7ed3\u679c\u3002\u67e5\u8be2: {}, \u539f\u56e0: {}", query, t.getMessage());
        return Collections.emptyList();
    }

    private List<SearchResult> rerankResults(String query, List<SearchResult> candidates, int topK) {
        try {
            List<String> documents = candidates.stream().map(SearchResult::getContent).collect(Collectors.toList());
            List<RerankResult> rerankResults = this.rerankService.rerank(query, documents, topK);
            if (rerankResults.isEmpty()) {
                logger.warn("\u91cd\u6392\u8fd4\u56de\u7a7a\u7ed3\u679c\uff0c\u964d\u7ea7\u4f7f\u7528\u539f\u59cb\u5411\u91cf\u6392\u5e8f\u7684\u524d{}\u4e2a", (Object)topK);
                return candidates.stream().limit(topK).collect(Collectors.toList());
            }
            ArrayList<SearchResult> reranked = new ArrayList<SearchResult>();
            for (RerankResult rr : rerankResults) {
                int originalIndex = rr.getIndex();
                if (originalIndex < 0 || originalIndex >= candidates.size()) continue;
                SearchResult original = candidates.get(originalIndex);
                SearchResult newResult = new SearchResult();
                newResult.setId(original.getId());
                newResult.setContent(original.getContent());
                newResult.setMetadata(original.getMetadata());
                newResult.setScore((float)rr.getRelevanceScore());
                reranked.add(newResult);
            }
            return reranked;
        }
        catch (Exception e) {
            logger.error("\u91cd\u6392\u5931\u8d25\uff0c\u964d\u7ea7\u4f7f\u7528\u539f\u59cb\u5411\u91cf\u6392\u5e8f\u7684\u524d{}\u4e2a: {}", (Object)topK, (Object)e.getMessage());
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }
    }

    public boolean isRerankAvailable() {
        try {
            return this.rerankService != null;
        }
        catch (Exception e) {
            return false;
        }
    }

    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;

        public void setId(String id) {
            this.id = id;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public void setScore(float score) {
            this.score = score;
        }

        public void setMetadata(String metadata) {
            this.metadata = metadata;
        }

        public String getId() {
            return this.id;
        }

        public String getContent() {
            return this.content;
        }

        public float getScore() {
            return this.score;
        }

        public String getMetadata() {
            return this.metadata;
        }
    }
}
