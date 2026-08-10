package com.gagneflow.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DateTimeTools 时间工具测试。
 * 覆盖: 返回格式有效性、时区处理。
 */
@DisplayName("DateTimeTools 时间工具测试")
class DateTimeToolsTest {

    private final DateTimeTools tools = new DateTimeTools();

    @Test
    @DisplayName("getCurrentDateTime 返回非空字符串")
    void returnsNonEmptyString() {
        String result = tools.getCurrentDateTime();
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("getCurrentDateTime 返回可解析的 ISO-8601 时间戳")
    void returnsParseableIsoTimestamp() {
        String result = tools.getCurrentDateTime();
        // 格式形如 2026-08-07T15:30:00+08:00[Asia/Shanghai]
        // 验证包含日期时间基础结构
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"),
                "应返回 ISO-8601 格式时间戳，实际: " + result);
        assertDoesNotThrow(() -> java.time.ZonedDateTime.parse(result));
    }

    @Test
    @DisplayName("getCurrentDateTime 返回值包含时区偏移或 Z 标记")
    void containsTimezoneInfo() {
        String result = tools.getCurrentDateTime();
        assertTrue(result.contains("+") || result.contains("Z") || result.contains("["),
                "应包含时区信息，实际: " + result);
    }

    @Test
    @DisplayName("返回值不是垃圾字符")
    void notGarbage() {
        String result = tools.getCurrentDateTime();
        assertThrows(DateTimeParseException.class, () ->
                java.time.ZonedDateTime.parse("not-a-date"));
        // 反向确认: 我们的输出必须能解析为 ZonedDateTime
        assertDoesNotThrow(() -> java.time.ZonedDateTime.parse(result));
    }

    @Test
    @DisplayName("工具常量定义正确")
    void toolConstantDefined() {
        assertEquals("getCurrentDateTime", DateTimeTools.TOOL_GET_CURRENT_DATETIME);
    }
}
