package com.gagneflow.service.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TokenCounter Token 估算测试")
class TokenCounterTest {

    private TokenCounter tokenCounter;

    @BeforeEach
    void setUp() {
        tokenCounter = new TokenCounter();
        ReflectionTestUtils.setField(tokenCounter, "calibrateEnabled", false);
        ReflectionTestUtils.setField(tokenCounter, "chineseRatio", 1.5);
        ReflectionTestUtils.setField(tokenCounter, "asciiRatio", 1.3);
        ReflectionTestUtils.setField(tokenCounter, "baseRatio", 1.0);
    }

    @Nested
    @DisplayName("estimate 基本估算")
    class EstimateBasicTests {

        @Test
        @DisplayName("null 文本返回 0")
        void nullText_shouldReturnZero() {
            assertEquals(0, tokenCounter.estimate(null));
        }

        @Test
        @DisplayName("空字符串返回 0")
        void emptyText_shouldReturnZero() {
            assertEquals(0, tokenCounter.estimate(""));
        }

        @Test
        @DisplayName("纯中文文本按 1.5 倍率估算")
        void pureChinese_shouldUseChineseRatio() {
            String text = "教学目标设计是教育过程中的核心环节";
            int tokens = tokenCounter.estimate(text);
            // 17个中文字符 × 1.5 = 25.5 → ceil = 26
            assertTrue(tokens >= 20, "纯中文估算应 >= 20 tokens");
            assertTrue(tokens <= 35, "纯中文估算应 <= 35 tokens");
        }

        @Test
        @DisplayName("纯英文文本按 1.3 倍率估算")
        void pureAscii_shouldUseAsciiRatio() {
            String text = "The quick brown fox jumps over the lazy dog";
            int tokens = tokenCounter.estimate(text);
            // 43个ASCII × 1.3 = 55.9 → ceil = 56
            assertTrue(tokens >= 40 && tokens <= 70);
        }

        @Test
        @DisplayName("混合中英文正确分类计数")
        void mixedText_shouldClassifyCorrectly() {
            String text = "数学Mathematics教学Design";
            int tokens = tokenCounter.estimate(text);
            assertTrue(tokens > 0, "混合文本应产生非零估算");
        }

        @Test
        @DisplayName("纯数字文本不触发汉字检测")
        void numericOnly_shouldNotCountAsChinese() {
            String text = "12345";
            int tokens = tokenCounter.estimate(text);
            assertTrue(tokens <= 10, "纯数字不应被误算为中文");
        }
    }

    @Nested
    @DisplayName("shouldTrim 阈值判断")
    class ShouldTrimTests {

        @Test
        @DisplayName("超过安全边界返回 true")
        void exceedsMargin_shouldReturnTrue() {
            // 安全边界 = 0.8, limit=100 → 阈值=80
            assertTrue(tokenCounter.shouldTrim(81, 100));
        }

        @Test
        @DisplayName("刚好在边界内返回 false")
        void withinMargin_shouldReturnFalse() {
            assertFalse(tokenCounter.shouldTrim(80, 100));
        }

        @Test
        @DisplayName("远低于边界返回 false")
        void farBelow_shouldReturnFalse() {
            assertFalse(tokenCounter.shouldTrim(10, 100));
        }

        @Test
        @DisplayName("零 token 返回 false")
        void zeroTokens_shouldReturnFalse() {
            assertFalse(tokenCounter.shouldTrim(0, 100));
        }
    }
}
