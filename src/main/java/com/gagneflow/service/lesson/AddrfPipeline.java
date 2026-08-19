package com.gagneflow.service.lesson;

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
    private final PersonalizationContextService personalizationService;
    private final com.gagneflow.service.memory.ConversationMemoryManager memoryManager;
    private final ThreadPoolExecutor executor;
    private final StringRedisTemplate redisTemplate;
    // 阶段C: 回灌延后到评分窗口关闭时定案, 需注入 VectorIndexService 执行入库(字段注入避免破坏直接 new 构造的单测)
    @Autowired(required = false)
    private com.gagneflow.service.vector.VectorIndexService vectorIndexService;
    // 2026-08-19 修复: 单例 future 多用户并发互相覆盖 -> 按 sessionId 隔离
    private final ConcurrentHashMap<String, CompletableFuture<Void>> reviewFutures = new ConcurrentHashMap<>();
    @Deprecated
    private volatile CompletableFuture<Void> reviewFuture;
    static final String CO_TERMINATE = "terminate";
    private static final String REVISE_PREFIX = "revise:";
    static final String TERMINATED = "_TERMINATED_";
    private static final Pattern DEDUP_SECTION_PATTERN = Pattern.compile("(?m)(?=^\\*\\*[^*]+\\*\\*)");
    private static final Pattern DEDUP_TITLE_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    // 2026-08-18: Analysis 意图理解 + 澄清(一期)
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.analysis-clarify-enabled:true}")
    private boolean analysisClarifyEnabled;
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.max-clarify-questions:3}")
    private int maxClarifyQuestions;
    // 2026-08-19: 澄清等待用户回答的窗口(秒), 超时降级不阻塞
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.clarify-wait-seconds:45}")
    private long clarifyWaitSeconds = 45L;
    // 2026-08-18: Analysis 缓存 TTL(小时), 由 1h 延长至 6h 减少重复调用
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.analysis-cache-ttl-hours:6}")
    private int analysisCacheTtlHours;
    // 2026-08-18: 进行中的流水线结果注册表(sessionId -> result), 供用户评分接口写入 userScore
    private final ConcurrentHashMap<String, AddrfResult> activeResults = new ConcurrentHashMap<>();
    // 2026-08-19 联调修复: 评分窗口时长(秒) — Review 完成后保留 entry, 供用户在查看完整教案后打分
    // 用实例字段(非 static final)便于单测注入短窗口验证
    private long scoreWindowSeconds = 300L;

    // 2026-08-19: 反哺综合分权重 — 用户评分占比(0-1), LLM 评分占比 = 1 - userWeight
    // 个人教案库(user_id 隔离)不再需要"公共库质量优先", 用户主观认可应参与入库决策
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.feedback-user-weight:0.6}")
    private double feedbackUserWeight = 0.6;

    /** 供 LessonController 用户评分接口通过 sessionId 定位 result */
    public AddrfResult getActiveResult(String sessionId) {
        return sessionId == null ? null : this.activeResults.get(sessionId);
    }

    /** 供 LessonController 评分成功后主动移除, 提前关闭评分窗口 */
    public void removeActiveResult(String sessionId) {
        if (sessionId != null) {
            this.activeResults.remove(sessionId);
        }
    }

    /**
     * Review 完成后延迟移除注册表: 用户需先查看完整教案(updated 事件)再打分,
     * 若 Review 完成立即移除则评分必然 404(联调实测)。延迟 SCORE_WINDOW_SECONDS 兜底防泄漏。
     */
    private void scheduleScoreWindowClose(String sessionId) {
        if (sessionId == null) return;
        long windowMs = this.scoreWindowSeconds * 1000L;
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(windowMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 阶段C(回灌延后): 评分窗口关闭时最终定案。未评分用户走此路径(consolidateQuality 仅对竞态防御),
            // 已评分用户已在评分接口 maybeBackfillNow 定案并释放 entry。
            this.consolidateQuality(sessionId);
            AddrfResult r = this.activeResults.get(sessionId);
            if (r != null) {
                this.maybeBackfillNow(r);
            }
            this.activeResults.remove(sessionId);
            logger.debug("[ADDRF] 评分窗口关闭: sessionId={}", sessionId);
        }, this.executor);
    }

    @Autowired
    public AddrfPipeline(PromptLoader promptLoader, PromptRegistry promptRegistry,
                         PromptExperiment promptExperiment, PromptMetricsCollector promptMetrics,
                         K12CurriculumLoader k12Loader, FormatTool formatTool,
                         SubjectFormatLoader subjectFormatLoader, PipelineStageConfig stageConfig,
                         PersonalizationContextService personalizationService,
                         com.gagneflow.service.memory.ConversationMemoryManager memoryManager,
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
        this.personalizationService = personalizationService;
        this.memoryManager = memoryManager;
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

    public AddrfResult execute(LessonPlanRequest request, ChatModelPort chatModel, SseEmitter emitter, String mode, ConcurrentHashMap<String, BlockingQueue<String>> copilotQueues, String sessionContext, Long userId, String sessionId) {
        boolean devDegraded;
        String enhancedDevPrompt;
        String k12Ctx;
        AddrfResult result;
        block18: {
            CompletableFuture<Void> devFuture;
            block17: {
                result = new AddrfResult();
                // 2026-08-18: 注册进行中的 result, 供用户评分接口写入(execute 返回时移除)
                if (sessionId != null) {
                    this.activeResults.put(sessionId, result);
                }
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
                    // 2026-08-18: Analysis 前置意图理解 + 澄清问题(一期, 建议式不阻塞)
                    String clarifiedInput = this.runAnalysisClarify(chatModel, initialInput, emitter, userId, sessionId, copilotQueues, request.getSubject());
                    result.analysis = this.callStageWithRevise(chatModel, analysisPrompt, clarifiedInput, emitter, "analysis", mode, copilotQueues, 60, request.getSubject());
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
                // 个性化注入: 用户偏好贯穿 Design 阶段(开关控制, 无记忆则跳过)
                final String designPromptFinal = this.appendPersonalizedContext(
                        enhancedDesignPrompt, userId, sessionId, "design");
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
                // 个性化注入: 用户偏好贯穿 Development 阶段
                final String devPromptFinal = this.appendPersonalizedContext(
                        enhancedDevPrompt, userId, sessionId, "development");
                CompletableFuture<Void> designFuture = CompletableFuture.runAsync(() -> {
                    result.design = this.callStageWithRevise(chatModel, designPromptFinal, result.analysis, emitter, "design", mode, copilotQueues, 60, request.getSubject());
                    logger.info("[ADDRF] Design \u5b8c\u6210, {} \u5b57\u7b26", (Object)(result.design != null ? result.design.length() : 0));
                }, this.executor).exceptionally(ex -> {
                    logger.error("[ADDRF] Design \u9636\u6bb5\u5185\u90e8\u5f02\u5e38\u88ab\u5f02\u6b65\u4efb\u52a1\u6355\u83b7", ex);
                    result.design = "[\u7cfb\u7edf\u63d0\u793a: Design \u9636\u6bb5\u5185\u90e8\u9519\u8bef]";
                    return null;
                });
                devFuture = CompletableFuture.runAsync(() -> {
                    result.development = this.callStageWithRevise(chatModel, devPromptFinal, result.analysis, emitter, "development", mode, copilotQueues, 180, request.getSubject());
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
        this.emitStageComplete(emitter, "stage:format", result.html, sessionId);
        if (!devDegraded && result.development != null && !result.development.startsWith(DEGRADED_PREFIX)) {
            String finalK12 = k12Ctx;
            String finalSubject = request.getSubject();
            CompletableFuture<Void> reviewTask = CompletableFuture.runAsync(() -> {
                logger.info("[ADDRF] Review \u540e\u53f0\u5f00\u59cb");
                this.asyncReview(result, chatModel, enhancedDevPrompt, emitter, finalK12, finalSubject, userId, sessionId);
                logger.info("[ADDRF] Review \u540e\u53f0\u5b8c\u6210, \u8bc4\u5206: {}", (Object)result.score);
                // 2026-08-18: 用户低分一票否决(1-2星) -> 无论 LLM 分, 标记人工审核
                if (result.userScore >= 1 && result.userScore <= 2) {
                    logger.warn("[ADDRF-HITL] 用户低分一票否决: userScore={}, llmScore={}, uid={}",
                            result.userScore, result.score, userId);
                    result.needsHumanReview = true;
                }
                // HITL 检查: 判断是否需要人工审核
                if (shouldRequestHumanReview(result, request.getSubject(), userId)) {
                    logger.warn("[ADDRF-HITL] \u89e6\u53d1\u4eba\u5de5\u5ba1\u6838: subject={}, score={}, uid={}",
                        request.getSubject(), result.score, userId);
                }
                try {
                    result.html = this.formatTool.format(result.analysis, result.design, result.development, result.review);
                    emitter.send(SseEmitter.event().name("message").data(Map.of("type", "stage:format", "content", result.html, "stage", "format", "updated", true, "sessionId", sessionId)));
                }
                catch (IllegalStateException e) {
                    // 2026-08-19 联调: emitter 已完成(done 后框架关闭)属预期时序, 教案已持久化兜底, 静默降级
                    logger.trace("[ADDRF] Review 更新 HTML 跳过 (emitter 已关闭): {}", e.getMessage());
                }
                catch (Exception e) {
                    logger.warn("[ADDRF] Review 更新 HTML 推送失败: {}", e.getMessage());
                }
                try {
                    this.emitStageComplete(emitter, "stage:review", result.review);
                }
                catch (RuntimeException e) {
                    // emitter 已关闭时 emitStageComplete 内部抛 IllegalStateException, 属预期
                    logger.trace("[ADDRF] stage:review 推送跳过 (emitter 已关闭): {}", e.getMessage());
                }
                // 2026-08-19 联调修复: 延迟关闭评分窗口 — Review 完成后保留 entry 供用户打分,
                // 立即移除会导致用户评分必然 404(前端在 Review 完成后才展示评分面板)
                this.scheduleScoreWindowClose(sessionId);
            }, this.executor);
            // 2026-08-19 修复: 按 sessionId 存 future(消除多用户并发覆盖), 完成后移除
            if (sessionId != null) {
                this.reviewFutures.put(sessionId, reviewTask);
                reviewTask.whenComplete((v, t) -> this.reviewFutures.remove(sessionId));
            }
            this.reviewFuture = reviewTask;
        } else {
            result.review = "[\u7cfb\u7edf\u63d0\u793a: \u6559\u6848\u4e3b\u4f53\u4e0d\u5b8c\u6574\uff0c\u8df3\u8fc7\u8bc4\u4f30]";
            this.emitStageComplete(emitter, "stage:review", result.review);
            // 2026-08-19 联调修复: 同样延迟关闭评分窗口(与正常路径一致)
            this.scheduleScoreWindowClose(sessionId);
        }
        return result;
    }

    public void awaitReview(AddrfResult result, long timeoutSeconds) {
        // 兼容旧签名: 无 sessionId 时降级用单例(向后兼容, 不推荐)
        this.awaitReview(result, timeoutSeconds, null);
    }

    /**
     * Wait for the background Review task to complete.
     * 2026-08-19 修复: 按 sessionId 取对应 future, 消除多用户并发互相覆盖竞态。
     */
    public void awaitReview(AddrfResult result, long timeoutSeconds, String sessionId) {
        CompletableFuture<Void> future = null;
        if (sessionId != null) {
            future = this.reviewFutures.get(sessionId);
        }
        if (future == null) {
            future = this.reviewFuture; // 旧单例兜底
        }
        if (future != null) {
            try {
                future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logger.warn("[ADDRF] Review background task timed out after {}s", timeoutSeconds);
            } catch (Exception e) {
                logger.warn("[ADDRF] Review background task failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Analysis 前置意图理解(2026-08-18 一期):
     * 调用意图理解 prompt 生成 [意图摘要] + [澄清问题], 通过独立 SSE 事件建议式推送(不阻塞)。
     * 返回合并了意图摘要的输入(供正式 Analysis 使用); 任何异常回退为原始输入。
     */
    private String runAnalysisClarify(ChatModelPort chatModel, String userInput, SseEmitter emitter,
                                      Long userId, String sessionId,
                                      ConcurrentHashMap<String, BlockingQueue<String>> copilotQueues,
                                      String subject) {
        if (!this.analysisClarifyEnabled) {
            return userInput;
        }
        String intentPrompt;
        try {
            intentPrompt = this.loadPrompt("addrf_analysis_intent", userId);
        } catch (Exception e) {
            logger.debug("[ADDRF] 意图理解 prompt 不可用, 跳过澄清: {}", e.getMessage());
            return userInput;
        }
        if (intentPrompt == null || intentPrompt.isBlank()) {
            return userInput;
        }
        try {
            String intentOutput = this.callAgent(chatModel, intentPrompt, userInput, emitter, "analysis_intent", "quick", 60, subject);
            if (intentOutput == null || intentOutput.isBlank()) {
                return userInput;
            }
            String intentSummary = extractIntentSection(intentOutput, "意图摘要");
            String questions = extractIntentSection(intentOutput, "澄清问题");
            // 建议式推送 + 等待用户回答(二期 2026-08-19): 限时等待, 超时降级不阻塞
            String clarifiedInput = userInput;
            String token = null;
            if (questions != null && !questions.isBlank()
                    && !questions.contains("无需澄清") && emitter != null) {
                try {
                    // 1. 生成 token 并放入 copilotQueues(复用现有交互机制)
                    token = "clarify_" + java.util.UUID.randomUUID().toString().substring(0, 8);
                    java.util.concurrent.BlockingQueue<String> queue = new java.util.concurrent.LinkedBlockingQueue<>(1);
                    copilotQueues.put(token, queue);
                    // 2. 推送问题 + token(前端提交回答用)
                    emitter.send(SseEmitter.event().name("message")
                            .data(java.util.Map.of("type", "analysis_clarify", "questions", questions, "token", token)));
                    logger.info("[ADDRF] Analysis 澄清问题已推送(等待回答): {}", questions.length());
                    // 3. 限时等待用户回答(默认 45s, 可配)
                    String answer = queue.poll(this.clarifyWaitSeconds, java.util.concurrent.TimeUnit.SECONDS);
                    if (answer != null && !answer.isBlank()) {
                        clarifiedInput = userInput + "\n\n[用户回答澄清问题]: " + answer.trim();
                        logger.info("[ADDRF] 收到澄清回答({} 字), 已合并进分析输入", answer.trim().length());
                        // 4. 回答写入 LTM(USER_EXPLICIT) -> 下次生成自动复用, 越问越少
                        if (this.memoryManager != null) {
                            try {
                                this.memoryManager.storeUserPreference(userId, sessionId, "消息偏好", answer.trim());
                            } catch (Exception ltmEx) {
                                logger.debug("[ADDRF] 澄清回答写入 LTM 失败: {}", ltmEx.getMessage());
                            }
                        }
                    } else {
                        logger.debug("[ADDRF] 澄清等待超时(＜{}s), 用原始输入继续", this.clarifyWaitSeconds);
                    }
                } catch (Exception e) {
                    logger.trace("[ADDRF] 澄清等待失败 (emitter 可能已关闭): {}", e.getMessage());
                } finally {
                    if (token != null) {
                        copilotQueues.remove(token);
                    }
                }
            }
            // 意图摘要合并进输入, 供正式 Analysis 参考
            if (intentSummary != null && !intentSummary.isBlank()) {
                return clarifiedInput + "\n\n[意图理解摘要(仅供分析参考)]: " + intentSummary;
            }
            return clarifiedInput;
        } catch (Exception e) {
            logger.warn("[ADDRF] 意图理解失败, 回退原始输入: {}", e.getMessage());
            return userInput;
        }
    }

    /** 从意图理解输出中提取 **标签** 段落 */
    private String extractIntentSection(String output, String label) {
        if (output == null) return null;
        int start = output.indexOf("**" + label + "**");
        if (start < 0) {
            start = output.indexOf(label + ":");
            if (start < 0) return null;
        } else {
            start = output.indexOf('\n', start);
        }
        // 找下一个 **标签** 或结尾
        int end = output.indexOf("\n**", start > 0 ? start : 0);
        if (end < 0) end = output.length();
        String section = output.substring(start < 0 ? 0 : start + 1, end).trim();
        // 去掉行首的 - 或数字序号
        return section.replaceAll("(?m)^\\s*[-\\d.、]+\\s*", "").trim();
    }

    private String callStageWithRevise(ChatModelPort chatModel, String systemPrompt, String userInput, SseEmitter emitter, String stage, String mode, ConcurrentHashMap<String, BlockingQueue<String>> copilotQueues, int timeoutSec, String subject) {
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

    private void asyncReview(AddrfResult result, ChatModelPort chatModel, String devPrompt, SseEmitter emitter, String k12Ctx, String subject, Long userId, String sessionId) {
        int prevScore = -1;
        for (int retryCount = 0; !(retryCount > 2 || result.development != null && result.development.startsWith(DEGRADED_PREFIX)); ++retryCount) {
            String reviewPrompt = this.loadPrompt("addrf_review", userId) + "\n\n\u8bfe\u7a0b\u6807\u51c6\uff1a\n" + k12Ctx;
            // 个性化注入: 用户评价标准入 Review 评分(核心), 避免按通用标准误判个性化教案
            reviewPrompt = this.appendPersonalizedContext(reviewPrompt, userId, sessionId, "review");
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
            // 通过(>=70)或达到最大重试(2): 结束
            if (result.score >= 70 || retryCount >= 2) break;
            // 收敛检测: 上一次重试后评分未有提升(Δ<5), 继续修改无意义, 停止自愈(业界 evaluator-optimizer 收敛检测)
            if (prevScore >= 0 && Math.abs(result.score - prevScore) < 5) {
                logger.info("[ADDRF-REVIEW] \u6536\u655b\u68c0\u6d4b: score={}(\u4e0a\u8f6e{}), \u5dee\u5f02\u5c0f\u4e8e5\u5206, \u505c\u6b62\u81ea\u6108", result.score, prevScore);
                break;
            }
            prevScore = result.score;
            String feedback = this.extractFeedback(result.review);
            result.development = this.callAgent(chatModel, devPrompt + "\n\u4fee\u6539\u610f\u89c1\uff1a\n" + feedback, result.analysis, null, "development", "quick", 180, subject);
            if (result.development == null || result.development.startsWith(DEGRADED_PREFIX)) break;
            // 自愈闭环: 重跑后的新 development 必须回传给前端(否则用户看到旧 development+新 review 的不一致组合)
            this.pushRefreshedDevelopment(emitter, result, sessionId);
        }
    }

    /**
     * 阶段B: 重跑 development 后, 重新生成完整 HTML 并回传前端(修复自愈不闭环问题)。
     * - 重新 format 生成 result.html, 以 stage:format(完整内容) 推送, 前端刷为修复后完整版
     * - 再补一个 stage:development 预览(updated=true) 指示该阶段已更新
     */
    private void pushRefreshedDevelopment(SseEmitter emitter, AddrfResult result, String sessionId) {
        try {
            result.html = this.formatTool.format(result.analysis, result.design, result.development, result.review);
        } catch (Exception e) {
            logger.warn("[ADDRF-REVIEW] \u81ea\u6108\u540e\u91cd\u65b0\u683c\u5f0f\u5316\u5931\u8d25: {}", e.getMessage());
            return;
        }
        try {
            emitStageComplete(emitter, "stage:development", result.development, sessionId);
            if (emitter != null) {
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("type", "stage:format");
                data.put("content", result.html);
                data.put("stage", "format");
                data.put("updated", true);
                data.put("sessionId", sessionId);
                emitter.send(SseEmitter.event().name("message").data(data));
            }
        } catch (IllegalStateException e) {
            // emitter 已关闭(SSE 已 done)属预期, 教案已持久化兜底
            logger.trace("[ADDRF-REVIEW] \u81ea\u6108\u56de\u4f20\u8df3\u8fc7 (emitter \u5df2\u5173\u95ed): {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("[ADDRF-REVIEW] \u81ea\u6108\u56de\u4f20\u5931\u8d25: {}", e.getMessage());
        }
    }

    private String callAgent(ChatModelPort chatModel, String systemPrompt, String userInput, SseEmitter emitter, String stage, String mode, String subject) {
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

    private String callAgent(ChatModelPort chatModel, String systemPrompt, String userInput, SseEmitter emitter, String stage, String mode, int timeoutSec) {
        return this.callAgent(chatModel, systemPrompt, userInput, emitter, stage, mode, timeoutSec, null);
    }

    private String callAgent(ChatModelPort chatModel, String systemPrompt, String userInput, SseEmitter emitter, String stage, String mode, int timeoutSec, String subject) {
        // 2026-08-19 优化: 去掉 executor.submit 嵌套(每阶段只占 1 线程, 不再编排线程等 worker 空转)
        // 超时控制改为 Flux.timeout 操作符, 同线程流式 + 超时
        int maxTokens = this.resolveMaxTokens(stage, subject);
        try {
            Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userInput)), (ChatOptions)DashScopeChatOptions.builder().withMaxToken(Integer.valueOf(maxTokens)).build());
            StringBuilder full = new StringBuilder();
            Flux<ChatResponse> flux = chatModel.stream(prompt);
            flux.timeout(java.time.Duration.ofSeconds(timeoutSec)).doOnNext(response -> {
                String chunk;
                if (response != null && response.getResult() != null && (chunk = response.getResult().getOutput().getText()) != null) {
                    full.append(chunk);
                    this.emitInterim(emitter, stage, chunk);
                }
            }).blockLast();
            return full.toString();
        }
        catch (Exception e) {
            // 超时/流异常 -> 降级 call()
            if (e instanceof java.util.concurrent.TimeoutException) {
                logger.warn("[ADDRF] {} \u8d85\u65f6({}s), \u964d\u7ea7 call()", (Object)stage, (Object)timeoutSec);
            } else {
                logger.warn("[ADDRF] {} stream() \u964d\u7ea7 call(): {}", (Object)stage, (Object)e.getMessage());
            }
            try {
                Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userInput)), (ChatOptions)DashScopeChatOptions.builder().withMaxToken(Integer.valueOf(maxTokens)).build());
                ChatResponse response2 = chatModel.call(prompt);
                if (response2 != null && response2.getResult() != null) {
                    AssistantMessage msg = response2.getResult().getOutput();
                    return msg != null ? msg.getText() : "";
                }
            }
            catch (Exception e2) {
                logger.warn("[ADDRF] {} stream() \u964d\u7ea7 call() \u4e5f\u5931\u8d25: {}", stage, e2.getMessage());
            }
            return "";
        }
    }

    // 2026-08-19: 节流缓冲 - 按 stage 累积, 达 50 字或 100ms 才推送(改善前端观感, 减少 SSE 消息数)
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> interimBuffers = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> interimLastFlush = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int INTERIM_FLUSH_CHARS = 50;
    // 2026-08-19 修复: 100ms 太短(LLM 吐字间隔本身就 >100ms), 时间条件每次触发导致节流失效
    // 改 500ms 兜底: 50 字为主触发, 时间只防残留卡住(慢速吐字时也能流畅输出)
    private static final long INTERIM_FLUSH_MS = 500L;

    private void emitInterim(SseEmitter emitter, String stage, String chunk) {
        if (emitter == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        // 节流累积
        StringBuilder buf = this.interimBuffers.computeIfAbsent(stage, k -> new StringBuilder());
        boolean flush;
        synchronized (buf) {
            buf.append(chunk);
            long now = System.currentTimeMillis();
            // 2026-08-19 修复: computeIfAbsent 而非 getOrDefault(0) - 首次调用记录时间但不触发时间条件
            // (旧实现 now-0 远超 500ms, 第一个 chunk 就立即 flush, 破坏节流)
            long last = this.interimLastFlush.computeIfAbsent(stage, k -> now);
            flush = buf.length() >= INTERIM_FLUSH_CHARS || (now - last) >= INTERIM_FLUSH_MS;
        }
        if (flush) {
            flushInterim(emitter, stage);
        }
    }

    /** 推送缓冲内容并重置 */
    private void flushInterim(SseEmitter emitter, String stage) {
        StringBuilder buf = this.interimBuffers.get(stage);
        if (buf == null) return;
        String batch;
        synchronized (buf) {
            if (buf.length() == 0) return;
            batch = buf.toString();
            buf.setLength(0);
            this.interimLastFlush.put(stage, System.currentTimeMillis());
        }
        try {
            emitter.send(SseEmitter.event().name("message").data(Map.of("type", "stage:content", "stage", stage, "chunk", batch)));
        }
        catch (IOException | IllegalStateException e) {
            logger.trace("[ADDRF] 临时块推送失败 (emitter 可能已关闭): {}", e.getMessage());
        }
    }

    private void emitStageComplete(SseEmitter emitter, String eventType, String content) {
        this.emitStageComplete(emitter, eventType, content, null);
    }

    /**
     * B1 前端重构配合: stage:format 事件附带真实教案 sessionId, 供前端评分/PDF 使用。
     * 新增字段不破坏既有契约 (旧前端忽略未知字段)。
     */
    private void emitStageComplete(SseEmitter emitter, String eventType, String content, String sessionId) {
        if (emitter == null) {
            return;
        }
        // 2026-08-19: 阶段完成前先 flush 节流缓冲的残余内容(避免 <50 字的内容滞留)
        try {
            String flushStage = eventType.replace("stage:", "");
            this.flushInterim(emitter, flushStage);
        } catch (Exception ignored) {
        }
        try {
            String stage = eventType.replace("stage:", "");
            String safe = content != null ? content.substring(0, Math.min(content.length(), 500)) : "";
            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("type", eventType);
            data.put("content", safe);
            data.put("stage", stage);
            if (sessionId != null) {
                data.put("sessionId", sessionId);
            }
            emitter.send(SseEmitter.event().name("message").data(data));
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
            this.redisTemplate.opsForValue().set(cacheKey, analysis, Duration.ofHours(this.analysisCacheTtlHours));
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

    /**
     * 追加个性化上下文段到 prompt(2026-08-17 新增)。
     * 无记忆/开关关闭/异常时原样返回, 不阻塞流水线。
     */
    private String appendPersonalizedContext(String prompt, Long userId, String sessionId, String stage) {
        try {
            String personalized = this.personalizationService.getContext(userId, sessionId, stage);
            if (personalized == null || personalized.isBlank()) {
                return prompt;
            }
            return prompt + personalized;
        } catch (Exception e) {
            logger.warn("[ADDRF] {} 阶段个性化上下文注入失败, 跳过: {}", stage, e.getMessage());
            return prompt;
        }
    }

    private String loadPrompt(String name, Long userId) {        try {
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
        // 多写法兜底(与前端 parseScore 对齐, 消除 P4 契约漂移): 依次尝试
        // 1) SCORE_JSON "score":N / "score":N(有无引号均可)  2) 总分: N  3) N/100
        Matcher m = Pattern.compile("\"score\"\\s*[:：]\\s*(\\d+)").matcher(review);
        if (m.find()) {
            return clampScore(Integer.parseInt(m.group(1)));
        }
        m = Pattern.compile("score\\s*[:：]\\s*(\\d+)").matcher(review);
        if (m.find()) {
            return clampScore(Integer.parseInt(m.group(1)));
        }
        m = Pattern.compile("\u603b\u5206\\s*[:：]?\\s*(\\d+)").matcher(review);
        if (m.find()) {
            return clampScore(Integer.parseInt(m.group(1)));
        }
        m = Pattern.compile("(\\d+)\\s*\\/\\s*100").matcher(review);
        if (m.find()) {
            return clampScore(Integer.parseInt(m.group(1)));
        }
        logger.warn("[ADDRF] 无法从 review 内容中解析评分，默认视为不通过: review 前200字符={}", 
                review.length() > 200 ? review.substring(0, 200) : review);
        return 0;
    }

    /**
     * 2026-08-19: 反哺综合分 — 个人教案库(user_id 隔离)场景下, 用户主观认可应参与入库决策与检索门槛。
     * 规则:
     *  - 未评分(userScore=0)或低分(1-2 星, 已被 HITL 阻断不会到达) -> 用 LLM 分(现状兜底)
     *  - 3-5 星: 综合分 = userWeight * (userScore*20) + (1-userWeight) * llmScore
     *    星数 x20 归一化到 100 分制; userWeight 默认 0.6(用户 6 成, LLM 4 成)
     */
    public int resolveFeedbackScore(AddrfResult r) {
        if (r == null) {
            return 0;
        }
        int llm = r.score;
        int user = r.userScore;
        if (user < 3) {
            return llm; // 未评分或 1-2 星(阻断路径)
        }
        double userPct = user * 20.0; // 1-5 星 -> 20-100 分
        double w = this.feedbackUserWeight;
        if (w <= 0 || w >= 1) {
            w = 0.6; // 配置越界兜底
        }
        return clampScore((int) Math.round(w * userPct + (1.0 - w) * llm));
    }

    /** 评分范围钳制 0-100, 避免 LLM 输出越界 */
    private static int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
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
     * HITL 判定: 对"必须人工审核"的信号做等级分流, 而非无差别任一触发。
     *   blocked(needsHumanReview=true, 主线程注入红色警示并跳过回灌):
     *     - 评分过低(score<60)
     *     - 存在降级输出(DEGRADED_PREFIX)
     *     - 危险关键词(合规)
     *     - 用户 1-2 星否决(userScore<=2, 与 asyncReview 结束时判定互补, 见 consolidateQuality)
     *   info/warn(qualityNote, 仅提示不阻断回灌/不弹红色):
     *     - Development 输出超长(>5000字) → 仅提示长文可能格式漂移, 不再误报为质量风险
     * 用户低分否决的最终定案在评分窗口关闭时由 consolidateQuality 完成(解决评审晚于评分面板展示的时序)。
     */
    public boolean shouldRequestHumanReview(AddrfResult result, String subject, Long userId) {
        // info: Development 输出超过 5000 字 → 仅提示(长文本易漂移), 不触发人工审核(方案 C 降级)
        if (result.development != null && result.development.length() > 5000) {
            result.qualityNote = "\u8b66\u793a\uff1a\u6559\u6848\u6559\u5b66\u8fc7\u7a0b\u8f83\u957f(" + result.development.length()
                + "\u5b57)\uff0c\u53ef\u80fd\u5b58\u5728\u683c\u5f0f\u6f02\u79fb\uff0c\u5efa\u8bae\u68c0\u67e5\u8868\u683c\u4e0e\u5206\u7ae0\u3002";
            logger.info("[ADDRF-HITL] \u63d0\u793a: Development \u8f93\u51fa\u8fc7\u957f ({}字, \u4ec5\u8b66\u544a\u4e0d\u963b\u65ad)", result.development.length());
        }
        // blocked: Review 评分 < 60
        if (result.score < 60 && result.score > 0) {
            logger.info("[ADDRF-HITL] blocked: Review \u8bc4\u5206\u8fc7\u4f4e ({})", result.score);
            result.needsHumanReview = true;
            return true;
        }
        // blocked: 存在降级输出（analysis/design/development/review 全覆盖）
        if ((result.analysis != null && result.analysis.startsWith(DEGRADED_PREFIX))
            || (result.design != null && result.design.startsWith(DEGRADED_PREFIX))
            || (result.development != null && result.development.startsWith(DEGRADED_PREFIX))
            || (result.review != null && result.review.startsWith(DEGRADED_PREFIX))) {
            logger.info("[ADDRF-HITL] blocked: \u5b58\u5728\u964d\u7ea7\u8f93\u51fa");
            result.needsHumanReview = true;
            return true;
        }
        // blocked: 危险关键词检测（合规）
        String combined = (result.development != null ? result.development : "")
            + (result.review != null ? result.review : "")
            + (result.analysis != null ? result.analysis : "");
        if (containsUnsafeKeyword(combined)) {
            logger.info("[ADDRF-HITL] blocked: \u68c0\u6d4b\u5230\u5371\u9669\u5173\u952e\u8bcd");
            result.needsHumanReview = true;
            return true;
        }
        // blocked: 用户 1-2 星一票否决（快路径, 适用于评分早于 review 完成的情况）
        if (result.userScore >= 1 && result.userScore <= 2) {
            logger.warn("[ADDRF-HITL] blocked: \u7528\u6237\u4f4e\u5206\u4e00\u7968\u5426\u51b3: userScore={}, llmScore={}, uid={}",
                result.userScore, result.score, userId);
            result.needsHumanReview = true;
            return true;
        }
        return false;
    }

    /**
     * 阶段C: 评分窗口关闭前的最终质量定案(解决 P1 时序竞态 —— 用户评分面板在 stage:review 后才展示,
     * 而 asyncReview 的 HITL 判定可能已完成)。
     * 在 scheduleScoreWindowClose 真正移除 activeResults 前触发: 若用户已在窗口内打分且为 1-2 星, 强制 needsHumanReview,
     * 从而使主线程走 HITL 分支并跳过回灌。用户来得再晚也会在窗口关闭时被覆盖。
     */
    private void consolidateQuality(String sessionId) {
        if (sessionId == null) return;
        AddrfResult result = this.activeResults.get(sessionId);
        if (result == null) return;
        if (result.userScore >= 1 && result.userScore <= 2 && !result.needsHumanReview) {
            result.needsHumanReview = true;
            logger.warn("[ADDRF-QUALITY] \u8bc4\u5206\u7a97\u53e3\u5173\u95ed\u5b9a\u6848: \u7528\u6237\u4f4e\u5206\u4e00\u7968\u5426\u51b3\u751f\u6548 userScore={}, llmScore={}, sessionId={}",
                result.userScore, result.score, sessionId);
        }
    }

    /**
     * 阶段C(回灌延后): 评分窗口关闭且最终未被 HITL 阻断时, 将教案回灌个人库供后续检索复用。
     * 由主线程置 scheduleBackfill+暂存参数, 窗口关闭回调调用(保证用户低分否决能正确阻断入库)。
     */
    private void backfillLessonPlan(AddrfResult r) {
        if (r == null || r.html == null || r.backfillUid == null) {
            logger.debug("[ADDRF] \u56de\u704c\u672a\u6267\u884c: \u7f3a\u5c11\u56de\u704c\u53c2\u6570(html/uid)");
            return;
        }
        if (this.vectorIndexService == null) {
            logger.warn("[ADDRF] \u56de\u704c\u8df3\u8fc7: vectorIndexService \u672a\u6ce8\u5165(\u975e Spring \u73af\u5883)");
            return;
        }
        String subject = r.backfillSubject;
        // 2026-08-19: 回灌分数改为综合分(用户评分参与) — 个人教案库场景用户认可应影响入库与检索门槛
        int finalScore = this.resolveFeedbackScore(r);
        this.vectorIndexService.indexLessonPlan(r.html, r.backfillUid, subject, finalScore);
    }

    /**
     * 阶段C: 立即按当前质量状态决定是否回灌(评分接口在释放 entry 前调用)。
     * 好评(>=3星)且未阻断 -> 立即回灌; 低分/已 HITL 阻断 -> 不回灌。
     * 未评分用户由评分窗口关闭回调(consolidateQuality + backfill)触达。
     */
    public void maybeBackfillNow(AddrfResult r) {
        if (r == null) return;
        if (r.scheduleBackfill && !r.needsHumanReview) {
            try {
                backfillLessonPlan(r);
            } catch (Exception e) {
                logger.warn("[ADDRF] \u56de\u704c\u5f02\u5e38(\u4e0d\u5f71\u54cd\u4e3b\u6d41): {}", e.getMessage());
            }
        }
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
        // 阶段C: 非阻断质量提示(如超长警告), 供前端/日志展示, 不触发人工审核
        public volatile String qualityNote = null;
        // 2026-08-18: 用户评分(1-5星, 0=未评分)。用户评分线程写入, asyncReview线程读取
        public volatile int userScore = 0;
        // 阶段C: 回灌延后到评分窗口关闭时定案(用户低分否决能正确阻断入库)。主线程置位并暂存参数。
        public volatile boolean scheduleBackfill = false;
        public volatile Long backfillUid = null;
        public volatile String backfillSubject = null;

        public Map<String, String> getStageOutputs() {
            return Map.of("analysis", this.analysis != null ? this.analysis : "", "design", this.design != null ? this.design : "", "development", this.development != null ? this.development : "", "review", this.review != null ? this.review : "");
        }
    }
}
