package com.gagneflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix="milvus")
public class MilvusProperties {
    private String host = "localhost";
    private Integer port = 19530;
    private String username = "";
    private String password = "";
    private String database = "default";
    private Long timeout = 10000L;
    private String personalPlansCollection = "personal_plans";
    /** 反哺教案入库与检索统一的质量门槛(默认 85; 2026-08-27 收敛原回灌70/检索85魔法数漂移) */
    private Integer lessonPlanMinScore = 85;

    public String getAddress() {
        return this.host + ":" + this.port;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public void setPersonalPlansCollection(String personalPlansCollection) {
        this.personalPlansCollection = personalPlansCollection;
    }

    public void setLessonPlanMinScore(Integer lessonPlanMinScore) {
        this.lessonPlanMinScore = lessonPlanMinScore;
    }

    public void setTimeout(Long timeout) {
        this.timeout = timeout;
    }

    public String getHost() {
        return this.host;
    }

    public Integer getPort() {
        return this.port;
    }

    public String getUsername() {
        return this.username;
    }

    public String getPassword() {
        return this.password;
    }

    public String getDatabase() {
        return this.database;
    }

    public String getPersonalPlansCollection() {
        return this.personalPlansCollection;
    }

    public Integer getLessonPlanMinScore() {
        return this.lessonPlanMinScore;
    }

    public Long getTimeout() {
        return this.timeout;
    }
}
