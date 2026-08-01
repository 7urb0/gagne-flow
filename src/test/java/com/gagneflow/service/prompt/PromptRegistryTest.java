package com.gagneflow.service.prompt;

import java.util.List;
import java.util.Optional;

import com.gagneflow.entity.PromptVersion;
import com.gagneflow.repository.PromptVersionRepository;
import com.gagneflow.service.document.PromptLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PromptRegistry 注册中心测试")
class PromptRegistryTest {

    private PromptRegistry registry;
    private PromptVersionRepository repo;
    private PromptLoader fileLoader;

    @BeforeEach
    void setUp() {
        repo = mock(PromptVersionRepository.class);
        fileLoader = mock(PromptLoader.class);
        registry = new PromptRegistry(repo, fileLoader);
        ReflectionTestUtils.setField(registry, "promptsBasePath", "agent-config/prompts");
    }

    @Nested
    @DisplayName("getContent")
    class GetContentTests {

        @Test
        @DisplayName("活跃版本存在 → 返回内容")
        void activeVersionExists_shouldReturnContent() {
            PromptVersion pv = new PromptVersion("addrf_review", 2,
                    "你是一个教育AI助手，请根据输入生成教案。", "修改评分标准");
            pv.setActive(true);
            when(repo.findByPromptNameAndActiveTrue("addrf_review"))
                    .thenReturn(Optional.of(pv));

            String content = registry.getContent("addrf_review");
            assertTrue(content.contains("教育AI助手"));
        }

        @Test
        @DisplayName("活跃版本不存在 → 降级到 PromptLoader")
        void noActiveVersion_shouldFallbackToFileLoader() {
            when(repo.findByPromptNameAndActiveTrue("addrf_review"))
                    .thenReturn(Optional.empty());
            when(fileLoader.load(eq("addrf/addrf_review")))
                    .thenReturn("fallback content from file");

            String content = registry.getContent("addrf_review");
            assertTrue(content.contains("fallback"));
        }

        @Test
        @DisplayName("指定版本查询 → 返回该版本内容")
        void specificVersion_shouldReturnThatVersion() {
            PromptVersion v1 = new PromptVersion("addrf_review", 1,
                    "v1 content", "初始版本");
            when(repo.findByPromptNameAndVersionNumber("addrf_review", 1))
                    .thenReturn(Optional.of(v1));

            String content = registry.getContent("addrf_review", 1);
            assertEquals("v1 content", content);
        }
    }

    @Nested
    @DisplayName("activate")
    class ActivateTests {

        @Test
        @DisplayName("切换活跃版本 → 旧版本 deactivate + 新版本 activate")
        void activate_differentVersion_shouldSwitch() {
            PromptVersion oldActive = new PromptVersion("addrf_review", 1,
                    "old content", "v1");
            oldActive.setActive(true);

            PromptVersion target = new PromptVersion("addrf_review", 2,
                    "new content", "v2");
            target.setActive(false);

            when(repo.findByPromptNameAndActiveTrue("addrf_review"))
                    .thenReturn(Optional.of(oldActive));
            when(repo.findByPromptNameAndVersionNumber("addrf_review", 2))
                    .thenReturn(Optional.of(target));

            PromptVersion result = registry.activate("addrf_review", 2);

            assertTrue(result.isActive());
            assertEquals(2, result.getVersionNumber());
            verify(repo, times(1)).save(oldActive);
            verify(repo, times(1)).save(target);
        }

        @Test
        @DisplayName("不存在的版本号 → 抛出异常")
        void activate_nonexistentVersion_shouldThrow() {
            when(repo.findByPromptNameAndVersionNumber("addrf_review", 99))
                    .thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> registry.activate("addrf_review", 99));
        }
    }

    @Nested
    @DisplayName("getActiveVersionNumber")
    class GetActiveVersionTests {

        @Test
        @DisplayName("活跃版本存在 → 返回版本号")
        void activeExists_shouldReturnVersionNumber() {
            PromptVersion pv = new PromptVersion("addrf_analysis", 3, "content", "v3");
            pv.setActive(true);
            when(repo.findByPromptNameAndActiveTrue("addrf_analysis"))
                    .thenReturn(Optional.of(pv));

            assertEquals(3, registry.getActiveVersionNumber("addrf_analysis"));
        }

        @Test
        @DisplayName("无活跃版本 → 返回默认值 1")
        void noActiveVersion_shouldReturnOne() {
            when(repo.findByPromptNameAndActiveTrue("addrf_review"))
                    .thenReturn(Optional.empty());

            assertEquals(1, registry.getActiveVersionNumber("addrf_review"));
        }
    }

    @Nested
    @DisplayName("listVersions")
    class ListVersionsTests {

        @Test
        @DisplayName("返回所有版本列表")
        void shouldReturnAllVersions() {
            PromptVersion v1 = new PromptVersion("addrf_review", 1, "c1", "v1");
            PromptVersion v2 = new PromptVersion("addrf_review", 2, "c2", "v2");
            when(repo.findByPromptNameOrderByVersionNumberDesc("addrf_review"))
                    .thenReturn(List.of(v2, v1));

            List<PromptVersion> versions = registry.listVersions("addrf_review");
            assertEquals(2, versions.size());
            assertEquals(2, versions.get(0).getVersionNumber());
            assertEquals(1, versions.get(1).getVersionNumber());
        }
    }
}
