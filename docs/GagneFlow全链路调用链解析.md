# GagneFlow 全链路调用链解析

> 本文档系统梳理项目全部核心调用链，阐明向量流转全路径、Redis 职责边界及各存储组件分工。

---

## 第一章：存储组件分工总览

### 1.1 三层存储模型

```
┌──────────────────────────────────────────────────────────────────────┐
│  MySQL (8.0) — 持久化层 / 最终一致性源                             │
│  - User          → 用户凭据 (BCrypt 密文)                          │
│  - SessionMeta   → 会话元数据 (标题/创建时间)                      │
│  - SessionMessage→ 消息归档 (role/content/timestamp)               │
│  - PromptVersion → Prompt版本记录 (version/content/active)          │
│  - 适用场景: 用户登录、会话列表查询、Prompt版本管理、数据备份      │
├──────────────────────────────────────────────────────────────────────┤
│  Redis (7) — 高速缓存层 / 会话主存储 / 并发控制                     │
│  - 会话数据 (主存储，MySQL仅归档):                                   │
│    gagneflow:chat:session:{uid}:{sid}        → Hash (消息列表+元数据)│
│  - 长期记忆事实:                                                     │
│    gagneflow:ltm:{uid}:{sid}                 → Set (事实ID集合)      │
│    gagneflow:ltm:detail:{factId}             → String (事实文本,30d) │
│    gagneflow:ltm:vec:{factId}                → String (向量JSON,30d) │
│  - Analysis阶段缓存:                                                  │
│    gagneflow:cache:analysis:{hash}           → String (分析结果,1h)  │
│  - 限流:                                                             │
│    gagneflow:rl:{ip}:{path}                  → ZSET (滑动窗口)       │
│  - JWT黑名单:                                                        │
│    gagneflow:jwt:blacklist:{jti}             → String (撤销标记)     │
│  - 分布式锁:                                                         │
│    gagneflow:lock:lesson:{userId}            → String (SETNX,300s)   │
├──────────────────────────────────────────────────────────────────────┤
│  Milvus (2.5.10) — 向量检索层 (唯一向量存储位置)                    │
│  Collection: biz                                                    │
│  索引: IVF_FLAT, nlist=1024, MetricType=L2                          │
│  向量维度: 1024 (text-embedding-v4)                                  │
│  - 文档分片向量 (上传的PDF/Word/md/txt)                              │
│  - K12 课标向量 (启动时预加载)                                       │
│  - 回灌教案向量 (ADDIE Review评分≥70)                                │
│  Schema: id(str) + content(str) + vector(float[]) + metadata(JSON)  │
└──────────────────────────────────────────────────────────────────────┘
```

### 1.2 Redis 边界声明

> **Redis 不参与向量存储，不参与向量检索。** 这是理解项目架构的第一原则。

Redis 中的 `gagneflow:ltm:vec:{factId}` 存储的是文本嵌入的 **JSON 序列化字符串**（如 `[0.012, -0.034, ...]`），用于 **应用层的余弦相似度计算** （`LongTermMemoryService.cosineSimilarity`，L195-211）。这个计算过程完全在 JVM 内存中完成，不经过 Milvus 索引，不构成向量检索体系。

| 组件 | 向量职责 | 检索方式 |
|------|---------|---------|
| Milvus | 文档/K12/教案向量存储+检索 | IVF_FLAT + L2 距离 |
| Redis | 长期记忆**事实文本**存储 + 向量**缓存** | 不参与检索，仅提供序列化数据 |
| JVM | 长期记忆余弦相似度计算 | `cosineSimilarity()` 纯内存计算 |

### 1.3 向量唯一存储位置

向量数据**仅**存储在 Milvus 的 `biz` collection 中。long-term memory 向量缓存在 Redis 中仅作为应用层计算的加速缓存，一旦 Redis 数据丢失，仅影响长期记忆检索速度（降级为实时 Embedding），不影响 Milvus 文档检索。

---

## 第二章：文档向量化完整调用链（向量在项目中完整的生命周期）

### 2.1 流程图

```
  ┌───────────────┐     ┌────────────────┐     ┌─────────────────┐
  │ 文件上传       │     │ K12启动初始化   │     │ ADDIE教案回灌    │
  │ POST /upload  │     │ @EventListener  │     │ score≥70触发     │
  └───────┬───────┘     └───────┬────────┘     └────────┬────────┘
          │                     │                       │
          ▼                     ▼                       ▼
  ┌──────────────────────────────────────────────────────────────┐
  │                   VectorIndexService                         │
  │  indexSingleFile()  /  indexLessonPlan()                     │
  └───────┬───────────────┬──────────────────┬──────────────────┘
          │               │                  │
          ▼               ▼                  ▼
  ┌──────────────┐  ┌────────────┐  ┌──────────────────┐
  │ 1. 读文件    │  │ 2. 分片    │  │ 3. Embedding     │
  │ DocumentReader│  │ DocumentChunk│  │ DashScope API   │
  │ Factory.get()│  │ Service     │  │ text-embedding-v4│
  └──────────────┘  └────────────┘  └────────┬─────────┘
                                             │
                                             ▼
                                   ┌──────────────────┐
                                   │ 4. L2 归一化     │
                                   │ normalizeL2()    │
                                   └────────┬─────────┘
                                            │
                                            ▼
                                   ┌──────────────────┐
                                   │ 5. Milvus insert │
                                   │ collection="biz" │
                                   └──────────────────┘
```

### 2.2 文档上传→向量化完整路径

```
POST /api/files/upload
  → FileUploadController.uploadFile()                         [controller/FileUploadController]
    → 校验: 文件类型∈{txt,md,pdf,docx}, 大小≤50MB
    → 保存: uploads/{uuid}.{ext}                              [本地文件系统]
    → VectorIndexService.indexSingleFile(filePath, userId)  [service/vector/VectorIndexService]
      
      Step 1: 获取 Reader
        → DocumentReaderFactory.getReader(extension)          [service/reader/]
          → 策略模式返回: PdfDocumentReader / WordDocumentReader /
                         MarkdownDocumentReader / PlainTextDocumentReader
        → reader.readText(path)                               → String (全文文本)

      Step 2: 内容分片
        → DocumentChunkService.chunkDocument(content, path)   [service/document/DocumentChunkService]
          → normalize(): 统一换行符, 移除零宽字符, 折叠连续空行
          → splitByHeadings(): 按 Markdown 标题 (#/##/###) 切分章节
            → 构建面包屑路径: "第一章 > 第一节 > 知识点A"
          → 每章=1个Section, 超长章节按段落再切
          → chunkSection(section, startIndex):
            → 800字符/片 (configurable via DocumentChunkConfig.maxSize)
            → 100字符重叠 (configurable via DocumentChunkConfig.overlap)
            → 表格保护: 检测 |---|---| 模式, 窗口扩大至1.5倍(1200字符)
          → 返回 List<DocumentChunk>

      Step 3: 向量化 (逐分片循环)
        → for each DocumentChunk:
          → VectorEmbeddingService.generateEmbedding(content)  [service/vector/VectorEmbeddingService]
            → DashScope text-embedding-v4 API                   [外部 API / DashScope]
            → 返回 List<Double> → List<Float>
            → **L2归一化**: normalizeL2(vector)                  [关键修复 P0]
              → norm = √(Σvᵢ²)
              → vᵢ = vᵢ / norm   (单位向量, L2距离等价余弦相似度)
            → 返回 1024 维 float 向量
          → buildMetadata(path, chunk, total, docMeta, userId)
            → metadata = { _user_id, _source, _extension, _file_name,
                           chunkIndex, totalChunks, title, ... }
            → 转换为 Gson JsonObject (Milvus SDK 要求)

      Step 4: 清理旧数据
        → deleteExistingData(normalizedPath)
          → Milvus Delete: metadata["_source"] == "{normalizedPath}"  [Milvus]

      Step 5: 批量写入 Milvus
        → loadCollectionIfNeeded()                                [Milvus]
        → InsertParam: collection="biz"
          → fields: [ id(UUID), content(str), vector(float[]), metadata(JSON) ]
        → milvusClient.insert(insertParam)                        [Milvus]
```

**关键修正：** 先删除旧数据再插入新数据（L161-167），避免 `_source` 条件误删刚插入的数据。

### 2.3 K12 知识库初始化链路

```
ApplicationReadyEvent
  → K12VectorInitializer.onApplicationEvent()               [service/document/K12VectorInitializer]
    → K12CurriculumLoader.load()                             [service/document/K12CurriculumLoader]
      → 解析 lesson-plan-docs/k12_curriculum.json → 课标文本段
    → SubjectFormatLoader.load()                             [service/document/SubjectFormatLoader]
      → 解析 lesson-plan-docs/subject-formats.json → 学科格式模板
    → 遍历 templates/ 目录 (初中语文.json, 小学数学.json ...)
    → 对每个条目:
      → DocumentChunkService.chunkDocument()                 [复用分片逻辑]
      → VectorEmbeddingService.generateEmbedding()           [DashScope API]
      → Milvus insert                                        [Milvus]
        → metadata._source = "k12_curriculum"
```

### 2.4 教案回灌链路

```
ADDIE Pipeline → Review 阶段评分 ≥ 70
  → LessonController.executeAddiePipeline()                 [controller/LessonController]
    → 前置检查: needsHumanReview=true → 跳过 (不回灌)
    → 前置检查: score < 70 → 跳过
    → VectorIndexService.indexLessonPlan(html, userId, subject, score)  [service/vector/VectorIndexService]

      Step 1: 提取纯文本
        → strip HTML tags + decode HTML entities + collapse whitespace
        → 长度 < 100字符 → 跳过

      Step 2: 相似度去重
        → 取前300字符为 probe
        → VectorSearchService.searchSimilarDocuments(probe, 1)  [Milvus 全库搜索]
        → 最高分 > 0.98 && 来源为 "generated_lesson_plan" → 跳过 (重复教案)
        → 最高分 > 0.98 && 来源为原始文档 → 允许回灌 (教案引用教材正常)

      Step 3: 分片 → 向量化 → Milvus insert (同 2.2 Step 2-5)
        → metadata._source = "generated_lesson_plan"
        → metadata._user_id / _subject / _score / _lesson_plan = "true"
```

### 2.5 向量流转关键节点

| 步骤 | 数据形态 | 处理组件 | 存储位置 |
|------|---------|---------|---------|
| 原始文档 | 二进制 | 文件系统 | `uploads/` |
| 文本提取 | `String` | DocumentReader | 内存 |
| 标题分片 | `List<DocumentChunk>` | DocumentChunkService | 内存 |
| 向量化 | `List<Float>[1024]` | VectorEmbeddingService | DashScope API 返回 |
| L2归一化 | `List<Float>[1024]` (单位向量) | normalizeL2() | 内存 |
| **向量持久化** | `float[1024]` (Milvus 二进制存储) | MilvusClient.insert() | **Milvus biz collection** |
| 元数据 | Gson JsonObject | buildMetadata() | Milvus metadata 字段 |
| LTM向量缓存 | `JSON string` | objectMapper | **Redis** (仅缓存, 非检索) |

---

## 第三章：RAG 检索问答完整调用链

### 3.1 流程图

```
  ┌──────────────────────────────────────────────────────────────────┐
  │  POST /api/rag/query                                              │
  │  → RateLimitInterceptor [限流]                                   │
  │  → RagController.query()                                         │
  └────────────────────────────────┬─────────────────────────────────┘
                                   ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  Step 1: 查询改写                                                │
  │  QueryRewriter.rewrite(question, history)                        │
  │  [规则路径] 短查询拼接最近消息 → 关键词提取                        │
  │  [LLM路径] 检测指代词 → qwen-turbo 改写                          │
  └────────────────────────────────┬─────────────────────────────────┘
                                   ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  Step 2: 向量搜索 (VectorSearchService)                          │
  │  → searchWithAdaptiveNprobe(query, topK=15, userId)              │
  │    ├─ generateQueryVector() → DashScope text-embedding-v4        │
  │    ├─ doSearch(nprobe=16) → Milvus L2 搜索                       │
  │    ├─ 召回不足? → nprobe=32 → nprobe=64 (自适应扩召)              │
  │    └─ L2距离→相似度: score = 1/(1+distance)                      │
  └────────────────────────────────┬─────────────────────────────────┘
                                   ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  Step 3: 重排序 (RerankService)                                  │
  │  → rerank(query, documents, topN=3)                              │
  │    ├─ [正常] POST DashScope gte-rerank API → 精排结果             │
  │    └─ [熔断] @CircuitBreaker → rerankFallback → 原始顺序          │
  └────────────────────────────────┬─────────────────────────────────┘
                                   ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  Step 4: 流式生成                                                │
  │  → RagService.generateAnswer(query, context, emitter)            │
  │    ├─ buildContextWithCitations(): "[1] 文档..."                  │
  │    ├─ DashScopeChatModel.stream() → qwen-max-latest              │
  │    └─ SSE 逐 token 推送 → SseEmitter.send()                      │
  └──────────────────────────────────────────────────────────────────┘
```

### 3.2 逐步骤详解

**Step 1: 查询改写 (`QueryRewriter.rewrite()`)**
```
输入: question="它的内角和是多少", history=[{user:"三角形的定义",assistant:"三角形是..."}]
输出: rewritten="三角形的内角和是多少"

[规则路径]:
  → 拼接历史: 若question < 5个字 → 拼接最近一条用户消息
  → 关键词提取: 提取history中的学科实体
  
[LLM路径] (默认关闭: rag.query-rewrite.llm-enabled=false):
  → shouldUseLlm(): 检测"它/这个/那个/上述"等指代词 → 触发
  → rewriteWithLlm(): qwen-turbo → "将以下问题补充完整..."
```

**Step 2: 向量搜索 (`VectorSearchService.searchWithRerank()`)**

`VectorSearchService` 的 L77 构建 Milvus 搜索参数：

```java
SearchParam.Builder builder = SearchParam.newBuilder()
    .withCollectionName("biz")
    .withVectorFieldName("vector")
    .withVectors(singletonList(queryVector))
    .withTopK(15)                           // 搜索15条候选
    .withMetricType(MetricType.L2)          // L2 距离
    .withOutFields(List.of("id", "content", "metadata"))
    .withParams("{\"nprobe\":16}");         // IVF_FLAT 搜索簇数

// 用户过滤表达式 (L79):
String expr = "metadata[\"_user_id\"] == \"42\" || " +
              "metadata[\"_source\"] == \"k12_curriculum\" || " +
              "not exists metadata[\"_user_id\"]";
```

自适应 nprobe 机制：如果 `searchTopK=15` 但返回不足 15 条结果，自动将 nprobe 从 16 提升至 32、64，扩大搜索范围。

**Step 3: 重排序 (`RerankService.rerank()`)**

```
输入: query + 15条文档 + topN=3
输出: 3条精排结果 (按 relevance_score 降序)

[正常路径] (L70-124):
  → POST https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
  → Authorization: Bearer {apiKey}
  → 请求体: { model: "gte-rerank", input: { query, documents }, parameters: { top_n: 3 } }
  → 解析响应 → 按 relevance_score 降序排列

[熔断降级路�] (L130-136, @CircuitBreaker(name="dashscope")):
  → callback rerankFallback()
  → 返回原始顺序的前 topN 条, relevanceScore=0.0
```

**Step 4: 流式生成**

```
RagService 构建含引用的 Prompt:
  "请基于以下文档回答用户问题，并在回答中标注引用来源 [1][2]...\n\n"
  "[1] 文档1内容...\n[2] 文档2内容...\n[3] 文档3内容..."

→ DashScopeChatModel.stream(systemPrompt + userQuestion)
→ Flowable.subscribe:
  onNext(token) → emitter.send(SseEmitter.event().name("token").data(token))
  onComplete() → emitter.send(SseEmitter.event().name("done"))
  onError(e) → emitter.send(SseEmitter.event().name("error").data(e.getMessage()))
```

### 3.3 RAG 链路中的存储交互

| 操作 | 存储 | 详细路径 |
|------|------|---------|
| 会话历史 | Redis → MySQL fallback | `ChatSessionService.getFromRedis()` → 若null则 `getOrCreate()` |
| 查询向量化 | DashScope API (外部) | `VectorEmbeddingService.generateQueryVector()` |
| 向量检索 | Milvus | `biz` collection, L2 距离, nprobe 自适应 |
| 重排序 | DashScope API (外部) | `RerankService.rerank()` |
| 答案生成 | DashScope API (外部) | `DashScopeChatModel.stream()` |
| 指标记录 | Micrometer (JVM 内存) | `PipelineMetrics.recordRagSearch()` |

---

## 第四章：ADDIE 教案生成完整调用链

### 4.1 流程图

```
┌──────────────────────────────────────────────────────────────────────┐
│  POST /api/lesson_plan                                                │
│  → RateLimitInterceptor [限流]                                        │
│  → LessonController.generate()                                        │
│    → 参数校验: stage/grade/subject/hours/goals                        │
│    → JVM锁: lessonLocks.putIfAbsent(uid, true)                        │
│    → Redis锁: setIfAbsent("gagneflow:lock:lesson:{uid}", 60s)         │
│    → executor.submit(() -> executeAddiePipeline(...))                  │
│    → return SseEmitter(600000L) [同步返回]                            │
└──────────────────────────────────┬───────────────────────────────────┘
                                   ▼ (后台线程)
┌──────────────────────────────────────────────────────────────────────┐
│  AddiePipeline.execute(request, model, emitter, mode, ...)            │
│                                                                      │
│  ┌──────────────────┐    ┌──────────────────┐    ┌────────────────┐  │
│  │ ANALYSIS         │    │ DESIGN ║ DEV     │    │ FORMAT         │  │
│  │ (串行, 60s, 2次) │───▶│ (并行, 240s/180s)│───▶│ (串行, Format- │  │
│  │ → K12课标        │    │ → Completable ▸  │    │  Tool.format)  │  │
│  │ → Redis缓存检查  │    │   Future.allOf() │    │ → SSE推送HTML  │  │
│  │ → LLM生成分析    │    │ → SSE各阶段      │    └────────┬───────┘  │
│  └──────────────────┘    └──────────────────┘             │           │
│                                                            │           │
│  ┌─────────────────────────────────────────────────────────┘           │
│  │  REVIEW (异步, CompletableFuture.runAsync)                         │
│  │  → 5维度评分 (20+30+20+20+10=100)                                 │
│  │  → <70分→重试(最多2次), <60分→HITL                               │
│  │  → ≥70分→回灌Milvus (送VectorIndexService)                        │
│  └────────────────────────────────────────────────────────────────────┘
└──────────────────────────────────────────────────────────────────────┘
```

### 4.2 逐阶段详解

**Phase 1: ANALYSIS (AddiePipeline L128-145)**
```
→ loadK12Context(request) → K12CurriculumLoader.lookup(stage, grade, subject)
→ Redis缓存检查: buildAnalysisCacheKey(userId, req)
  → key = "gagneflow:analysis:cache:{userId}:{stage}:{grade}:{subject}:{goalsHash}"
  → 命中 → 跳过 LLM 调用
  → 未命中 → LLM生成 → tryCacheAnalysis(key, result)
→ DashScopeChatModel.call(systemPrompt + initialInput)  [qwen-plus]
```

**Phase 2-3: DESIGN + DEVELOPMENT 并行 (AddiePipeline L165-200)**
```
CompletableFuture<Void> designFuture = CompletableFuture.runAsync(() -> {
    result.design = this.callStageWithRevise("addie_design", ...);
}, executor);

CompletableFuture<Void> devFuture = CompletableFuture.runAsync(() -> {
    result.development = this.callStageWithRevise("addie_development", ...);
}, executor);

CompletableFuture.allOf(designFuture, devFuture).get(240, TimeUnit.SECONDS);
```

Design Development 无数据依赖（两者均只依赖 Analysis 输出），此为并行原因。

**Phase 4: FORMAT (AddiePipeline L212-213)**
```
result.html = this.formatTool.format(result.analysis, result.design, result.development, "");
→ Markdown → HTML (含 A4 打印样式)
→ SSE推送: {"type": "stage:format", "content": html, "done": true}
```

**Phase 5: REVIEW 异步 (AddiePipeline L217-234)**
```
CompletableFuture<Void> reviewTask = CompletableFuture.runAsync(() -> {
    this.asyncReview(result, chatModel, devPrompt, emitter, ...);
}, executor);
this.reviewFuture = reviewTask;
```

`asyncReview()` 内部 (L287-311):
```
→ loadPrompt("addie_review", userId)
→ callAgent(chatModel, reviewPrompt, result.development, ...)
→ extractScore(review)  → 正则提取 "总分: XX" 或 JSON "score"
→ promptMetrics.recordScore("addie_review", version, result.score)
→ 评分 < 70 → 重试 (最多2次, 带修改反馈)
→ 评分 ≥ 70 → 通过
```

**回灌触发 (LessonController L161-167)**:
```
if (result.score >= 70 && !result.needsHumanReview) {
    try {
        this.vectorIndexService.indexLessonPlan(html, uid, req.getSubject(), result.score);
    } catch (Exception e) {
        logger.warn("教案回灌失败（不影响主流程）: {}", e.getMessage());
    }
}
```

### 4.3 Copilot 交互链路

```
用户前端 → SSE推送 ("stage:analysis", "pause:true")
  → AddiePipeline.emitCopilotAwait(token, emitter, stage, queue)
    → 推送: {"event":"copilot_await","token":"...","stage":"analysis"}
    → BlockingQueue<String>.take() 阻塞等待
      → 用户响应 → POST /api/lesson_plan/action
        → LessonController.handleAction()
          → queue.offer(userAction)   // 解除阻塞
      → 超时 120s → 自动 "continue"
    → 返回: "revise:xxx" → 重新执行; "continue" → 下一步; "terminate" → 中断
    → finally: copilotQueues.remove(token)
```

### 4.4 ADDIE 链路 Redis 使用清单

| Key | 类型 | TTL | 用途 |
|-----|------|-----|------|
| `gagneflow:lock:lesson:{uid}` | String | 300s | 分布式锁 (SETNX) |
| `gagneflow:cache:analysis:{hash}` | String | 3600s | Analysis 阶段缓存 |
| `gagneflow:lock:copilot:{uid}:{sid}` | String | 120s | Copilot 等待锁 |

---

## 第五章：对话与会话管理完整调用链

### 5.1 对话链路

```
POST /api/chat_stream
  → RateLimitInterceptor [限流: 10次/60s]                     [config/RateLimitInterceptor]
  → ChatController.chat()                                      [controller/ChatController]
    → SseEmitter(600000L)
    → 创建心跳: ScheduledExecutorService(30s间隔, "ping")       [新增修复]
    
    → 后台线程:
    → ChatSessionService.getOrCreate(userId, sessionId)        [service/chat/ChatSessionService]
      → Redis: get "gagneflow:chat:session:{uid}:{sid}"       [Redis]
      → 若 null → 创建新 ChatSession → Redis SET
      → 刷新 TTL (会话有效期)
    
    → ConversationMemoryManager.buildFullContext()             [service/memory/ConversationMemoryManager]
      → getHistory(): 短期窗口 (最近6轮)                       [Redis: ChatSession.messages]
      → 若 Token > 2000 → trimByTokenBudget() (裁剪优先保留最新消息)
      → LongTermMemoryService.searchFacts()                   [Redis LTM: 向量/关键词检索]
        → Redis: SMEMBERS "gagneflow:ltm:{uid}:{sid}"         [Redis]
        → vectorSearch(): 余弦相似度 (缓存向量 或 实时Embedding) [Redis + JVM]
        → 返回 Top-3 事实 → 注入 System Prompt
    
    → ChatService.buildSystemPrompt(history, longTermContext) [service/chat/ChatService]
    → ReactAgent.create(chatModel, systemPrompt, tools)        [Spring AI Alibaba]
      → tools: DateTimeTools.getCurrentTime(), InternalDocsTools.query()
    
    → agent.stream(userMessage)                                [DashScopeChatModel → SSE]
      → Flowable<GenerationResult>
      → onNext: emitter.send(event.name("token").data(token))
      → onComplete: emitter.send(event.name("done"))
    
    → ChatSessionService.addMessage(role, content)             [service/chat/ChatSessionService]
      → Redis: 更新会话消息列表                                [Redis]
      → ChatSessionService.saveMessage()                       [MySQL异步双写]
        → SessionMessageRepository.save(entity)                [MySQL]
    
    → ConversationMemoryManager.triggerSummary()               [条件触发]
      → pairCount > 5 && newPairs ≥ 3
      → LLM 压缩: qwen-plus → "将对话压缩为不超过300字的摘要"
      → 摘要替换历史消息
```

### 5.2 会话管理链路

```
GET /api/sessions
  → SessionController.listSessions(userId)                      [controller/SessionController]
    → SessionMetaRepository.findAllByUserId(userId)             [MySQL]

DELETE /api/sessions/{id}
  → SessionController.deleteSession(id, userId)
    → SessionMetaRepository.delete(id)                         [MySQL]
    → SessionMessageRepository.deleteBySessionId(sessionId)    [MySQL]
    → ChatSessionService.deleteSession(userId, sessionId)
      → Redis: DEL "gagneflow:chat:session:{uid}:{sid}"        [Redis]
      → Redis: DEL "gagneflow:ltm:{uid}:{sid}"                 [Redis]
      → LongTermMemoryService.clearSessionFacts()               [Redis]
        → DEL all "gagneflow:ltm:detail:{factId}"               [Redis]
        → DEL all "gagneflow:ltm:vec:{factId}"                  [Redis]
```

### 5.3 对话链路存储交互

| 操作 | 存储 | 路径 |
|------|------|------|
| 会话读取 | Redis | `getFromRedis()` → `getOrCreate()` 兜底 |
| 消息持久化 | MySQL | `SessionMessageRepository.save()` |
| 短期窗口 | Redis | `ChatSession.messages` (List) |
| 长期记忆事实 | Redis | `gagneflow:ltm:*` |
| 长期记忆向量 | Redis (缓存) / DashScope (实时) | `gagneflow:ltm:vec:*` |

---

## 第六章：认证与安全完整调用链

### 6.1 登录链路

```
POST /api/auth/login
  → RateLimitInterceptor [限流: 5次/60s]                             [config/RateLimitInterceptor]
  → AuthController.login()                                            [controller/AuthController]
    → UserService.authenticate(username, password)                    [service/UserService]
      → UserRepository.findByUsername(username)                       [MySQL]
      → 未找到 → throw AuthenticationException
      → 找到 → BCryptPasswordEncoder.matches(password, storedHash)    [BCrypt]
        → 不匹配 → throw AuthenticationException
    
    → JwtUtil.generateAccessToken(userId, username)                   [config/security/JwtUtil]
      → JWT Header: { alg: "HS256", typ: "JWT" }
      → JWT Payload: { sub: username, userId, iat, exp: +1h, jti: UUID }
      → JWT 签名: HMAC-SHA256(secret)
    
    → JwtUtil.generateRefreshToken(userId, username)
      → JWT Payload: { sub: username, userId, iat, exp: +7d, jti: UUID }
      → 添加 jti 到 Redis: "gagneflow:jwt:refresh:{userId}:{jti}"     [Redis]
    
    → 返回: { accessToken, refreshToken, username }
```

### 6.2 请求过滤链路

```
每次请求:
  → JwtAuthFilter.doFilterInternal(request, response, chain)          [config/security/JwtAuthFilter]
    → 提取 Authorization: Bearer {token}
    → JwtUtil.parseToken(token) → Claims (sub, userId, jti, iat, exp)
    → JwtUtil.isTokenRevoked(jti)                                     [config/security/JwtUtil]
      → Redis: hasKey "gagneflow:jwt:blacklist:{jti}"                 [Redis]
      → jti==null → return false (旧版兼容)
      → 存在 → revoked=true → throw AuthenticationException
    → validateToken(claims) → 验证签名 + 未过期
    → 构建 UsernamePasswordAuthenticationToken, 设置 SecurityContextHolder
    → filterChain.doFilter(request, response)

  → RateLimitInterceptor.preHandle(request, response, handler)        [config/RateLimitInterceptor]
    → 解析 path, userId/IP → buildKey
    → Redis Lua 脚本 (内联, L45-66):
      ```
      local key = KEYS[1]
      local limit = tonumber(ARGV[1])
      local window = tonumber(ARGV[2])
      local now = redis.call('TIME')[1] * 1000 + redis.call('TIME')[2]/1000
      redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
      local count = redis.call('ZCARD', key)
      if count >= limit then
        return { 0, redis.call('TTL', key) }
      end
      redis.call('ZADD', key, now, now)
      redis.call('EXPIRE', key, math.ceil(window/1000))
      return { 1, -1 }
      ```
    → 结果[0]==0 → 429 Too Many Requests                          [返回 429]
    → Lua脚本失败 → 降级放行 (catch异常, logger.warn)                [降级策略]

  → Controller 处理
```

### 6.3 注销链路

```
POST /api/auth/logout
  → AuthController.logout()                                           [controller/AuthController]
    → 从 Header 提取 accessToken
    → JwtUtil.parseToken(token) → 提取 jti + exp
    → JwtUtil.invalidateToken(token)
      → Redis: SET "gagneflow:jwt:blacklist:{jti}" "revoked"         [Redis]
      → TTL = exp - now (与 access token 剩余有效期一致)
    → 清理 refresh token:
      → Redis: DEL "gagneflow:jwt:refresh:{userId}:{jti}"            [Redis]
    → SecurityContextHolder.clearContext()
    → 返回 { success: true }
```

---

## 第七章：记忆系统完整调用链

### 7.1 三层记忆架构

```
  ┌────────────────────────────────────────────────────────────┐
  │  三层记忆协同 (ConversationMemoryManager)                  │
  │                                                            │
  │  L1: 短期窗口 (Redis → ChatSession.messages)               │
  │  ├─ 最近 6 轮对话 (12条消息)                                │
  │  ├─ Token 预算: 2000 (超限 → trimByTokenBudget)             │
  │  └─ 裁剪优先级: 最新用户消息 > 系统提示 > 摘要 > 早期对话     │
  │                                                            │
  │  L2: LLM 摘要 (触发后替代 L1 部分消息)                      │
  │  ├─ 触发条件: pairCount>5 && newPairs≥3                     │
  │  ├─ 生成: qwen-plus → "不超过300字摘要"                     │
  │  ├─ 压缩比检查: 摘要长度/原文 < 0.5 (否则拒绝)               │
  │  └─ 注入 System Prompt + MySQL 持久化                       │
  │                                                            │
  │  L3: 长期语义记忆 (Redis)                                   │
  │  ├─ 7 类事实: 教学需求/学生情况/偏好/学科年级/约束/否定/数值   │
  │  ├─ 存储: Redis Set + Detail + 向量缓存                     │
  │  └─ 检索: 余弦相似度(优先) → 关键词匹配(降级)                │
  └────────────────────────────────────────────────────────────┘
```

### 7.2 L1 短期窗口调用链

```
ChatSessionService.addMessage(uid, sid, role, content)
  → getOrCreate(uid, sid)                                      [Redis]
    → getFromRedis() → null → create new ChatSession → saveToRedis()
  
  → session.addMessage(role, content, estimatedTokens)
  → trimByTokenBudget()
    → while (totalTokens > maxWindowTokens):
      → 从最早的非系统消息开始移除
      → 更新 totalTokens
      → 若只剩系统消息仍超限 → 触发 L2 摘要
  
  → saveToRedis(uid, sid, session)
    → Redis: SET "gagneflow:chat:session:{uid}:{sid}"           [Redis]
    → EXPIRE 30天
```

### 7.3 L2 摘要生成调用链

```
ConversationMemoryManager.triggerSummary(uid, sid)
  → ChatSessionService.getSession(uid, sid)                     [Redis / MySQL]
  → 检查触发条件: pairCount > 5 && newPairs >= 3
  → 不符合 → return
  
  → 提取历史消息 → 拼成对话文本
  → ChatService.createSummaryModel()
    → qwen-plus, temperature=0.3, maxTokens=500
  → LLM: "请将以下对话压缩为不超过300字的摘要，保留关键教学信息："
  → 压缩比检查: 摘要长度 / 原文长度 < 0.5
    → 通过 → 替换历史消息
    → 拒绝 → 保留原文，下次对话再次检查
  
  → ChatSessionService.replaceHistory(uid, sid, newHistory, summary, sumPairs)
    → Redis WATCH key → GET → MULTI → SET → EXEC              [乐观锁]
    → 若 WATCH 冲突 → 重试 3 次 → 抛 ConcurrentModificationException
  
  → ChatSessionService.saveMessage(type="summary", content=summary)
    → SessionMessageRepository.save()                           [MySQL]
  
  → extractFactsFromSummary(summary)                            [L3 触发]
```

### 7.4 L3 长期记忆完整调用链

**存储路径：**
```
ConversationMemoryManager.onSummaryGenerated(uid, sid, summary)
  → extractFactsFromSummary(summary)                            [service/memory/ConversationMemoryManager]
    → 句子分割 → 关键词分类 (7类)
    → 每类评分 → 每类取Top-5
    → 返回 List<MemoryFact>
  
  → LongTermMemoryService.storeFacts(uid, sid, facts)          [service/memory/LongTermMemoryService]
    → for each fact:
      → factId = UUID.nameUUIDFromBytes(sessionId + factText)
      → Redis SADD "gagneflow:ltm:{uid}:{sid}" factId           [Redis Set]
      → Redis SET "gagneflow:ltm:detail:{factId}" factText     [Redis String, TTL=30天]
      → Redis SET "gagneflow:ltm:vec:{factId}" vecJson          [Redis String, TTL=30天]
        → embeddingService.generateEmbedding(factText)          [DashScope API]
        → objectMapper.writeValueAsString(vector)               [序列化]
    → Redis EXPIRE "gagneflow:ltm:{uid}:{sid}" 30天             [Redis]
```

**检索路径：**
```
LongTermMemoryService.searchFacts(uid, sid, query, topK=3)
  → Redis SMEMBERS "gagneflow:ltm:{uid}:{sid}"                 [Redis: 获取所有事实ID]
  → 若无事实 → 返回空

  → [向量路径] vectorSearch(factIds, query, topK):              [优先]
    → embeddingService.generateQueryVector(query)                [DashScope API]
    → 批量加载缓存向量:
      → Redis MGET "gagneflow:ltm:vec:{id1}, {id2}, ..."       [Redis]
    → 批量加载事实文本:
      → Redis MGET "gagneflow:ltm:detail:{id1}, ..."           [Redis]
    → 余弦相似度计算:
      → for each fact: dotProduct / (normA * normB)             [JVM内存计算]
    → 按相似度降序排列 → 返回 Top-3

  → [关键词降级] keywordSearch(factIds, query, topK):           [向量路径失败时]
    → for each fact:
      → Redis GET "gagneflow:ltm:detail:{id}"                  [Redis]
      → 检查: fact.contains(any keyword in query.split())
      → 匹配 → 加入结果 → 满 topK 返回
```

**存储交互总结：**

| 操作 | 存储 | Key 模式 | 数据 |
|------|------|---------|------|
| 事实ID集合 | Redis | `gagneflow:ltm:{uid}:{sid}` | Set of UUID |
| 事实详情 | Redis | `gagneflow:ltm:detail:{factId}` | 事实文本 |
| 向量缓存 | Redis | `gagneflow:ltm:vec:{factId}` | JSON序列化向量 |
| 相似度计算 | JVM | — | `cosineSimilarity()` 纯内存 |

---

## 第八章：Prompt 版本管理完整调用链

### 8.1 启动初始化链路

```
应用启动
  → PromptLoader.scanAndLoad()                                  [service/document/PromptLoader]
    → 扫描 agent-config/prompts/ (支持 v1/, v2/ 子目录)
      → addie/addie_analysis.md → "addie_analysis"
      → addie/addie_design.md → "addie_design"
      → addie/addie_development.md → "addie_development"
      → addie/addie_review.md → "addie_review"
    → 加载到内存 Map: {name: content}
  
  → PromptRegistry.seedFromDatabase()                           [service/prompt/PromptRegistry]
    → PromptVersionRepository.findAll()                         [MySQL]
    → 对比数据库版本 vs 文件版本
    → 数据库无记录 → 从文件导入自动创建 PromptVersion 记录
    → 数据库有记录 → 更新 active 版本到内存缓存
    
  → PromptAdminController START                                    [controller/PromptAdminController]
    → GET /api/admin/prompts → 列出所有 prompt 名称
    → GET /api/admin/prompts/{name} → 列出所有版本
```

### 8.2 运行时调用链路

```
AddiePipeline.loadPrompt(name, userId)                              [service/lesson/AddiePipeline]
  → PromptRegistry.getContent(name, versionNumber)
    → [DB可用] PromptVersionRepository.findByPromptNameAndActiveTrue(name)
      → 返回 PromptVersion (版本号+内容)
      → 更新内存缓存
    → [DB不可用] PromptLoader.load(name)                             [文件系统降级]
      → 返回 agent-config/prompts/ 中的 Markdown 内容

  → PromptExperiment.selectVersion(promptName, defaultVersion, userId)  [A/B分流]
    → 实验配置: { "addie_review": { 1: 0.7, 2: 0.3 } }
    → hasExperiment? → 否 → 返回 defaultVersion
    → hasExperiment && userId != null:
      → hash(userId) % 100 → < 70 → 版本 1; ≥ 70 → 版本 2
  
  → PromptMetricsCollector.recordUsage(name, versionNumber)          [指标采集]
  → [Review阶段] PromptMetricsCollector.recordScore(name, version, score)
```

### 8.3 版本切换链路

```
PUT /api/admin/prompts/{name}/{version}/activate
  → PromptAdminController.setActiveVersion()
    → PromptRegistry.activate(promptName, versionNumber)
      → PromptVersionRepository.findByPromptNameAndActiveTrue(name)
        → 将旧版本 active = false
      → PromptVersionRepository.findByPromptNameAndVersionNumber(name, version)
        → 设置 active = true
      → 更新内存缓存
      → 返回新激活的 PromptVersion
    → 无需重启服务 — 后续 loadPrompt() 立即使用新版本
```

---

> **更新日期**：2026-07-30
> **覆盖范围**：全部 8 条核心调用链 + 第9章 已修复设计缺陷，标注类名.方法名、存储交互及核心配置参数。
> **核心结论**：Milvus 是向量存储与检索的唯一位置，Redis 不参与向量检索。长期记忆的向量缓存（`ltm:vec:*`）仅用于应用层余弦相似度计算，与 Milvus 的 IVF_FLAT 索引是完全独立的两个体系。

---

## 附：已修复的设计缺陷（第九章）

### 9.1 C-2：ChatSessionService Redis 故障降级（2026-07-30）

**问题**：`getFromRedis()` catch(Exception) 返回 null → `getOrCreate()` 创建空会话 → Redis 闪断时历史对话静默丢失。

**修复**：`getOrCreate()` 增加 try-catch，Redis 异常时调用 `rebuildFromMySql()` 从 MySQL `SessionMessage` 表重建会话。

### 9.2 C-5：AddieResult 字段线程可见性（2026-07-30）

**问题**：`AddieResult` 7 个字段 `public` 非 `volatile`，多线程写入-读取无 happens-before 保证。

**修复**：字段改为 `public volatile`。

### 9.3 C-10：VectorEmbeddingService 熔断降级返回空列表（2026-07-30）

**问题**：熔断时返回 1024 维全零向量 → L2 归一化产生 NaN → 污染 Milvus 搜索。

**修复**：降级时返回 `Collections.emptyList()`。

### 9.4 H-10：Redis 锁 TTL 对齐任务耗时（2026-07-30）

**问题**：`setIfAbsent(lockKey, "1", 60s)` 对 240s+ 任务严重不足。

**修复**：TTL 60s → 600s。

### 9.5 H-13：RerankService 降级标记（2026-07-30）

**问题**：`rerankFallback()` 返回 `relevanceScore=0.0` 无法区分降级与真实零分。

**修复**：`RerankResult` 新增 `boolean degraded`，fallback 时 `setDegraded(true)`。

### 9.6 C-3：CompletableFuture 异常处理（已修复）

**问题**：`designFuture`/`devFuture` 无 `.exceptionally()`，`TimeoutException` 掩盖真实根因。

**修复**：每个 CompletableFuture 链式 `.exceptionally(ex → { logger.error(...); result.field = DEGRADED_PREFIX; })`。
- `AddiePipeline.java` L170-174（designFuture）
- `AddiePipeline.java` L178-182（devFuture）

### 9.7 C-8：InterruptedException 中断状态恢复（2026-07-30）

**问题**：`emitCopilotAwait` 和 `callAgent` 中 `catch(InterruptedException)` 后未调用 `Thread.currentThread().interrupt()`。

**修复**：两处 catch 块添加 `Thread.currentThread().interrupt()`。
- `AddiePipeline.java` L387（`callAgent`）
- `AddiePipeline.java` L454（`emitCopilotAwait`）

### 9.8 C-11：trimByTokenBudget 死循环防护（已修复）

**问题**：`tokenCounter.estimate()` 返回 0 时 tokens 永不减少，空循环体可能无限循环，最终 `removeCount` 达到上限导致整个对话历史被清空。

**修复**：改为 while 循环 + `stuckCounter ≥ 10 → break` 保护。
- `ChatSessionService.java` L91-109

### 9.9 H-1：PdfGenerator 字体可配置（2026-07-30）

**问题**：字体路径硬编码 Windows/macOS/Linux 三路径，Docker 容器中全不存在导致 PDF 生成失败。

**修复**：
- 新增 `@Value("${gagneflow.pdf.font-path:}")`，优先使用配置路径
- 配置路径不存在时回退硬编码路径
- 空 catch 块添加 debug 日志
- `PdfGenerator.java` L47-72

### 9.10 H-8：SSE emitter 并发保护（2026-07-30）

**问题**：Review 异步线程和主流程线程竞争 `emitter.complete()`，可能 `IllegalStateException`。

**修复**：`LessonController` 新增 `AtomicBoolean emitterCompleted`，`sendAndComplete()` 使用 `compareAndSet(false, true)` 确保单次 complete。
- `LessonController.java` L87 + L379-392
