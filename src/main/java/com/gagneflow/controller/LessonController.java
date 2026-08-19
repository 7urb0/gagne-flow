package com.gagneflow.controller;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.gagneflow.agent.tool.LessonPlanTools;
import com.gagneflow.config.security.CurrentUser;
import com.gagneflow.constant.UserConstants;
import com.gagneflow.dto.LessonPlanRequest;
import com.gagneflow.dto.SseMessage;
import com.gagneflow.entity.SessionMessage;
import com.gagneflow.service.chat.ChatService;
import com.gagneflow.service.chat.ChatSession;
import com.gagneflow.service.chat.ChatSessionService;
import com.gagneflow.service.document.SubjectFormatLoader;
import com.gagneflow.service.lesson.AddrfPipeline;
import com.gagneflow.service.lesson.DashScopeChatModelPort;
import com.gagneflow.service.lesson.FormatTool;
import com.gagneflow.service.memory.ConversationMemoryManager;
import com.gagneflow.service.memory.TokenCounter;
import com.gagneflow.service.metrics.PipelineMetrics;
import com.gagneflow.service.pdf.PdfGenerator;
import com.gagneflow.service.vector.VectorIndexService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 教案生成控制器 — 从 ChatController 拆分
 * 职责：ADDRF 教案生成流水线、PDF 导出、Copilot 人机协同交互
 */
@RestController
@RequestMapping("/api")
public class LessonController {
    private static final Logger logger = LoggerFactory.getLogger(LessonController.class);

    @Autowired
    private FormatTool formatTool;
    @Autowired
    private AddrfPipeline addrfPipeline;
    @Autowired
    private ChatService chatService;
    @Autowired
    private LessonPlanTools lessonPlanTools;
    @Autowired
    private ChatSessionService chatSessionService;
    @Autowired
    private ConversationMemoryManager memoryManager;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private PdfGenerator pdfGenerator;
    @Autowired(required = false)
    private TokenCounter tokenCounter;
    @Autowired(required = false)
    private SubjectFormatLoader subjectFormatLoader;
    @Autowired(required = false)
    private PipelineMetrics pipelineMetrics;
    @Autowired
    private VectorIndexService vectorIndexService;
    @Autowired
    private ThreadPoolExecutor executor;
    @Autowired
    private DashScopeApi dashScopeApi;

    private final ConcurrentHashMap<Long, Boolean> lessonLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BlockingQueue<String>> copilotQueues = new ConcurrentHashMap<>();
    // P1修复: SSE 完成状态保护，防止重复 complete
    private final AtomicBoolean emitterCompleted = new AtomicBoolean(false);

    private Long resolveUserId(Long userId, String sessionId) {
        return UserConstants.resolveUserId(userId, sessionId);
    }

    @GetMapping(value = {"/lesson_plan/placeholder/{subject}"})
    public ResponseEntity<Map<String, String>> getSubjectPlaceholder(@PathVariable String subject) {
        String placeholder;
        if (this.subjectFormatLoader != null && this.subjectFormatLoader.isLoaded()
                && (placeholder = this.subjectFormatLoader.getPlaceholder(subject)) != null
                && !placeholder.isEmpty()) {
            return ResponseEntity.ok(Map.of("placeholder", placeholder));
        }
        return ResponseEntity.ok(Map.of("placeholder", "请输入教学目标..."));
    }

    @PostMapping(value = {"/lesson_plan"}, produces = {"text/event-stream;charset=UTF-8"})
    public SseEmitter lessonPlanV2(@Valid @RequestBody LessonPlanRequest req, @CurrentUser Long userId) {
        SseEmitter emitter = new SseEmitter(Long.valueOf(600000L));
        Long uid = userId != null ? userId : UserConstants.DEFAULT_USER_ID;
        String sid = "lesson_" + System.currentTimeMillis();
        String lockKey = "gagneflow:lock:lesson:" + uid;
        boolean jvmLocked = (this.lessonLocks.putIfAbsent(uid, Boolean.TRUE) == null);
        boolean redisLocked = false;
        if (jvmLocked) {
            redisLocked = tryRedisLock(lockKey);
            if (!redisLocked) {
                logger.info("Redis lock failed, using JVM lock only. uid={}", uid);
            }
        }
        if (!jvmLocked) {
            this.sendAndComplete(emitter, SseMessage.error("已有教案生成任务在进行中，请等待完成后再试"));
            return emitter;
        }
        boolean finalRedisLocked = redisLocked;
        ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
            } catch (IOException e) {
                heartbeatExecutor.shutdownNow();
            } catch (Exception e) {
                heartbeatExecutor.shutdownNow();
            }
        }, 30L, 30L, TimeUnit.SECONDS);
        this.executor.execute(() -> {
            try {
                this.executeAddrfPipeline(req, emitter, uid, sid, lockKey, finalRedisLocked);
            } finally {
                heartbeatExecutor.shutdownNow();
            }
        });
        return emitter;
    }

    private boolean tryRedisLock(String lockKey) {
        try {
            // H-10修复: 增加 TTL 适配 ADDRF 完整流程（异步 Review 240s×2 + 生成时间）
            // 2026-08-19: 600s -> 900s(最坏 Design240+Dev240+Review240=720s, 留余量防锁提前过期)
            return Boolean.TRUE.equals(
                    this.stringRedisTemplate.opsForValue()
                            .setIfAbsent(lockKey, "1", Duration.ofSeconds(900L)));
        } catch (Exception e) {
            logger.warn("Redis 不可用，降级为 JVM 锁: {}", e.getMessage());
            return false;
        }
    }

    private void executeAddrfPipeline(LessonPlanRequest req, SseEmitter emitter,
                                       Long uid, String sid, String lockKey, boolean redisLocked) {
        try {
            DashScopeChatModel model = DashScopeChatModel.builder().dashScopeApi(this.dashScopeApi)
                    .defaultOptions(DashScopeChatOptions.builder()
                            .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                            .withTemperature(Double.valueOf(0.3))
                            .withMaxToken(Integer.valueOf(8000))
                            .withTopP(Double.valueOf(0.9)).build()).build();
            String sessionCtx = this.buildSessionContextForAddrf(uid, sid, req);
            // 2026-08-18: 表单个性化偏好写入 LTM(USER_EXPLICIT, 进全局集合), 下次生成自动复用
            this.storeFormPreferencesToLtm(req, uid, sid);
            AddrfPipeline.AddrfResult result = this.addrfPipeline.execute(
                    req, new DashScopeChatModelPort(model), emitter, req.getMode(), this.copilotQueues, sessionCtx, uid, sid);
            String html = result.html != null ? result.html : "<p>生成失败</p>";
            // 等待 Review 后台线程完成，拿到真实评分与 HITL 标志（score 由 asyncReview 异步写入）
            this.addrfPipeline.awaitReview(result, 240, sid);
            // HITL: 教案质量人工审核（必须在 awaitReview 之后判断，否则 score/needsHumanReview 尚未就绪）
            if (result.needsHumanReview) {
                String hitlMsg = "\u26a0 \u7cfb\u7edf\u68c0\u6d4b\u5230\u8be5\u6559\u6848\u53ef\u80fd\u5b58\u5728\u8d28\u91cf\u95ee\u9898\uff08\u8bc4\u5206: "
                    + result.score + "\uff09\uff0c\u5efa\u8bae\u4eba\u5de5\u590d\u6838\u540e\u4f7f\u7528\u3002";
                logger.warn("[ADDRF-HITL] 教案需人工审核: uid={}, subject={}, score={}",
                    uid, req.getSubject(), result.score);
                // 将提醒注入 HTML
                html = "<div class=\"hitl-warning\" style=\"border:2px solid #ff4444;padding:12px;margin:12px 0;background:#fff5f5;border-radius:8px;\">"
                    + hitlMsg + "</div>" + html;
                // 记录 HITL 指标
                if (this.pipelineMetrics != null) {
                    this.pipelineMetrics.recordHitlTrigger(req.getSubject(), uid);
                }
            } else {
                // 非 HITL: 教案质量达标，回灌到知识库供后续检索（此时 score 为 Review 真实评分）
                try {
                    this.vectorIndexService.indexLessonPlan(html, uid, req.getSubject(), result.score);
                } catch (Exception e) {
                    logger.warn("[ADDRF] 教案回灌失败（不影响主流程）: {}", e.getMessage());
                }
            }
            String summary = this.extractLessonSummary(result);
            this.chatSessionService.addMessage(uid, sid,
                    req.getStage() + " " + req.getGrade() + "年级 " + req.getSubject(), summary);
            try {
                this.chatSessionService.registerSession(uid, sid,
                        req.getStage() + " " + req.getGrade() + "年级 " + req.getSubject() + " 教案");
                this.chatSessionService.saveMessage(uid, sid, "user",
                        req.getStage() + " " + req.getGrade() + "年级 " + req.getSubject());
                this.chatSessionService.saveMessage(uid, sid, "assistant", html);
                // 教案长驻存档(Redis 7天): 供对话 agent 通过 getLatestLessonPlan 检索最近教案
                this.lessonPlanTools.archiveLatestLessonPlan(uid, sid, html);
            } catch (Exception mysqlEx) {
                logger.error("MySQL 教案持久化失败 (Redis 已写入): {}", mysqlEx.getMessage());
            }
            this.tryTriggerSummary(uid, sid);
            this.sendAndComplete(emitter, SseMessage.done());
        } catch (Exception e) {
            logger.error("ADDRF lesson plan failed: {}", e.getMessage(), e);
            this.sendAndComplete(emitter, SseMessage.error(
                    e.getMessage() != null ? e.getMessage() : "lesson plan generation failed"));
        } finally {
            this.lessonLocks.remove(uid);
            if (redisLocked) {
                try {
                    this.stringRedisTemplate.delete(lockKey);
                } catch (Exception ex) {
                    logger.warn("Failed to release Redis lock: {}", ex.getMessage());
                }
            }
        }
    }

    private String extractLessonSummary(AddrfPipeline.AddrfResult result) {
        StringBuilder sb = new StringBuilder("[教案] ");
        if (result.analysis != null) {
            String a = result.analysis.length() > 200 ? result.analysis.substring(0, 200) : result.analysis;
            sb.append(a.replace("\n", " "));
        }
        if (result.development != null) {
            sb.append(" | ");
            String d = result.development.length() > 100 ? result.development.substring(0, 100) : result.development;
            sb.append(d.replace("\n", " "));
        }
        return sb.toString();
    }

    private String buildSessionContextForAddrf(Long userId, String currentSessionId,
                                               com.gagneflow.dto.LessonPlanRequest req) {
        StringBuilder ctx = new StringBuilder();
        List<Map<String, Object>> sessions = this.chatSessionService.getUserSessions(userId);
        if (sessions != null) {
            for (Map<String, Object> s : sessions) {
                ChatSession cs;
                String sid = (String) s.get("sessionId");
                if (sid == null || sid.equals(currentSessionId)
                        || (cs = this.chatSessionService.getRaw(userId, sid)) == null
                        || cs.getSummary() == null || cs.getSummary().isEmpty()) continue;
                ctx.append("对话摘要（来自最近会话 ")
                        .append(sid, 0, Math.min(sid.length(), 8))
                        .append("...): ").append(cs.getSummary()).append("\n");
                break;
            }
        }
        String ltm = this.memoryManager.getLongTermContext(userId, currentSessionId,
                "教学需求 学生情况 教学目标 教学偏好 学科 年级", 5);
        if (ltm != null && !ltm.isEmpty()) {
            ctx.append(ltm);
        }
        // 2026-08-18: 表单个性化字段注入(学情/重难点/风格/作业/特殊要求), 全可选
        appendFormContext(ctx, req);
        return ctx.toString();
    }

    /** 将教案表单的个性化字段拼入 sessionContext + 存 LTM(USER_EXPLICIT) */
    private void appendFormContext(StringBuilder ctx, com.gagneflow.dto.LessonPlanRequest req) {
        if (req == null) return;
        StringBuilder formCtx = new StringBuilder();
        appendIfPresent(formCtx, "学情分析", req.getStudentProfile());
        appendIfPresent(formCtx, "教学重难点", req.getKeyPoints());
        appendIfPresent(formCtx, "教学风格偏好", req.getStylePreference());
        appendIfPresent(formCtx, "作业/评价要求", req.getAssignmentRequirement());
        appendIfPresent(formCtx, "特殊要求", req.getSpecialRequirements());
        if (formCtx.length() > 0) {
            ctx.append("\n[用户本次填写要求]\n").append(formCtx);
        }
    }

    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        sb.append("- ").append(label).append(": ").append(value.trim()).append("\n");
    }

    /** 表单个性化字段写入 LTM: 学情/风格/作业要求属稳定画像, 存 USER_EXPLICIT 进全局 */
    private void storeFormPreferencesToLtm(com.gagneflow.dto.LessonPlanRequest req, Long uid, String sid) {
        if (req == null) return;
        try {
            this.memoryManager.storeUserPreference(uid, sid, "学生情况", req.getStudentProfile());
            this.memoryManager.storeUserPreference(uid, sid, "教学偏好", req.getStylePreference());
            this.memoryManager.storeUserPreference(uid, sid, "约束限制", req.getAssignmentRequirement());
            this.memoryManager.storeUserPreference(uid, sid, "教学需求", req.getSpecialRequirements());
        } catch (Exception e) {
            logger.warn("[LTM] 表单偏好写入失败(不影响主流程): {}", e.getMessage());
        }
    }

    @GetMapping(value = {"/lesson_plan/pdf/{sessionId}"})
    public ResponseEntity<?> downloadLessonPlanPdf(
            @PathVariable String sessionId, @CurrentUser Long userId) {
        Long uid = resolveUserId(userId, sessionId);
        List<SessionMessage> msgs = this.chatSessionService.getSessionMessages(uid, sessionId);
        String html = msgs.stream()
                .filter(m -> "assistant".equals(m.getRole())
                        && m.getContent() != null
                        && m.getContent().startsWith("<!DOCTYPE html>"))
                .map(SessionMessage::getContent)
                .reduce((first, second) -> second).orElse(null);
        if (html == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] pdf = this.pdfGenerator.htmlToPdf(html);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("Content-Disposition",
                            "attachment; filename=\"lesson_plan_" + sessionId + ".pdf\"")
                    .header("Content-Length", String.valueOf(pdf.length))
                    .body(pdf);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "PDF 生成失败: " + e.getMessage()));
        }
    }

    @PostMapping(value = {"/lesson_plan/action"})
    public ResponseEntity<?> lessonPlanAction(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String action = body.get("action");
        if (token == null || action == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "token and action required"));
        }
        BlockingQueue<String> queue = this.copilotQueues.get(token);
        if (queue == null) {
            return ResponseEntity.status(404).body(Map.of("error", "stage token not found or expired"));
        }
        if ("revise".equals(action)) {
            String instruction = body.getOrDefault("instruction", "");
            queue.offer("revise:" + instruction);
        } else {
            queue.offer("continue");
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * 2026-08-18: 用户评分(1-5星)。Format 完成后推送评分 UI, 用户在 Review 完成前打分。
     * 用户低分(1-2星)一票否决 -> 无论 LLM 评分, 标记人工审核。
     */
    @PostMapping(value = {"/lesson_plan/score"})
    public ResponseEntity<?> lessonPlanScore(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String scoreStr = body.get("score");
        if (sessionId == null || scoreStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId and score required"));
        }
        int userScore;
        try {
            userScore = Integer.parseInt(scoreStr.trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "score must be integer 1-5"));
        }
        if (userScore < 1 || userScore > 5) {
            return ResponseEntity.badRequest().body(Map.of("error", "score must be 1-5"));
        }
        AddrfPipeline.AddrfResult result = this.addrfPipeline.getActiveResult(sessionId);
        if (result == null) {
            return ResponseEntity.status(404).body(Map.of("error", "active lesson plan not found or review already finished"));
        }
        result.userScore = userScore;
        String feedback = body.getOrDefault("feedback", "");
        logger.info("[ADDRF] 用户评分: sessionId={}, score={}, feedback={}",
                sessionId, userScore, feedback.isEmpty() ? "(无)" : feedback);
        return ResponseEntity.ok(Map.of("ok", true, "userScore", userScore));
    }

    /**
     * 2026-08-19: 用户提交意图澄清回答(token + answer)。
     * 后端在 Analysis 前置澄清的限时等待窗口内消费该回答, 合并进分析输入。
     */
    @PostMapping(value = {"/lesson_plan/clarify"})
    public ResponseEntity<?> lessonPlanClarify(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String answer = body.get("answer");
        if (token == null || answer == null || answer.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token and answer required"));
        }
        BlockingQueue<String> queue = this.copilotQueues.get(token);
        if (queue == null) {
            return ResponseEntity.status(404).body(Map.of("error", "clarify token not found or expired"));
        }
        queue.offer(answer.trim());
        logger.info("[ADDRF] 收到澄清回答: token={}, len={}", token, answer.trim().length());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * P1修复: 摘要替换一致性 — 先删 MySQL 再更新 Redis，避免状态分裂
     */
    private void tryTriggerSummary(Long userId, String sessionId) {
        ChatSession session = this.chatSessionService.getRaw(userId, sessionId);
        if (session == null) return;
        int pairCount = session.getMessagePairCount();
        if (pairCount <= 5) return;
        int since = pairCount - session.getLastSummaryPairCount();
        if (since < 3) return;
        try {
            List<Map<String, String>> full = session.getMessageHistory();
            int sumPairs = Math.min(session.getLastSummaryPairCount() + 3, pairCount);
            int sumCount = sumPairs * 2;
            if (full.size() < sumCount) return;
            ArrayList<Map<String, String>> toSummarize =
                    new ArrayList<>(full.subList(0, sumCount));
            ArrayList<Map<String, String>> remaining =
                    new ArrayList<>(full.subList(sumCount, full.size()));
            String summary = this.chatService.generateConversationSummary(toSummarize);
            if (summary == null || summary.trim().isEmpty()) return;
            if (summary.length() > 600) {
                logger.warn("摘要过长({}字)，截断至500字: {}", summary.length(), sessionId);
                summary = com.gagneflow.service.chat.ChatService.truncateAtSentenceBoundary(summary, 500);
            }
            if (this.tokenCounter != null) {
                int origTokens = this.tokenCounter.estimate(
                        toSummarize.stream().map(m -> m.getOrDefault("content", ""))
                                .reduce("", String::concat));
                int summaryTokens = this.tokenCounter.estimate(summary);
                double ratio = (double) summaryTokens / (double) Math.max(origTokens, 1);
                logger.info("摘要压缩比: {}/{} tokens = {:.1%}", summaryTokens, origTokens, ratio);
                if (ratio > 0.5) {
                    logger.warn("摘要压缩比过高({:.1%})，跳过本次摘要: {}", ratio, sessionId);
                    return;
                }
            }
            ArrayList<Map<String, String>> newHistory = new ArrayList<>();
            HashMap<String, String> sm = new HashMap<>();
            sm.put("role", "system");
            sm.put("content", "[历史对话摘要] " + summary);
            newHistory.add(sm);
            newHistory.addAll(remaining);
            // Step 1: 先删 MySQL 旧消息（失败则跳过，避免数据不一致）
            List<SessionMessage> msgs = this.chatSessionService.getSessionMessages(userId, sessionId);
            int deleteCount = Math.min(sumCount, msgs.size());
            if (deleteCount > 0) {
                try {
                    this.chatSessionService.deleteMessages(msgs.subList(0, deleteCount));
                } catch (Exception e) {
                    logger.warn("MySQL 旧消息清理失败，跳过本次摘要以保证数据一致性: {}", e.getMessage());
                    return;
                }
            }
            // Step 2: 再更新 Redis (乐观锁保证并发安全)
            this.chatSessionService.replaceHistory(userId, sessionId, newHistory, summary, sumPairs);
            ChatSession updated = this.chatSessionService.getRaw(userId, sessionId);
            if (this.tokenCounter != null && updated != null) {
                updated.setTotalTokens(this.tokenCounter.estimate(updated.buildFullText()));
                this.chatSessionService.saveRaw(userId, sessionId, updated);
            }
            this.memoryManager.onSummaryGenerated(userId, sessionId, summary);
            if (this.pipelineMetrics != null && this.tokenCounter != null) {
                int origTokens = this.tokenCounter.estimate(
                        toSummarize.stream().map(m -> m.getOrDefault("content", ""))
                                .reduce("", String::concat));
                int sumTokens = this.tokenCounter.estimate(summary);
                this.pipelineMetrics.recordSummaryCompression(sessionId, origTokens, sumTokens,
                        (double) sumTokens / (double) Math.max(origTokens, 1));
            }
        } catch (Exception e) {
            logger.error("对话总结失败: {}", sessionId, e);
        }
    }

    private void sendAndComplete(SseEmitter emitter, SseMessage msg) {
        if (!this.emitterCompleted.compareAndSet(false, true)) {
            logger.debug("SSE emitter already completed, skipping");
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("message").data(msg, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        } catch (IllegalStateException e) {
            logger.debug("SSE emitter already completed, skipping send: {}", e.getMessage());
        }
    }

    public FormatTool getFormatTool() {
        return formatTool;
    }
}
