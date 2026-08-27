package com.gagneflow.agent.orchestration;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.gagneflow.agent.tool.InternalDocsTools;
import com.gagneflow.service.chat.ChatService;
import com.gagneflow.service.memory.TokenCounter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 对话多 Agent 轻量编排器(阶段一: supervisor 编排壳, 默认 single 零行为变化)。
 *
 * <p>流程(仅复杂任务):
 * PLAN(Supervisor LLM 决策) -> [FETCH(Retrieval 提炼摘要)] -> EXECUTE(复用 ReactAgent)
 * -> REVIEW(质量复核, FAIL 回溯重执行, 上限 2 次) -> FINISH。
 * 简单问答直接走 EXECUTE 快捷路径(与 single 等效, 不触发规划/复核, 保证延迟)。
 *
 * <p>原则:
 * - 不引第三方图框架, 先以可测的轻量编排落地, 阶段二若需复杂 DAG 再评估 StateGraph;
 * - 不接 checkpoint(断点续答为后续需求);
 * - 五个孤儿角色提示词(planner/supervisor/retrieval/executor/review/decision_guide)
 *   自 agent-config/prompts/v1 接线为真实消费, 目录缺失时回落内置最小定义;
 * - LLM 调用与执行器均以函数式接口抽象, 便于单测注入与转正指标(A/B)评估。
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    public enum Decision { PLAN, FETCH, EXECUTE, REVIEW, FINISH }

    /** 规划/复核 LLM 抽象(测试可注入 fake) */
    public interface LlmCall {
        String call(String prompt);
    }

    /** 执行器抽象(默认实现 = ReactAgent 单次调用, 测试可注入 fake) */
    public interface ExecutorCall {
        String execute(String systemPrompt, String input);
    }

    private static final int MAX_REVIEW_LOOPS = 2;
    private static final int PLAN_MAX_INPUT_CHARS = 600;
    private static final int RETRIEVAL_SUMMARY_CHARS = 1500;
    private static final int REVIEW_ANSWER_CHARS = 800;
    private static final int PROMPT_MAX_CHARS = 2000;
    private static final Pattern DECISION_PATTERN =
            Pattern.compile("\"decision\"\\s*:\\s*\"(PLAN|FETCH|EXECUTE|REVIEW|FINISH)\"");
    private static final Pattern REVIEW_PATTERN =
            Pattern.compile("\"verdict\"\\s*:\\s*\"(PASS|FAIL)\"");
    private static final String DECISION_GUIDE_FALLBACK =
            "## 决策类型\n"
            + "- PLAN: 继续分析和规划任务步骤\n"
            + "- EXECUTE: 需要执行具体步骤, 由 Executor 调用工具\n"
            + "- FETCH: 需要从知识库检索教学资料、课程标准、教案模板\n"
            + "- REVIEW: 教案草稿已完成, 请求 Review 质量审查\n"
            + "- FINISH: 审查通过后的最终输出(必须先经过 REVIEW)\n";

    private final ChatService chatService;
    private final DashScopeApi dashScopeApi;
    private final InternalDocsTools internalDocsTools;
    private final TokenCounter tokenCounter;
    private final String mode;
    private final Map<String, String> promptTemplates;
    private final OrchestratorMetrics metrics;

    /** 简单问答: 快捷路径不触发 supervisor/review, 等价单 agent */
    private static final String[] PLAN_HINTS = {"设计", "优化", "规划", "修改", "重写", "改进", "制定"};
    private static final String[] FETCH_HINTS =
            {"资料", "课标", "课程标准", "模板", "策略", "检索", "教材", "文档", "知识点"};

    private volatile LlmCall llmOverride;
    private volatile ExecutorCall executorOverride;

    @Autowired
    public AgentOrchestrator(ChatService chatService, DashScopeApi dashScopeApi,
                             InternalDocsTools internalDocsTools, TokenCounter tokenCounter,
                             @Value("${gagneflow.agent.mode:single}") String mode,
                             @Value("${gagneflow.agent.prompt-dir:agent-config/prompts/v1}") String promptDir) {
        this.chatService = chatService;
        this.dashScopeApi = dashScopeApi;
        this.internalDocsTools = internalDocsTools;
        this.tokenCounter = tokenCounter;
        this.mode = mode == null ? "single" : mode;
        this.metrics = new OrchestratorMetrics();
        this.promptTemplates = loadPrompts(promptDir);
    }

    // ------------------------------------------------------------------ 对外 API

    /** 当前是否启用编排路径(single 模式下 controller 走原路径) */
    public boolean isEnabled() {
        return "orchestrated".equalsIgnoreCase(this.mode);
    }

    public OrchestratorMetrics metrics() {
        return this.metrics;
    }

    /** 测试注入位 */
    public void setLlmOverride(LlmCall llmOverride) {
        this.llmOverride = llmOverride;
    }

    public void setExecutorOverride(ExecutorCall executorOverride) {
        this.executorOverride = executorOverride;
    }

    public OrchestratorResult run(OrchestratorInput input) {
        long start = System.currentTimeMillis();
        List<String> path = new ArrayList<>();
        int token = 0;
        String question = input.question();

        // 简单问答: 快捷路径(等价 single, 不触发 supervisor/review)
        if (!shouldPlan(question)) {
            path.add("EXECUTE");
            String systemPrompt = baseSystemPrompt(input);
            String answer = execute(systemPrompt, question);
            token += this.tokenCounter.estimate(answer)
                    + this.tokenCounter.estimate(systemPrompt) / 10;
            OrchestratorResult r = buildResult(answer, path, true, token, start);
            this.metrics.record(r);
            return r;
        }

        // 复杂任务: PLAN -> [FETCH] -> EXECUTE -> REVIEW(≤2 回溯) -> FINISH
        path.add("PLAN");
        Decision decision = plan(input, question);
        String retrievalContext = "";
        if (decision == Decision.FETCH || shouldFetch(question)) {
            path.add("FETCH");
            retrievalContext = retrieve(question);
            decision = Decision.EXECUTE;
        }

        path.add("EXECUTE");
        String systemPrompt = baseSystemPrompt(input);
        String answer = execute(systemPrompt, question + retrievalContext);
        token += this.tokenCounter.estimate(systemPrompt)
                + this.tokenCounter.estimate(answer) + this.tokenCounter.estimate(retrievalContext);

        boolean reviewPassed = true;
        for (int loop = 0; loop < MAX_REVIEW_LOOPS; loop++) {
            path.add("REVIEW");
            ReviewVerdict verdict = review(question, answer);
            token += verdict.promptTokens();
            if (verdict.pass()) {
                reviewPassed = true;
                break;
            }
            // FAIL: 带上审查意见回溯重执行
            path.add("FIX");
            String fixHint = "审查未通过, 请针对以下问题修订后重新作答: " + verdict.reason();
            answer = execute(systemPrompt, question + retrievalContext + "\n" + fixHint);
            token += this.tokenCounter.estimate(answer);
            reviewPassed = false;
        }
        path.add("FINISH");

        OrchestratorResult r = buildResult(answer, path, reviewPassed, token, start);
        this.metrics.record(r);
        return r;
    }

    // ------------------------------------------------------------------ 节点实现

    /** Supervisor 规划: LLM 决策 JSON, 解析失败/异常降级 EXECUTE(不阻断) */
    private Decision plan(OrchestratorInput input, String question) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(this.promptTemplates.getOrDefault("planner", "")).append("\n\n");
        prompt.append(this.promptTemplates.getOrDefault("supervisor", "你是 AI 教育 Supervisor, 负责调度专职 Agent。\n")).append("\n\n");
        // retrieval 模板接线: 告知 Supervisor 检索节点可用的数据源, 以决定是否应走 FETCH
        String retrievalGuide = this.promptTemplates.get("retrieval");
        if (retrievalGuide != null && !retrievalGuide.isBlank()) {
            prompt.append("--- 检索节点能力说明(Retrieval Agent) ---\n")
                    .append(retrievalGuide.length() > PROMPT_MAX_CHARS
                            ? retrievalGuide.substring(0, PROMPT_MAX_CHARS) : retrievalGuide)
                    .append("\n\n");
        }
        prompt.append(this.promptTemplates.getOrDefault("decision_guide", DECISION_GUIDE_FALLBACK)).append("\n\n");
        prompt.append("--- 用户输入 ---\n").append(question).append("\n");
        prompt.append("--- 会话上下文(摘要) ---\n").append(summarizeHistory(input.history())).append("\n");
        prompt.append("请只输出一条 JSON, 必须包含 decision 字段(取值 PLAN/FETCH/EXECUTE/REVIEW/FINISH)。");
        String raw = this.callLlm(prompt.toString());
        return parseDecision(raw).decision();
    }

    /** Retrieval 节点: 知识库检索并提炼为上下文摘要(不向 Executor 灌原文) */
    private String retrieve(String question) {
        try {
            String raw = this.internalDocsTools.queryInternalDocs(question);
            String summarized = raw == null ? "" : raw;
            if (summarized.length() > RETRIEVAL_SUMMARY_CHARS) {
                summarized = summarized.substring(0, RETRIEVAL_SUMMARY_CHARS) + "...";
            }
            if (summarized.isEmpty() || summarized.contains("\"status\": \"no_results\"")) {
                return "";
            }
            return "\n\n【参考资料(检索提炼)】\n" + summarized;
        } catch (Exception e) {
            log.warn("[ORCH] retrieval 失败, 降级无检索上下文: {}", e.getMessage());
            return "";
        }
    }

    /** Executor 节点: 复用现有 ReactAgent, 追加 executor 角色模板 */
    private String execute(String systemPrompt, String input) {
        ExecutorCall overridden = this.executorOverride;
        if (overridden != null) {
            return overridden.execute(systemPrompt, input);
        }
        String merged = appendTemplate(systemPrompt, "executor");
        DashScopeChatModel chatModel = this.chatService.createStandardChatModel(this.dashScopeApi);
        ReactAgent agent = this.chatService.createReactAgent(chatModel, merged);
        try {
            return this.chatService.executeChat(agent, input);
        } catch (Exception e) {
            log.warn("[ORCH] executor 执行失败, 返回降级文案: {}", e.getMessage());
            return "[无法完成] 执行失败: " + e.getMessage();
        }
    }

    /** Review 节点: LLM 质量复核, 输出 {"verdict":"PASS|FAIL","reason":".."} */
    private ReviewVerdict review(String question, String answer) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(this.promptTemplates.getOrDefault("review", "你是 Review Agent, 负责审查教案质量。\n")).append("\n\n");
        prompt.append(this.promptTemplates.getOrDefault("decision_guide", DECISION_GUIDE_FALLBACK)).append("\n\n");
        prompt.append("--- 用户需求 ---\n").append(question).append("\n");
        String truncated = answer == null ? "" : answer;
        if (truncated.length() > REVIEW_ANSWER_CHARS) {
            truncated = truncated.substring(0, REVIEW_ANSWER_CHARS) + "...";
        }
        prompt.append("--- 待审查输出 ---\n").append(truncated).append("\n");
        prompt.append("请输出 JSON: {\"verdict\":\"PASS|FAIL\", \"reason\":\"简要原因\"}");
        String raw = this.callLlm(prompt.toString());
        int promptTokens = this.tokenCounter.estimate(prompt.toString()) + this.tokenCounter.estimate(raw);
        Matcher m = REVIEW_PATTERN.matcher(raw == null ? "" : raw);
        if (m.find()) {
            boolean pass = "PASS".equals(m.group(1));
            return new ReviewVerdict(pass, extractReason(raw), promptTokens);
        }
        // 解析失败: 默认通过, 不阻断
        return new ReviewVerdict(true, "", promptTokens);
    }

    // ------------------------------------------------------------------ 内部工具

    private String callLlm(String prompt) {
        LlmCall overridden = this.llmOverride;
        if (overridden != null) {
            return overridden.call(prompt);
        }
        DashScopeChatModel model = this.chatService.createChatModel(this.dashScopeApi, 0.3, 500, 0.9);
        try {
            return model.call(prompt);
        } catch (Exception e) {
            log.warn("[ORCH] LLM 调用失败: {}", e.getMessage());
            return "";
        }
    }

    private Assignment parseDecision(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Assignment(Decision.EXECUTE, 0);
        }
        Matcher m = DECISION_PATTERN.matcher(raw);
        if (m.find()) {
            try {
                return new Assignment(Decision.valueOf(m.group(1)), raw.length());
            } catch (IllegalArgumentException e) {
                return new Assignment(Decision.EXECUTE, raw.length());
            }
        }
        return new Assignment(Decision.EXECUTE, raw.length());
    }

    private boolean shouldPlan(String question) {
        for (String hint : PLAN_HINTS) {
            if (question.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldFetch(String question) {
        for (String hint : FETCH_HINTS) {
            if (question.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private String baseSystemPrompt(OrchestratorInput input) {
        return this.chatService.buildSystemPrompt(input.history(), input.longTermContext());
    }

    private String appendTemplate(String systemPrompt, String templateName) {
        String tpl = this.promptTemplates.get(templateName);
        if (tpl == null || tpl.isBlank()) {
            return systemPrompt;
        }
        if (tpl.length() > PROMPT_MAX_CHARS) {
            tpl = tpl.substring(0, PROMPT_MAX_CHARS);
        }
        return systemPrompt + "\n\n" + tpl;
    }

    private String summarizeHistory(List<Map<String, String>> history) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> msg : history) {
            String role = msg.get("role");
            String content = msg.get("content");
            if (content == null) {
                continue;
            }
            if ("system".equals(role)) {
                sb.append("[摘要] ").append(content).append("\n");
            } else if ("user".equals(role)) {
                sb.append("用户: ").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                sb.append("助手: ").append(content).append("\n");
            }
            if (sb.length() > PLAN_MAX_INPUT_CHARS) {
                break;
            }
        }
        return sb.length() > PLAN_MAX_INPUT_CHARS
                ? sb.substring(0, PLAN_MAX_INPUT_CHARS) + "..."
                : sb.toString();
    }

    private Map<String, String> loadPrompts(String promptDir) {
        Map<String, String> loaded = new HashMap<>();
        for (String name : new String[]{"planner", "supervisor", "retrieval", "executor", "review", "decision_guide"}) {
            Path p = Path.of(promptDir, name + ".md");
            try {
                String content = Files.readString(p, StandardCharsets.UTF_8);
                loaded.put(name, content);
                log.info("[ORCH] 已加载角色模板: {}", p);
            } catch (IOException | RuntimeException e) {
                log.warn("[ORCH] 模板缺失/读取失败({}), 使用内置兜底: {}", p, e.getMessage());
            }
        }
        return loaded;
    }

    private static String extractReason(String raw) {
        Matcher m = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]{1,200})").matcher(raw == null ? "" : raw);
        return m.find() ? m.group(1) : "质量未达标";
    }

    private OrchestratorResult buildResult(String answer, List<String> path,
                                           boolean reviewPassed, int token, long start) {
        return new OrchestratorResult(answer, path, reviewPassed, token, System.currentTimeMillis() - start);
    }

    private record Assignment(Decision decision, int promptChars) {
    }

    private record ReviewVerdict(boolean pass, String reason, int promptTokens) {
    }
}