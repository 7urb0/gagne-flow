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

    // 2026-09-02 教案结构改造: format 以 LessonHeader + 平铺内容为签名(5参: header + 3内容 + review兼容)
    private static final LessonHeader HEADER = new LessonHeader("两位数乘一位数", "小学", 3, "数学", 1);

    @Test
    void format_shouldReturnValidHtml() {
        String result = formatTool.format(HEADER, "分析内容", "设计内容", "过程内容", "");

        assertNotNull(result);
        assertTrue(result.startsWith("<!DOCTYPE html>"), "应该以 HTML doctype 开头");
        assertTrue(result.contains("<h1>两位数乘一位数</h1>"), "头部应渲染课题名");
        assertTrue(result.contains("小学 · 3年级 · 数学 · 共 1 课时"), "应渲染元信息行");
        assertTrue(result.contains("</html>"), "应该包含闭合标签");
    }

    @Test
    void format_shouldFlattenContents() {
        // 2026-09-02: 不再按阶段壳包裹, 三段内容平铺进交付教案
        String result = formatTool.format(HEADER, "学情正文A", "设计正文B", "过程正文C", "");

        assertTrue(result.contains("学情正文A"));
        assertTrue(result.contains("设计正文B"));
        assertTrue(result.contains("过程正文C"));
        assertFalse(result.contains("教学评估"), "教案正文不应包含质量评估/教学评估节");
    }

    @Test
    void format_header_withoutTopic_usesSubjectFallback() {
        String result = formatTool.format(new LessonHeader(null, "小学", 3, "数学", 1), "a", "b", "c", "");
        assertTrue(result.contains("<h1>数学3教案</h1>"), "无课题时应回退 {学科}{年级}教案");
    }

    @Test
    void format_shouldContainStylesheet() {
        String result = formatTool.format(HEADER, "a", "b", "c", "");

        assertTrue(result.contains("@page{size:A4;"), "应该包含打印样式");
        assertTrue(result.contains("font-family"), "应该包含字体样式");
    }

    @Test
    void format_shouldHandleEmptyContent() {
        String result = formatTool.format(HEADER, "", "", "", "");

        assertNotNull(result);
        assertTrue(result.contains("此部分未生成或生成失败"), "空内容应渲染占位提示");
    }

    @Test
    void format_shouldHandleNullContent_withPlaceholder() {
        String result = formatTool.format(HEADER, null, null, null, "");

        assertNotNull(result);
        assertTrue(result.contains("此部分未生成或生成失败"), "null 内容应渲染占位提示");
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
        String html = formatTool.format(HEADER, "分析🌟", "设计", "板书: \uD83C\uDF1F 主题", "");
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

    // ============ 教案结构改造(2026-09-02) ============

    @Test
    void filterDeliverableSections_keepsOnlyDeliverableH2() {
        // 中间产物(h2 知识点清单等)应被剔除, 交付章节保留
        String md = "## 学情分析\n学情正文A\n"
                + "## 知识点清单\n内部知识列表\n"
                + "## 教学目标\n目标B";
        String html = FormatTool.filterDeliverableSections(md);
        assertTrue(html.contains("学情正文A"), "学情分析应保留");
        assertTrue(html.contains("目标B"), "教学目标应保留");
        assertFalse(html.contains("知识点清单"), "知识点清单(中间产物)应被剔除");
        assertFalse(html.contains("内部知识列表"), "中间产物内容不应进入教案");
    }

    @Test
    void filterDeliverableSections_noH2_keepsWholeText() {
        // 无 ## 结构(降级/占位文本)整体保留, 防丢内容
        String html = FormatTool.filterDeliverableSections("（此部分待补充）");
        assertTrue(html.contains("（此部分待补充）"), "无 h2 文本应整体保留");
    }

    @Test
    void formatDirect_withHeader_rendersTopicAndMeta() {
        String html = formatTool.formatDirect("<h2>教学目标</h2><p>知识</p>", HEADER);
        assertTrue(html.contains("<h1>两位数乘一位数</h1>"), "直出 HTML 应渲染课题头");
        assertTrue(html.contains("小学 · 3年级 · 数学 · 共 1 课时"), "直出 HTML 应渲染元信息");
        assertTrue(html.contains("<h2>教学目标</h2>"), "正文片段应保留");
    }
}
