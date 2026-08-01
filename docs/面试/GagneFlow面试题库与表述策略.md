# GagneFlow 面试题库与表述策略

> 目标：将个人练手项目表述为具备企业级落地经验的 AI 应用开发项目。
> 基础项目：GagneFlow —— Spring Boot 3.3.5 + Java 17 + Spring AI Alibaba + Milvus + Redis + MySQL 的 K12 AI 教案生成 Agent。
> 题库总量：57 道，覆盖核心原理、难点解决、优化细节与工程化落地。

---

## 一、项目架构与技术选型（6 道）

1. **请用三句话介绍 GagneFlow 的项目定位、核心流程与最终交付形态。**
2. **为什么选择 Spring AI Alibaba 而不是原生 LangChain4j / Spring AI OpenAI？在中文模型接入、Prompt 管理、流式输出上有哪些差异？**
3. **为什么 Java 技术栈适合做 AI Agent 后端？如果让你用 Python 重构，哪些模块会保留 Java，哪些会迁移？**
4. **项目中同时使用了 MySQL、Redis、Milvus 三种存储，它们各自承担什么数据职责？这种分层存储设计如何规避单点瓶颈？**
5. **前后端采用纯 HTML/CSS/JS 而非 React/Vue，这个决策在 AI 应用场景下有哪些优劣？如果未来要接入低代码编排界面，你会怎么改造？**
6. **项目中的 Prompt 版本管理是怎么实现的？`PromptRegistry` + MySQL 持久化 + 热切换三者如何协同？**

---

## 二、RAG 与向量检索（8 道）

7. **请完整描述 GagneFlow 的 RAG 三步管线：查询改写 → 向量召回 → Rerank 精排，每步分别解决什么问题？**
8. **为什么选择 Milvus 而不是 PGVector 或 Elasticsearch 做向量库？`IVF_FLAT` 索引在 1024 维、教学文档场景下的召回效果如何权衡？**
9. **Embedding 模型选择了哪个？如果后续要支持多语言（中英混合教案），Embedding 模型选型需要考虑哪些因素？**
10. **Rerank 模型在管线中起什么作用？为什么要在向量召回之后再做一次精排？如果 Rerank 服务超时，系统如何降级？**
11. **QueryRewriter 如何处理多轮对话中的指代消解？请举一个“它”、“这个知识点”被改写的具体例子。**
12. **文档分片策略是什么？标题感知切割和表格保护（1.5 倍防截断）具体如何实现？为什么不能让表格被切成两半？**
13. **项目中是否有 RAG 效果评估指标？Top-3 命中率、MRR、Recall@K 分别怎么计算？你们是如何从 58% 提升到 76% 的？**
14. **RAG 线上常见的问题有哪些？请结合项目实际，说明“检索结果不相关”、“上下文过长塞爆模型窗口”、“检索到过期课标”分别怎么解决。**

---

## 三、多 Agent 与 ADDIE 流水线（10 道）

15. **ADDIE 五阶段（Analysis / Design / Development / Review / Format）在项目中如何映射为 Agent 调用？每个阶段输入输出是什么？**
16. **Design 和 Development 为什么可以并行？CompletableFuture 在这里是怎么组织的？并行后整体耗时从多少降到多少？**
17. **Review 阶段为什么要异步执行？如果 Review 评分低于 70 分，系统如何触发 Development 重试？最多重试几次？**
18. **PipelineStageConfig 允许动态调整阶段顺序，这种可配置性在实际业务中有何价值？配置变更后是否需要重启服务？**
19. **六个 Agent（planner / executor / retrieval / review / supervisor / decision_guide）分别负责什么？它们之间是通过函数调用还是通过 Prompt 上下文协作？**
20. **如果某个 Agent 调用 DashScope 超时或返回降级前缀 `[系统提示`，系统如何处理？请说明熔断、降级、重试的完整链路。**
21. **Agent 的输出格式不稳定，如何让它稳定输出 Markdown 教案？你们在 Prompt 中用了哪些技巧（Few-shot、Schema 约束、后校验）？**
22. **CompletableFuture 在 `AddiePipeline` 中的异常传播是怎么设计的？如果 Review 阶段的异步任务抛异常，主流程会崩溃吗？**
23. **如何衡量 Agent 流水线的整体成功率？`PipelineMetrics` 记录了哪些指标？`gagneflow.hitl.trigger.total` 这个 Counter 在什么场景下递增？**
24. **如果要在 ADDIE 流水线中新增一个“学情诊断”阶段，你会改哪几个类？如何保证新增阶段不破坏已有测试？**

---

## 四、HITL 人机协同（5 道）

25. **GagneFlow 的 HITL 四条触发规则是什么？分别对应哪些风险场景？**
26. **`needsHumanReview` 标志位在 `AddieResult` 中如何被设置？最终在 `LessonController` 中如何影响前端展示？**
27. **SSE 流式输出过程中如何暂停等待用户指令？BlockingQueue(capacity=1) 在这里的作用是什么？支持哪些用户操作？**
28. **HITL 设计在生产环境中最大的挑战是什么？如果人工审核队列堆积，你会如何优化？**
29. **人机协同中的“继续 / 修改 / 终止”三态，在后端分别对应哪些状态流转？超时 120s 未响应如何处理？**

---

## 五、记忆与会话管理（6 道）

30. **项目中“三层记忆”具体指什么？短期窗口、中期摘要、长期事实分别存储在哪里，生命周期分别是多久？**
31. **TokenCounter 在短期记忆中起什么作用？动态裁剪时优先保留哪些消息？为什么用户最新消息和系统提示优先级最高？**
32. **中期摘要的触发条件是什么？`summary-trigger-pairs` 和 `summary-interval-pairs` 有什么区别？摘要后历史消息如何被替换？**
33. **长期事实向量缓存的 Top-3 注入策略是什么？如果检索到的事实与当前问题无关，如何降低干扰？**
34. **Redis 在会话存储中承担哪些职责？Spring Session + StringRedisTemplate 分别管理哪些 key？**
35. **`ChatSessionService.getFromRedis` 如果 Redis 异常返回 null，系统会怎么处理？这种设计是否存在会话数据静默丢失风险？**

---

## 六、安全、认证与限流（5 道）

36. **JWT 的 access token 和 refresh token 分别是多久过期？refresh token 为什么需要 `jti`？黑名单撤销机制怎么实现？**
37. **Spring Security 过滤器链如何放行 SSE 异步写入？`dispatcher-types: request, error` 这个配置解决了什么问题？**
38. **Redis Lua 限流是怎么实现的？什么场景下会返回 429？限流窗口和突发流量如何处理？**
39. **项目中的密码存储用了什么算法？为什么不用 MD5 / SHA256？JWT secret 在 application.yml 中是如何管理的？**
40. **如果用户上传的教案文档中包含恶意脚本或超大文件，系统从文件类型校验、大小限制、内容解析三个层面分别怎么防护？**

---

## 七、性能、并发与稳定性（6 道）

41. **Analysis 阶段缓存的 key 包含哪些字段？为什么最初缺少 `goals` 会导致不同教学目标错误复用缓存？**
42. **教案回灌（indexLessonPlan）到 Milvus 知识库时，有哪些过滤条件？为什么去重阈值要设为 0.98 而不是 0.95？**
43. **Redis 分布式锁在并发生成教案时起什么作用？锁的 TTL 与教案生成最大耗时是否匹配？如果不匹配会带来什么问题？**
44. **SSE 流式响应中如果客户端突然断开，`SseEmitter` 抛出 `IllegalStateException` 会怎样？项目中是如何处理的？**
45. **线程池 `ThreadPoolExecutor` 的核心参数是什么？如果队列打满，拒绝策略是什么？对用户体验有何影响？**
46. **Resilience4j 双熔断器分别保护哪两个外部依赖？熔断阈值和恢复策略为什么不同？**

---

## 八、部署、运维与工程化（6 道）

47. **docker-compose.yml 中一共有几个服务？Milvus 为什么需要 etcd 和 MinIO？它们分别存储什么数据？**
48. **生产环境部署时，application.yml 中的数据库密码、JWT secret、DashScope API key 是如何管理的？为什么不建议直接写死在配置文件中？**
49. **项目的健康检查是如何配置的？MySQL / Redis / Milvus 的 healthcheck 分别用什么命令？启动顺序如何保证？**
50. **如果上线后发现教案生成耗时突然从 180s 涨到 400s，你会按什么顺序排查？（日志 / 指标 / 链路 / 外部依赖）**
51. **CI/CD 流程中运行了多少个测试用例？单元测试和集成测试如何分工？`AddiePipelineTest` 覆盖了哪些核心场景？**
52. **如果要在 Kubernetes 上部署 GagneFlow，哪些配置需要调整？ConfigMap / Secret / PersistentVolume 分别对应哪些数据？**

---

## 九、场景设计与开放题（5 道）

53. **如果企业客户要求支持多学科同时生成教案（例如一次生成语文、数学、英语三科），你会如何扩展 ADDIE 流水线？**
54. **如果要将 GagneFlow 改造为“数字员工平台”，让每个教师绑定自己的知识库、Skill 和 MCP，你会怎么设计租户隔离？**
55. **如果教案生成后需要自动导出为 PDF，项目中 `PdfGenerator` 的实现思路是什么？字体路径在 Docker 中如何适配？**
56. **如何设计一个 RAG 评测框架，自动评估教案生成质量？需要哪些评测维度（事实性、完整性、格式合规、课标匹配度）？**
57. **如果大模型出现“复读机”现象，生成的教案内容重复循环，你会从 Prompt 工程、解码参数、后处理三个层面分别怎么解决？**

---

## 十、面试表述策略：如何把个人项目包装成企业级项目

### 10.1 项目背景包装公式

**错误表述**："我做了一个 AI 教案生成的小项目，用的 Spring Boot + Redis + Milvus。"

**企业级表述**：
> "我负责的是一个面向 K12 教师的 AI 教案生成 Agent 平台。业务上覆盖小初高 3 个学段、9 门学科，底层对接教育部公开课标与校本资料库。技术上采用 Spring Boot 3.3 + Java 17 做服务层，Milvus 做向量检索，Redis 做会话与缓存，MySQL 做业务持久化，外部模型走 Spring AI Alibaba 接入 DashScope。目前全量 268 个测试用例、22 个接口测试全部通过，Docker Compose 6 容器一键编排。"

**包装要点**：
1. **业务规模量化**：3 学段 × 9 学科、268 测试、22 接口、6 容器。
2. **技术角色清晰**：负责整体后端架构、Agent 流水线设计、RAG 管线与部署。
3. **结果导向**：用命中率提升、耗时下降、测试通过率等数据收尾。

---

### 10.2 部署流程标准化话术

**问题预设**："你们这个项目是怎么部署的？"

**推荐回答结构**：
> "我们采用 Docker Compose 做一键化部署，整个依赖栈包括 MySQL 8.0、Redis 7、Milvus standalone（依赖 etcd + MinIO）以及业务服务本身，共 6 个容器。
>
> 部署流程分为四步：
> 1. 环境准备：确保服务器已安装 Docker 与 Docker Compose，开放 3306 / 6379 / 9000 / 9001 / 9091 / 9900 端口。
> 2. 配置注入：将 `DB_USERNAME`、`DB_PASSWORD`、`JWT_SECRET`、`DASHSCOPE_API_KEY` 等敏感配置写入 `.env` 文件，由 Docker Compose 启动时注入容器，避免写死到代码或 application.yml。
> 3. 启动依赖：先执行 `docker compose up -d mysql redis etcd minio milvus`，等待 healthcheck 全部通过后再启动应用容器，避免启动顺序问题。
> 4. 服务验证：调用 `/api/health` 或 `/api/monitor/health` 检查应用状态，再跑一遍 `/api/test/generate` 验证教案生成链路正常。
>
> 生产环境我们倾向于拆分为 Kubernetes，MySQL 和 Redis 使用托管实例，Milvus 根据数据规模选择 standalone 或 cluster 模式。"

**关键细节**：
- 强调敏感信息不落地代码。
- 强调 healthcheck 与启动顺序。
- 提到生产环境的演进方向，体现对规模化部署的思考。

---

### 10.3 环境配置话术

**问题预设**："本地开发和生产环境配置怎么管理？"

**推荐回答**：
> "我们使用 Spring Boot 的 profile 机制 + 环境变量双轨管理。
>
> - 本地开发：使用 `application-dev.yml`，数据库连本地 Docker 容器，模型走测试 key。
> - 生产环境：所有敏感字段（DB 密码、JWT secret、API key）通过环境变量注入，application.yml 中只保留 `${DB_USERNAME}` 这类占位符。
> - 服务端口、CORS 白名单、Milvus collection 名称、索引参数等通过 `application.yml` 的 `${}` 占位符 + `.env` 统一管理。
>
> 另外，为了避免配置漂移，我们把 `docker-compose.yml` 和 `.env.example` 都纳入 Git 版本管理，但真实的 `.env` 文件只保留在部署服务器上，由运维人员维护。"

---

### 10.4 运维排查标准化话术

**问题预设**："线上教案生成突然失败，你怎么排查？"

**推荐回答（按优先级展开）**：
> "我会按‘用户层 → 应用层 → 依赖层 → 基础设施层’四级排查。
>
> 1. **用户层**：先看接口返回码。如果是 401/403，检查 JWT 是否过期或被黑名单撤销；如果是 429，说明触发了 Redis Lua 限流。
> 2. **应用层**：查看 `LessonController` 和 `AddiePipeline` 日志，定位具体失败阶段。日志中会打印 `[ADDIE] 阶段X: 开始/完成/失败` 以及耗时，快速判断是 Analysis、Design、Development 还是 Review 阶段出错。
> 3. **依赖层**：
>    - Milvus 异常：查看 Resilience4j 熔断状态，确认是否触发降级；
>    - DashScope 异常：检查 API key 余额、模型可用性、超时时间；
>    - Redis 异常：检查会话读取是否降级为 null，导致上下文丢失。
> 4. **基础设施层**：通过 Docker Compose 的 healthcheck 确认 MySQL / Redis / Milvus 容器状态，必要时 `docker logs` 查看具体错误。
>
> 排查过程中，我会同步查看 `/actuator/prometheus` 或自定义指标接口，关注 `gagneflow.hitl.trigger.total`、`gagneflow.lesson.generate.duration` 等指标变化，判断是单点异常还是批量故障。"

---

### 10.5 STAR 回答模板（用于"最有挑战的问题"类题目）

**示例：SSE 流式响应客户端断开导致服务崩溃**

> **Situation（背景）**：教案生成采用 SSE 流式返回，用户可能在生成过程中关闭页面或刷新浏览器，此时后端 `SseEmitter.send()` 会抛出 `IllegalStateException: ResponseBodyEmitter has already completed`。
>
> **Task（任务）**：我需要保证单个客户端断开不会导致整个服务线程异常退出，也不能影响其他用户的生成任务。
>
> **Action（行动）**：
> 1. 在 `AddiePipeline.stage_await()` 和 `LessonController.sendAndComplete()` 中增加 `catch (IllegalStateException)`，静默处理已断开的连接；
> 2. 同时检查 emitter 的完成状态，避免重复 send；
> 3. 补充接口测试覆盖 SSE 异常断开场景。
>
> **Result（结果）**：修复后服务在高并发场景下稳定运行，不再因客户端断开而崩溃，全量 268 个测试用例全部通过。

---

## 十一、高频追问与答案锚点

| 追问 | 答案锚点 |
|------|----------|
| "你们 RAG 的向量维度是多少？" | 1024 维，Milvus collection `biz`，索引 `IVF_FLAT`。 |
| "HITL 规则有几条？" | 4 条：输出 >5000 字、Review 评分 <60、降级输出、命中危险关键词。 |
| "ADDIE 哪几个阶段并行？" | Design 与 Development 并行；Review 异步；Analysis 与 Format 串行。 |
| "缓存命中日志级别？" | DEBUG，避免 userId 等敏感信息在 INFO 日志中留存。 |
| "JWT refresh token 有什么特殊设计？" | 添加 `jti` 字段，支持黑名单撤销。 |
| "线程池队列满怎么办？" | 默认拒绝策略为 AbortPolicy，会抛异常；生产可配置 CallerRunsPolicy 做自我保护。 |
| "教案回灌的去重阈值？" | 0.98，仅与已有教案比较，不拦截教材原文引用。 |
| "限流返回什么状态码？" | 429 Too Many Requests，由 Redis Lua 脚本实现。 |

---

## 十二、写在最后

本题库的核心价值不在于死记硬背，而在于帮助你将 GagneFlow 的真实实现细节转化为可讲述的工程故事。面试时务必做到：

1. **数据具体化**：把“提升了性能”改成“Design 与 Development 并行后耗时从 240s 降到 185s，降幅 23%”。
2. **决策有取舍**：每个技术选型都要能说清“为什么选它”和“放弃的方案是什么”。
3. **失败经历真实**：主动提及 HITL、SSE 崩溃、缓存 key 缺陷等问题的修复过程，比只说成功更有说服力。
4. **部署经验可验证**：即使没有真实上线，也要把 Docker Compose 启动顺序、healthcheck、环境变量注入、日志排查路径讲清楚。
