package com.gagneflow.controller;

import com.gagneflow.config.FileUploadConfig;
import com.gagneflow.dto.ApiResponse;
import com.gagneflow.dto.FileUploadRes;
import com.gagneflow.service.vector.VectorIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FileUploadController 文件上传测试。
 * 覆盖: 空请求、扩展名校验、路径穿越防护、批量部分成功、全部失败、异步索引降级。
 */
@DisplayName("FileUploadController 文件上传测试")
class FileUploadControllerTest {

    private FileUploadController controller;
    private FileUploadConfig fileUploadConfig;
    private VectorIndexService vectorIndexService;
    private ThreadPoolExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        controller = new FileUploadController();
        fileUploadConfig = new FileUploadConfig();
        vectorIndexService = mock(VectorIndexService.class);
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());

        java.lang.reflect.Field f1 = FileUploadController.class.getDeclaredField("fileUploadConfig");
        f1.setAccessible(true);
        f1.set(controller, fileUploadConfig);

        java.lang.reflect.Field f2 = FileUploadController.class.getDeclaredField("vectorIndexService");
        f2.setAccessible(true);
        f2.set(controller, vectorIndexService);

        java.lang.reflect.Field f3 = FileUploadController.class.getDeclaredField("executor");
        f3.setAccessible(true);
        f3.set(controller, executor);

        // 使用临时目录
        Path tmpDir = Files.createTempDirectory("gagneflow-upload-test");
        fileUploadConfig.setPath(tmpDir.toString());
        fileUploadConfig.setAllowedExtensions("md,txt,pdf,docx");
    }

    private MultipartFile makeFile(String name, byte[] content) {
        return new MockMultipartFile("file", name, "text/plain", content);
    }

    @Nested
    @DisplayName("upload 参数校验")
    class UploadValidationTests {

        @Test
        @DisplayName("空文件列表 → 400")
        void emptyFiles_returns400() {
            ResponseEntity<?> res = controller.upload(null, 1L);
            assertEquals(400, res.getStatusCode().value());
            assertTrue(res.getBody().toString().contains("请选择"));
        }

        @Test
        @DisplayName("不支持扩展名 → 全部失败 code=500")
        void unsupportedExtension_allFail() {
            ResponseEntity<?> res = controller.upload(List.of(makeFile("virus.exe", "bad".getBytes())), 1L);

            ApiResponse<?> body = (ApiResponse<?>) res.getBody();
            assertEquals(500, body.getCode());
            assertTrue(body.getMessage().contains("所有文件上传失败"));
            FileUploadController.BatchUploadResult data =
                    (FileUploadController.BatchUploadResult) body.getData();
            assertEquals(0, data.getSuccessCount());
            assertEquals(1, data.getFailCount());
            assertTrue(data.getErrors().get(0).contains("不支持"));
        }

        @Test
        @DisplayName("空文件名 → 校验失败")
        void emptyFilename_fails() {
            MultipartFile f = new MockMultipartFile("file", "", "text/plain", "x".getBytes());
            ResponseEntity<?> res = controller.upload(List.of(f), 1L);

            FileUploadController.BatchUploadResult data =
                    (FileUploadController.BatchUploadResult) ((ApiResponse<?>) res.getBody()).getData();
            assertTrue(data.getErrors().get(0).contains("文件名不能为空"));
        }

        @Test
        @DisplayName("空文件内容 → 校验失败")
        void emptyContent_fails() {
            ResponseEntity<?> res = controller.upload(List.of(makeFile("empty.md", new byte[0])), 1L);

            FileUploadController.BatchUploadResult data =
                    (FileUploadController.BatchUploadResult) ((ApiResponse<?>) res.getBody()).getData();
            assertTrue(data.getErrors().get(0).contains("文件不能为空"));
        }
    }

    @Nested
    @DisplayName("upload 成功路径")
    class UploadSuccessTests {

        @Test
        @DisplayName("合法文件 → 上传成功 + 异步索引")
        void validFile_uploadedAndIndexed() {
            ResponseEntity<?> res = controller.upload(
                    List.of(makeFile("笔记.md", "内容".getBytes())), 1L);

            ApiResponse<?> body = (ApiResponse<?>) res.getBody();
            assertEquals(200, body.getCode());
            FileUploadController.BatchUploadResult data =
                    (FileUploadController.BatchUploadResult) body.getData();
            assertEquals(1, data.getSuccessCount());
            assertEquals(0, data.getFailCount());
            assertEquals(1, data.getTotalCount());
            assertEquals("indexing", data.getUploadedFiles().get(0).getIndexStatus());
        }

        @Test
        @DisplayName("混合合法+非法 → 部分成功 code=207")
        void mixedFiles_partialSuccess() {
            ResponseEntity<?> res = controller.upload(List.of(
                    makeFile("ok.md", "内容".getBytes()),
                    makeFile("bad.exe", "x".getBytes())), 1L);

            ApiResponse<?> body = (ApiResponse<?>) res.getBody();
            assertEquals(207, body.getCode());
            assertTrue(body.getMessage().contains("部分"));
            FileUploadController.BatchUploadResult data =
                    (FileUploadController.BatchUploadResult) body.getData();
            assertEquals(1, data.getSuccessCount());
            assertEquals(1, data.getFailCount());
        }

        @Test
        @DisplayName("路径穿越文件名 → 安全清洗而非越界写入")
        void pathTraversal_sanitized() {
            ResponseEntity<?> res = controller.upload(
                    List.of(makeFile("../../etc/passwd.md", "safe".getBytes())), 1L);

            FileUploadController.BatchUploadResult data =
                    (FileUploadController.BatchUploadResult) ((ApiResponse<?>) res.getBody()).getData();
            assertEquals(1, data.getSuccessCount(), "路径分隔符应被清洗而非拒绝");
            String saved = data.getUploadedFiles().get(0).getFilePath();
            // 真正的穿越模式是 .. 后紧跟路径分隔符；清洗后 ../ 变为 .._，不再构成越界
            assertFalse(saved.contains("..\\") || saved.contains("../"),
                    "保存路径不应含可解析的上级目录引用: " + saved);
            assertTrue(saved.contains("_"), "路径分隔符应替换为下划线");
        }

        @Test
        @DisplayName("null userId → 使用 0 目录")
        void nullUserId_usesZeroDir() {
            ResponseEntity<?> res = controller.upload(
                    List.of(makeFile("doc.txt", "内容".getBytes())), null);

            ApiResponse<?> body = (ApiResponse<?>) res.getBody();
            assertEquals(200, body.getCode());
        }
    }

    @Nested
    @DisplayName("upload 索引降级")
    class IndexFallbackTests {

        @Test
        @DisplayName("executor 为 null → 同步索引，失败记录 vectorError")
        void nullExecutor_syncIndexFailure_recordsError() throws Exception {
            java.lang.reflect.Field f = FileUploadController.class.getDeclaredField("executor");
            f.setAccessible(true);
            f.set(controller, null);

            doThrow(new RuntimeException("embedding API down"))
                    .when(vectorIndexService).indexSingleFile(anyString(), anyLong());

            ResponseEntity<?> res = controller.upload(
                    List.of(makeFile("failing.txt", "内容".getBytes())), 1L);

            ApiResponse<?> body = (ApiResponse<?>) res.getBody();
            assertEquals(200, body.getCode(), "上传本身成功，索引失败不应影响上传");
            FileUploadController.BatchUploadResult data =
                    (FileUploadController.BatchUploadResult) body.getData();
            FileUploadRes first = data.getUploadedFiles().get(0);
            assertNotNull(first.getVectorError());
            assertTrue(first.getVectorError().contains("索引建立失败"));
        }

        @Test
        @DisplayName("executor 为 null + 索引成功 → indexStatus=done")
        void nullExecutor_syncIndexSuccess_done() throws Exception {
            java.lang.reflect.Field f = FileUploadController.class.getDeclaredField("executor");
            f.setAccessible(true);
            f.set(controller, null);

            ResponseEntity<?> res = controller.upload(
                    List.of(makeFile("good.txt", "内容".getBytes())), 1L);

            FileUploadController.BatchUploadResult data =
                    (FileUploadController.BatchUploadResult) ((ApiResponse<?>) res.getBody()).getData();
            assertEquals("done", data.getUploadedFiles().get(0).getIndexStatus());
        }
    }
}
