package com.gagneflow.service.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PipelineMetrics 指标采集测试")
class PipelineMetricsTest {

    private PipelineMetrics metrics;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PipelineMetrics(registry);
    }

    @Nested
    @DisplayName("RAG 搜索指标")
    class RagMetricsTests {

        @Test
        @DisplayName("recordRagSearch 不抛异常")
        void recordRagSearch_shouldNotThrow() {
            assertDoesNotThrow(() ->
                    metrics.recordRagSearch("小学数学教案", 250L, 15, 3, 0.85));
        }

        @Test
        @DisplayName("recordCitations 正常记录引用数")
        void recordCitations_shouldNotThrow() {
            assertDoesNotThrow(() ->
                    metrics.recordCitations("答案[1]包含[2]引用", 2));
        }

        @Test
        @DisplayName("recordCitations 引用丢失 → 触发告警计数器")
        void recordCitations_zeroActual_shouldIncrementLossCounter() {
            // 预存计数器值
            double before = registry.get("gagneflow.rag.citation.loss")
                    .counter().count();

            metrics.recordCitations("无引用的答案文本", 3);

            double after = registry.get("gagneflow.rag.citation.loss")
                    .counter().count();
            assertEquals(before + 1, after, 0.01);
        }
    }

    @Nested
    @DisplayName("内存指标")
    class MemoryMetricsTests {

        @Test
        @DisplayName("recordSummaryCompression 不抛异常")
        void recordSummaryCompression_shouldNotThrow() {
            assertDoesNotThrow(() ->
                    metrics.recordSummaryCompression("sess_001", 500, 200, 0.4));
        }

        @Test
        @DisplayName("recordContextBuild 不抛异常")
        void recordContextBuild_shouldNotThrow() {
            assertDoesNotThrow(() ->
                    metrics.recordContextBuild("sess_001", 300, 150, 450));
        }
    }

    @Nested
    @DisplayName("ADDRF 流水线指标")
    class AddrfMetricsTests {

        @Test
        @DisplayName("recordAddrfStage 不抛异常")
        void recordAddrfStage_shouldNotThrow() {
            assertDoesNotThrow(() ->
                    metrics.recordAddrfStage("analysis", 15000L, 2048));
        }
    }
}
