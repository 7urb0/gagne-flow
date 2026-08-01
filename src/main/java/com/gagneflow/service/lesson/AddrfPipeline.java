package com.gagneflow.service.lesson;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.gagneflow.config.PipelineStageConfig;
import com.gagneflow.dto.LessonPlanRequest;
import com.gagneflow.service.document.K12CurriculumLoader;
import com.gagneflow.service.document.PromptLoader;
import com.gagneflow.service.document.SubjectFormatLoader;
import com.gagneflow.service.prompt.PromptExperiment;
import com.gagneflow.service.prompt.PromptMetricsCollector;
import com.gagneflow.service.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

@Service
public class AddrfPipeline {
    private static final Logger logger = LoggerFactory.getLogger(AddrfPipeline.class);
    private static final int STAGE_TIMEOUT_SEC = 60;
    private static final int DEV_TIMEOUT_SEC = 180;
    private static final int MAX_RETRIES = 2;
    private static final int TOKENS_GENERAL = 5000;
    private static final int TOKENS_CONTENT = 8000;
    private static final int TOKENS_CONTENT_TEXT_HEAVY = 10000;
    private static final Set<String> TEXT_HEAVY_SUBJECTS = Set.of("\u8bed\u6587", "\u82f1\u8bed", "\u5386\u53f2", "\u653f\u6cbb");
    private static final String DEGRADED_PREFIX = "[\u7cfb\u7edf\u63d0\u793a";
    private final PromptLoader promptLoader;
    private final PromptRegistry promptRegistry;
    private final PromptExperiment promptExperiment;
    private final PromptMetricsCollector promptMetrics;
    private final K12CurriculumLoader k12Loader;
    private final FormatTool formatTool;
    private final SubjectFormatLoader subjectFormatLoader;
    private final PipelineStageConfig stageConfig;
    private final ThreadPoolExecutor executor;
    private final StringRedisTemplate redisTemplate;
    private volatile CompletableFuture<Void> reviewFuture;
    static final String CO_TERMINATE = "terminate";
    private static final String REVISE_PREFIX = "revise:";
    static final String TERMINATED = "_TERMINATED_";
    private static final Pattern DEDUP_SECTION_PATTERN = Pattern.compile("(?m)(?=^\\*\\*[^*]+\\*\\*)");
    private static final Pattern DEDUP_TITLE_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    @Autowired
    public AddrfPipeline(PromptLoader promptLoader, PromptRegistry promptRegistry,
                         PromptExperiment promptExperiment, PromptMetricsCollector promptMetrics,
                         K12CurriculumLoader k12Loader, FormatTool formatTool,
                         SubjectFormatLoader subjectFormatLoader, PipelineStageConfig stageConfig,
                         StringRedisTemplate redisTemplate,
                         @Autowired(required=false) ThreadPoolExecutor executor) {
        this.promptLoader = promptLoader;
        this.promptRegistry = promptRegistry;
        this.promptExperiment = promptExperiment;
        this.promptMetrics = promptMetrics;
        this.k12Loader = k12Loader;
        this.formatTool = formatTool;
        this.subjectFormatLoader = subjectFormatLoader;
        this.stageConfig = stageConfig;
        this.redisTemplate = redisTemplate;
        this.executor = executor != null ? executor : AddrfPipeline.createDefaultExecutor();
        // P3修复: 启动时校验并输出配置的阶段顺序
        validateStageConfig();
    }

    /**
     * P3修复: 启动时校验 Pipeline 阶段配置的有效性
     */
    private void validateStageConfig() {
        List<String> stages = stageConfig.getStages();
        if (stages == null || stages.isEmpty()) {
            logger.warn("[ADDRF] Pipeline stages \u914d\u7f6e\u4e3a\u7a7a\uff0c\u4f7f\u7528\u9ed8\u8ba4\u987a\u5e8f");
            return;
        }
        // 校验必须有核心阶段
        boolean hasAnalysis = stages.contains("analysis");
        boolean hasDesign = stages.contains("design");
        boolean hasDevelopment = stages.contains("development");
        if (!hasAnalysis || !hasDesign || !hasDevelopment) {
            logger.error("[ADDRF] Pipeline stages \u914d\u7f6e\u7f3a\u5c11\u5fc5\u8981\u9636\u6bb5 (analysis/design/development)\uff0c\u5f53\u524d: {}", stages);
            throw new IllegalStateException("Pipeline stages must include at least: analysis, design, development. Current: " + stages);
        }
        logger.info("[ADDRF] Pipeline \u9636\u6bb5\u914d\u7f6e: {} (\u5b9e\u9645\u6267\u884c\u987a\u5e8f\u4e3a: analysis \u2192 design\u2225development \u2192 format \u2192 review(\u5f02\u6b65))", stages);
    }

    static ThreadPoolExecutor createDefaultExecutor() {
        return new ThreadPoolExecutor(2, 4, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(50), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public AddrfResult execute(LessonPlanRequest request, DashScopeChatModel chatModel, SseEmitter emitter, String mode, ConcurrentHashMap<String, BlockingQueue<String>> copilotQueues, String sessionContext, Long userId) {
        boolean devDegraded;
        String enhancedDevPrompt;
        String k12Ctx;
        AddrfResult result;
        block18: {
            CompletableFuture<Void> devFuture;
            block17: {
                result = new AddrfResult();
                k12Ctx = this.loadK12Context(request);
                String initialInput = this.buildInitialInput(request, k12Ctx, sessionContext);
                logger.info("[ADDRF] \u9636\u6bb51: Analysis \u5f00\u59cb");
                // Analysis \u7f13\u5b58: \u540c\u4e00\u7528\u6237\u76f8\u540c\u5b66\u6bb5+\u5e74\u7ea7+\u5b66\u79d1\u53ef\u590d\u7528
                String analysisCacheKey = buildAnalysisCacheKey(userId, request);
                String cachedAnalysis = tryGetCachedAnalysis(analysisCacheKey);
                if (cachedAnalysis != null) {
                    logger.debug("[ADDRF] Analysis \u7f13\u5b58\u547d\u4e2d: {}", analysisCacheKey);
                    result.analysis = cachedAnalysis;
                } else {
                    String analysisPrompt = this.loadPrompt("addrf_analysis", userId);
                    result.analysis = this.callStageWithRevise(chatModel, analysisPrompt, initialInput, emitter, "analysis", mode, copilotQueues, 60, request.getSubject());
                    if (TERMINATED.equals(result.analysis)) {
                        result.analysis = "[\u7528\u6237\u7ec8\u6b62]";
                        return result;
                    }
                    if (result.analysis != null) {
                        result.analysis = AddrfPipeline.dedupContent(result.analysis);
                        tryCacheAnalysis(analysisCacheKey, result.analysis);
                    }
                }
                logger.info("[ADDRF] \u9636\u6bb52+3: Design & Development \u5e76\u884c\u542f\u52a8");
                String designPrompt = this.loadPrompt("addrf_design", userId);
                String devPrompt = this.loadPrompt("addrf_development", userId);
                String designExtra = this.subjectFormatLoader.getDesignExtra(request.getSubject());
                StringBuilder designPromptBuilder = new StringBuilder(designPrompt);
                if (!designExtra.isEmpty()) {
                    designPromptBuilder.append("\n\n=== ").append(request.getSubject()).append("\u5b66\u79d1\u4e13\u5c5e\u8bbe\u8ba1\u8981\u6c42 ===\n").append(designExtra);
                }
                String enhancedDesignPrompt = designPromptBuilder.toString();
                String devInstr = this.subjectFormatLoader.getDevelopmentInstructions(request.getSubject());
                String stageInstr = this.subjectFormatLoader.getStageInstructions(request.getStage());
                StringBuilder devPromptBuilder = new StringBuilder(devPrompt);
                if (!devInstr.isEmpty()) {
                    devPromptBuilder.append("\n\n=== ").append(request.getSubject()).append("\u5b66\u79d1\u4e13\u5c5e\u683c\u5f0f\u8981\u6c42 ===\n").append(devInstr);
                }
                if (!stageInstr.isEmpty()) {
                    devPromptBuilder.append("\n\n=== ").append(request.getStage()).append("\u5b66\u6bb5\u6559\u5b66\u6307\u5bfc ===\n").append(stageInstr);
                }
                enhancedDevPrompt = devPromptBuilder.toString();
                CompletableFuture<Void> designFuture = CompletableFuture.runAsync(() -> {
                    result.design = this.callStageWithRevise(chatModel, enhancedDesignPrompt, result.analysis, emitter, "design", mode, copilotQueues, 60, request.getSubject());
                    logger.info("[ADDRF] Design \u5b8c\u6210, {} \u5b57\u7b26", (Object)(result.design != null ? result.design.length() : 0));
                }, this.executor).exceptionally(ex -> {
                    logger.error("[ADDRF] Design \u9636\u6bb5\u5185\u90e8\u5f02\u5e38\u88ab\u5f02\u6b65\u4efb\u52a1\u6355\u83b7", ex);
                    result.design = "[\u7cfb\u7edf\u63d0\u793a: Design \u9636\u6bb5\u5185\u90e8\u9519\u8bef]";
                    return null;
                });
                devFuture = CompletableFuture.runAsync(() -> {
                    result.development = this.callStageWithRevise(chatModel, enhancedDevPrompt, result.analysis, emitter, "development", mode, copilotQueues, 180, request.getSubject());
                    logger.info("[ADDRF] Development \u5b8c\u6210, {} \u5b57\u7b26", (Object)(result.development != null ? result.development.length() : 0));
                }, this.executor).exceptionally(ex -> {
                    logger.error("[ADDRF] Development \u9636\u6bb5\u5185\u90e8\u5f02\u5e38\u88ab\u5f02\u6b65\u4efb\u52a1\u6355\u83b7", ex);
                    result.development = "[\u7cfb\u7edf\u63d0\u793a: Development \u9636\u6bb5\u5185\u90e8\u9519\u8bef]";
                    return null;
                });
                try {
                    designFuture.get(240L, TimeUnit.SECONDS);
                }
                catch (TimeoutException e) {
                    logger.warn("[ADDRF] Design \u9636\u6bb5\u8d85\u65f6({}s)", (Object)240);
                    if (result.design == null) {
                        result.design = "[\u7cfb\u7edf\u63d0\u793a: Design \u9636\u6bb5\u8d85\u65f6]";
                    }
                    designFuture.cancel(true);
                }
                catch (Exception e) {
                    logger.error("[ADDRF] Design \u9636\u6bb5\u5f02\u5e38", (Throwable)e);
                    if (result.design != null) break block17;
                    result.design = "[\u7cfb\u7edf\u63d0\u793a: Design \u9636\u6bb5\u5f02\u5e38]";
                }
            }
            try {
                devFuture.get(240L, TimeUnit.SECONDS);
            }
            catch (TimeoutException e) {
                logger.warn("[ADDRF] Development \u9636\u6bb5\u8d85\u65f6({}s)", (Object)240);
                if (result.development == null) {
                    result.development = "[\u7cfb\u7edf\u63d0\u793a: Development \u9636\u6bb5\u8d85\u65f6]";
                }
                devFuture.cancel(true);
            }
            catch (Exception e) {
                logger.error("[ADDRF] Development \u9636\u6bb5\u5f02\u5e38", (Throwable)e);
                if (result.development != null) break block18;
                result.development = "[\u7cfb\u7edf\u63d0\u793a: Development \u9636\u6bb5\u5f02\u5e38]";
            }
        }
        logger.info("[ADDRF] Format \u5f00\u59cb");
        boolean bl = devDegraded = result.development != null && result.development.startsWith(DEGRADED_PREFIX);
        if (result.design != null && result.design.startsWith(DEGRADED_PREFIX)) {
            result.design = "\uff08\u6b64\u90e8\u5206\u5f85\u8865\u5145\uff09";
            devDegraded = true;
        }
        if (devDegraded) {
            result.development = "\uff08\u6b64\u90e8\u5206\u5f85\u8865\u5145\uff09\n\n> **\u26a0 \u8be5\u9636\u6bb5\u751f\u6210\u8d85\u65f6\u6216\u5931\u8d25\uff0c\u4ec5\u5c55\u793a\u5df2\u751f\u6210\u7684\u5206\u6790\u4e0e\u8bbe\u8ba1\u5185\u5bb9\u3002\u53ef\u5c1d\u8bd5\u51cf\u5c11\u8bfe\u65f6\u6570\u540e\u91cd\u65b0\u751f\u6210\u3002**";
        }
        result.html = this.formatTool.format(result.analysis, result.design, result.development, "");
        this.emitStageComplete(emitter, "stage:format", result.html);
        if (!devDegraded && result.development != null && !result.development.startsWith(DEGRADED_PREFIX)) {
            String finalK12 = k12Ctx;
            String finalSubject = request.getSubject();
            CompletableFuture<Void> reviewTask = CompletableFuture.runAsync(() -> {
                logger.info("[ADDRF] Review \u540e\u53f0\u5f00\u59cb");
                this.asyncReview(result, chatModel, enhancedDevPrompt, emitter, finalK12, finalSubject, userId);
                logger.info("[ADDRF] Review \u540e\u53f0\u5b8c\u6210, \u8bc4\u5206: {}", (Object)result.score);
                // HITL 检查: 判断是否需要人工审核
                if (shouldRequestHumanReview(result, request.getSubject(), userId)) {
                    logger.warn("[ADDRF-HITL] \u89e6\u53d1\u4eba\u5de5\u5ba1\u6838: subject={}, score={}, uid={}",
                        request.getSubject(), result.score, userId);
                }
                try {
                    result.html = this.formatTool.format(result.analysis, result.design, result.development, result.review);
                    emitter.send(SseEmitter.event().name("message").data(Map.of("type", "stage:format", "content", result.html, "stage", "format", "updated", true)));
                }
                catch (Exception e) {
                    logger.warn("[ADDRF] Review 更新 HTML 推送失败 (emitter 可能已关闭): {}", e.getMessage());
                }
                this.emitStageComplete(emitter, "stage:review", result.review);
            }, this.executor);
            this.reviewFuture = reviewTask;
        } else {
            result.review = "[\u7cfb\u7edf\u63d0\u793a: \u6559\u6848\u4e3b\u4f53\u4e0d\u5b8c\u6574\uff0c\u8df3\u8fc7\u8bc4\u4f30]";
            this.emitStageComplete(emitter, "stage:review", result.review);
        }
        return result;
    }

    /**
     * Wait for the background Review task to complete.
     * Fixes F05: ensures Review async SSE pushes happen before main flow sends "done".
     */
    public void awaitReview(AddrfResult result, long timeoutSeconds) {
        if (this.reviewFuture != null) {
            try {
                this.reviewFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logger.warn("[ADDRF] Review background task timed out after {}s", timeoutSeconds);
            } catch (Exception e) {
                logger.warn("[ADDRF] Review background task failed: {}", e.getMessage());
            }
        }
    }

    private String callStageWithRevise(DashScopeChatModel chatModel, String systemPrompt, String userInput, SseEmitter emitter, String stage, String mode, ConcurrentHashMap<String, BlockingQueue<String>> copilotQueues, int timeoutSec, String subject) {
        Object currentInput = userInput;
        for (int round = 0; round < 5; ++round) {
            Object content = this.callAgent(chatModel, systemPrompt, (String)currentInput, emitter, stage, mode, timeoutSec, subject);
            if (content == null) {
                content = "[\u7cfb\u7edf\u63d0\u793a: " + stage + " \u9636\u6bb5\u751f\u6210\u5931\u8d25]";
            }
            this.emitStageComplete(emitter, "stage:" + stage, content.toString());
            String action = this.emitCopilotAwait(emitter, stage, mode, (String)content, copilotQueues);
            if (action == null) {
                return (String) content;
            }
            if (CO_TERMINATE.equals(action)) {
                return TERMINATED;
            }
            if ("continue".equals(action)) {
                return (String) content;
            }
            if (!action.startsWith(REVISE_PREFIX)) {
                return (String) content;
            }
            String instruction = action.substring(REVISE_PREFIX.length());
            logger.info("Copilot {} \u7b2c{}\u8f6e\u4fee\u8ba2: {}", new Object[]{stage, round + 1, instruction});
            currentInput = userInput + "\n\n[\u7528\u6237\u4fee\u6539\u610f\u89c1]: " + instruction;
        }
        return this.callAgent(chatModel, systemPrompt, userInput, emitter, stage, mode, timeoutSec, subject);
    }

    private void asyncReview(AddrfResult result, DashScopeChatModel chatModel, String devPrompt, SseEmitter emitter, String k12Ctx, String subject, Long userId) {
        for (int retryCount = 0; !(retryCount > 2 || result.development != null && result.development.startsWith(DEGRADED_PREFIX)); ++retryCount) {
            String reviewPrompt = this.loadPrompt("addrf_review", userId) + "\n\n\u8bfe\u7a0b\u6807\u51c6\uff1a\n" + k12Ctx;
            result.review = this.callAgent(chatModel, reviewPrompt, result.development, null, "review", "quick", 60, subject);
            if (result.review == null) {
                result.review = "[\u7cfb\u7edf\u63d0\u793a: Review \u9636\u6bb5\u751f\u6210\u5931\u8d25]";
                break;
            }
            result.score = this.extractScore(result.review);
            // 记录 Review 评分到 Prompt 指标收集器
            try {
                int reviewVersion = this.promptRegistry.getActiveVersionNumber("addrf_review");
                this.promptMetrics.recordScore("addrf_review", reviewVersion, result.score);
                if (result.score < 60) {
                    logger.warn("[ADDRF-REVIEW] \u4f4e\u5206\u9884\u8b66: score={}, subject={}, uid={}",
                            result.score, subject, userId);
                }
            } catch (Exception e) {
                logger.debug("[ADDRF-REVIEW] \u8bb0\u5f55\u8bc4\u5206\u5931\u8d25: {}", e.getMessage());
            }
            if (result.score >= 70 || retryCount >= 2) break;
            String feedback = this.extractFeedback(result.review);
            result.development = this.callAgent(chatModel, devPrompt + "\n\u4fee\u6539\u610f\u89c1\uff1a\n" + feedback, result.analysis, null, "development", "quick", 180, subject);
            if (result.development == null || result.development.startsWith(DEGRADED_PREFIX)) break;
        }
    }

    private String callAgent(DashScopeChatModel chatModel, String systemPrompt, String userInput, SseEmitter emitter, String stage, String mode, String subject) {
        return this.callAgent(chatModel, systemPrompt, userInput, emitter, stage, mode, 60, subject);
    }

    int resolveMaxTokens(String stage, String subject) {
        if (!"development".equals(stage)) {
            return 5000;
        }
        if (subject != null && TEXT_HEAVY_SUBJECTS.contains(subject)) {
            return 10000;
        }
        return 8000;
    }

    private String callAgent(DashScopeChatModel chatModel, String systemPrompt, String userInput, SseEmitter emitter, String stage, String mode, int timeoutSec) {
        return this.callAgent(chatModel, systemPrompt, userInput, emitter, stage, mode, timeoutSec, null);
    }

    private String callAgent(DashScopeChatModel chatModel, String systemPrompt, String userInput, SseEmitter emitter, String stage, String mode, int timeoutSec, String subject) {
        int maxTokens = this.resolveMaxTokens(stage, subject);
        Future<String> future = this.executor.submit(() -> {
            try {
                Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userInput)), (ChatOptions)DashScopeChatOptions.builder().withMaxToken(Integer.valueOf(maxTokens)).build());
                StringBuilder full = new StringBuilder();
                Flux<ChatResponse> flux = chatModel.stream(prompt);
                flux.doOnNext(response -> {
                    String chunk;
                    if (response != null && response.getResult() != null && (chunk = response.getResult().getOutput().getText()) != null) {
                        full.append(chunk);
                        this.emitInterim(emitter, stage, chunk);
                    }
                }).blockLast();
                return full.toString();
            }
            catch (Exception e) {
                logger.warn("[ADDRF] {} stream() \u964d\u7ea7 call(): {}", (Object)stage, (Object)e.getMessage());
                try {
                    Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userInput)), (ChatOptions)DashScopeChatOptions.builder().withMaxToken(Integer.valueOf(maxTokens)).build());
                    ChatResponse response2 = chatModel.call(prompt);
                    if (response2 != null && response2.getResult() != null) {
                        AssistantMessage msg = response2.getResult().getOutput();
                        return msg != null ? msg.getText() : "";
                    }
                }
                catch (Exception e2) {
                    logger.warn("[ADDRF] {} stream() 降级 call() 也失败: {}", stage, e2.getMessage());
                }
                return "";
            }
        });
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        }
        catch (TimeoutException e) {
            future.cancel(true);
            logger.warn("[ADDRF] {} \u8d85\u65f6({}s)", (Object)stage, (Object)timeoutSec);
            return null;
        }
        catch (ExecutionException e) {
            logger.error("[ADDRF] {} \u5f02\u5e38", (Object)stage, (Object)e.getCause());
            return null;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        }
    }

    private void emitInterim(SseEmitter emitter, String stage, String chunk) {
        if (emitter == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("message").data(Map.of("type", "stage:content", "stage", stage, "chunk", chunk)));
        }
        catch (IOException | IllegalStateException e) {
            logger.trace("[ADDRF] 临时块推送失败 (emitter 可能已关闭): {}", e.getMessage());
        }
    }

    private void emitStageComplete(SseEmitter emitter, String eventType, String content) {
        if (emitter == null) {
            return;
        }
        try {
            String stage = eventType.replace("stage:", "");
            String safe = content != null ? content.substring(0, Math.min(content.length(), 500)) : "";
            emitter.send(SseEmitter.event().name("message").data(Map.of("type", eventType, "content", safe, "stage", stage)));
        }
        catch (IOException | IllegalStateException e) {
            logger.trace("[ADDRF] 阶段完成推送失败 (emitter 可能已关闭): {}", e.getMessage());
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private String emitCopilotAwait(SseEmitter emitter, String stage, String mode, String content, ConcurrentHashMap<String, BlockingQueue<String>> copilotQueues) {
        String preview;
        if (!"copilot".equals(mode)) {
            return null;
        }
        String string = preview = content != null ? content.substring(0, Math.min(content.length(), 200)) : "";
        if (copilotQueues == null) {
            try {
                emitter.send(SseEmitter.event().name("message").data(Map.of("type", "stage_await", "stage", stage, "content", preview)));
            }
            catch (IOException | IllegalStateException e) {
                logger.trace("[ADDRF] stage_await 推送失败 (emitter 可能已关闭)");
            }
            return null;
        }
        String token = UUID.randomUUID().toString().substring(0, 8);
        LinkedBlockingQueue queue = new LinkedBlockingQueue(1);
        copilotQueues.put(token, queue);
        try {
            emitter.send(SseEmitter.event().name("message").data(Map.of("type", "stage_await", "stage", stage, "token", token, "content", preview)));
            String action = (String)queue.poll(120L, TimeUnit.SECONDS);
            if (action == null) {
                String string2 = null;
                return string2;
            }
            if (CO_TERMINATE.equals(action)) {
                logger.info("Copilot {} \u7528\u6237\u7ec8\u6b62\u6d41\u6c34\u7ebf", (Object)stage);
            }
            String string3 = action;
            return string3;
        }
        catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            String string4 = null;
            return string4;
        }
        finally {
            copilotQueues.remove(token);
        }
    }

    static String dedupContent(String content) {
        if (content == null || content.length() < 100) {
            return content;
        }
        String[] sections = DEDUP_SECTION_PATTERN.split(content);
        if (sections.length <= 1) {
            return content;
        }
        LinkedHashMap<String, String> deduped = new LinkedHashMap<String, String>();
        for (String sec : sections) {
            String existing;
            String key;
            if ((sec = sec.trim()).isEmpty()) continue;
            Matcher m = DEDUP_TITLE_PATTERN.matcher(sec);
            String string = key = m.find() ? m.group(1).trim() : "_no_title_";
            if ("_no_title_".equals(key) && sec.length() < 30 || (existing = (String)deduped.get(key)) != null && sec.replaceAll("\\*\\*", "").length() <= existing.replaceAll("\\*\\*", "").length()) continue;
            deduped.put(key, sec);
        }
        StringBuilder result = new StringBuilder();
        for (String sec : deduped.values()) {
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            result.append(sec);
        }
        String cleaned = result.toString();
        if ((double)cleaned.length() < (double)content.length() * 0.6 && content.length() > 500) {
            logger.warn("\u53bb\u91cd\u540e\u5185\u5bb9\u7f29\u51cf {}\u2192{} \u5b57\u7b26 ({}%)", new Object[]{content.length(), cleaned.length(), (int)(100.0 * (double)cleaned.length() / (double)content.length())});
        }
        return cleaned;
    }

    private String buildAnalysisCacheKey(Long userId, LessonPlanRequest req) {
        // goals 的 hashCode 作为区分因子：同一学科年级但不同教学目标应产生不同缓存
        int goalsHash = req.getGoals() != null ? req.getGoals().hashCode() : 0;
        return "gagneflow:analysis:cache:" + userId + ":"
                + req.getStage() + ":" + req.getGrade() + ":" + req.getSubject()
                + ":" + goalsHash;
    }

    private String tryGetCachedAnalysis(String cacheKey) {
        try {
            return this.redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            logger.debug("[ADDRF] Analysis \u7f13\u5b58\u8bfb\u53d6\u5931\u8d25: {}", e.getMessage());
            return null;
        }
    }

    private void tryCacheAnalysis(String cacheKey, String analysis) {
        try {
            this.redisTemplate.opsForValue().set(cacheKey, analysis, Duration.ofHours(1));
            logger.debug("[ADDRF] Analysis \u7f13\u5b58\u5199\u5165: {}", cacheKey);
        } catch (Exception e) {
            logger.debug("[ADDRF] Analysis \u7f13\u5b58\u5199\u5165\u5931\u8d25: {}", e.getMessage());
        }
    }

    private String loadK12Context(LessonPlanRequest req) {
        try {
            String lookup = this.k12Loader.lookup(req.getStage(), null, req.getSubject());
            return lookup != null && !lookup.contains("\u672a\u52a0\u8f7d") ? lookup : "";
        }
        catch (Exception e) {
            return "";
        }
    }

    private String buildInitialInput(LessonPlanRequest req, String k12Ctx, String sessionContext) {
        String analysisExtra;
        StringBuilder sb = new StringBuilder();
        if (sessionContext != null && !sessionContext.isEmpty()) {
            sb.append("\u4f1a\u8bdd\u4e0a\u4e0b\u6587\uff08\u6b64\u524d\u5bf9\u8bdd\u4e2d\u7684\u5173\u952e\u4fe1\u606f\uff0c\u8bf7\u5728\u6559\u6848\u8bbe\u8ba1\u4e2d\u4f53\u73b0\uff09\uff1a\n");
            sb.append(sessionContext).append("\n\n");
        }
        sb.append("\u6559\u5b66\u9700\u6c42\uff1a\n");
        sb.append("- \u5b66\u6bb5\uff1a").append(req.getStage()).append("\n");
        sb.append("- \u5e74\u7ea7\uff1a").append(req.getGrade()).append("\u5e74\u7ea7\n");
        sb.append("- \u5b66\u79d1\uff1a").append(req.getSubject()).append("\n");
        sb.append("- \u8bfe\u65f6\uff1a").append(req.getHours()).append("\u8bfe\u65f6\n");
        sb.append("- \u6559\u5b66\u76ee\u6807\uff1a").append(req.getGoals()).append("\n\n");
        if (!k12Ctx.isEmpty()) {
            sb.append("\u8bfe\u7a0b\u6807\u51c6\u53c2\u8003\uff1a\n").append(k12Ctx).append("\n\n");
        }
        if (!(analysisExtra = this.subjectFormatLoader.getAnalysisExtra(req.getSubject())).isEmpty()) {
            sb.append(analysisExtra).append("\n");
        }
        return sb.toString();
    }

    private String loadPrompt(String name, Long userId) {
        try {
            // Step 1: 查活跃版本号
            int activeVersion = this.promptRegistry.getActiveVersionNumber(name);
            // Step 2: 实验分流选版本（基于 userId hash 确定性分配）
            int actualVersion = this.promptExperiment.selectVersion(name, activeVersion, userId);
            // Step 3: 取内容
            String prompt = this.promptRegistry.getContent(name, actualVersion);
            // Step 4: 记录指标
            this.promptMetrics.recordUsage(name, actualVersion);
            return prompt != null && !prompt.isBlank() ? prompt : "\u4f60\u662f\u4e00\u4e2a\u6559\u80b2AI\u52a9\u624b\uff0c\u8bf7\u6839\u636e\u8f93\u5165\u751f\u6210\u6559\u6848\u5185\u5bb9\u3002";
        }
        catch (Exception e) {
            logger.warn("Prompt 加载失败, 使用 fallback: {}", e.getMessage());
            return "\u4f60\u662f\u4e00\u4e2a\u6559\u80b2AI\u52a9\u624b\uff0c\u8bf7\u6839\u636e\u8f93\u5165\u751f\u6210\u6559\u6848\u5185\u5bb9\u3002";
        }
    }

    public int extractScore(String review) {
        if (review == null) {
            return 0;
        }
        Matcher m = Pattern.compile("\u603b\u5206[:\\s]+(\\d+)").matcher(review);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = Pattern.compile("\"score\"\\s*:\\s*(\\d+)").matcher(review);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        logger.warn("[ADDRF] 无法从 review 内容中解析评分，默认视为不通过: review 前200字符={}", 
                review.length() > 200 ? review.substring(0, 200) : review);
        return 0;
    }

    String extractFeedback(String review) {
        if (review == null) {
            return "";
        }
        int idx = review.indexOf("### \u4fee\u6539\u5efa\u8bae");
        if (idx >= 0) {
            return review.substring(idx);
        }
        idx = review.indexOf("\u4fee\u6539\u5efa\u8bae");
        if (idx >= 0) {
            return review.substring(idx);
        }
        return review;
    }

    /**
     * HITL 判断: 四条触发规则，决定是否必须人工审核。
     * 规则 1: Development 阶段输出超过 5000 字 → 长文本格式易漂移
     * 规则 2: Review 评分 < 60 → 低分内容有风险
     * 规则 3: 触发了降级（DEGRADED_PREFIX）→ 异常路径需确认
     * 规则 4: 危险关键词（毒品/暴力/歧视/自残）→ 合规要求
     */
    public boolean shouldRequestHumanReview(AddrfResult result, String subject, Long userId) {
        // 规则 1: Development 输出超过 5000 字
        if (result.development != null && result.development.length() > 5000) {
            logger.info("[ADDRF-HITL] 规则1触发: Development 输出过长 ({}字)", result.development.length());
            result.needsHumanReview = true;
            return true;
        }
        // 规则 2: Review 评分 < 60
        if (result.score < 60 && result.score > 0) {
            logger.info("[ADDRF-HITL] 规则2触发: Review 评分过低 ({})", result.score);
            result.needsHumanReview = true;
            return true;
        }
        // 规则 3: 存在降级输出（analysis/design/development/review 全覆盖）
        if ((result.analysis != null && result.analysis.startsWith(DEGRADED_PREFIX))
            || (result.design != null && result.design.startsWith(DEGRADED_PREFIX))
            || (result.development != null && result.development.startsWith(DEGRADED_PREFIX))
            || (result.review != null && result.review.startsWith(DEGRADED_PREFIX))) {
            logger.info("[ADDRF-HITL] 规则3触发: 存在降级输出");
            result.needsHumanReview = true;
            return true;
        }
        // 规则 4: 危险关键词检测
        String combined = (result.development != null ? result.development : "")
            + (result.review != null ? result.review : "")
            + (result.analysis != null ? result.analysis : "");
        if (containsUnsafeKeyword(combined)) {
            logger.info("[ADDRF-HITL] 规则4触发: 检测到危险关键词");
            result.needsHumanReview = true;
            return true;
        }
        return false;
    }

    private static final Set<String> UNSAFE_KEYWORDS = Set.of(
        "\u6bd2\u54c1", "\u66b4\u529b", "\u6b67\u89c6", "\u81ea\u6b8b",
        "\u8272\u60c5", "\u6050\u6016", "\u795e\u5974", "\u53cd\u52a8"
    );

    private boolean containsUnsafeKeyword(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String keyword : UNSAFE_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    public static class AddrfResult {
        // volatile: 多线程可见性保证（Review异步线程写入，主线程读取）
        public volatile String analysis;
        public volatile String design;
        public volatile String development;
        public volatile String review;
        public volatile String html;
        public volatile int score;
        // volatile: HITL标志位（asyncReview线程设置，LessonController主线程读取）
        public volatile boolean needsHumanReview = false;

        public Map<String, String> getStageOutputs() {
            return Map.of("analysis", this.analysis != null ? this.analysis : "", "design", this.design != null ? this.design : "", "development", this.development != null ? this.development : "", "review", this.review != null ? this.review : "");
        }
    }
}
