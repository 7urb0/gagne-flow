package com.gagneflow.agent.tool;

import com.gagneflow.service.document.K12CurriculumLoader;
import com.gagneflow.service.vector.VectorSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * InternalDocsTools 知识库检索工具测试。
 * 覆盖: 检索成功、空结果 K12 降级、无结果、异常、K12 课标查询、当前用户解析。
 */
@DisplayName("InternalDocsTools 知识库工具测试")
class InternalDocsToolsTest {

    private VectorSearchService vectorSearchService;
    private K12CurriculumLoader k12Loader;
    private InternalDocsTools tools;

    @BeforeEach
    void setUp() {
        vectorSearchService = mock(VectorSearchService.class);
        k12Loader = mock(K12CurriculumLoader.class);
        tools = new InternalDocsTools(vectorSearchService, k12Loader);
    }

    private VectorSearchService.SearchResult makeResult(String id, String content, float score) {
        VectorSearchService.SearchResult r = new VectorSearchService.SearchResult();
        r.setId(id);
        r.setContent(content);
        r.setScore(score);
        r.setMetadata("{\"_file_name\":\"数学课标.pdf\"}");
        return r;
    }

    @Nested
    @DisplayName("queryInternalDocs")
    class QueryInternalDocsTests {

        @Test
        @DisplayName("检索有结果 → 返回结果 JSON")
        void hasResults_returnsJson() {
            when(vectorSearchService.searchWithRerank(anyString(), anyLong()))
                    .thenReturn(List.of(makeResult("1", "分数加减法", 0.85f)));

            String json = tools.queryInternalDocs("分数的加减法");

            assertTrue(json.contains("\"id\"") || json.contains("id"), "应返回结果 JSON");
            assertTrue(json.contains("分数加减法"));
        }

        @Test
        @DisplayName("检索为空 + K12 命中 → 返回 k12_fallback")
        void emptyResults_k12Hit_returnsK12Fallback() {
            when(vectorSearchService.searchWithRerank(anyString(), anyLong()))
                    .thenReturn(List.of());
            when(k12Loader.lookup(any(), any(), any())).thenReturn("小学数学课标内容");

            String json = tools.queryInternalDocs("小学数学分数");

            assertTrue(json.contains("k12_fallback"));
            assertTrue(json.contains("小学数学课标内容"));
        }

        @Test
        @DisplayName("检索为空 + K12 也空 → 返回 no_results")
        void emptyResults_noK12_returnsNoResults() {
            when(vectorSearchService.searchWithRerank(anyString(), anyLong()))
                    .thenReturn(List.of());
            when(k12Loader.lookup(any(), any(), any())).thenReturn("");

            String json = tools.queryInternalDocs("不相关内容查询");

            assertTrue(json.contains("no_results"));
        }

        @Test
        @DisplayName("检索异常 → 返回 error JSON 不抛异常")
        void searchThrows_returnsErrorJson() {
            when(vectorSearchService.searchWithRerank(anyString(), anyLong()))
                    .thenThrow(new RuntimeException("Milvus timeout"));

            String json = tools.queryInternalDocs("查询");

            assertTrue(json.contains("\"status\": \"error\""));
        }

        @Test
        @DisplayName("K12 fallback: 查询含小学+数学 → 调 lookup")
        void k12Fallback_matchesStageAndSubject() {
            when(vectorSearchService.searchWithRerank(anyString(), anyLong()))
                    .thenReturn(List.of());
            when(k12Loader.lookup("小学", null, "数学")).thenReturn("小学数学课标");

            tools.queryInternalDocs("小学三年级的数学知识点");

            verify(k12Loader).lookup("小学", null, "数学");
        }
    }

    @Nested
    @DisplayName("queryK12Curriculum")
    class QueryK12Tests {

        @Test
        @DisplayName("正常查询 → 返回课标内容")
        void normalQuery_returnsContent() {
            when(k12Loader.lookup("小学", "三年级", "数学")).thenReturn("三年级数学课标");

            String result = tools.queryK12Curriculum("小学", "三年级", "数学");

            assertEquals("三年级数学课标", result);
        }

        @Test
        @DisplayName("空参 → 传 null 查询全部")
        void emptyParams_passNull() {
            when(k12Loader.lookup(null, null, null)).thenReturn("全部课标");

            String result = tools.queryK12Curriculum("", "", "");

            assertEquals("全部课标", result);
            verify(k12Loader).lookup(null, null, null);
        }

        @Test
        @DisplayName("lookup 异常 → 返回错误消息不抛异常")
        void lookupThrows_returnsErrorMessage() {
            when(k12Loader.lookup(any(), any(), any()))
                    .thenThrow(new RuntimeException("JSON 解析失败"));

            String result = tools.queryK12Curriculum("高中", "高一", "物理");

            assertTrue(result.contains("查询 K12 课程标准失败"));
        }
    }

    @Nested
    @DisplayName("当前用户解析")
    class CurrentUserTests {

        @Test
        @DisplayName("无认证上下文 → userId 为 0")
        void noAuth_returnsZero() {
            SecurityContextHolder.clearContext();
            String json = tools.queryInternalDocs("查询");

            verify(vectorSearchService).searchWithRerank(anyString(), eq(0L));
        }

        @Test
        @DisplayName("认证主体为 Long → 使用该 userId")
        void authWithLongPrincipal_usesUserId() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(42L, null, List.of()));
            try {
                String json = tools.queryInternalDocs("查询");
                verify(vectorSearchService).searchWithRerank(anyString(), eq(42L));
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        @Test
        @DisplayName("认证主体非 Long → 回退 0")
        void authWithNonLongPrincipal_fallsBackToZero() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("anonymous", null, List.of()));
            try {
                tools.queryInternalDocs("查询");
                verify(vectorSearchService).searchWithRerank(anyString(), eq(0L));
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }
}
