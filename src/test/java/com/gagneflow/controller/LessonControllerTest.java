package com.gagneflow.controller;

import com.gagneflow.service.document.SubjectFormatLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("LessonController 简单端点单元测试")
class LessonControllerTest {

    private LessonController newController() {
        return new LessonController();
    }

    @Test
    void getSubjectPlaceholder_loaderNull_returnsDefault() {
        LessonController c = newController();
        ResponseEntity<Map<String, String>> resp = c.getSubjectPlaceholder("数学");
        assertEquals(200, resp.getStatusCodeValue());
        assertEquals("请输入教学目标...", resp.getBody().get("placeholder"));
    }

    @Test
    void getSubjectPlaceholder_loaderUnloaded_returnsDefault() {
        LessonController c = newController();
        SubjectFormatLoader sfl = mock(SubjectFormatLoader.class);
        when(sfl.isLoaded()).thenReturn(false);
        ReflectionTestUtils.setField(c, "subjectFormatLoader", sfl);
        ResponseEntity<Map<String, String>> resp = c.getSubjectPlaceholder("数学");
        assertEquals("请输入教学目标...", resp.getBody().get("placeholder"));
    }

    @Test
    void getSubjectPlaceholder_loaderLoaded_returnsPlaceholder() {
        LessonController c = newController();
        SubjectFormatLoader sfl = mock(SubjectFormatLoader.class);
        when(sfl.isLoaded()).thenReturn(true);
        when(sfl.getPlaceholder("数学")).thenReturn("数学学科教案目标模板");
        ReflectionTestUtils.setField(c, "subjectFormatLoader", sfl);
        ResponseEntity<Map<String, String>> resp = c.getSubjectPlaceholder("数学");
        assertEquals("数学学科教案目标模板", resp.getBody().get("placeholder"));
    }

    @Test
    void lessonPlanAction_missingFields_badRequest() {
        LessonController c = newController();
        ResponseEntity<?> resp = c.lessonPlanAction(Map.of("token", "tok"));
        assertEquals(400, resp.getStatusCodeValue());
    }

    @Test
    void lessonPlanAction_tokenNotFound_404() {
        LessonController c = newController();
        ResponseEntity<?> resp = c.lessonPlanAction(Map.of("token", "missing", "action", "continue"));
        assertEquals(404, resp.getStatusCodeValue());
    }

    @Test
    void lessonPlanAction_continue_offersQueue() throws Exception {
        LessonController c = newController();
        BlockingQueue<String> q = new LinkedBlockingQueue<>(1);
        ConcurrentHashMap<String, BlockingQueue<String>> queues = new ConcurrentHashMap<>();
        queues.put("tok1", q);
        ReflectionTestUtils.setField(c, "copilotQueues", queues);
        ResponseEntity<?> resp = c.lessonPlanAction(Map.of("token", "tok1", "action", "continue"));
        assertEquals(200, resp.getStatusCodeValue());
        assertEquals("continue", q.poll());
    }

    @Test
    void lessonPlanAction_revise_offersReviseWithInstruction() throws Exception {
        LessonController c = newController();
        BlockingQueue<String> q = new LinkedBlockingQueue<>(1);
        ConcurrentHashMap<String, BlockingQueue<String>> queues = new ConcurrentHashMap<>();
        queues.put("tok2", q);
        ReflectionTestUtils.setField(c, "copilotQueues", queues);
        ResponseEntity<?> resp = c.lessonPlanAction(
                Map.of("token", "tok2", "action", "revise", "instruction", "加强互动环节"));
        assertEquals(200, resp.getStatusCodeValue());
        assertEquals("revise:加强互动环节", q.poll());
    }

    // ============================================================
    // 用户评分接口 (2026-08-18 人机协同 Review)
    // ============================================================

    @Test
    void lessonPlanScore_missingFields_badRequest() {
        LessonController c = newController();
        assertEquals(400, c.lessonPlanScore(Map.of("sessionId", "s1")).getStatusCodeValue());
        assertEquals(400, c.lessonPlanScore(Map.of("score", "3")).getStatusCodeValue());
    }

    @Test
    void lessonPlanScore_invalidScore_badRequest() {
        LessonController c = newController();
        assertEquals(400, c.lessonPlanScore(Map.of("sessionId", "s1", "score", "0")).getStatusCodeValue());
        assertEquals(400, c.lessonPlanScore(Map.of("sessionId", "s1", "score", "6")).getStatusCodeValue());
        assertEquals(400, c.lessonPlanScore(Map.of("sessionId", "s1", "score", "abc")).getStatusCodeValue());
    }

    @Test
    void lessonPlanScore_noActiveResult_404() {
        LessonController c = newController();
        com.gagneflow.service.lesson.AddrfPipeline pipeline = mock(com.gagneflow.service.lesson.AddrfPipeline.class);
        when(pipeline.getActiveResult("s1")).thenReturn(null);
        ReflectionTestUtils.setField(c, "addrfPipeline", pipeline);
        assertEquals(404, c.lessonPlanScore(Map.of("sessionId", "s1", "score", "3")).getStatusCodeValue());
    }

    @Test
    void lessonPlanScore_validScore_writesToResult() {
        LessonController c = newController();
        com.gagneflow.service.lesson.AddrfPipeline pipeline = mock(com.gagneflow.service.lesson.AddrfPipeline.class);
        com.gagneflow.service.lesson.AddrfPipeline.AddrfResult result =
                new com.gagneflow.service.lesson.AddrfPipeline.AddrfResult();
        when(pipeline.getActiveResult("s1")).thenReturn(result);
        ReflectionTestUtils.setField(c, "addrfPipeline", pipeline);

        ResponseEntity<?> resp = c.lessonPlanScore(Map.of("sessionId", "s1", "score", "2", "feedback", "重难点不清"));
        assertEquals(200, resp.getStatusCodeValue());
        assertEquals(2, result.userScore);
    }
}