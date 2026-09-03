package com.gagneflow.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class DashScopeConfig {
    @Value(value="${spring.ai.dashscope.chat.options.timeout:180000}")
    private long timeout;

    @Value(value="${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Bean
    public RestClient.Builder restClientBuilder() {
        // 使用 Java 11 HttpClient 连接池，复用 HTTP 连接
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeout))
                .build();
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient));
    }

    /**
     * 修复BUG 3: DashScopeApi 单例，复用 HTTP 连接池，避免每次请求新建导致泄漏。
     * ChatController、LessonController、ChatService 均可注入此单例。
     */
    @Bean
    public DashScopeApi dashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(apiKey)
                .build();
    }
}
