package com.gagneflow.service.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PdfGenerator HTML→PDF 转换测试。
 * 覆盖: 正常转换、空输入、非法 HTML、输出结构验证。
 */
@DisplayName("PdfGenerator HTML→PDF 转换测试")
class PdfGeneratorTest {

    private final PdfGenerator pdfGenerator = new PdfGenerator();

    @Nested
    @DisplayName("htmlToPdf 正常路径")
    class HtmlToPdfNormalTests {

        @Test
        @DisplayName("简单 HTML 生成有效 PDF（%PDF 文件头）")
        void simpleHtml_producesPdfHeader() {
            String html = "<html><body><h1>标题</h1><p>段落内容</p></body></html>";
            byte[] pdf = pdfGenerator.htmlToPdf(html);

            assertNotNull(pdf);
            assertTrue(pdf.length > 0, "PDF 输出不应为空");
            // PDF 文件以 %PDF- 开头
            String header = new String(pdf, 0, Math.min(8, pdf.length), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(header.startsWith("%PDF"), "输出应为 PDF 格式，实际头: " + header);
        }

        @Test
        @DisplayName("完整 HTML 文档（含 head/样式）生成有效 PDF")
        void fullHtmlDocument_producesPdf() {
            String html = "<!DOCTYPE html><html><head><title>测试</title>"
                    + "<style>body{font-family:SimSun;font-size:12pt}</style></head>"
                    + "<body><table border='1'><tr><td>单元格A</td><td>单元格B</td></tr></table>"
                    + "<ul><li>项目1</li><li>项目2</li></ul></body></html>";
            byte[] pdf = pdfGenerator.htmlToPdf(html);

            assertNotNull(pdf);
            assertTrue(pdf.length > 100, "含样式与表格的 HTML 应生成较大 PDF");
            String header = new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertEquals("%PDF-", header);
        }

        @Test
        @DisplayName("空 body HTML 不抛异常")
        void emptyBodyHtml_noException() {
            byte[] pdf = assertDoesNotThrow(() -> pdfGenerator.htmlToPdf("<html><body></body></html>"));
            assertNotNull(pdf);
        }
    }

    @Nested
    @DisplayName("htmlToPdf 边界与异常")
    class HtmlToPdfEdgeTests {

        @Test
        @DisplayName("null 输入抛 RuntimeException")
        void nullInput_throwsRuntimeException() {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> pdfGenerator.htmlToPdf(null));
            assertNotNull(ex.getMessage());
        }

        @Test
        @DisplayName("非法 HTML（未闭合标签）不抛异常或抛可接受异常")
        void malformedHtml_tolerableBehavior() {
            // 未闭合的标签可能渲染失败，也可能被解析器容忍——两者都应被外层处理
            try {
                byte[] pdf = pdfGenerator.htmlToPdf("<html><body><div>未闭合");
                assertNotNull(pdf, "解析器容忍时返回非空输出");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("PDF 生成失败"), "异常应包装为 PDF 生成失败");
            }
        }
    }
}
