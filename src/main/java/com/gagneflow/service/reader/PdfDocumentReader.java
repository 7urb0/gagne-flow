package com.gagneflow.service.reader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PdfDocumentReader
implements DocumentReader {
    private static final Logger logger = LoggerFactory.getLogger(PdfDocumentReader.class);
    private static final List<String> SUPPORTED_EXTENSIONS = List.of("pdf");

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public String readText(Path filePath) throws IOException {
        StringBuilder fullText = new StringBuilder();
        int totalImages = 0;
        try (PDDocument document = Loader.loadPDF((File)filePath.toFile());){
            int totalPages = document.getNumberOfPages();
            logger.info("PDF \u5171 {} \u9875: {}", (Object)totalPages, (Object)filePath.getFileName());
            for (int page = 1; page <= totalPages; ++page) {
                String pageText;
                PDPage pdPage = document.getPage(page - 1);
                int pageImages = this.countImages(pdPage);
                totalImages += pageImages;
                if (pageImages > 0) {
                    fullText.append("[\u672c\u9875\u542b ").append(pageImages).append(" \u5f20\u56fe\u7247]\n");
                }
                if ((pageText = this.extractPageText(document, page)) != null && !pageText.trim().isEmpty()) {
                    fullText.append(pageText.trim());
                }
                if (page >= totalPages) continue;
                fullText.append("\n\n--- PAGE ").append(page + 1).append(" ---\n\n");
            }
            logger.info("PDF \u89e3\u6790\u5b8c\u6210: {} -> {} \u5b57\u7b26, {} \u9875, {} \u56fe\u7247", new Object[]{filePath.getFileName(), fullText.length(), totalPages, totalImages});
            if (fullText.isEmpty()) {
                logger.warn("PDF \u6587\u672c\u4e3a\u7a7a: {}\u3002\u53ef\u80fd\u4e3a\u626b\u63cf\u7248\uff08\u9700 OCR\uff09\u6216\u52a0\u5bc6\u6587\u6863", (Object)filePath.getFileName());
                String string = "[\u7cfb\u7edf\u63d0\u793a: \u6b64 PDF \u4e3a\u626b\u63cf\u7248\u6216\u56fe\u7247\u578b\u6587\u6863\uff0c\u65e0\u6cd5\u76f4\u63a5\u63d0\u53d6\u6587\u5b57\u3002\u8bf7\u4f7f\u7528 OCR \u5de5\u5177\u8bc6\u522b\u540e\u518d\u4e0a\u4f20\uff0c\u6216\u8f6c\u6362\u4e3a\u53ef\u641c\u7d22 PDF \u683c\u5f0f\u3002]";
                return string;
            }
        }
        return fullText.toString();
    }

    private String extractPageText(PDDocument document, int pageNum) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageNum);
        stripper.setEndPage(pageNum);
        stripper.setSortByPosition(true);
        stripper.setAddMoreFormatting(false);
        stripper.setParagraphStart("\n");
        stripper.setParagraphEnd("\n");
        stripper.setLineSeparator("\n");
        stripper.setWordSeparator(" ");
        return stripper.getText(document);
    }

    private int countImages(PDPage page) {
        try {
            PDResources resources = page.getResources();
            if (resources == null) {
                return 0;
            }
            int count = 0;
            for (COSName name : resources.getXObjectNames()) {
                if (!resources.isImageXObject(name)) continue;
                ++count;
            }
            if (count > 0) {
                logger.debug("\u9875\u9762\u68c0\u6d4b\u5230 {} \u5f20\u56fe\u7247", (Object)count);
            }
            return count;
        }
        catch (Exception e) {
            return 0;
        }
    }
}
