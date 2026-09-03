package com.gagneflow.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LessonPlanRequest 可选章节解析测试（2026-09-02 教案结构改造）。
 * 语义: null = 默认集(未提交, 兼容旧客户端); 空数组 = 仅骨架(用户显式取消全部);
 * 非空 = 白名单过滤 + 去重。
 */
class LessonPlanRequestTest {

    @Test
    void nullOptionalSections_returnsDefaults() {
        LessonPlanRequest r = new LessonPlanRequest();
        List<String> got = r.resolveOptionalSections();
        assertEquals(LessonPlanRequest.OPTIONAL_SECTION_DEFAULTS, got, "未提交时应返回默认集");
    }

    @Test
    void emptyArray_returnsSkeletonOnly() {
        LessonPlanRequest r = new LessonPlanRequest();
        r.setOptionalSections(List.of());
        assertTrue(r.resolveOptionalSections().isEmpty(), "空数组应表示仅骨架(不含附加模块)");
    }

    @Test
    void whitelistFiltersUnknownAndDedups() {
        LessonPlanRequest r = new LessonPlanRequest();
        r.setOptionalSections(List.of("板书设计", "不存在的模块", "板书设计", "作业设计"));
        List<String> got = r.resolveOptionalSections();
        assertEquals(List.of("板书设计", "作业设计"), got, "白名单外应静默过滤、重复应去重");
    }

    @Test
    void allWhitelistPassesThrough() {
        LessonPlanRequest r = new LessonPlanRequest();
        r.setOptionalSections(LessonPlanRequest.OPTIONAL_SECTION_WHITELIST);
        assertEquals(LessonPlanRequest.OPTIONAL_SECTION_WHITELIST, r.resolveOptionalSections());
    }
}
