package com.gagneflow.service.reader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentReaderFactoryTest {

    private DocumentReaderFactory factory;

    @BeforeEach
    void setUp() {
        // 使用匿名实现创建 mock reader
        DocumentReader wordReader = new DocumentReader() {
            @Override
            public List<String> getSupportedExtensions() {
                return List.of("doc", "docx");
            }

            @Override
            public String readText(java.nio.file.Path path) {
                return "word content";
            }
        };

        DocumentReader textReader = new DocumentReader() {
            @Override
            public List<String> getSupportedExtensions() {
                return List.of("txt", "md");
            }

            @Override
            public String readText(java.nio.file.Path path) {
                return "text content";
            }
        };

        factory = new DocumentReaderFactory(List.of(wordReader, textReader));
    }

    @Test
    void getReader_shouldReturnWordReader_forDocExtension() {
        DocumentReader reader = factory.getReader("doc");
        assertNotNull(reader, "doc 扩展名应返回一个 reader");
        assertTrue(reader.getSupportedExtensions().contains("doc"));
    }

    @Test
    void getReader_shouldReturnWordReader_forDocxExtension() {
        DocumentReader reader = factory.getReader("docx");
        assertNotNull(reader, "docx 扩展名应返回一个 reader");
        assertTrue(reader.getSupportedExtensions().contains("docx"));
    }

    @Test
    void getReader_shouldReturnTextReader_forTxtExtension() {
        DocumentReader reader = factory.getReader("txt");
        assertNotNull(reader, "txt 扩展名应返回一个 reader");
        assertTrue(reader.getSupportedExtensions().contains("txt"));
    }

    @Test
    void getReader_shouldReturnTextReader_forMdExtension() {
        DocumentReader reader = factory.getReader("md");
        assertNotNull(reader, "md 扩展名应返回一个 reader");
        assertTrue(reader.getSupportedExtensions().contains("md"));
    }

    @Test
    void getReader_shouldReturnNull_forUnsupportedExtension() {
        DocumentReader reader = factory.getReader("xyz");
        assertNull(reader, "不支持的扩展名应返回 null");
    }

    @Test
    void getReader_shouldReturnNull_forNullInput() {
        assertNull(factory.getReader(null));
    }

    @Test
    void getReader_shouldReturnNull_forEmptyInput() {
        assertNull(factory.getReader(""));
    }

    @Test
    void getReader_shouldHandleCaseInsensitive() {
        DocumentReader lower = factory.getReader("docx");
        DocumentReader upper = factory.getReader("DOCX");

        assertNotNull(lower);
        assertNotNull(upper);
        assertSame(lower, upper, "大小写不同应返回同一个 reader");
    }

    @Test
    void isSupported_shouldReturnTrueForValidExtensions() {
        assertTrue(factory.isSupported("doc"));
        assertTrue(factory.isSupported("md"));
        assertFalse(factory.isSupported("xyz"));
    }

    @Test
    void getSupportedExtensions_shouldReturnAllKeys() {
        var extensions = factory.getSupportedExtensions();
        assertTrue(extensions.contains("doc"));
        assertTrue(extensions.contains("docx"));
        assertTrue(extensions.contains("txt"));
        assertTrue(extensions.contains("md"));
    }

    @Test
    void factoryWithEmptyList_shouldSupportNothing() {
        DocumentReaderFactory empty = new DocumentReaderFactory(Collections.emptyList());
        assertFalse(empty.isSupported("doc"));
        assertNull(empty.getReader("doc"));
        assertTrue(empty.getSupportedExtensions().isEmpty());
    }
}
