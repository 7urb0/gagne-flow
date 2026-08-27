package com.gagneflow.service.lesson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MarkdownTableRepair 单元测试 — 覆盖 LLM 表格漂移的各类畸形输入。
 */
class MarkdownTableRepairTest {

    @Test
    @DisplayName("缺失结尾管道符: 补全")
    void missingTrailingPipe() {
        String md = "| 课时 | 内容\n|------|------|\n| 1 | 活动一";
        String fixed = MarkdownTableRepair.repair(md);
        assertTrue(fixed.contains("| 课时 | 内容 |"), "表头应补结尾管道符: " + fixed);
        assertTrue(fixed.contains("| 1 | 活动一 |"), "数据行应补结尾管道符: " + fixed);
    }

    @Test
    @DisplayName("缺失首管道符: 补全")
    void missingLeadingPipe() {
        String md = "课时 | 内容 |\n------|------|\n1 | 活动一 |";
        String fixed = MarkdownTableRepair.repair(md);
        assertTrue(fixed.startsWith("| 课时 | 内容 |"), "表头应补首管道符: " + fixed);
    }

    @Test
    @DisplayName("分隔行列数与表头不一致: 按表头列数重写")
    void mismatchedSeparatorCols() {
        String md = "| 课时 | 教学内容 | 时长 |\n|------|------|\n| 1 | 导入 | 40min |";
        String fixed = MarkdownTableRepair.repair(md);
        String sepLine = fixed.split("\n")[1];
        assertEquals("|---|---|---|", sepLine, "分隔行应为 3 列: " + sepLine);
    }

    @Test
    @DisplayName("数据行列数多于表头: 多余列并入最后一列")
    void extraColsMerged() {
        String md = "| 课时 | 内容 |\n|---|---|\n| 1 | 导入 | 40min |";
        String fixed = MarkdownTableRepair.repair(md);
        assertTrue(fixed.contains("| 1 | 导入 40min |"), "多余列应并入最后一列: " + fixed);
    }

    @Test
    @DisplayName("数据行缺列: 补空单元格")
    void missingColsPadded() {
        String md = "| 课时 | 内容 | 时长 |\n|---|---|---|\n| 1 | 导入 |";
        String fixed = MarkdownTableRepair.repair(md);
        assertTrue(fixed.contains("| 1 | 导入 |  |"), "缺列应补空: " + fixed);
    }

    @Test
    @DisplayName("缺失分隔行: 按表头列数补充")
    void missingSeparatorRow() {
        String md = "| 课时 | 内容 |\n| 1 | 导入 |";
        String fixed = MarkdownTableRepair.repair(md);
        String[] lines = fixed.split("\n");
        assertEquals("|---|---|", lines[1], "应在表头后补分隔行: " + fixed);
    }

    @Test
    @DisplayName("单元格内 <br> 保留不被破坏")
    void brInCellPreserved() {
        String md = "| 课时 | 活动 |\n|---|---|\n| 1 | 活动一 <br>活动二 |";
        String fixed = MarkdownTableRepair.repair(md);
        assertTrue(fixed.contains("<br>"), "<br> 应原样保留: " + fixed);
        assertTrue(fixed.contains("| 1 | 活动一 <br>活动二 |"), "列数应保持 2 列: " + fixed);
    }

    @Test
    @DisplayName("非表格内容原样保留(非破坏性)")
    void nonTableContentUntouched() {
        String md = "## 教学目标\n\n掌握两位数乘法\n\n- 重点\n- 难点";
        String fixed = MarkdownTableRepair.repair(md);
        assertEquals(md, fixed, "非表格内容不应被修改");
    }

    @Test
    @DisplayName("简单Markdown集成: 漂移表格渲染为真正的 table")
    void simpleMarkdownIntegration() {
        String md = "| 课时 | 内容\n|------|------|\n| 1 | 导入";
        String html = FormatTool.simpleMarkdown(md);
        assertTrue(html.contains("<table>"), "应渲染为 <table>: " + html);
        assertTrue(html.contains("<th>课时</th>"), "表头应转为 <th>: " + html);
        assertFalse(html.contains("|------"), "原始管道符分隔行不应残留: " + html);
    }

    @Test
    @DisplayName("null 输入返回 null")
    void nullInput() {
        assertNull(MarkdownTableRepair.repair(null));
    }

    @Test
    @DisplayName("空字符串返回空")
    void emptyInput() {
        assertEquals("", MarkdownTableRepair.repair(""));
    }
}
