package com.gagneflow.dto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DocumentParseResult {
    private final String markdown;
    private int pageCount;
    private int tableCount;
    private int imageCount;
    private long parseTimeMs;
    private final List<String> warnings = new ArrayList<String>();

    public DocumentParseResult(String markdown) {
        this.markdown = markdown;
    }

    public String getMarkdown() {
        return this.markdown;
    }

    public int getPageCount() {
        return this.pageCount;
    }

    public void setPageCount(int pageCount) {
        this.pageCount = pageCount;
    }

    public int getTableCount() {
        return this.tableCount;
    }

    public void setTableCount(int tableCount) {
        this.tableCount = tableCount;
    }

    public int getImageCount() {
        return this.imageCount;
    }

    public void setImageCount(int imageCount) {
        this.imageCount = imageCount;
    }

    public long getParseTimeMs() {
        return this.parseTimeMs;
    }

    public void setParseTimeMs(long parseTimeMs) {
        this.parseTimeMs = parseTimeMs;
    }

    public List<String> getWarnings() {
        return this.warnings;
    }

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }

    public boolean hasStructuredContent() {
        return this.tableCount > 0 || this.imageCount > 0;
    }

    public Map<String, Object> toMetadataMap() {
        HashMap<String, Object> map = new HashMap<String, Object>();
        if (this.tableCount > 0) {
            map.put("tableCount", this.tableCount);
        }
        if (this.imageCount > 0) {
            map.put("imageCount", this.imageCount);
        }
        if (this.pageCount > 0) {
            map.put("pageCount", this.pageCount);
        }
        return map;
    }

    public String toString() {
        return String.format("DocumentParseResult{pages=%d, tables=%d, images=%d, warnings=%d, chars=%d, time=%dms}", this.pageCount, this.tableCount, this.imageCount, this.warnings.size(), this.markdown.length(), this.parseTimeMs);
    }
}
