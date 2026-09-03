package com.gagneflow.service.lesson;

import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
import jakarta.annotation.PreDestroy;
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
    // 2026-08-31: 上传参考资料注入上限(条数/单条截断)
    private static final int UPLOADED_DOCS_TOP_K = 3;
    private static final int UPLOADED_DOC_SNIPPET_CHARS = 400;
    private static final Set<String> TEXT_HEAVY_SUBJECTS = Set.of("\u8bed\u6587", "\u82f1\u8bed", "\u5386\u53f2", "\u653f\u6cbb");
    private static final String DEGRADED_PREFIX = "[\u7cfb\u7edf\u63d0\u793a";
    // 2026-08-31: 超时部分保留标记(与 DEGRADED 语义独立): 交付已生成部分而非重做/占位
    private static final String PARTIAL_PREFIX = "[\u8f93\u51fa\u622a\u65ad";
    // 2026-08-31 v2: 续写最小长度阈值 — partial 低于此值时续写无意义(=更短预算的重做), 直接 RECALL_FULL
    private static final int MIN_CONTINUE_CHARS = 50;
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
    // P1-4: 评分窗口定时关闭用独立 ScheduledExecutorService, 替代在共享线程池上 Thread.sleep
    // (原实现在 core=10 的共享池上 sleep 最长 30min, 并发教案多份会抽空核心线程)
    private volatile ScheduledExecutorService windowScheduler;
    // 阶段C: 回灌延后到评分窗口关闭时定案, 需注入 VectorIndexService 执行入库(字段注入避免破坏直接 new 构造的单测)
    @Autowired(required = false)
    private com.gagneflow.service.vector.VectorIndexService vectorIndexService;
    // 2026-08-23 quick 直出 HTML: 降级指标(PipelineMetrics 字段注入, 可空容错, 避免破坏直接 new 构造的单测)
    @Autowired(required = false)
    private com.gagneflow.service.metrics.PipelineMetrics pipelineMetrics;
    // 2026-08-31: 上传参考资料检索注入(修复"传了资料但生成不用"的传导断链; 字段注入可空容错)
    @Autowired(required = false)
    private com.gagneflow.service.vector.VectorSearchService vectorSearchService;
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
    // 2026-08-19: 澄清等待用户回答的窗口(秒), 超时降级不阻塞; 2026-08-31 默认 45->90(真实用户打字/思考需要时间)
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.clarify-wait-seconds:90}")
    private long clarifyWaitSeconds = 90L;
    // 2026-08-31: copilot stage_await 等待用户确认的超时(秒), 由硬编码 120s 提为可配且默认 180s(与前端 CopilotConfirm.AWAIT_TIMEOUT_SEC 同步)
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.await-timeout-seconds:180}")
    private long awaitTimeoutSeconds = 180L;
    // 2026-08-31 超时完整度改进: 部分保留/断点续写(详见 application.yml gagneflow.addrf 段注释)
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.timeout-partial-keep:true}")
    private boolean timeoutPartialKeep = true;
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.partial-keep-chars:300}")
    private int partialKeepChars = 300;
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.continue-budget-seconds:45}")
    private int continueBudgetSeconds = 45;
    // 2026-08-31 v2 双层计时: L1 挂死检测(chunk 间隔, 含 TTFB) — 与 L2 总预算(Flux.take(timeoutSec))分离
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.gap-timeout-sec:45}")
    private int gapTimeoutSeconds = 45;
    // 2026-08-18: Analysis 缓存 TTL(小时), 由 1h 延长至 6h 减少重复调用
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.analysis-cache-ttl-hours:6}")
    private int analysisCacheTtlHours;
    // 2026-08-18: 进行中的流水线结果注册表(sessionId -> result), 供用户评分接口写入 userScore
    private final ConcurrentHashMap<String, AddrfResult> activeResults = new ConcurrentHashMap<>();
    // 2026-08-19 联调修复: 评分窗口时长(秒) — Review 完成后保留 entry, 供用户在查看完整教案后打分
    // 用实例字段(非 static final)便于单测注入短窗口验证
    private long scoreWindowSeconds = 300L;
    // 2026-08-21: 评分窗口"延迟激活"改造 — Review 完成后先启动的长兜底窗口(分钟),
    // 防 activeResults 泄漏(用户从未打开教案/关闭页面); 前端实际展示教案时调 activateScoreWindow 进入正式窗口
    private static final long TOTAL_WINDOW_MINUTES = 30L;

    // 2026-08-23 quick 直出 HTML: MD 降级发生次数(用于观测 LLM 对"输出纯 HTML"的遵守度, 指导后续调 prompt)
    private final java.util.concurrent.atomic.AtomicLong quickMarkdownFallbackCount = new java.util.concurrent.atomic.AtomicLong(0);

    // 2026-08-19: 反哺综合分权重 — 用户评分占比(0-1), LLM 评分占比 = 1 - userWeight
    // 个人教案库(user_id 隔离)不再需要"公共库质量优先", 用户主观认可应参与入库决策
    @org.springframework.beans.factory.annotation.Value("${gagneflow.addrf.feedback-user-weight:0.6}")
    private double feedbackUserWeight = 0.6;

    /** 供 LessonController 用户评分接口通过 sessionId 定位 result */
    public AddrfResult getActiveResult(String sessionId) {
        return sessionId == null ? null : this.activeResults.get(sessionId);
    }

    /** 2026-08-23: quick 直出 HTML 的 MD 降级次数(观测 LLM 遵守度) */
    public long getQuickMarkdownFallbackCount() {
        return this.quickMarkdownFallbackCount.get();
    }

    /** 供 LessonController 评分成功后主动移除, 提前关闭评分窗口 */
    public void removeActiveResult(String sessionId) {
        if (sessionId != null) {
            this.activeResults.remove(sessionId);
        }
    }

    /**
     * Review 完成后延迟移除注册表: 用户需先查看完整教案(updated 事件)再打分,
     * 若 Review 完成立即移除则评分必然 404(联调实测)。延迟 windowSec 兜底防泄漏。
     * 窗口关闭时最终定案(consolidateQuality + maybeBackfillNow)后移除 entry。
     */
    private void scheduleScoreWindowClose(String sessionId, long windowSec) {
        if (sessionId == null) return;
        // P1-4: 用独立 ScheduledExecutorService 定时执行, 不在共享线程池上 Thread.sleep(避免占线程).
        // 原实现 CompletableFuture.runAsync(executor) + sleep(windowMs), 兜底窗口 30min 会占住共享池线程.
        ScheduledExecutorService scheduler = this.windowScheduler;
        if (scheduler == null) {
            synchronized (this) {
                scheduler = this.windowScheduler;
                if (scheduler == null) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(
                            r -> { Thread t = new Thread(r, "adDRF-window"); t.setDaemon(true); return t; });
                    this.windowScheduler = scheduler;
                }
            }
        }
        scheduler.schedule(() -> {
            // 阶段C(回灌延后): 评分窗口关闭时最终定案。未评分用户走此路径(consolidateQuality 仅对竞态防御),
            // 已评分用户已在评分接口 maybeBackfillNow 定案并释放 entry。
            this.consolidateQuality(sessionId);
            AddrfResult r = this.activeResults.get(sessionId);
            if (r != null) {
                this.maybeBackfillNow(r);
            }
            this.activeResults.remove(sessionId);
            logger.debug("[ADDRF] 评分窗口关闭: sessionId={}", sessionId);
        }, windowSec, TimeUnit.SECONDS);
    }

    /**
     * 2026-08-21: 评分窗口"延迟激活" — 用户实际查看教案(前端打开工作台)时调用,
     * 启动正式评分窗口(scoreWindowSeconds)。幂等: 每个 sessionId 只激活一次。
     * <p>
     * 背景: 重新生成时新教案会被前端暂存(pendingNewIndex 闸门), 用户先处理旧教案,
     * 若 Review 完成即开始 300s 计时, 用户处理完旧教案再看新教案时窗口可能已关闭(评分 404)。
     * 改为"展示即激活": Review 后只启动长兜底窗口(30min), 用户真正看到新教案才进入正式窗口。
     * </p>
     */
    public void activateScoreWindow(String sessionId) {
        if (sessionId == null) return;
        this.activeResults.computeIfPresent(sessionId, (k, r) -> {
            if (!r.scoreWindowActivated) {
                r.scoreWindowActivated = true;
                this.scheduleScoreWindowClose(sessionId, this.scoreWindowSeconds);
                logger.info("[ADDRF] 评分窗口激活: sessionId={}, 正式窗口 {}s", sessionId, this.scoreWindowSeconds);
            }
            return r;
        });
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

    /** 从表单请求构造教案头部信息（2026-09-02 教案结构改造） */
    private static LessonHeader buildHeader(LessonPlanRequest request) {
        if (request == null) {
            return null;
        }
        return new LessonHeader(request.getTopic(), request.getStage(), request.getGrade(),
                request.getSubject(), request.getHours());
    }

    static ThreadPoolExecutor createDefaultExecutor() {
        return new ThreadPoolExecutor(2, 4, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(50), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * quick 模式独立路径（2026-08-23）：一次 LLM 调用直出完整教案，不做 Design/Development 分解、
     * 不澄清、不缓存、不 Review、不 HITL、不挂评分窗口、不回灌。
     * 契约：流式推 stage:content → 完成推 stage:format(带真实 sessionId, updated:false) → done。
     * 不推 stage:analysis/design/development/review、stage_await、analysis_clarify。
     */
    public AddrfResult executeQuick(LessonPlanRequest request, ChatModelPort chatModel, SseEmitter emitter,
                                    String sessionContext, Long userId, String sessionId) {
        AddrfResult result = new AddrfResult();
        result.lessonHeader = buildHeader(request);
        if (sessionId != null) {
            this.activeResults.put(sessionId, result);
        }
        try {
            String k12Ctx = this.loadK12Context(request);
            String initialInput = this.buildInitialInput(request, k12Ctx, sessionContext, userId);
            // 2026-08-23 quick 升级: 优先 LLM 直出 HTML(绕开 simpleMarkdown 围栏/表格漂移), 含 MD 特征时降级 MD 路径。
            String quickPrompt = this.loadPrompt("addrf_quick_html", userId);
            quickPrompt = this.appendPersonalizedContext(quickPrompt, userId, sessionId, "quick");
            String quickOut = this.callAgent(chatModel, quickPrompt, initialInput, emitter, "quick", "quick", 180, request.getSubject(), sessionId);
            // 2026-08-31 v2: 流式超时部分保留 — 剥标记置 truncated, 交付前拼黄条
            if (quickOut != null && quickOut.startsWith(PARTIAL_PREFIX)) {
                quickOut = this.stripPartialPrefix(quickOut);
                result.truncated = true;
                logger.warn("[ADDRF-QUICK] \u622a\u65ad\u4ea4\u4ed8: quickOut={} \u5b57, uid={}", quickOut.length(), userId);
            }
            if (quickOut == null || quickOut.isBlank()) {
                quickOut = "";
                logger.warn("[ADDRF-QUICK] \u5feb\u901f\u6559\u6848\u751f\u6210\u5931\u8d25: subject={}, uid={}", request.getSubject(), userId);
            }
            boolean isMarkdown = this.looksLikeMarkdown(quickOut);
            if (isMarkdown) {
                // 降级: LLM 未遵 HTML 约束(混入 ** / 行首## / 管道表格) -> 回退旧 MD 路径
                this.quickMarkdownFallbackCount.incrementAndGet();
                if (this.pipelineMetrics != null) {
                    this.pipelineMetrics.recordQuickFallback();
                }
                logger.warn("[ADDRF-QUICK] \u68c0\u6d4b\u5230 Markdown \u7279\u5f81, \u964d\u7ea7 MD \u8def\u5f84: uid={}, sid={}", userId, sessionId);
                result.development = quickOut;
                result.html = this.formatTool.format(result.lessonHeader, "", "", quickOut, "");
            } else {
                // 主路径: 直出 HTML(Jsoup 白名单消毒 -> emoji 安全化 -> 套外壳)
                result.html = this.formatTool.formatDirect(quickOut, result.lessonHeader);
            }
            if (result.truncated) {
                result.html = this.injectPartialWarning(result.html);
            }
            this.emitStageCompleteFull(emitter, "stage:format", result.html, sessionId);
            logger.info("[ADDRF-QUICK] \u5feb\u901f\u6559\u6848\u751f\u6210\u5b8c\u6210: {} \u5b57\u7b26, subject={}, uid={}, sid={}, mdFallback={}",
                    quickOut.length(), request.getSubject(), userId, sessionId, isMarkdown);
        } catch (Exception e) {
            logger.error("[ADDRF-QUICK] \u5feb\u901f\u6559\u6848\u751f\u6210\u5f02\u5e38: {}, uid={}, sid={}", e.getMessage(), userId, sessionId);
            result.html = "<p>\u751f\u6210\u5931\u8d25\uff1a" + (e.getMessage() != null ? e.getMessage() : "\u672a\u77e5\u9519\u8bef") + "</p>";
            this.emitStageCompleteFull(emitter, "stage:format", result.html, sessionId);
        }
        return result;
    }

    /** 判断 quick 输出是否混入 Markdown 特征(粗体 ** / 行首 ## 标题 / 管道符表格) -> 需要降级到 MD 路径 */
    private boolean looksLikeMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        // HTML 主路径: 输出以 < 开头的标签, 或含 <p>/<table>/<h2> 等
        boolean hasHtmlTag = text.contains("<p") || text.contains("<table") || text.contains("<h2") || text.contains("<h3");
        if (hasHtmlTag) {
            // 含 HTML 标签但明显混入 MD 痕迹(管道表格/行首#/裸 **) 也判 MD
            boolean hasMdTable = text.matches("(?s).*\\n\\s*\\|[^\\n]*\\|.*");
            boolean hasMdHeading = text.matches("(?m)^#{1,3} .+");
            boolean hasMdBold = text.matches("(?s).*\\*\\*[^*]+\\*\\*.*");
            return hasMdTable || hasMdHeading || hasMdBold;
        }
        // 无 HTML 标签(纯文本/MD) -> 判 MD
        return true;
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
                result.lessonHeader = buildHeader(request);
                // 2026-08-18: 注册进行中的 result, 供用户评分接口写入(execute 返回时移除)
                if (sessionId != null) {
                    this.activeResults.put(sessionId, result);
                }
                k12Ctx = this.loadK12Context(request);
                String initialInput = this.buildInitialInput(request, k12Ctx, sessionContext, userId);
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
                    result.analysis = this.callStageWithRevise(chatModel, analysisPrompt, clarifiedInput, emitter, "analysis", mode, copilotQueues, 60, request.getSubject(), sessionId);
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
                    result.design = this.callStageWithRevise(chatModel, designPromptFinal, result.analysis, emitter, "design", mode, copilotQueues, 60, request.getSubject(), sessionId);
                    logger.info("[ADDRF] Design \u5b8c\u6210, {} \u5b57\u7b26", (Object)(result.design != null ? result.design.length() : 0));
                }, this.executor).exceptionally(ex -> {
                    logger.error("[ADDRF] Design \u9636\u6bb5\u5185\u90e8\u5f02\u5e38\u88ab\u5f02\u6b65\u4efb\u52a1\u6355\u83b7", ex);
                    result.design = "[\u7cfb\u7edf\u63d0\u793a: Design \u9636\u6bb5\u5185\u90e8\u9519\u8bef]";
                    return null;
                });
                devFuture = CompletableFuture.runAsync(() -> {
                    result.development = this.callStageWithRevise(chatModel, devPromptFinal, result.analysis, emitter, "development", mode, copilotQueues, 180, request.getSubject(), sessionId);
                    logger.info("[ADDRF] Development \u5b8c\u6210, {} \u5b57\u7b26", (Object)(result.development != null ? result.development.length() : 0));
                }, this.executor).exceptionally(ex -> {
                    logger.error("[ADDRF] Development \u9636\u6bb5\u5185\u90e8\u5f02\u5e38\u88ab\u5f02\u6b65\u4efb\u52a1\u6355\u83b7", ex);
                    result.development = "[\u7cfb\u7edf\u63d0\u793a: Development \u9636\u6bb5\u5185\u90e8\u9519\u8bef]";
                    return null;
                });
                try {
                    designFuture.get(240L, TimeUnit.SECONDS);
                    // P1-2: copilot 终止需在 Design/Development 也生效(原仅 Analysis 检查)。
                    // 用户点在 design 阶段的 stage_await 上点"终止" -> callStageWithRevise 返回 TERMINATED,
                    // 若不处理会把字面 "_TERMINATED_" 写入 HTML; 改为取消并行 dev 并提前返回。
                    if (TERMINATED.equals(result.design)) {
                        logger.info("[ADDRF] Design \u9636\u6bb5\u7528\u6237\u7ec8\u6b62");
                        devFuture.cancel(true);
                        result.design = "[\u7528\u6237\u7ec8\uff08Design \u9636\u6bb5\u7ec8\u6b62\uff09]";
                        result.development = null;
                        return result;
                    }
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
                if (TERMINATED.equals(result.development)) {
                    logger.info("[ADDRF] Development \u9636\u6bb5\u7528\u6237\u7ec8\u6b62");
                    result.development = "[\u7528\u6237\u7ec8\uff08Development \u9636\u6bb5\u7ec8\u6b62\uff09]";
                    result.design = result.design != null
                            && result.design.startsWith(DEGRADED_PREFIX) ? "[\u7528\u6237\u7ec8]"
                            : result.design;
                    return result;
                }
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
        // 2026-08-31 v2: 流式超时部分保留(截断交付) — 剥标记置 truncated, 不触发占位覆盖, 不进 Review/自愈
        if (result.development != null && result.development.startsWith(PARTIAL_PREFIX)) {
            result.development = this.stripPartialPrefix(result.development);
            result.truncated = true;
            logger.warn("[ADDRF] Development \u622a\u65ad\u4ea4\u4ed8: development={} \u5b57, uid={}", result.development.length(), userId);
        }
        boolean bl = devDegraded = result.development != null && result.development.startsWith(DEGRADED_PREFIX);
        if (result.design != null && result.design.startsWith(DEGRADED_PREFIX)) {
            result.design = "\uff08\u6b64\u90e8\u5206\u5f85\u8865\u5145\uff09";
            devDegraded = true;
        }
        if (devDegraded && !result.truncated) {
            result.development = "\uff08\u6b64\u90e8\u5206\u5f85\u8865\u5145\uff09\n\n> **\u26a0 \u8be5\u9636\u6bb5\u751f\u6210\u8d85\u65f6\u6216\u5931\u8d25\uff0c\u4ec5\u5c55\u793a\u5df2\u751f\u6210\u7684\u5206\u6790\u4e0e\u8bbe\u8ba1\u5185\u5bb9\u3002\u53ef\u5c1d\u8bd5\u51cf\u5c11\u8bfe\u65f6\u6570\u540e\u91cd\u65b0\u751f\u6210\u3002**";
        }
        result.html = this.formatTool.format(result.lessonHeader, result.analysis, result.design, result.development, "");
        if (result.truncated) {
            result.html = this.injectPartialWarning(result.html);
        }
        this.emitStageComplete(emitter, "stage:format", result.html, sessionId);
        if (!devDegraded && !result.truncated && result.development != null && !result.development.startsWith(DEGRADED_PREFIX)) {
            String finalK12 = k12Ctx;
            String finalSubject = request.getSubject();
            CompletableFuture<Void> reviewTask = CompletableFuture.runAsync(() -> {
                logger.info("[ADDRF] Review \u540e\u53f0\u5f00\u59cb");
                this.asyncReview(result, chatModel, enhancedDevPrompt, emitter, finalK12, finalSubject, userId, sessionId, buildSectionPlanText(request));
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
                    result.html = this.formatTool.format(result.lessonHeader, result.analysis, result.design, result.development, result.review);
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
                    this.emitReviewComplete(emitter, result.review, result.score, this.resolveReviewDimensions(result), sessionId);
                }
                catch (RuntimeException e) {
                    // emitter 已关闭时 emitStageComplete 内部抛 IllegalStateException, 属预期
                    logger.trace("[ADDRF] stage:review 推送跳过 (emitter 已关闭): {}", e.getMessage());
                }
                // 2026-08-19 联调修复: 延迟关闭评分窗口 — Review 完成后保留 entry 供用户打分,
                // 2026-08-21 改造: 先启动长兜底窗口(30min 防泄漏), 前端展示教案时 activateScoreWindow 进入正式窗口
                this.scheduleScoreWindowClose(sessionId, TOTAL_WINDOW_MINUTES * 60L);
            }, this.executor);
            // 2026-08-19 修复: 按 sessionId 存 future(消除多用户并发覆盖), 完成后移除
            if (sessionId != null) {
                this.reviewFutures.put(sessionId, reviewTask);
                reviewTask.whenComplete((v, t) -> this.reviewFutures.remove(sessionId));
            }
            this.reviewFuture = reviewTask;
        } else {
            result.review = "[\u7cfb\u7edf\u63d0\u793a: \u6559\u6848\u4e3b\u4f53\u4e0d\u5b8c\u6574\uff0c\u8df3\u8fc7\u8bc4\u4f30]";
            this.emitReviewComplete(emitter, result.review, result.score, this.resolveReviewDimensions(result), sessionId);
            // 2026-08-19 联调修复: 同样延迟关闭评分窗口(与正常路径一致), 长兜底窗口
            this.scheduleScoreWindowClose(sessionId, TOTAL_WINDOW_MINUTES * 60L);
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
            String intentOutput = this.callAgent(chatModel, intentPrompt, userInput, emitter, "analysis_intent", "quick", 60, subject, sessionId);
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

    private String callStageWithRevise(ChatModelPort chatModel, String systemPrompt, String userInput, SseEmitter emitter, String stage, String mode, ConcurrentHashMap<String, BlockingQueue<String>> copilotQueues, int timeoutSec, String subject, String sessionId) {
        Object currentInput = userInput;
        for (int round = 0; round < 5; ++round) {
            Object content = this.callAgent(chatModel, systemPrompt, (String)currentInput, emitter, stage, mode, timeoutSec, subject, sessionId);
            if (content == null) {
                content = "[\u7cfb\u7edf\u63d0\u793a: " + stage + " \u9636\u6bb5\u751f\u6210\u5931\u8d25]";
            }
            // 2026-08-31 v2: 截断交付短路 — 内容不完整, revise/continue 循环无意义, 直接返回(调用方剥标记处理)
            if (content.toString().startsWith(PARTIAL_PREFIX)) {
                return (String) content;
            }
            this.emitStageComplete(emitter, "stage:" + stage, content.toString(), sessionId);
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
        return this.callAgent(chatModel, systemPrompt, userInput, emitter, stage, mode, timeoutSec, subject, sessionId);
    }

    private void asyncReview(AddrfResult result, ChatModelPort chatModel, String devPrompt, SseEmitter emitter, String k12Ctx, String subject, Long userId, String sessionId, String sectionPlan) {
        int prevScore = -1;
        for (int retryCount = 0; !(retryCount > 2 || result.truncated || result.development != null && result.development.startsWith(DEGRADED_PREFIX)); ++retryCount) {
            String reviewPrompt = this.loadPrompt("addrf_review", userId) + "\n\n\u8bfe\u7a0b\u6807\u51c6\uff1a\n" + k12Ctx;
            // 2026-08-31: Review 感知用户章节计划 — 按用户定制结构校验完整性, 而非固定默认模板
            // (消除"用户自定义结构被 Review 判低分 -> 自愈拉回默认结构"的对抗)
            if (sectionPlan != null && !sectionPlan.isBlank()) {
                reviewPrompt += "\n\n【本次交付章节计划】\n" + sectionPlan
                        + "\n（按以上计划校验章节完整性；用户未勾选/未要求的默认附加小节缺失不扣分；用户特殊要求与默认结构冲突时以用户为准。）";
            }
            // 个性化注入: 用户评价标准入 Review 评分(核心), 避免按通用标准误判个性化教案
            reviewPrompt = this.appendPersonalizedContext(reviewPrompt, userId, sessionId, "review");
            String reviewRaw = this.callAgentJson(chatModel, reviewPrompt, result.development, null, "review", "quick", 60, subject, sessionId);
            ReviewJson parsedReview = this.parseReviewJson(reviewRaw);
            if (parsedReview != null) {
                // 2026-08-21 Layer 2: 结构化 JSON 评审成功 -> 直接采用 report/score/dimensions(单一数据源, 前后端同源)
                result.review = parsedReview.report;
                result.score = parsedReview.score;
                result.reviewDimensions = parsedReview.dimensions;
            } else {
                // 降级: 原样使用(兼容旧版自由文本评审), extractScore 正则兜底
                result.review = reviewRaw != null ? reviewRaw : "[\u7cfb\u7edf\u63d0\u793a: Review \u9636\u6bb5\u751f\u6210\u5931\u8d25]";
                result.score = this.extractScore(result.review);
            }
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
            result.development = this.callAgent(chatModel, devPrompt + "\n\u4fee\u6539\u610f\u89c1\uff1a\n" + feedback, result.analysis, null, "development", "quick", 180, subject, sessionId);
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
            result.html = this.formatTool.format(result.lessonHeader, result.analysis, result.design, result.development, result.review);
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
        return this.callAgent(chatModel, systemPrompt, userInput, emitter, stage, mode, 60, subject, false, null);
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
        return this.callAgent(chatModel, systemPrompt, userInput, emitter, stage, mode, timeoutSec, null, false, null);
    }

    private String callAgent(ChatModelPort chatModel, String systemPrompt, String userInput, SseEmitter emitter, String stage, String mode, int timeoutSec, String subject, String sessionId) {
        return this.callAgent(chatModel, systemPrompt, userInput, emitter, stage, mode, timeoutSec, subject, false, sessionId);
    }

    /**
     * 2026-08-21 Layer 2: Review 阶段专用 —— 强制模型输出合法 JSON(response_format=json_object),
     * 从模型层杜绝输出格式抖动(与 Layer 1 的"单一解析源"叠加后, 解析失败率趋近于 0)。
     */
    private String callAgentJson(ChatModelPort chatModel, String systemPrompt, String userInput, SseEmitter emitter, String stage, String mode, int timeoutSec, String subject, String sessionId) {
        return this.callAgent(chatModel, systemPrompt, userInput, emitter, stage, mode, timeoutSec, subject, true, sessionId);
    }

    private ChatOptions buildChatOptions(int maxTokens, boolean jsonMode) {
        if (jsonMode) {
            return DashScopeChatOptions.builder().withMaxToken(Integer.valueOf(maxTokens))
                    .withResponseFormat(DashScopeResponseFormat.builder().type(DashScopeResponseFormat.Type.JSON_OBJECT).build())
                    .build();
        }
        return DashScopeChatOptions.builder().withMaxToken(Integer.valueOf(maxTokens)).build();
    }

    private String callAgent(ChatModelPort chatModel, String systemPrompt, String userInput, SseEmitter emitter, String stage, String mode, int timeoutSec, String subject, boolean jsonMode, String sessionId) {
        // 2026-08-31 v2 双层计时重构:
        //   L1 挂死检测 Flux.timeout(gap 45s): chunk 间隔超 45s 即判挂死(原 180s timeout 是间隔语义, 挂死拖太久)
        //   L2 总预算 Flux.take(timeoutSec): 到期优雅 onComplete, 已流出内容天然保留(非异常丢弃)
        //   两者任一触发 → 决策树(续写优先/部分保留/重调), 详见 decideTimeoutFallback
        int maxTokens = this.resolveMaxTokens(stage, subject);
        long start = System.currentTimeMillis();
        StringBuilder full = new StringBuilder();
        boolean budgetExhausted = false;
        try {
            Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userInput)), this.buildChatOptions(maxTokens, jsonMode));
            AtomicLong firstChunkAt = new AtomicLong(0L);
            AtomicBoolean budgetHit = new AtomicBoolean(false);
            Flux<ChatResponse> flux = chatModel.stream(prompt);
            flux.timeout(java.time.Duration.ofSeconds(this.gapTimeoutSeconds))
                .take(java.time.Duration.ofSeconds(timeoutSec))
                .doOnNext(response -> {
                    if (firstChunkAt.get() == 0L) {
                        firstChunkAt.set(System.currentTimeMillis());
                    }
                    String chunk;
                    if (response != null && response.getResult() != null && (chunk = response.getResult().getOutput().getText()) != null) {
                        full.append(chunk);
                        // JSON 模式: 中间块是零散 JSON 片段, 对前端无展示价值, 不推 interim
                        if (!jsonMode) {
                            this.emitInterim(emitter, sessionId, stage, chunk);
                        }
                    }
                })
                .doFinally(sig -> {
                    long elapsed = System.currentTimeMillis() - start;
                    if (firstChunkAt.get() > 0L && this.pipelineMetrics != null) {
                        this.pipelineMetrics.recordLlmTtfb(stage, firstChunkAt.get() - start);
                    }
                    if (this.pipelineMetrics != null) {
                        this.pipelineMetrics.recordLlmBudgetRatio(stage, elapsed, timeoutSec * 1000L);
                    }
                    // take 到期与自然完成的区分用耗时近似判定(-500ms 容差), 简单可靠
                    if (elapsed >= timeoutSec * 1000L - 500L) {
                        budgetHit.set(true);
                    }
                })
                .blockLast();
            budgetExhausted = budgetHit.get();
        } catch (Exception e) {
            // L1 gap 超时(TimeoutException) 或 流异常 — 已流出内容仍在 full 中, 进入决策树
            logger.warn("[ADDRF] {} 流中断: 已收 {} 字, 原因: {}", stage, full.length(), e.getMessage());
            budgetExhausted = true;
        }
        if (!budgetExhausted) {
            // 模型自然完成(预算内)
            return full.toString();
        }
        // ---- 未完整输出: 决策树(v2: 续写优先于保留) ----
        String partial = full.toString();
        TimeoutFallback fallback = this.decideTimeoutFallback(stage, jsonMode, partial.length(), this.timeoutPartialKeep);
        if (fallback == TimeoutFallback.CONTINUE) {
            // 断点续写: 流式(45s 预算), interim 继续推前端 — 无感修复优先出口
            try {
                Prompt contPrompt = this.buildContinuationPrompt(systemPrompt, userInput, partial, maxTokens, stage);
                StringBuilder cont = new StringBuilder();
                chatModel.stream(contPrompt)
                    .timeout(java.time.Duration.ofSeconds(this.continueBudgetSeconds))
                    .doOnNext(response -> {
                        String chunk;
                        if (response != null && response.getResult() != null && (chunk = response.getResult().getOutput().getText()) != null) {
                            cont.append(chunk);
                            if (!jsonMode) {
                                this.emitInterim(emitter, sessionId, stage, chunk);
                            }
                        }
                    })
                    .blockLast();
                if (cont.length() > 0) {
                    if (this.pipelineMetrics != null) {
                        this.pipelineMetrics.recordPartialContinued();
                    }
                    logger.info("[ADDRF] {} 续写补全 {} 字, 总长 {} — 无感修复", stage, cont.length(), partial.length() + cont.length());
                    return partial + cont;
                }
                logger.warn("[ADDRF] {} 续写 0 字, 视为失败", stage);
            } catch (Exception e2) {
                logger.warn("[ADDRF] {} 续写失败: {}", stage, e2.getMessage());
            }
            // 续写失败 -> 长度分流: ≥ 阈值保留交付(截断标记), 否则重调
            if (partial.length() >= this.partialKeepChars) {
                if (this.pipelineMetrics != null) {
                    this.pipelineMetrics.recordPartialKept();
                }
                logger.warn("[ADDRF] {} 保留部分输出 {} 字(截断交付)", stage, partial.length());
                return PARTIAL_PREFIX + "\n" + partial;
            }
            logger.warn("[ADDRF] {} 部分内容过短({}字), 回退 call() 重调", stage, partial.length());
        }
        // RECALL_FULL: 原有非流式 call() 从零重调(最后防线, SDK 重试内含)
        try {
            Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userInput)), this.buildChatOptions(maxTokens, jsonMode));
            ChatResponse response2 = chatModel.call(prompt);
            if (response2 != null && response2.getResult() != null) {
                AssistantMessage msg = response2.getResult().getOutput();
                return msg != null ? msg.getText() : "";
            }
        } catch (Exception e2) {
            logger.warn("[ADDRF] {} call() 重调也失败: {}", stage, e2.getMessage());
        }
        return "";
    }

    /** 超时决策(v2): 仅返回 RECALL_FULL 或 CONTINUE; KEEP_PARTIAL 由续写失败后的长度分流在流程内使用 */
    TimeoutFallback decideTimeoutFallback(String stage, boolean jsonMode, int partialLen, boolean switchOn) {
        if (!switchOn || jsonMode) {
            return TimeoutFallback.RECALL_FULL;
        }
        boolean target = "development".equals(stage) || "quick".equals(stage);
        if (!target) {
            return TimeoutFallback.RECALL_FULL;
        }
        // 空/极短内容续写无意义(等于更短预算的重做)
        if (partialLen < MIN_CONTINUE_CHARS) {
            return TimeoutFallback.RECALL_FULL;
        }
        return TimeoutFallback.CONTINUE;
    }

    enum TimeoutFallback { RECALL_FULL, CONTINUE, KEEP_PARTIAL }

    /**
     * 2026-08-31 v2: 断点续写 Prompt — 带结构锚点(尾部 100 字 + 已完成标题列表)防重复/防断裂。
     * messages: [system, user(原输入), assistant(partial), user(续写指令)]
     */
    private Prompt buildContinuationPrompt(String systemPrompt, String userInput, String partial, int maxTokens, String stage) {
        String stageName = "development".equals(stage) ? "教学过程"
                : "quick".equals(stage) ? "教案" : stage;
        String tail = partial.length() > 100 ? partial.substring(partial.length() - 100) : partial;
        String headings = extractHeadings(partial);
        String instruction = "这是 K12 教案的\"" + stageName + "\"部分，因超时中断。已输出内容的结尾是：\n"
                + "「" + tail + "」\n"
                + (headings.isEmpty() ? "" : "已完成的章节标题：" + headings + "\n")
                + "请从上句之后自然衔接，继续完成剩余环节（如：巩固练习/课堂总结/课后作业/板书设计等），"
                + "不要重复任何已输出内容，保持原有的格式与编号风格。";
        return new Prompt(
                List.of(new SystemMessage(systemPrompt),
                        new UserMessage(userInput),
                        new AssistantMessage(partial),
                        new UserMessage(instruction)),
                this.buildChatOptions(maxTokens, false));
    }

    /** 剥离 PARTIAL_PREFIX 标记, 返回纯内容(供上层置 truncated 后使用) */
    private String stripPartialPrefix(String s) {
        String content = s.substring(PARTIAL_PREFIX.length());
        return content.startsWith("\n") ? content.substring(1) : content.stripLeading();
    }

    /**
     * 2026-08-31 v2: 超时部分保留的黄色非阻断提示条(后端拼壳, 前端零改动)。
     * 刻意不复用 .hitl-warning(那会触发前端"保留/放弃"强制确认, 违反 UX 约束);
     * 可关闭交互留二期(需前端改动)。
     */
    private String injectPartialWarning(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        String div = "<div class=\"partial-warning\" style=\"border:2px solid #e6a700;padding:12px;margin:12px 0;background:#fffbf0;border-radius:8px;color:#8a6100;font-size:14px;\">"
                + "本教案可能因超时未完整生成，已为你保留当前内容；如需完整版本，可点击重新生成。</div>";
        int idx = html.indexOf("<body>");
        if (idx >= 0) {
            return html.substring(0, idx + 6) + "\n" + div + html.substring(idx + 6);
        }
        return div + html;
    }

    /** 提取 partial 中的 Markdown 标题(^#{1,6})作为续写进度锚点, 最多 20 个 */
    static String extractHeadings(String partial) {
        if (partial == null || partial.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^#{1,6}.*$").matcher(partial);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (m.find() && count < 20) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(m.group().trim());
            count++;
        }
        return sb.toString();
    }

    // 2026-08-19: 节流缓冲 - 按 stage 累积, 达 50 字或 100ms 才推送(改善前端观感, 减少 SSE 消息数)
    // 2026-08-23 P0-3: key 追加 sessionId 隔离(原按 stage 共享, 多用户并行进同阶段 chunk 交叉混写)
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> interimBuffers = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> interimLastFlush = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int INTERIM_FLUSH_CHARS = 50;
    // 2026-08-19 修复: 100ms 太短(LLM 吐字间隔本身就 >100ms), 时间条件每次触发导致节流失效
    // 改 500ms 兜底: 50 字为主触发, 时间只防残留卡住(慢速吐字时也能流畅输出)
    private static final long INTERIM_FLUSH_MS = 500L;

    private String interimKey(String sessionId, String stage) {
        return (sessionId == null ? "" : sessionId) + ":" + stage;
    }

    private void emitInterim(SseEmitter emitter, String sessionId, String stage, String chunk) {
        if (emitter == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        String key = interimKey(sessionId, stage);
        // 节流累积
        StringBuilder buf = this.interimBuffers.computeIfAbsent(key, k -> new StringBuilder());
        boolean flush;
        synchronized (buf) {
            buf.append(chunk);
            long now = System.currentTimeMillis();
            // 2026-08-19 修复: computeIfAbsent 而非 getOrDefault(0) - 首次调用记录时间但不触发时间条件
            // (旧实现 now-0 远超 500ms, 第一个 chunk 就立即 flush, 破坏节流)
            long last = this.interimLastFlush.computeIfAbsent(key, k -> now);
            flush = buf.length() >= INTERIM_FLUSH_CHARS || (now - last) >= INTERIM_FLUSH_MS;
        }
        if (flush) {
            flushInterim(emitter, sessionId, stage);
        }
    }

    /** 推送缓冲内容并重置 */
    private void flushInterim(SseEmitter emitter, String sessionId, String stage) {
        String key = interimKey(sessionId, stage);
        StringBuilder buf = this.interimBuffers.get(key);
        if (buf == null) return;
        String batch;
        synchronized (buf) {
            if (buf.length() == 0) return;
            batch = buf.toString();
            buf.setLength(0);
            this.interimLastFlush.put(key, System.currentTimeMillis());
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
            this.flushInterim(emitter, sessionId, flushStage);
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

    /**
     * 2026-08-23 quick 独立: 推送完整内容的 stage 完成事件(不截断到 500)。
     * 与 {@link #emitStageComplete} 的唯一区别是 content 不截断 ——
     * 用于 quick 模式的首个 stage:format(前端以它为最终完整教案, 无 updated 二次推送覆盖)。
     */
    private void emitStageCompleteFull(SseEmitter emitter, String eventType, String content, String sessionId) {
        if (emitter == null) {
            return;
        }
        try {
            String flushStage = eventType.replace("stage:", "");
            this.flushInterim(emitter, sessionId, flushStage);
        } catch (Exception ignored) {
        }
        try {
            String stage = eventType.replace("stage:", "");
            String safe = content != null ? content : "";
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
            logger.trace("[ADDRF] 阶段完成(全文)推送失败 (emitter 可能已关闭): {}", e.getMessage());
        }
    }

    /**
     * 2026-08-21: stage:review 事件一并向前端下发后端解析结果 {score, dimensions},
     * 前端直接消费该单一数据源, 不再自行 re-parse review 文本(消除 P4 契约漂移)。
     * 新增字段向后兼容(旧前端忽略未知字段)。
     */
    private void emitReviewComplete(SseEmitter emitter, String review, int score, Map<String, Integer> dimensions, String sessionId) {
        if (emitter == null) {
            return;
        }
        try {
            this.flushInterim(emitter, sessionId, "review");
        } catch (Exception ignored) {
        }
        try {
            String safe = review != null ? review.substring(0, Math.min(review.length(), 500)) : "";
            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("type", "stage:review");
            data.put("content", safe);
            data.put("stage", "review");
            data.put("score", score);
            data.put("dimensions", dimensions != null ? dimensions : new LinkedHashMap<String, Integer>());
            emitter.send(SseEmitter.event().name("message").data(data));
        } catch (IOException | IllegalStateException e) {
            logger.trace("[ADDRF] stage:review 推送失败 (emitter 可能已关闭): {}", e.getMessage());
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
            String action = (String)queue.poll(this.awaitTimeoutSeconds, TimeUnit.SECONDS);
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
        catch (IllegalStateException e) {
            // 2026-08-19 联调: emitter 已关闭(SSE 连接断开)时 send 抛 IllegalStateException,
            // 属预期降级场景, 返回 null 表示放弃本次 copilot 等待, 不应让阶段异常
            logger.trace("[ADDRF] stage_await 推送跳过 (emitter 已关闭): {}", e.getMessage());
            return null;
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

    private String buildInitialInput(LessonPlanRequest req, String k12Ctx, String sessionContext, Long userId) {
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
        // 2026-08-31: 上传参考资料检索注入(仅用户确实上传了文件时触发; 失败/无结果静默跳过)
        String uploadedCtx = this.retrieveUploadedContext(req, userId);
        if (!uploadedCtx.isEmpty()) {
            sb.append(uploadedCtx).append("\n\n");
        }
        if (!(analysisExtra = this.subjectFormatLoader.getAnalysisExtra(req.getSubject())).isEmpty()) {
            sb.append(analysisExtra).append("\n");
        }
        // 2026-09-02 教案结构改造: 交付章节要求(固定骨架 + 用户勾选模块), 注入所有阶段输入
        return appendSectionPlan(sb.toString(), req);
    }

    /**
     * 2026-09-02 教案结构改造 v1: 追加"教案交付章节要求"段落。
     * 所有阶段(analysis/design/development/quick)共享该输入 —— LLM 据此输出教师视角章节,
     * 与 FormatTool 的交付章节过滤层(DELIVERABLE_KEYWORDS)共同保证交付结构。
     * 未勾选模块由 prompt 约束不输出; format 层白名单兜底防 LLM 乱出中间产物。
     */
    private static String appendSectionPlan(String baseInput, LessonPlanRequest req) {
        if (baseInput == null) {
            return "";
        }
        if (req == null) {
            return baseInput;
        }
        return baseInput + "\n\n" + buildSectionPlanText(req);
    }

    /**
     * 2026-08-31: 生成「教案交付章节要求」计划文本(生成与 Review 共用的单一数据源)。
     * Review 阶段按此计划校验章节完整性, 而非固定默认模板 — 避免"用户自定义结构被 Review 拉回"的对抗。
     */
    static String buildSectionPlanText(LessonPlanRequest req) {
        java.util.List<String> chosen = req == null ? java.util.List.of() : req.resolveOptionalSections();
        StringBuilder sb = new StringBuilder();
        sb.append("教案交付章节要求（必须严格遵守）：\n");
        sb.append("- 固定骨架（必须包含）：教学目标、教学重难点、教学过程；多课时时教学过程需按「第 X 课时」分节。\n");
        if (!chosen.isEmpty()) {
            sb.append("- 用户勾选的附加模块（必须输出对应章节）：").append(String.join("、", chosen)).append("。\n");
        }
        sb.append("- 未列入以上清单的模块不得输出独立章节（除非用户特殊要求明确指定）。");
        return sb.toString();
    }

    /**
     * 2026-08-31: 检索用户上传参考资料的摘要, 供生成 prompt 注入。
     * 仅当用户确实上传了文件时触发; 检索失败/无结果静默返回空串, 不阻塞生成。
     * 包级可见以便单测。
     */
    String retrieveUploadedContext(LessonPlanRequest request, Long userId) {
        if (this.vectorSearchService == null || request.getUploadedFileNames() == null
                || request.getUploadedFileNames().isEmpty()) {
            return "";
        }
        try {
            String query = (request.getGoals() == null ? "" : request.getGoals()) + " " + request.getSubject();
            List<com.gagneflow.service.vector.VectorSearchService.SearchResult> hits =
                    this.vectorSearchService.searchUploadedDocs(query, userId, UPLOADED_DOCS_TOP_K);
            if (hits == null || hits.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("\u3010\u53c2\u8003\u8d44\u6599\uff08\u6765\u81ea\u7528\u6237\u4e0a\u4f20\u6587\u6863\u7684\u68c0\u7d22\u63d0\u70bc\uff09\u3011\n");
            int used = 0;
            for (com.gagneflow.service.vector.VectorSearchService.SearchResult hit : hits) {
                if (hit == null || hit.getContent() == null || hit.getContent().isBlank()) {
                    continue;
                }
                String c = hit.getContent();
                if (c.length() > UPLOADED_DOC_SNIPPET_CHARS) {
                    c = c.substring(0, UPLOADED_DOC_SNIPPET_CHARS) + "...";
                }
                sb.append("- ").append(c).append("\n");
                if (++used >= UPLOADED_DOCS_TOP_K) {
                    break;
                }
            }
            return used == 0 ? "" : sb.toString();
        } catch (Exception e) {
            logger.warn("[ADDRF] 上传资料检索失败, 跳过注入: {}", e.getMessage());
            return "";
        }
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
        // 2026-08-21 修复(P4 契约漂移): 优先从末尾 SCORE_JSON 锚点按结构化解析 score,
        // 避免正文里 stray 的 "score: N" 被误匹配; 解析失败再回退下方 4 种正则写法
        String scoreBlock = extractScoreJsonBlock(review);
        if (scoreBlock != null) {
            Matcher sbm = Pattern.compile("\"score\"\\s*[:：]\\s*(\\d+)").matcher(scoreBlock);
            if (sbm.find()) {
                return clampScore(Integer.parseInt(sbm.group(1)));
            }
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
     * 2026-08-21 修复(P4 契约漂移): 提取末尾 SCORE_JSON 锚点的 JSON 对象字符串(单行/多行均可)。
     * 该锚点由 agent-config/prompts/v1/addrf/addrf_review.md 约定为输出末尾固定格式,
     * 作为前后端唯一可信评分源; 前端改为直接消费后端下发的 {score,dimensions}, 不再自行 re-parse。
     */
    private static final Pattern SCORE_JSON_BLOCK = Pattern.compile("SCORE_JSON\\s*:\\s*(\\{[\\s\\S]*\\})\\s*$", Pattern.DOTALL);
    private String extractScoreJsonBlock(String review) {
        if (review == null) {
            return null;
        }
        Matcher m = SCORE_JSON_BLOCK.matcher(review.trim());
        return m.find() ? m.group(1) : null;
    }

    /**
     * 2026-08-21: 从 review 解析 5 维度分(clarity/accuracy/strategy/alignment/format, 各 0-20)。
     * 优先 SCORE_JSON.dimensions; 缺失时回退中文维度表 "目标清晰度: 15/20"(与前端 lenient 对齐)。
     * 供 stage:review 事件一并下发, 使前端不再自行 re-parse raw review(消除 P4 漂移)。
     */
    private static final String[] DIM_KEYS_ARR = {"clarity", "accuracy", "strategy", "alignment", "format"};
    private static final String[][] ZH_DIM_LABELS = {
        {"目标清晰度", "clarity"}, {"内容准确性", "accuracy"}, {"策略合理性", "strategy"},
        {"课标对齐度", "alignment"}, {"格式规范度", "format"}
    };
    public Map<String, Integer> extractDimensions(String review) {
        Map<String, Integer> dims = new LinkedHashMap<>();
        String block = extractScoreJsonBlock(review);
        if (block != null) {
            for (String key : DIM_KEYS_ARR) {
                Matcher dm = Pattern.compile("\"" + key + "\"\\s*[:：]\\s*(\\d+)").matcher(block);
                if (dm.find()) {
                    dims.put(key, clampDim(Integer.parseInt(dm.group(1))));
                }
            }
        }
        if (dims.size() < 5 && review != null) {
            for (String[] zh : ZH_DIM_LABELS) {
                if (!dims.containsKey(zh[1])) {
                    Matcher zm = Pattern.compile(zh[0] + "\\s*[:：]?\\s*(\\d+)\\s*/\\s*20").matcher(review);
                    if (zm.find()) {
                        dims.put(zh[1], clampDim(Integer.parseInt(zm.group(1))));
                    }
                }
            }
        }
        return dims;
    }

    private static int clampDim(int d) {
        return Math.max(0, Math.min(20, d));
    }

    /**
     * 2026-08-21 Layer 2: Review 结构化 JSON 解析结果(单一数据源)。
     * report 为人类可读的评审报告(Markdown), score/dimensions 为结构化评分, 前后端同源。
     */
    static final class ReviewJson {
        String report;
        int score;
        Map<String, Integer> dimensions;
    }

    private static final ObjectMapper REVIEW_JSON_MAPPER = new ObjectMapper();

    /**
     * 2026-08-21 Layer 2: 解析 Review 的结构化 JSON 输出。
     * 模型被 response_format=json_object 强制输出 {"report","score","dimensions"},
     * 此处做最终权威解析; 解析失败返回 null, 由调用方降级到正则路径。
     */
    ReviewJson parseReviewJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode root = REVIEW_JSON_MAPPER.readTree(raw);
            if (root == null || !root.isObject() || !root.hasNonNull("report") || !root.hasNonNull("score")) {
                return null;
            }
            ReviewJson rj = new ReviewJson();
            rj.report = root.get("report").asText();
            rj.score = clampScore(root.get("score").asInt());
            Map<String, Integer> dims = new LinkedHashMap<>();
            JsonNode dimsNode = root.get("dimensions");
            if (dimsNode != null && dimsNode.isObject()) {
                for (String key : DIM_KEYS_ARR) {
                    JsonNode v = dimsNode.get(key);
                    if (v != null && v.isNumber()) {
                        dims.put(key, clampDim(v.asInt()));
                    }
                }
            }
            rj.dimensions = dims;
            return rj;
        } catch (Exception e) {
            logger.trace("[ADDRF] review JSON 解析失败, 降级正则解析: {}", e.getMessage());
            return null;
        }
    }

    /** Layer 1+2: stage:review 下发维度分时, 优先用结构化解析值, 否则回退文本解析 */
    private Map<String, Integer> resolveReviewDimensions(AddrfResult r) {
        if (r != null && r.reviewDimensions != null) {
            return r.reviewDimensions;
        }
        return this.extractDimensions(r != null ? r.review : null);
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
     *     - 评分过低(score<70, 与自愈/回灌阈值对齐; 2026-08-23 从 <60 上调, 消除灰色带)
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
        // blocked: Review 评分 < 70(与自愈停止/回灌门槛对齐, 消除 60-69 分「被交付、不审核、不入库」灰色带)
        if (result.score < 70 && result.score > 0) {
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
            logger.info("[ADDRF-HITL] blocked: \u68c0\u6d4b\u5230\u5371\u9669\u5173\u952e\u8bcd(\u5b89\u5168\u7c7b)");
            result.needsHumanReview = true;
            result.needsSafetyReview = true;
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
        // 质量类 or 安全类阻断任一为真则不回灌; 用户高分仅可覆盖"质量类", 安全类(危险词)永远不可覆盖
        // 2026-08-31: truncated(超时部分保留)永不回灌 — 不完整内容不入库, 与用户评分高低无关
        if (r.scheduleBackfill && !r.needsHumanReview && !r.needsSafetyReview && !r.truncated) {
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

    @PreDestroy
    public void shutdownWindowScheduler() {
        ScheduledExecutorService scheduler = this.windowScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public static class AddrfResult {
        // volatile: 多线程可见性保证（Review异步线程写入，主线程读取）
        public volatile String analysis;
        public volatile String design;
        public volatile String development;
        public volatile String review;
        public volatile String html;
        public volatile int score;
        // 2026-08-21 Layer 2: Review 结构化 JSON 解析出的 5 维分(clarity/accuracy/strategy/alignment/format, 0-20)
        public volatile Map<String, Integer> reviewDimensions = null;
        // volatile: HITL标志位（asyncReview线程设置，LessonController主线程读取）
        public volatile boolean needsHumanReview = false;
        // 2026-08-21: 安全类阻断(危险关键词等合规), 与"质量类"阻断分离 ——
        // 用户显式高分保留(>=4星)可覆盖"质量类"否决(让个人库可反哺),
        // 但"安全类"否决(危险关键词)不可被覆盖(合规红线)。
        public volatile boolean needsSafetyReview = false;
        // 阶段C: 非阻断质量提示(如超长警告), 供前端/日志展示, 不触发人工审核
        public volatile String qualityNote = null;
        // 2026-08-21: 评分窗口是否已激活(前端展示教案后置位, 幂等保证只启动一次正式窗口)
        public volatile boolean scoreWindowActivated = false;
        // 2026-08-18: 用户评分(1-5星, 0=未评分)。用户评分线程写入, asyncReview线程读取
        public volatile int userScore = 0;
        // 阶段C: 回灌延后到评分窗口关闭时定案(用户低分否决能正确阻断入库)。主线程置位并暂存参数。
        public volatile boolean scheduleBackfill = false;
        // 2026-08-31: 流式超时后保留的部分交付(可能不完整) — 黄条提示/跳过Review/不回灌
        public volatile boolean truncated = false;
        // 2026-09-02 教案结构改造: 教学基本信息(课题/学段/年级/学科/课时), format 渲染头部
        public volatile LessonHeader lessonHeader = null;
        public volatile Long backfillUid = null;
        public volatile String backfillSubject = null;

        public Map<String, String> getStageOutputs() {
            return Map.of("analysis", this.analysis != null ? this.analysis : "", "design", this.design != null ? this.design : "", "development", this.development != null ? this.development : "", "review", this.review != null ? this.review : "");
        }
    }
}
