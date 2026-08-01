package com.gagneflow.service.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("K12CurriculumLoader Tests")
class K12CurriculumLoaderTest {

    private K12CurriculumLoader loader;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        loader = new K12CurriculumLoader();
        mapper = new ObjectMapper();
    }

    /** 反射注入 root，绕过文件系统依赖 */
    private void injectRoot(String json) throws Exception {
        Field rootField = K12CurriculumLoader.class.getDeclaredField("root");
        rootField.setAccessible(true);
        JsonNode node = mapper.readTree(json);
        rootField.set(loader, node);
    }

    @Nested
    @DisplayName("isLoaded")
    class LoadState {

        @Test
        @DisplayName("未初始化时 isLoaded 返回 false")
        void notLoadedByDefault() {
            assertFalse(loader.isLoaded());
        }

        @Test
        @DisplayName("注入 JSON 后 isLoaded 返回 true")
        void loadedAfterInject() throws Exception {
            injectRoot("{\"学段\":[]}");
            assertTrue(loader.isLoaded());
        }
    }

    @Nested
    @DisplayName("lookup with valid parameters")
    class LookupValidParams {

        @Test
        @DisplayName("未加载数据时返回提示文本")
        void lookupBeforeLoad() {
            String result = loader.lookup("小学", "三年级", "语文");
            assertNotNull(result);
            assertTrue(result.contains("未加载") || result.contains("K12"),
                    "应返回未加载提示，实际: " + result);
        }

        @Test
        @DisplayName("lookup 返回匹配的课程标准数据")
        void lookupReturnsMatch() throws Exception {
            String json = """
                {
                  "学段": [
                    {
                      "name": "小学",
                      "学科": [
                        {
                          "name": "语文",
                          "年级": [
                            {
                              "grade": "三年级",
                              "章节": [
                                {"name": "古诗三首", "知识点": ["朗读", "背诵"]}
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
            injectRoot(json);

            String result = loader.lookup("小学", "三年级", "语文");
            assertNotNull(result);
            assertTrue(result.contains("小学"), "结果应包含学段，实际: " + result);
            assertTrue(result.contains("古诗三首"), "结果应包含章节，实际: " + result);
            assertTrue(result.contains("朗读"), "结果应包含知识点，实际: " + result);
        }

        @Test
        @DisplayName("lookup 不匹配的学段返回不含内容的文本")
        void lookupInvalidStage() throws Exception {
            String json = """
                {
                  "学段": [
                    {"name": "小学", "学科": [{"name": "语文", "年级": []}]}
                  ]
                }
                """;
            injectRoot(json);

            String result = loader.lookup("大学", "三年级", "语文");
            assertNotNull(result);
            assertFalse(result.contains("小学"), "不匹配的学段不应包含结果，实际: " + result);
        }

        @Test
        @DisplayName("lookup 不匹配的学科返回空结果")
        void lookupInvalidSubject() throws Exception {
            String json = """
                {
                  "学段": [
                    {"name": "小学", "学科": [{"name": "语文", "年级": []}]}
                  ]
                }
                """;
            injectRoot(json);

            String result = loader.lookup("小学", "三年级", "数学");
            assertNotNull(result);
            assertFalse(result.contains("数学"), "不匹配的学科不应包含结果，实际: " + result);
        }

        @Test
        @DisplayName("lookup 不匹配的年级返回空结果")
        void lookupInvalidGrade() throws Exception {
            String json = """
                {
                  "学段": [
                    {
                      "name": "小学",
                      "学科": [
                        {
                          "name": "语文",
                          "年级": [{"grade": "三年级", "章节": []}]
                        }
                      ]
                    }
                  ]
                }
                """;
            injectRoot(json);

            String result = loader.lookup("小学", "五年级", "语文");
            assertNotNull(result);
            assertFalse(result.contains("五年级"), "不匹配的年级不应包含结果，实际: " + result);
        }

        @Test
        @DisplayName("lookup 年级为 0 返回空结果")
        void lookupZeroGrade() throws Exception {
            String json = """
                {
                  "学段": [
                    {
                      "name": "小学",
                      "学科": [
                        {
                          "name": "语文",
                          "年级": [{"grade": "三年级", "章节": []}]
                        }
                      ]
                    }
                  ]
                }
                """;
            injectRoot(json);

            String result = loader.lookup("小学", "0", "语文");
            assertNotNull(result);
            assertFalse(result.contains("0"), "年级为 0 不应匹配到任何年级，实际: " + result);
            assertFalse(result.contains("三年级"), "不应返回实际年级内容，实际: " + result);
        }

        @Test
        @DisplayName("lookup null 参数不抛异常")
        void lookupNullParams() throws Exception {
            injectRoot("{\"学段\":[]}");
            assertDoesNotThrow(() -> loader.lookup(null, null, null));
        }
    }
}
