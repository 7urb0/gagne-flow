package com.gagneflow.service.vector;

import com.gagneflow.dto.DocumentChunk;
import com.gagneflow.service.document.DocumentChunkService;
import com.gagneflow.service.reader.DocumentReader;
import com.gagneflow.service.reader.DocumentReaderFactory;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * VectorIndexService 文档索引与教案回灌测试。
 * 覆盖: indexDirectory（目录校验/空目录/混合结果）、indexSingleFile（不支持类型/空内容）、
 *       indexLessonPlan（评分门槛/结构校验/去重/成功回灌）。
 */
@DisplayName("VectorIndexService 索引服务测试")
class VectorIndexServiceTest {

    private VectorIndexService indexService;
    private MilvusServiceClient milvusClient;
    private VectorEmbeddingService embeddingService;
    private DocumentChunkService chunkService;
    private DocumentReaderFactory readerFactory;
    private VectorSearchService vectorSearchService;

    @BeforeEach
    void setUp() {
        indexService = new VectorIndexService();
        milvusClient = mock(MilvusServiceClient.class);
        embeddingService = mock(VectorEmbeddingService.class);
        chunkService = mock(DocumentChunkService.class);
        readerFactory = mock(DocumentReaderFactory.class);
        vectorSearchService = mock(VectorSearchService.class);

        ReflectionTestUtils.setField(indexService, "milvusClient", milvusClient);
        ReflectionTestUtils.setField(indexService, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(indexService, "chunkService", chunkService);
        ReflectionTestUtils.setField(indexService, "readerFactory", readerFactory);
        ReflectionTestUtils.setField(indexService, "vectorSearchService", vectorSearchService);
        ReflectionTestUtils.setField(indexService, "uploadPath", "./uploads");

        // 默认 mock: Milvus 响应成功（先建 R mock 再 stub，避免嵌套 stub 触发 UnfinishedStubbing）
        R<RpcStatus> loadResp = mock(R.class);
        when(loadResp.getStatus()).thenReturn(0);
        when(loadResp.getMessage()).thenReturn("ok");
        when(milvusClient.loadCollection(any(LoadCollectionParam.class))).thenReturn(loadResp);

        R<MutationResult> insertResp = mock(R.class);
        when(insertResp.getStatus()).thenReturn(0);
        when(insertResp.getMessage()).thenReturn("ok");
        when(insertResp.getData()).thenReturn(mock(MutationResult.class));
        when(milvusClient.insert(any(InsertParam.class))).thenReturn(insertResp);

        R<MutationResult> deleteResp = mock(R.class);
        when(deleteResp.getStatus()).thenReturn(0);
        when(deleteResp.getMessage()).thenReturn("ok");
        when(deleteResp.getData()).thenReturn(mock(MutationResult.class));
        when(milvusClient.delete(any(DeleteParam.class))).thenReturn(deleteResp);
    }

    @SuppressWarnings("unchecked")
    private R<RpcStatus> mockRpcStatus(int status, String message) {
        R<RpcStatus> r = mock(R.class);
        when(r.getStatus()).thenReturn(status);
        when(r.getMessage()).thenReturn(message);
        return r;
    }

    @SuppressWarnings("unchecked")
    private R<MutationResult> mockR(int status, String message) {
        R<MutationResult> r = mock(R.class);
        when(r.getStatus()).thenReturn(status);
        when(r.getMessage()).thenReturn(message);
        MutationResult data = mock(MutationResult.class);
        when(r.getData()).thenReturn(data);
        return r;
    }

    private DocumentChunk makeChunk(int index, String content) {
        DocumentChunk c = new DocumentChunk();
        c.setChunkIndex(index);
        c.setContent(content);
        c.setTitle("章节" + index);
        return c;
    }

    @Nested
    @DisplayName("indexDirectory 目录索引")
    class IndexDirectoryTests {

        @Test
        @DisplayName("目录不存在 → 失败结果")
        void nonexistentDir_returnsFailure() {
            VectorIndexService.IndexingResult result =
                    indexService.indexDirectory("/nonexistent/path/xyz");

            assertFalse(result.isSuccess());
            assertNotNull(result.getErrorMessage());
            assertTrue(result.getErrorMessage().contains("不存在"));
        }

        @Test
        @DisplayName("null 目录 → 使用默认 uploadPath，不存在则失败")
        void nullDir_fallsBackToUploadPath() {
            VectorIndexService.IndexingResult result = indexService.indexDirectory(null);

            // ./uploads 在测试环境可能不存在；两种情况都不抛异常且结果对象完整
            assertNotNull(result);
            assertNotNull(result.getStartTime());
            assertNotNull(result.getEndTime());
        }

        @Test
        @DisplayName("空目录（无支持文件）→ 成功且 0 文件")
        void emptyDir_returnsSuccessZeroFiles() throws IOException {
            Path tmp = Files.createTempDirectory("gagneflow-empty-dir");
            try {
                when(readerFactory.isSupported(anyString())).thenReturn(true);
                VectorIndexService.IndexingResult result = indexService.indexDirectory(tmp.toString());

                assertTrue(result.isSuccess());
                assertEquals(0, result.getTotalFiles());
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    @Nested
    @DisplayName("indexSingleFile 单文件索引")
    class IndexSingleFileTests {

        @Test
        @DisplayName("文件不存在 → IllegalArgumentException")
        void nonexistentFile_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> indexService.indexSingleFile("/nonexistent/file.md"));
        }

        @Test
        @DisplayName("不支持的文件类型 → IllegalArgumentException")
        void unsupportedExtension_throws() throws IOException {
            Path tmp = Files.createTempFile("doc", ".xyz");
            try {
                when(readerFactory.getReader("xyz")).thenReturn(null);
                assertThrows(IllegalArgumentException.class,
                        () -> indexService.indexSingleFile(tmp.toString()));
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Test
        @DisplayName("文件内容为空 → IllegalArgumentException")
        void emptyContent_throws() throws IOException {
            Path tmp = Files.createTempFile("doc", ".txt");
            try {
                when(readerFactory.getReader("txt")).thenReturn(mock(DocumentReader.class));
                DocumentReader reader = mock(DocumentReader.class);
                when(reader.readText(any(Path.class))).thenReturn("   \n  ");
                when(readerFactory.getReader("txt")).thenReturn(reader);

                assertThrows(IllegalArgumentException.class,
                        () -> indexService.indexSingleFile(tmp.toString()));
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    @Nested
    @DisplayName("indexLessonPlan 教案回灌")
    class IndexLessonPlanTests {

        private String buildValidHtml() {
            StringBuilder sb = new StringBuilder("<html><body>");
            sb.append("教学目标: 学生掌握分数加减法。\n");
            sb.append("教学重难点: 通分与化简。\n");
            sb.append("教学过程: 导入-探究-练习-小结。\n");
            sb.append("教学评估: 课堂练习与课后作业。\n");
            while (sb.length() < 550) {
                sb.append("教师通过情境导入引导学生观察生活中的分数现象，"
                        + "借助数轴与图形直观理解分数加减法的算理，"
                        + "组织小组合作完成巩固练习并交流反馈。");
            }
            sb.append("</body></html>");
            return sb.toString();
        }

        @Test
        @DisplayName("评分 < 70 → 跳过回灌，不调用 Milvus")
        void scoreBelow70_skips() {
            indexService.indexLessonPlan(buildValidHtml(), 1L, "数学", 60);

            verify(milvusClient, never()).insert(any(InsertParam.class));
        }

        @Test
        @DisplayName("HTML 过短（<500 字）→ 规则校验拦截")
        void shortHtml_structureFails() {
            String shortHtml = "<html><body>教学目标: 掌握加法。教学重难点: 进位。教学过程: 讲授。教学评估: 小测。</body></html>";
            indexService.indexLessonPlan(shortHtml, 1L, "数学", 95);

            verify(milvusClient, never()).insert(any(InsertParam.class));
        }

        @Test
        @DisplayName("评分达标 + 结构完整 + 无重复 → 正常回灌")
        void validPlan_getsIndexed() {
            String html = buildValidHtml();
            when(chunkService.chunkDocument(anyString(), anyString()))
                    .thenReturn(List.of(makeChunk(0, "教学目标: 学生掌握分数加减法。"),
                            makeChunk(1, "教学过程: 导入-探究-练习-小结。")));
            when(embeddingService.generateEmbedding(anyString()))
                    .thenReturn(List.of(0.1f, 0.2f, 0.3f));
            when(vectorSearchService.searchSimilarDocuments(anyString(), anyInt()))
                    .thenReturn(List.of());

            indexService.indexLessonPlan(html, 1L, "数学", 95);

            verify(milvusClient).insert(any(InsertParam.class));
        }

        @Test
        @DisplayName("与已有教案高度相似（score>0.98）→ 去重跳过")
        void duplicatePlan_skips() {
            String html = buildValidHtml();
            VectorSearchService.SearchResult dup = new VectorSearchService.SearchResult();
            dup.setScore(0.99f);
            dup.setMetadata("{\"_source\":\"generated_lesson_plan\"}");
            when(vectorSearchService.searchSimilarDocuments(anyString(), anyInt()))
                    .thenReturn(List.of(dup));

            indexService.indexLessonPlan(html, 1L, "数学", 95);

            verify(milvusClient, never()).insert(any(InsertParam.class));
        }

        @Test
        @DisplayName("与原始文档相似但非教案（source 非 generated）→ 继续回灌")
        void similarToOriginalDoc_continues() {
            String html = buildValidHtml();
            VectorSearchService.SearchResult src = new VectorSearchService.SearchResult();
            src.setScore(0.99f);
            src.setMetadata("{\"_source\":\"/uploads/k12_curriculum.json\"}");
            when(vectorSearchService.searchSimilarDocuments(anyString(), anyInt()))
                    .thenReturn(List.of(src));
            when(chunkService.chunkDocument(anyString(), anyString()))
                    .thenReturn(List.of(makeChunk(0, "教学目标内容")));
            when(embeddingService.generateEmbedding(anyString()))
                    .thenReturn(List.of(0.1f, 0.2f, 0.3f));

            indexService.indexLessonPlan(html, 1L, "数学", 95);

            verify(milvusClient).insert(any(InsertParam.class));
        }

        @Test
        @DisplayName("相似度检查异常 → 继续回灌（warn 不阻断）")
        void similarityCheckThrows_continues() {
            String html = buildValidHtml();
            when(vectorSearchService.searchSimilarDocuments(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("Milvus down"));
            when(chunkService.chunkDocument(anyString(), anyString()))
                    .thenReturn(List.of(makeChunk(0, "教学目标内容")));
            when(embeddingService.generateEmbedding(anyString()))
                    .thenReturn(List.of(0.1f, 0.2f, 0.3f));

            assertDoesNotThrow(() -> indexService.indexLessonPlan(html, 1L, "数学", 95));
            verify(milvusClient).insert(any(InsertParam.class));
        }
    }
}
