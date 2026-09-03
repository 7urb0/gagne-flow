package com.gagneflow.service.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SubjectFormatLoader {
    private static final Logger logger = LoggerFactory.getLogger(SubjectFormatLoader.class);
    @Value(value="${gagneflow.subject-formats.path:lesson-plan-docs/subject-formats.json}")
    private String configPath;
    private JsonNode root;

    @PostConstruct
    public void init() {
        try {
            File file = Paths.get(this.configPath, new String[0]).toFile();
            if (!file.exists()) {
                logger.warn("\u5b66\u79d1\u683c\u5f0f\u914d\u7f6e\u6587\u4ef6\u4e0d\u5b58\u5728: {}\uff0c\u5c06\u4e0d\u4f7f\u7528\u5b66\u79d1\u5dee\u5f02\u5316", (Object)this.configPath);
                return;
            }
            this.root = new ObjectMapper().readTree(file);
            logger.info("\u5b66\u79d1\u683c\u5f0f\u914d\u7f6e\u52a0\u8f7d\u5b8c\u6210: {} \u4e2a\u5b66\u79d1", (Object)this.root.size());
        }
        catch (Exception e) {
            logger.error("\u5b66\u79d1\u683c\u5f0f\u914d\u7f6e\u52a0\u8f7d\u5931\u8d25: {}", (Object)e.getMessage());
            this.root = null;
        }
    }

    public String getDevelopmentInstructions(String subject) {
        if (this.root == null || subject == null) {
            return "";
        }
        JsonNode node = this.root.path(subject);
        if (node.isMissingNode()) {
            return "";
        }
        JsonNode di = node.path("development_instructions");
        return di.isMissingNode() ? "" : di.asText();
    }

    public String getAnalysisExtra(String subject) {
        if (this.root == null || subject == null) {
            return "";
        }
        JsonNode node = this.root.path(subject);
        if (node.isMissingNode()) {
            return "";
        }
        JsonNode ae = node.path("analysis_extra");
        return ae.isMissingNode() ? "" : ae.asText();
    }

    public String getDesignExtra(String subject) {
        if (this.root == null || subject == null) {
            return "";
        }
        JsonNode node = this.root.path(subject);
        if (node.isMissingNode()) {
            return "";
        }
        JsonNode de = node.path("design_extra");
        return de.isMissingNode() ? "" : de.asText();
    }

    public String getPlaceholder(String subject) {
        if (this.root == null || subject == null) {
            return "";
        }
        JsonNode node = this.root.path(subject);
        if (node.isMissingNode()) {
            return "";
        }
        JsonNode ph = node.path("placeholder");
        return ph.isMissingNode() ? "" : ph.asText();
    }

    public String getStageInstructions(String stage) {
        if (stage == null) {
            return "";
        }
        return switch (stage) {
            case "\u5c0f\u5b66" -> "- **\u6559\u5b66\u8bed\u8a00**\uff1a\u7b80\u5355\u6613\u61c2\uff0c\u591a\u7528\u6bd4\u55bb\u548c\u6e38\u620f\u5316\u8868\u8fbe\u3002\n- **\u8bfe\u5802\u8282\u594f**\uff1a\u6bcf\u73af\u8282\u63a7\u5236\u5728 5-8 \u5206\u949f\uff0c\u4fdd\u6301\u5b66\u751f\u6ce8\u610f\u529b\u3002\n- **\u677f\u4e66\u98ce\u683c**\uff1a\u4f7f\u7528\u5927\u91cf emoji \u56fe\u6807\u589e\u5f3a\u89c6\u89c9\u5438\u5f15\u529b\uff0c\u964d\u4f4e\u6587\u5b57\u5bc6\u5ea6\u3002\n";
            case "\u521d\u4e2d" -> "- **\u6559\u5b66\u65b9\u6cd5**\uff1a\u5f15\u5165\u5c0f\u7ec4\u5408\u4f5c\u3001\u63a2\u7a76\u5f0f\u5b66\u4e60\u65b9\u6cd5\uff0c\u9f13\u52b1\u5b66\u751f\u81ea\u4e3b\u53d1\u73b0\u3002\n- **\u7406\u8bba\u57fa\u7840**\uff1a\u9002\u5f53\u5f15\u7528\u6559\u80b2\u7406\u8bba\uff08\u6700\u8fd1\u53d1\u5c55\u533a\u3001\u5efa\u6784\u4e3b\u4e49\uff09\u652f\u6491\u6559\u5b66\u8bbe\u8ba1\u3002\n- **\u677f\u4e66\u98ce\u683c**\uff1a\u7ed3\u6784\u6e05\u6670\uff0c\u5173\u952e\u8bcd\u7a81\u51fa\uff0c\u4f7f\u7528\u601d\u7ef4\u5bfc\u56fe\u8f85\u52a9\u3002\n";
            case "\u9ad8\u4e2d" -> "- **\u6559\u5b66\u8bed\u8a00**\uff1a\u4f7f\u7528\u5b66\u672f\u5316\u8bed\u8a00\uff0c\u6ce8\u91cd\u903b\u8f91\u4e25\u5bc6\u6027\u548c\u7406\u8bba\u6df1\u5ea6\u3002\n- **\u5185\u5bb9\u6df1\u5ea6**\uff1a\u5305\u542b\u601d\u7ef4\u5bfc\u56fe\u3001\u9ad8\u8003\u8003\u70b9\u6807\u6ce8\u3001\u8de8\u5b66\u79d1\u94fe\u63a5\u3002\n- **\u8bad\u7ec3\u5f3a\u5ea6**\uff1a\u8bbe\u8ba1\u5206\u5c42\u7ec3\u4e60\uff0c\u5305\u542b\u771f\u9898\u8bad\u7ec3\u548c\u53d8\u5f0f\u62d3\u5c55\u3002\n";
            default -> "";
        };
    }

    public boolean isLoaded() {
        return this.root != null;
    }
}
