package com.gagneflow.agent.tool;

import com.gagneflow.entity.SessionMessage;
import com.gagneflow.repository.SessionMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LessonPlanTools 最近教案检索工具测试。
 * 覆盖: Redis 命中、MySQL 降级命中、未命中、用户隔离、存档写入。
 */
@DisplayName("LessonPlanTools 最近教案工具测试")
class LessonPlanToolsTest {

    private static final Long UID = 42L;

    private SessionMessageRepository repo;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private LessonPlanTools tools;

    @BeforeEach
    void setUp() {
        repo = mock(SessionMessageRepository.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        tools = new LessonPlanTools(repo, redis);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authAs(Long uid) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(uid, null, List.of()));
    }

    private String makeHtml() {
        return "<!DOCTYPE html><html><head><title>教案</title></head><body>"
                + "教学目标：掌握分数加减法。".repeat(20)
                + "SECRET_FULL_CONTENT_MARKER_超过预览截断长度的内容";
    }

    @Nested
    @DisplayName("getLatestLessonPlan 命中场景")
    class HitTests {

        @Test
        @DisplayName("MySQL 命中 → 返回 ok JSON(含 pdfUrl+preview, 不含全量 HTML)")
        void mysqlHit_returnsOkJson() {
            authAs(UID);
            String html = makeHtml();
            SessionMessage msg = new SessionMessage(UID, "s1", "assistant", html);
            when(repo.findLatestByUserIdAndRoleAndContentPrefix(eq(UID), eq("assistant"),
                    eq(LessonPlanTools.HTML_PREFIX), any(Pageable.class)))
                    .thenReturn(List.of(msg));

            String json = tools.getLatestLessonPlan();

            assertTrue(json.contains("\"status\":\"ok\""));
            assertTrue(json.contains("\"sessionId\":\"s1\""));
            assertTrue(json.contains("\"pdfUrl\":\"/api/lesson_plan/pdf/s1\""));
            assertTrue(json.contains("\"preview\""));
            // 只回截断预览(前200字)，绝不含全量 HTML（省 token + 防注入）
            assertFalse(json.contains("SECRET_FULL_CONTENT_MARKER"),
                    "不应包含超过预览截断长度的 HTML 内容");
            assertFalse(json.length() > 1000, "返回 JSON 不应携带全量 HTML: " + json.length());
            // 校验取的是最新一条(PageRequest(0,1))
            verify(repo).findLatestByUserIdAndRoleAndContentPrefix(eq(UID), eq("assistant"),
                    eq(LessonPlanTools.HTML_PREFIX), argThat(p -> p.getPageNumber() == 0 && p.getPageSize() == 1));
        }

        @Test
        @DisplayName("Redis 存档命中 → 直接返回 ok JSON，不查 MySQL")
        void redisHit_returnsOkJson() {
            authAs(UID);
            when(valueOps.get("gagneflow:lesson:latest:" + UID))
                    .thenReturn("{\"sessionId\":\"s_redis\",\"html\":\"<!DOCTYPE html><p>Redis教案内容</p>\"}");

            String json = tools.getLatestLessonPlan();

            assertTrue(json.contains("\"status\":\"ok\""));
            assertTrue(json.contains("\"sessionId\":\"s_redis\""));
            assertTrue(json.contains("\"pdfUrl\":\"/api/lesson_plan/pdf/s_redis\""));
            assertTrue(json.contains("\"preview\""));
            verify(repo, never()).findLatestByUserIdAndRoleAndContentPrefix(
                    anyLong(), anyString(), anyString(), any(Pageable.class));
        }

        @Test
        @DisplayName("Redis 数据损坏 → 降级查 MySQL 命中")
        void redisCorrupt_fallsBackToMySql() {
            authAs(UID);
            when(valueOps.get("gagneflow:lesson:latest:" + UID)).thenReturn("not-json{{");
            SessionMessage msg = new SessionMessage(UID, "s2", "assistant", "<!DOCTYPE html><p>fallback</p>");
            when(repo.findLatestByUserIdAndRoleAndContentPrefix(eq(UID), eq("assistant"),
                    eq(LessonPlanTools.HTML_PREFIX), any(Pageable.class)))
                    .thenReturn(List.of(msg));

            String json = tools.getLatestLessonPlan();

            assertTrue(json.contains("\"sessionId\":\"s2\""));
            verify(repo).findLatestByUserIdAndRoleAndContentPrefix(eq(UID), eq("assistant"),
                    eq(LessonPlanTools.HTML_PREFIX), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("getLatestLessonPlan 未命中场景")
    class MissTests {

        @Test
        @DisplayName("MySQL 无记录 → 返回 no_lesson_plan")
        void noRecord_returnsNoLessonPlan() {
            authAs(UID);
            when(repo.findLatestByUserIdAndRoleAndContentPrefix(anyLong(), anyString(),
                    anyString(), any(Pageable.class))).thenReturn(List.of());

            String json = tools.getLatestLessonPlan();

            assertEquals("{\"status\":\"no_lesson_plan\",\"message\":\"未找到最近生成的教案\"}", json);
        }

        @Test
        @DisplayName("无认证上下文(userId=0) → 直接 no_lesson_plan, 不查库(用户隔离)")
        void noAuth_returnsNoLessonPlan_withoutQuery() {
            SecurityContextHolder.clearContext();

            String json = tools.getLatestLessonPlan();

            assertEquals("{\"status\":\"no_lesson_plan\",\"message\":\"未找到最近生成的教案\"}", json);
            verify(repo, never()).findLatestByUserIdAndRoleAndContentPrefix(
                    anyLong(), anyString(), anyString(), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("用户隔离")
    class IsolationTests {

        @Test
        @DisplayName("认证主体为 Long → 只查该 userId, 不跨用户")
        void authUsesPrincipalUserId() {
            authAs(UID);
            tools.getLatestLessonPlan();

            verify(repo).findLatestByUserIdAndRoleAndContentPrefix(eq(UID), eq("assistant"),
                    eq(LessonPlanTools.HTML_PREFIX), any(Pageable.class));
        }

        @Test
        @DisplayName("认证主体非 Long → 回退 0, 不查任何用户教案")
        void nonLongPrincipal_fallsBackZero_noQuery() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("anonymous", null, List.of()));

            String json = tools.getLatestLessonPlan();

            assertEquals("{\"status\":\"no_lesson_plan\",\"message\":\"未找到最近生成的教案\"}", json);
            verify(repo, never()).findLatestByUserIdAndRoleAndContentPrefix(
                    anyLong(), anyString(), anyString(), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("archiveLatestLessonPlan 教案长驻存档")
    class ArchiveTests {

        @Test
        @DisplayName("正常存档 → Redis 写入 JSON(含 sessionId+html), 带 7 天 TTL")
        void archive_writesJsonWithTtl() {
            String html = "<!DOCTYPE html><p>教案</p>";

            tools.archiveLatestLessonPlan(UID, "s1", html);

            verify(valueOps).set(eq("gagneflow:lesson:latest:" + UID),
                    argThat(s -> s.contains("\"sessionId\":\"s1\"") && s.contains("教案")),
                    argThat(d -> d.equals(Duration.ofDays(7))));
        }

        @Test
        @DisplayName("空 sessionId/html → 不写 Redis")
        void archive_emptyInput_skipsWrite() {
            tools.archiveLatestLessonPlan(UID, "", "<!DOCTYPE html>");
            tools.archiveLatestLessonPlan(UID, "s1", "");
            tools.archiveLatestLessonPlan(null, "s1", "<!DOCTYPE html>");

            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("Redis 异常 → 不抛异常(不影响主流程)")
        void archive_redisThrows_noException() {
            doThrow(new RuntimeException("Redis down"))
                    .when(valueOps).set(anyString(), anyString(), any(Duration.class));

            assertDoesNotThrow(() -> tools.archiveLatestLessonPlan(UID, "s1", "<!DOCTYPE html>"));
        }
    }
}
