package com.gagneflow.config;

import com.alibaba.dashscope.utils.Constants;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DashScopeSdkInitializer {
    private static final Logger logger = LoggerFactory.getLogger(DashScopeSdkInitializer.class);
    @Value(value="${DASHSCOPE_API_KEY}")
    private String apiKey;
    @Value(value="${dashscope.base-url:https://dashscope.aliyuncs.com/api/v1}")
    private String baseHttpApiUrl;

    @PostConstruct
    public void initSdk() {
        if (this.apiKey == null || this.apiKey.isEmpty() || "your-api-key-here".equals(this.apiKey)) {
            logger.error("DashScope API Key \u672a\u6b63\u786e\u914d\u7f6e\uff0c\u8bf7\u68c0\u67e5 DASHSCOPE_API_KEY \u73af\u5883\u53d8\u91cf");
            return;
        }
        Constants.apiKey = this.apiKey;
        Constants.baseHttpApiUrl = this.baseHttpApiUrl;
        logger.info("DashScope SDK \u521d\u59cb\u5316\u5b8c\u6210 | baseUrl: {} | apiKey: {}...{}", new Object[]{this.baseHttpApiUrl, this.apiKey.substring(0, Math.min(8, this.apiKey.length())), this.apiKey.substring(Math.max(0, this.apiKey.length() - 4))});
    }
}
