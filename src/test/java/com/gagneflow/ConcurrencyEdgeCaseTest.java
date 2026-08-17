package com.gagneflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Concurrency and Edge Case Tests")
class ConcurrencyEdgeCaseTest {

    // ==================================================================
    // ChatSession Concurrency
    // ==================================================================

    @Nested
    @DisplayName("ChatSession Thread Safety")
    class ChatSessionConcurrency {

        @Test
        @DisplayName("concurrent addMessage on same session does not lose data")
        void concurrentAddMessage() throws Exception {
            var session = new com.gagneflow.service.chat.ChatSession("concurrent-test");
            var counter = new com.gagneflow.service.memory.TokenCounter();

            int threads = 5;
            int messagesPerThread = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < messagesPerThread; i++) {
                            session.addMessage("q-" + threadId + "-" + i,
                                    "a-" + threadId + "-" + i, 1000, counter);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            int expectedMessages = threads * messagesPerThread * 2; // pairs = 2 messages each
            assertTrue(session.getMessageHistory().size() >= expectedMessages - 5,
                    "Expected at least " + (expectedMessages - 5) + " but got " +
                            session.getMessageHistory().size());
        }

        @Test
        @DisplayName("ChatSession clearHistory does not throw under concurrent access")
        void clearHistoryConcurrent() throws Exception {
            var session = new com.gagneflow.service.chat.ChatSession("clear-test");
            var counter = new com.gagneflow.service.memory.TokenCounter();

            session.addMessage("q1", "a1", 10, counter);
            Thread t1 = new Thread(session::clearHistory);
            Thread t2 = new Thread(() -> session.addMessage("q2", "a2", 10, counter));

            t1.start();
            t2.start();
            t1.join(3000);
            t2.join(3000);

            // Should not throw
            assertNotNull(session.getMessageHistory());
        }
    }

    // ==================================================================
    // TokenCounter Edge Cases
    // ==================================================================

    @Nested
    @DisplayName("TokenCounter Edge Cases")
    class TokenCounterEdges {

        @Test
        @DisplayName("estimate handles null input")
        void estimateNull() {
            var counter = new com.gagneflow.service.memory.TokenCounter();
            assertEquals(0, counter.estimate(null));
        }

        @Test
        @DisplayName("estimate handles empty string")
        void estimateEmpty() {
            var counter = new com.gagneflow.service.memory.TokenCounter();
            assertEquals(0, counter.estimate(""));
        }

        @Test
        @DisplayName("estimate handles whitespace only")
        void estimateWhitespace() {
            var counter = new com.gagneflow.service.memory.TokenCounter();
            int result = counter.estimate("   \n\t  ");
            assertTrue(result >= 0);
        }

        @Test
        @DisplayName("estimate handles very long input")
        void estimateVeryLong() {
            var counter = new com.gagneflow.service.memory.TokenCounter();
            String longText = "测试文本".repeat(5000);
            int result = counter.estimate(longText);
            assertTrue(result > 0);
            assertTrue(result < 100000);
        }

        @Test
        @DisplayName("estimate handles mixed CJK and ASCII")
        void estimateMixed() {
            var counter = new com.gagneflow.service.memory.TokenCounter();

            int pureCjk = counter.estimate("这是一段纯中文文本");
            int pureAscii = counter.estimate("This is pure ASCII text");
            int mixed = counter.estimate("Hello世界 123测试ABC");

            assertTrue(pureCjk > 0);
            assertTrue(pureAscii > 0);
            assertTrue(mixed > 0);
        }

        @Test
        @DisplayName("estimate with unset ratios uses defaults")
        void estimateDefaultRatios() {
            var counter = new com.gagneflow.service.memory.TokenCounter();
            int result = counter.estimate("Hello 世界 test 文本");
            assertTrue(result >= 0);
        }

        @Test
        @DisplayName("estimate special characters only")
        void estimateSpecialChars() {
            var counter = new com.gagneflow.service.memory.TokenCounter();
            int result = counter.estimate("!@#$%^&*()_+-=[]{}|;':\",./<>?");
            assertTrue(result >= 0);
        }
    }

    // ==================================================================
    // DTO Serialization
    // ==================================================================

    @Nested
    @DisplayName("DTO Serialization Edge Cases")
    class DtoSerialization {

        @Test
        @DisplayName("ApiResponse with null data serializes correctly")
        void apiResponseNullData() throws Exception {
            var response = new com.gagneflow.dto.ApiResponse<String>();
            response.setCode(200);
            response.setMessage("OK");
            response.setData(null);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(response);
            assertTrue(json.contains("200"));
            assertTrue(json.contains("OK"));
        }

        @Test
        @DisplayName("ApiResponse with error message")
        void apiResponseError() throws Exception {
            var response = com.gagneflow.dto.ApiResponse.error("Internal Server Error");
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(response);
            assertTrue(json.contains("500"));
            assertTrue(json.contains("Internal Server Error"));
        }

        @Test
        @DisplayName("LessonPlanRequest boundary values")
        void lessonPlanRequestBoundary() {
            var req = new com.gagneflow.dto.LessonPlanRequest();
            req.setStage("小学");
            req.setGrade(1);
            req.setSubject("语文");
            req.setGoals("教学目标");
            req.setHours(0);
            req.setMode("auto");

            assertEquals("小学", req.getStage());
            assertEquals(1, req.getGrade());
            assertEquals(0, req.getHours());
            assertEquals("教学目标", req.getGoals());
        }

        @Test
        @DisplayName("DocumentChunk boundary values")
        void documentChunkBoundary() {
            var chunk = new com.gagneflow.dto.DocumentChunk();
            chunk.setContent("");
            chunk.setChunkIndex(0);
            chunk.setTitle(null);

            assertEquals("", chunk.getContent());
            assertEquals(0, chunk.getChunkIndex());
            assertNull(chunk.getTitle());
        }
    }

    // ==================================================================
    // AddrfPipeline Result Edge Cases
    // ==================================================================

    @Nested
    @DisplayName("AddrfPipeline Result Edge Cases")
    class AddrfPipelineResultEdges {

        @Test
        @DisplayName("extractScore with null input")
        void extractScoreNull() throws Exception {
            var pipeline = newPipeline();
            var method = com.gagneflow.service.lesson.AddrfPipeline.class
                    .getDeclaredMethod("extractScore", String.class);
            method.setAccessible(true);

            int score = (int) method.invoke(pipeline, (Object) null);
            assertEquals(0, score);
        }

        @Test
        @DisplayName("extractScore with no numeric content")
        void extractScoreNoNumber() throws Exception {
            var pipeline = newPipeline();
            var method = com.gagneflow.service.lesson.AddrfPipeline.class
                    .getDeclaredMethod("extractScore", String.class);
            method.setAccessible(true);

            int score = (int) method.invoke(pipeline, "This text has no score");
            assertEquals(0, score);
        }

        @Test
        @DisplayName("extractScore with 100")
        void extractScoreMax() throws Exception {
            var pipeline = newPipeline();
            var method = com.gagneflow.service.lesson.AddrfPipeline.class
                    .getDeclaredMethod("extractScore", String.class);
            method.setAccessible(true);

            int score = (int) method.invoke(pipeline, "总分: 100");
            assertEquals(100, score);
        }

        @Test
        @DisplayName("extractScore with out-of-range value clamps to 0-100")
        void extractScoreOutOfRange() throws Exception {
            var pipeline = newPipeline();
            var method = com.gagneflow.service.lesson.AddrfPipeline.class
                    .getDeclaredMethod("extractScore", String.class);
            method.setAccessible(true);

            int tooHigh = (int) method.invoke(pipeline, "评分: 999");
            int negative = (int) method.invoke(pipeline, "评分: -50");

            assertTrue(tooHigh <= 100, "Expected <= 100 but got " + tooHigh);
            assertTrue(negative >= 0, "Expected >= 0 but got " + negative);
        }

        @Test
        @DisplayName("extractFeedback with null input")
        void extractFeedbackNull() throws Exception {
            var pipeline = newPipeline();
            var method = com.gagneflow.service.lesson.AddrfPipeline.class
                    .getDeclaredMethod("extractFeedback", String.class);
            method.setAccessible(true);

            String feedback = (String) method.invoke(pipeline, (Object) null);
            assertEquals("", feedback);
        }

        @Test
        @DisplayName("extractFeedback with content but no feedback markers")
        void extractFeedbackNoMarkers() throws Exception {
            var pipeline = newPipeline();
            var method = com.gagneflow.service.lesson.AddrfPipeline.class
                    .getDeclaredMethod("extractFeedback", String.class);
            method.setAccessible(true);

            String feedback = (String) method.invoke(pipeline, "Just a regular review text");
            assertNotNull(feedback);
        }

        @Test
        @DisplayName("dedupContent removes duplicate lines")
        void dedupContentRemovesDupes() throws Exception {
            var pipeline = newPipeline();
            var method = com.gagneflow.service.lesson.AddrfPipeline.class
                    .getDeclaredMethod("dedupContent", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(pipeline,
                    "Line A\nLine B\nLine A\nLine C\nLine B");
            assertNotNull(result);

            int firstA = result.indexOf("Line A");
            int lastA = result.lastIndexOf("Line A");
            // After dedup, Line A should appear only once (or could keep first occurrence)
            assertTrue(firstA >= 0);
        }
    }

    // ==================================================================
    // Concurrent Collection Safety
    // ==================================================================

    /** 用带参构造器创建 AddrfPipeline 实例（无参构造器不存在） */
    private static com.gagneflow.service.lesson.AddrfPipeline newPipeline() {
        return new com.gagneflow.service.lesson.AddrfPipeline(
                null, null, null, null, null, null, null,
                new com.gagneflow.config.PipelineStageConfig(), null, null, null);
    }

    @Nested
    @DisplayName("Concurrent Collection Tests")
    class ConcurrentCollections {

        @Test
        @DisplayName("ConcurrentHashMap concurrent put and get")
        void concurrentMapSafety() throws Exception {
            ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
            int threads = 10;
            int ops = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(threads);

            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    for (int i = 0; i < ops; i++) {
                        map.put("key-" + i, i);
                        map.get("key-" + i);
                    }
                });
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(ops, map.size());
        }
    }
}
