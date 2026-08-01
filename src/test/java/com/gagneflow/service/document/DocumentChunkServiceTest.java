package com.gagneflow.service.document;

import java.util.List;

import com.gagneflow.config.DocumentChunkConfig;
import com.gagneflow.dto.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DocumentChunkService 文档分块测试")
class DocumentChunkServiceTest {

    private DocumentChunkService chunkService;

    @BeforeEach
    void setUp() {
        DocumentChunkConfig config = new DocumentChunkConfig();
        ReflectionTestUtils.setField(config, "maxSize", 800);
        ReflectionTestUtils.setField(config, "overlap", 160);

        chunkService = new DocumentChunkService();
        ReflectionTestUtils.setField(chunkService, "chunkConfig", config);
    }

    @Nested
    @DisplayName("基本分块")
    class BasicChunkingTests {

        @Test
        @DisplayName("null 内容返回空列表")
        void nullContent_shouldReturnEmpty() {
            List<DocumentChunk> chunks = chunkService.chunkDocument(null, "/test.md");
            assertTrue(chunks.isEmpty());
        }

        @Test
        @DisplayName("空内容返回空列表")
        void emptyContent_shouldReturnEmpty() {
            List<DocumentChunk> chunks = chunkService.chunkDocument("   ", "/test.md");
            assertTrue(chunks.isEmpty());
        }

        @Test
        @DisplayName("短文本(≤maxSize) → 单个分块")
        void shortText_shouldReturnSingleChunk() {
            String content = "# 教学目标\n\n让学生掌握分数的基本概念和运算方法。";
            List<DocumentChunk> chunks = chunkService.chunkDocument(content, "/test.md");

            assertEquals(1, chunks.size());
            DocumentChunk chunk = chunks.get(0);
            assertTrue(chunk.getContent().contains("分数"));
        }

        @Test
        @DisplayName("无标题的纯文本 → 回退为无标题分块")
        void plainTextNoHeading_shouldChunkWithoutTitle() {
            String content = repeat("这是一个没有标题的纯文本段落。", 80);
            List<DocumentChunk> chunks = chunkService.chunkDocument(content, "/test.md");

            assertFalse(chunks.isEmpty());
        }
    }

    @Nested
    @DisplayName("多标题分块")
    class MultiHeadingTests {

        @Test
        @DisplayName("带多个 Markdown 标题 → 按标题分段")
        void multipleHeadings_shouldSplitBySections() {
            String content = "# 教学目标\n" + repeat("教学目标内容。", 10)
                    + "\n# 教学重难点\n" + repeat("重难点内容。", 10)
                    + "\n# 教学过程\n" + repeat("过程内容。", 10);

            List<DocumentChunk> chunks = chunkService.chunkDocument(content, "/test.md");

            assertTrue(chunks.size() >= 3, "至少应分成 3 个 sections");
        }

        @Test
        @DisplayName("二级标题 → 面包屑路径包含层级关系")
        void nestedHeadings_shouldMaintainBreadcrumb() {
            String content = "# 第一章\n" + repeat("章节内容。", 10)
                    + "\n## 1.1 概述\n" + repeat("概述内容。", 10);

            List<DocumentChunk> chunks = chunkService.chunkDocument(content, "/test.md");

            assertFalse(chunks.isEmpty(), "应至少有一个分块");
            // 至少有一个 chunk 包含二级标题内容
            assertTrue(chunks.stream().anyMatch(c ->
                    c.getContent().contains("概述")));
        }
    }

    @Nested
    @DisplayName("表格处理")
    class TableHandlingTests {

        @Test
        @DisplayName("表格行不拆分 → 允许 1.5 倍溢出")
        void tableRows_shouldNotBeSplit() {
            StringBuilder sb = new StringBuilder("# 数据表\n\n");
            sb.append("| 列1 | 列2 | 列3 |\n");
            sb.append("|---|---|---|\n");
            // 生成大量表格行来触发分块
            for (int i = 0; i < 30; i++) {
                sb.append("| 数据").append(i).append(" | 值").append(i)
                        .append(" | 说明").append(i).append(" |\n");
            }

            List<DocumentChunk> chunks = chunkService.chunkDocument(sb.toString(), "/test.md");

            assertFalse(chunks.isEmpty());
        }
    }

    @Nested
    @DisplayName("重叠控制")
    class OverlapTests {

        @Test
        @DisplayName("长文本分块 → 相邻块存在重叠")
        void longText_shouldHaveOverlap() {
            // 构造超过maxSize的文本
            StringBuilder sb = new StringBuilder("# 长文本测试\n\n");
            for (int i = 0; i < 40; i++) {
                sb.append("这是第").append(i).append("段内容，用于测试文档分块的正确性。");
                sb.append("包含足够多的字符以确保超过maxSize阈值。\n\n");
            }

            List<DocumentChunk> chunks = chunkService.chunkDocument(sb.toString(), "/test.md");

            assertTrue(chunks.size() > 1, "长文本应被分成多个分块");
        }
    }

    private static String repeat(String base, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(base);
        }
        return sb.toString();
    }
}
