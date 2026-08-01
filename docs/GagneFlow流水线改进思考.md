# GagneFlow 流水线改进思考与实施

> 评估日期：2026-07-29 | 实施日期：2026-07-29

---

## 改进项评估与实施结果

| # | 改进项 | 可行性 | 价值 | 风险 | 决策 |
|:--|:------|:------:|:----:|:----:|:----:|
| 1 | 教案回灌文档库 | ✅ 高 | ⭐⭐⭐⭐ | 🟢 低 | **已实施** |
| 2 | 增量重生成 | ⚠️ 需前端 | ⭐⭐⭐⭐⭐ | 🟡 中 | **搁置** |
| 3 | Review评分回灌Prompt | ✅ 高 | ⭐⭐ | 🟢 低 | **已实施** |
| 4 | Analysis阶段上下文缓存 | ✅ 高 | ⭐⭐⭐ | 🟢 低 | **已实施** |

---

## 改进一：教案回灌文档库 ✅ 已实施

### 核心设计

```
生成教案 HTML
  ↓
HITL 检查 ← needsHumanReview=true → 跳过回灌
  ↓
评分检查 ← score < 70 → 跳过回灌
  ↓
相似度检查 ← 已有近似内容 → 跳过回灌
  ↓
strip HTML → chunkDocument 分片
  ↓
向量化 → Milvus insert（标记 source=generated_lesson_plan）
```

### 改动文件

| 文件 | 操作 | 说明 |
|------|:----:|------|
| `service/vector/VectorIndexService.java` | 新增方法 | `indexLessonPlan()` (L195-270) — 三级过滤+分片+写入 |
| `controller/LessonController.java` | 修改 | `executeAddiePipeline()` L162-167 调用回灌，HITL 检查 L148-161 |

### 关键修正（vs 原始提案）

1. **chunkDocument 签名不是 (text, docId)** — 实际是 `(String content, String filePath)`，第二个参数是标识字符串
2. **不需要独立的 insertVector 方法** — 直接复用现有 Milvus insert 逻辑
3. **相似度使用 searchSimilarDocuments** — L2距离转换的score，>0.95表示高度相似
4. **metadata 构建方式** — 通过 Gson JsonObject，与现有 buildMetadata 风格一致

---

## 改进二：增量重生成 ⚠️ 搁置

### 搁置原因

- 需要前端 Copilot 协议扩展（`revise:development:...` 格式）
- 需要 AddieResult 缓存机制（ConcurrentHashMap by session）
- 前端改动跨团队，风险不可控
- 实际使用频率存疑（大部分用户一次性生成完整教案）

### 未来重访条件

- 前端已完成对应协议扩展
- AddiePipeline.execute() 增加 `@Nullable String rerunFromStage` 参数

---

## 改进三：Review 评分回灌 Prompt ✅ 已实施

### 核心设计

在 `asyncReview()` 的评分提取后，调用 `PromptMetricsCollector.recordScore("addie_review", version, score)`，将评价数据汇聚到 Prompt 版本管理。

### 改动文件

| 文件 | 操作 | 说明 |
|------|:----:|------|
| `service/lesson/AddiePipeline.java` | 修改 `asyncReview()` | 评分后记录到 PromptMetricsCollector |

---

## 改进四：Analysis 阶段上下文缓存 ✅ 已实施

### 核心设计

同一用户对相同（学段+年级+学科）的 Analysis 结果缓存到 Redis（TTL 1h），避免重复 LLM 调用。

```
请求进入
  ↓
查 Redis: gagneflow:analysis:cache:{uid}:{stage}:{grade}:{subject}
  ↓
命中 → 跳过 Analysis LLM 调用，直接使用缓存输出
  ↓
未命中 → 执行 Analysis → 写入 Redis
```

### 实现方案（vs 原始提案）

原始提案建议在 AddiePipeline 注入 RedisTemplate，但这会改变构造函数签名（虽然 Spring 自动处理，但增加了耦合）。

**实际采用方案**：在 `AddiePipeline` 构造函数注入 `StringRedisTemplate`，在 `execute()` 方法 L129-143 实现缓存逻辑。

```text
AddiePipeline.execute() L129-143:
  1. buildAnalysisCacheKey(userId, request) → key
  2. tryGetCachedAnalysis(key) → 命中则跳过 LLM 调用
  3. callStageWithRevise(...) → 未命中则执行 Analysis
  4. tryCacheAnalysis(key, result.analysis) → 写入 Redis (TTL 1h)
```

**HITL 过滤**：教案回灌前由 `LessonController.executeAddiePipeline()` L148-161 判断 `result.needsHumanReview`：为 true 时跳过回灌并植入 HTML 警告，为 false 时调用 `vectorIndexService.indexLessonPlan()` (L162-167)。

### 缓存 Key 设计

```
gagneflow:analysis:cache:{userId}:{stage}:{grade}:{subject}
```

TTL: 3600 秒（1小时）

---

## 实施总结

三改进均已完成，纯后端改动，不依赖前端，不影响现有流程：

1. **教案回灌**：~110 行 VectorIndexService (L195-270) + ~15 行 LessonController (L148-167)
2. **Review评分回灌**：~5 行 AddiePipeline.asyncReview() (L289-297)
3. **Analysis缓存**：~30 行 AddiePipeline.execute() (L129-143) + 3 个辅助方法 (L484-508)

全部三级过滤（HITL→评分→相似度），回灌失败不影响主流程（被 try-catch 包裹）。

---

## 测试覆盖

| 测试项 | 测试类 | 覆盖内容 |
|--------|--------|----------|
| `buildAnalysisCacheKey` | `AddiePipelineTest` | key 格式 `gagneflow:analysis:cache:{uid}:{stage}:{grade}:{subject}` 构建正确性 |
| HITL 过滤覆盖 | `AddiePipelineTest$HumanReviewTests` | 已有 10 个测试：4 条 HITL 规则 + needsHumanReview 标志位一致性 + 正常/边界覆盖 |
| `indexLessonPlan` | 暂无 | 依赖 Milvus/Embedding 外部服务，适合集成测试环境覆盖 |
| `buildLessonPlanMetadata` | 暂无 | 纯逻辑方法，后续可补充单元测试验证 metadata 字段完整性 |
| Review评分回灌 | 暂无 | `asyncReview()` 为异步复杂方法，适合 E2E/集成测试覆盖 |

> 注：`indexLessonPlan` 回灌逻辑经 try-catch 包裹（LessonController L162-167），Milvus 不可用时静默降级，不影响教案生成主流程。
