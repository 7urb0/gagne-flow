package com.gagneflow.service.document;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.gagneflow.config.DocumentChunkConfig;
import com.gagneflow.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DocumentChunkService {
    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);
    // 修复BUG 6: 预编译正则，避免每次 chunkDocument 调用时重新编译
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    @Autowired
    private DocumentChunkConfig chunkConfig;

    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        ArrayList<DocumentChunk> chunks = new ArrayList<DocumentChunk>();
        if (content == null || content.trim().isEmpty()) {
            logger.warn("\u6587\u6863\u5185\u5bb9\u4e3a\u7a7a: {}", (Object)filePath);
            return chunks;
        }
        content = this.normalize(content);
        List<Section> sections = this.splitByHeadings(content);
        int globalChunkIndex = 0;
        for (Section section : sections) {
            List<DocumentChunk> sectionChunks = this.chunkSection(section, globalChunkIndex);
            chunks.addAll(sectionChunks);
            globalChunkIndex += sectionChunks.size();
        }
        logger.info("\u6587\u6863\u5206\u7247\u5b8c\u6210: {} -> {} \u4e2a\u5206\u7247", (Object)filePath, (Object)chunks.size());
        return chunks;
    }

    private List<Section> splitByHeadings(String content) {
        String sectionContent;
        String breadcrumb;
        ArrayList<Section> sections = new ArrayList<Section>();
        // 修复BUG 6: 使用预编译的正则常量
        Matcher matcher = HEADING_PATTERN.matcher(content);
        int lastEnd = 0;
        String currentTitle = null;
        int currentLevel = 0;
        ArrayDeque<String> breadcrumbStack = new ArrayDeque<String>();
        while (matcher.find()) {
            String sectionContent2;
            if (lastEnd < matcher.start() && !(sectionContent2 = content.substring(lastEnd, matcher.start()).trim()).isEmpty()) {
                breadcrumb = String.join((CharSequence)" > ", breadcrumbStack);
                sections.add(new Section(currentTitle, sectionContent2, lastEnd, currentLevel, breadcrumb));
            }
            int level = matcher.group(1).length();
            String title = matcher.group(2).trim();
            while (!breadcrumbStack.isEmpty() && level <= currentLevel) {
                breadcrumbStack.pollLast();
                --currentLevel;
            }
            breadcrumbStack.addLast(title);
            currentLevel = level;
            currentTitle = title;
            lastEnd = matcher.start();
        }
        if (lastEnd < content.length() && !(sectionContent = content.substring(lastEnd).trim()).isEmpty()) {
            breadcrumb = String.join((CharSequence)" > ", breadcrumbStack);
            sections.add(new Section(currentTitle, sectionContent, lastEnd, currentLevel, breadcrumb));
        }
        if (sections.isEmpty()) {
            sections.add(new Section(null, content, 0, 0, ""));
        }
        return sections;
    }

    private List<DocumentChunk> chunkSection(Section section, int startChunkIndex) {
        String sectionTitle = null;
        ArrayList<DocumentChunk> chunks = new ArrayList<DocumentChunk>();
        String content = section.content;
        Object object = section.breadcrumb != null && !section.breadcrumb.isEmpty() ? section.breadcrumb + " > " + (section.title != null ? section.title : "") : (sectionTitle = section.title);
        if (content.length() <= this.chunkConfig.getMaxSize()) {
            DocumentChunk chunk = new DocumentChunk(content, section.startIndex, section.startIndex + content.length(), startChunkIndex);
            chunk.setTitle(sectionTitle);
            chunks.add(chunk);
            return chunks;
        }
        List<String> paragraphs = this.splitByParagraphs(content);
        StringBuilder currentChunk = new StringBuilder();
        int currentStartIndex = section.startIndex;
        int chunkIndex = startChunkIndex;
        for (String paragraph : paragraphs) {
            // 修复: 单个段落超长(如 strip HTML 后无空行的整篇文本)时按 maxSize 硬切,
            // 防止生成超过 Milvus content 字段上限(8192)的 chunk
            while (paragraph.length() > this.chunkConfig.getMaxSize()) {
                // 先 flush 当前累积内容
                if (!currentChunk.isEmpty()) {
                    String flushContent = currentChunk.toString().trim();
                    DocumentChunk flushChunk = new DocumentChunk(flushContent, currentStartIndex,
                            currentStartIndex + flushContent.length(), chunkIndex++);
                    flushChunk.setTitle(sectionTitle);
                    chunks.add(flushChunk);
                    currentChunk = new StringBuilder();
                    currentStartIndex += flushContent.length();
                }
                // 硬切超长段落为独立 chunk
                String piece = paragraph.substring(0, this.chunkConfig.getMaxSize());
                DocumentChunk pieceChunk = new DocumentChunk(piece, currentStartIndex,
                        currentStartIndex + piece.length(), chunkIndex++);
                pieceChunk.setTitle(sectionTitle);
                chunks.add(pieceChunk);
                currentStartIndex += piece.length();
                paragraph = paragraph.substring(this.chunkConfig.getMaxSize());
            }
            String[] lines;
            boolean lastLineTable;
            boolean isTableRow = paragraph.trim().matches("^\\|.+\\|$");
            if (isTableRow && currentChunk.length() > 0 && (lastLineTable = (lines = currentChunk.toString().split("\n"))[lines.length - 1].trim().matches("^\\|.+\\|$")) && (double)(currentChunk.length() + paragraph.length()) <= (double)this.chunkConfig.getMaxSize() * 1.5) {
                currentChunk.append(paragraph).append("\n\n");
                continue;
            }
            if (!currentChunk.isEmpty() && currentChunk.length() + paragraph.length() > this.chunkConfig.getMaxSize()) {
                String chunkContent = currentChunk.toString().trim();
                DocumentChunk chunk = new DocumentChunk(chunkContent, currentStartIndex, currentStartIndex + chunkContent.length(), chunkIndex++);
                chunk.setTitle(sectionTitle);
                chunks.add(chunk);
                String overlap = this.getOverlapText(chunkContent);
                currentChunk = new StringBuilder(overlap);
                currentStartIndex = currentStartIndex + chunkContent.length() - overlap.length();
            }
            currentChunk.append(paragraph).append("\n\n");
        }
        if (!currentChunk.isEmpty()) {
            String chunkContent = currentChunk.toString().trim();
            DocumentChunk chunk = new DocumentChunk(chunkContent, currentStartIndex, currentStartIndex + chunkContent.length(), chunkIndex);
            chunk.setTitle(sectionTitle);
            chunks.add(chunk);
        }
        return chunks;
    }

    private List<String> splitByParagraphs(String content) {
        String[] parts;
        ArrayList<String> paragraphs = new ArrayList<String>();
        for (String part : parts = content.split("\n\n+")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            paragraphs.add(trimmed);
        }
        return paragraphs;
    }

    private String getOverlapText(String text) {
        int overlapSize = Math.min(this.chunkConfig.getOverlap(), text.length());
        if (overlapSize <= 0) {
            return "";
        }
        String overlap = text.substring(text.length() - overlapSize);
        int lastSentenceEnd = Math.max(overlap.lastIndexOf(12290), Math.max(overlap.lastIndexOf(65311), overlap.lastIndexOf(65281)));
        if (lastSentenceEnd > overlapSize / 2) {
            return overlap.substring(lastSentenceEnd + 1).trim();
        }
        return overlap.trim();
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace("\r", "\n").replaceAll("[\u200b\u200c\u200d\ufeff]", "").replaceAll("(?m)^[ ]{1,3}(?=[^ \t])", "").replaceAll("(?m)^\t(?=[^\t])", "").replaceAll("[ \t]+$", "").replaceAll("\n{3,}", "\n\n").trim();
    }

    private static class Section {
        String title;
        String content;
        int startIndex;
        int headingLevel;
        String breadcrumb;

        Section(String title, String content, int startIndex, int headingLevel, String breadcrumb) {
            this.title = title;
            this.content = content;
            this.startIndex = startIndex;
            this.headingLevel = headingLevel;
            this.breadcrumb = breadcrumb;
        }
    }
}
