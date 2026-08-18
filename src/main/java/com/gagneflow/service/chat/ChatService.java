package com.gagneflow.service.chat;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import java.util.List;
import java.util.Map;
import com.gagneflow.agent.tool.DateTimeTools;
import com.gagneflow.agent.tool.InternalDocsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);
    @Autowired
    private InternalDocsTools internalDocsTools;
    @Autowired
    private DateTimeTools dateTimeTools;
    @Autowired(required=false)
    private ToolCallbackProvider tools;
    @Autowired
    private DashScopeApi dashScopeApi;
    @Value(value="${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;
    @Value(value="${dashscope.summary.model:qwen-plus}")
    private String summaryModel;

    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder().apiKey(this.dashScopeApiKey).build();
    }

    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder().dashScopeApi(dashScopeApi).defaultOptions(DashScopeChatOptions.builder().withModel(DashScopeChatModel.DEFAULT_MODEL_NAME).withTemperature(Double.valueOf(temperature)).withMaxToken(Integer.valueOf(maxToken)).withTopP(Double.valueOf(topP)).build()).build();
    }

    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return this.createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    public String buildSystemPrompt(List<Map<String, String>> history) {
        return this.buildSystemPrompt(history, null);
    }

    public String buildSystemPrompt(List<Map<String, String>> history, String longTermContext) {
        StringBuilder systemPromptBuilder = new StringBuilder();
        systemPromptBuilder.append("\u4f60\u662f\u4e00\u4e2a\u4e13\u4e1a\u7684 AI \u6559\u80b2\u52a9\u624b\uff0c\u4e13\u6ce8\u4e8e\u5e2e\u52a9\u6559\u5e08\u8bbe\u8ba1\u6559\u6848\u3001\u5206\u6790\u6559\u5b66\u5185\u5bb9\u3001\u68c0\u7d22\u8bfe\u7a0b\u8d44\u6e90\u3002\n");
        systemPromptBuilder.append("\u5f53\u7528\u6237\u8be2\u95ee\u65f6\u95f4\u76f8\u5173\u95ee\u9898\u65f6\uff0c\u4f7f\u7528 getCurrentDateTime \u5de5\u5177\u3002\n");
        systemPromptBuilder.append("\u5f53\u7528\u6237\u9700\u8981\u67e5\u8be2\u6559\u5b66\u8d44\u6599\u3001\u8bfe\u7a0b\u6807\u51c6\u3001\u6559\u6848\u6a21\u677f\u6216\u6559\u80b2\u7b56\u7565\u65f6\uff0c\u4f7f\u7528 queryInternalDocs \u5de5\u5177\u68c0\u7d22\u77e5\u8bc6\u5e93\u3002\n");
        systemPromptBuilder.append("\u5982\u679c\u5bf9\u8bdd\u5386\u53f2\u4e2d\u5df2\u5b58\u5728\u6559\u6848\u5185\u5bb9\uff0c\u8bf7\u57fa\u4e8e\u7528\u6237\u7684\u4fee\u6539\u6307\u4ee4\u4f18\u5316\u6559\u6848\uff0c\u800c\u975e\u5b8c\u5168\u91cd\u65b0\u751f\u6210\uff0c\u4fdd\u7559\u672a\u88ab\u63d0\u53ca\u4fee\u6539\u7684\u90e8\u5206\u3002\n\n");
        if (longTermContext != null && !longTermContext.isEmpty()) {
            systemPromptBuilder.append(longTermContext).append("\n\n");
        }
        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- \u5bf9\u8bdd\u5386\u53f2 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("system".equals(role)) {
                    systemPromptBuilder.append("[\u5386\u53f2\u5bf9\u8bdd\u6458\u8981]: ").append(content).append("\n");
                    continue;
                }
                if ("user".equals(role)) {
                    systemPromptBuilder.append("\u7528\u6237: ").append(content).append("\n");
                    continue;
                }
                if (!"assistant".equals(role)) continue;
                systemPromptBuilder.append("\u52a9\u624b: ").append(content).append("\n");
            }
            systemPromptBuilder.append("--- \u5bf9\u8bdd\u5386\u53f2\u7ed3\u675f ---\n\n");
        }
        systemPromptBuilder.append("\u8bf7\u57fa\u4e8e\u4ee5\u4e0a\u5bf9\u8bdd\u5386\u53f2\uff0c\u56de\u7b54\u7528\u6237\u7684\u65b0\u95ee\u9898\u3002");
        return systemPromptBuilder.toString();
    }

    public Object[] buildMethodToolsArray() {
        return buildStandardMethodTools();
    }

    /**
     * 统一工具构建入口（供 ChatService 和 LessonPlanService 共用）。
     * 所有需要标准工具数组的调用方均应通过此方法获取。
     */
    public Object[] buildStandardMethodTools() {
        return new Object[]{this.dateTimeTools, this.internalDocsTools};
    }

    public ToolCallback[] getToolCallbacks() {
        if (this.tools == null) {
            return new ToolCallback[0];
        }
        return this.tools.getToolCallbacks();
    }

    public void logAvailableTools() {
        if (this.tools == null) {
            logger.info("MCP \u5df2\u7981\u7528\uff0c\u8df3\u8fc7\u5de5\u5177\u56de\u8c03\u8bb0\u5f55");
            return;
        }
        ToolCallback[] toolCallbacks = this.tools.getToolCallbacks();
        logger.info("\u53ef\u7528 MCP \u5de5\u5177\u5217\u8868:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", (Object)toolCallback.getToolDefinition().name());
        }
    }

    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder().name("intelligent_assistant").model((ChatModel)chatModel).systemPrompt(systemPrompt).methodTools(this.buildMethodToolsArray()).tools(this.getToolCallbacks()).build();
    }

    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("\u6267\u884c ReactAgent.call() - \u81ea\u52a8\u5904\u7406\u5de5\u5177\u8c03\u7528");
        AssistantMessage response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent \u5bf9\u8bdd\u5b8c\u6210\uff0c\u7b54\u6848\u957f\u5ea6: {}", (Object)answer.length());
        return answer;
    }

    public String generateConversationSummary(List<Map<String, String>> messagesToSummarize) {
        logger.info("\u5f00\u59cb\u751f\u6210\u5bf9\u8bdd\u6458\u8981\uff0c\u6d88\u606f\u6761\u6570: {}", (Object)messagesToSummarize.size());
        StringBuilder conversationText = new StringBuilder();
        for (Map<String, String> msg : messagesToSummarize) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("user".equals(role)) {
                conversationText.append("\u7528\u6237: ").append(content).append("\n");
                continue;
            }
            if ("assistant".equals(role)) {
                conversationText.append("\u52a9\u624b: ").append(content).append("\n");
                continue;
            }
            if (!"system".equals(role)) continue;
            conversationText.append("[\u6458\u8981]: ").append(content).append("\n");
        }
        String summaryPrompt = String.format("\u4f60\u662f\u4e00\u4e2a\u5bf9\u8bdd\u6458\u8981\u52a9\u624b\u3002\u8bf7\u5c06\u4ee5\u4e0b\u591a\u8f6e\u5bf9\u8bdd\u538b\u7f29\u4e3a\u4e00\u6bb5\u7ed3\u6784\u5316\u6458\u8981\uff08\u4e0d\u8d85\u8fc7300\u5b57\uff09\u3002\n\n\u5fc5\u987b\u4fdd\u7559\u4ee5\u4e0b\u4fe1\u606f\uff1a\n1. \u7528\u6237\u7684\u6838\u5fc3\u9700\u6c42\u548c\u76ee\u6807\uff08\u5982\u679c\u6709\u53d8\u66f4\uff0c\u8bb0\u5f55\u6700\u7ec8\u7248\u672c\uff09\n2. \u7528\u6237\u660e\u786e\u8868\u8fbe\u8fc7\u7684\u4e0d\u6ee1\u610f\u70b9\u3001\u5426\u5b9a\u53cd\u9988\u548c\u4fee\u6539\u6307\u4ee4\uff08\u4fdd\u7559\u539f\u8bdd\u8981\u70b9\uff09\n3. AI \u7ed9\u51fa\u7684\u5173\u952e\u7ed3\u8bba\u3001\u5efa\u8bae\u548c\u65b9\u6848\uff08\u4ec5\u8bb0\u5f55\u6700\u7ec8\u91c7\u7eb3\u7684\u7248\u672c\uff09\n4. \u4efb\u4f55\u5177\u4f53\u7684\u6570\u503c\u3001\u65e5\u671f\u3001\u4eba\u540d\u3001\u5b66\u79d1\u672f\u8bed\uff08\u4e0d\u53ef\u6cdb\u5316\uff09\n5. \u7528\u6237\u660e\u786e\u8981\u6c42\u540e\u7eed\u4fdd\u7559\u7684\u504f\u597d\u6216\u7ea6\u675f\n\n\u7981\u6b62\u884c\u4e3a\uff1a\n- \u4e0d\u8981\u5c06[\u7528\u6237\u4e0d\u6ee1\u610f\u6570\u5b66\u5bfc\u5165\u73af\u8282]\u6982\u62ec\u4e3a[\u8ba8\u8bba\u4e86\u6559\u5b66\u5bfc\u5165]\n- \u4e0d\u8981\u9057\u6f0f\u7528\u6237\u7684\u5177\u4f53\u4fee\u6539\u6307\u4ee4\n- \u4e0d\u8981\u6dfb\u52a0\u5bf9\u8bdd\u4e2d\u672a\u51fa\u73b0\u7684\u4fe1\u606f\n\n--- \u5bf9\u8bdd\u5185\u5bb9 ---\n%s\n--- \u5bf9\u8bdd\u7ed3\u675f ---\n\n\u8bf7\u76f4\u63a5\u8f93\u51fa\u6458\u8981\u6587\u672c\uff0c\u4e0d\u8981\u6dfb\u52a0\u4efb\u4f55\u524d\u7f00\u6216\u6807\u8bb0\u3002", conversationText);
        DashScopeChatModel summaryChatModel = this.createChatModel(this.dashScopeApi, 0.3, 500, 0.9);
        try {
            logger.debug("\u8c03\u7528\u6458\u8981\u6a21\u578b: {}, temperature: 0.3", (Object)this.summaryModel);
            String summary = summaryChatModel.call(summaryPrompt);
            if (summary == null || summary.trim().isEmpty()) {
                throw new RuntimeException("\u6458\u8981\u6a21\u578b\u8fd4\u56de\u7a7a\u7ed3\u679c");
            }
            logger.info("\u5bf9\u8bdd\u6458\u8981\u751f\u6210\u6210\u529f\uff0c\u6458\u8981\u957f\u5ea6: {} \u5b57\u7b26", (Object)summary.length());
            return summary.trim();
        }
        catch (Exception e) {
            logger.error("\u5bf9\u8bdd\u6458\u8981\u751f\u6210\u5931\u8d25", (Throwable)e);
            throw new RuntimeException("\u5bf9\u8bdd\u6458\u8981\u751f\u6210\u5931\u8d25: " + e.getMessage(), e);
        }
    }

    /**
     * \u6309\u53e5\u5b50\u8fb9\u754c\u622a\u65ad\uff1a\u5728\u6700\u5927\u957f\u5ea6\u5185\u627e\u5230\u6700\u540e\u4e00\u4e2a\u4e2d\u6587\u6807\u70b9(\u3002\uff01\uff1f\uff1b\uff1a\u3001)\u6216\u6362\u884c\u622a\u65ad\uff0c
     * \u907f\u514d\u5c06\u5173\u952e\u8bcd/\u53e5\u5b50\u786c\u5207\u6210\u4e24\u534a\u3002\u82e5\u672a\u627e\u5230\u53ef\u7528\u8fb9\u754c\u5219\u56de\u843d\u4e3a\u786c\u622a\u65ad\u3002
     */
    public static String truncateAtSentenceBoundary(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        String cut = text.substring(0, maxLen);
        int boundary = -1;
        for (int i = cut.length() - 1; i >= 0; i--) {
            char c = cut.charAt(i);
            if (c == '\u3002' || c == '\uff01' || c == '\uff1f' || c == '\uff1b' || c == '\uff1a' || c == '\u3001' || c == '\n') {
                boundary = i;
                break;
            }
        }
        // \u81f3\u5c11\u4fdd\u7559 30% \u7684\u6587\u672c\uff0c\u907f\u514d\u524d\u51e0\u4e2a\u5b57\u5c31\u627e\u5230\u6807\u70b9\u5bfc\u81f4\u622a\u5f97\u8fc7\u77ed
        if (boundary >= maxLen / 3) {
            return cut.substring(0, boundary + 1);
        }
        return cut;
    }
}
