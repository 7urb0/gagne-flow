package com.gagneflow.service.reader;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WordDocumentReader
implements DocumentReader {
    private static final Logger logger = LoggerFactory.getLogger(WordDocumentReader.class);
    private static final List<String> SUPPORTED_EXTENSIONS = List.of("docx");

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public String readText(Path filePath) throws IOException {
        StringBuilder fullText = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             XWPFDocument document = new XWPFDocument((InputStream)fis);){
            List<IBodyElement> bodyElements = document.getBodyElements();
            block14: for (IBodyElement element : bodyElements) {
                switch (element.getElementType()) {
                    case PARAGRAPH: {
                        XWPFParagraph paragraph = (XWPFParagraph)element;
                        String paragraphText = this.extractParagraph(paragraph);
                        if (paragraphText.isEmpty()) continue block14;
                        fullText.append(paragraphText).append("\n\n");
                        continue block14;
                    }
                    case TABLE: {
                        XWPFTable table = (XWPFTable)element;
                        String tableText = this.extractTable(table);
                        if (tableText.isEmpty()) continue block14;
                        fullText.append(tableText).append("\n\n");
                        continue block14;
                    }
                }
                logger.debug("\u8df3\u8fc7\u4e0d\u652f\u6301\u7684\u5143\u7d20\u7c7b\u578b: {}", (Object)element.getElementType());
            }
            logger.info("Word \u6587\u672c\u63d0\u53d6\u5b8c\u6210: {} -> {} \u5b57\u7b26, {} \u4e2a\u5143\u7d20", new Object[]{filePath.getFileName(), fullText.length(), bodyElements.size()});
        }
        if (fullText.isEmpty()) {
            logger.warn("Word \u6587\u672c\u4e3a\u7a7a: {}\uff0c\u53ef\u80fd\u4e3a\u7eaf\u56fe\u7247/\u8868\u683c/\u52a0\u5bc6\u6587\u6863", (Object)filePath.getFileName());
            return "[\u7cfb\u7edf\u63d0\u793a: \u6b64 .docx \u6587\u4ef6\u5185\u5bb9\u4e3a\u7a7a\u6216\u65e0\u6cd5\u63d0\u53d6\u6587\u672c\uff08\u53ef\u80fd\u4e3a\u7eaf\u56fe\u7247\u3001\u7eaf\u8868\u683c\u6216\u52a0\u5bc6\u6587\u6863\uff09\uff0c\u8bf7\u4e0a\u4f20\u7eaf\u6587\u672c .docx \u6216\u8f6c\u6362\u6210 .md/.txt \u683c\u5f0f\u540e\u91cd\u8bd5]";
        }
        return fullText.toString();
    }

    private String extractParagraph(XWPFParagraph paragraph) {
        int headingLevel;
        String text = paragraph.getText();
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        String styleId = paragraph.getStyleID();
        if (styleId != null && (headingLevel = this.extractHeadingLevel(styleId)) > 0 && headingLevel <= 6) {
            String markdownHeading = "#".repeat(headingLevel) + " " + text.trim();
            logger.debug("Word Heading{} \u2192 Markdown: {}", (Object)headingLevel, (Object)markdownHeading);
            return markdownHeading;
        }
        String listPrefix = this.extractListPrefix(paragraph);
        if (listPrefix != null) {
            return listPrefix + text.trim();
        }
        return text.trim();
    }

    private String extractListPrefix(XWPFParagraph paragraph) {
        String cleanNum;
        String indent;
        if (paragraph.getNumID() == null) {
            return null;
        }
        String numLevelText = paragraph.getNumLevelText();
        int indentLevel = paragraph.getNumIlvl() != null ? paragraph.getNumIlvl().intValue() : 0;
        String string = indent = indentLevel > 0 ? "  ".repeat(indentLevel) : "";
        if (numLevelText != null && numLevelText.matches(".*\\d+.*") && !(cleanNum = numLevelText.replaceAll("[^0-9]", "")).isEmpty()) {
            return indent + cleanNum + ". ";
        }
        return indent + "- ";
    }

    private int extractHeadingLevel(String styleId) {
        if (styleId == null) {
            return 0;
        }
        String lower = styleId.toLowerCase().trim();
        for (int i = 1; i <= 6; ++i) {
            if (!lower.contains("heading" + i) && !lower.contains("heading " + i)) continue;
            return i;
        }
        if (lower.contains("heading")) {
            try {
                String numericPrefix = styleId.trim().split("[\\s.]")[0];
                int level = Integer.parseInt(numericPrefix);
                if (level >= 1 && level <= 6) {
                    return level;
                }
            }
            catch (NumberFormatException e) {
                logger.trace("Word heading level parse failed for styleId={}: {}", styleId, e.getMessage());
            }
        }
        return 0;
    }

    private String extractTable(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return "";
        }
        int maxCols = 0;
        for (XWPFTableRow row : rows) {
            maxCols = Math.max(maxCols, row.getTableCells().size());
        }
        if (maxCols == 0) {
            return "";
        }
        boolean hasHeader = this.isHeaderRow((XWPFTableRow)rows.get(0));
        StringBuilder md = new StringBuilder();
        for (int r = 0; r < rows.size(); ++r) {
            XWPFTableRow row = (XWPFTableRow)rows.get(r);
            List cells = row.getTableCells();
            StringBuilder rowText = new StringBuilder("| ");
            for (int c = 0; c < maxCols; ++c) {
                String cellText = c < cells.size() ? ((XWPFTableCell)cells.get(c)).getText().trim() : "";
                cellText = cellText.replace("|", "\\|").replace("\n", " ");
                rowText.append(cellText).append(" | ");
            }
            md.append((CharSequence)rowText).append("\n");
            if (!hasHeader || r != 0) continue;
            StringBuilder sep = new StringBuilder("|");
            for (int c = 0; c < maxCols; ++c) {
                sep.append("------|");
            }
            md.append((CharSequence)sep).append("\n");
        }
        return md.toString().trim();
    }

    private boolean isHeaderRow(XWPFTableRow row) {
        for (XWPFTableCell cell : row.getTableCells()) {
            for (XWPFParagraph para : cell.getParagraphs()) {
                for (XWPFRun run : para.getRuns()) {
                    if (!run.isBold()) continue;
                    return true;
                }
            }
        }
        return false;
    }
}
