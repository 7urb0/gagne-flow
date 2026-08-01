package com.gagneflow.constant;

public class MilvusConstants {
    public static final String MILVUS_DB_NAME = "default";
    public static final String MILVUS_COLLECTION_NAME = "biz";
    public static final int VECTOR_DIM = 1024;
    public static final int ID_MAX_LENGTH = 256;
    public static final int CONTENT_MAX_LENGTH = 8192;
    public static final int DEFAULT_SHARD_NUMBER = 2;

    private MilvusConstants() {
    }
}
