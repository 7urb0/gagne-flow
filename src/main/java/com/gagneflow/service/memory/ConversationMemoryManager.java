package com.gagneflow.service.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.gagneflow.service.chat.ChatSession;
import com.gagneflow.service.chat.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemoryManager {
    private static final Logger logger = LoggerFactory.getLogger(ConversationMemoryManager.class);
    private static final int MAX_FACTS = 5;

    // P1修复: 结构化事实提取 — 按类别 + 关键词命中权重代替简单分句
    private static final Pattern SENTENCE_PATTERN = Pattern.compile(
        "[^。！？\\n]+[。！？\\n]?");  // 按句子分割
    private static final Pattern QUOTED_PATTERN = Pattern.compile(
        "[「『\"'\"]([^」』\"'\"']+)[」』\"'\"']");  // 引号内关键信息

    /** 事实类别及关键词 (按优先级排序) */
    private static final Map<String, List<String>> FACT_CATEGORIES = new LinkedHashMap<>();
    static {
        // 格式: 类别名 -> 关键词列表
        FACT_CATEGORIES.put("教学需求", List.of(
            "需求", "需要", "要求", "目标", "期望", "想要", "希望", "教学重点"));
        FACT_CATEGORIES.put("学生情况", List.of(
            "学生", "学情", "年级", "班级", "水平", "基础", "掌握", "薄弱"));
        FACT_CATEGORIES.put("教学偏好", List.of(
            "偏好", "喜欢", "习惯", "风格", "方式", "形式", "模式", "擅长"));
        FACT_CATEGORIES.put("学科年级", List.of(
            "学科", "科目", "年级", "学期", "单元", "章节", "学段", "课时"));
        FACT_CATEGORIES.put("约束限制", List.of(
            "不能", "不得", "禁止", "不要", "避免", "限制", "约束", "时间限制", "资源"));
        FACT_CATEGORIES.put("否定反馈", List.of(
            "不对", "不行", "不好", "错误", "不正确", "不适合", "不满意", "不是"));
        FACT_CATEGORIES.put("数值信息", List.of(
            "时长", "分钟", "课时数", "人数", "次数", "分数", "比例", "占比"));
    }

    private final ChatSessionService chatSessionService;
    private final LongTermMemoryService longTermMemoryService;

    public ConversationMemoryManager(ChatSessionService chatSessionService,
                                      LongTermMemoryService longTermMemoryService) {
        this.chatSessionService = chatSessionService;
        this.longTermMemoryService = longTermMemoryService;
    }

    public String getOrCreateSession(Long userId, String sessionId) {
        return this.chatSessionService.getOrCreate(userId, sessionId).getSessionId();
    }

    public void addMessage(Long userId, String sessionId, String question, String answer) {
        this.chatSessionService.addMessage(userId, sessionId, question, answer);
    }

    public List<Map<String, String>> getHistory(Long userId, String sessionId) {
        return this.chatSessionService.getHistory(userId, sessionId);
    }

    public int getMessagePairCount(Long userId, String sessionId) {
        return this.chatSessionService.getMessagePairCount(userId, sessionId);
    }

    public void clearHistory(Long userId, String sessionId) {
        this.chatSessionService.clearHistory(userId, sessionId);
        this.longTermMemoryService.clearSessionFacts(userId, sessionId);
    }

    public void onSummaryGenerated(Long userId, String sessionId, String summary) {
        List<LongTermMemoryService.MemoryFact> facts = this.extractFactsFromSummary(summary);
        this.longTermMemoryService.storeFacts(userId, sessionId, facts);
    }

    /**
     * 存储用户通过教案表单显式填写的个性化偏好(2026-08-18 新增)。
     * 来源标记 USER_EXPLICIT(权重 1.0), 会进入跨会话全局集合。
     */
    public void storeUserPreference(Long userId, String sessionId, String factType, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        LongTermMemoryService.MemoryFact fact = new LongTermMemoryService.MemoryFact();
        fact.setFact(text.trim());
        fact.setFactType(factType);
        fact.setSourcePhase(LongTermMemoryService.SOURCE_USER);
        this.longTermMemoryService.storeFacts(userId, sessionId, List.of(fact));
    }

    public ConversationContext buildFullContext(Long userId, String sessionId,
                                                  String currentQuery, int ltmTopK) {
        ChatSession session = this.chatSessionService.getRaw(userId, sessionId);
        List<Map<String, String>> history = session != null
            ? session.getMessageHistory() : Collections.emptyList();
        String longTermContext = this.getLongTermContext(userId, sessionId, currentQuery, ltmTopK);
        int shortTermTokens = session != null ? session.getTotalTokens() : 0;
        return new ConversationContext(history, longTermContext, shortTermTokens);
    }

    public String getLongTermContext(Long userId, String sessionId, String query, int topK) {
        List<LongTermMemoryService.MemoryFact> facts =
            this.longTermMemoryService.searchFacts(userId, sessionId, query, topK);
        if (facts.isEmpty()) {
            return "";
        }
        StringBuilder ctx = new StringBuilder("\n\n[\u957f\u671f\u8bb0\u5fc6]\n");
        for (LongTermMemoryService.MemoryFact fact : facts) {
            ctx.append("- ").append(fact.getFact()).append("\n");
        }
        return ctx.toString();
    }

    /**
     * P1修复: 结构化事实提取。
     * 从对话摘要中按类别识别关键事实，替代简单 split("[。！？\\n]") 取前5句的做法。
     *
     * 算法:
     * 1. 按句子分割摘要
     * 2. 对每个句子计算"重要性分数": 基于类别关键词命中数 + 引号内关键信息权重
     * 3. 按分数降序排序，取 topN
     * 4. 每个类别最多取 2 条，避免单一类别垄断
     */
    private List<LongTermMemoryService.MemoryFact> extractFactsFromSummary(String summary) {
        if (summary == null || summary.isEmpty()) {
            return Collections.emptyList();
        }

        // 预处理: 提取引号内的关键信息作为额外事实候选
        List<String> quotedTerms = new ArrayList<>();
        Matcher qm = QUOTED_PATTERN.matcher(summary);
        while (qm.find()) {
            String term = qm.group(1).trim();
            if (term.length() >= 4 && term.length() <= 80) {
                quotedTerms.add(term);
            }
        }

        // 按句子分割并评分
        List<ScoredSentence> scoredSentences = new ArrayList<>();
        Matcher sm = SENTENCE_PATTERN.matcher(summary);
        while (sm.find()) {
            String sentence = sm.group().trim();
            // 过滤: 太短(<10字)或太长(>200字)的句子
            if (sentence.length() < 10 || sentence.length() > 200) {
                continue;
            }
            // 过滤: 纯格式标记 / 过渡句
            if (sentence.matches("^[\\(\\[#\\-\\*]+.*") || sentence.matches("^[\\uff0c\u3002]+$")) {
                continue;
            }
            int score = scoreSentence(sentence);
            String category = classifySentence(sentence);
            scoredSentences.add(new ScoredSentence(sentence, score, category));
        }

        // 按分数降序排序
        scoredSentences.sort((a, b) -> Integer.compare(b.score, a.score));

        // 选取 topN，每个类别最多2条
        List<LongTermMemoryService.MemoryFact> facts = new ArrayList<>();
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        for (ScoredSentence ss : scoredSentences) {
            if (facts.size() >= MAX_FACTS) break;
            int catCount = categoryCounts.getOrDefault(ss.category, 0);
            if (catCount >= 2) continue; // 每类最多2条

            LongTermMemoryService.MemoryFact fact = new LongTermMemoryService.MemoryFact();
            fact.setFact(ss.text);
            fact.setFactType(ss.category);
            // P1: 从摘要提取的事实标记为中间立场来源(权重低)，避免"中间讨论"污染最终检索
            fact.setSourcePhase(LongTermMemoryService.SOURCE_SUMMARY);
            facts.add(fact);
            categoryCounts.put(ss.category, catCount + 1);
        }

        // 补充: 引号内的关键术语作为补充事实 (如果还有名额)
        for (String term : quotedTerms) {
            if (facts.size() >= MAX_FACTS) break;
            boolean alreadyExists = facts.stream().anyMatch(f -> f.getFact().contains(term));
            if (alreadyExists) continue;
            LongTermMemoryService.MemoryFact fact = new LongTermMemoryService.MemoryFact();
            fact.setFact(term);
            fact.setFactType("关键术语");
            fact.setSourcePhase(LongTermMemoryService.SOURCE_SUMMARY);
            facts.add(fact);
        }

        if (facts.isEmpty()) {
            // 降级: 没有任何句子匹配类别关键词时，取前N个有效句子
            int count = 0;
            for (ScoredSentence ss : scoredSentences) {
                if (count >= MAX_FACTS) break;
                LongTermMemoryService.MemoryFact fact = new LongTermMemoryService.MemoryFact();
                fact.setFact(ss.text);
                fact.setFactType("未分类");
                fact.setSourcePhase(LongTermMemoryService.SOURCE_SUMMARY);
                facts.add(fact);
                count++;
            }
        }

        logger.info("\u7ed3\u6784\u5316\u4e8b\u5b9e\u63d0\u53d6: {} \u6761\u4ece {} \u4e2a\u5019\u9009\u53e5", facts.size(), scoredSentences.size());
        return facts;
    }

    /** 为句子计算重要性分数 */
    private int scoreSentence(String sentence) {
        int score = 1; // 基础分
        for (Map.Entry<String, List<String>> entry : FACT_CATEGORIES.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (sentence.contains(keyword)) {
                    score += 2;
                }
            }
        }
        // 引号内信息加成
        Matcher qm = QUOTED_PATTERN.matcher(sentence);
        while (qm.find()) {
            score += 1;
        }
        // 数值信息加分 (包含数字的句子通常是具体约束)
        if (sentence.matches(".*\\d+.*")) {
            score += 1;
        }
        return score;
    }

    /** 判断句子属于哪个类别 */
    private String classifySentence(String sentence) {
        String bestCategory = "\u5176\u4ed6\u4fe1\u606f";
        int bestCount = 0;
        for (Map.Entry<String, List<String>> entry : FACT_CATEGORIES.entrySet()) {
            int count = 0;
            for (String keyword : entry.getValue()) {
                if (sentence.contains(keyword)) count++;
            }
            if (count > bestCount) {
                bestCount = count;
                bestCategory = entry.getKey();
            }
        }
        return bestCategory;
    }

    /** 带评分的句子 (内部类) */
    private static class ScoredSentence {
        final String text;
        final int score;
        final String category;

        ScoredSentence(String text, int score, String category) {
            this.text = text;
            this.score = score;
            this.category = category;
        }
    }

    public static class ConversationContext {
        private final List<Map<String, String>> history;
        private final String longTermContext;
        private final int totalTokens;

        public ConversationContext(List<Map<String, String>> history, String longTermContext,
                                    int totalTokens) {
            this.history = history;
            this.longTermContext = longTermContext;
            this.totalTokens = totalTokens;
        }

        public List<Map<String, String>> getHistory() { return this.history; }
        public String getLongTermContext() { return this.longTermContext; }
        public int getTotalTokens() { return this.totalTokens; }
    }
}
