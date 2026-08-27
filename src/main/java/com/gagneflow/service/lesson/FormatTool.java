package com.gagneflow.service.lesson;

import org.springframework.stereotype.Component;

@Component
public class FormatTool {
    /**
     * \u751f\u6210\u6559\u6848\u5b8c\u6574 HTML\u3002
     * 2026-08-22: \u8d28\u91cf\u8bc4\u4f30(Review)\u4e0d\u518d\u5199\u5165\u6559\u6848\u6b63\u6587 \u2014\u2014 \u8bc4\u5206/\u7ef4\u5ea6\u7531 SSE stage:review \u4e8b\u4ef6\u72ec\u7acb\u4e0b\u53d1
     * \u7ed9\u524d\u7aef\u5c55\u793a, \u6559\u6848 HTML/PDF \u53ea\u5305\u542b\u6559\u5b66\u5206\u6790/\u8bbe\u8ba1/\u8fc7\u7a0b\u4e09\u8282, \u4e0d\u6df7\u5165\u4e0e\u6559\u5b66\u65e0\u5173\u7684\u5185\u5bb9\u3002
     * review \u53c2\u6570\u4fdd\u7559\u4ec5\u4e3a\u517c\u5bb9\u65e7\u8c03\u7528, \u5185\u90e8\u4e0d\u518d\u6e32\u67d3\u3002
     */
    public String format(String analysis, String design, String development, String review) {
        String html = "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n<title>\u6559\u6848</title>\n<style>\n@page{size:A4;margin:20mm}\n*{margin:0;padding:0;box-sizing:border-box}\nbody{font-family:'SimSun','Songti SC','Arial',serif;font-size:11pt;line-height:1.7;color:#222;margin:0 auto;padding:0}\nh1{font-size:18pt;text-align:center;margin:0 0 12pt;font-family:'SimHei','PingFang SC',sans-serif;font-weight:700;color:#2c2420}\nh2{font-size:13pt;margin:14pt 0 6pt;padding-bottom:3pt;border-bottom:2px solid #2c2420;font-family:'SimHei',sans-serif;font-weight:700;color:#2c2420}\nh3{font-size:11.5pt;margin:10pt 0 4pt;font-family:'SimHei',sans-serif;font-weight:700;color:#3d3430}\np{margin:3pt 0;text-indent:2em;font-size:11pt}\nol,ul{margin:4pt 0 4pt 2.5em;font-size:11pt}\nli{margin:2pt 0}\ntable{border-collapse:collapse;width:100%;margin:8pt 0;font-size:10.5pt}\nth{background:#f0ede8;font-weight:700;border:1px solid #999;padding:4pt 6pt;text-align:center}\ntd{border:1px solid #999;padding:4pt 6pt}\nstrong{color:#c48650;font-weight:700}\npre{background:#f8f6f3;padding:8pt;border-radius:3pt;font-size:10pt;overflow-x:auto}\n.header{text-align:center;margin-bottom:14pt}\n.section{border:1px solid #e0dcd6;border-radius:4pt;padding:6pt 10pt;margin:6pt 0;background:#fdfbf8}\n.section-title{font-weight:700;font-size:12pt;margin-bottom:4pt;color:#2c2420}\n.stage-label{display:inline-block;background:#2c2420;color:#fff;font-size:9pt;padding:2pt 8pt;border-radius:3pt;margin-right:6pt;vertical-align:2pt}\n.img-placeholder{display:block;margin:8pt auto;max-width:90%;padding:8pt;border:1px dashed #ccc;text-align:center;color:#999;font-size:10pt;background:#fafafa;border-radius:4pt}\n</style>\n</head>\n<body>\n<div class=\"header\"><h1>\u6559\u6848</h1></div>\n" + this.toSection("\u6559\u5b66\u5206\u6790", analysis) + this.toSection("\u6559\u5b66\u8bbe\u8ba1", design) + this.toSection("\u6559\u5b66\u8fc7\u7a0b", development) + "</body>\n</html>";
        // 2026-08-22: emoji 替换/剥离, 防止 PDF 字体缺字形渲染乱码
        return sanitizeEmoji(html);
    }

    private String toSection(String title, String content) {
        if (content == null || content.isBlank()) {
            content = "_\u6b64\u90e8\u5206\u5c06\u5728\u540e\u53f0\u81ea\u52a8\u751f\u6210\uff0c\u8bf7\u7a0d\u540e\u5237\u65b0\u9875\u9762\u67e5\u770b\u3002_";
        }
        return "<div class=\"section\">\n<div class=\"section-title\"><span class=\"stage-label\">" + title + "</span></div>\n" + FormatTool.simpleMarkdown(content) + "\n</div>\n";
    }

    static String simpleMarkdown(String md) {
        if (md == null) {
            return "";
        }
        // 2026-08-21: 解析前先修复 LLM 表格漂移(漏管道符/列数不一致/缺分隔行),
        // 修复层保证后续解析拿到规范表格, 避免"竖线横线墙"文本
        md = MarkdownTableRepair.repair(md);

        StringBuilder result = new StringBuilder();
        boolean inCode = false;
        boolean inOl = false;
        boolean inUl = false;
        boolean inTable = false;
        String prevRow = null;
        StringBuilder codeBuf = new StringBuilder();
        for (String line : md.split("\n", -1)) {
            String item;
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    result.append("<pre><code>").append(FormatTool.escapeHtml(codeBuf.toString())).append("</code></pre>\n");
                    codeBuf.setLength(0);
                }
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                codeBuf.append(line).append("\n");
                continue;
            }
            if (inOl && !trimmed.matches("^\\d+\\. .+")) {
                result.append("</ol>\n");
                inOl = false;
            }
            if (inUl && !trimmed.startsWith("- ") && !trimmed.startsWith("* ")) {
                result.append("</ul>\n");
                inUl = false;
            }
            if (inTable && !trimmed.startsWith("|")) {
                result.append("</table>\n");
                inTable = false;
                prevRow = null;
            }
            if (trimmed.isEmpty()) {
                result.append("<p>&nbsp;</p>\n");
                continue;
            }
            if (trimmed.matches("^\\[img:.+]$")) {
                String desc = trimmed.substring(5, trimmed.length() - 1);
                result.append("<div class=\"img-placeholder\">[\u63d2\u56fe: ").append(FormatTool.escapeHtml(desc)).append("]</div>\n");
                continue;
            }
            if (trimmed.matches("^#{1,6}\\s*[^#\\s].*")) {
                // 2026-08-23: 标题识别加强 — 支持 1-6 级且可无空格(##标题), 结果钳制到 h6。
                // 原实现 ^#{1,3} .+ 只认 1-3 级且必须有空格, 导致 #### 四级标题落到 <p>、##标题残留 ##。
                int hashes = 0;
                while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') {
                    hashes++;
                }
                String h = trimmed.substring(hashes).trim();
                int level = Math.min(hashes, 6); // 钳制到 h6
                h = FormatTool.escapeHtml(h);
                h = FormatTool.applyBoldItalic(h);
                result.append("<h").append(level).append(">").append(h).append("</h").append(level).append(">\n");
                continue;
            }
            if (trimmed.matches("^\\d+\\. .+")) {
                if (!inOl) {
                    result.append("<ol>\n");
                    inOl = true;
                }
                item = trimmed.replaceFirst("^\\d+\\. ", "");
                result.append("<li>").append(FormatTool.applyBoldItalic(item)).append("</li>\n");
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                if (!inUl) {
                    result.append("<ul>\n");
                    inUl = true;
                }
                item = trimmed.replaceFirst("^[-*] ", "");
                result.append("<li>").append(FormatTool.applyBoldItalic(item)).append("</li>\n");
                continue;
            }
            // 表格行识别放宽: 仅要求以 | 开头(repair 层已规范化首尾管道符, 此处防御)
            if (trimmed.startsWith("|")) {
                // 分隔行(含 - 的对齐行): 用于把上一行升级为表头
                if (trimmed.matches("^\\|?[\\s\\-:|]+\\|?$") && trimmed.contains("-")) {
                    if (prevRow != null && inTable) {
                        String thRow = prevRow.replace("<td>", "<th>").replace("</td>", "</th>");
                        int trPos = result.lastIndexOf("<tr>");
                        if (trPos >= 0) {
                            int trEnd = result.indexOf("</tr>", trPos);
                            if (trEnd >= 0) {
                                result.replace(trPos, trEnd + 5, thRow);
                            }
                        }
                    }
                    prevRow = null;
                    continue;
                }
                if (!inTable) {
                    result.append("<table>\n");
                    inTable = true;
                }
                String cells = trimmed.replaceAll("^\\|+", "").replaceAll("\\|+$", "");
                // 2026-08-27: 转义管道符(\|)先保护为哨兵字符再拆分, 避免单元格内字面 | 被误拆为列分隔
                String protectedCells = cells.replace("\\|", "\u0000");
                StringBuilder row = new StringBuilder("<tr>");
                for (String cell : protectedCells.split("\\|")) {
                    String cellHtml = FormatTool.applyBoldItalic(cell.trim().replace("\u0000", "|"));
                    row.append("<td>").append(cellHtml).append("</td>");
                }
                row.append("</tr>\n");
                prevRow = row.toString();
                result.append(prevRow);
                continue;
            }
            if (line.startsWith("<div") || line.startsWith("</div")) {
                result.append(FormatTool.applyBoldItalic(line)).append("\n");
                continue;
            }
            result.append("<p>").append(line.isEmpty() ? "&nbsp;" : FormatTool.applyBoldItalic(line)).append("</p>\n");
        }
        if (inOl) {
            result.append("</ol>\n");
        }
        if (inUl) {
            result.append("</ul>\n");
        }
        if (inTable) {
            result.append("</table>\n");
        }
        // 2026-08-23: 围栏不闭合兜底 —— LLM 偶发只开一个 ``` 未收尾, 原实现把 codeBuf 静默丢弃,
        // 吞掉含 ## 标题的后续所有行。改为把残留 codeBuf 作为 pre 输出(内容不丢)。
        if (inCode && codeBuf.length() > 0) {
            result.append("<pre><code>").append(FormatTool.escapeHtml(codeBuf.toString())).append("</code></pre>\n");
            codeBuf.setLength(0);
        }
        return result.toString().replace("<br>", "<br/>");
    }

    /**
     * 行内 Markdown -> HTML 处理(2026-08-22 补强, 消除教案正文的 MD 痕迹):
     * 链接[text](url)->text(教案场景保留文本)、删除线~~x~~->del、行内代码`x`->code、
     * 粗体**x**->strong、斜体粗体***x***->strong+em。
     * 残留的未配对 ** 双星清理(单星 * 保留, 避免误伤数学表达式如 3*4)。
     */
    private static String applyBoldItalic(String text) {
        if (text == null) {
            return "";
        }
        String s = text;
        s = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("~~(.+?)~~", "<del>$1</del>");
        s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        s = s.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "<strong><em>$1</em></strong>")
             .replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        // 残留未配对双星清理(避免正文出现裸 **)
        s = s.replace("**", "");
        return s;
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ==================== emoji 安全化(2026-08-22) ====================
    // PDF 渲染链路(Flying Saucer + SimSun/SimHei/Arial)没有 emoji 字形,
    // 板书设计中的 emoji(🌟⭐🏠等)渲染为方框乱码。策略: 常见装饰 emoji 映射为
    // 可打印符号(语义保留), 其余 emoji 一律剥离, 保证教案正文/PDF 不出现乱码。
    private static final java.util.Map<String, String> EMOJI_MAP = java.util.Map.ofEntries(
        java.util.Map.entry("\uD83C\uDF1F", "\u2605"), // 🌟 -> ★
        java.util.Map.entry("\u2B50", "\u2605"),        // ⭐ -> ★
        java.util.Map.entry("\u2728", "\u2605"),        // ✨ -> ★
        java.util.Map.entry("\uD83D\uDCBB", "\u25A0"), // 💻 -> ■
        java.util.Map.entry("\uD83D\uDCDA", "\u25A3"), // 📚 -> ▣
        java.util.Map.entry("\u270F\uFE0F", "\u270E"), // ✏️ -> ✎
        java.util.Map.entry("\uD83D\uDCDD", "\u270E"), // 📝 -> ✎
        java.util.Map.entry("\uD83C\uDFAF", "\u25CE"), // 🎯 -> ◎
        java.util.Map.entry("\uD83D\uDD0D", "\u25CE"), // 🔍 -> ◎
        java.util.Map.entry("\uD83D\uDCA1", "\u2726"), // 💡 -> ✦
        java.util.Map.entry("\uD83C\uDFA8", "\u2726"), // 🎨 -> ✦
        java.util.Map.entry("\uD83C\uDFE0", "\u25A6"), // 🏠 -> ▦
        java.util.Map.entry("\uD83D\uDC6B", "\u25C9"), // 👫 -> ◉
        java.util.Map.entry("\uD83C\uDF0D", "\u25C9"), // 🌍 -> ◉
        java.util.Map.entry("\uD83D\uDE0A", "\u263A")  // 😊 -> ☺
    );

    /** 是否为 emoji 码点(含装饰/杂项符号区), 用于兜底剥离未映射的 emoji */
    private static boolean isEmojiCodePoint(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)   // 表情/符号/补充符号
            || (cp >= 0x2600 && cp <= 0x27BF)      // 杂项符号/装饰符号
            || (cp >= 0xFE00 && cp <= 0xFE0F)      // 变体选择符
            || cp == 0x200D;                        // ZWJ
    }

    /** 教案 HTML 出口统一安全化: emoji 映射为可打印符号, 未映射的剥离(防 PDF 乱码) */
    static String sanitizeEmoji(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        StringBuilder sb = new StringBuilder(html.length());
        int i = 0;
        while (i < html.length()) {
            int cp = html.codePointAt(i);
            int len = Character.charCount(cp);
            if (cp == 0x200D || (cp >= 0xFE00 && cp <= 0xFE0F)) {
                i += len; // ZWJ/变体选择符剥离
                continue;
            }
            if (isEmojiCodePoint(cp)) {
                String ch = new String(Character.toChars(cp));
                String mapped = EMOJI_MAP.get(ch);
                if (mapped != null) {
                    sb.append(mapped);
                }
                i += len;
                continue;
            }
            sb.append(html, i, i + len);
            i += len;
        }
        return sb.toString();
    }

    // ==================== 直出 HTML(quick 升级, 2026-08-23) ====================
    // 让 LLM 直接产出语义化 HTML 片段, 绕开 simpleMarkdown(MD 解析的围栏/表格漂移缺陷)。
    // 服务端只做: Jsoup 白名单消毒 → emoji 安全化 → 套统一外壳。

    /** Jsoup 白名单: 仅允许教学相关标签, 剔除 script/style/iframe 等危险标签 */
    private static final org.jsoup.safety.Safelist LESSON_SAFELIST = buildLessonSafelist();

    private static org.jsoup.safety.Safelist buildLessonSafelist() {
        org.jsoup.safety.Safelist safe = new org.jsoup.safety.Safelist();
        // 块级/语义标签
        safe.addTags("h1", "h2", "h3", "h4", "p", "div", "br", "hr", "strong", "em", "del", "code", "pre", "blockquote");
        // 列表
        safe.addTags("ul", "ol", "li");
        // 表格(仅保留结构 + 对齐属性)
        safe.addTags("table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption");
        safe.addAttributes("th", "colspan", "rowspan");
        safe.addAttributes("td", "colspan", "rowspan");
        safe.addProtocols("a", "href", "http", "https", "mailto");
        safe.addAttributes("a", "href");
        // 其余标签/属性一律剥离
        return safe;
    }

    /**
     * 直出 HTML 教案(LLM 产 HTML 片段): Jsoup 白名单消毒 → emoji 安全化 → 套统一外壳。
     * 不走 simpleMarkdown(避免 MD 解析缺陷); PDF 字体链/分页 CSS 与 {@link #format} 一致。
     *
     * @param htmlFragment LLM 产出的 HTML 片段(body 内部内容, 无 html/head/body 外壳)
     */
    public String formatDirect(String htmlFragment) {
        String fragment = htmlFragment != null ? htmlFragment : "";
        // 1) Jsoup 白名单消毒: 剥离 script/style/iframe 及全部非白名单标签/属性, 防止注入
        String cleaned = org.jsoup.Jsoup.clean(fragment, LESSON_SAFELIST);
        // 2) emoji 安全化(PDF 字体无 emoji 字形)
        cleaned = sanitizeEmoji(cleaned);
        // 3) 套统一外壳
        return wrapWithShell(cleaned);
    }

    /** 复用 format 的 字体链 / A4 分页 / 颜色 外壳, 仅塞入消毒后的正文片段 */
    private String wrapWithShell(String body) {
        String css = "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n<title>教案</title>\n<style>\n"
            + "@page{size:A4;margin:20mm}\n*{margin:0;padding:0;box-sizing:border-box}\n"
            + "body{font-family:'SimSun','Songti SC','Arial',serif;font-size:11pt;line-height:1.7;color:#222;margin:0 auto;padding:0}\n"
            + "h1{font-size:18pt;text-align:center;margin:0 0 12pt;font-family:'SimHei','PingFang SC',sans-serif;font-weight:700;color:#2c2420}\n"
            + "h2{font-size:13pt;margin:14pt 0 6pt;padding-bottom:3pt;border-bottom:2px solid #2c2420;font-family:'SimHei',sans-serif;font-weight:700;color:#2c2420}\n"
            + "h3{font-size:11.5pt;margin:10pt 0 4pt;font-family:'SimHei',sans-serif;font-weight:700;color:#3d3430}\n"
            + "p{margin:3pt 0;text-indent:2em;font-size:11pt}\n"
            + "ol,ul{margin:4pt 0 4pt 2.5em;font-size:11pt}\nli{margin:2pt 0}\n"
            + "table{border-collapse:collapse;width:100%;margin:8pt 0;font-size:10.5pt}\n"
            + "th{background:#f0ede8;font-weight:700;border:1px solid #999;padding:4pt 6pt;text-align:center}\n"
            + "td{border:1px solid #999;padding:4pt 6pt}\n"
            + "strong{color:#c48650;font-weight:700}\n"
            + "pre{background:#f8f6f3;padding:8pt;border-radius:3pt;font-size:10pt;overflow-x:auto}\n"
            + ".header{text-align:center;margin-bottom:14pt}\n"
            + "</style>\n</head>\n<body>\n<div class=\"header\"><h1>教案</h1></div>\n"
            + body + "\n</body>\n</html>";
        return css;
    }
}