package com.gagneflow.controller;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.gagneflow.entity.PromptVersion;
import com.gagneflow.service.prompt.PromptExperiment;
import com.gagneflow.service.prompt.PromptMetricsCollector;
import com.gagneflow.service.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PromptAdminController 管理端点测试")
class PromptAdminControllerTest {

    private PromptAdminController controller;
    private PromptRegistry promptRegistry;
    private PromptExperiment promptExperiment;
    private PromptMetricsCollector promptMetrics;

    @BeforeEach
    void setUp() throws Exception {
        promptRegistry = mock(PromptRegistry.class);
        promptExperiment = mock(PromptExperiment.class);
        promptMetrics = mock(PromptMetricsCollector.class);

        controller = new PromptAdminController();
        injectField("promptRegistry", promptRegistry);
        injectField("promptExperiment", promptExperiment);
        injectField("promptMetrics", promptMetrics);
    }

    private void injectField(String name, Object value) throws Exception {
        Field field = PromptAdminController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private PromptVersion version(int v, boolean active, String desc) {
        PromptVersion pv = new PromptVersion("addie_review", v, "content-" + v, desc);
        pv.setActive(active);
        return pv;
    }

    @Nested
    @DisplayName("GET /{name} 列出版本")
    class ListVersionsTests {

        @Test
        @DisplayName("返回版本列表，含 versionNumber/active/contentLength")
        void listVersions_shouldMapAllFields() {
            when(promptRegistry.listVersions("addie_review"))
                    .thenReturn(List.of(version(1, false, "v1"), version(2, true, "v2")));

            ResponseEntity<List<Map<String, Object>>> res =
                    controller.listVersions("addie_review");

            assertEquals(HttpStatus.OK, res.getStatusCode());
            List<Map<String, Object>> body = res.getBody();
            assertNotNull(body);
            assertEquals(2, body.size());
            assertEquals(2, body.get(1).get("versionNumber"));
            assertEquals(true, body.get(1).get("active"));
            assertEquals("v2", body.get(1).get("description"));
            assertEquals(9, body.get(1).get("contentLength")); // "content-2".length()=9
            assertNotNull(body.get(1).get("createdAt"));
        }

        @Test
        @DisplayName("description 为 null 时返回空串不 NPE")
        void listVersions_nullDescription_returnsEmptyString() {
            when(promptRegistry.listVersions("addie_review"))
                    .thenReturn(List.of(version(1, true, null)));

            ResponseEntity<List<Map<String, Object>>> res =
                    controller.listVersions("addie_review");

            assertEquals("", res.getBody().get(0).get("description"));
        }
    }

    @Nested
    @DisplayName("GET / 列出名称")
    class ListPromptNamesTests {

        @Test
        @DisplayName("registry 有记录时返回 distinct 名称")
        void listNames_shouldReturnDistinct() {
            when(promptRegistry.listVersions("addie_analysis"))
                    .thenReturn(List.of(version(1, true, null), version(2, true, null)));

            ResponseEntity<List<String>> res = controller.listPromptNames();

            assertEquals(HttpStatus.OK, res.getStatusCode());
            assertEquals(List.of("addie_review"), res.getBody());
        }

        @Test
        @DisplayName("registry 无记录时返回默认 4 个 addie 名称")
        void listNames_empty_returnsDefaults() {
            when(promptRegistry.listVersions("addie_analysis")).thenReturn(List.of());

            ResponseEntity<List<String>> res = controller.listPromptNames();

            assertEquals(List.of("addie_analysis", "addie_design", "addie_development", "addie_review"),
                    res.getBody());
        }
    }

    @Nested
    @DisplayName("POST /{name}/{version}/activate")
    class ActivateTests {

        @Test
        @DisplayName("激活成功返回目标版本信息")
        void activate_shouldReturnActivatedVersion() {
            when(promptRegistry.activate("addie_review", 2)).thenReturn(version(2, true, "v2"));

            ResponseEntity<Map<String, Object>> res = controller.activate("addie_review", 2);

            assertEquals(HttpStatus.OK, res.getStatusCode());
            Map<String, Object> body = res.getBody();
            assertEquals("addie_review", body.get("promptName"));
            assertEquals(2, body.get("versionNumber"));
            assertEquals(true, body.get("active"));
            assertTrue(body.get("message").toString().contains("已激活"));
        }
    }

    @Nested
    @DisplayName("GET /{name}/compare 与 /{name}/stats")
    class MetricsTests {

        @Test
        @DisplayName("compare 返回 PromptComparison")
        void compare_shouldReturnComparison() {
            PromptMetricsCollector.PromptComparison cmp =
                    mock(PromptMetricsCollector.PromptComparison.class);
            when(promptMetrics.compare("addie_review", 1, 2)).thenReturn(cmp);

            ResponseEntity<PromptMetricsCollector.PromptComparison> res =
                    controller.compare("addie_review", 1, 2);

            assertEquals(HttpStatus.OK, res.getStatusCode());
            assertSame(cmp, res.getBody());
        }

        @Test
        @DisplayName("stats 返回版本统计 Map")
        void stats_shouldReturnStats() {
            Map<Integer, PromptMetricsCollector.VersionStats> stats =
                    Map.of(1, mock(PromptMetricsCollector.VersionStats.class));
            when(promptMetrics.getStats("addie_review")).thenReturn(stats);

            ResponseEntity<Map<Integer, PromptMetricsCollector.VersionStats>> res =
                    controller.getStats("addie_review");

            assertEquals(HttpStatus.OK, res.getStatusCode());
            assertEquals(stats, res.getBody());
        }
    }

    @Nested
    @DisplayName("GET /experiment/status")
    class ExperimentStatusTests {

        @Test
        @DisplayName("返回 enabled 与 splits")
        void experimentStatus_shouldReturnConfig() {
            when(promptExperiment.isEnabled()).thenReturn(true);
            when(promptExperiment.getSplits())
                    .thenReturn(Map.of("addie_review", Map.of(1, 0.7, 2, 0.3)));

            ResponseEntity<Map<String, Object>> res = controller.experimentStatus();

            assertEquals(HttpStatus.OK, res.getStatusCode());
            assertEquals(true, res.getBody().get("enabled"));
            assertNotNull(res.getBody().get("splits"));
        }
    }
}
