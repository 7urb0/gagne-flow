package com.gagneflow.service.lesson;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 表格修复器（2026-08-21 新增，解决 LLM 表格格式漂移）。
 * <p>
 * 背景: LLM 输出的 Markdown 表格常出现以下畸形, 导致 {@link FormatTool#simpleMarkdown}
 * 放弃表格解析、按普通段落渲染, HTML/PDF 中呈现"竖线横线墙"而非真正的表格:
 * <ul>
 *   <li>行缺失首/尾管道符 {@code |}</li>
 *   <li>分隔行列数与表头不一致 / 缺失分隔行</li>
 *   <li>数据行列数与表头不一致（多列或缺列）</li>
 * </ul>
 * 本类为<b>解析前修复层</b>: 在 simpleMarkdown 之前对原始文本做无损表格修复,
 * 只动被判定为表格的连续行块, 其余内容原样保留。
 * </p>
 * <p>
 * 候选块识别策略（参考 arxiv 2508.15910 的两阶段 candidate extraction）:
 * 一行"像表格"（含管道符）且（以 | 开头 或 下一行是分隔行）即进入候选,
 * 向后收集所有"含管道符或分隔行"的连续行; 这样漏了首管道符的表头也能被识别修复。
 * </p>
 */
public final class MarkdownTableRepair {

    private MarkdownTableRepair() {
    }

    /**
     * 修复文本中的所有畸形 Markdown 表格。
     * 非破坏性: 非表格内容原样保留。
     */
    public static String repair(String md) {
        if (md == null || md.isEmpty()) {
            return md;
        }
        String[] lines = md.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String next = i + 1 < lines.length ? lines[i + 1] : null;
            // 候选: 像表格(含 |) 且 (以 | 开头 或 下一行是分隔行)
            if (isTableish(line) && (line.trim().startsWith("|") || isSeparatorLine(trim(next)))) {
                List<String> block = new ArrayList<>();
                block.add(line);
                int j = i + 1;
                while (j < lines.length && (isTableish(lines[j]) || isSeparatorLine(lines[j].trim()))) {
                    block.add(lines[j]);
                    j++;
                }
                out.addAll(repairBlock(block));
                i = j;
            } else {
                out.add(line);
                i++;
            }
        }
        return String.join("\n", out);
    }

    /** 一行是否"像表格行": 以 | 开头, 或含 | 且非代码块行 */
    private static boolean isTableish(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim();
        return t.startsWith("|") || (t.contains("|") && !t.startsWith("```"));
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** 分隔行: |---|---| 或 |:---:| 或 ------|------ (允许缺首 |), 必须含 "-" 且含 "|" */
    private static boolean isSeparatorLine(String trimmed) {
        if (trimmed == null || !trimmed.contains("-") || !trimmed.contains("|")) {
            return false;
        }
        String inner = trimmed.replaceAll("^\\|+", "").replaceAll("\\|+$", "");
        return inner.matches("^[\\s\\-:|]+$");
    }

    /** 修复一个连续表格候选块 */
    private static List<String> repairBlock(List<String> block) {
        // 1. 表头 = 第一个"以 | 开头 或 含 |"的非分隔行(通常是第一行, 漏首 | 也接受)
        int headerIdx = -1;
        for (int k = 0; k < block.size(); k++) {
            String t = block.get(k).trim();
            if (t.contains("|") && !isSeparatorLine(t)) {
                headerIdx = k;
                break;
            }
        }
        if (headerIdx < 0) {
            return block; // 找不到表头, 原样返回
        }

        // 2. 规范化表头并确定列数
        String header = normalizeRow(block.get(headerIdx));
        int headerCols = countCells(header);

        List<String> out = new ArrayList<>();
        boolean hasSeparator = false;

        for (int k = 0; k < block.size(); k++) {
            String t = block.get(k).trim();
            if (isSeparatorLine(t)) {
                hasSeparator = true;
                out.add(separatorRow(headerCols));
                continue;
            }
            if (!t.contains("|")) {
                out.add(block.get(k)); // 非表格行(防御)
                continue;
            }
            if (k == headerIdx) {
                out.add(header);
            } else {
                out.add(alignRow(normalizeRow(block.get(k)), headerCols));
            }
        }

        // 3. 缺失分隔行则补(表头后插入)
        if (!hasSeparator && out.size() > 1) {
            out.add(1, separatorRow(headerCols));
        }
        return out;
    }

    /** 规范化单行: 确保以 | 开头和结尾, 并重建为 "| a | b |" 统一格式 */
    private static String normalizeRow(String line) {
        String t = line.trim();
        // 剥掉首尾管道符(可能多个)
        String inner = t.replaceAll("^\\|+", "").replaceAll("\\|+$", "").trim();
        if (inner.isEmpty()) {
            return "|  |";
        }
        String[] cells = splitCellsEscaped(inner);
        StringBuilder sb = new StringBuilder("|");
        for (String c : cells) {
            sb.append(" ").append(c.trim()).append(" |");
        }
        return sb.toString();
    }

    /** 统计单元格数(按 | 分割, 忽略首尾空段) */
    private static int countCells(String normalizedRow) {
        String inner = stripOuterPipes(normalizedRow);
        if (inner.isEmpty()) {
            return 1;
        }
        return splitCellsEscaped(inner).length;
    }

    /** 按表头列数对齐一行: 多列->多余内容并入最后一列; 缺列->补空 */
    private static String alignRow(String normalizedRow, int headerCols) {
        String inner = stripOuterPipes(normalizedRow);
        if (inner.isEmpty()) {
            return emptyRow(headerCols);
        }
        String[] cells = splitCellsEscaped(inner);
        List<String> cols = new ArrayList<>(headerCols);
        if (cells.length == headerCols) {
            for (String c : cells) {
                cols.add(c.trim());
            }
        } else if (cells.length > headerCols) {
            for (int c = 0; c < headerCols - 1; c++) {
                cols.add(cells[c].trim());
            }
            StringBuilder last = new StringBuilder(cells[headerCols - 1].trim());
            for (int c = headerCols; c < cells.length; c++) {
                last.append(" ").append(cells[c].trim());
            }
            cols.add(last.toString());
        } else {
            for (String c : cells) {
                cols.add(c.trim());
            }
            while (cols.size() < headerCols) {
                cols.add("");
            }
        }
        StringBuilder sb = new StringBuilder("|");
        for (String c : cols) {
            sb.append(" ").append(c).append(" |");
        }
        return sb.toString();
    }

    /** 去掉行首尾的管道符 */
    private static String stripOuterPipes(String normalizedRow) {
        String s = normalizedRow;
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 按 | 拆单元格, 但先保护转义管道符(\\|), 避免单元格内字面 | 被误拆为列分隔。
     * 拆完后还原为原文, 交由渲染层({@link FormatTool#simpleMarkdown})再次保护还原。
     */
    private static String[] splitCellsEscaped(String inner) {
        String protectedStr = inner.replace("\\|", "\u0001");
        String[] raw = protectedStr.split("\\|", -1);
        for (int i = 0; i < raw.length; i++) {
            raw[i] = raw[i].replace("\u0001", "\\|");
        }
        return raw;
    }

    /** 生成与列数匹配的分隔行 */
    private static String separatorRow(int cols) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < cols; c++) {
            sb.append("---|");
        }
        return sb.toString();
    }

    /** 生成空行(用于缺列补空, 保留与表头一致的列数) */
    private static String emptyRow(int cols) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < cols; c++) {
            sb.append("  |");
        }
        return sb.toString();
    }
}
