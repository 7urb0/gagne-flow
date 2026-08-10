package com.gagneflow.service.memory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final String DETAIL_KEY_PREFIX = "gagneflow:ltm:detail:";
    /** 跨会话全局事实集合: gagneflow:ltm:global:{uid} */
    private static final String GLOBAL_KEY_PREFIX = "gagneflow:ltm:global:";
    /** 全局事实在 MySQL 中的 sessionId 标记 */
    private static final String GLOBAL_SESSION = "GLOBAL";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 事实来源阶段: 从摘要中提取(可能含中间立场) */
    public static final String SOURCE_SUMMARY = "SUMMARY_EXTRACTED";
    /** 事实来源阶段: 用户明确声明的偏好/约束 */
    public static final String SOURCE_USER = "USER_EXPLICIT";
    /** 事实来源阶段: 教案 Review>=70 回灌的最终决策 */
    public static final String SOURCE_FINAL = "FINAL_DECISION";

    // ---- 时间衰减与访问频率(2026-08-10 新增) ----
    /** 时间衰减半衰期(天): 30 天后事实权重衰减为一半 */
    private static final double HALF_LIFE_DAYS = 30.0;
    /** 访问频率封顶: 超过 CAP 次不再线性增长, 防高频事实无限放大 */
    private static final int FREQ_CAP = 10;
    /** 每次访问的频率增益 */
    private static final double FREQ_FACTOR = 0.05;
    /** detail 解析后缺失字段的默认访问时间(epoch ms): 视为刚写入 */
    private static final long DEFAULT_ACCESS_TIME = 0L;

    // ---- 记忆整合与冲突消解(2026-08-10 新增) ----
    /** 相似度超过该值视为重复事实 -> 合并覆盖 */
    private static final double DUP_SIM_THRESHOLD = 0.95;
    /** 相似度在该区间且存在否定词反转 -> 判定为矛盾事实 */
    private static final double CONFLICT_SIM_MIN = 0.75;
    private static final double CONFLICT_SIM_MAX = 0.95;
    /** 否定词表: 用于检测"一正一否"的矛盾事实 */
    private static final String[] NEGATION_WORDS = {"不", "没", "无", "非", "未", "别", "莫"};

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

    private String globalKey(Long userId) {
        long uid = userId != null ? userId : 0L;
        return GLOBAL_KEY_PREFIX + uid;
    }

    /**
     * 存储事实: 写入 Redis(Set + detail + 向量缓存) + MySQL(ltm_fact) 持久化兜底。
     * detail 格式(4 段): "factText|sourcePhase|lastAccessTime|accessCount"，
     * 兼容旧 2 段("factText|sourcePhase")与纯文本格式(解析时兜底)。
     * 记忆整合: 与已有事实相似度 >0.95 视为重复(合并覆盖), 0.75~0.95 且否定词反转视为矛盾(来源权重高者胜)。
     * 跨会话: USER_EXPLICIT / FINAL_DECISION 来源的事实同步写入全局 Set(ltm:global:{uid})，
     *         检索时与会话级事实取并集; 摘要提取(可能含中间立场)不进全局。
     */
    public void storeFacts(Long userId, String sessionId, List<MemoryFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        String key = this.sessionKey(userId, sessionId);
        String globalKey = this.globalKey(userId);
        int stored = 0;
        int vecCached = 0;
        int merged = 0;
        int conflicted = 0;
        long uid = userId != null ? userId : 0L;
        long now = System.currentTimeMillis();
        ArrayList<LtmFact> mysqlFacts = new ArrayList<>();
        // 已有事实快照: factId -> (text, source) 用于整合判断
        Set<String> existingIds = this.safeSetMembers(key);
        Map<String, String[]> existingDetails = this.loadExistingDetails(existingIds);
        for (MemoryFact fact : facts) {
            try {
                String factId = UUID.nameUUIDFromBytes((sessionId + "_" + fact.getFact()).getBytes()).toString();
                String sourcePhase = fact.getSourcePhase() != null ? fact.getSourcePhase() : SOURCE_SUMMARY;
                String text = fact.getFact();
                // ---- 记忆整合: 与已有事实对比 ----
                IntegrationDecision decision = this.resolveIntegration(factId, text, sourcePhase, existingIds, existingDetails);
                if (decision.isDuplicate()) {
                    // 重复: 覆盖旧事实(删旧写新)
                    this.removeFact(userId, sessionId, decision.targetId);
                    existingIds.remove(decision.targetId);
                    existingDetails.remove(decision.targetId);
                    merged++;
                } else if (decision.isConflict()) {
                    // 矛盾: 新事实来源权重更高 -> 删旧写新; 否则跳过新事实
                    boolean newWins = this.sourcePhaseWeight(sourcePhase) >= this.sourcePhaseWeight(decision.oldSource);
                    if (!newWins) {
                        logger.info("冲突消解: 保留旧事实(来源权重更高) {}, 丢弃新事实 {}", decision.targetId, text);
                        conflicted++;
                        continue;
                    }
                    this.removeFact(userId, sessionId, decision.targetId);
                    existingIds.remove(decision.targetId);
                    existingDetails.remove(decision.targetId);
                    conflicted++;
                }
                // ---- 写入 Redis ----
                this.redisTemplate.opsForSet().add(key, factId);
                // 是否进全局集合: 用户明确声明 / 最终决策
                boolean isGlobal = SOURCE_USER.equals(sourcePhase) || SOURCE_FINAL.equals(sourcePhase);
                if (isGlobal) {
                    this.redisTemplate.opsForSet().add(globalKey, factId);
                }
                String detailKey = DETAIL_KEY_PREFIX + factId;
                String detail = text + "|" + sourcePhase + "|" + now + "|0";
                this.redisTemplate.opsForValue().set(detailKey, detail, Duration.ofDays(30L));
                // 预计算向量并缓存
                try {
                    List<Float> vec = this.embeddingService.generateEmbedding(text);
                    String vecJson = objectMapper.writeValueAsString(vec);
                    this.redisTemplate.opsForValue().set(VEC_KEY_PREFIX + factId, vecJson, Duration.ofDays(30L));
                    vecCached++;
                } catch (Exception ve) {
                    logger.debug("事实向量预计算失败 (search时将降级到关键词): {}", ve.getMessage());
                }
                mysqlFacts.add(new LtmFact(factId, uid, sessionId, text, fact.getFactType(), sourcePhase));
                ++stored;
            }
            catch (Exception e) {
                logger.warn("事实 {} 存储失败: {}", fact.getFact(), e.getMessage());
            }
        }
        // Set 与 detail/vec 的 TTL 保持一致，避免僵尸 key
        try {
            this.redisTemplate.expire(key, Duration.ofDays(30L));
            this.redisTemplate.expire(globalKey, Duration.ofDays(30L));
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
        logger.info("会话 {} 长期记忆: 存储 {} / {} 条事实, 向量缓存 {} 条, 整合去重 {} 条, 冲突消解 {} 条",
                sessionId, stored, facts.size(), vecCached, merged, conflicted);
    }

    /** 检索事实: 会话级 Set 与全局 Set 取并集; Redis 无事实时从 MySQL 重建。 */
    public List<MemoryFact> searchFacts(Long userId, String sessionId, String query, int topK) {
        String key = this.sessionKey(userId, sessionId);
        Set<String> factIds = this.safeSetMembers(key);
        Set<String> globalIds = this.safeSetMembers(this.globalKey(userId));
        if ((factIds == null || factIds.isEmpty()) && (globalIds == null || globalIds.isEmpty())) {
            // P0: Redis 无事实(丢失/过期)时降级从 MySQL 重建到 Redis
            return this.rebuildFromMySql(userId, sessionId, query, topK);
        }
        Set<String> merged = new HashSet<>();
        if (factIds != null) merged.addAll(factIds);
        if (globalIds != null) merged.addAll(globalIds);
        try {
            return this.vectorSearch(merged, query, topK);
        }
        catch (Exception e) {
            logger.warn("向量语义搜索失败，降级为关键词匹配: {}", e.getMessage());
            return this.keywordSearch(merged, query, topK);
        }
    }

    /**
     * P0: 从 MySQL ltm_fact 重建会话事实到 Redis，返回按相关性排序的 topK。
     * 同时重建全局事实集合(ltm:global:{uid})，实现跨会话记忆的持久化兜底。
     */
    private List<MemoryFact> rebuildFromMySql(Long userId, String sessionId, String query, int topK) {
        long uid = userId != null ? userId : 0L;
        try {
            List<LtmFact> rows = this.ltmFactRepository.findByUserIdAndSessionId(uid, sessionId);
            if (rows == null || rows.isEmpty()) {
                return Collections.emptyList();
            }
            String key = this.sessionKey(userId, sessionId);
            String globalKey = this.globalKey(userId);
            for (LtmFact row : rows) {
                try {
                    this.redisTemplate.opsForSet().add(key, row.getFactId());
                    String detailKey = DETAIL_KEY_PREFIX + row.getFactId();
                    String sourcePhase = row.getSourcePhase() != null ? row.getSourcePhase() : SOURCE_SUMMARY;
                    this.redisTemplate.opsForValue().set(detailKey,
                            row.getFactText() + "|" + sourcePhase + "|" + System.currentTimeMillis() + "|0",
                            Duration.ofDays(30L));
                    // 重建时同步恢复全局集合标记
                    if (SOURCE_USER.equals(sourcePhase) || SOURCE_FINAL.equals(sourcePhase)) {
                        this.redisTemplate.opsForSet().add(globalKey, row.getFactId());
                    }
                    // 向量缓存按需重建: 先只恢复文本，向量缺失时 searchFacts 会实时补算
                } catch (Exception e) {
                    logger.trace("重建事实到 Redis 失败: {}", e.getMessage());
                }
            }
            this.redisTemplate.expire(key, Duration.ofDays(30L));
            this.redisTemplate.expire(globalKey, Duration.ofDays(30L));
            logger.info("LTM 从 MySQL 重建: sessionId={}, 恢复 {} 条事实", sessionId, rows.size());
            // 重建后走标准检索路径(含全局并集)
            Set<String> factIds = this.safeSetMembers(key);
            Set<String> globalIds = this.safeSetMembers(globalKey);
            Set<String> merged = new HashSet<>();
            if (factIds != null) merged.addAll(factIds);
            if (globalIds != null) merged.addAll(globalIds);
            if (merged.isEmpty()) {
                return Collections.emptyList();
            }
            return this.vectorSearch(merged, query, topK);
        } catch (Exception e) {
            logger.warn("LTM MySQL 重建失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 使用预缓存的向量进行余弦相似度检索，避免实时Embedding API调用。
     * 排序分 = 余弦相似度 × 来源阶段权重 × 时间衰减 × 访问频率增益。
     * 命中 topK 后回写访问统计(lastAccessTime/accessCount)到 detail。
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

        List<String> detailKeys = factIdList.stream().map(id -> DETAIL_KEY_PREFIX + id).collect(Collectors.toList());
        List<String> details = this.redisTemplate.opsForValue().multiGet(detailKeys);
        List<FactEntry> entries = new ArrayList<>();

        int cachedHits = 0;
        long now = System.currentTimeMillis();
        for (int i = 0; i < factIdList.size(); i++) {
            String detail = (details != null && i < details.size()) ? details.get(i) : null;
            if (detail == null) continue;
            String[] parts = this.parseDetailParts(detail);
            String factText = parts[0];
            String sourcePhase = parts[1];

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
            long lastAccess = this.parseLong(parts, 2, DEFAULT_ACCESS_TIME);
            int accessCount = this.parseInt(parts, 3, 0);
            entries.add(new FactEntry(factIdList.get(i), factText, sourcePhase, cachedVec, lastAccess, accessCount));
        }

        if (cachedHits > 0) {
            logger.debug("长期记忆向量缓存命中: {}/{}", cachedHits, entries.size());
        }

        // 相似度计算 (使用缓存向量或实时计算) + 来源阶段加权 + 时间衰减 + 访问频率增益
        for (FactEntry entry : entries) {
            try {
                List<Float> factVector = entry.cachedVector;
                if (factVector == null) {
                    // 缓存未命中, 降级到实时Embedding
                    factVector = this.embeddingService.generateEmbedding(entry.factText);
                }
                float sim = this.cosineSimilarity(queryVector, factVector);
                double score = sim * this.sourcePhaseWeight(entry.sourcePhase)
                        * this.timeDecayWeight(now, entry.lastAccessTime)
                        * this.frequencyBoost(entry.accessCount);
                entry.similarity = (float) score;
            }
            catch (Exception e) {
                logger.debug("事实向量化失败: {} (前20字)", entry.factText.substring(0, Math.min(20, entry.factText.length())));
                entry.similarity = -1.0f;
            }
        }

        entries.sort((a, b) -> Float.compare(b.similarity, a.similarity));
        ArrayList<MemoryFact> results = new ArrayList<MemoryFact>();
        ArrayList<String> hitIds = new ArrayList<>();
        for (FactEntry entry : entries) {
            if (entry.similarity < 0.0f) continue;
            MemoryFact fact = new MemoryFact();
            fact.setFact(entry.factText);
            fact.setSourcePhase(entry.sourcePhase);
            results.add(fact);
            hitIds.add(entry.factId);
            if (results.size() >= topK) break;
        }

        // 访问统计回写: 命中的事实 accessCount+1, lastAccessTime=now (best-effort)
        if (!hitIds.isEmpty()) {
            this.bumpAccessCount(hitIds);
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

    /** 时间衰减权重: 指数半衰期, 30 天后权重减半; 无访问时间(旧数据)按满权重处理 */
    private double timeDecayWeight(long now, long lastAccess) {
        if (lastAccess <= DEFAULT_ACCESS_TIME) {
            return 1.0;
        }
        double ageDays = (now - lastAccess) / 86400000.0;
        return Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
    }

    /** 访问频率增益: 线性增长封顶, 最多 1 + CAP*FACTOR = 1.5 倍 */
    private double frequencyBoost(int accessCount) {
        int capped = Math.min(accessCount, FREQ_CAP);
        return 1.0 + capped * FREQ_FACTOR;
    }

    /** 回写访问统计: 命中事实 accessCount+1 且刷新 lastAccessTime (Redis + MySQL 尽力而为) */
    private void bumpAccessCount(List<String> hitFactIds) {
        if (hitFactIds == null || hitFactIds.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (String factId : hitFactIds) {
            try {
                String detailKey = DETAIL_KEY_PREFIX + factId;
                String detail = this.redisTemplate.opsForValue().get(detailKey);
                if (detail == null) continue;
                String[] parts = this.parseDetailParts(detail);
                String text = parts[0];
                String source = parts[1];
                long lastAccess = this.parseLong(parts, 2, now);
                int count = this.parseInt(parts, 3, 0);
                this.redisTemplate.opsForValue().set(detailKey,
                        text + "|" + source + "|" + now + "|" + (count + 1), Duration.ofDays(30L));
                // MySQL 同步(尽力而为): 命中即代表事实活跃, 用 row 更新 accessCount
                try {
                    java.util.Optional<LtmFact> rowOpt = this.ltmFactRepository.findById(factId);
                    if (rowOpt.isPresent()) {
                        LtmFact row = rowOpt.get();
                        row.setLastAccessTime(now);
                        row.setAccessCount(count + 1);
                        this.ltmFactRepository.save(row);
                    }
                } catch (Exception me) {
                    logger.trace("LTM 访问统计 MySQL 更新失败: {}", me.getMessage());
                }
            } catch (Exception e) {
                logger.trace("LTM 访问统计回写失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 记忆整合决策: 新事实 vs 已有事实。
     * 返回 DUPLICATE(重复, targetId=被覆盖的旧事实) / CONFLICT(矛盾, targetId+oldSource) / NONE(直接写入)。
     * 用向量相似度判断; 向量不可用时(缓存缺失且 embedding 失败)保守返回 NONE 避免误删。
     */
    private IntegrationDecision resolveIntegration(String newFactId, String newText, String newSource,
                                                   Set<String> existingIds, Map<String, String[]> existingDetails) {
        if (existingIds == null || existingIds.isEmpty()) return IntegrationDecision.NONE;
        for (String oldId : existingIds) {
            String[] oldParts = existingDetails.get(oldId);
            if (oldParts == null) continue;
            String oldText = oldParts[0];
            String oldSource = oldParts[1];
            // 跳过完全相同的文本(重复写入场景)
            if (oldText.equals(newText)) {
                return new IntegrationDecision(IntegrationDecisionType.DUPLICATE, oldId, oldSource);
            }
            double sim;
            try {
                sim = this.similarityBetween(oldText, newText);
            } catch (Exception e) {
                continue; // 向量不可用, 跳过该对比
            }
            if (sim >= DUP_SIM_THRESHOLD) {
                return new IntegrationDecision(IntegrationDecisionType.DUPLICATE, oldId, oldSource);
            }
            if (sim >= CONFLICT_SIM_MIN && sim <= CONFLICT_SIM_MAX && this.isContradictory(oldText, newText)) {
                return new IntegrationDecision(IntegrationDecisionType.CONFLICT, oldId, oldSource);
            }
        }
        return IntegrationDecision.NONE;
    }

    /** 计算两条事实的向量余弦相似度(取缓存向量, 缺失则实时计算) */
    private double similarityBetween(String a, String b) {
        List<Float> va = this.embeddingService.generateEmbedding(a);
        List<Float> vb = this.embeddingService.generateEmbedding(b);
        return this.cosineSimilarity(va, vb);
    }

    /** 否定词反转检测: 去掉否定词后高度相似, 且否定词出现情况不同 -> 一正一否矛盾 */
    private boolean isContradictory(String a, String b) {
        String stripA = stripNegations(a);
        String stripB = stripNegations(b);
        boolean aNeg = stripA.length() != a.length();
        boolean bNeg = stripB.length() != b.length();
        if (aNeg == bNeg) return false; // 同正同否, 不是矛盾
        // 去否定词后是否近似(用包含关系粗判, 避免再调一次 embedding)
        if (stripA.length() < 4 || stripB.length() < 4) return false;
        return stripA.contains(stripB) || stripB.contains(stripA)
                || this.jaccardSimilar(stripA, stripB) > 0.6;
    }

    private String stripNegations(String s) {
        String out = s;
        for (String w : NEGATION_WORDS) {
            out = out.replace(w, "");
        }
        return out;
    }

    /** 字符集合 Jaccard 相似度(轻量近似, 用于矛盾检测) */
    private double jaccardSimilar(String a, String b) {
        Set<Character> sa = a.chars().mapToObj(c -> (char) c).collect(Collectors.toSet());
        Set<Character> sb = b.chars().mapToObj(c -> (char) c).collect(Collectors.toSet());
        Set<Character> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<Character> union = new HashSet<>(sa);
        union.addAll(sb);
        if (union.isEmpty()) return 0.0;
        return (double) inter.size() / union.size();
    }

    private List<MemoryFact> keywordSearch(Set<String> factIds, String query, int topK) {
        ArrayList<MemoryFact> results = new ArrayList<MemoryFact>();
        for (String factId : factIds) {
            String detailKey = DETAIL_KEY_PREFIX + factId;
            String detail = this.redisTemplate.opsForValue().get(detailKey);
            if (detail == null) continue;
            String[] parts = this.parseDetailParts(detail);
            String factText = parts[0];
            String sourcePhase = parts[1];
            if (!this.containsKeyword(factText, query)) continue;
            MemoryFact fact = new MemoryFact();
            fact.setFact(factText);
            fact.setSourcePhase(sourcePhase);
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
        Set<String> factIds = this.safeSetMembers(key);
        if (factIds != null) {
            for (String factId : factIds) {
                this.redisTemplate.delete(DETAIL_KEY_PREFIX + factId);
                this.redisTemplate.delete(VEC_KEY_PREFIX + factId);  // 清理缓存向量
                // 若该事实进了全局集合, 一并移除(仅本会话持有的 factId 属于本会话)
                this.redisTemplate.opsForSet().remove(this.globalKey(userId), factId);
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

    // ---- 工具方法 ----

    private Set<String> safeSetMembers(String key) {
        try {
            return this.redisTemplate.opsForSet().members(key);
        } catch (Exception e) {
            logger.warn("读取 LTM Set 失败: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /** 批量读取已有事实的 detail(解析后), 用于整合判断 */
    private Map<String, String[]> loadExistingDetails(Set<String> factIds) {
        Map<String, String[]> map = new HashMap<>();
        if (factIds == null || factIds.isEmpty()) return map;
        List<String> keys = new ArrayList<>(factIds).stream()
                .map(id -> DETAIL_KEY_PREFIX + id).collect(Collectors.toList());
        List<String> details = this.redisTemplate.opsForValue().multiGet(keys);
        List<String> idList = new ArrayList<>(factIds);
        for (int i = 0; i < idList.size(); i++) {
            if (details != null && i < details.size() && details.get(i) != null) {
                map.put(idList.get(i), this.parseDetailParts(details.get(i)));
            }
        }
        return map;
    }

    /** 解析 detail: 兼容 4 段(新) / 2 段(旧) / 纯文本(最旧) */
    private String[] parseDetailParts(String detail) {
        if (detail == null || detail.isEmpty()) {
            return new String[]{"", SOURCE_SUMMARY, String.valueOf(DEFAULT_ACCESS_TIME), "0"};
        }
        String[] parts = detail.contains("|") ? detail.split("\\|", 4) : new String[]{detail};
        return new String[]{
                parts[0],
                (parts.length > 1 && parts[1] != null && !parts[1].isEmpty()) ? parts[1] : SOURCE_SUMMARY,
                (parts.length > 2 && parts[2] != null && !parts[2].isEmpty()) ? parts[2] : String.valueOf(DEFAULT_ACCESS_TIME),
                (parts.length > 3 && parts[3] != null && !parts[3].isEmpty()) ? parts[3] : "0"
        };
    }

    private long parseLong(String[] parts, int idx, long dflt) {        if (parts == null || idx >= parts.length) return dflt;
        try {
            return Long.parseLong(parts[idx].trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private int parseInt(String[] parts, int idx, int dflt) {
        if (parts == null || idx >= parts.length) return dflt;
        try {
            return Integer.parseInt(parts[idx].trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    /** 删除单条事实: Set 移除 + detail/vec 删除 + MySQL 删除(尽力而为) */
    private void removeFact(Long userId, String sessionId, String factId) {
        try {
            this.redisTemplate.opsForSet().remove(this.sessionKey(userId, sessionId), factId);
            this.redisTemplate.opsForSet().remove(this.globalKey(userId), factId);
            this.redisTemplate.delete(DETAIL_KEY_PREFIX + factId);
            this.redisTemplate.delete(VEC_KEY_PREFIX + factId);
        } catch (Exception e) {
            logger.trace("删除事实 {} Redis 键失败: {}", factId, e.getMessage());
        }
        try {
            long uid = userId != null ? userId : 0L;
            this.ltmFactRepository.deleteById(factId);
        } catch (Exception e) {
            logger.trace("删除事实 {} MySQL 行失败: {}", factId, e.getMessage());
        }
    }

    /** 整合决策枚举 + 携带目标 factId/来源 */
    private enum IntegrationDecisionType { NONE, DUPLICATE, CONFLICT }

    private static class IntegrationDecision {
        static final IntegrationDecision NONE = new IntegrationDecision(IntegrationDecisionType.NONE, null, null);
        final IntegrationDecisionType type;
        final String targetId;
        final String oldSource;
        IntegrationDecision(IntegrationDecisionType type, String targetId, String oldSource) {
            this.type = type; this.targetId = targetId; this.oldSource = oldSource;
        }
        boolean isDuplicate() { return this.type == IntegrationDecisionType.DUPLICATE; }
        boolean isConflict() { return this.type == IntegrationDecisionType.CONFLICT; }
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
        final String factId;
        final String factText;
        final String sourcePhase;
        final List<Float> cachedVector;  // 预缓存的向量 (可为null)
        final long lastAccessTime;
        final int accessCount;
        float similarity = 0.0f;

        FactEntry(String factId, String factText, String sourcePhase, List<Float> cachedVector,
                  long lastAccessTime, int accessCount) {
            this.factId = factId;
            this.factText = factText;
            this.sourcePhase = sourcePhase;
            this.cachedVector = cachedVector;
            this.lastAccessTime = lastAccessTime;
            this.accessCount = accessCount;
        }
    }
}
