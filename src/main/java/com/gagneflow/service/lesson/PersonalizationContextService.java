package com.gagneflow.service.lesson;

import java.util.Map;

import com.gagneflow.service.memory.ConversationMemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 个性化上下文提供者（2026-08-17 新增）。
 *
 * 背景: 教案生成流水线中, 用户偏好(LTM 长期记忆)只在 Analysis 阶段注入,
 *       Design/Development/Review 阶段"失忆", 导致 Review 按通用标准误判个性化教案。
 *
 * 职责: 按流水线阶段检索 LTM 中长期记忆, 生成 "[用户个性化要求]" 段落,
 *       供 Design/Development/Review 阶段注入 prompt —— 让用户偏好贯穿全流程。
 *
 * 设计:
 * - 各阶段用不同查询词检索(教学偏好/学生情况等), 保证注入内容与阶段相关
 * - gagneflow.personalization.enabled 开关, 默认开启, 可一键回退
 * - LTM 不可用/无记忆时返回空串, 不阻塞流水线(降级链)
 * - prompt 模板保持纯净(注入是运行时拼接, 不进模板, 不影响 Prompt 版本管理)
 */
@Service
public class PersonalizationContextService {
    private static final Logger logger = LoggerFactory.getLogger(PersonalizationContextService.class);
    private static final String PREFIX = "\n\n[用户个性化要求]\n";

    private final ConversationMemoryManager memoryManager;

    @Value("${gagneflow.personalization.enabled:true}")
    private boolean enabled = true;
    @Value("${gagneflow.personalization.top-k:3}")
    private int topK = 3;

    /** 各阶段检索查询词: 让 LTM 检索命中与阶段相关的偏好事实 */
    private static final Map<String, String> STAGE_QUERY = Map.of(
        "design", "教学偏好 教学方法 设计风格 教学要求",
        "development", "教学风格 课堂组织 练习方式 互动形式",
        "review", "用户偏好 教学要求 质量标准 评价标准",
        "format", "格式偏好 呈现方式 结构要求"
    );

    public PersonalizationContextService(ConversationMemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * 获取指定阶段的个性化上下文段(供 prompt 追加)。
     * 返回 "\n\n[用户个性化要求]\n- 事实..." 或空串(无记忆/开关关闭/异常)。
     */
    public String getContext(Long userId, String sessionId, String stage) {
        if (!enabled) {
            return "";
        }
        String query = STAGE_QUERY.getOrDefault(stage, "用户偏好 教学要求");
        try {
            String ltm = this.memoryManager.getLongTermContext(userId, sessionId, query, this.topK);
            if (ltm == null || ltm.isBlank()) {
                return "";
            }
            // getLongTermContext 返回 "\n\n[长期记忆]\n- 事实...", 替换标签为个性化段(保留方括号风格)
            return ltm.replace("[长期记忆]", "[用户个性化要求]");
        } catch (Exception e) {
            logger.warn("[Personalization] {} 阶段个性化上下文获取失败, 跳过注入: {}",
                    stage, e.getMessage());
            return "";
        }
    }

    /** 是否启用个性化注入 */
    public boolean isEnabled() {
        return this.enabled;
    }
}
