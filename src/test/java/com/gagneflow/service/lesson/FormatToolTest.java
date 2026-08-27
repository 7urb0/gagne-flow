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
        // 2026-08-22: 质量评估(Review)不再写入教案正文, 由 SSE stage:review 独立下发
        assertFalse(result.contains("质量评估"), "教案正文不应包含质量评估节");
        assertFalse(result.contains("质量评估D"), "教案正文不应包含 review 内容");
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
        assertFalse(result.contains("质量评估"), "教案正文不应包含质量评估节");
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
        assertFalse(result.contains("质量评估"), "教案正文不应包含质量评估节");
    }

    @Test
    void inlineMarkdown_shouldStripMdTraces() {
        // 2026-08-22: 正文不允许残留 MD 痕迹
        String html = FormatTool.simpleMarkdown(
            "**重点**: 掌握 `两位数乘法`, 参考[示例](http://x.com), 注意~~删除~~部分。\n\n残留 **未配对星号");
        assertTrue(html.contains("<strong>重点</strong>"), "粗体应转为 strong");
        assertTrue(html.contains("<code>两位数乘法</code>"), "行内代码应转为 code");
        assertFalse(html.contains("http://x.com"), "链接应去掉 url 语法");
        assertTrue(html.contains("参考示例"), "链接应保留文本");
        assertTrue(html.contains("<del>删除</del>"), "删除线应转为 del");
        assertFalse(html.contains("**"), "不应残留双星");
    }

    @Test
    void inlineMarkdown_shouldKeepMathAsterisk() {
        // 单星 * 保留(避免误伤数学表达式)
        String html = FormatTool.simpleMarkdown("计算 3*4=12");
        assertTrue(html.contains("3*4=12"), "单星数学表达式不应被清理");
    }

    @Test
    void emoji_shouldBeMappedOrStripped() {
        // 2026-08-22: 教案输出统一做 emoji 安全化, 防止 PDF 字体缺字形乱码
        String html = FormatTool.sanitizeEmoji("\uD83C\uDF1F 板书 \u2B50\uD83D\uDCDA \uD83D\uDC00 正文");
        assertTrue(html.contains("\u2605"), "🌟/⭐ 应映射为 ★: " + html);
        assertFalse(html.contains("\uD83D\uDC00"), "未映射 emoji(🦀)应剥离: " + html);
        assertTrue(html.contains("板书"), "中文应保留");
        assertTrue(html.contains("正文"), "正文应保留");
    }

    @Test
    void format_shouldNotContainEmoji() {
        // 完整 format 出口不应残留 emoji(含板书设计的 🌟 等)
        String html = formatTool.format("分析🌟", "设计", "板书: \uD83C\uDF1F 主题", "评估");
        assertFalse(html.contains("\uD83C\uDF1F"), "format 输出不应含 emoji");
        assertTrue(html.contains("分析"), "内容应保留");
    }

    // ============ quick 直出 HTML(2026-08-23) ============

    @Test
    void formatDirect_stripsScriptAndUnsafeTags() {
        String fragment = "<h2>教学目标</h2><p>知识</p><script>alert(1)</script>"
                + "<iframe src=\"x\"></iframe><img src=\"i\" onerror=\"x\">"
                + "<a href=\"javascript:alert(1)\">bad</a><strong>ok</strong>";
        String html = formatTool.formatDirect(fragment);

        assertTrue(html.startsWith("<!DOCTYPE html>"), "应套外壳");
        assertTrue(html.contains("<h2>教学目标</h2>"), "应保留 h2");
        assertTrue(html.contains("<p>知识</p>"), "应保留 p");
        assertFalse(html.contains("script"), "应剥离 script");
        assertFalse(html.contains("<iframe"), "应剥离 iframe");
        assertFalse(html.contains("<img"), "应剥离 img(非白名单)");
        assertFalse(html.contains("javascript:"), "应剥离去危险链接");
        assertTrue(html.contains("<strong>ok</strong>"), "应保留 strong");
        assertTrue(html.contains("</html>"), "应闭合");
    }

    @Test
    void formatDirect_stripsEmoji() {
        String html = formatTool.formatDirect("<h2>题\u76ee</h2><p>\uD83C\uDF1F \u5f15\u5165</p>");
        assertFalse(html.contains("\uD83C\uDF1F"), "直出 HTML 出口不应含 emoji");
        assertTrue(html.contains("引入"), "文本应保留");
    }

    @Test
    void formatDirect_keepsTable() {
        String html = formatTool.formatDirect("<table><thead><tr><th>a</th><th>b</th></tr></thead><tbody><tr><td>1</td><td>2</td></tr></tbody></table>");
        assertTrue(html.contains("<table"), "应保留 table");
        assertTrue(html.contains("<th>a</th>"), "表头应保留");
        assertTrue(html.contains("<td>1</td>"), "单元格应保留");
    }

    @Test
    void simpleMarkdown_unclosedFence_flushesResidual() {
        // 2026-08-23: 围栏只开不闭(LLM 偶发), 原实现吞掉后续行; 现应把残留 code 作为 pre 输出
        String html = FormatTool.simpleMarkdown("## 目标\n```\nint x = 1;\n");
        assertTrue(html.contains("<pre><code>"), "应输出残留代码块");
        assertTrue(html.contains("int x = 1;"), "代码内容不应丢失");
    }

    // ============ 标题识别加强(2026-08-23) ============

    @Test
    void simpleMarkdown_heading_levels() {
        assertTrue(FormatTool.simpleMarkdown("## 一、导入").contains("<h2>一、导入</h2>"));
        assertTrue(FormatTool.simpleMarkdown("### 二、探究新知").contains("<h3>二、探究新知</h3>"));
        // 四级标题(修复前落到 <p>####...)
        assertTrue(FormatTool.simpleMarkdown("#### 四、课堂总结").contains("<h4>四、课堂总结</h4>"));
        // 六级钳制(不产生非法 h7)
        assertTrue(FormatTool.simpleMarkdown("###### 六级").contains("<h6>六级</h6>"));
        assertFalse(FormatTool.simpleMarkdown("###### 六级").contains("<h7>"), "不应产生 <h7>");
    }

    @Test
    void simpleMarkdown_heading_noSpace() {
        // 无空格(##标题)也应识别, 不残留 ## 原文
        String html = FormatTool.simpleMarkdown("##标题");
        assertTrue(html.contains("<h2>标题</h2>"), "无空格标题应识别: " + html);
    }

    @Test
    void simpleMarkdown_heading_pureHashes_notHeading() {
        // 纯 ######(无内容)不误判为标题, 走段落
        String html = FormatTool.simpleMarkdown("######");
        assertFalse(html.contains("<h6>"), "纯井号不应是标题: " + html);
        assertTrue(html.contains("<p>"), "纯井号应走段落");
    }

    @Test
    void table_escapedPipe_notSplit() {
        // 单元格内转义管道符 \| 应保留为字面 |, 不应被误拆为列分隔
        String html = FormatTool.simpleMarkdown("| 名称 | 说明 |\n| --- | --- |\n| a \\| b | 字面管道 |");
        assertTrue(html.contains("<td>a | b</td>"), "转义管道应保留在单元格内: " + html);
        assertTrue(html.contains("<td>字面管道</td>"), "第二列应完整渲染: " + html);
    }
}
