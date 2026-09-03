package com.gagneflow.service.rag;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.gagneflow.service.metrics.PipelineMetrics;
import com.gagneflow.service.vector.VectorSearchService;
import io.reactivex.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagService.queryStream 主流程测试。
 * 覆盖: 查询改写、空结果降级回复、LLM 流式生成回调、异常 onError、
 *       用户隔离 userId 传递、指标埋点。
 */
@DisplayName("RagService queryStream 主流程测试")
class RagServiceQueryStreamTest {

    private RagService ragService;
    private VectorSearchService vectorSearchService;
    private QueryRewriter queryRewriter;
    private PipelineMetrics pipelineMetrics;
    private Generation generation;

    @BeforeEach
    void setUp() {
        ragService = new RagService();
        vectorSearchService = mock(VectorSearchService.class);
        queryRewriter = mock(QueryRewriter.class);
        pipelineMetrics = mock(PipelineMetrics.class);
        generation = mock(Generation.class);

        ReflectionTestUtils.setField(ragService, "vectorSearchService", vectorSearchService);
        ReflectionTestUtils.setField(ragService, "queryRewriter", queryRewriter);
        ReflectionTestUtils.setField(ragService, "pipelineMetrics", pipelineMetrics);
        ReflectionTestUtils.setField(ragService, "generation", generation);
        // 2026-08-31: rag.top-k 已删除(误导性配置), 最终送文档数由 rerankTopN 控制
        ReflectionTestUtils.setField(ragService, "model", "qwen-max-latest");
        ReflectionTestUtils.setField(ragService, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(ragService, "relevanceThreshold", 0.3);
        ReflectionTestUtils.setField(ragService, "searchTopK", 30);
        ReflectionTestUtils.setField(ragService, "rerankTopN", 3);

        when(queryRewriter.rewrite(anyString(), anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private VectorSearchService.SearchResult makeResult(String id, String content, float score) {
        VectorSearchService.SearchResult r = new VectorSearchService.SearchResult();
        r.setId(id);
        r.setContent(content);
        r.setScore(score);
        r.setMetadata("{\"_file_name\":\"三年级数学.pdf\"}");
        return r;
    }

    private static class RecordingCallback implements RagService.StreamCallback {
        final AtomicInteger searchResults = new AtomicInteger();
        final AtomicInteger contentChunks = new AtomicInteger();
        final AtomicInteger reasoningChunks = new AtomicInteger();
        final AtomicInteger completes = new AtomicInteger();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final AtomicReference<String> finalContent = new AtomicReference<>();
        List<VectorSearchService.SearchResult> results;

        @Override public void onSearchResults(List<VectorSearchService.SearchResult> r) { this.results = r; searchResults.incrementAndGet(); }
        @Override public void onReasoningChunk(String c) { reasoningChunks.incrementAndGet(); }
        @Override public void onContentChunk(String c) { contentChunks.incrementAndGet(); }
        @Override public void onComplete(String c, String r) { completes.incrementAndGet(); finalContent.set(c); }
        @Override public void onError(Exception e) { error.set(e); }
    }

    @Nested
    @DisplayName("查询改写")
    class QueryRewriteTests {

        @Test
        @DisplayName("改写器返回新查询 → 用改写后查询搜索")
        void rewrittenQuery_usedForSearch() {
            when(queryRewriter.rewrite(anyString(), anyList())).thenReturn("改写后的完整问题");
            when(vectorSearchService.searchWithRerank(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Collections.emptyList());

            ragService.queryStream("它是什么？", 7L, List.of(Map.of("role", "user", "content", "前文")),
                    new RecordingCallback());

            verify(vectorSearchService).searchWithRerank(eq("改写后的完整问题"), eq(30), eq(3), eq(7L));
        }

        @Test
        @DisplayName("改写器返回相同查询 → 正常搜索不重复改写日志")
        void sameQuery_noLog() {
            when(queryRewriter.rewrite(anyString(), anyList())).thenReturn("原始问题");
            when(vectorSearchService.searchWithRerank(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Collections.emptyList());

            ragService.queryStream("原始问题", new RecordingCallback());

            verify(vectorSearchService).searchWithRerank(eq("原始问题"), eq(30), eq(3), eq(0L));
        }
    }

    @Nested
    @DisplayName("空结果处理")
    class EmptyResultsTests {

        @Test
        @DisplayName("搜索无结果 → onComplete 兜底文案，不调 LLM")
        void emptyResults_completesWithFallback() throws Exception {
            when(vectorSearchService.searchWithRerank(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Collections.emptyList());

            RecordingCallback cb = new RecordingCallback();
            ragService.queryStream("查不到的问题", cb);

            assertEquals(1, cb.searchResults.get());
            assertTrue(cb.results.isEmpty());
            assertEquals(1, cb.completes.get());
            assertNull(cb.error.get());
            assertTrue(cb.finalContent.get().contains("没有找到"));
            verify(generation, never()).streamCall(any());
        }

        @Test
        @DisplayName("空结果也埋点 recordRagSearch")
        void emptyResults_recordsMetrics() {
            when(vectorSearchService.searchWithRerank(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Collections.emptyList());

            ragService.queryStream("问题", new RecordingCallback());

            verify(pipelineMetrics).recordRagSearch(anyString(), anyLong(), eq(0), eq(0), eq(0.0));
        }
    }

    @Nested
    @DisplayName("正常生成流")
    class GenerationStreamTests {

        @Test
        @DisplayName("有搜索结果 → onSearchResults + LLM 流式回调 + onComplete")
        void withResults_fullCallbackFlow() throws Exception {
            when(vectorSearchService.searchWithRerank(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(List.of(makeResult("1", "分数加减法内容", 0.85f)));
            doReturn(Flowable.empty()).when(generation).streamCall(any());

            RecordingCallback cb = new RecordingCallback();
            ragService.queryStream("分数的加法", cb);

            assertEquals(1, cb.searchResults.get());
            assertEquals(1, cb.completes.get());
            assertEquals("", cb.finalContent.get());
            assertNull(cb.error.get());
        }

        @Test
        @DisplayName("结果带低分 → 上下文过滤后仍触发生成")
        void lowScoreResults_stillGenerate() throws Exception {
            when(vectorSearchService.searchWithRerank(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(List.of(makeResult("1", "低相关内容", 0.1f)));
            doReturn(Flowable.empty()).when(generation).streamCall(any());

            RecordingCallback cb = new RecordingCallback();
            ragService.queryStream("问题", cb);

            assertEquals(1, cb.searchResults.get());
            assertEquals(1, cb.completes.get());
            verify(generation).streamCall(any());
        }

        @Test
        @DisplayName("LLM 流式调用异常 → onError 回调")
        void generationThrows_onError() throws Exception {
            when(vectorSearchService.searchWithRerank(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(List.of(makeResult("1", "内容", 0.8f)));
            doThrow(new RuntimeException("DashScope API error")).when(generation).streamCall(any());

            RecordingCallback cb = new RecordingCallback();
            assertDoesNotThrow(() -> ragService.queryStream("问题", cb));

            assertNotNull(cb.error.get());
            assertEquals(0, cb.completes.get());
        }
    }

    @Nested
    @DisplayName("异常处理")
    class ErrorHandlingTests {

        @Test
        @DisplayName("搜索抛异常 → onError 回调，不中断调用方")
        void searchThrows_onError() {
            when(vectorSearchService.searchWithRerank(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenThrow(new RuntimeException("Milvus connection refused"));

            RecordingCallback cb = new RecordingCallback();
            assertDoesNotThrow(() -> ragService.queryStream("问题", cb));

            assertNotNull(cb.error.get());
            assertEquals(0, cb.completes.get());
        }

        @Test
        @DisplayName("改写器抛异常 → onError 回调")
        void rewriteThrows_onError() {
            when(queryRewriter.rewrite(anyString(), anyList())).thenThrow(new RuntimeException("rewrite failed"));

            RecordingCallback cb = new RecordingCallback();
            assertDoesNotThrow(() -> ragService.queryStream("问题", cb));

            assertNotNull(cb.error.get());
        }
    }

    @Nested
    @DisplayName("用户隔离与指标")
    class UserIsolationTests {

        @Test
        @DisplayName("userId 透传到 searchWithRerank")
        void userId_passedThrough() {
            when(vectorSearchService.searchWithRerank(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Collections.emptyList());

            ragService.queryStream("问题", 99L, new ArrayList<>(), new RecordingCallback());

            verify(vectorSearchService).searchWithRerank(anyString(), eq(30), eq(3), eq(99L));
        }

        @Test
        @DisplayName("userId 为 null → 传 0")
        void nullUserId_passesZero() {
            when(vectorSearchService.searchWithRerank(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(Collections.emptyList());

            ragService.queryStream("问题", null, new ArrayList<>(), new RecordingCallback());

            verify(vectorSearchService).searchWithRerank(anyString(), eq(30), eq(3), eq(0L));
        }

        @Test
        @DisplayName("有结果时记录指标（平均分）")
        void withResults_recordsMetrics() throws Exception {
            when(vectorSearchService.searchWithRerank(anyString(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(List.of(makeResult("1", "内容A", 0.8f), makeResult("2", "内容B", 0.6f)));
            doReturn(Flowable.empty()).when(generation).streamCall(any());

            ragService.queryStream("问题", new RecordingCallback());

            // float 0.8f + 0.6f 均值存在浮点误差，用 doubleThat delta 匹配
            verify(pipelineMetrics).recordRagSearch(eq("问题"), anyLong(), eq(2), eq(2),
                    doubleThat(avg -> Math.abs(avg - 0.7) < 0.001));
        }
    }
}
