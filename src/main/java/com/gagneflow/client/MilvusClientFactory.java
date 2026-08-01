package com.gagneflow.client;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import java.util.concurrent.TimeUnit;
import com.gagneflow.config.MilvusProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MilvusClientFactory {
    private static final Logger logger = LoggerFactory.getLogger(MilvusClientFactory.class);
    @Autowired
    private MilvusProperties milvusProperties;

    public MilvusServiceClient createClient() {
        MilvusServiceClient client = null;
        try {
            logger.info("\u6b63\u5728\u8fde\u63a5\u5230 Milvus: {}:{}", (Object)this.milvusProperties.getHost(), (Object)this.milvusProperties.getPort());
            client = this.connectToMilvus();
            logger.info("\u6210\u529f\u8fde\u63a5\u5230 Milvus");
            if (!this.collectionExists(client, "biz")) {
                logger.info("collection '{}' \u4e0d\u5b58\u5728\uff0c\u6b63\u5728\u521b\u5efa...", (Object)"biz");
                this.createBizCollection(client);
                logger.info("\u6210\u529f\u521b\u5efa collection '{}'", (Object)"biz");
                this.createIndexes(client);
                logger.info("\u6210\u529f\u521b\u5efa\u7d22\u5f15");
            } else {
                logger.info("collection '{}' \u5df2\u5b58\u5728", (Object)"biz");
            }
            return client;
        }
        catch (Exception e) {
            logger.error("\u521b\u5efa Milvus \u5ba2\u6237\u7aef\u5931\u8d25", (Throwable)e);
            if (client != null) {
                client.close();
            }
            throw new RuntimeException("\u521b\u5efa Milvus \u5ba2\u6237\u7aef\u5931\u8d25: " + e.getMessage(), e);
        }
    }

    private MilvusServiceClient connectToMilvus() {
        ConnectParam.Builder builder = ConnectParam.newBuilder().withHost(this.milvusProperties.getHost()).withPort(this.milvusProperties.getPort().intValue()).withConnectTimeout(this.milvusProperties.getTimeout().longValue(), TimeUnit.MILLISECONDS);
        if (this.milvusProperties.getUsername() != null && !this.milvusProperties.getUsername().isEmpty()) {
            builder.withAuthorization(this.milvusProperties.getUsername(), this.milvusProperties.getPassword());
        }
        return new MilvusServiceClient(builder.build());
    }

    private boolean collectionExists(MilvusServiceClient client, String collectionName) {
        R response = client.hasCollection(HasCollectionParam.newBuilder().withCollectionName(collectionName).build());
        if (response.getStatus() != 0) {
            throw new RuntimeException("\u68c0\u67e5 collection \u5931\u8d25: " + response.getMessage());
        }
        return (Boolean)response.getData();
    }

    private void createBizCollection(MilvusServiceClient client) {
        FieldType idField = FieldType.newBuilder().withName("id").withDataType(DataType.VarChar).withMaxLength(Integer.valueOf(256)).withPrimaryKey(true).build();
        FieldType vectorField = FieldType.newBuilder().withName("vector").withDataType(DataType.FloatVector).withDimension(Integer.valueOf(1024)).build();
        FieldType contentField = FieldType.newBuilder().withName("content").withDataType(DataType.VarChar).withMaxLength(Integer.valueOf(8192)).build();
        FieldType metadataField = FieldType.newBuilder().withName("metadata").withDataType(DataType.JSON).build();
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder().withEnableDynamicField(false).addFieldType(idField).addFieldType(vectorField).addFieldType(contentField).addFieldType(metadataField).build();
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder().withCollectionName("biz").withDescription("Business knowledge collection").withSchema(schema).withShardsNum(2).build();
        R response = client.createCollection(createParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("\u521b\u5efa collection \u5931\u8d25: " + response.getMessage());
        }
    }

    private void createIndexes(MilvusServiceClient client) {
        // P0修复: 统一使用 L2 (欧氏距离) 建索引，与搜索端一致
        CreateIndexParam vectorIndexParam = CreateIndexParam.newBuilder().withCollectionName("biz").withFieldName("vector").withIndexType(IndexType.IVF_FLAT).withMetricType(MetricType.L2).withExtraParam("{\"nlist\":128}").withSyncMode(Boolean.FALSE).build();
        R response = client.createIndex(vectorIndexParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("\u521b\u5efa vector \u7d22\u5f15\u5931\u8d25: " + response.getMessage());
        }
        logger.info("\u6210\u529f\u4e3a vector \u5b57\u6bb5\u521b\u5efa\u7d22\u5f15 (MetricType=L2)");
    }
}
