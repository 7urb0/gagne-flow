package com.gagneflow.client;

import com.gagneflow.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("MilvusClientFactory unit tests")
class MilvusClientFactoryTest {

    private MilvusProperties mockProps() {
        MilvusProperties p = mock(MilvusProperties.class);
        when(p.getHost()).thenReturn("127.0.0.1");
        when(p.getPort()).thenReturn(19530);
        when(p.getTimeout()).thenReturn(200L);
        when(p.getUsername()).thenReturn("");
        return p;
    }

    private MilvusClientFactory newFactory() {
        MilvusClientFactory f = new MilvusClientFactory();
        ReflectionTestUtils.setField(f, "milvusProperties", mockProps());
        return f;
    }

    @Test
    void createClient_milvusUnavailable_throwsRuntimeException() {
        MilvusClientFactory f = newFactory();
        assertThrows(RuntimeException.class, f::createClient,
                "Milvus 不可达时应抛 RuntimeException 且不泄漏裸异常");
    }

    @Test
    void collectionExists_statusOk_dataTrue_returnsTrue() throws Exception {
        MilvusClientFactory f = newFactory();
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        R<Boolean> r = mock(R.class);
        when(r.getStatus()).thenReturn(0);
        when(r.getData()).thenReturn(true);
        when(client.hasCollection(any(HasCollectionParam.class))).thenReturn(r);
        boolean exists = (Boolean) ReflectionTestUtils.invokeMethod(f, "collectionExists", client, "biz");
        assertTrue(exists);
    }

    @Test
    void collectionExists_statusOk_dataFalse_returnsFalse() throws Exception {
        MilvusClientFactory f = newFactory();
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        R<Boolean> r = mock(R.class);
        when(r.getStatus()).thenReturn(0);
        when(r.getData()).thenReturn(false);
        when(client.hasCollection(any(HasCollectionParam.class))).thenReturn(r);
        boolean exists = (Boolean) ReflectionTestUtils.invokeMethod(f, "collectionExists", client, "biz");
        assertFalse(exists);
    }

    @Test
    void collectionExists_statusError_throws() throws Exception {
        MilvusClientFactory f = newFactory();
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        R<Boolean> r = mock(R.class);
        when(r.getStatus()).thenReturn(1);
        when(r.getMessage()).thenReturn("has collection failed");
        when(client.hasCollection(any(HasCollectionParam.class))).thenReturn(r);
        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(f, "collectionExists", client, "biz"));
    }

    @Test
    void createCollection_success_noThrow() throws Exception {
        MilvusClientFactory f = newFactory();
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        R r = mock(R.class);
        when(r.getStatus()).thenReturn(0);
        when(client.createCollection(any(CreateCollectionParam.class))).thenReturn(r);
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(f, "createCollection", client, "biz", "desc"));
    }

    @Test
    void createCollection_failure_throws() throws Exception {
        MilvusClientFactory f = newFactory();
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        R r = mock(R.class);
        when(r.getStatus()).thenReturn(1);
        when(client.createCollection(any(CreateCollectionParam.class))).thenReturn(r);
        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(f, "createCollection", client, "biz", "desc"));
    }

    @Test
    void createIndexes_success_noThrow() throws Exception {
        MilvusClientFactory f = newFactory();
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        R r = mock(R.class);
        when(r.getStatus()).thenReturn(0);
        when(client.createIndex(any(CreateIndexParam.class))).thenReturn(r);
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(f, "createIndexes", client, "biz"));
    }

    @Test
    void createIndexes_failure_throws() throws Exception {
        MilvusClientFactory f = newFactory();
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        R r = mock(R.class);
        when(r.getStatus()).thenReturn(1);
        when(client.createIndex(any(CreateIndexParam.class))).thenReturn(r);
        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(f, "createIndexes", client, "biz"));
    }
}