package com.gagneflow.service.prompt;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gagneflow.prompt.experiment")
public class PromptExperiment {
    private static final Logger logger = LoggerFactory.getLogger(PromptExperiment.class);

    /** 是否启用 A/B 实验 */
    private boolean enabled = false;

    /**
     * { promptName: { versionNumber: ratio } } 例: {"addrf_review": {1: 0.7, 2: 0.3}}
     * 使用 LinkedHashMap 保持配置顺序, 保证 selectVersion 累积概率遍历确定性
     * (HashMap 迭代顺序随机会破坏"同一用户恒定版本"的设计)
     */
    private Map<String, Map<Integer, Double>> splits = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Map<String, Map<Integer, Double>> getSplits() { return splits; }
    public void setSplits(Map<String, Map<Integer, Double>> splits) { this.splits = splits; }

    /**
     * 基于 userId 做确定性版本分配。
     * 同一 userId 始终返回同一版本，保证一个 session 内 Prompt 一致。
     *
     * @param promptName    prompt 名称
     * @param activeVersion 当前活跃版本号
     * @param userId        用户标识（用于 hash 取模）
     * @return 分配的版本号
     */
    public int selectVersion(String promptName, int activeVersion, Long userId) {
        if (!enabled || userId == null || userId == 0L) {
            return activeVersion;
        }

        Map<Integer, Double> ratios = splits.get(promptName);
        if (ratios == null || ratios.isEmpty()) {
            return activeVersion;
        }

        // 基于 userId 做确定性 hash，同一用户始终走同一版本
        int bucket = Math.abs(userId.hashCode()) % 100;
        double roll = bucket / 100.0;

        double cumulative = 0.0;
        for (Map.Entry<Integer, Double> entry : ratios.entrySet()) {
            cumulative += entry.getValue();
            if (roll <= cumulative) {
                if (entry.getKey() != activeVersion) {
                    logger.debug("Prompt 实验分流: {} userId={} bucket={} → v{} (active=v{})",
                            promptName, userId, bucket, entry.getKey(), activeVersion);
                }
                return entry.getKey();
            }
        }

        return activeVersion;
    }
}
