package com.gagneflow.service.memory;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TokenCounter {
    private static final Logger logger = LoggerFactory.getLogger(TokenCounter.class);
    @Value(value="${spring.ai.dashscope.api-key:}")
    private String apiKey;
    @Value(value="${gagneflow.token-counter.calibrate:true}")
    private boolean calibrateEnabled;
    private double chineseRatio = 1.5;
    private double asciiRatio = 1.3;
    private double baseRatio = 1.0;
    private static final double SAFETY_MARGIN = 0.8;

    @PostConstruct
    public void init() {
        if (this.calibrateEnabled && this.apiKey != null && !this.apiKey.isBlank()) {
            this.tryCalibrate();
        } else {
            logger.info("TokenCounter \u4f7f\u7528\u9ed8\u8ba4\u500d\u7387 (\u4e2d:{}, ASCII:{})", (Object)this.chineseRatio, (Object)this.asciiRatio);
        }
    }

    private void tryCalibrate() {
        try {
            String chineseSample = "\u6559\u5b66\u8bbe\u8ba1\u662f\u6559\u80b2\u8fc7\u7a0b\u4e2d\u7684\u6838\u5fc3\u73af\u8282\u5b83\u51b3\u5b9a\u4e86\u6559\u5b66\u6d3b\u52a8\u7684\u65b9\u5411\u548c\u6548\u679c";
            String asciiSample = "The quick brown fox jumps over the lazy dog repeatedly";
            int chTokens = this.countViaApi(chineseSample);
            int enTokens = this.countViaApi(asciiSample);
            if (chTokens > 0 && enTokens > 0) {
                double newChinese = (double)chTokens / (double)chineseSample.length();
                double newAscii = (double)enTokens / (double)asciiSample.length();
                if (newChinese > 0.5 && newChinese < 3.0) {
                    this.chineseRatio = newChinese;
                }
                if (newAscii > 0.5 && newAscii < 3.0) {
                    this.asciiRatio = newAscii;
                }
                logger.info("TokenCounter \u6821\u51c6\u5b8c\u6210: \u4e2d={:.3f}, ASCII={:.3f}", (Object)this.chineseRatio, (Object)this.asciiRatio);
            }
        }
        catch (Exception e) {
            logger.warn("TokenCounter \u6821\u51c6\u5931\u8d25\uff0c\u4f7f\u7528\u9ed8\u8ba4\u500d\u7387: {}", (Object)e.getMessage());
        }
    }

    private int countViaApi(String text) {
        try {
            Map usage;
            Object total;
            RestClient client = RestClient.create();
            Map resp = (Map)((RestClient.RequestBodySpec)((RestClient.RequestBodySpec)client.post().uri("https://dashscope.aliyuncs.com/compatible-mode/v1/tokenizer", new Object[0])).header("Authorization", new String[]{"Bearer " + this.apiKey})).contentType(MediaType.APPLICATION_JSON).body(Map.of("model", "qwen-turbo", "input", Map.of("messages", List.of(Map.of("role", "user", "content", text))))).retrieve().body(Map.class);
            if (resp != null && resp.get("usage") instanceof Map && (total = (usage = (Map)resp.get("usage")).get("total_tokens")) instanceof Number) {
                return ((Number)total).intValue();
            }
        }
        catch (Exception e) {
            logger.warn("TokenCounter API call failed, using character-based estimation: {}", e.getMessage());
        }
        return -1;
    }

    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int chineseChars = 0;
        int asciiChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                ++chineseChars;
                continue;
            }
            if (c >= '\u0080') continue;
            ++asciiChars;
        }
        int total = (int)Math.ceil((double)chineseChars * this.chineseRatio + (double)asciiChars * this.asciiRatio + (double)(text.length() - chineseChars - asciiChars) * this.baseRatio);
        logger.trace("Token\u4f30\u7b97: {}\u5b57\u7b26\u2192{}tokens (\u4e2d:{}, ASCII:{})", new Object[]{text.length(), total, chineseChars, asciiChars});
        return total;
    }

    public boolean shouldTrim(int estimatedTokens, int tokenLimit) {
        return estimatedTokens > (int)((double)tokenLimit * SAFETY_MARGIN);
    }
}
