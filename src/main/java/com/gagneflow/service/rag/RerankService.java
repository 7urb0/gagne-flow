package com.gagneflow.service.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gagneflow.dto.RerankResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;

@Service
public class RerankService {
    private static final Logger logger = LoggerFactory.getLogger(RerankService.class);
    private static final String RERANK_API_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    @Value(value="${spring.ai.dashscope.api-key}")
    private String apiKey;
    @Value(value="${dashscope.rerank.model:gte-rerank}")
    private String model;
    @Value(value="${dashscope.rerank.top-n:3}")
    private int defaultTopN;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private RestClient restClient;

    @Value(value="${dashscope.rerank.connect-timeout:10}")
    private int connectTimeout;

    @Value(value="${dashscope.rerank.read-timeout:30}")
    private int readTimeout;

    public RerankService(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        String maskedKey = this.apiKey != null && this.apiKey.length() > 8
            ? this.apiKey.substring(0, 8) + "..." + this.apiKey.substring(this.apiKey.length() - 4)
            : "***";
        if (this.apiKey == null || this.apiKey.trim().isEmpty() || this.apiKey.equals("your-api-key-here")) {
            logger.error("API Key 未正确配置！当前值: {}", maskedKey);
            throw new IllegalStateException("请设置环境变量 DASHSCOPE_API_KEY 或在 application.yml 中配置 spring.ai.dashscope.api-key");
        }
        logger.info("DashScope Rerank 服务初始化完成，模型: {}, 默认 TopN: {}, API Key: {}", this.model, this.defaultTopN, maskedKey);
        this.restClient = this.restClientBuilder
                .requestFactory(ClientHttpRequestFactories.get(
                    ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(this.connectTimeout))
                        .withReadTimeout(Duration.ofSeconds(this.readTimeout))))
                .build();
    }

    public List<RerankResult> rerank(String query, List<String> documents) {
        return this.rerank(query, documents, this.defaultTopN);
    }

    @CircuitBreaker(name="dashscope", fallbackMethod="rerankFallback")
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            logger.warn("\u6587\u6863\u5217\u8868\u4e3a\u7a7a\uff0c\u8df3\u8fc7\u91cd\u6392");
            return new ArrayList<RerankResult>();
        }
        if (query == null || query.trim().isEmpty()) {
            logger.warn("\u67e5\u8be2\u6587\u672c\u4e3a\u7a7a\uff0c\u8df3\u8fc7\u91cd\u6392\uff0c\u8fd4\u56de\u539f\u59cb\u987a\u5e8f");
            ArrayList<RerankResult> fallback = new ArrayList<RerankResult>();
            for (int i = 0; i < Math.min(documents.size(), topN); ++i) {
                fallback.add(new RerankResult(i, documents.get(i), 0.0));
            }
            return fallback;
        }
        try {
            logger.info("\u5f00\u59cb\u8c03\u7528 DashScope Rerank API, \u67e5\u8be2: {}, \u6587\u6863\u6570: {}, topN: {}, \u6a21\u578b: {}", new Object[]{query, documents.size(), topN, this.model});
            RerankRequest request = new RerankRequest();
            request.setModel(this.model);
            request.setInput(new RerankInput(query, documents));
            request.setParameters(new RerankParameters(topN, false));
            String requestBody = this.objectMapper.writeValueAsString((Object)request);
            logger.debug("Rerank \u8bf7\u6c42\u4f53: {}", (Object)requestBody);
            String responseBody = (String)this.restClient.post()
                .uri(RERANK_API_URL)
                .header("Authorization", "Bearer " + this.apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);
            logger.debug("Rerank \u54cd\u5e94\u4f53: {}", (Object)responseBody);
            RerankResponse response = (RerankResponse)this.objectMapper.readValue(responseBody, RerankResponse.class);
            if (response == null || response.getOutput() == null || response.getOutput().getResults() == null) {
                throw new RuntimeException("DashScope Rerank API \u8fd4\u56de\u7a7a\u7ed3\u679c");
            }
            ArrayList<RerankResult> results = new ArrayList<RerankResult>();
            for (RerankResponse.ResultItem item : response.getOutput().getResults()) {
                RerankResult result = new RerankResult();
                result.setIndex(item.getIndex());
                result.setRelevanceScore(item.getRelevanceScore());
                if (item.getIndex() >= 0 && item.getIndex() < documents.size()) {
                    result.setDocument(documents.get(item.getIndex()));
                }
                results.add(result);
            }
            results.sort(Comparator.comparingDouble(RerankResult::getRelevanceScore).reversed());
            logger.info("DashScope Rerank \u5b8c\u6210\uff0c\u8fd4\u56de {} \u4e2a\u7ed3\u679c\uff0cusage: {}", (Object)results.size(), response.getUsage() != null ? response.getUsage() : "N/A");
            return results;
        }
        catch (JsonProcessingException e) {
            logger.error("Rerank \u8bf7\u6c42/\u54cd\u5e94 JSON \u89e3\u6790\u5931\u8d25", (Throwable)e);
            throw new RuntimeException("Rerank JSON \u89e3\u6790\u5931\u8d25: " + e.getMessage(), e);
        }
        catch (Exception e) {
            logger.error("\u8c03\u7528 DashScope Rerank API \u5931\u8d25", (Throwable)e);
            throw new RuntimeException("Rerank \u8c03\u7528\u5931\u8d25: " + e.getMessage(), e);
        }
    }

    /**
     * P1修复: DashScope Rerank 熔断器 fallback — 返回原始顺序的前 topN 个文档
     */
    public List<RerankResult> rerankFallback(String query, List<String> documents, int topN, Throwable t) {
        logger.warn("[CIRCUIT-BREAKER] DashScope Rerank\u7194\u65ad\u5668\u5df2\u6253\u5f00\uff0c\u964d\u7ea7\u4e3a\u539f\u59cb\u987a\u5e8f\u3002\u67e5\u8be2: {}, \u539f\u56e0: {}", query, t.getMessage());
        ArrayList<RerankResult> fallback = new ArrayList<RerankResult>();
        for (int i = 0; i < Math.min(documents != null ? documents.size() : 0, topN); ++i) {
            RerankResult r = new RerankResult(i, documents.get(i), 0.0);
            r.setDegraded(true);
            fallback.add(r);
        }
        return fallback;
    }

    private static class RerankRequest {
        private String model;
        private RerankInput input;
        private RerankParameters parameters;

        public void setModel(String model) {
            this.model = model;
        }

        public void setInput(RerankInput input) {
            this.input = input;
        }

        public void setParameters(RerankParameters parameters) {
            this.parameters = parameters;
        }

        public String getModel() {
            return this.model;
        }

        public RerankInput getInput() {
            return this.input;
        }

        public RerankParameters getParameters() {
            return this.parameters;
        }
    }

    private static class RerankInput {
        private String query;
        private List<String> documents;

        public RerankInput() {
        }

        public RerankInput(String query, List<String> documents) {
            this.query = query;
            this.documents = documents;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public void setDocuments(List<String> documents) {
            this.documents = documents;
        }

        public String getQuery() {
            return this.query;
        }

        public List<String> getDocuments() {
            return this.documents;
        }
    }

    private static class RerankParameters {
        @JsonProperty(value="top_n")
        private int topN;
        @JsonProperty(value="return_documents")
        private boolean returnDocuments;

        public RerankParameters() {
        }

        public RerankParameters(int topN, boolean returnDocuments) {
            this.topN = topN;
            this.returnDocuments = returnDocuments;
        }

        @JsonProperty(value="top_n")
        public void setTopN(int topN) {
            this.topN = topN;
        }

        @JsonProperty(value="return_documents")
        public void setReturnDocuments(boolean returnDocuments) {
            this.returnDocuments = returnDocuments;
        }

        public int getTopN() {
            return this.topN;
        }

        public boolean isReturnDocuments() {
            return this.returnDocuments;
        }
    }

    private static class RerankResponse {
        private RerankOutput output;
        private Object usage;

        private RerankResponse() {
        }

        public void setOutput(RerankOutput output) {
            this.output = output;
        }

        public void setUsage(Object usage) {
            this.usage = usage;
        }

        public RerankOutput getOutput() {
            return this.output;
        }

        public Object getUsage() {
            return this.usage;
        }

        public static class RerankOutput {
            private List<ResultItem> results;

            public void setResults(List<ResultItem> results) {
                this.results = results;
            }

            public List<ResultItem> getResults() {
                return this.results;
            }
        }

        public static class ResultItem {
            private int index;
            @JsonProperty(value="relevance_score")
            private double relevanceScore;

            public void setIndex(int index) {
                this.index = index;
            }

            @JsonProperty(value="relevance_score")
            public void setRelevanceScore(double relevanceScore) {
                this.relevanceScore = relevanceScore;
            }

            public int getIndex() {
                return this.index;
            }

            public double getRelevanceScore() {
                return this.relevanceScore;
            }
        }
    }
}
