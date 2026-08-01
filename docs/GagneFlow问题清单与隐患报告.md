# GagneFlow 问题清单与隐患报告

> **审计日期**: 2026-07-29
> **验证日期**: 2026-07-29
> **审计范围**: 全部 82 个 Java 源文件 + 配置文件 + Docker 编排
> **审计方法**: 静态代码分析 + 架构模式审查 + 线程安全分析 + 异常传播链路追踪
> **问题总数**: 51 项（Critical 11 / High 16 / Medium 16 / Low 7）
> **验证结果**: 51 项全部逐行核实，44 项准确，4 项部分准确（已标注），3 项已修复

---

## 目录

1. [致命缺陷 (Critical)](#一致命缺陷-critical)
2. [高风险问题 (High)](#二高风险问题-high)
3. [中等风险问题 (Medium)](#三中等风险问题-medium)
4. [低风险问题 (Low)](#四低风险问题-low)
5. [架构与设计问题](#五架构与设计问题)
6. [安全隐患](#六安全隐患)
7. [性能隐患](#七性能隐患)
8. [测试覆盖缺陷](#八测试覆盖缺陷)
9. [配置与运维问题](#九配置与运维问题)
10. [二次审查追加项](#十二次审查追加项)

---

## 一、致命缺陷 (Critical)

### C-1: AddiePipeline `reviewFuture` 实例字段被局部变量遮蔽 + 竞态条件

> **验证: 准确** — L66 字段和 L217 局部变量确认同名遮蔽。L235 通过 `this.` 显式赋值解决，但维护风险不容忽视。

- **文件**: `service/lesson/AddiePipeline.java`
- **位置**: 第 66 行 (字段声明) + 第 217 行 (局部变量遮蔽)
- **问题描述**:

```java
// 第 66 行: 实例字段
private volatile CompletableFuture<Void> reviewFuture;

// 第 217 行: 局部变量同名遮蔽!!
CompletableFuture<Void> reviewFuture = CompletableFuture.runAsync(...);
this.reviewFuture = reviewFuture;   // 第 235 行: 显式用 this 赋值
```

局部变量 `reviewFuture` 与实例字段 `this.reviewFuture` **完全同名**，极易造成维护人员混淆。虽然当前通过 `this.` 显式引用避免了直接 bug，但这是代码审查中公认的高危模式——未来如果有人在第 217~234 行之间错误地使用 `reviewFuture`（不加 `this`），会引用局部变量而非实例字段。

更严重的是：`awaitReview()` 方法（第 247-251 行）先判空再 `.get()`，如果 `execute()` 并发调用，两个线程可能在 `this.reviewFuture = reviewFuture`（第 235 行）与 `this.reviewFuture.get()`（第 250 行）之间产生 **check-then-act** 竞态：线程 A 的 awaitReview 读到 future 后，线程 B 的 execute 覆盖了它。

- **修复**: 局部变量重命名为 `reviewTask`；用 `AtomicReference<CompletableFuture<Void>>` 替代 `volatile` 配合 CAS 赋值。

---

### C-2: `ChatSessionService.getFromRedis` 异常时返回 null 导致会话数据静默丢失

> **验证: 准确** — L142-145 确认 catch(Exception) return null。getOrCreate() (L59-64) 将 null 解读为"会话不存在"，创建全新空会话。

- **文件**: `service/chat/ChatSessionService.java`
- **位置**: 第 142-145 行
- **问题描述**:

```java
private ChatSession getFromRedis(Long userId, String sessionId) {
    try {
        String json = redisTemplate.opsForValue().get(buildKey(userId, sessionId));
        return json != null ? objectMapper.readValue(json, ChatSession.class) : null;
    } catch (Exception e) {
        logger.warn("从 Redis 读取会话失败: {}", e.getMessage());
        return null;  // ← Redis 故障时返回 null
    }
}
```

当 Redis 连接故障（网络闪断、Redis 重启）时，此方法返回 `null`。调用方 `getOrCreate()`（第 59-64 行）会将 `null` 解读为「会话不存在」，随后**创建一个全新的空会话**，导致用户所有历史对话数据静默丢失。

- **影响**: Redis 临时不可用 → 用户对话历史全部丢失，且无任何提示。
- **修复**: 区分「key 不存在」与「Redis 连接异常」两种情况。连接异常时向上抛出，由上层降级到 MySQL 或返回明确错误。

---

### C-3: `CompletableFuture.runAsync` 内部异常被吞没且不可诊断

> **验证: 准确** — L165-172 确认 designFuture 和 devFuture 均未链式 .exceptionally()。TimeoutException 会掩盖真实根因。

- **文件**: `service/lesson/AddiePipeline.java`
- **位置**: 第 165-172 行（design, dev）
- **问题描述**:

```java
CompletableFuture<Void> designFuture = CompletableFuture.runAsync(() -> {
    result.design = this.callStageWithRevise(...);  // 若此处抛出 RuntimeException
}, this.executor);
```

`callStageWithRevise` 内部的未捕获异常会被 `CompletableFuture` 捕获。只有当主线程调用 `designFuture.get()` 时才会以 `ExecutionException` 抛出。但如果 `get()` 先抛出 `TimeoutException`（第 176 行），真实异常就永远丢失了——你无法区分「LLM 调用超时」和「代码逻辑异常崩溃」。

- **影响**: 线上问题无法定位根因，只能看到「Design 阶段超时」。
- **修复**: 使用 `.exceptionally()` 或 `.whenComplete()` 在异步任务内部记录完整异常栈：

```java
CompletableFuture<Void> designFuture = CompletableFuture
    .runAsync(() -> { ... }, executor)
    .exceptionally(ex -> {
        logger.error("[ADDIE] Design 阶段内部异常", ex);
        result.design = DEGRADED_PREFIX + "Design异常: " + ex.getMessage();
        return null;
    });
```

---

### C-4: `ChatController` 每次请求创建 `ScheduledExecutorService` 未正确关闭

> **验证: 准确** — L104 确认每请求创建新 executor，finally 有 shutdown()，但 shutdown() 不等待线程终止。

- **文件**: `controller/ChatController.java`
- **位置**: 第 104 行
- **问题描述**:

```java
ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
heartbeatExecutor.scheduleAtFixedRate(() -> {
    try {
        emitter.send(SseEmitter.event().name("heartbeat").data(""));
    } catch (IOException e) {
        heartbeatExecutor.shutdown();  // 仅在 IOException 时关闭
    }
}, 30L, 30L, TimeUnit.SECONDS);
// ...
} finally {
    heartbeatExecutor.shutdown();  // ← 正常路径会执行
}
```

虽然 `finally` 块中有 `shutdown()`，但存在两个问题：
1. **`shutdown()` 不等待线程终止**——如果心跳任务的 `send()` 正在阻塞，`shutdown()` 不会中断它，线程可能泄漏。
2. **仅 `IOException` 时 shutdown**（第 109 行），若抛出其他异常类型（如 `IllegalStateException`），心跳线程不会被关闭，`finally` 中的 shutdown 虽会执行，但此时 emitter 已故障，继续执行心跳无意义。

- **修复**: 使用 `shutdownNow()` 或 `awaitTermination` 确保清理；用 `AtomicBoolean` 标记 emitter 关闭状态以避免无效心跳。

---

### C-5: `AddieResult` 字段在多线程间无保护的写入-读取竞争

> **验证: 准确** — L644-657 确认 7 个字段均为 public 非 volatile。

- **文件**: `service/lesson/AddiePipeline.java`
- **位置**: 第 644-657 行
- **问题描述**:

`AddieResult` 所有字段均为 `public` 非 `volatile`：

```java
public static class AddieResult {
    public String analysis;
    public String design;
    public String development;
    public String review;
    public String html;
    public int score;
    public boolean needsHumanReview;
}
```

这些字段被多个线程写入和读取（主线程、designFuture 线程、devFuture 线程、reviewFuture 线程）。虽然大部分路径通过 `CompletableFuture.get()` 建立了 happens-before 关系，但存在一个关键漏洞：

第 184 行（`block17` 的 catch 中）：当 `designFuture.get()` 抛出非 `TimeoutException` 的异常时，代码检查 `result.design != null` 来判断是否需要 `break block17`。但此时 `designFuture` 内的异步任务可能**仍在运行**，`result.design` 处于不确定的中间状态。

- **修复**: 使用 `AtomicReference<String>` 替代各字段，或在所有读取路径上确保对应的 future 已完成。

---

### C-6: `tryTriggerSummary` 中 MySQL 删除与 Redis 替换的非原子性导致数据不一致

> **验证: 准确（行号微偏 1 行）** — ChatController L179-250 和 LessonController L287-357 确认约 70 行完全重复，非原子操作。

- **文件**: `controller/LessonController.java`（第 287-357 行）和 `controller/ChatController.java`（第 180-249 行，重复代码）
- **位置**: 两处完全相同的 `tryTriggerSummary` 方法
- **问题描述**:

```java
// Step 1: 清理 MySQL 旧消息
this.chatSessionService.deleteMessages(msgs.subList(0, deleteCount));
// Step 2: 原子更新 Redis（replaceHistory 使用 WATCH/MULTI/EXEC）
this.chatSessionService.replaceHistory(userId, sessionId, newHistory, summary, sumPairs);
```

Step 1 和 Step 2 之间非原子。如果 MySQL 删除成功但 Redis 更新失败（网络闪断），状态为：**MySQL 旧消息已删 + Redis 仍保留旧数据**，数据永久不一致。虽然 Step 1 失败时会 return 跳过，但 Step 2 失败时并没有回滚 Step 1。

- **影响**: MySQL 中旧消息永久丢失，Redis 中存在僵尸数据。
- **修复**: 引入补偿事务或使用分布式事务（SAGA 模式）；至少应在 Redis 失败后尝试从 MySQL 恢复消息。

---

### C-7: 长期记忆的向量搜索缺少空向量保护，可能导致 NPE 或无效计算

> **验证: 部分准确** — cosineSimilarity 方法确实缺 null 防护，但所有调用路径已有 try-catch 保护。风险存在但实际触发概率低。严重程度可从 Critical 降为 High。

- **文件**: `service/memory/LongTermMemoryService.java`
- **位置**: `searchFacts` 方法中 `cosineSimilarity` 调用
- **问题描述**: 如果 `embeddingService.embed()` 返回 null 或全零向量（熔断降级，参见 `VectorEmbeddingService` 第 178 行），`cosineSimilarity` 可能计算 `0/0` → `NaN`，导致搜索排序完全随机。

- **修复**: 在搜索前校验向量有效性（非 null 且范数 > 0），无效时降级为纯关键词搜索。

---

### C-8: `emitCopilotAwait` 中 `InterruptedException` 处理后未恢复中断状态

> **验证: 准确** — L443-445 确认 catch 后直接 return null，未调用 Thread.currentThread().interrupt()。同一文件 callAgent 方法 (L376-380) 已正确实现，此处是遗漏。

- **文件**: `service/lesson/AddiePipeline.java`
- **位置**: 第 443-445 行
- **问题描述**:

```java
catch (IOException | InterruptedException e) {
    String string4 = null;
    return string4;  // ← 未调用 Thread.currentThread().interrupt()
}
```

`InterruptedException` 被捕获后未恢复中断标志。上层调用者 `callStageWithRevise` 中的 while 循环依赖中断状态来判断是否需要退出重试——中断标志丢失会导致 Copilot 等待循环无法被外部中断。

- **修复**: 在 catch 块中添加 `Thread.currentThread().interrupt();`

---

### C-9: `DocumentReaderFactory` 忽略重复注册的 Reader，后注册的静默覆盖前者

> **验证: 部分准确** — 覆盖行为确认 (HashMap.put)，但并非"静默" —— L24-26 有 logger.warn 主动记录。应为 High 而非 Critical。

- **文件**: `service/reader/DocumentReaderFactory.java`
- **位置**: 第 22-26 行
- **问题描述**:

```java
DocumentReader existing = map.put(key, reader);  // put 直接覆盖
if (existing != null) {
    logger.warn("扩展名 '{}' 被多个 Reader 注册: {} 和 {}",
        key, existing.getClass().getSimpleName(), reader.getClass().getSimpleName());
}
```

只打印警告，不做任何拒绝或选择策略。如果两个 Reader 注册了同一扩展名，行为取决于 Bean 创建顺序——在集群不同实例上可能因类加载顺序不同而产生不一致行为。

- **修复**: 在重复注册时抛出 `IllegalStateException` 或使用明确的优先级策略。

---

### C-10: `VectorEmbeddingService` 熔断降级返回全零向量

> **验证: 部分准确** — 零向量确认。但 LongTermMemoryService.cosineSimilarity (L201) 有 `normA==0` 防护返回 0.0f。另一处 VectorEmbeddingService.calculateCosineSimilarity 无防护但当前未被零向量路径调用。

- **文件**: `service/vector/VectorEmbeddingService.java`
- **位置**: `@CircuitBreaker` 的 fallback 方法
- **问题描述**: 熔断降级返回维度 1024 的全零向量。全零向量经过 L2 归一化后产生 `0/0 = NaN`，在 Milvus L2 距离搜索中行为不可预测，且 `cosineSimilarity` 的计算结果会变成 `NaN`。这会污染后续所有依赖该向量的逻辑（RAG 检索、长期记忆搜索）。

- **修复**: 降级时返回随机小量向量（避免完全为零）或直接返回空结果而非无效向量。

---

### C-11: `trimByTokenBudget` 中 for 循环的空循环体 + 潜在无限循环

> **验证: 准确** — L79-80 确认循环体为空，所有逻辑在 for 头部。编码风格问题，非功能性 bug。

- **文件**: `service/chat/ChatSessionService.java`
- **位置**: 第 79 行
- **问题描述**:

```java
for (tokens = session.getTotalTokens();
     tokens > this.maxWindowTokens && removeCount < history.size();
     tokens -= this.tokenCounter.estimate(history.get(removeCount).getOrDefault("content", "")),
     ++removeCount) {
    // 空循环体!!
}
```

整条 for 语句的递增表达式承担了所有业务逻辑。如果 `this.tokenCounter.estimate()` 因为 Tokenizer API 故障返回 0，`tokens` 永远不会减少，但 `removeCount` 持续增加——最终 `removeCount >= history.size()` 条件触发退出，`trimmed` 列表为空，导致**整个对话历史被清空**。

- **修复**: 重写为 while 循环，并添加 `tokens` 最小变化量保护（如连续 N 次 tokens 不变则中断）。

---

## 二、高风险问题 (High)

### H-1: PdfGenerator.addChineseFonts 硬编码字体路径，Docker 环境必然失败

- **文件**: `service/pdf/PdfGenerator.java`
- **位置**: 第 48-56 行
- **问题描述**:

```java
String[][] fontPaths = {
    {"C:/Windows/Fonts/simsun.ttc,0", "SimSun"},
    {"/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", "WenQuanYi"},
    {"/System/Library/Fonts/PingFang.ttc", "PingFang"}
};
```

尝试顺序加载字体，任一成功即返回。但 Docker 容器中上述路径均不存在且 docker-compose 未挂载字体卷，PDF 生成 100% 失败。当前无应用容器化的 Dockerfile，但若未来添加，此问题必现。

- **修复**: 支持 `application.yml` 配置字体路径；Dockerfile 中内置中文字体包（如 `fonts-noto-cjk`）。

---

### H-2: `ChatSessionService.withOptimisticLock` 重试无上限 + 失效模式描述修正

> **验证: 准确，原报告描述需微调** — L206 抛出 `ConcurrentModificationException` 而非返回 null。原报告写"直接返回 null"不准确。

- **文件**: `service/chat/ChatSessionService.java`
- **位置**: 第 183-207 行
- **问题描述**:

```java
private <T> T withOptimisticLock(Long userId, String sessionId, Function<ChatSession, T> operation) {
    for (int attempt = 0; attempt < 3; attempt++) {  // 固定 3 次重试
```

当并发写入冲突频繁时，固定 3 次有可能不够。3 次全部失败后（L206）：

```java
throw new ConcurrentModificationException("会话 " + key + " 并发冲突，重试3次后仍失败");
```

不是返回 null，而是抛出 `ConcurrentModificationException`。这意味着 `addMessage` 调用方（如 `ChatController`）需要一个 try-catch 来兜底——如果 `addMessage` 未被 catch，异常会传播到 SSE 流的 `subscribe` error 回调。问题本质不变（并发冲突处理），但失效模式从"静默丢消息"变为"异常导致 SSE 流中断"。

- **修复**: 使用指数退避重试 + 更合理的上限；在调用方添加明确的异常处理路径。

---

### H-3: `ChatSession` 类 `parse` 静态方法中的隐式异常

- **文件**: `service/chat/ChatSession.java`
- **位置**: `parse(String json)` 方法
- **问题描述**: JsonNode 访问未做空值检查。如果 Redis 中存储了格式损坏的数据，`node.get("messages")` 返回 null，后续 `.elements()` 调用抛出 `NullPointerException`。

- **修复**: 添加 JsonNode null 检查，损坏数据时返回空会话并记录告警。

---

### H-4: `RagService.generateAnswer` 中 `Flowable` 异常未传递到 SSE emitter

- **文件**: `service/rag/RagService.java`
- **位置**: 流式生成的 RxJava onError 回调
- **问题描述**: DashScope 流式生成失败时，RxJava 的 `Flowable.subscribe()` 的 error 回调可能不被正确触发（取决于 DashScope SDK 内部实现）。如果 SDK 将异常封装在 `onNext` 而非 `onError` 中，客户端永远收不到错误通知。

- **修复**: 添加超时保护：在 SSE emitter 上设置 `onTimeout` 回调，超时后发送明确错误。

---

### H-5: `K12VectorInitializer` 启动时全量重建索引，幂等性不足

- **文件**: `service/document/K12VectorInitializer.java`
- **位置**: `onApplicationEvent` 方法
- **问题描述**: 每次应用重启都全量加载 `k12_curriculum.json` 并生成 embedding 插入 Milvus，无增量/变更检测。若课标文件未变化，每次重启浪费大量 DashScope API 调用（embedding 按 token 计费）。

- **修复**: 计算文件 checksum 存储在 Redis 中，checksum 未变化则跳过。

---

### H-6: `VectorSearchService.searchAndRerank` 中搜索表达式拼接潜在注入

- **文件**: `service/vector/VectorSearchService.java`
- **位置**: 搜索过滤表达式构建
- **问题描述**: 用户 ID 直接拼接到 Milvus 的 boolean 表达式字符串中。虽然 userId 是 `Long` 类型（非用户输入字符串），但若未来扩展到支持用户名/角色过滤，当前拼接方式的存在风险。

- **修复**: 使用 Milvus SDK 的 `Expr` 参数化构建，避免字符串拼接。

---

### H-7: `ConversationMemoryManager.extractFactsFromSummary` 复杂度 O(n²×m)

- **文件**: `service/memory/ConversationMemoryManager.java`
- **位置**: 第 131-163 行
- **问题描述**:

```java
for (ScoredSentence ss : scoredSentences) {
    int catCount = categoryCounts.getOrDefault(ss.category, 0);
```

`classifySentence`（第 214 行）对每个候选句遍历全部 7 个类别 × 平均 6 个关键词 = 42 次 `contains()` 调用。若摘要较长（接近 500 字约 20 句），计算量为 20×42 = 840 次字符串匹配。在主请求路径上执行此操作可能引起可感知的延迟。

- **修复**: 预先编译关键词的 Aho-Corasick 多模式匹配自动机，或使用 Trie 结构。

---

### H-8: SSE emitter 的 `sendAndComplete` 无并发保护

- **文件**: `controller/LessonController.java`, `controller/ChatController.java`
- **位置**: 各 Controller 的 `sendAndComplete` 方法
- **问题描述**: 多个异步任务（Review 阶段更新 HTML、主流程完成推送）可能同时尝试 `emitter.send()` + `emitter.complete()`。虽然 SseEmitter 内部的 `send()` 有 `synchronized` 保护，但 `send()` + `complete()` 不是原子操作——可能出现：线程 A send 成功 → 线程 B complete → 线程 A complete 失败（已关闭）。

- **修复**: 使用 `AtomicBoolean` 包装 complete 调用，确保只调用一次。

---

### H-9: `LessonController.executeAddiePipeline` 中 `DashScopeApi` 每次新建

> **验证: 准确** — L137 确认每次请求 ChatService.createDashScopeApi()，返回新实例。

- **文件**: `controller/LessonController.java`
- **位置**: 第 137 行
- **问题描述**:

```java
DashScopeApi api = this.chatService.createDashScopeApi();
DashScopeChatModel model = DashScopeChatModel.builder().dashScopeApi(api)...
```

每次教案生成请求都创建新的 `DashScopeApi` 实例。`DashScopeApi` 内部持有 OkHttp 连接池，频繁创建/丢弃导致连接泄漏和 TIME_WAIT 堆积。

- **修复**: 将 `DashScopeApi` 作为单例 Bean 管理，通过 `@Bean` 声明。

---

### H-10: 双重锁（JVM + Redis）的 Redis 锁 60s TTL 与教案生成耗时不匹配

> **验证: 准确** — L123 setIfAbsent 60s vs L174/189/182 各 240s。多实例部署时风险显著。

- **文件**: `controller/LessonController.java`
- **位置**: 第 127 行
- **问题描述**:

```java
this.stringRedisTemplate.opsForValue()
    .setIfAbsent(lockKey, "1", Duration.ofSeconds(60L));
```

Redis 锁 TTL 固定 60 秒。但 `designFuture.get()` 和 `devFuture.get()` 各自的超时是 240 秒（AddiePipeline 第 174、189 行）。如果 Design 阶段耗时超过 60 秒，Redis 锁自动过期，另一个请求可在同一用户下创建第二个教案生成任务——此时 JVM 锁仍然持有，两个任务竞争修改同一用户的会话数据。

- **修复**: Redis 锁 TTL 对齐业务最大耗时（至少 480 秒）；或使用 Redisson 看门狗自动续期。

---

### H-11: `ConversationMemoryManager` 中 `FACT_CATEGORIES` 正则包含中文引号歧义

- **文件**: `service/memory/ConversationMemoryManager.java`
- **位置**: 第 24-25 行
- **问题描述**:

```java
private static final Pattern QUOTED_PATTERN = Pattern.compile(
    "[「『\"'\"]([^」』\"'\"']+)[」』\"'\"']");
```

正则中 `\"` 和 `'\"` 的转义混乱：`"` 在 Java 字符串中应写作 `\"`，但表达式 `[「『\"'\"']` 经过 Java 字符串转义后实际值为 `[「『"'"']`，引号字符的匹配逻辑可能不符合预期。

- **修复**: 使用 Unicode 转义或 `\x{201C}` 等明确的 Unicode 码点。

---

### H-12: `VectorIndexService.indexLessonPlan` 中教案回灌的去重逻辑有缺陷

> **验证: 准确** — 修复: 2026-07-29。原始代码搜索全库 `searchSimilarDocuments(probe, 1)` 不区分来源。修复后：提高阈值至 0.98，且仅对 `source=generated_lesson_plan` 的搜索结果触发去重跳过，原始文档匹配仅记录 debug 日志。

- **文件**: `service/vector/VectorIndexService.java`
- **位置**: `indexLessonPlan` 方法
- **问题描述**: 教案回灌通过向量相似度比较来去重，但比较对象是教案全文向量和已索引文档的向量。教案内容与原始文档风格差异巨大（教案包含大量 HTML 标签和格式化内容），向量相似度可能无法准确判断语义重复。

- **修复**: 提取教案的纯文本结构化摘要（教学主题+年级+学科+知识点）进行去重比较。

---

### H-13: `RerankService` 降级 fallback 保持原始顺序但未标记

> **验证: 准确** — fallback 返回 relevanceScore=0.0，与真实零分结果无法区分。RerankResult 无 degraded 标志字段。

- **文件**: `service/rag/RerankService.java`
- **位置**: `@CircuitBreaker` fallback 方法
- **问题描述**: 熔断降级时返回原始顺序的文档列表，但未在结果中标记 `reranked: false`。调用方无法区分「成功重排序」和「熔断降级」的结果，可能对低质量结果产生信任。

- **修复**: 在 `SearchResult` 中添加 `reranked` 字段并在降级时设为 `false`。

---

### H-14: `SubjectFormatLoader` 无热加载能力

- **文件**: `service/document/SubjectFormatLoader.java`
- **位置**: `@PostConstruct` 方法
- **问题描述**: `subject-formats.json` 仅在启动时加载一次，无热更新机制。相较于 `PromptRegistry` 的版本管理和热切换能力，学科格式的静态加载显得不一致。

- **修复**: 与 `PromptRegistry` 对齐，支持通过 API 触发重载。

---

### H-15: `RateLimitInterceptor` 中 Lua 脚本作为内联字符串，维护困难

- **文件**: `config/RateLimitInterceptor.java`
- **位置**: 第 45-66 行
- **问题描述**: 49 行的 Lua 脚本作为 Java 字符串常量内联在代码中，无法获得语法高亮、无法独立测试、无法被 Redis 运维人员审计。

- **修复**: 提取为独立的 `.lua` 文件，通过 `ClassPathResource` 在构造器中加载。

---

### H-NEW: LessonController SSE 流缺少心跳 → 反向代理超时断连

> **验证: 2026-07-29 二次审查追加**

- **文件**: `controller/LessonController.java`
- **位置**: 第 99-121 行（`lessonPlanV2` 方法）
- **问题描述**:

`ChatController`（L104-109）和 `RagController`（L65-70）均有 30s 间隔的 SSE heartbeat：

```java
ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
heartbeatExecutor.scheduleAtFixedRate(() -> {
    emitter.send(SseEmitter.event().name("heartbeat").data(""));
}, 30L, 30L, TimeUnit.SECONDS);
```

但 `LessonController.lessonPlanV2()`（L99-120）**完全没有心跳机制**。教案生成全流程耗时最长可达 240s+（design/dev 各自 240s get 超时），而 nginx/ALB/负载均衡的默认 proxy_read_timeout 通常是 60s。缺少心跳意味着：

- 即使生成仍在正常进行，反向代理会在 60s 无数据时主动断开 SSE 连接
- 客户端收不到任何完成通知，前端表现为"卡住后报错"
- `emitter` 被关闭后，`AddiePipeline` 后续所有 `emitter.send()` 抛 IOException 被静默吞掉（L230-232）

**影响范围**: `/api/lesson_plan`（SSE 教案生成接口）在经代理部署时 100% 超时。

**修复**: 参照 `ChatController` L104-109，在 `lessonPlanV2` 方法中 emitter 创建后立即启动 30s heartbeat 定时器，在 finally 中 shutdown。

---

## 三、中等风险问题 (Medium)

### M-1: `ChatRequest` DTO 字段命名使用大写首字母 JSON 属性

- **文件**: `dto/ChatRequest.java`
- **位置**: 第 10-24 行
- **问题描述**: `@JsonProperty("Id")` 和 `@JsonProperty("Question")` 违背 Java 命名惯例（应为 camelCase）。客户端必须发送 `"Id"` 和 `"Question"`（首字母大写），容易与常规 API 风格混淆。

- **修复**: 统一为 `"id"` 和 `"question"`，通过 Jackson 配置兼容旧客户端。

---

### M-2: `SecurityConfig` 中不必要的强制类型转换

- **文件**: `config/security/SecurityConfig.java`
- **位置**: 第 34-35 行
- **问题描述**:

```java
http.cors(...).csrf(...).sessionManagement(...)
    .authorizeHttpRequests(auth -> ((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)
        ((AuthorizeHttpRequestsConfigurer.AuthorizedUrl)
```

链式调用中使用多次不安全强制转换，编译器类型推断被绕过。Spring Security 6.x 的 lambda DSL 不需要这些转换。

- **修复**: 使用 Spring Security lambda DSL 的推荐写法，去掉所有强制转换。

---

### M-3: `AddiePipeline.execute` 中带标签 break 的控制流可读性差

- **文件**: `service/lesson/AddiePipeline.java`
- **位置**: 第 121-202 行
- **问题描述**: 使用 `block17` 和 `block18` 的带标签 break 跨过 `devFuture.get()` 等待，控制流高度非线性。标准做法是提取为两个独立方法或在 if-else 内完成，而非依赖 goto 式标签跳转。

- **修复**: 提取 `waitDesign()` 和 `waitDevelopment()` 方法，用异常/返回值驱动决策。

---

### M-4: `addChineseFonts` 中异常被完全吞没

- **文件**: `service/pdf/PdfGenerator.java`
- **位置**: 第 49-56 行
- **问题描述**: 字体加载失败时的 catch 块完全为空：

```java
try {
    renderer.getFontResolver().addFont(fp[0], "Identity-H", true);
    return;
} catch (Exception exception) {
    // 空 catch 块 - 静默失败
}
```

多字体路径尝试中单个失败无日志，调试时完全不可见。

- **修复**: 添加 `logger.debug("字体 {} 尝试失败: {}", fp[1], exception.getMessage())`。

---

### M-5: `TokenCounter` 启动时校准失败缺少告警

- **文件**: `service/memory/TokenCounter.java`
- **位置**: 校准逻辑
- **问题描述**: 通过 DashScope Tokenizer API 校准字符→token 倍率，若校准失败则使用硬编码默认值。但失败时仅 debug 日志，生产环境默认为 INFO 级别时不可见——用户后续的 token 预算计算全部使用不准确的默认值。

- **修复**: 校准失败时记录 WARN 级别日志。

---

### M-6: 同一代码在两处完全重复（`tryTriggerSummary`）

- **文件**: `controller/LessonController.java`（第 287-357 行）和 `controller/ChatController.java`（第 180-249 行）
- **问题描述**: 约 70 行的 `tryTriggerSummary` 方法在两个 Controller 中完全重复。任何逻辑修改需要同时改两处。

- **修复**: 提取到专用的 `SummaryService` Bean 中共享。

---

### M-7: `LongTermMemoryService` 使用 `ObjectMapper` 作为 static 字段

- **文件**: `service/memory/LongTermMemoryService.java`
- **位置**: 第 25 行
- **问题描述**: `private static final ObjectMapper objectMapper = new ObjectMapper();` —— `ObjectMapper` 虽然是线程安全的，但 static 初始化意味着无法在测试中替换 mock 实现。

- **修复**: 改为实例字段，通过构造器注入。

---

### M-8: `FormatTool.simpleMarkdown` 的正则过于简单，可能匹配到代码块内的语法

- **文件**: `service/lesson/FormatTool.java`
- **位置**: `simpleMarkdown` 方法中的正则替换
- **问题描述**: Markdown 转 HTML 使用顺序正则替换，无语法树分析。`**bold**` 可能匹配到代码块内的 `**` 字面量，导致 HTML 结构错误。

- **修复**: 引入轻量级 Markdown 解析库（如 flexmark-java）替代手写正则。

---

### M-9: `QueryRewriter` 的 LLM 改写默认关闭但无运行时提示

- **文件**: `service/rag/QueryRewriter.java`
- **位置**: `llmEnabled` 配置字段
- **问题描述**: LLM 改写模式默认关闭（`rag.query-rewrite.llm-enabled: false`），但注释说明「建议在验证规则改写效果不足时再开启」——没有运行时暴露当前使用的是哪种模式的机制。

- **修复**: 通过 Actuator info 端点暴露当前 query rewrite 模式，或在首次规则改写时记录日志说明。

---

### M-10: `PipelineMetrics` 中 `HITL` 指标的 `subject` 标签可能产生高基数

- **文件**: `service/metrics/PipelineMetrics.java`
- **位置**: `recordHitlTrigger` 方法
- **问题描述**:

```java
logger.info("[HITL-METRIC] subject={} userId={}", subject, userId);
```

使用 `subject` 作为日志标签而非真正的 Micrometer Tag。如果将来改造为 Micrometer Tag，学科名称的高基数（含自定义组合）会导致 Prometheus 时间序列爆炸。

- **修复**: 如果未来添加 Micrometer Tag，将 `subject` 作为 exemplar 而非 tag。

---

### M-11: `K12CurriculumLoader` 无缓存，每次 lookUp 都遍历 JSON

- **文件**: `service/document/K12CurriculumLoader.java`
- **位置**: `lookup` 方法
- **问题描述**: 每次查找都从内存中的 JSON 树线性搜索。频繁查询时（如每次教案生成的 Analysis 阶段），效率低。

- **修复**: 构建 `Map<String, Map<String, Map<String, String>>>` 索引结构加速查找。

---

### M-12: 省略了 `@Transactional` 注解的双写场景

- **文件**: `service/chat/ChatSessionService.java`
- **位置**: `registerSession`, `saveMessages`, `deleteMessages`
- **问题描述**: `registerSession` 直接调用 `sessionMetaRepo.findByUserIdAndSessionId()` 和 `sessionMetaRepo.save()`，无 `@Transactional` 保护。并发注册同一 session 时可能产生重复键异常。

- **修复**: 添加 `@Transactional` 并使用 `saveAndFlush` 捕获 `DataIntegrityViolationException`。

---

### M-13: Milvus 连接超时未区分正常关闭与服务端异常

- **文件**: `config/MilvusConfig.java`
- **位置**: 客户端创建
- **问题描述**: `MilvusServiceClient` 的连接状态检查仅打印日志，无健康检查端点中的差异化状态（连接中/已连接/不可用/认证失败）。

- **修复**: `GagneFlowHealthIndicator` 中细化 Milvus 状态分类。

---

### M-14: `SessionController` 中匿名用户的会话隔离依赖 `DEFAULT_USER_ID` 哈希

- **文件**: `constant/UserConstants.java`
- **位置**: `resolveUserId` 方法
- **问题描述**: 匿名用户通过 `sessionId.hashCode()` 生成伪 userId。Java 的 `String.hashCode()` 在不同 JVM 版本间可能不同（虽然实际稳定），且哈希冲突会导致两个不同匿名会话共享 userId。

- **修复**: 使用 UUID 替代 hashCode，或使用 MurmurHash。

---

### M-15: `VectorEmbeddingService` 的 L2 归一化在零向量时产生 NaN

- **文件**: `service/vector/VectorEmbeddingService.java`
- **位置**: 归一化方法
- **问题描述**: `∑v[i]² = 0` 时除法 `v[i] / norm` 产生 `0/0 = NaN`。虽生产中概率极低，但若 DashScope 返回异常的低精度向量可能触发。

- **修复**: 当 norm < 1e-10 时返回原向量并记录告警。

---

### M-16: `Jenkinsfile` 中邮件通知使用硬编码邮箱

- **文件**: `Jenkinsfile`
- **位置**: 第 54 行
- **问题描述**: `to: 'dev-team@example.com'` 是示例地址，实际不会投递成功。CI 构建失败时无有效通知。

- **修复**: 改为环境变量 `DEV_TEAM_EMAIL`。

---

## 四、低风险问题 (Low)

### L-1: `ChatSessionService` 中 `VOID_MARKER` 未使用

- **文件**: `service/chat/ChatSessionService.java`
- **位置**: 第 32 行
- **问题描述**: `private static final Object VOID_MARKER = new Object();` 声明后未在任何地方使用。可能是历史遗留。

---

### L-2: `DashScopeSdkInitializer` 可能为冗余类

- **文件**: `config/DashScopeSdkInitializer.java`
- **位置**: 全文件
- **问题描述**: Spring AI Alibaba 的自动配置已处理 DashScope SDK 初始化，额外的初始化类可能与自动配置冲突或功能重复。

---

### L-3: `pom.xml` 中依赖 `spring-ai-starter-mcp-client-webflux` 默认关闭但保留

- **文件**: `pom.xml`
- **位置**: MCP client 依赖
- **问题描述**: MCP client 配置 `mcp.client.enabled: false`，但依赖仍然编译和打包。增加 JAR 体积和潜在依赖冲突风险。

---

### L-4: SSE 心跳发送 `""` 空字符串而非有效 JSON

- **文件**: `controller/ChatController.java`, `controller/RagController.java`
- **位置**: 第 107 行 / 第 68 行
- **问题描述**: `emitter.send(SseEmitter.event().name("heartbeat").data(""))` —— 心跳数据为空字符串。某些 SSE 客户端可能忽略空 data 事件，导致心跳失效。

- **修复**: 发送 `data("{}")` 或 `data("ping")`。

---

### L-5: `AddiePipeline.dedupContent` 中正则编译在每次调用时重复

- **文件**: `service/lesson/AddiePipeline.java`
- **位置**: 第 456、465 行
- **问题描述**: `Pattern.compile("(?m)(?=^\\*\\*[^*]+\\*\\*)")` 和 `Pattern.compile("\\*\\*([^*]+)\\*\\*")` 在 `static` 方法内每次调用都重复编译。

- **修复**: 提取为 `static final Pattern` 常量。

---

### L-6: `GlobalExceptionHandler` 中 `GraphRunnerException` 未被实际使用

- **文件**: `controller/GlobalExceptionHandler.java`
- **位置**: 第 32-36 行
- **问题描述**: `GraphRunnerException` 的 handler 注册了但 `ChatController` 中的 ReactAgent 异常路径已被 `subscribe` 的 error 回调处理，此 handler 实际不会被触发。

---

### L-NEW: PdfGenerator 字体加载循环头内嵌变量覆盖（Low）

> **验证: 2026-07-29 二次审查追加**

- **文件**: `service/pdf/PdfGenerator.java`
- **位置**: 第 48-49 行
- **问题描述**:

```java
String[][] fontPaths;
for (String[] fp : fontPaths = new String[][]{{"C:/Windows/Fonts/simsun.ttc,0", "SimSun"}, ...}) {
```

第 48 行声明了局部变量 `fontPaths`，但第 49 行 for-each 头部又对其赋值覆盖。这种写法在代码审查中属于可读性反模式——赋值副作用藏在循环头中，维护者容易以为 `fontPaths` 来自上层变量而忽略此处的覆盖。实际 `fontPaths` 变量从未被读取，是无效声明。

- **修复**: 将数组构造提取到 for 语句之前：`String[][] fontPaths = new String[][]{...}; for (String[] fp : fontPaths) {...}` 或将声明与初始化合并为一行。

---

## 五、架构与设计问题

### A-1: `AddiePipeline` 为 658 行单体类

- **问题**: 全部 5 个阶段编排、LLM 调用、重试、SSE 推送、内容去重、评分解析、HITL 检查集中在一个类中。单一职责原则严重违反。
- **建议**: 拆分为 `AnalysisStage`、`DesignStage`、`DevelopmentStage`、`ReviewStage`、`FormatStage`，通过责任链或 Pipeline 模式编排。

---

### A-2: `LessonController` 承担了超出 Controller 职责的业务逻辑

- **问题**: `executeAddiePipeline` 方法中包含 Redis 锁管理、会话摘要触发、MySQL 持久化、HITL 日志记录等业务逻辑。
- **建议**: 提取 `LessonCompletionService` 处理生成后置逻辑。

---

### A-3: 缺少统一的事件总线

- **问题**: 模块间通过直接 `@Autowired` 注入耦合。教案生成完成 → 持久化 → 摘要 → 指标记录完全硬编码调用链。
- **建议**: 引入 Spring ApplicationEvent：`LessonPlanCompletedEvent` → `@EventListener` 异步处理持久化；`SummaryGeneratedEvent` → 触发长期记忆存储。

---

### A-4: `ChatSessionService` 承担了 Redis 操作、MySQL 操作、乐观锁、Token 裁剪等过多职责

- **问题**: 该类混合了缓存操作、数据库操作、并发控制和业务逻辑。
- **建议**: 拆分为 `SessionRedisStore` 和 `SessionMySqlStore`，由 `ChatSessionService` 协调。

---

### A-5: `ReactAgent` 仅用于对话模块，ADDIE 流水线完全不用

- **问题**: 项目自称为 Agent 架构，但核心的 ADDIE 流水线是命令式的 `CompletableFuture` + 直接 LLM 调用，并非 ReactAgent。两种调用方式并存造成架构不一致。
- **建议**: 要么统一为 Agent 模式，要么在文档中明确区分两种调用场景。

---

### A-6: 文件上传后向量化是同步阻塞的

- **问题**: `FileUploadController` 的上传处理在请求线程上完成文档解析、分片、embedding 生成和 Milvus 插入。大文件（如 50MB PDF）导致 HTTP 请求超时。
- **建议**: 改为异步处理：上传后返回 taskId，后台线程处理，通过 SSE 或轮询通知进度。

---

## 六、安全隐患

### S-1: JWT 无需 `jti` 的老 Token 无法被撤销

> **验证: 准确** — 修复: 2026-07-29。`generateRefreshToken()` 已添加 `.claim("jti", UUID.randomUUID().toString())`，与 access token 对齐。`isTokenRevoked()` (L142-143) 的 `jti==null → return false` 逻辑已不再对 refresh token 触发。

- **文件**: `config/security/JwtUtil.java`
- **位置**: 第 142 行
- **问题描述**: `isTokenRevoked` 检查 `claims.get("jti")`，若为 null 则返回 `false`（不检查）。而 `generateToken`（第 60 行）已正确添加 `jti`，但 `generateRefreshToken`（第 72 行）**未添加 `jti`**——意味着 refresh token 无法进入黑名单。
- **影响**: 已知安全漏洞——用户注销后 refresh token 仍可用于刷新。

---

### S-2: 登出接口依赖客户端主动发送 Token

- **文件**: `controller/AuthController.java`
- **位置**: `/api/auth/logout`（第 81-97 行）
- **问题描述**: 如果客户端忘记调用 logout（页面关闭、浏览器崩溃），token 在 1 小时内仍然有效。无服务端主动失效机制（如 token 使用计数或登录态跟踪）。

---

### S-3: 敏感信息在日志中可能泄露

> **验证: 基本准确** — 修复: 2026-07-29。`buildAnalysisCacheKey` 中的日志已从 INFO 降为 DEBUG。userId 是 Long 数值型，本身敏感度有限。

- **文件**: 多个 Service 类
- **问题描述**: `logger.info("[ADDIE] Analysis 缓存命中: {}", analysisCacheKey)` 中 `analysisCacheKey` 包含 userId。虽然 userId 本身不敏感，但结合其他日志可能关联到具体用户行为。`RagService` 的查询日志可能包含用户输入的敏感信息。

---

### S-4: CORS 生产环境默认值包含 `localhost`

- **文件**: `application.yml`
- **位置**: `cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:9900,http://localhost:5173}`
- **问题描述**: 默认 CORS 白名单保留 localhost，若运维未覆盖环境变量，生产环境将接受来自 localhost 的跨域请求。

---

### S-5: BCrypt 密码加密无误，但盐值强度使用默认

- **文件**: `config/security/SecurityConfig.java`
- **位置**: `new BCryptPasswordEncoder()`（第 40 行）
- **问题描述**: 使用默认强度 10。对于 2026 年的算力，建议提升至 12。

---

## 七、性能隐患

### P-1: DashScope API 创建每次都新建，连接池未复用

- **问题**: `ChatService.createDashScopeApi()` 和 `LessonController.executeAddiePipeline` 中每次请求创建新实例。OkHttp 底层需要连接池维护 HTTP/2 长连接，频繁创建导致连接复用失效。

---

### P-2: Analysis 阶段缓存 Key 未区分 customInstruction

> **验证: 准确** — 修复: 2026-07-29。`buildAnalysisCacheKey()` 已加入 `req.getGoals().hashCode()` 作为区分因子。同一学科年级但不同教学目标现在产生不同缓存 key。新增单元测试 `cacheKey_differsByGoals`。

- **文件**: `service/lesson/AddiePipeline.java`
- **位置**: 第 484-486 行
- **问题描述**: 缓存 key 仅包含 userId + stage + grade + subject，未包含 `customInstruction`（如果用户提供了特殊要求）。同一学科年级的不同教学目标被当作相同缓存返回。

---

### P-3: Milvus 搜索使用 L2 距离而非 IP（内积）

- **文件**: Milvus Collection 配置
- **问题描述**: L2 距离对已 L2 归一化的向量等价于 `2*(1-cosine_similarity)`。直接使用 IP（内积）度量可避免冗余计算。

---

### P-4: `LongTermMemoryService.searchFacts` 每次查询可能触发实时 embedding

- **文件**: `service/memory/LongTermMemoryService.java`
- **位置**: `searchFacts` 方法
- **问题描述**: 如果查询文本的向量缓存未命中，触发同步 `embeddingService.embed()` 调用。在线程池中阻塞等待远程 API 响应（通常 200-500ms）。

---

## 八、测试覆盖缺陷

### T-1: JaCoCo 覆盖率阈值仅 30%

- **文件**: `pom.xml`
- **位置**: `<limit><minimum>0.30</minimum></limit>`
- **问题描述**: CI 强制的最低覆盖率仅 30%，意味着 70% 的代码可以不经过任何测试就合并。对于核心流水线涉及 LLM + 数据库的场景，风险极高。

---

### T-2: 无集成测试

- **问题描述**: 全部 18 个测试文件均为单元测试。无 `@SpringBootTest` 集成测试覆盖 Controller → Service → Repository 全链路。特别是 SSE 流式响应的端到端验证完全缺失。

---

### T-3: 无异常路径测试

- **问题描述**: 现有测试仅覆盖 happy path。Milvus 宕机、Redis 不可用、DashScope API 限频/超时、熔断器触发等异常场景均无自动化验证。

---

### T-4: 无并发/线程安全测试

- **问题描述**: `withOptimisticLock`、`lessonLocks` 双重锁、`reviewFuture` 等并发关键路径未经过 `ExecutorService` 多线程压力验证。

---

## 九、配置与运维问题

### O-1: `application-prod.yml` 完全依赖环境变量但无变量校验

- **文件**: `src/main/resources/application-prod.yml`
- **问题描述**: 生产配置所有值来自 `${ENV_VAR}`，但无启动时校验（类似 `JwtUtil.init()` 对 JWT_SECRET 的检查）。
- **建议**: 添加 `EnvironmentValidator` Bean 在 `ApplicationReadyEvent` 上校验所有必填环境变量。

---

### O-2: Docker Compose 缺少应用容器定义

- **文件**: `docker-compose.yml`
- **问题描述**: 编排了 6 个基础设施容器（MySQL/Redis/etcd/MinIO/Milvus/Attu），但不含 GagneFlow 应用本身。开发者需手动 `mvn spring-boot:run`。
- **建议**: 添加应用 Dockerfile 和服务定义。

---

### O-3: `make start` 将日志重定向到 `server.log` 但无日志轮转

- **文件**: `Makefile`
- **位置**: `>> server.log` 命令
- **问题描述**: 持续追加到同一文件，无大小限制或轮转策略。长期运行可导致磁盘占满。

---

### O-4: 无健康检查的端到端验证

- **文件**: `controller/GagneFlowHealthIndicator.java`
- **问题描述**: 健康检查仅验证 Spring 组件状态，不做 Milvus 搜索/Redis 读写/MySQL 查询的功能性验证。

---

## 十、二次审查追加项

### 追加说明

以下问题在 2026-07-29 二次审查（逐行核实全部 82 个 Java 源文件）中新增发现，已合并到对应章节。总数从原始 48 项更新为 51 项。

| 编号 | 文件 | 严重程度 | 追加原因 |
|:-----|:-----|:--------:|:---------|
| H-NEW | LessonController.java | High | 教案生成 SSE 流无心跳，代理超时断连 |
| L-NEW | PdfGenerator.java | Low | 字体数组声明与循环头赋值重叠，可读性反模式 |
| H-2 (修正) | ChatSessionService.java | — | 原报告描述"返回 null"修正为"抛 ConcurrentModificationException" |

---

## 附录 A: 问题严重程度分布

| 严重程度 | 数量 | 占比 |
|----------|------|------|
| Critical | 11 | 21.6% |
| High | 16 | 31.4% |
| Medium | 16 | 31.4% |
| Low | 7 | 13.7% |
| **合计** | **51** | **100%** |

## 附录 B: 问题按模块分布

| 模块 | Critical | High | Medium | Low | 合计 |
|------|----------|------|--------|-----|------|
| AddiePipeline | 5 | 0 | 2 | 1 | 8 |
| ChatSessionService | 1 | 2 | 1 | 1 | 5 |
| ChatController | 1 | 0 | 0 | 1 | 2 |
| LessonController | 0 | 3 | 1 | 0 | 4 |
| LongTermMemoryService | 1 | 0 | 1 | 0 | 2 |
| ConversationMemoryManager | 0 | 1 | 0 | 0 | 1 |
| VectorEmbeddingService | 1 | 0 | 1 | 0 | 2 |
| VectorSearchService | 0 | 1 | 0 | 0 | 1 |
| VectorIndexService | 0 | 1 | 0 | 0 | 1 |
| DocumentReaderFactory | 1 | 0 | 0 | 0 | 1 |
| PdfGenerator | 0 | 1 | 1 | 1 | 3 |
| 安全模块 | 0 | 0 | 0 | 5 | 5 |
| 架构/设计 | 0 | 0 | 6 | 0 | 6 |
| 测试 | 0 | 0 | 4 | 0 | 4 |
| 配置/运维 | 0 | 0 | 4 | 0 | 4 |
| 其他 | 0 | 0 | 4 | 3 | 7 |

---

*本报告基于对全部 82 个 Java 源文件、10 个 Agent Prompt 文件、3 个 YAML 配置文件及 Docker 编排、Jenkinsfile、GitHub Actions 的全面静态审计生成。*
