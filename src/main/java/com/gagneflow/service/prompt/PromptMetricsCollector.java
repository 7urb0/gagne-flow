package com.gagneflow.service.prompt;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PromptMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(PromptMetricsCollector.class);

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Counter> usageCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DistributionSummary> scoreDistributions = new ConcurrentHashMap<>();

    public PromptMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // === 记录 ===

    public void recordUsage(String promptName, int versionNumber) {
        String key = promptName + ":v" + versionNumber;
        usageCounters.computeIfAbsent(key, k ->
                Counter.builder("prompt.usage.total")
                        .description("Total prompt usage count")
                        .tags("name", promptName, "version", String.valueOf(versionNumber))
                        .register(meterRegistry)
        ).increment();
    }

    public void recordScore(String promptName, int versionNumber, int score) {
        String key = promptName + ":v" + versionNumber;
        scoreDistributions.computeIfAbsent(key, k ->
                DistributionSummary.builder("prompt.score")
                        .description("Prompt review score distribution")
                        .tags("name", promptName, "version", String.valueOf(versionNumber))
                        .publishPercentiles(0.5, 0.95)
                        .register(meterRegistry)
        ).record(score);
    }

    // === 查询（实时聚合） ===

    public Map<Integer, VersionStats> getStats(String promptName) {
        Map<Integer, VersionStats> result = new HashMap<>();

        for (int v = 1; v <= 10; v++) {
            String usageKey = promptName + ":v" + v;
            String scoreKey = promptName + ":v" + v;

            Counter usage = usageCounters.get(usageKey);
            DistributionSummary score = scoreDistributions.get(scoreKey);

            if (usage != null && usage.count() > 0) {
                VersionStats stats = new VersionStats();
                stats.versionNumber = v;
                stats.usageCount = (long) usage.count();
                if (score != null && score.count() > 0) {
                    stats.avgScore = score.mean();
                }
                result.put(v, stats);
            }
        }

        return result;
    }

    public PromptComparison compare(String promptName, int versionA, int versionB) {
        Map<Integer, VersionStats> stats = getStats(promptName);

        VersionStats sA = stats.getOrDefault(versionA, new VersionStats());
        VersionStats sB = stats.getOrDefault(versionB, new VersionStats());

        PromptComparison pc = new PromptComparison();
        pc.versionA = versionA;
        pc.versionB = versionB;
        pc.avgScoreA = sA.avgScore;
        pc.avgScoreB = sB.avgScore;
        pc.usageCountA = sA.usageCount;
        pc.usageCountB = sB.usageCount;

        if (sA.usageCount > 0 && sB.usageCount > 0 && sA.avgScore != sB.avgScore) {
            String winner = sA.avgScore > sB.avgScore ? "v" + versionA : "v" + versionB;
            double diff = Math.abs(sA.avgScore - sB.avgScore);
            double pct = diff / Math.max(sA.avgScore, 1) * 100;
            pc.summary = String.format("%s wins by +%.1f (%.1f%%)", winner, diff, pct);
        } else if (sA.usageCount == 0 && sB.usageCount == 0) {
            pc.summary = "无数据 — 两个版本均无调用记录";
        } else {
            pc.summary = "样本不足，无法判定";
        }

        logger.info("Prompt 对比: {} v{} vs v{} → {}", promptName, versionA, versionB, pc.summary);
        return pc;
    }

    // === 内部类 ===

    public static class VersionStats {
        public int versionNumber;
        public long usageCount;
        public double avgScore;
    }

    public static class PromptComparison {
        public int versionA;
        public int versionB;
        public double avgScoreA;
        public double avgScoreB;
        public long usageCountA;
        public long usageCountB;
        public String summary;
    }
}
