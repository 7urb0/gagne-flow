package com.gagneflow.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PipelineMetrics {
    private static final Logger logger = LoggerFactory.getLogger(PipelineMetrics.class);

    private final Counter ragSearchCounter;
    private final Timer ragSearchTimer;
    private final DistributionSummary ragCandidatesSummary;
    private final Counter citationLossCounter;
    private final Counter summaryCompressionCounter;
    private final Counter contextBuildCounter;
    private final Counter addieStageCounter;
    private final Counter hitlTriggerCounter;
    private final Timer addieStageTimer;

    public PipelineMetrics(MeterRegistry meterRegistry) {
        this.ragSearchCounter = Counter.builder("gagneflow.rag.search.total")
                .description("Total RAG search requests").register(meterRegistry);
        this.ragSearchTimer = Timer.builder("gagneflow.rag.search.duration")
                .description("RAG search duration").register(meterRegistry);
        this.ragCandidatesSummary = DistributionSummary.builder("gagneflow.rag.candidates")
                .description("Number of candidates retrieved per search").register(meterRegistry);
        this.citationLossCounter = Counter.builder("gagneflow.rag.citation.loss")
                .description("RAG answers with missing citations").register(meterRegistry);
        this.summaryCompressionCounter = Counter.builder("gagneflow.memory.summary.compression")
                .description("Summary compression triggers").register(meterRegistry);
        this.contextBuildCounter = Counter.builder("gagneflow.memory.context.build")
                .description("Context build operations").register(meterRegistry);
        this.addieStageCounter = Counter.builder("gagneflow.addie.stage.total")
                .description("Total ADDIE stage executions").register(meterRegistry);
        this.hitlTriggerCounter = Counter.builder("gagneflow.hitl.trigger.total")
                .description("Total HITL triggers (human review required)").register(meterRegistry);
        this.addieStageTimer = Timer.builder("gagneflow.addie.stage.duration")
                .description("ADDIE stage execution duration").register(meterRegistry);
    }

    public void recordRagSearch(String query, long durationMs, int candidateCount, int finalCount, double avgRelevance) {
        ragSearchCounter.increment();
        ragSearchTimer.record(Duration.ofMillis(durationMs));
        ragCandidatesSummary.record(candidateCount);
        logger.info("[RAG-METRIC] query=\"{}\" duration={}ms candidates={} final={} avgRelevance={:.3f}",
                truncate(query, 80), durationMs, candidateCount, finalCount, avgRelevance);
    }

    public void recordCitations(String answer, int expectedCitations) {
        int maxCitation = 0;
        Matcher m = Pattern.compile("\\[(\\d+)\\]").matcher(answer != null ? answer : "");
        while (m.find()) {
            maxCitation = Math.max(maxCitation, Integer.parseInt(m.group(1)));
        }
        if (expectedCitations > 0 && maxCitation == 0) {
            citationLossCounter.increment();
            logger.warn("[RAG-METRIC] 引用丢失: expected={} but 0 citations in answer", expectedCitations);
        } else {
            logger.info("[RAG-METRIC] 引用数: {}/{}", maxCitation, expectedCitations);
        }
    }

    public void recordSummaryCompression(String sessionId, int originalTokens, int summaryTokens, double ratio) {
        summaryCompressionCounter.increment();
        logger.info("[MEM-METRIC] session={} compression {}→{}tokens ratio={:.1%}",
                sessionId, originalTokens, summaryTokens, ratio);
    }

    public void recordContextBuild(String sessionId, int shortTermTokens, int ltmChars, int totalTokens) {
        contextBuildCounter.increment();
        logger.info("[MEM-METRIC] session={} ctx: short={}tokens ltm={}chars total={}tokens",
                sessionId, shortTermTokens, ltmChars, totalTokens);
    }

    public void recordAddieStage(String stage, long durationMs, int outputChars) {
        addieStageCounter.increment();
        addieStageTimer.record(Duration.ofMillis(durationMs));
        logger.info("[ADDIE-METRIC] stage={} duration={}ms output={}chars", stage, durationMs, outputChars);
    }

    public void recordHitlTrigger(String subject, Long userId) {
        hitlTriggerCounter.increment();
        logger.info("[HITL-METRIC] subject={} userId={} trigger=total", subject, userId);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
