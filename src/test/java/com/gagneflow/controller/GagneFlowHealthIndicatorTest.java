package com.gagneflow.controller;

import java.lang.reflect.Field;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import com.gagneflow.service.vector.VectorEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("GagneFlowHealthIndicator 健康指标测试")
class GagneFlowHealthIndicatorTest {

    private GagneFlowHealthIndicator indicator;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private MilvusServiceClient milvusClient;
    private VectorEmbeddingService embeddingService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        milvusClient = mock(MilvusServiceClient.class);
        embeddingService = mock(VectorEmbeddingService.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        indicator = new GagneFlowHealthIndicator();
        injectField("redisTemplate", redisTemplate);
        injectField("milvusClient", milvusClient);
        injectField("embeddingService", embeddingService);
    }

    private void injectField(String name, Object value) throws Exception {
        Field field = GagneFlowHealthIndicator.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(indicator, value);
    }

    @Nested
    @DisplayName("全部依赖正常 → UP")
    class AllUpTests {

        @Test
        @DisplayName("redis + milvus + dashscope 均正常 → status UP")
        void allHealthy_shouldBeUp() {
            when(valueOps.get("health:ping")).thenReturn("pong");
            R r = mock(R.class);
            when(r.getStatus()).thenReturn(0);
            when(milvusClient.showCollections(any())).thenReturn(r);
            when(embeddingService.generateEmbedding("health-check"))
                    .thenReturn(java.util.List.of(0.1f));

            Health health = indicator.health();

            assertEquals(Status.UP, health.getStatus());
            assertEquals("UP", health.getDetails().get("redis"));
            assertEquals("UP", health.getDetails().get("milvus"));
            assertEquals("UP", health.getDetails().get("dashscope"));
        }
    }

    @Nested
    @DisplayName("依赖异常 → DOWN")
    class DownTests {

        @Test
        @DisplayName("redis 异常 → DOWN + redis=DOWN")
        void redisDown_shouldBeDown() {
            when(valueOps.get("health:ping")).thenThrow(new RuntimeException("refused"));
            R r = mock(R.class);
            when(r.getStatus()).thenReturn(0);
            when(milvusClient.showCollections(any())).thenReturn(r);
            when(embeddingService.generateEmbedding(anyString()))
                    .thenReturn(java.util.List.of(0.1f));

            Health health = indicator.health();

            assertEquals(Status.DOWN, health.getStatus());
            assertEquals("DOWN", health.getDetails().get("redis"));
        }

        @Test
        @DisplayName("milvus 异常 → DOWN + milvus 含错误信息")
        void milvusDown_shouldBeDown() {
            when(valueOps.get("health:ping")).thenReturn("pong");
            when(milvusClient.showCollections(any()))
                    .thenThrow(new RuntimeException("timeout"));
            when(embeddingService.generateEmbedding(anyString()))
                    .thenReturn(java.util.List.of(0.1f));

            Health health = indicator.health();

            assertEquals(Status.DOWN, health.getStatus());
            assertTrue(health.getDetails().get("milvus").toString().contains("DOWN"));
            assertTrue(health.getDetails().get("milvus").toString().contains("timeout"));
        }

        @Test
        @DisplayName("dashscope 异常 → DOWN + dashscope 含错误")
        void dashscopeDown_shouldBeDown() {
            when(valueOps.get("health:ping")).thenReturn("pong");
            R r = mock(R.class);
            when(r.getStatus()).thenReturn(0);
            when(milvusClient.showCollections(any())).thenReturn(r);
            when(embeddingService.generateEmbedding(anyString()))
                    .thenThrow(new RuntimeException("api key invalid"));

            Health health = indicator.health();

            assertEquals(Status.DOWN, health.getStatus());
            assertTrue(health.getDetails().get("dashscope").toString().contains("api key invalid"));
        }
    }

    @Nested
    @DisplayName("milvusClient 为 null → DISABLED")
    class MilvusDisabledTests {

        @Test
        @DisplayName("milvusClient null 时 milvus=DISABLED 且不视为 DOWN")
        void milvusNull_shouldBeDisabled() throws Exception {
            Field f = GagneFlowHealthIndicator.class.getDeclaredField("milvusClient");
            f.setAccessible(true);
            f.set(indicator, null);
            when(valueOps.get("health:ping")).thenReturn("pong");
            when(embeddingService.generateEmbedding(anyString()))
                    .thenReturn(java.util.List.of(0.1f));

            Health health = indicator.health();

            assertEquals(Status.UP, health.getStatus());
            assertEquals("DISABLED", health.getDetails().get("milvus"));
        }
    }
}
