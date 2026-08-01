package com.gagneflow.service.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LongTermMemoryService 纯逻辑测试。
 * 该类大部分方法依赖 Redis（private），仅测试可公开访问的 POJO 和构造器。
 */
@DisplayName("LongTermMemoryService 纯逻辑测试")
class LongTermMemoryServiceTest {

    @Test
    @DisplayName("MemoryFact 字段设置和读取")
    void memoryFact_fields_work() {
        LongTermMemoryService.MemoryFact fact = new LongTermMemoryService.MemoryFact();
        fact.setFact("学生需要掌握分数概念");

        assertEquals("学生需要掌握分数概念", fact.getFact());
    }

    @Test
    @DisplayName("MemoryFact null 字段不抛异常")
    void memoryFact_nullField_doesNotThrow() {
        LongTermMemoryService.MemoryFact fact = new LongTermMemoryService.MemoryFact();
        assertDoesNotThrow(() -> fact.setFact(null));
        assertNull(fact.getFact());
    }

    @Test
    @DisplayName("MemoryFact 空字符串可设置")
    void memoryFact_emptyString() {
        LongTermMemoryService.MemoryFact fact = new LongTermMemoryService.MemoryFact();
        fact.setFact("");
        assertEquals("", fact.getFact());
    }

    @Test
    @DisplayName("MemoryFact 长文本存储")
    void memoryFact_longText() {
        String longText = "a".repeat(10000);
        LongTermMemoryService.MemoryFact fact = new LongTermMemoryService.MemoryFact();
        fact.setFact(longText);
        assertEquals(longText, fact.getFact());
    }
}
