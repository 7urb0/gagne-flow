package com.gagneflow.service.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PromptExperiment A/B 分流测试")
class PromptExperimentTest {

    private PromptExperiment experiment;

    @BeforeEach
    void setUp() {
        experiment = new PromptExperiment();
    }

    @Nested
    @DisplayName("基本行为")
    class BasicBehaviorTests {

        @Test
        @DisplayName("disabled=false → 始终返回 activeVersion")
        void disabled_shouldReturnActiveVersion() {
            experiment.setEnabled(false);
            int result = experiment.selectVersion("addrf_review", 2, 42L);
            assertEquals(2, result);
        }

        @Test
        @DisplayName("userId=null → 返回 activeVersion")
        void nullUserId_shouldReturnActiveVersion() {
            experiment.setEnabled(true);
            Map<String, Map<Integer, Double>> splits = new HashMap<>();
            splits.put("addrf_review", Map.of(1, 0.7, 2, 0.3));
            experiment.setSplits(splits);

            assertEquals(2, experiment.selectVersion("addrf_review", 2, null));
        }

        @Test
        @DisplayName("userId=0 → 返回 activeVersion（匿名用户不走实验）")
        void anonymousUser_shouldReturnActiveVersion() {
            experiment.setEnabled(true);
            Map<String, Map<Integer, Double>> splits = new HashMap<>();
            splits.put("addrf_review", Map.of(1, 0.7, 2, 0.3));
            experiment.setSplits(splits);

            assertEquals(2, experiment.selectVersion("addrf_review", 2, 0L));
        }

        @Test
        @DisplayName("无实验配置 → 返回 activeVersion")
        void noConfig_shouldReturnActiveVersion() {
            experiment.setEnabled(true);
            experiment.setSplits(new HashMap<>());

            assertEquals(2, experiment.selectVersion("addrf_review", 2, 42L));
        }
    }

    @Nested
    @DisplayName("确定性分配")
    class DeterministicAssignmentTests {

        private void setupTestSplit() {
            experiment.setEnabled(true);
            Map<String, Map<Integer, Double>> splits = new HashMap<>();
            splits.put("addrf_review", new HashMap<>(Map.of(1, 0.7, 2, 0.3)));
            experiment.setSplits(splits);
        }

        @Test
        @DisplayName("同一 userId 多次调用返回同一版本")
        void sameUserId_shouldReturnSameVersion() {
            setupTestSplit();
            int first = experiment.selectVersion("addrf_review", 2, 42L);
            int second = experiment.selectVersion("addrf_review", 2, 42L);
            int third = experiment.selectVersion("addrf_review", 2, 42L);

            assertEquals(first, second);
            assertEquals(second, third);
        }

        @Test
        @DisplayName("不同 userId 分布接近配置比例（采样验证）")
        void differentUsers_shouldDistributeByRatio() {
            setupTestSplit();
            int v1Count = 0;
            int v2Count = 0;
            int total = 200;

            for (long uid = 1; uid <= total; uid++) {
                int version = experiment.selectVersion("addrf_review", 2, uid);
                if (version == 1) v1Count++;
                else if (version == 2) v2Count++;
            }

            assertEquals(total, v1Count + v2Count);
            double v1Ratio = (double) v1Count / total;
            assertTrue(Math.abs(v1Ratio - 0.7) < 0.15,
                    "v1 分配比例应接近 0.7, 实际=" + String.format("%.3f", v1Ratio));
        }
    }
}
