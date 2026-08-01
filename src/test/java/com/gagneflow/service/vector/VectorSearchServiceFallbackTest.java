package com.gagneflow.service.vector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VectorSearchService 降级与熔断测试")
class VectorSearchServiceFallbackTest {

    private VectorSearchService vectorSearchService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        vectorSearchService = new VectorSearchService();
        // 不需要注入 mock 依赖 — 只测 fallback 方法
    }

    @Nested
    @DisplayName("searchSimilarDocuments fallback")
    class SearchFallbackTests {

        @Test
        @DisplayName("Milvus 熔断 fallback 返回空列表而非 null")
        void fallback_shouldReturnEmptyListNotNull() {
            List<VectorSearchService.SearchResult> results =
                    vectorSearchService.searchSimilarDocumentsFallback(
                            "小学数学", 3,
                            new RuntimeException("Milvus connection timeout"));

            assertNotNull(results);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("fallback 不抛异常")
        void fallback_shouldNotThrow() {
            assertDoesNotThrow(() ->
                    vectorSearchService.searchSimilarDocumentsFallback(
                            "查询", 5, new RuntimeException("any error")));
        }
    }

    @Nested
    @DisplayName("isRerankAvailable")
    class RerankAvailableTests {

        @Test
        @DisplayName("未注入 RerankService 时返回 false")
        void noRerankService_shouldReturnFalse() {
            assertFalse(vectorSearchService.isRerankAvailable());
        }
    }

    @Nested
    @DisplayName("SearchResult POJO")
    class SearchResultTests {

        @Test
        @DisplayName("SearchResult 字段设置正确")
        void searchResult_fields_shouldWork() {
            VectorSearchService.SearchResult sr = new VectorSearchService.SearchResult();
            sr.setId("doc_001");
            sr.setContent("测试内容");
            sr.setScore(0.85f);
            sr.setMetadata("{\"source\":\"test\"}");

            assertEquals("doc_001", sr.getId());
            assertEquals("测试内容", sr.getContent());
            assertEquals(0.85f, sr.getScore(), 0.0001f);
            assertEquals("{\"source\":\"test\"}", sr.getMetadata());
        }
    }
}
