package com.gagneflow.service.reader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MarkdownDocumentReader
implements DocumentReader {
    private static final Logger logger = LoggerFactory.getLogger(MarkdownDocumentReader.class);
    private static final List<String> SUPPORTED_EXTENSIONS = List.of("md");

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public String readText(Path filePath) throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        logger.debug("Markdown \u8bfb\u53d6\u5b8c\u6210: {} -> {} \u5b57\u7b26", (Object)filePath.getFileName(), (Object)content.length());
        return content;
    }
}
