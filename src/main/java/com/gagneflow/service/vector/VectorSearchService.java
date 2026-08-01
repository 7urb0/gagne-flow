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
        return doSearch(query, topK, this.nprobe, 0L);
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
        SearchParam searchParam;
        R searchResponse;
        List<Float> queryVector = this.embeddingService.generateQueryVector(query);
        // P0修复: 使用 L2 距离，与索引端 MetricType 一致
        SearchParam.Builder builder = SearchParam.newBuilder().withCollectionName("biz").withVectorFieldName("vector").withVectors(Collections.singletonList(queryVector)).withTopK(Integer.valueOf(topK)).withMetricType(MetricType.L2).withOutFields(List.of("id", "content", "metadata")).withParams(String.format("{\"nprobe\":%d}", nprobeVal));
        if (userId != null && userId > 0L) {
            builder.withExpr(String.format("metadata[\"_user_id\"] == \"%s\" || metadata[\"_source\"] == \"k12_curriculum\" || not exists metadata[\"_user_id\"]", String.valueOf(userId)));
        }
        if ((searchResponse = this.milvusClient.search(searchParam = builder.build())).getStatus() != 0) {
            throw new RuntimeException("\u5411\u91cf\u641c\u7d22\u5931\u8d25: " + searchResponse.getMessage());
        }
        SearchResultsWrapper wrapper = new SearchResultsWrapper(((SearchResults)searchResponse.getData()).getResults());
        ArrayList<SearchResult> results = new ArrayList<SearchResult>();
        for (int i = 0; i < wrapper.getRowRecords(0).size(); ++i) {
            SearchResult result = new SearchResult();
            result.setId((String)((SearchResultsWrapper.IDScore)wrapper.getIDScore(0).get(i)).get("id"));
            result.setContent((String)wrapper.getFieldData("content", 0).get(i));
            double l2Score = ((SearchResultsWrapper.IDScore)wrapper.getIDScore(0).get(i)).getScore();
            // L2距离转相似度: score = 1/(1+distance), 距离越小相似度越高
            result.setScore((float)(1.0 / (1.0 + l2Score)));
            Object metaObj = wrapper.getFieldData("metadata", 0).get(i);
            if (metaObj != null) {
                result.setMetadata(metaObj.toString());
            }
            results.add(result);
        }
        return results;
    }

    public List<SearchResult> searchWithRerank(String query) {
        return this.searchWithRerank(query, 0L);
    }

    public List<SearchResult> searchWithRerank(String query, Long userId) {
        return this.searchWithRerank(query, this.searchTopK, this.rerankTopN, userId);
    }

    public List<SearchResult> searchWithRerank(String query, int searchTopK, int finalTopK) {
        return this.searchWithRerank(query, searchTopK, finalTopK, 0L);
    }

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
