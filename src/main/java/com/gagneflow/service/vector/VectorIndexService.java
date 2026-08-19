package com.gagneflow.service.vector;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.gagneflow.dto.DocumentChunk;
import com.gagneflow.dto.DocumentParseResult;
import com.gagneflow.service.document.DocumentChunkService;
import com.gagneflow.service.reader.DocumentReader;
import com.gagneflow.service.reader.DocumentReaderFactory;
import com.gagneflow.service.vector.VectorSearchService.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VectorIndexService {
    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);
    @Autowired
    private MilvusServiceClient milvusClient;
    @Autowired
    private VectorEmbeddingService embeddingService;
    @Autowired
    private DocumentChunkService chunkService;
    @Autowired
    private DocumentReaderFactory readerFactory;
    @Autowired
    private VectorSearchService vectorSearchService;
    @Value(value="${file.upload.path}")
    private String uploadPath;

    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());
        try {
            String targetPath = directoryPath != null && !directoryPath.trim().isEmpty() ? directoryPath : this.uploadPath;
            Path dirPath = Paths.get(targetPath, new String[0]).normalize();
            File directory = dirPath.toFile();
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("\u76ee\u5f55\u4e0d\u5b58\u5728\u6216\u4e0d\u662f\u6709\u6548\u76ee\u5f55: " + targetPath);
            }
            result.setDirectoryPath(directory.getAbsolutePath());
            File[] files = directory.listFiles((dir, name) -> {
                String ext = this.getFileExtension(name);
                return this.readerFactory.isSupported(ext);
            });
            if (files == null || files.length == 0) {
                logger.warn("\u76ee\u5f55\u4e2d\u6ca1\u6709\u627e\u5230\u652f\u6301\u7684\u6587\u4ef6: {}", (Object)targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }
            result.setTotalFiles(files.length);
            logger.info("\u5f00\u59cb\u7d22\u5f15\u76ee\u5f55: {}, \u627e\u5230 {} \u4e2a\u6587\u4ef6", (Object)targetPath, (Object)files.length);
            for (File file : files) {
                try {
                    this.indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("\u2713 \u6587\u4ef6\u7d22\u5f15\u6210\u529f: {}", (Object)file.getName());
                }
                catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("\u2717 \u6587\u4ef6\u7d22\u5f15\u5931\u8d25: {}", (Object)file.getName(), (Object)e);
                }
            }
            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());
            logger.info("\u76ee\u5f55\u7d22\u5f15\u5b8c\u6210: \u603b\u6570={}, \u6210\u529f={}, \u5931\u8d25={}", new Object[]{result.getTotalFiles(), result.getSuccessCount(), result.getFailCount()});
            return result;
        }
        catch (Exception e) {
            logger.error("\u7d22\u5f15\u76ee\u5f55\u5931\u8d25", (Throwable)e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    public void indexSingleFile(String filePath) throws Exception {
        this.indexSingleFile(filePath, 0L);
    }

    public void indexSingleFile(String filePath, Long userId) throws Exception {
        String content;
        Path path = Paths.get(filePath, new String[0]).normalize();
        File file = path.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("\u6587\u4ef6\u4e0d\u5b58\u5728: " + filePath);
        }
        logger.info("\u5f00\u59cb\u7d22\u5f15\u6587\u4ef6: {}", (Object)path);
        String extension = this.getFileExtension(path.toString());
        DocumentReader reader = this.readerFactory.getReader(extension);
        if (reader == null) {
            throw new IllegalArgumentException("\u4e0d\u652f\u6301\u7684\u6587\u4ef6\u7c7b\u578b: ." + extension + " (\u6587\u4ef6: " + String.valueOf(path) + ")");
        }
        try {
            content = reader.readText(path);
        }
        catch (IOException e) {
            throw new IOException("\u6587\u4ef6\u8bfb\u53d6\u5931\u8d25: " + String.valueOf(path) + " - " + e.getMessage(), e);
        }
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("\u6587\u4ef6\u5185\u5bb9\u4e3a\u7a7a\u6216\u65e0\u6cd5\u63d0\u53d6\u6587\u672c: " + String.valueOf(path));
        }
        logger.info("\u8bfb\u53d6\u6587\u4ef6: {} (\u7c7b\u578b: .{}), \u5185\u5bb9\u957f\u5ea6: {} \u5b57\u7b26", new Object[]{path, extension, content.length()});
        DocumentParseResult docMeta = this.extractDocMetadata(content);
        List<DocumentChunk> chunks = this.chunkService.chunkDocument(content, path.toString());
        logger.info("\u6587\u6863\u5206\u7247\u5b8c\u6210: {} -> {} \u4e2a\u5206\u7247, {}", new Object[]{filePath, chunks.size(), docMeta});
        if (chunks.isEmpty()) {
            logger.warn("\u6587\u4ef6 {} \u5206\u7247\u540e\u4e3a\u7a7a\uff0c\u8df3\u8fc7\u7d22\u5f15", (Object)filePath);
            return;
        }
        ArrayList<String> ids = new ArrayList<String>(chunks.size());
        ArrayList<String> contents = new ArrayList<String>(chunks.size());
        ArrayList<List<Float>> vectors = new ArrayList<List<Float>>(chunks.size());
        ArrayList<JsonObject> metadataList = new ArrayList<JsonObject>(chunks.size());
        String normalizedPath = path.toString().replace(File.separator, "/");
        for (int i = 0; i < chunks.size(); ++i) {
            DocumentChunk chunk = chunks.get(i);
            try {
                List<Float> vector = this.embeddingService.generateEmbedding(chunk.getContent());
                Map<String, Object> metadata = this.buildMetadata(normalizedPath, chunk, chunks.size(), docMeta.toMetadataMap(), userId);
                String id = UUID.nameUUIDFromBytes((normalizedPath + "_" + chunk.getChunkIndex()).getBytes()).toString();
                ids.add(id);
                contents.add(chunk.getContent());
                vectors.add(vector);
                metadataList.add(this.convertToJsonObject(metadata));
                logger.debug("\u5206\u7247 {}/{} \u51c6\u5907\u5b8c\u6210", (Object)(i + 1), (Object)chunks.size());
                continue;
            }
            catch (Exception e) {
                logger.error("\u2717 \u5206\u7247 {}/{} \u51c6\u5907\u5931\u8d25", new Object[]{i + 1, chunks.size(), e});
                throw new RuntimeException("\u5206\u7247\u51c6\u5907\u5931\u8d25: " + e.getMessage(), e);
            }
        }
        // 修复BUG 1: 先删除旧数据，再插入新数据，避免新数据被同名 _source 条件误删
        // 2026-08-19 加固: 删除表达式显式带 _user_id, 不依赖路径巧合隔离, 防跨用户误删
        try {
            this.deleteExistingData(normalizedPath, userId);
            logger.info("\u2713 \u5df2\u6e05\u9664\u6587\u4ef6 {} \u7684\u65e7\u7d22\u5f15\u6570\u636e", (Object)normalizedPath);
        }
        catch (Exception e) {
            logger.warn("\u6e05\u9664\u65e7\u6570\u636e\u5931\u8d25\uff08\u53ef\u80fd\u9996\u6b21\u7d22\u5f15\uff09\uff0c\u7ee7\u7eed\u63d2\u5165: {}", (Object)e.getMessage());
        }
        try {
            logger.info("\u5f00\u59cb\u6279\u91cf\u63d2\u5165 {} \u4e2a\u5206\u7247\u5230 Milvus", (Object)chunks.size());
            long start = System.currentTimeMillis();
            this.loadCollectionIfNeeded();
            InsertParam insertParam = InsertParam.newBuilder().withCollectionName("biz").withFields(Arrays.asList(new InsertParam.Field("id", ids), new InsertParam.Field("content", contents), new InsertParam.Field("vector", vectors), new InsertParam.Field("metadata", metadataList))).build();
            R response = this.milvusClient.insert(insertParam);
            if (response.getStatus() != 0) {
                throw new RuntimeException("\u6279\u91cf\u63d2\u5165\u5931\u8d25: " + response.getMessage());
            }
            long elapsed = System.currentTimeMillis() - start;
            logger.info("\u2713 \u6279\u91cf\u63d2\u5165\u6210\u529f: {} \u4e2a\u5206\u7247, \u8017\u65f6 {} ms", (Object)chunks.size(), (Object)elapsed);
        }
        catch (Exception e) {
            logger.error("\u2717 \u6279\u91cf\u63d2\u5165\u5931\u8d25", (Throwable)e);
            throw new RuntimeException("\u6279\u91cf\u63d2\u5165\u5931\u8d25: " + e.getMessage(), e);
        }
        logger.info("\u6587\u4ef6\u7d22\u5f15\u5b8c\u6210: {}, \u5171 {} \u4e2a\u5206\u7247", (Object)filePath, (Object)chunks.size());
    }

    /**
     * 教案回灌: 将生成的教案内容向量化后写入 Milvus 知识库。
     * 三级过滤: HITL状态检查(由调用方负责) → 评分阈值 → 相似度去重。
     *
     * @param html    教案 HTML 内容
     * @param userId  用户 ID
     * @param subject 学科
     * @param score   Review 评分 (0-100)
     */
    public void indexLessonPlan(String html, Long userId, String subject, int score) {
        // 1. 评分阈值检查
        if (score < 70) {
            logger.info("[回灌跳过] 评分 {} < 70，质量不达标", score);
            return;
        }
        // 2. 提取纯文本
        String plainText = html.replaceAll("<[^>]+>", "")
                .replaceAll("&[a-z]+;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // 3. 规则硬校验（纯规则，不依赖 LLM 评分）: 结构完整性 + 字数下限
        String invalidReason = validateLessonPlanStructure(plainText);
        if (invalidReason != null) {
            logger.info("[回灌跳过] 规则校验未通过: {}", invalidReason);
            return;
        }
        // 3. 相似度检查: 仅对比当前用户已有教案，不对比原始文档（教案与教材内容相似是合理的）
        try {
            String probe = plainText.length() > 300 ? plainText.substring(0, 300) : plainText;
            // 2026-08-19: 个人库去重 - 仅本人教案参与去重(跨用户教案不互相去重)
            List<SearchResult> existing = this.vectorSearchService.searchSimilarLessonPlans(probe, 1, userId);
            if (!existing.isEmpty() && existing.get(0).getScore() > 0.98f) {
                String source = existing.get(0).getMetadata();
                boolean fromLessonPlan = source != null && source.contains("generated_lesson_plan");
                if (fromLessonPlan) {
                    logger.info("[回灌跳过] 已有高度相似教案 (score={})", existing.get(0).getScore());
                    return;
                }
                // 与原始文档相似但未达极端阈值：允许回灌（教案引用教材内容属正常）
                logger.debug("[回灌] 搜索结果来自原始文档(source={})，非教案重复，继续回灌", source);
            }
        } catch (Exception e) {
            logger.warn("[回灌] 相似度检查失败，继续回灌: {}", e.getMessage());
        }
        // 4. 分片
        String docId = "lesson_plan_" + userId + "_" + System.currentTimeMillis();
        List<DocumentChunk> chunks = this.chunkService.chunkDocument(plainText, docId);
        if (chunks.isEmpty()) {
            logger.info("[回灌跳过] 分片后为空");
            return;
        }
        // 5. 向量化
        ArrayList<String> ids = new ArrayList<>(chunks.size());
        ArrayList<String> contents = new ArrayList<>(chunks.size());
        ArrayList<List<Float>> vectors = new ArrayList<>(chunks.size());
        ArrayList<JsonObject> metadataList = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            try {
                List<Float> vector = this.embeddingService.generateEmbedding(chunk.getContent());
                Map<String, Object> metadata = buildLessonPlanMetadata(userId, subject, score,
                        chunk, chunks.size());
                String id = UUID.nameUUIDFromBytes(
                        (docId + "_" + chunk.getChunkIndex()).getBytes()).toString();
                ids.add(id);
                contents.add(chunk.getContent());
                vectors.add(vector);
                metadataList.add(this.convertToJsonObject(metadata));
            } catch (Exception e) {
                logger.error("[回灌] 分片 {}/{} 准备失败", i + 1, chunks.size(), e);
                throw new RuntimeException("分片准备失败: " + e.getMessage(), e);
            }
        }
        // 6. 写入 Milvus(2026-08-19: 反哺教案独立到 personal_plans 个人库)
        try {
            this.loadCollectionIfNeeded(com.gagneflow.constant.MilvusConstants.PERSONAL_PLANS_COLLECTION);
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(com.gagneflow.constant.MilvusConstants.PERSONAL_PLANS_COLLECTION)
                    .withFields(Arrays.asList(
                            new InsertParam.Field("id", ids),
                            new InsertParam.Field("content", contents),
                            new InsertParam.Field("vector", vectors),
                            new InsertParam.Field("metadata", metadataList)))
                    .build();
            R<MutationResult> response = this.milvusClient.insert(insertParam);
            if (response.getStatus() != 0) {
                throw new RuntimeException("批量插入失败: " + response.getMessage());
            }
            logger.info("[回灌完成] 教案已回灌到知识库: {} 分片, subject={}, score={}, uid={}",
                    chunks.size(), subject, score, userId);
        } catch (Exception e) {
            logger.error("[回灌] 批量插入失败", e);
            throw new RuntimeException("批量插入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 反哺教案结构规则校验 (纯规则，不依赖 LLM 评分)。
     * 参考 agent-config/prompts/v1/addrf/ 模板与 FormatTool 输出结构，
     * 教案完整 HTML 必然包含以下核心要素（经 HTML→纯文本后仍保留关键词）：
     *   - 教学目标:   Analysis 阶段 addrf_analysis 模板强制输出 **教学目标**
     *   - 教学重难点: Design 阶段 addrf_design 模板强制输出 教学重难点
     *   - 教学过程:   Development 阶段 addrf_development 模板输出 导入/探究新知/巩固练习 等环节
     *   - 教学评估:   Review 阶段 addrf_review 模板输出 质量评估报告
     * 要求: 纯文本 >= 500 字 且 至少命中 3 个核心要素（宁缺毋滥）。
     *
     * @return 校验失败原因；null 表示通过
     */
    static String validateLessonPlanStructure(String plainText) {
        if (plainText == null || plainText.trim().isEmpty()) {
            return "纯文本为空";
        }
        // 字数下限: 完整教案（分析+设计+过程+评估）应远超 500 字
        if (plainText.length() < 500) {
            return "纯文本过短 (" + plainText.length() + " 字 < 500 字)";
        }
        // 核心要素关键词（按 addrf 模板的强约束输出格式确定）
        String[] coreElements = {
            "教学目标", "教学重难点", "教学过程", "教学评估"
        };
        int hitCount = 0;
        for (String element : coreElements) {
            if (plainText.contains(element)) {
                hitCount++;
            }
        }
        if (hitCount < 3) {
            return "结构不完整: 核心要素命中 " + hitCount + "/4 (至少需 3 个)";
        }
        return null;
    }

    private Map<String, Object> buildLessonPlanMetadata(Long userId, String subject, int score,
                                                          DocumentChunk chunk, int totalChunks) {
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("_source", "generated_lesson_plan");
        metadata.put("_user_id", String.valueOf(userId != null ? userId : 0L));
        metadata.put("_subject", subject != null ? subject : "");
        // 反哺教案分级: 必须存数值类型，与检索端 metadata["_score"] >= 85 表达式保持一致
        // （原实现存 String.valueOf(score) 会导致 Milvus 数值比较类型不匹配，过滤失效）
        metadata.put("_score", score);
        metadata.put("_lesson_plan", "true");
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        return metadata;
    }

    private void loadCollectionIfNeeded() {
        loadCollectionIfNeeded("biz");
    }

    private void loadCollectionIfNeeded(String collectionName) {
        R loadResponse = this.milvusClient.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(collectionName).build());
        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            logger.warn("\u52a0\u8f7d collection \u65f6\u51fa\u73b0\u5f02\u5e38: {}", (Object)loadResponse.getMessage());
        }
    }

    private void deleteExistingData(String normalizedPath, Long userId) {
        try {
            // 显式带 _user_id 隔离: 删除只影响当前用户在该路径下的数据
            String uid = String.valueOf(userId != null ? userId : 0L);
            String expr = String.format("(metadata[\"_user_id\"] == \"%s\") && metadata[\"_source\"] == \"%s\"",
                    uid, normalizedPath);
            logger.debug("删除表达式: {}", (Object)expr);
            this.loadCollectionIfNeeded();
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(com.gagneflow.constant.MilvusConstants.MILVUS_COLLECTION_NAME).withExpr(expr).build();
            R response = this.milvusClient.delete(deleteParam);
            if (response.getStatus() != 0) {
                logger.warn("\u5220\u9664\u65e7\u6570\u636e\u65f6\u51fa\u73b0\u8b66\u544a: {}", (Object)response.getMessage());
            } else {
                long deletedCount = ((MutationResult)response.getData()).getDeleteCnt();
                logger.info("\u2713 \u5df2\u5220\u9664 {} \u6761\u65e7\u6570\u636e", (Object)deletedCount);
            }
        }
        catch (Exception e) {
            logger.warn("\u5220\u9664\u65e7\u6570\u636e\u5931\u8d25: {}", (Object)e.getMessage());
        }
    }

    private String getFileExtension(String filePath) {
        String fileName = Paths.get(filePath, new String[0]).getFileName().toString();
        int lastDot = fileName.lastIndexOf(46);
        if (lastDot == -1 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1).toLowerCase();
    }

    private DocumentParseResult extractDocMetadata(String content) {
        long pages;
        DocumentParseResult result = new DocumentParseResult(content);
        if (content == null) {
            return result;
        }
        int tables = 0;
        boolean inTable = false;
        for (String line : content.split("\n")) {
            if (line.trim().matches("^\\|.+\\|$")) {
                if (inTable) continue;
                ++tables;
                inTable = true;
                continue;
            }
            inTable = false;
        }
        if (tables > 0) {
            result.setTableCount(tables);
        }
        Matcher im = Pattern.compile("\u672c\u9875\u542b (\\d+) \u5f20\u56fe\u7247").matcher(content);
        int images = 0;
        while (im.find()) {
            images += Integer.parseInt(im.group(1));
        }
        if (images > 0) {
            result.setImageCount(images);
        }
        if ((pages = Pattern.compile("--- PAGE \\d+ ---").matcher(content).results().count()) > 0L) {
            result.setPageCount((int)(pages + 1L));
        }
        return result;
    }

    private Map<String, Object> buildMetadata(String normalizedPath, DocumentChunk chunk, int totalChunks, Map<String, Object> docMeta, Long userId) {
        HashMap<String, Object> metadata = new HashMap<String, Object>();
        // 修复遗漏-3: docMeta 先合入，确定性字段后覆盖，避免未来字段名冲突
        metadata.putAll(docMeta);
        metadata.put("_user_id", String.valueOf(userId != null ? userId : 0L));
        Path path = Paths.get(normalizedPath, new String[0]);
        String fileNameStr = path.getFileName().toString();
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf(46);
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }
        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        return metadata;
    }

    // Gson 是 Milvus Java SDK 的传递依赖，InsertParam.Field 的 metadata 参数接受 com.google.gson.JsonObject 类型
    private JsonObject convertToJsonObject(Map<String, Object> map) {
        Gson gson = new Gson();
        return gson.toJsonTree(map).getAsJsonObject();
    }

    public static class IndexingResult {
        private boolean success;
        private String directoryPath;
        private int totalFiles;
        private int successCount;
        private int failCount;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<String, String>();

        public void incrementSuccessCount() {
            ++this.successCount;
        }

        public void incrementFailCount() {
            ++this.failCount;
        }

        public long getDurationMs() {
            if (this.startTime != null && this.endTime != null) {
                return Duration.between(this.startTime, this.endTime).toMillis();
            }
            return 0L;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }

        public boolean isSuccess() {
            return this.success;
        }

        public String getDirectoryPath() {
            return this.directoryPath;
        }

        public int getTotalFiles() {
            return this.totalFiles;
        }

        public int getSuccessCount() {
            return this.successCount;
        }

        public int getFailCount() {
            return this.failCount;
        }

        public LocalDateTime getStartTime() {
            return this.startTime;
        }

        public LocalDateTime getEndTime() {
            return this.endTime;
        }

        public String getErrorMessage() {
            return this.errorMessage;
        }

        public Map<String, String> getFailedFiles() {
            return this.failedFiles;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public void setDirectoryPath(String directoryPath) {
            this.directoryPath = directoryPath;
        }

        public void setTotalFiles(int totalFiles) {
            this.totalFiles = totalFiles;
        }

        public void setStartTime(LocalDateTime startTime) {
            this.startTime = startTime;
        }

        public void setEndTime(LocalDateTime endTime) {
            this.endTime = endTime;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }
}
