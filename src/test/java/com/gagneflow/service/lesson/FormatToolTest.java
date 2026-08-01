package com.gagneflow.service.lesson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FormatToolTest {

    private FormatTool formatTool;

    @BeforeEach
    void setUp() {
        formatTool = new FormatTool();
    }

    @Test
    void format_shouldReturnValidHtml() {
        String result = formatTool.format("分析内容", "设计内容", "过程内容", "评估内容");

        assertNotNull(result);
        assertTrue(result.startsWith("<!DOCTYPE html>"), "应该以 HTML doctype 开头");
        assertTrue(result.contains("<h1>教案</h1>"), "应该包含标题");
        assertTrue(result.contains("</html>"), "应该包含闭合标签");
    }

    @Test
    void format_shouldContainAllSections() {
        String result = formatTool.format("教学分析A", "教学设计B", "教学过程C", "质量评估D");

        assertTrue(result.contains("教学分析"));
        assertTrue(result.contains("教学分析A"));
        assertTrue(result.contains("教学设计"));
        assertTrue(result.contains("教学设计B"));
        assertTrue(result.contains("教学过程"));
        assertTrue(result.contains("教学过程C"));
        assertTrue(result.contains("质量评估"));
        assertTrue(result.contains("质量评估D"));
    }

    @Test
    void format_shouldContainStylesheet() {
        String result = formatTool.format("a", "b", "c", "d");

        assertTrue(result.contains("@page{size:A4;"), "应该包含打印样式");
        assertTrue(result.contains("font-family"), "应该包含字体样式");
    }

    @Test
    void format_shouldHandleEmptyContent() {
        String result = formatTool.format("", "", "", "");

        assertNotNull(result);
        assertTrue(result.contains("教学分析"));
        assertTrue(result.contains("教学设计"));
        assertTrue(result.contains("教学过程"));
        assertTrue(result.contains("质量评估"));
    }

    @Test
    void format_shouldHandleNullContent_withPlaceholder() {
        String result = formatTool.format(null, null, null, null);

        assertNotNull(result);
        // toSection 方法将 null/blank 替换为占位符文本
        assertTrue(result.contains("此部分将在后台自动生成"));
        assertTrue(result.contains("教学分析"));
        assertTrue(result.contains("教学设计"));
        assertTrue(result.contains("教学过程"));
        assertTrue(result.contains("质量评估"));
    }
}
