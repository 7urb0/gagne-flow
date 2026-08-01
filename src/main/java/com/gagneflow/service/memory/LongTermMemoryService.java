package com.gagneflow.service.memory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gagneflow.entity.LtmFact;
import com.gagneflow.repository.LtmFactRepository;
import com.gagneflow.service.vector.VectorEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class LongTermMemoryService {
    private static final Logger logger = LoggerFactory.getLogger(LongTermMemoryService.class);
    private static final String LTM_KEY_PREFIX = "gagneflow:ltm:";
    private static final String VEC_KEY_PREFIX = "gagneflow:ltm:vec:";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 事实来源阶段: 从摘要中提取(可能含中间立场) */
    public static final String SOURCE_SUMMARY = "SUMMARY_EXTRACTED";
    /** 事实来源阶段: 用户明确声明的偏好/约束 */
    public static final String SOURCE_USER = "USER_EXPLICIT";
    /** 事实来源阶段: 教案 Review>=70 回灌的最终决策 */
    public static final String SOURCE_FINAL = "FINAL_DECISION";

    private final StringRedisTemplate redisTemplate;
    private final VectorEmbeddingService embeddingService;
    private final LtmFactRepository ltmFactRepository;

    public LongTermMemoryService(StringRedisTemplate redisTemplate, VectorEmbeddingService embeddingService,
                                 LtmFactRepository ltmFactRepository) {
        this.redisTemplate = redisTemplate;
        this.embeddingService = embeddingService;
        this.ltmFactRepository = ltmFactRepository;
    }

    private String sessionKey(Long userId, String sessionId) {
        long uid = userId != null ? userId : 0L;
        return LTM_KEY_PREFIX + uid + ":" + sessionId;
    }

    /**
     * 存储事实: 写入 Redis(Set + detail + 向量缓存) + MySQL(ltm_fact) 持久化兜底。
     * detail 格式: "factText|sourcePhase"，searchFacts 用 split("|",2) 解析。
     */
    public void storeFacts(Long userId, String sessionId, List<MemoryFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        String key = this.sessionKey(userId, sessionId);
        int stored = 0;
        int vecCached = 0;
        long uid = userId != null ? userId : 0L;
        ArrayList<LtmFact> mysqlFacts = new ArrayList<>();
        for (MemoryFact fact : facts) {
            try {
                String factId = UUID.nameUUIDFromBytes((sessionId + "_" + fact.getFact()).getBytes()).toString();
                String sourcePhase = fact.getSourcePhase() != null ? fact.getSourcePhase() : SOURCE_SUMMARY;
                this.redisTemplate.opsForSet().add(key, factId);
                String detailKey = "gagneflow:ltm:detail:" + factId;
                this.redisTemplate.opsForValue().set(detailKey, fact.getFact() + "|" + sourcePhase, Duration.ofDays(30L));
                // 预计算向量并缓存
                try {
                    List<Float> vec = this.embeddingService.generateEmbedding(fact.getFact());
                    String vecJson = objectMapper.writeValueAsString(vec);
                    this.redisTemplate.opsForValue().set(VEC_KEY_PREFIX + factId, vecJson, Duration.ofDays(30L));
                    vecCached++;
                } catch (Exception ve) {
                    logger.debug("事实向量预计算失败 (search时将降级到关键词): {}", ve.getMessage());
                }
                mysqlFacts.add(new LtmFact(factId, uid, sessionId, fact.getFact(), fact.getFactType(), sourcePhase));
                ++stored;
            }
            catch (Exception e) {
                logger.warn("事实 {} 存储失败: {}", fact.getFact(), e.getMessage());
            }
        }
        // Set 与 detail/vec 的 TTL 保持一致，避免僵尸 key
        try {
            this.redisTemplate.expire(key, Duration.ofDays(30L));
        } catch (Exception e) {
            logger.trace("设置 LTM Set TTL 失败: {}", e.getMessage());
        }
        // P0: MySQL 持久化兜底 (失败不影响主流程，仅告警)
        try {
            if (!mysqlFacts.isEmpty()) {
                this.ltmFactRepository.saveAll(mysqlFacts);
            }
        } catch (Exception e) {
            logger.warn("LTM 事实 MySQL 持久化失败 (Redis 已写入): sessionId={}, 原因: {}", sessionId, e.getMessage());
        }
        logger.info("会话 {} 长期记忆: 存储 {} / {} 条事实, 向量缓存 {} 条", sessionId, stored, facts.size(), vecCached);
    }

    public List<MemoryFact> searchFacts(Long userId, String sessionId, String query, int topK) {
        String key = this.sessionKey(userId, sessionId);
        Set<String> factIds = this.redisTemplate.opsForSet().members(key);
        if (factIds == null || factIds.isEmpty()) {
            // P0: Redis 无事实(丢失/过期)时降级从 MySQL 重建到 Redis
            return this.rebuildFromMySql(userId, sessionId, query, topK);
        }
        try {
            return this.vectorSearch(factIds, query, topK);
        }
        catch (Exception e) {
            logger.warn("向量语义搜索失败，降级为关键词匹配: {}", e.getMessage());
            return this.keywordSearch(factIds, query, topK);
        }
    }

    /**
     * P0: 从 MySQL ltm_fact 重建会话事实到 Redis，返回按相关性排序的 topK。
     * 服务器迁移/Redis 清空后记忆不丢（对应"记忆丢失"坑的修复）。
     */
    private List<MemoryFact> rebuildFromMySql(Long userId, String sessionId, String query, int topK) {
        long uid = userId != null ? userId : 0L;
        try {
            List<LtmFact> rows = this.ltmFactRepository.findByUserIdAndSessionId(uid, sessionId);
            if (rows == null || rows.isEmpty()) {
                return Collections.emptyList();
            }
            String key = this.sessionKey(userId, sessionId);
            for (LtmFact row : rows) {
                try {
                    this.redisTemplate.opsForSet().add(key, row.getFactId());
                    String detailKey = "gagneflow:ltm:detail:" + row.getFactId();
                    String sourcePhase = row.getSourcePhase() != null ? row.getSourcePhase() : SOURCE_SUMMARY;
                    this.redisTemplate.opsForValue().set(detailKey,
                            row.getFactText() + "|" + sourcePhase, Duration.ofDays(30L));
                    // 向量缓存按需重建: 先只恢复文本，向量缺失时 searchFacts 会实时补算
                } catch (Exception e) {
                    logger.trace("重建事实到 Redis 失败: {}", e.getMessage());
                }
            }
            this.redisTemplate.expire(key, Duration.ofDays(30L));
            logger.info("LTM 从 MySQL 重建: sessionId={}, 恢复 {} 条事实", sessionId, rows.size());
            // 重建后走标准检索路径
            Set<String> factIds = this.redisTemplate.opsForSet().members(key);
            if (factIds == null || factIds.isEmpty()) {
                return Collections.emptyList();
            }
            return this.vectorSearch(factIds, query, topK);
        } catch (Exception e) {
            logger.warn("LTM MySQL 重建失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 使用预缓存的向量进行余弦相似度检索，避免实时Embedding API调用。
     * P1: 按来源阶段加权 —— 最终结论/用户明确声明权重更高，中间立场权重低。
     */
    private List<MemoryFact> vectorSearch(Set<String> factIds, String query, int topK) {
        List<Float> queryVector;
        try {
            queryVector = this.embeddingService.generateQueryVector(query);
        }
        catch (Exception e) {
            logger.warn("查询向量化失败，降级: {}", e.getMessage());
            return this.keywordSearch(factIds, query, topK);
        }

        // 先固定顺序，再据此构建所有 key 列表，避免三次迭代 HashSet 隐式依赖顺序一致性
        List<String> factIdList = new ArrayList<>(factIds);
        List<String> vecKeys = factIdList.stream().map(id -> VEC_KEY_PREFIX + id).collect(Collectors.toList());
        List<String> vecJsons = this.redisTemplate.opsForValue().multiGet(vecKeys);

        List<String> detailKeys = factIdList.stream().map(id -> "gagneflow:ltm:detail:" + id).collect(Collectors.toList());
        List<String> details = this.redisTemplate.opsForValue().multiGet(detailKeys);
        List<FactEntry> entries = new ArrayList<>();

        int cachedHits = 0;
        for (int i = 0; i < factIdList.size(); i++) {
            String detail = (details != null && i < details.size()) ? details.get(i) : null;
            if (detail == null) continue;
            String[] parts = detail.contains("|") ? detail.split("\\|", 2) : new String[]{detail, SOURCE_SUMMARY};
            String factText = parts[0];
            String sourcePhase = parts[1] != null && !parts[1].isEmpty() ? parts[1] : SOURCE_SUMMARY;

            String vecJson = (vecJsons != null && i < vecJsons.size()) ? vecJsons.get(i) : null;
            List<Float> cachedVec = null;
            if (vecJson != null) {
                try {
                    cachedVec = objectMapper.readValue(vecJson, new TypeReference<List<Float>>() {});
                    cachedHits++;
                } catch (Exception e) {
                    logger.trace("向量反序列化失败: {}", e.getMessage());
                }
            }
            entries.add(new FactEntry(factText, sourcePhase, cachedVec));
        }

        if (cachedHits > 0) {
            logger.debug("长期记忆向量缓存命中: {}/{}", cachedHits, entries.size());
        }

        // 相似度计算 (使用缓存向量或实时计算) + 来源阶段加权
        for (FactEntry entry : entries) {
            try {
                List<Float> factVector = entry.cachedVector;
                if (factVector == null) {
                    // 缓存未命中, 降级到实时Embedding
                    factVector = this.embeddingService.generateEmbedding(entry.factText);
                }
                float sim = this.cosineSimilarity(queryVector, factVector);
                entry.similarity = sim * sourcePhaseWeight(entry.sourcePhase);
            }
            catch (Exception e) {
                logger.debug("事实向量化失败: {} (前20字)", entry.factText.substring(0, Math.min(20, entry.factText.length())));
                entry.similarity = -1.0f;
            }
        }

        entries.sort((a, b) -> Float.compare(b.similarity, a.similarity));
        ArrayList<MemoryFact> results = new ArrayList<MemoryFact>();
        for (FactEntry entry : entries) {
            if (entry.similarity < 0.0f) continue;
            MemoryFact fact = new MemoryFact();
            fact.setFact(entry.factText);
            fact.setSourcePhase(entry.sourcePhase);
            results.add(fact);
            if (results.size() >= topK) break;
        }

        if (results.size() < topK) {
            List<MemoryFact> list = this.keywordSearch(factIds, query, topK - results.size());
            for (MemoryFact kf : list) {
                boolean dup = results.stream().anyMatch(r -> r.getFact().equals(kf.getFact()));
                if (dup) continue;
                results.add(kf);
            }
        }
        logger.info("向量语义搜索完成: {} 条结果, topK={}", results.size(), topK);
        return results;
    }

    /**
     * P1: 来源阶段权重 —— 最终决策/用户明确声明 = 1.0，摘要提取(可能含中间立场) = 0.85。
     */
    private float sourcePhaseWeight(String sourcePhase) {
        if (sourcePhase == null) {
            return 0.85f;
        }
        switch (sourcePhase) {
            case SOURCE_FINAL:
            case SOURCE_USER:
                return 1.0f;
            default:
                return 0.85f;
        }
    }

    private List<MemoryFact> keywordSearch(Set<String> factIds, String query, int topK) {
        ArrayList<MemoryFact> results = new ArrayList<MemoryFact>();
        for (String factId : factIds) {
            String detailKey = "gagneflow:ltm:detail:" + factId;
            String detail = this.redisTemplate.opsForValue().get(detailKey);
            if (detail == null) continue;
            String[] parts = detail.contains("|") ? detail.split("\\|", 2) : new String[]{detail, SOURCE_SUMMARY};
            String factText = parts[0];
            if (!this.containsKeyword(factText, query)) continue;
            MemoryFact fact = new MemoryFact();
            fact.setFact(factText);
            fact.setSourcePhase(parts[1] != null && !parts[1].isEmpty() ? parts[1] : SOURCE_SUMMARY);
            results.add(fact);
            if (results.size() >= topK) break;
        }
        return results;
    }

    private float cosineSimilarity(List<Float> a, List<Float> b) {
        if (a.size() != b.size()) {
            return -1.0f;
        }
        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        for (int i = 0; i < a.size(); ++i) {
            dotProduct += a.get(i).floatValue() * b.get(i).floatValue();
            normA += a.get(i).floatValue() * a.get(i).floatValue();
            normB += b.get(i).floatValue() * b.get(i).floatValue();
        }
        if (normA == 0.0f || normB == 0.0f) {
            return 0.0f;
        }
        return dotProduct / (float)(Math.sqrt(normA) * Math.sqrt(normB));
    }

    public void clearSessionFacts(Long userId, String sessionId) {
        String key = this.sessionKey(userId, sessionId);
        Set<String> factIds = this.redisTemplate.opsForSet().members(key);
        if (factIds != null) {
            for (String factId : factIds) {
                this.redisTemplate.delete("gagneflow:ltm:detail:" + factId);
                this.redisTemplate.delete(VEC_KEY_PREFIX + factId);  // 清理缓存向量
            }
        }
        this.redisTemplate.delete(key);
        // P0: 联动删除 MySQL 持久化副本
        try {
            long uid = userId != null ? userId : 0L;
            this.ltmFactRepository.deleteByUserIdAndSessionId(uid, sessionId);
        } catch (Exception e) {
            logger.warn("LTM MySQL 清理失败: {}", e.getMessage());
        }
    }

    public void storeUserPreference(Long userId, String subject, String grade) {
        String key = LTM_KEY_PREFIX + userId + ":prefs";
        HashMap<String, String> prefs = new HashMap<String, String>();
        prefs.put("subject", subject);
        prefs.put("grade", grade);
        prefs.put("lastUsed", String.valueOf(System.currentTimeMillis()));
        this.redisTemplate.opsForHash().putAll(key, prefs);
        // 用户偏好添加90天TTL，避免永久占用
        try {
            this.redisTemplate.expire(key, Duration.ofDays(90L));
        } catch (Exception e) {
            logger.trace("设置用户偏好 TTL 失败: {}", e.getMessage());
        }
    }

    private boolean containsKeyword(String fact, String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        for (String keyword : query.split("\\s+")) {
            if (!fact.contains(keyword)) continue;
            return true;
        }
        return false;
    }

    public static class MemoryFact {
        private String fact;
        private String factType;
        private String sourcePhase;

        public String getFact() {
            return this.fact;
        }

        public void setFact(String fact) {
            this.fact = fact;
        }

        public String getFactType() {
            return this.factType;
        }

        public void setFactType(String factType) {
            this.factType = factType;
        }

        public String getSourcePhase() {
            return this.sourcePhase;
        }

        public void setSourcePhase(String sourcePhase) {
            this.sourcePhase = sourcePhase;
        }
    }

    private static class FactEntry {
        final String factText;
        final String sourcePhase;
        final List<Float> cachedVector;  // 预缓存的向量 (可为null)
        float similarity = 0.0f;

        FactEntry(String factText, String sourcePhase, List<Float> cachedVector) {
            this.factText = factText;
            this.sourcePhase = sourcePhase;
            this.cachedVector = cachedVector;
        }
    }
}
