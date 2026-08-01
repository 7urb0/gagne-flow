package com.gagneflow.service.reader;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DocumentReaderFactory {
    private static final Logger logger = LoggerFactory.getLogger(DocumentReaderFactory.class);
    private final Map<String, DocumentReader> readerMap;

    public DocumentReaderFactory(List<DocumentReader> readers) {
        HashMap<String, DocumentReader> map = new HashMap<String, DocumentReader>();
        for (DocumentReader reader : readers) {
            for (String extension : reader.getSupportedExtensions()) {
                String key = extension.toLowerCase();
                DocumentReader existing = map.put(key, reader);
                if (existing != null) {
                    logger.warn("\u6269\u5c55\u540d '{}' \u88ab\u591a\u4e2a Reader \u6ce8\u518c: {} \u548c {}\uff0c\u540e\u6ce8\u518c\u7684\u8986\u76d6\u524d\u8005", new Object[]{key, existing.getClass().getSimpleName(), reader.getClass().getSimpleName()});
                    continue;
                }
                logger.info("\u6ce8\u518c\u6587\u6863\u8bfb\u53d6\u5668: .{} -> {}", (Object)key, (Object)reader.getClass().getSimpleName());
            }
        }
        this.readerMap = Collections.unmodifiableMap(map);
        logger.info("DocumentReaderFactory \u521d\u59cb\u5316\u5b8c\u6210\uff0c\u5df2\u6ce8\u518c {} \u79cd\u6587\u4ef6\u7c7b\u578b: {}", (Object)this.readerMap.size(), this.readerMap.keySet());
    }

    public DocumentReader getReader(String extension) {
        if (extension == null || extension.isEmpty()) {
            return null;
        }
        return this.readerMap.get(extension.toLowerCase());
    }

    public boolean isSupported(String extension) {
        return this.readerMap.containsKey(extension.toLowerCase());
    }

    public Set<String> getSupportedExtensions() {
        return this.readerMap.keySet();
    }
}
