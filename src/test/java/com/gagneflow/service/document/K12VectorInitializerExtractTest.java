package com.gagneflow.service.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("K12VectorInitializer extractTexts 提取正文验证")
class K12VectorInitializerExtractTest {

    @Test
    void extractTexts_producesContentChunks() throws Exception {
        K12VectorInitializer init = new K12VectorInitializer();
        Method m = K12VectorInitializer.class.getDeclaredMethod(
                "extractTexts", JsonNode.class, String.class, List.class, List.class);
        m.setAccessible(true);
        JsonNode root = new ObjectMapper().readTree(
                Files.readString(Paths.get("lesson-plan-docs/k12_curriculum.json"), StandardCharsets.UTF_8));
        List<String> texts = new ArrayList<>();
        m.invoke(init, root, "", texts, new ArrayList<Map<String, Object>>());
        System.out.println("EXTRACTED=" + texts.size());
        assertFalse(texts.isEmpty(), "应提取出知识点正文分片");
        assertTrue(texts.size() >= 200, "应提取 200+ 条知识点分片");
        System.out.println("SAMPLE=" + texts.get(0));
        int minLen = texts.stream().mapToInt(String::length).min().orElse(0);
        assertTrue(minLen >= 100, "分片应为正文长度(>=100字符), 实际 min=" + minLen);
    }
}