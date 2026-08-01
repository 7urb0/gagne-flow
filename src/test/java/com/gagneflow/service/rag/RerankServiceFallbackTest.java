package com.gagneflow.service.rag;

import com.gagneflow.dto.RerankResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RerankService 熔断降级与纯逻辑测试（仿 VectorSearchServiceFallbackTest 策略）。
 * 测试 rerankFallback 方法和相关纯逻辑，不依赖外部 API。
 */
@DisplayName("RerankService 降级测试")
class RerankServiceFallbackTest {

    @Nested
    @DisplayName("rerankFallback — 熔断降级")
    class RerankFallbackTests {

        @Test
        @DisplayName("正常文档列表 → 返回原始顺序的前 N 个")
        void fallback_normalDocs_returnsTopN() {
            RerankService svc = new RerankService(null);
            List<String> docs = List.of("文档A", "文档B", "文档C", "文档D");

            List<RerankResult> results = svc.rerankFallback(
                    "查询", docs, 3, new RuntimeException("API timeout"));

            assertEquals(3, results.size());
            assertEquals(0, results.get(0).getIndex());
            assertEquals("文档A", results.get(0).getDocument());
            assertEquals("文档B", results.get(1).getDocument());
            assertEquals("文档C", results.get(2).getDocument());
        }

        @Test
        @DisplayName("文档数少于 topN → 返回全部文档")
        void fallback_fewerDocs_returnsAll() {
            RerankService svc = new RerankService(null);
            List<String> docs = List.of("唯一文档");

            List<RerankResult> results = svc.rerankFallback(
                    "查询", docs, 5, new RuntimeException("error"));

            assertEquals(1, results.size());
            assertEquals("唯一文档", results.get(0).getDocument());
        }

        @Test
        @DisplayName("空文档列表 → 返回空列表")
        void fallback_emptyDocs_returnsEmpty() {
            RerankService svc = new RerankService(null);

            List<RerankResult> results = svc.rerankFallback(
                    "查询", List.of(), 3, new RuntimeException("error"));

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("null 文档列表 → 返回空列表")
        void fallback_nullDocs_returnsEmpty() {
            RerankService svc = new RerankService(null);

            List<RerankResult> results = svc.rerankFallback(
                    "查询", null, 3, new RuntimeException("error"));

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("fallback 不抛异常（防御性）")
        void fallback_shouldNotThrow() {
            RerankService svc = new RerankService(null);

            assertDoesNotThrow(() ->
                    svc.rerankFallback("查询", List.of("a", "b"), 100,
                            new RuntimeException("any error")));
        }
    }

    @Nested
    @DisplayName("RerankResult POJO")
    class RerankResultTests {

        @Test
        @DisplayName("RerankResult 字段设置和读取")
        void rerankResult_fields_work() {
            RerankResult r = new RerankResult();
            r.setIndex(5);
            r.setDocument("文档内容");
            r.setRelevanceScore(0.85);

            assertEquals(5, r.getIndex());
            assertEquals("文档内容", r.getDocument());
            assertEquals(0.85, r.getRelevanceScore(), 0.0001);
        }

        @Test
        @DisplayName("RerankResult 三参数构造器")
        void rerankResult_threeArgConstructor() {
            RerankResult r = new RerankResult(0, "测试文档", 0.75);

            assertEquals(0, r.getIndex());
            assertEquals("测试文档", r.getDocument());
            assertEquals(0.75, r.getRelevanceScore(), 0.0001);
        }
    }
}
