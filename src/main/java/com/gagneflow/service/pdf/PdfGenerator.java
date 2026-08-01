package com.gagneflow.service.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.xhtmlrenderer.pdf.ITextRenderer;

@Component
public class PdfGenerator {
    private static final Logger logger = LoggerFactory.getLogger(PdfGenerator.class);

    @Value("${gagneflow.pdf.font-path:}")
    private String configuredFontPath;

    public byte[] htmlToPdf(String html) {
        byte[] byArray;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ITextRenderer renderer = new ITextRenderer();
            this.addChineseFonts(renderer);
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF((OutputStream)baos);
            byte[] pdf = baos.toByteArray();
            logger.info("PDF \u751f\u6210\u6210\u529f: {} \u5b57\u8282", (Object)pdf.length);
            byArray = pdf;
        }
        catch (Throwable throwable) {
            try {
                try {
                    try { baos.close(); } catch (IOException ignored) {}
                }
                catch (Throwable throwable2) {
                    throwable.addSuppressed(throwable2);
                }
                throw throwable;
            }
            catch (Exception e) {
                logger.error("PDF \u751f\u6210\u5931\u8d25", (Throwable)e);
                throw new RuntimeException("PDF \u751f\u6210\u5931\u8d25: " + e.getMessage(), e);
            }
        }
        try { baos.close(); } catch (IOException e) {}
        return byArray;
    }

    private void addChineseFonts(ITextRenderer renderer) {
        // 优先使用配置字体路径
        if (this.configuredFontPath != null && !this.configuredFontPath.isEmpty()) {
            try {
                renderer.getFontResolver().addFont(this.configuredFontPath, "Identity-H", true);
                logger.info("PDF 字体加载成功: {}", this.configuredFontPath);
                return;
            } catch (Exception e) {
                logger.warn("配置字体加载失败: {}, 回退硬编码路径", this.configuredFontPath, e);
            }
        }
        String[][] fontPaths = new String[][]{{"C:/Windows/Fonts/simsun.ttc,0", "SimSun"}, {"C:/Windows/Fonts/simhei.ttf", "SimHei"}, {"/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", "WenQuanYi"}, {"/System/Library/Fonts/PingFang.ttc", "PingFang"}};
        for (String[] fp : fontPaths) {
            try {
                renderer.getFontResolver().addFont(fp[0], "Identity-H", true);
                return;
            }
            catch (Exception exception) {
            }
        }
        logger.warn("\u672a\u627e\u5230\u4e2d\u6587\u5b57\u4f53\u6587\u4ef6\uff0cPDF \u4e2d\u6587\u6e32\u67d3\u53ef\u80fd\u5f02\u5e38");
    }
}
