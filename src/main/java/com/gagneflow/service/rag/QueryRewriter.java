package com.gagneflow.service.rag;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QueryRewriter {
    private static final Logger logger = LoggerFactory.getLogger(QueryRewriter.class);
    private static final int SHORT_QUERY_LENGTH = 10;

    @Value("${rag.query-rewrite.enabled:true}")
    private boolean enabled;

    // L4修复: LLM 查询改写开关 — 默认关闭，验证效果后再开启
    @Value("${rag.query-rewrite.llm-enabled:false}")
    private boolean llmEnabled;

    @Value("${rag.query-rewrite.llm-model:qwen-turbo}")
    private String llmModel;

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    public String rewrite(String originalQuestion, List<Map<String, String>> history) {
        if (!this.enabled || originalQuestion == null || originalQuestion.isBlank()) {
            return originalQuestion;
        }
        String question = originalQuestion.trim();

        // L4修复: LLM 改写路径 — 处理上下文依赖查询（指代词、省略主语等）
        if (this.llmEnabled && shouldUseLlm(question, history)) {
            try {
                String llmRewritten = rewriteWithLlm(question, history);
                if (llmRewritten != null && !llmRewritten.isBlank()) {
                    logger.debug("LLM 查询改写: {} → {}", originalQuestion, llmRewritten);
                    return llmRewritten;
                }
            } catch (Exception e) {
                logger.warn("LLM 查询改写失败，降级为规则改写: {}", e.getMessage());
            }
        }

        // 规则改写 (原有逻辑，作为默认路径和降级路径)
        String lastUserMsg;
        if (question.length() < SHORT_QUERY_LENGTH && history != null && !history.isEmpty()
                && (lastUserMsg = this.findLastUserMessage(history)) != null && !lastUserMsg.isBlank()) {
            question = lastUserMsg + " " + question;
        }
        String keywords = this.extractKeywords(question);
        if (!keywords.isEmpty()) {
            question = question + " " + keywords;
        }
        if (!question.equals(originalQuestion.trim())) {
            logger.debug("查询改写: {} → {}", originalQuestion, question);
        }
        return question;
    }

    /**
     * L4修复: 判断是否应使用 LLM 改写
     * 触发条件：查询包含指代词、上下文依赖词，或查询过短但历史非空
     */
    private boolean shouldUseLlm(String query, List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) return false;
        // 指代词检测
        if (query.matches(".*(上次|那个|之前|刚刚|前面|刚才|它|这个|这些|那些).*")) return true;
        // 短查询有上下文
        if (query.length() < SHORT_QUERY_LENGTH) return true;
        // 疑问句且省略主语
        if ((query.startsWith("怎么") || query.startsWith("如何") || query.startsWith("为什么"))
                && findLastUserMessage(history) != null) return true;
        return false;
    }

    /**
     * L4修复: 使用轻量模型进行 LLM 查询改写
     * 将上下文依赖查询改写为独立、完整的检索查询
     */
    private String rewriteWithLlm(String query, List<Map<String, String>> history) {
        DashScopeApi api = DashScopeApi.builder().apiKey(this.apiKey).build();
        DashScopeChatModel model = DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(this.llmModel)
                        .withTemperature(0.1)
                        .withMaxToken(200)
                        .build())
                .build();

        String systemPrompt = """
                你是一个查询改写助手。根据对话历史，将用户的查询改写为独立、完整的检索查询。

                规则：
                1. 将指代词（"它"、"这个"、"上次说的"、"那个方案"）替换为对话中的具体内容
                2. 补充上下文中省略的主语、宾语、学科、年级等信息
                3. 如果查询已经完整独立，直接返回原查询
                4. 输出只有改写后的查询文本，不要加引号、不要解释、不要标注

                示例：
                历史: 用户问"三年级数学分数怎么教"，助手答"建议从分蛋糕引入"
                查询: "上次说的那个分数导入方法还有其他例子吗"
                输出: 三年级数学分数教学的导入方法 其他例子 分蛋糕引入""";

        String historyText = formatHistoryForPrompt(history);
        String userMessage = "对话历史：\n" + historyText + "\n用户查询：" + query;

        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)));
        ChatResponse response = model.call(prompt);
        if (response != null && response.getResult() != null) {
            String rewritten = response.getResult().getOutput().getText();
            if (rewritten != null && !rewritten.isBlank() && !rewritten.equals(query)) {
                return rewritten.trim();
            }
        }
        return null;
    }

    /**
     * 将对话历史格式化为 LLM 可读的文本（最近 3 轮）
     */
    private String formatHistoryForPrompt(List<Map<String, String>> history) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, history.size() - 6); // 最多 3 轮 = 6 条消息
        for (int i = start; i < history.size(); i++) {
            Map<String, String> msg = history.get(i);
            String role = msg.get("role");
            String content = msg.get("content");
            if (content != null && content.length() > 200) {
                content = content.substring(0, 200) + "...";
            }
            if ("user".equals(role)) {
                sb.append("用户: ").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                sb.append("助手: ").append(content).append("\n");
            }
        }
        return sb.toString();
    }

    private String findLastUserMessage(List<Map<String, String>> history) {
        for (int i = history.size() - 1; i >= 0; --i) {
            Map<String, String> msg = history.get(i);
            if (!"user".equals(msg.get("role"))) continue;
            return msg.get("content");
        }
        return null;
    }

    String extractKeywords(String question) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("[\u300c\u300c\"\"]([^\u300d\u300d\"\"]+)[\u300d\u300d\"\"]").matcher(question);
        while (m.find()) {
            String term = m.group(1).trim();
            if (term.length() <= 1) continue;
            sb.append(term).append(" ");
        }
        m = Pattern.compile("((\\d+|[\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341]?))\\s*(\u5e74\u7ea7|\u8bfe\u65f6|\u5355\u5143|\u7ae0|\u8bfe|\u8282|\u5b66\u671f|\u5b66\u6bb5)").matcher(question);
        while (m.find()) {
            sb.append(m.group()).append(" ");
        }
        return sb.toString().trim();
    }
}
