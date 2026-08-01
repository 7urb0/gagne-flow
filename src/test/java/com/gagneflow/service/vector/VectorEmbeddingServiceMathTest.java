package com.gagneflow.service.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VectorEmbeddingService 向量数学测试")
class VectorEmbeddingServiceMathTest {

    @Nested
    @DisplayName("normalizeL2 — L2 归一化")
    class NormalizeL2Tests {

        @Test
        @DisplayName("单位向量保持不变")
        void unitVector_shouldRemainUnchanged() {
            List<Float> v = List.of(0.0f, 1.0f, 0.0f);
            List<Float> result = VectorEmbeddingService.normalizeL2(v);
            assertEquals(0.0f, result.get(0), 0.0001f);
            assertEquals(1.0f, result.get(1), 0.0001f);
            assertEquals(0.0f, result.get(2), 0.0001f);
        }

        @Test
        @DisplayName("一般向量归一化后 L2 范数接近 1")
        void generalVector_shouldHaveNearUnitNorm() {
            List<Float> v = List.of(3.0f, 4.0f, 0.0f); // L2=5
            List<Float> result = VectorEmbeddingService.normalizeL2(v);
            assertEquals(0.6f, result.get(0), 0.0001f);
            assertEquals(0.8f, result.get(1), 0.0001f);

            float norm = 0.0f;
            for (float f : result) norm += f * f;
            assertEquals(1.0f, Math.sqrt(norm), 0.0001f);
        }

        @Test
        @DisplayName("零向量归一化不抛异常，返回原向量")
        void zeroVector_shouldNotThrow() {
            List<Float> v = List.of(0.0f, 0.0f, 0.0f);
            List<Float> result = VectorEmbeddingService.normalizeL2(v);
            assertEquals(v, result);
        }

        @Test
        @DisplayName("负值向量也能正确归一化")
        void negativeValues_shouldNormalizeCorrectly() {
            List<Float> v = List.of(-1.0f, 2.0f, -3.0f);
            List<Float> result = VectorEmbeddingService.normalizeL2(v);
            float norm = 0.0f;
            for (float f : result) norm += f * f;
            assertEquals(1.0f, Math.sqrt(norm), 0.0001f);
        }
    }

    @Nested
    @DisplayName("calculateCosineSimilarity — 余弦相似度")
    class CosineSimilarityTests {

        @Test
        @DisplayName("相同向量相似度为 1")
        void identicalVectors_shouldReturnOne() {
            VectorEmbeddingService svc = new VectorEmbeddingService();
            List<Float> v = List.of(1.0f, 2.0f, 3.0f);
            assertEquals(1.0f, svc.calculateCosineSimilarity(v, v), 0.0001f);
        }

        @Test
        @DisplayName("正交向量相似度为 0")
        void orthogonalVectors_shouldReturnZero() {
            VectorEmbeddingService svc = new VectorEmbeddingService();
            List<Float> a = List.of(1.0f, 0.0f, 0.0f);
            List<Float> b = List.of(0.0f, 1.0f, 0.0f);
            assertEquals(0.0f, svc.calculateCosineSimilarity(a, b), 0.0001f);
        }

        @Test
        @DisplayName("相反方向向量相似度为 -1")
        void oppositeVectors_shouldReturnNegativeOne() {
            VectorEmbeddingService svc = new VectorEmbeddingService();
            List<Float> a = List.of(1.0f, 2.0f, 3.0f);
            List<Float> b = List.of(-1.0f, -2.0f, -3.0f);
            assertEquals(-1.0f, svc.calculateCosineSimilarity(a, b), 0.0001f);
        }

        @Test
        @DisplayName("维度不匹配抛异常")
        void dimensionMismatch_shouldThrow() {
            VectorEmbeddingService svc = new VectorEmbeddingService();
            List<Float> a = List.of(1.0f, 2.0f, 3.0f);
            List<Float> b = List.of(1.0f, 2.0f);
            assertThrows(IllegalArgumentException.class,
                    () -> svc.calculateCosineSimilarity(a, b));
        }
    }

    @Nested
    @DisplayName("generateEmbeddingFallback — 熔断降级")
    class FallbackTests {

        @Test
        @DisplayName("熔断 fallback 返回 1024 维零向量")
        void fallback_shouldReturnZeroVector() {
            VectorEmbeddingService svc = new VectorEmbeddingService();
            List<Float> result = svc.generateEmbeddingFallback(
                    "test content",
                    new RuntimeException("DashScope timeout"));

            assertNotNull(result);
            assertTrue(result.isEmpty(), "熔断降级应返回空列表而非零向量");
        }

        @Test
        @DisplayName("熔断 fallback null 内容也能处理")
        void fallback_nullContent_shouldStillReturnZeroVector() {
            VectorEmbeddingService svc = new VectorEmbeddingService();
            List<Float> result = svc.generateEmbeddingFallback(
                    null, new RuntimeException("Connection refused"));

            assertNotNull(result);
            assertTrue(result.isEmpty(), "熔断降级 null 内容也应返回空列表");
        }
    }
}
