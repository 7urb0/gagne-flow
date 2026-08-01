package com.gagneflow.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import com.gagneflow.service.document.K12CurriculumLoader;
import com.gagneflow.service.vector.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class InternalDocsTools {
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";
    private final VectorSearchService vectorSearchService;
    private final K12CurriculumLoader k12Loader;
    @Value(value="${rag.top-k:3}")
    private int topK = 3;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public InternalDocsTools(VectorSearchService vectorSearchService, K12CurriculumLoader k12Loader) {
        this.vectorSearchService = vectorSearchService;
        this.k12Loader = k12Loader;
    }

    @Tool(description="Use this tool to search internal documentation and knowledge base for relevant information. It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. This is useful when you need to understand internal procedures, best practices, or step-by-step guides stored in the company's documentation.")
    public String queryInternalDocs(@ToolParam(description="Search query describing what information you are looking for") String query) {
        try {
            Long userId = this.getCurrentUserId();
            List<VectorSearchService.SearchResult> searchResults = this.vectorSearchService.searchWithRerank(query, userId);
            if (searchResults.isEmpty()) {
                String fallback = this.k12Fallback(query);
                if (!fallback.isEmpty()) {
                    return "{\"status\": \"k12_fallback\", \"message\": \"\u5411\u91cf\u5e93\u65e0\u7ed3\u679c\uff0c\u4ece\u8bfe\u7a0b\u6807\u51c6\u67e5\u8be2\", \"data\": " + this.objectMapper.writeValueAsString((Object)fallback) + "}";
                }
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found.\"}";
            }
            String resultJson = this.objectMapper.writeValueAsString(searchResults);
            return resultJson;
        }
        catch (Exception e) {
            logger.error("[\u5de5\u5177\u9519\u8bef] queryInternalDocs \u6267\u884c\u5931\u8d25", (Throwable)e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}", e.getMessage());
        }
    }

    private String k12Fallback(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        String stage = query.contains("\u5c0f\u5b66") ? "\u5c0f\u5b66" : (query.contains("\u521d\u4e2d") ? "\u521d\u4e2d" : (query.contains("\u9ad8\u4e2d") ? "\u9ad8\u4e2d" : null));
        String subject = null;
        for (String s : new String[]{"\u8bed\u6587", "\u6570\u5b66", "\u82f1\u8bed", "\u7269\u7406", "\u5316\u5b66", "\u751f\u7269", "\u5386\u53f2", "\u5730\u7406", "\u653f\u6cbb", "\u79d1\u5b66"}) {
            if (!query.contains(s)) continue;
            subject = s;
            break;
        }
        if (stage == null && subject == null) {
            return "";
        }
        return this.k12Loader.lookup(stage, null, subject);
    }

    private Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Long) {
                return (Long)auth.getPrincipal();
            }
        }
        catch (Exception e) {
            logger.trace("\u65e0\u6cd5\u83b7\u53d6\u5f53\u524d\u7528\u6237: {}", (Object)e.getMessage());
        }
        return 0L;
    }

    @Tool(description="\u67e5\u8be2 K12 \u6559\u80b2\u8bfe\u7a0b\u6807\u51c6\u6570\u636e\u3002\u6309\u5b66\u6bb5\uff08\u5c0f\u5b66/\u521d\u4e2d/\u9ad8\u4e2d\uff09\u3001\u5e74\u7ea7\uff08\u5982\u4e09\u5e74\u7ea7\uff09\u3001\u5b66\u79d1\uff08\u8bed\u6587/\u6570\u5b66/\u82f1\u8bed\uff09\u67e5\u627e\u7ae0\u8282\u548c\u77e5\u8bc6\u70b9\u3002\u53c2\u6570\u53ef\u4e0d\u586b\uff0c\u4e0d\u586b\u8868\u793a\u4e0d\u9650\u5236\u8be5\u6761\u4ef6\u3002")
    public String queryK12Curriculum(@ToolParam(description="\u5b66\u6bb5\uff1a\u5c0f\u5b66\u3001\u521d\u4e2d\u3001\u9ad8\u4e2d\u3002\u4e0d\u586b\u67e5\u5168\u90e8") String stage, @ToolParam(description="\u5e74\u7ea7\uff0c\u5982'\u4e09\u5e74\u7ea7'\u3001'\u4e03\u5e74\u7ea7'\u3002\u4e0d\u586b\u67e5\u5168\u90e8") String grade, @ToolParam(description="\u5b66\u79d1\uff1a\u8bed\u6587\u3001\u6570\u5b66\u3001\u82f1\u8bed\u3002\u4e0d\u586b\u67e5\u5168\u90e8") String subject) {
        try {
            String result = this.k12Loader.lookup(stage != null && !stage.isEmpty() ? stage : null, grade != null && !grade.isEmpty() ? grade : null, subject != null && !subject.isEmpty() ? subject : null);
            return result;
        }
        catch (Exception e) {
            logger.error("[\u5de5\u5177\u9519\u8bef] queryK12Curriculum \u6267\u884c\u5931\u8d25", (Throwable)e);
            return "\u67e5\u8be2 K12 \u8bfe\u7a0b\u6807\u51c6\u5931\u8d25: " + e.getMessage();
        }
    }
}
