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
            // 2026-08-19: 双 collection 初始化 - 公共知识库(biz) + 个人教案库(personal_plans)
            this.ensureCollection(client, com.gagneflow.constant.MilvusConstants.MILVUS_COLLECTION_NAME, "Business knowledge collection");
            this.ensureCollection(client, com.gagneflow.constant.MilvusConstants.PERSONAL_PLANS_COLLECTION, "Personal lesson plans collection");
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

    private void createCollection(MilvusServiceClient client, String collectionName, String description) {
        FieldType idField = FieldType.newBuilder().withName("id").withDataType(DataType.VarChar).withMaxLength(Integer.valueOf(256)).withPrimaryKey(true).build();
        FieldType vectorField = FieldType.newBuilder().withName("vector").withDataType(DataType.FloatVector).withDimension(Integer.valueOf(1024)).build();
        FieldType contentField = FieldType.newBuilder().withName("content").withDataType(DataType.VarChar).withMaxLength(Integer.valueOf(8192)).build();
        FieldType metadataField = FieldType.newBuilder().withName("metadata").withDataType(DataType.JSON).build();
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder().withEnableDynamicField(false).addFieldType(idField).addFieldType(vectorField).addFieldType(contentField).addFieldType(metadataField).build();
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder().withCollectionName(collectionName).withDescription(description).withSchema(schema).withShardsNum(2).build();
        R response = client.createCollection(createParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("\u521b\u5efa collection \u5931\u8d25: " + response.getMessage());
        }
    }

    /** 2026-08-19: 确保 collection 存在(不存在则创建+建索引) */
    private void ensureCollection(MilvusServiceClient client, String collectionName, String description) {
        try {
            if (!this.collectionExists(client, collectionName)) {
                logger.info("collection '{}' \u4e0d\u5b58\u5728\uff0c\u6b63\u5728\u521b\u5efa...", (Object)collectionName);
                this.createCollection(client, collectionName, description);
                this.createIndexes(client, collectionName);
                logger.info("\u6210\u529f\u521b\u5efa collection '{}' \u53ca\u7d22\u5f15", (Object)collectionName);
            } else {
                logger.info("collection '{}' \u5df2\u5b58\u5728", (Object)collectionName);
            }
        } catch (Exception e) {
            logger.warn("collection '{}' \u521d\u59cb\u5316\u5931\u8d25(\u5f71\u54cd\u8be5\u5e93\u529f\u80fd, \u4e0d\u963b\u585e\u5176\u4ed6): {}", collectionName, e.getMessage());
        }
    }

    private void createIndexes(MilvusServiceClient client, String collectionName) {
        // P0修复: 统一使用 L2 (欧氏距离) 建索引，与搜索端一致
        CreateIndexParam vectorIndexParam = CreateIndexParam.newBuilder().withCollectionName(collectionName).withFieldName("vector").withIndexType(IndexType.IVF_FLAT).withMetricType(MetricType.L2).withExtraParam("{\"nlist\":128}").withSyncMode(Boolean.FALSE).build();
        R response = client.createIndex(vectorIndexParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("\u521b\u5efa vector \u7d22\u5f15\u5931\u8d25: " + response.getMessage());
        }
        logger.info("\u6210\u529f\u4e3a {} vector \u5b57\u6bb5\u521b\u5efa\u7d22\u5f15 (MetricType=L2)", (Object)collectionName);
    }
}
