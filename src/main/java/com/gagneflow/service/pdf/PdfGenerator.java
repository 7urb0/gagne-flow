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
        // 2026-08-18 修复: flying-saucer 要求 XHTML 严格闭合, 教案 HTML 的
        // <meta charset=...> / <br> 等 HTML5 自闭合写法会导致 SAXParseException。
        // 入口做轻量规范化: 无属性自闭合标签补 /, 带属性且未闭合的补 /。
        String xhtml = normalizeToXhtml(html);
        byte[] byArray;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ITextRenderer renderer = new ITextRenderer();
            this.addChineseFonts(renderer);
            renderer.setDocumentFromString(xhtml);
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

    /**
     * HTML5 -> XHTML 轻量规范化: flying-saucer 的 XML 解析要求严格闭合。
     * 处理 <meta ...> / <br> / <hr> / <img> / <link> / <input> 等自闭合标签:
     * 无属性 -> 补 "/", 带属性且以 ">" 结尾 -> 补 "/"。
     * 不做完整 HTML 解析, 只做正则替换, 失败时原样返回(保底)。
     */
    static String normalizeToXhtml(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        try {
            // 1. 无属性的自闭合标签: <br> / <hr> (可能带空格: <br >)
            String result = html.replaceAll("(?i)<(br|hr)(\\s+[^>]*)?>", "<$1$2/>");
            // 2. 带属性且未自闭合的空元素: <meta charset="UTF-8"> -> <meta charset="UTF-8" />
            result = result.replaceAll("(?i)<(meta|link|img|input)([^>]*?[^/])>", "<$1$2 />");
            return result;
        } catch (Exception e) {
            logger.warn("HTML 规范化失败, 原样返回: {}", e.getMessage());
            return html;
        }
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
