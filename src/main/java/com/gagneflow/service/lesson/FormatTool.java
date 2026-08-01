package com.gagneflow.service.lesson;

import org.springframework.stereotype.Component;

@Component
public class FormatTool {
    public String format(String analysis, String design, String development, String review) {
        return "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n<title>\u6559\u6848</title>\n<style>\n@page{size:A4;margin:20mm}\n*{margin:0;padding:0;box-sizing:border-box}\nbody{font-family:'SimSun','Songti SC',serif;font-size:11pt;line-height:1.7;color:#222;margin:0 auto;padding:0}\nh1{font-size:18pt;text-align:center;margin:0 0 12pt;font-family:'SimHei','PingFang SC',sans-serif;font-weight:700;color:#2c2420}\nh2{font-size:13pt;margin:14pt 0 6pt;padding-bottom:3pt;border-bottom:2px solid #2c2420;font-family:'SimHei',sans-serif;font-weight:700;color:#2c2420}\nh3{font-size:11.5pt;margin:10pt 0 4pt;font-family:'SimHei',sans-serif;font-weight:700;color:#3d3430}\np{margin:3pt 0;text-indent:2em;font-size:11pt}\nol,ul{margin:4pt 0 4pt 2.5em;font-size:11pt}\nli{margin:2pt 0}\ntable{border-collapse:collapse;width:100%;margin:8pt 0;font-size:10.5pt}\nth{background:#f0ede8;font-weight:700;border:1px solid #999;padding:4pt 6pt;text-align:center}\ntd{border:1px solid #999;padding:4pt 6pt}\nstrong{color:#c48650;font-weight:700}\npre{background:#f8f6f3;padding:8pt;border-radius:3pt;font-size:10pt;overflow-x:auto}\n.header{text-align:center;margin-bottom:14pt}\n.section{border:1px solid #e0dcd6;border-radius:4pt;padding:6pt 10pt;margin:6pt 0;background:#fdfbf8}\n.section-title{font-weight:700;font-size:12pt;margin-bottom:4pt;color:#2c2420}\n.stage-label{display:inline-block;background:#2c2420;color:#fff;font-size:9pt;padding:2pt 8pt;border-radius:3pt;margin-right:6pt;vertical-align:2pt}\n.img-placeholder{display:block;margin:8pt auto;max-width:90%;padding:8pt;border:1px dashed #ccc;text-align:center;color:#999;font-size:10pt;background:#fafafa;border-radius:4pt}\n</style>\n</head>\n<body>\n<div class=\"header\"><h1>\u6559\u6848</h1></div>\n" + this.toSection("\u6559\u5b66\u5206\u6790", analysis) + this.toSection("\u6559\u5b66\u8bbe\u8ba1", design) + this.toSection("\u6559\u5b66\u8fc7\u7a0b", development) + this.toSection("\u8d28\u91cf\u8bc4\u4f30", review) + "</body>\n</html>";
    }

    private String toSection(String title, String content) {
        if (content == null || content.isBlank()) {
            content = "_\u6b64\u90e8\u5206\u5c06\u5728\u540e\u53f0\u81ea\u52a8\u751f\u6210\uff0c\u8bf7\u7a0d\u540e\u5237\u65b0\u9875\u9762\u67e5\u770b\u3002_";
        }
        return "<div class=\"section\">\n<div class=\"section-title\"><span class=\"stage-label\">" + title + "</span></div>\n" + FormatTool.simpleMarkdown(content) + "\n</div>\n";
    }

    static String simpleMarkdown(String md) {
        StringBuilder result = new StringBuilder();
        if (md == null) {
            return "";
        }
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
            if (trimmed.matches("^#{1,3} .+")) {
                int level = trimmed.indexOf(32);
                String h = trimmed.substring(level + 1).trim();
                h = FormatTool.escapeHtml(h);
                h = FormatTool.applyBoldItalic(h);
                result.append("<h").append(level + 1).append(">").append(h).append("</h").append(level + 1).append(">\n");
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
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                if (trimmed.matches("^\\|[\\s\\-:|]+\\|$")) {
                    if (prevRow != null && inTable) {
                        int trEnd;
                        String thRow = prevRow.replace("<td>", "<th>").replace("</td>", "</th>");
                        int trPos = result.lastIndexOf("<tr>");
                        if (trPos >= 0 && (trEnd = result.indexOf("</tr>", trPos)) >= 0) {
                            result.replace(trPos, trEnd + 5, thRow);
                        }
                    }
                    prevRow = null;
                    continue;
                }
                if (!inTable) {
                    result.append("<table>\n");
                    inTable = true;
                }
                String cells = trimmed.replaceAll("^\\|", "").replaceAll("\\|$", "");
                StringBuilder row = new StringBuilder("<tr>");
                for (String cell : cells.split("\\|")) {
                    String cellHtml = FormatTool.applyBoldItalic(cell.trim());
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
        return result.toString().replace("<br>", "<br/>");
    }

    private static String applyBoldItalic(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "<strong><em>$1</em></strong>").replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
