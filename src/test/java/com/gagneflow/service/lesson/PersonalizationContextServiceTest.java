package com.gagneflow.service.lesson;

import com.gagneflow.service.memory.ConversationMemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PersonalizationContextService 测试(2026-08-17):
 * - 按阶段查询词检索 LTM
 * - 开关控制(默认开, 可关)
 * - 降级链(无记忆/异常返回空串, 不阻塞)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PersonalizationContextService")
class PersonalizationContextServiceTest {

    @Mock private ConversationMemoryManager memoryManager;

    private PersonalizationContextService service;

    private static final Long UID = 1L;
    private static final String SID = "session-1";

    @BeforeEach
    void setUp() {
        service = new PersonalizationContextService(memoryManager);
    }

    private void setEnabled(boolean enabled) throws Exception {
        Field f = PersonalizationContextService.class.getDeclaredField("enabled");
        f.setAccessible(true);
        f.setBoolean(service, enabled);
    }

    @Nested
    @DisplayName("个性化上下文检索")
    class GetContextTests {

        @Test
        @DisplayName("有记忆时返回 [用户个性化要求] 段")
        void contextWithMemory() {
            when(memoryManager.getLongTermContext(eq(UID), eq(SID), anyString(), anyInt()))
                    .thenReturn("\n\n[长期记忆]\n- 用户偏好边做题边学习\n");

            String ctx = service.getContext(UID, SID, "review");

            assertTrue(ctx.contains("[用户个性化要求]"), "应包含个性化段标签");
            assertTrue(ctx.contains("用户偏好边做题边学习"), "应包含偏好事实");
        }

        @Test
        @DisplayName("无记忆时返回空串")
        void contextNoMemory() {
            when(memoryManager.getLongTermContext(any(), any(), any(), anyInt())).thenReturn("");

            assertEquals("", service.getContext(UID, SID, "review"));
        }

        @Test
        @DisplayName("LTM 异常时返回空串(不阻塞流水线)")
        void contextOnException() {
            when(memoryManager.getLongTermContext(any(), any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("redis down"));

            assertEquals("", service.getContext(UID, SID, "design"));
        }

        @Test
        @DisplayName("未知阶段使用默认查询词(不抛异常)")
        void contextUnknownStage() {
            when(memoryManager.getLongTermContext(any(), any(), anyString(), anyInt()))
                    .thenReturn("\n\n[长期记忆]\n- 用户偏好启发式教学\n");

            String ctx = service.getContext(UID, SID, "unknown-stage");

            assertTrue(ctx.contains("用户偏好启发式教学"));
        }
    }

    @Nested
    @DisplayName("开关控制")
    class EnabledTests {

        @Test
        @DisplayName("开关关闭时不检索 LTM(返回空)")
        void disabledReturnsEmpty() throws Exception {
            setEnabled(false);

            String ctx = service.getContext(UID, SID, "review");

            assertEquals("", ctx);
            verify(memoryManager, never()).getLongTermContext(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("开关默认开启")
        void enabledByDefault() {
            assertTrue(service.isEnabled());
        }
    }
}
