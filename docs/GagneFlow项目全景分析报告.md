# GagneFlow 项目全景分析报告

> **生成日期**: 2026-07-29  
> **分析范围**: 全量源代码 (82 Java + 配置文件 + 文档)  
> **分析方法**: 静态代码审查 + 架构模式分析 + 依赖调用链追踪

---

## 一、项目概览

GagneFlow 是一个基于 **Spring AI Alibaba + DashScope** 的 **K12 智能教案生成系统**，核心能力覆盖：

| 能力 | 描述 |
|------|------|
| **ADDIE 教案自动生成** | Analysis → Design → Development → Review → Format 五阶段流水线 |
| **RAG 智能问答** | Milvus 向量库 + DashScope 重排序 + 多轮流式对话 |
| **文档管理与向量化** | 支持 PDF/Word/Markdown/TXT 上传，自动分片入库 |
| **多级会话记忆** | 短期窗口 + 长期摘要 + 向量语义检索 |
| **Copilot 交互模式** | 人机协作的教案微调与修订 |
| **HITL 质量安全** | 输出质量自动检测 + 人工审核触发 |

**访问入口**: `http://localhost:9900` | Swagger: `http://localhost:9900/swagger-ui.html`

---

## 二、技术栈全景

### 2.1 核心框架与版本

| 类别 | 技术 | 版本 |
|------|------|------|
| **语言** | Java | 17 |
| **框架** | Spring Boot | 3.3.5 |
| **AI 框架** | Spring AI Alibaba Agent Framework | 1.1.0.0-RC2 |
| **LLM 平台** | DashScope (通义千问) | SDK 2.17.0 |
| **向量数据库** | Milvus | SDK 2.6.10 (Server 2.5.10) |
| **缓存/会话** | Redis | 7 (Lettuce 客户端) |
| **持久化** | MySQL + JPA/Hibernate | 8.0 |
| **安全** | Spring Security + JJWT | 0.12.5 |
| **熔断** | Resilience4j | 2.2.0 |
| **监控** | Micrometer + Actuator | (Spring Boot 内置) |
| **PDF 生成** | Flying Saucer (iTextRenderer) | 9.1.22 |
| **文档解析** | PDFBox 3.0.4 / POI 5.4.0 | - |
| **API 文档** | SpringDoc OpenAPI (Swagger) | 2.6.0 |

### 2.2 外部依赖服务

```
┌─────────────────────────────────────────────────────┐
│                  GagneFlow App (:9900)                │
│  Spring Boot 3.3.5 + Spring AI Alibaba               │
└──────┬──────────┬───────────┬───────────┬────────────┘
       │          │           │           │
       ▼          ▼           ▼           ▼
   ┌──────┐  ┌──────┐   ┌────────┐  ┌──────────┐
   │MySQL │  │Redis │   │Milvus  │  │DashScope │
   │ :3306│  │ :6379│   │ :19530 │  │ (阿里云) │
   └──────┘  └──────┘   └────────┘  └──────────┘
                               │
                          ┌────┴────┐
                          │  etcd   │
                          │ (Milvus │
                          │  元数据) │
                          └─────────┘
                          │
                          ┌─────────┐
                          │  MinIO  │
                          │ (Milvus │
                          │  存储)   │
                          └─────────┘
```

---

## 三、目录结构与模块职责

```
GagneFlow/
├── agent-config/prompts/          # AI Agent 的 Prompt 定义 (10个 .md 文件)
│   ├── supervisor.md              #   Supervisor 调度 Agent
│   ├── planner.md                 #   任务规划 Agent
│   ├── executor.md                #   工具执行 Agent
│   ├── retrieval.md               #   知识检索 Agent
│   ├── review.md                  #   教案审查 Agent
│   ├── decision_guide.md          #   决策类型定义
│   ├── addie_analysis.md          #   ADDIE-Analysis 阶段 Prompt
│   ├── addie_design.md            #   ADDIE-Design 阶段 Prompt
│   ├── addie_development.md       #   ADDIE-Development 阶段 Prompt
│   └── addie_review.md            #   ADDIE-Review 阶段 Prompt
│
├── lesson-plan-docs/              # K12 教学知识库
│   ├── k12_curriculum.json        #   课标知识 (小/初/高·语数英)
│   ├── subject-formats.json       #   学科教学格式规范 (9个学科)
│   └── templates/                 #   10个教案模板 JSON
│
├── config/checkstyle/             # 代码规范 (行宽140, Tab等)
├── docs/                          # 18个历史迭代文档
│
├── src/main/java/com/gagneflow/
│   ├── Main.java                  #   Spring Boot 入口
│   ├── constant/                  #   常量定义
│   ├── dto/                       #   数据传输对象 (9个)
│   ├── entity/                    #   JPA 实体 (4个)
│   ├── repository/                #   JPA Repository (4个)
│   ├── config/                    #   配置层 (14个)
│   │   ├── security/              #     安全: JWT/Security/CORS
│   │   ├── DashScopeConfig.java   #     DashScope SDK 配置
│   │   ├── MilvusConfig.java      #     Milvus 连接配置
│   │   ├── RedisConfig.java       #     Redis 模板配置
│   │   ├── ExecutorConfig.java    #     共享线程池配置
│   │   ├── PipelineStageConfig.java #   ADDIE 阶段配置
│   │   └── RateLimitInterceptor.java # Redis 滑动窗口限流
│   ├── controller/                #   REST 控制器 (10个)
│   │   ├── AuthController.java    #     认证 (注册/登录/刷新/注销)
│   │   ├── ChatController.java    #     流式对话 / 线程池状态
│   │   ├── LessonController.java  #     教案生成 / PDF导出 / Copilot
│   │   ├── RagController.java     #     RAG 检索问答
│   │   ├── SessionController.java #     会话管理 (CRUD)
│   │   ├── FileUploadController   #     文件上传与向量化
│   │   ├── PromptAdminController  #     Prompt 版本管理
│   │   ├── MilvusCheckController  #     Milvus 健康检查
│   │   ├── GagneFlowHealthIndicator #   应用健康指标
│   │   └── GlobalExceptionHandler #     全局异常处理
│   ├── service/                   #   业务服务层
│   │   ├── lesson/                #     ADDIE 流水线核心
│   │   │   ├── AddiePipeline.java #      五阶段编排 (658行)
│   │   │   └── FormatTool.java    #      HTML 格式化 + PDF 准备
│   │   ├── chat/                  #     智能对话
│   │   │   ├── ChatService.java   #      对话 Agent 创建与摘要
│   │   │   ├── ChatSession.java   #      会话数据模型
│   │   │   └── ChatSessionService #      会话 Redis/MySQL 双写
│   │   ├── rag/                   #     RAG 检索增强
│   │   │   ├── RagService.java    #      完整 RAG 流水线
│   │   │   ├── RerankService.java #      DashScope 重排序
│   │   │   └── QueryRewriter.java #      查询改写 (规则+LLM)
│   │   ├── vector/                #     向量引擎
│   │   │   ├── VectorEmbeddingService # 文本向量化
│   │   │   ├── VectorSearchService #    相似搜索+重排序
│   │   │   └── VectorIndexService #     批量索引/目录索引
│   │   ├── document/              #     文档处理
│   │   │   ├── DocumentChunkService #   智能分片
│   │   │   ├── K12CurriculumLoader #   K12课标加载
│   │   │   ├── K12VectorInitializer #  启动时K12索引
│   │   │   └── SubjectFormatLoader #   学科格式加载
│   │   ├── reader/                #     文档解析器 (策略模式)
│   │   │   ├── DocumentReader.java     # 接口
│   │   │   ├── DocumentReaderFactory   # 工厂 (自动注册)
│   │   │   ├── PdfDocumentReader       # PDF 解析
│   │   │   ├── WordDocumentReader      # Word 解析
│   │   │   ├── MarkdownDocumentReader  # Markdown 解析
│   │   │   └── PlainTextDocumentReader # 纯文本解析
│   │   ├── memory/                #     多级记忆
│   │   │   ├── ConversationMemoryManager # 上下文构建/事实提取
│   │   │   ├── LongTermMemoryService    # 长期记忆 (Redis+向量)
│   │   │   └── TokenCounter.java        # Token 估算器
│   │   ├── prompt/                #     Prompt 管理
│   │   │   ├── PromptRegistry.java      # 版本注册与切换
│   │   │   ├── PromptExperiment.java    # A/B 实验
│   │   │   └── PromptMetricsCollector   # 指标收集
│   │   ├── pdf/PdfGenerator.java #     HTML→PDF 渲染
│   │   └── metrics/PipelineMetrics #   Micrometer 指标
│   └── agent/tool/                #   Agent 工具
│       ├── DateTimeTools.java     #     日期时间查询
│       └── InternalDocsTools.java #     内部文档检索
│
└── src/test/java/com/gagneflow/  # 18个测试文件
```

---

## 四、核心架构分析

### 4.1 整体分层架构

```
 ┌─────────────────────────────────────────────────────┐
 │                    表现层 (Controller)                │
 │  AuthController │ ChatController │ LessonController  │
 │  RagController  │ SessionController │ FileUpload...  │
 │  PromptAdminController │ GlobalExceptionHandler     │
 ├─────────────────────────────────────────────────────┤
 │                    安全层 (Security)                  │
 │  JwtAuthFilter → JwtUtil → BCrypt → RateLimit       │
 ├─────────────────────────────────────────────────────┤
 │                    服务层 (Service)                   │
 │  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
 │  │ Chat     │  │ ADDIE    │  │ RAG Pipeline     │  │
 │  │ Service  │  │ Pipeline │  │ (Rewrite→Search   │  │
 │  │ (Agent)  │  │ (5阶段)  │  │  →Rerank→Answer)  │  │
 │  └──────────┘  └──────────┘  └──────────────────┘  │
 │  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
 │  │ Memory   │  │ Vector   │  │ Document         │  │
 │  │ Manager  │  │ Engine   │  │ Pipeline         │  │
 │  │ (3级记忆) │  │ (3个服务) │  │ (Reader→Chunk)   │  │
 │  └──────────┘  └──────────┘  └──────────────────┘  │
 ├─────────────────────────────────────────────────────┤
 │                   基础设施层 (Infrastructure)         │
 │  MySQL(JPA) │ Redis(Lettuce) │ Milvus │ DashScope   │
 └─────────────────────────────────────────────────────┘
```

### 4.2 设计模式应用

| 模式 | 应用位置 | 说明 |
|------|----------|------|
| **策略模式** | `DocumentReader` + 4个实现 | 根据文件后缀动态选择解析器 |
| **工厂模式** | `DocumentReaderFactory` | 自动发现注册所有 Reader Bean |
| **模板方法** | `AddiePipeline` 五阶段 | 固定顺序执行，各阶段可独立替换 Prompt |
| **观察者/SSE** | `SseEmitter` | 流式推送各阶段进度到客户端 |
| **拦截器链** | `RateLimitInterceptor` + `JwtAuthFilter` | 请求预处理管线 |
| **双写模式** | `ChatSessionService` | Redis + MySQL 会话数据一致性 |
| **版本管理** | `PromptRegistry` | Prompt 版本化存储与热切换 |

---

## 五、核心流程详解

### 5.1 ADDIE 教案生成流程 (最核心)

```
用户请求 POST /api/lesson_plan
        │
        ▼
┌─ LessonController ──────────────────────────────────┐
│  1. JVM + Redis 双重锁防并发                          │
│  2. 提交到共享线程池异步执行                            │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼
┌─ AddiePipeline.execute() ───────────────────────────┐
│                                                       │
│  Phase 1: ANALYSIS (分析)                             │
│  ┌──────────────────────────────────────────────┐    │
│  │ • 查询 K12 课标 (K12CurriculumLoader)         │    │
│  │ • 查询学科格式 (SubjectFormatLoader)           │    │
│  │ • 构建 Analysis Prompt (含 Redis 缓存)         │    │
│  │ • 调用 LLM → 输出学情分析/知识点/教学目标       │    │
│  └──────────────────────────────────────────────┘    │
│                       │                               │
│  Phase 2-3: DESIGN + DEVELOPMENT (并行)               │
│  ┌──────────────────┐  ┌───────────────────────┐     │
│  │ DESIGN            │  │ DEVELOPMENT           │     │
│  │ • 重难点分析      │  │ • 每课时5环节          │     │
│  │ • 课时分布表      │  │   (导入/探究/练习/     │     │
│  │ • 教学策略表      │  │    总结/作业)          │     │
│  │ • 板书设计(HTML)  │  │ • 教师+学生活动       │     │
│  │ • 教学资源清单    │  │ • 设计意图标注        │     │
│  └──────────────────┘  └───────────────────────┘     │
│          CompletableFuture.allOf() 等待并行完成        │
│                       │                               │
│  Phase 4: FORMAT (格式化)                             │
│  ┌──────────────────────────────────────────────┐    │
│  │ FormatTool: Markdown → HTML                   │    │
│  │ • A4 打印样式 (SimSun/SimHei 字体)            │    │
│  │ • 表格/列表/标题/代码块转换                    │    │
│  └──────────────────────────────────────────────┘    │
│                       │                               │
│  Phase 5: REVIEW (异步审查 + HITL)                    │
│  ┌──────────────────────────────────────────────┐    │
│  │ • 5维度评分: 目标/内容/策略/课标/格式 (100分)  │    │
│  │ • HITL 四规则检查:                             │    │
│  │   1. 输出过短 (<800字符)                       │    │
│  │   2. 评分过低 (<60分)                          │    │
│  │   3. 质量降级 (连续2次评分下降)                 │    │
│  │   4. 不安全关键词检测                          │    │
│  │ • 合格教案 → 回灌 Milvus (三级过滤)            │    │
│  └──────────────────────────────────────────────┘    │
│                                                       │
│  Copilot 模式: 每阶段可暂停等待用户反馈                  │
│  ┌──────────────────────────────────────────────┐    │
│  │ SSE 推送 → 等待客户端 /api/lesson_plan/action  │    │
│  │ • revise: 带修改指令重新生成                   │    │
│  │ • continue: 继续下一阶段                       │    │
│  └──────────────────────────────────────────────┘    │
│                                                       │
│  输出: AddieResult                                    │
│  { analysis, design, development, review,             │
│    html, score, needsHumanReview }                    │
└───────────────────────────────────────────────────────┘
                       │
                       ▼
┌─ 后续处理 ───────────────────────────────────────────┐
│  • 持久化到 MySQL + Redis (双写)                       │
│  • 触发对话摘要 (summary-trigger-pairs: 5)            │
│  • 异步等待 Review 完成 (240s 超时)                    │
│  • 若 Redis 锁持有则释放                               │
└───────────────────────────────────────────────────────┘
```

### 5.2 RAG 检索增强生成流程

```
用户问题 POST /api/rag/query
        │
        ▼
┌─ QueryRewriter ──────────────────────────────────────┐
│  规则模式 (默认):                                      │
│    • 短查询 → 拼接历史最后一条用户消息                   │
│    • 提取关键词/引号内容/年级模式                       │
│  LLM 模式 (可选,默认关闭):                              │
│    • qwen-turbo 改写代词/省略/指代消解                  │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼
┌─ VectorSearchService.searchAndRerank() ──────────────┐
│  1. 文本 → Embedding (text-embedding-v4)              │
│     💡 熔断: @CircuitBreaker("dashscope")              │
│  2. Milvus L2 距离搜索 (nprobe=16, 自适应)            │
│     💡 熔断: @CircuitBreaker("milvus")                 │
│     • 用户范围过滤: ownerId + k12_curriculum + public │
│     • 返回 searchTopK=15 候选                          │
│  3. DashScope Rerank (gte-rerank)                     │
│     • 精排到 topN=3                                    │
│     💡 熔断降级: 保持原始顺序                           │
│  4. 相关性过滤 (threshold=0.3)                         │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼
┌─ RagService.generateAnswer() ────────────────────────┐
│  • 构建引用感知 Prompt: "[N] 参考内容"                  │
│  • 流式生成答案 (SSE 推送)                              │
│  • 引用完整性检查: Matcher [\d+]                        │
│  • 指标记录: 搜索耗时/候选数/最终数/平均相关性            │
└───────────────────────────────────────────────────────┘
```

### 5.3 文档上传与向量化流程

```
POST /api/files/upload (multipart/form-data)
        │
        ▼
┌─ FileUploadController ───────────────────────────────┐
│  • 后缀白名单校验: txt,md,pdf,docx                     │
│  • 文件大小限制: 50MB                                  │
│  • 保存到 ./uploads/ 目录                              │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼
┌─ VectorIndexService.indexFile() ─────────────────────┐
│  1. DocumentReaderFactory 选择解析器                   │
│  2. DocumentChunkService 智能分片                      │
│     • 按 Markdown 标题分节                             │
│     • 按段落分片 (max=800, overlap=160)                │
│     • 表格保持完整 (允许 1.5x)                         │
│     • 保留标题面包屑                                   │
│  3. VectorEmbeddingService 批量向量化                  │
│     • text-embedding-v4 → 1024维                      │
│     • L2 归一化                                        │
│  4. 插入 Milvus (含 userId, source 元数据)             │
│  5. 清理旧版本（按 source path）                        │
└───────────────────────────────────────────────────────┘
```

### 5.4 多级记忆管理流程

```
┌─────────────────────────────────────────────────────┐
│                  三级记忆架构                         │
│                                                       │
│  L1: 短期窗口记忆 (Redis)                              │
│  ┌──────────────────────────────────────────────┐    │
│  │ • 最近 N 轮对话 (max-window-size: 6)          │    │
│  │ • Token 上限: max-window-tokens: 2000         │    │
│  │ • 实时追加用户/助手消息                         │    │
│  └──────────────────────────────────────────────┘    │
│        │ 触发条件: 对话轮次 > 5 && 新增 > 3            │
│        ▼                                              │
│  L2: 摘要压缩记忆 (Redis + MySQL 双写)                 │
│  ┌──────────────────────────────────────────────┐    │
│  │ • LLM 摘要压缩 (qwen-plus)                     │    │
│  │ • 压缩比检查: >50% 则拒绝                       │    │
│  │ • 注入为 system prompt 前缀                     │    │
│  │ • 触发事实提取 → L3                             │    │
│  └──────────────────────────────────────────────┘    │
│        │                                               │
│        ▼                                              │
│  L3: 长期语义记忆 (Redis + 向量缓存)                    │
│  ┌──────────────────────────────────────────────┐    │
│  │ • 从摘要提取结构化事实 (7类×5条×2类上限)        │    │
│  │ • Redis 存储 + 30天 TTL                        │    │
│  │ • 向量语义搜索 (缓存优先, 回退实时embedding)     │    │
│  │ • 关键词搜索作为兜底                             │    │
│  └──────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────┘
```

---

## 六、模块依赖关系图

### 6.1 服务层关键依赖

```
                    ┌──────────────┐
                    │  Controller  │
                    └──┬──┬──┬──┬─┘
          ┌────────────┤  │  │  ├────────────┐
          ▼            ▼  │  ▼               ▼
   ┌──────────┐  ┌──────────┐  ┌──────────────────┐
   │  Chat    │  │  Lesson  │  │  RAG / Session   │
   │ Service  │  │Controller│  │  Controller       │
   └────┬─────┘  └────┬─────┘  └────┬─────────────┘
        │             │             │
   ┌────┴────┐   ┌────┴────────┐    │
   │ Chat    │   │  Addie      │    │
   │ Session │   │  Pipeline   │    │
   │ Service │   └──┬──┬──┬───┘    │
   └────┬────┘      │  │  │        │
        │     ┌─────┘  │  └─────┐  │
        │     ▼        ▼        ▼  │
        │  ┌──────┐┌──────┐┌───────┐
        │  │Format││K12   ││Subject│
        │  │Tool  ││Loader││Loader │
        │  └──────┘└──────┘└───────┘
        │
   ┌────┴──────────────────────────┐
   │   ConversationMemoryManager   │
   │   ┌──────────┐ ┌────────────┐ │
   │   │ Chat     │ │ LongTerm   │ │
   │   │ Session  │ │ Memory     │ │
   │   │ Service  │ │ Service    │ │
   │   └──────────┘ └─────┬──────┘ │
   └──────────────────────┼────────┘
                          │
         ┌────────────────┼────────────────┐
         ▼                ▼                ▼
   ┌──────────┐    ┌──────────┐    ┌──────────────┐
   │  Vector  │    │  Vector  │    │   Rerank     │
   │ Embedding│    │  Search  │    │   Service    │
   │ Service  │    │  Service │    └──────────────┘
   └──────────┘    └──────────┘
         │                │
         ▼                ▼
   ┌──────────────────────────┐
   │   Milvus (向量数据库)     │
   └──────────────────────────┘
```

### 6.2 数据存储职责划分

| 存储层 | 职责 | 数据内容 |
|--------|------|----------|
| **MySQL (JPA)** | 持久化的结构化数据 | User, SessionMeta, SessionMessage, PromptVersion |
| **Redis** | 热数据缓存 + 会话 | ChatSession (窗口对话), 长期记忆事实, JWT黑名单, 限流计数器, 分布式锁 |
| **Milvus** | 语义向量检索 | 文档分片向量, K12课标向量, 教案回灌向量 |
| **DashScope** | AI 推理服务 | 对话生成, Embedding, Rerank, 教案生成, 摘要生成 |

### 6.3 API 路由树

```
/api
├── /auth
│   ├── POST /register                # 用户注册
│   ├── POST /login                   # 用户登录 → JWT
│   ├── POST /refresh                 # 刷新 Token
│   └── POST /logout                  # 注销 Token (黑名单)
├── /chat_stream (SSE)                # 流式多轮对话
├── /chat
│   ├── GET  /pool-status             # 线程池状态
│   └── GET  /history                 # 对话历史
├── /lesson_plan (SSE)                # ADDIE 教案生成
│   ├── GET  /{subject}               # 学科占位符
│   ├── POST /                        # 生成教案
│   ├── POST /action                  # Copilot 交互
│   └── GET  /pdf/{sessionId}         # PDF 导出
├── /rag
│   └── POST /query                   # RAG 检索问答
├── /sessions                         # 会话 CRUD
│   ├── GET  /                        # 列出全部会话
│   ├── POST /                        # 创建会话
│   ├── DELETE /{sessionId}           # 删除会话
│   └── DELETE /{sessionId}/messages  # 清空消息
├── /files
│   └── POST /upload                  # 文件上传
├── /prompts/admin/...                # Prompt 版本管理
├── /milvus/health                    # Milvus 健康检查
└── /actuator/health                  # Spring Actuator
```

---

## 七、安全架构

### 7.1 认证流程

```
客户端                    服务端
  │                         │
  │ POST /api/auth/login    │
  │ ──────────────────────► │ JwtUtil.generateToken(userId)
  │                         │ → access token (1h) + refresh token (7d)
  │ ◄────────────────────── │
  │                         │
  │ 后续请求                 │
  │ Authorization: Bearer X │ JwtAuthFilter:
  │ ──────────────────────► │  1. 签名+过期校验
  │                         │  2. refresh token 拒绝
  │                         │  3. Redis 黑名单检查
  │                         │  4. 设置 SecurityContext
  │ ◄────────────────────── │
  │                         │
  │ POST /api/auth/logout   │
  │ ──────────────────────► │ jwtUtil.revokeToken()
  │                         │ → Redis 黑名单 (TTL = 剩余有效期)
```

### 7.2 安全特性清单

| 特性 | 实现方式 |
|------|----------|
| JWT 认证 | HMAC-SHA256, 强制环境变量密钥 ≥32字节 |
| Token 撤销 | Redis 黑名单 (键: `gagneflow:jwt:blacklist:{jti}`) |
| 密码加密 | BCryptPasswordEncoder |
| CSRF | 关闭 (无状态 API) |
| CORS | 可配置白名单 (默认 localhost:9900,5173) |
| 限流 | Redis ZSET 滑动窗口 (Lua 原子化) |
| 分布式锁 | Redis `SETNX` + JVM `ConcurrentHashMap` 双重锁 |
| 安全上下文传递 | `MODE_INHERITABLETHREADLOCAL` + `@CurrentUser` 注解 |
| 日志脱敏 | JWT Token 仅展示前后4位 |

### 7.3 限流策略

| 接口 | 限制 | 窗口 |
|------|------|------|
| `/api/auth/login` | 5次 | 60秒 |
| `/api/chat_stream` | 10次 | 60秒 |
| `/api/lesson_plan` | 2次 | 60秒 |
| `/api/rag/query` | 30次 | 60秒 |

---

## 八、可观测性

### 8.1 Micrometer 指标 (PipelineMetrics)

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `gagneflow.rag.search.total` | Counter | RAG 搜索总次数 |
| `gagneflow.rag.search.duration` | Timer | 搜索耗时分布 |
| `gagneflow.rag.candidates` | DistributionSummary | 每次检索候选数 |
| `gagneflow.rag.citation.loss` | Counter | 引用丢失次数 |
| `gagneflow.memory.summary.compression` | Counter | 摘要压缩触发次数 |
| `gagneflow.memory.context.build` | Counter | 上下文构建次数 |
| `gagneflow.addie.stage.total` | Counter | ADDIE 阶段执行次数 |
| `gagneflow.addie.stage.duration` | Timer | 阶段耗时分布 |
| `gagneflow.hitl.trigger.total` | Counter | HITL 人工审核触发次数 |

### 8.2 健康检查

| 端点 | 说明 |
|------|------|
| `/actuator/health` | Spring Actuator (含 DB/Redis/Disk) |
| `/milvus/health` | Milvus 连接状态 |
| `/api/chat/pool-status` | 线程池状态 (active/pool/queue/completed) |
| `/actuator/metrics` | Prometheus 指标导出 |

---

## 九、CI/CD

项目采用双 CI 流水线：

### GitHub Actions (`.github/workflows/ci.yml`)

```
Checkstyle → Unit Test + JaCoCo → Build → Archive
```
- 触发分支: `master`, `develop`, `feature/**`, `fix/**`
- PR 触发: `master`, `develop`

### Jenkins (`Jenkinsfile`)

```
Checkout → Checkstyle → Unit Test + JaCoCo → Build → Archive
                                                    │
                                          failure → 邮件通知
```

- Checkstyle 规则: `config/checkstyle/checkstyle.xml`
- JaCoCo 覆盖率阈值: 最低 30%
- 测试 Profile: `spring.profiles.active=test` (使用 H2 内存数据库)

---

## 十、潜在优化建议

### 10.1 架构层面

| 序号 | 问题 | 影响 | 建议 |
|------|------|------|------|
| 1 | **AddiePipeline 过大 (658行)** | 可维护性差, 单点复杂度高 | 拆分为独立的 Stage 类: `AnalysisStage`, `DesignStage` 等, 使用责任链或 Pipeline 模式 |
| 2 | **LessonController 任务过重** | 教案生成+PDF导出+Copilot交互+摘要触发+会话持久化全部耦合 | 将 `executeAddiePipeline` 中的持久化/摘要逻辑提取到独立的 `LessonCompletionService` |
| 3 | **缺少统一的事件总线** | 各模块通过直接注入耦合 | 引入 Spring Event/ApplicationEventPublisher，如: 教案生成完成事件、摘要生成事件 |
| 4 | **MCP Client 处于空置状态** | 资源浪费, 代码死代码 | 若不使用则移除依赖或加 `@ConditionalOnProperty` 完整控制 |

### 10.2 性能层面

| 序号 | 问题 | 影响 | 建议 |
|------|------|------|------|
| 5 | **Design/Development 阶段提示可真正并行** | 当前 `CompletableFuture.allOf()` 并行但共用同一个 LLM 调用链，被 DashScope API 限频 | 引入请求级队列/令牌桶控制并发 LLM 调用 |
| 6 | **Redis 向量缓存未设置上限** | 大量会话的长期记忆向量可能撑爆 Redis | 为长期记忆向量设置 LRU 淘汰策略和数量上限 |
| 7 | **Analysis 阶段 Redis 缓存Key简单** | 缓存粒度可能不够精确 | 将缓存 Key 从简单的 subject+grade 升级为包含具体 customInstruction hash 的复合键 |

### 10.3 可靠性层面

| 序号 | 问题 | 影响 | 建议 |
|------|------|------|------|
| 8 | **PdfGenerator 字体查找硬编码** | 仅支持 Windows/Mac/Linux 固定路径 | 支持 `application.yml` 配置字体路径, Docker 镜像内置中文字体 |
| 9 | **K12VectorInitializer 启动时全量重建索引** | 若已索引数据量大，启动耗时过长 | 增加幂等性检查 (checksum 比对)，仅增量/变更重建 |
| 10 | **限流 Redis Lua 脚本内联为字符串** | 维护困难，无语法高亮 | 提取为 `.lua` 文件，使用 `ClassPathResource` 加载 |

### 10.4 代码质量

| 序号 | 问题 | 影响 | 建议 |
|------|------|------|------|
| 11 | **JaCoCo 覆盖率阈值仅30%** | 测试覆盖不足的质量信号 | 逐步提升至 60%+，并按模块分别设置阈值 |
| 12 | **ChatRequest 字段命名违背 Java 惯例** | `@JsonProperty("Id")` / `@JsonProperty("Question")` 大写首字母 | 统一为小写 camelCase，通过 Jackson 配置兼容旧客户端 |
| 13 | **SQL 注入潜在风险(低)** | `VectorSearchService` 中的搜索表达式拼接 `userId` | 使用参数化查询或 Milvus SDK 的参数绑定 API |
| 14 | **缺少集成测试** | 仅 18 个单元测试，无端到端验证 | 添加 `@SpringBootTest` 集成测试覆盖核心流程 |
| 15 | **SecurityConfig 类型强转不安全** | 匿名内部类链式调用中使用强制类型转换 | 拆分为命名方法，去掉 `(SecurityFilterChain)` 和 `AbstractHttpConfigurer` 强转 |

### 10.5 运维层面

| 序号 | 问题 | 影响 | 建议 |
|------|------|------|------|
| 16 | **应用日志写入 server.log 无轮转** | `make start` 的 `>> server.log` 无限增长 | 使用 Logback 的 `RollingFileAppender` 替代 |
| 17 | **Docker Compose 无应用容器** | 仅编排基础设施，应用需手动启动 | 添加应用 Dockerfile 和服务定义，实现 `docker compose up` 一键全栈启动 |
| 18 | **缺少 OpenTelemetry 追踪** | 无法追踪分布式调用链 | 集成 Spring Cloud Sleuth / Micrometer Tracing |

---

## 十一、数据流总结

```
┌─────────────────────────────────────────────────────────────────────┐
│                          GagneFlow 数据全景流                        │
│                                                                      │
│  ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐   │
│  │ 用户输入  │────►│ 安全过滤  │────►│ 限流检查  │────►│ 业务路由  │   │
│  │ (API)    │     │ (JWT)    │     │ (Redis)  │     │ (Controller)  │
│  └──────────┘     └──────────┘     └──────────┘     └────┬─────┘   │
│                                                          │          │
│              ┌───────────────────────────────────────────┤          │
│              ▼                    ▼                      ▼          │
│     ┌──────────────┐    ┌──────────────┐    ┌──────────────────┐   │
│     │  智能对话     │    │  教案生成     │    │  RAG 检索问答     │   │
│     │  (Agent)     │    │  (ADDIE)     │    │  (Rewrite→Rerank) │   │
│     └──────┬───────┘    └──────┬───────┘    └────────┬─────────┘   │
│            │                   │                     │              │
│            ▼                   ▼                     ▼              │
│     ┌──────────────────────────────────────────────────────────┐   │
│     │                    DashScope (阿里云)                      │   │
│     │  • Chat (qwen-max)  • Embedding (text-embedding-v4)      │   │
│     │  • Rerank (gte-rerank) • Summary (qwen-plus)            │   │
│     └──────────────────────────────────────────────────────────┘   │
│            │                   │                     │              │
│            ▼                   ▼                     ▼              │
│     ┌──────────┐       ┌──────────────┐     ┌──────────────┐      │
│     │  Redis   │       │    Milvus    │     │    MySQL     │      │
│     │ • 会话   │       │ • 文档向量   │     │ • 用户       │      │
│     │ • 记忆   │       │ • K12课标    │     │ • 会话元数据 │      │
│     │ • 锁/限流│       │ • 教案回灌   │     │ • 消息历史   │      │
│     │ • 黑名单 │       │              │     │ • Prompt版本 │      │
│     └──────────┘       └──────────────┘     └──────────────┘      │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                     输出 (SSE 流式)                            │   │
│  │  • 对话回答  • 教案 HTML  • RAG 引用答案  • Copilot 交互      │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 十二、总结

GagneFlow 是一个**架构设计较为成熟的 K12 AI 教育应用**，核心亮点包括：

1. **精心设计的 ADDIE 五阶段流水线**: 严格遵循教学设计模型，支持快速模式和 Copilot 人机协作模式
2. **完整的三级记忆体系**: 短期窗口→摘要压缩→长期语义检索，有效控制上下文长度
3. **多重可靠性保障**: Resilience4j 熔断器、Redis+Lua 分布式限流、JVM+Redis 双重锁、多级降级策略
4. **版本化 Prompt 管理**: 支持热切换和 A/B 实验
5. **HITL 安全机制**: 四规则自动检测教案质量并触发人工审核

需要关注的主要改进方向集中在 **单体类的复杂度拆分**、**测试覆盖率提升**、**容器化部署完善** 以及 **性能优化(LLM 调用并发控制)** 等方面。

---

*本报告基于对项目全部 82 个 Java 源文件、10 个 Agent Prompt 文件、完整的 YAML 配置集及 Docker 编排文件的系统性深度审查生成。*
