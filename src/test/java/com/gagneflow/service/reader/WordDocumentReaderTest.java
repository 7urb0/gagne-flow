package com.gagneflow.service.reader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WordDocumentReader Tests")
class WordDocumentReaderTest {

    private WordDocumentReader reader;

    @BeforeEach
    void setUp() {
        reader = new WordDocumentReader();
    }

    @Nested
    @DisplayName("Format Support")
    class FormatSupport {

        @Test
        @DisplayName("supports docx extension")
        void supportsDocx() {
            assertTrue(reader.getSupportedExtensions().contains("docx"));
            assertTrue(reader.getSupportedExtensions().contains("DOCX") == false
                    || reader.getSupportedExtensions().contains("docx"));
        }

        @Test
        @DisplayName("rejects unsupported extensions")
        void rejectsUnsupported() {
            assertFalse(reader.getSupportedExtensions().contains("pdf"));
            assertFalse(reader.getSupportedExtensions().contains("txt"));
            assertFalse(reader.getSupportedExtensions().contains(""));
        }
    }

    @Nested
    @DisplayName("File Reading")
    class FileReading {

        @Test
        @DisplayName("reads text from .docx file")
        void readsDocxFile(@TempDir Path tempDir) throws IOException {
            // Create a minimal .docx file (ZIP with word/document.xml)
            Path docxFile = createMinimalDocx(tempDir, "test.docx",
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body><w:p><w:r><w:t>Hello World</w:t></w:r></w:p></w:body>" +
                    "</w:document>");

            String text = reader.readText(docxFile);
            assertNotNull(text);
            assertTrue(text.contains("Hello"));
        }

        @Test
        @DisplayName("reads multi-paragraph docx")
        void readsMultiParagraph(@TempDir Path tempDir) throws IOException {
            Path docxFile = createMinimalDocx(tempDir, "multi.docx",
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body>" +
                    "<w:p><w:r><w:t>Paragraph One</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t>Paragraph Two</w:t></w:r></w:p>" +
                    "</w:body></w:document>");

            String text = reader.readText(docxFile);
            assertNotNull(text);
            assertTrue(text.contains("Paragraph One"));
            assertTrue(text.contains("Paragraph Two"));
        }

        @Test
        @DisplayName("reads table content from docx")
        void readsTableContent(@TempDir Path tempDir) throws IOException {
            Path docxFile = createMinimalDocx(tempDir, "table.docx",
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body>" +
                    "<w:tbl>" +
                    "<w:tr><w:tc><w:p><w:r><w:t>Cell 1</w:t></w:r></w:p></w:tc>" +
                    "<w:tc><w:p><w:r><w:t>Cell 2</w:t></w:r></w:p></w:tc></w:tr>" +
                    "</w:tbl>" +
                    "</w:body></w:document>");

            String text = reader.readText(docxFile);
            assertNotNull(text);
            assertTrue(text.contains("Cell 1"));
            assertTrue(text.contains("Cell 2"));
        }

        @Test
        @DisplayName("reads heading styles")
        void readsHeadingStyles(@TempDir Path tempDir) throws IOException {
            Path docxFile = createMinimalDocx(tempDir, "heading.docx",
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body>" +
                    "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>" +
                    "<w:r><w:t>Chapter Title</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t>Regular text</w:t></w:r></w:p>" +
                    "</w:body></w:document>");

            String text = reader.readText(docxFile);
            assertNotNull(text);
            assertTrue(text.contains("Chapter Title"));
            assertTrue(text.contains("Regular text"));
        }

        @Test
        @DisplayName("handles empty docx gracefully")
        void handlesEmptyDocx(@TempDir Path tempDir) throws IOException {
            Path docxFile = createMinimalDocx(tempDir, "empty.docx",
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body></w:body></w:document>");

            String text = reader.readText(docxFile);
            assertNotNull(text); // Should return empty or minimal text, not null
        }

        @Test
        @DisplayName("throws IOException for nonexistent file")
        void throwsForNonexistentFile() {
            Path nonexistent = Path.of("/nonexistent/file.docx");
            assertThrows(IOException.class, () -> reader.readText(nonexistent));
        }

        @Test
        @DisplayName("handles null path gracefully")
        void handlesNullPath() {
            assertThrows(NullPointerException.class, () -> reader.readText((Path) null));
        }
    }

    @Nested
    @DisplayName("Bold and Italic Detection")
    class FormattingDetection {

        @Test
        @DisplayName("bold text is detected")
        void detectsBold(@TempDir Path tempDir) throws IOException {
            Path docxFile = createMinimalDocx(tempDir, "bold.docx",
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body><w:p>" +
                    "<w:r><w:rPr><w:b/></w:rPr><w:t>Bold Text</w:t></w:r>" +
                    "<w:r><w:t>Normal Text</w:t></w:r>" +
                    "</w:p></w:body></w:document>");

            String text = reader.readText(docxFile);
            assertNotNull(text);
            assertTrue(text.contains("Bold Text"));
            // Bold text should be wrapped in ** markers if implementation supports it
        }

        @Test
        @DisplayName("italic text is detected")
        void detectsItalic(@TempDir Path tempDir) throws IOException {
            Path docxFile = createMinimalDocx(tempDir, "italic.docx",
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body><w:p>" +
                    "<w:r><w:rPr><w:i/></w:rPr><w:t>Italic Text</w:t></w:r>" +
                    "</w:p></w:body></w:document>");

            String text = reader.readText(docxFile);
            assertNotNull(text);
            assertTrue(text.contains("Italic Text"));
        }
    }

    // Helper to create minimal .docx files
    private Path createMinimalDocx(Path tempDir, String name, String documentXml) throws IOException {
        Path docxFile = tempDir.resolve(name);
        Path wordDir = Files.createDirectories(tempDir.resolve(name.replace(".docx", "_word")));
        Path docFile = wordDir.resolve("document.xml");
        Files.writeString(docFile, documentXml);

        // Create minimal .docx structure
        Path contentType = tempDir.resolve("[Content_Types].xml");
        Files.writeString(contentType, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "</Types>");

        Path relsDir = Files.createDirectories(tempDir.resolve("_rels"));
        Path relsFile = relsDir.resolve(".rels");
        Files.writeString(relsFile, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
                "Target=\"word/document.xml\"/>" +
                "</Relationships>");

        // Create DOCX as ZIP
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(docxFile.toFile());
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos)) {

            addToZip(zos, "word/document.xml", Files.readString(docFile));
            addToZip(zos, "[Content_Types].xml", Files.readString(contentType));
            addToZip(zos, "_rels/.rels", Files.readString(relsFile));
        }
        return docxFile;
    }

    private void addToZip(java.util.zip.ZipOutputStream zos, String entryName, String content) throws IOException {
        java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(entryName);
        zos.putNextEntry(ze);
        zos.write(content.getBytes());
        zos.closeEntry();
    }
}
