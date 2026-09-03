package com.gagneflow.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import com.gagneflow.config.FileUploadConfig;
import com.gagneflow.config.security.CurrentUser;
import com.gagneflow.dto.ApiResponse;
import com.gagneflow.dto.FileUploadRes;
import com.gagneflow.service.vector.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class FileUploadController {
    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);
    @Autowired
    private FileUploadConfig fileUploadConfig;
    @Autowired
    private VectorIndexService vectorIndexService;
    @Autowired(required=false)
    private ThreadPoolExecutor executor;

    @PostMapping(value={"/api/upload"}, consumes={"multipart/form-data"})
    public ResponseEntity<?> upload(@RequestParam(value="file") List<MultipartFile> files, @CurrentUser Long userId) {
        Long uid = userId != null ? userId : 0L;
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body((Object)"\u8bf7\u9009\u62e9\u81f3\u5c11\u4e00\u4e2a\u6587\u4ef6");
        }
        ArrayList<FileUploadRes> uploadedFiles = new ArrayList<FileUploadRes>();
        ArrayList<String> errors = new ArrayList<String>();
        for (MultipartFile file : files) {
            try {
                FileUploadRes result = this.processSingleFile(file, uid);
                if (result == null) continue;
                uploadedFiles.add(result);
            }
            catch (IllegalArgumentException e) {
                errors.add(file.getOriginalFilename() + ": " + e.getMessage());
                logger.warn("\u6587\u4ef6\u9a8c\u8bc1\u5931\u8d25: {}", (Object)e.getMessage());
            }
            catch (IOException e) {
                errors.add(file.getOriginalFilename() + ": \u4fdd\u5b58\u5931\u8d25 - " + e.getMessage());
                logger.error("\u6587\u4ef6\u4fdd\u5b58\u5931\u8d25: {}", (Object)file.getOriginalFilename(), (Object)e);
            }
            catch (Exception e) {
                errors.add(file.getOriginalFilename() + ": \u5904\u7406\u5931\u8d25 - " + e.getMessage());
                logger.error("\u6587\u4ef6\u5904\u7406\u5931\u8d25: {}", (Object)file.getOriginalFilename(), (Object)e);
            }
        }
        ApiResponse<BatchUploadResult> apiResponse = new ApiResponse<BatchUploadResult>();
        apiResponse.setCode(200);
        if (!errors.isEmpty() && uploadedFiles.isEmpty()) {
            apiResponse.setCode(500);
            apiResponse.setMessage("\u6240\u6709\u6587\u4ef6\u4e0a\u4f20\u5931\u8d25");
        } else if (!errors.isEmpty()) {
            apiResponse.setCode(207);
            apiResponse.setMessage("\u90e8\u5206\u6587\u4ef6\u4e0a\u4f20\u6210\u529f");
        } else {
            apiResponse.setMessage("success");
        }
        apiResponse.setData(new BatchUploadResult(uploadedFiles, errors));
        return ResponseEntity.ok(apiResponse);
    }

    private FileUploadRes processSingleFile(MultipartFile file, Long userId) throws IOException {
        Path filePath;
        if (file.isEmpty()) {
            throw new IllegalArgumentException("\u6587\u4ef6\u4e0d\u80fd\u4e3a\u7a7a");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new IllegalArgumentException("\u6587\u4ef6\u540d\u4e0d\u80fd\u4e3a\u7a7a");
        }
        String fileExtension = this.getFileExtension(originalFilename);
        if (!this.isAllowedExtension(fileExtension)) {
            throw new IllegalArgumentException("\u4e0d\u652f\u6301\u7684\u6587\u4ef6\u683c\u5f0f\uff0c\u4ec5\u652f\u6301: " + this.fileUploadConfig.getAllowedExtensions());
        }
        String uploadPath = this.fileUploadConfig.getPath();
        Path uploadDir = Paths.get(uploadPath, new String[0]).resolve(String.valueOf(userId)).normalize();
        if (!Files.exists(uploadDir, new LinkOption[0])) {
            Files.createDirectories(uploadDir, new FileAttribute[0]);
        }
        // 修复P0-4: 使用 toRealPath() 替代 normalize()，解析 NTFS junction/符号链接
        Path realUploadDir = uploadDir.toRealPath(LinkOption.NOFOLLOW_LINKS);
        // 文件名清洗: 去除路径分隔符、空字节、Unicode 控制字符
        String safeFilename = originalFilename
                .replaceAll("[/\\\\]", "_")
                .replaceAll("\\u0000", "")
                .replaceAll("[\\p{C}&&[^\\p{Print}]]", "");
        if (!(filePath = realUploadDir.resolve(safeFilename)).normalize().toAbsolutePath().normalize().startsWith(realUploadDir.toAbsolutePath().normalize())) {
            throw new SecurityException("\u975e\u6cd5\u6587\u4ef6\u8def\u5f84: " + originalFilename);
        }
        if (Files.exists(filePath, new LinkOption[0])) {
            logger.info("\u6587\u4ef6\u5df2\u5b58\u5728\uff0c\u5c06\u8986\u76d6: {}", (Object)filePath);
            Files.delete(filePath);
        }
        try (InputStream is = file.getInputStream();){
            Files.copy(is, filePath, new CopyOption[0]);
        }
        logger.info("\u6587\u4ef6\u4e0a\u4f20\u6210\u529f: {}", (Object)filePath);
        String finalPath = filePath.toString();
        Long finalUserId = userId;
        FileUploadRes res = new FileUploadRes(originalFilename, finalPath, file.getSize(), null);
        // P0修复: 初始状态为 indexing，告知前端正在建索引
        res.setIndexStatus("indexing");
        if (this.executor != null) {
            this.executor.execute(() -> {
                try {
                    logger.info("\u5f02\u6b65\u5f00\u59cb\u4e3a\u4e0a\u4f20\u6587\u4ef6\u521b\u5efa\u5411\u91cf\u7d22\u5f15: {} (userId={})", (Object)finalPath, (Object)finalUserId);
                    this.vectorIndexService.indexSingleFile(finalPath, finalUserId);
                    logger.info("\u5f02\u6b65\u5411\u91cf\u7d22\u5f15\u521b\u5efa\u6210\u529f: {}", (Object)finalPath);
                }
                catch (Exception e) {
                    // P0修复: 索引失败时记录详细错误到日志 (用户可通过 /api/upload/status 查询)
                    logger.error("[\u7d22\u5f15\u5efa\u7acb\u5931\u8d25] file={}, userId={}, error={}",
                        finalPath, finalUserId, e.getMessage(), e);
                }
            });
        } else {
            logger.warn("\u5171\u4eab\u7ebf\u7a0b\u6c60\u672a\u521d\u59cb\u5316\uff0c\u964d\u7ea7\u4e3a\u540c\u6b65\u7d22\u5f15: {}", (Object)finalPath);
            try {
                this.vectorIndexService.indexSingleFile(finalPath, finalUserId);
                res.setIndexStatus("done");
            }
            catch (Exception e) {
                logger.error("[\u7d22\u5f15\u5efa\u7acb\u5931\u8d25] file={}, userId={}, error={}",
                    finalPath, finalUserId, e.getMessage(), e);
                res.setVectorError("\u7d22\u5f15\u5efa\u7acb\u5931\u8d25: " + e.getMessage());
            }
        }
        return res;
    }

    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = this.fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        List<String> allowedList = Arrays.asList(allowedExtensions.split(","));
        return allowedList.contains(extension.toLowerCase());
    }

    public static class BatchUploadResult {
        private List<FileUploadRes> uploadedFiles;
        private List<String> errors;
        private int totalCount;
        private int successCount;
        private int failCount;

        public BatchUploadResult() {
        }

        public BatchUploadResult(List<FileUploadRes> uploadedFiles, List<String> errors) {
            this.uploadedFiles = uploadedFiles;
            this.errors = errors;
            this.totalCount = uploadedFiles.size() + errors.size();
            this.successCount = uploadedFiles.size();
            this.failCount = errors.size();
        }

        public void setUploadedFiles(List<FileUploadRes> uploadedFiles) {
            this.uploadedFiles = uploadedFiles;
        }

        public void setErrors(List<String> errors) {
            this.errors = errors;
        }

        public void setTotalCount(int totalCount) {
            this.totalCount = totalCount;
        }

        public void setSuccessCount(int successCount) {
            this.successCount = successCount;
        }

        public void setFailCount(int failCount) {
            this.failCount = failCount;
        }

        public List<FileUploadRes> getUploadedFiles() {
            return this.uploadedFiles;
        }

        public List<String> getErrors() {
            return this.errors;
        }

        public int getTotalCount() {
            return this.totalCount;
        }

        public int getSuccessCount() {
            return this.successCount;
        }

        public int getFailCount() {
            return this.failCount;
        }
    }
}
