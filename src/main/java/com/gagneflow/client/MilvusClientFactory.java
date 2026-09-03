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
            // 2026-08-19: 双 collection 初始化 - 公共知识库(biz) + 个人教案库(personal_plans, 可配)
            this.ensureCollection(client, com.gagneflow.constant.MilvusConstants.MILVUS_COLLECTION_NAME, "Business knowledge collection");
            this.ensureCollection(client, this.milvusProperties.getPersonalPlansCollection(), "Personal lesson plans collection");
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
        // 2026-08-23 调研: 本 SDK(milvus-sdk-java 2.6.10) 的 ConnectParam.Builder 无 withDatabase()(已实测编译报错),
        // Milvus 2.5 多命名空间需单独 useDatabase API 切换, ConnectParam 级不生效。milvus.database 配置暂仅文档说明,
        // 不引入编译错误。
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
                // 2026-08-21: 对已存在的 collection 幂等补建索引(升级场景), 索引已存在时 createIndex 报错则忽略
                try {
                    this.createIndexes(client, collectionName);
                } catch (Exception e) {
                    logger.info("collection '{}' \u7d22\u5f15\u5df2\u5b58\u5728\u6216\u521b\u5efa\u5931\u8d25(\u5ffd\u7565): {}", (Object)collectionName, e.getMessage());
                }
            }
        } catch (Exception e) {
            // P3-4: 原实现吞掉初始化异常, 集合缺失会延迟到运行时检索才崩(且走熔断降级成"没找到")。
            // 启动阶段即 fail-fast, 避免"启动成功却在检索时静默无结果"。内层幂等建索引的 catch 仍保留。
            throw new RuntimeException("collection '" + collectionName + "' \u521d\u59cb\u5316\u5931\u8d25: " + e.getMessage(), e);
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

        // 2026-08-21: metadata JSON 字段补建 INVERTED 标量索引, 加速 _user_id/_source 等字符串过滤表达式, 避免全量扫描
        // 注意: Milvus 2.5 对 JSON 字段建 inverted index 必须指定 json_cast_type(varchar),
        // 否则报 missing parameter[json_cast_type]; 数值比较过滤(_score>=85)不受影响, 只是无索引加速
        CreateIndexParam metadataIndexParam = CreateIndexParam.newBuilder().withCollectionName(collectionName).withFieldName("metadata").withIndexType(IndexType.INVERTED).withExtraParam("{\"json_cast_type\":\"varchar\"}").withSyncMode(Boolean.FALSE).build();
        R metaResponse = client.createIndex(metadataIndexParam);
        if (metaResponse.getStatus() != 0) {
            throw new RuntimeException("\u521b\u5efa metadata \u7d22\u5f15\u5931\u8d25: " + metaResponse.getMessage());
        }
        logger.info("\u6210\u529f\u4e3a {} metadata \u5b57\u6bb5\u521b\u5efa\u7d22\u5f15 (IndexType=INVERTED)", (Object)collectionName);

        // 2026-08-23 方向①: personal_plans 对 metadata["_score"] 追加 JSON-path 数值索引(仅个人教案库需要)。
        // Milvus 2.5 支持每个 JSON path 各一个索引, 与上面整个 metadata 的 varchar 索引并存(不同 path)。
        // _score 已存数值(int), json_cast_type=double 与之匹配, 使 _score>=85 过滤走索引而非全表扫。
        // biz 库无 _score(公共文档/课标), 不为它建以免索引引用了不存在的 key(path 缺失的行会被跳过, 无错误)。
        if (com.gagneflow.constant.MilvusConstants.PERSONAL_PLANS_COLLECTION.equals(collectionName)
                || (this.milvusProperties != null
                    && collectionName.equals(this.milvusProperties.getPersonalPlansCollection()))) {
            CreateIndexParam scoreIndexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName).withFieldName("metadata")
                    .withIndexType(IndexType.INVERTED)
                    .withExtraParam("{\"json_path\":\"metadata[\\\"_score\\\"]\",\"json_cast_type\":\"double\"}")
                    .withSyncMode(Boolean.FALSE).build();
            R scoreResp = client.createIndex(scoreIndexParam);
            if (scoreResp.getStatus() != 0) {
                throw new RuntimeException("\u521b\u5efa metadata[_score] \u7d22\u5f15\u5931\u8d25: " + scoreResp.getMessage());
            }
            logger.info("\u6210\u529f\u4e3a {} metadata[_score] \u521b\u5efa\u7d22\u5f15 (IndexType=INVERTED, json_cast_type=double)", (Object)collectionName);
        }
    }
}
