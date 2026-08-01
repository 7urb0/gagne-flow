package com.gagneflow.controller;

import java.lang.reflect.Field;
import java.util.Map;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.param.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("MilvusCheckController 健康检查端点测试")
class MilvusCheckControllerTest {

    private MilvusCheckController controller;
    private MilvusServiceClient milvusClient;

    @BeforeEach
    void setUp() throws Exception {
        milvusClient = mock(MilvusServiceClient.class);
        controller = new MilvusCheckController();
        Field field = MilvusCheckController.class.getDeclaredField("milvusClient");
        field.setAccessible(true);
        field.set(controller, milvusClient);
    }

    @SuppressWarnings("unchecked")
    private R<ShowCollectionsResponse> mockR(int status, String message, String collections) {
        R<ShowCollectionsResponse> r = mock(R.class);
        when(r.getStatus()).thenReturn(status);
        when(r.getMessage()).thenReturn(message);
        if (collections != null) {
            ShowCollectionsResponse data = mock(ShowCollectionsResponse.class);
            com.google.protobuf.ProtocolStringList names =
                    mock(com.google.protobuf.ProtocolStringList.class);
            when(names.toString()).thenReturn("[" + collections + "]");
            when(data.getCollectionNamesList()).thenReturn(names);
            when(r.getData()).thenReturn(data);
        }
        return r;
    }

    @Nested
    @DisplayName("GET /milvus/health")
    class HealthTests {

        @Test
        @DisplayName("Milvus 正常 → 200 + collections")
        void healthy_shouldReturn200() {
            R<ShowCollectionsResponse> r = mockR(0, "ok", "biz");
            when(milvusClient.showCollections(any())).thenReturn(r);

            ResponseEntity<Map<String, Object>> res = controller.simpleHealth();

            assertEquals(HttpStatus.OK, res.getStatusCode());
            assertEquals("ok", res.getBody().get("message"));
            assertTrue(res.getBody().get("collections").toString().contains("biz"));
        }

        @Test
        @DisplayName("Milvus 状态非 0 → 503 + message")
        void unhealthyStatus_shouldReturn503() {
            R<ShowCollectionsResponse> r = mockR(1, "collection not loaded", null);
            when(milvusClient.showCollections(any())).thenReturn(r);

            ResponseEntity<Map<String, Object>> res = controller.simpleHealth();

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
            assertEquals("collection not loaded", res.getBody().get("message"));
        }

        @Test
        @DisplayName("Milvus 抛异常 → 503 + error")
        void exception_shouldReturn503() {
            when(milvusClient.showCollections(any()))
                    .thenThrow(new RuntimeException("connection refused"));

            ResponseEntity<Map<String, Object>> res = controller.simpleHealth();

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
            assertTrue(res.getBody().get("error").toString().contains("connection refused"));
        }
    }
}
