# GagneFlow

> 基于 Spring AI Alibaba + DashScope 的 K12 智能教案生成系统 —— RAG 问答 · ADDRF 流水线 · 多轮对话 · 多级记忆

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI Alibaba](https://img.shields.io/badge/Spring%20AI%20Alibaba-1.1.0-blue.svg)](https://sca.aliyun.com/)
[![Milvus](https://img.shields.io/badge/Milvus-2.5.10-00bcd4.svg)](https://milvus.io/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## 目录

- [项目简介](#项目简介)
- [系统架构](#系统架构)
- [核心能力](#核心能力)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [API 接口](#api-接口)
- [配置说明](#配置说明)
- [核心流程](#核心流程)
- [安全机制](#安全机制)
- [开发指南](#开发指南)
- [部署运维](#部署运维)

---

## 项目简介

GagneFlow 面向 K12 教育场景，提供从教学需求输入到完整教案输出的全链路 AI 能力：

- **智能问答**：基于 Milvus 向量库 + DashScope 重排序的 RAG 多轮流式对话
- **教案生成**：遵循 ADDRF 教学设计模型，自动生成符合课标的 HTML 教案并导出 PDF
- **文档管理**：支持 PDF / Word / Markdown / TXT 上传，自动分片、向量化入库
- **会话记忆**：短期窗口 + 摘要压缩 + 长期语义检索的三级记忆体系
- **人机协作**：Copilot 模式支持阶段暂停、修改指令、继续生成
- **质量安全**：HITL 自动质量检测与人工审核触发机制

---

## 系统架构

```
┌─────────────────────────────────────────────────────────┐
│                    表现层 (REST + SSE)                    │
│   AuthController  ChatController  LessonController      │
│   RagController   SessionController  FileUpload...      │
├─────────────────────────────────────────────────────────┤
│                    安全层                                │
│   JwtAuthFilter → JwtUtil → RateLimitInterceptor        │
├─────────────────────────────────────────────────────────┤
│                    服务层                                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Chat Service │  │ ADDRF        │  │ RAG Pipeline │  │
│  │ (ReactAgent) │  │ Pipeline     │  │ (Rewrite→    │  │
│  │              │  │ (5-Stage)    │  │ Search→Rerank│  │
│  │              │  │              │  │ →Answer)     │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Memory       │  │ Vector       │  │ Document     │  │
│  │ Manager      │  │ Engine       │  │ Pipeline     │  │
│  │ (3-Level)    │  │ (Embed/Search│  │ (Reader→     │  │
│  │              │  │  /Index)     │  │ Chunk→Index) │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
├─────────────────────────────────────────────────────────┤
│                    基础设施层                             │
│  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────────┐   │
│  │ MySQL  │  │ Redis  │  │ Milvus │  │ DashScope  │   │
│  └────────┘  └────────┘  └────────┘  └────────────┘   │
└─────────────────────────────────────────────────────────┘
```

---

## 核心能力

### ADDRF 教案生成流水线

| 阶段 | 职责 | 输出 |
|------|------|------|
| **Analysis** 分析 | 学情诊断、知识点清单、三维教学目标、前置知识 | 结构化学情报告 |
| **Design** 设计 | 教学重难点、课时分布、教学策略(含理论依据)、板书设计、资源清单 | 教学方案蓝图 |
| **Development** 开发 | 每课时 5 环节(导入/探究/练习/总结/作业)，含师生双边活动与设计意图 | 详案内容 |
| **Review** 审查 | 5 维度评分(目标/内容/策略/课标/格式 共100分)，不合格触发修订 | 质量评估报告 |
| **Format** 格式化 | Markdown → HTML + A4 打印样式，支持导出 PDF | 可交付教案 |

**两种运行模式**：
- **Quick 模式**：全自动完成，一次性输出完整教案
- **Copilot 模式**：每阶段暂停，支持人工反馈修改指令(revise/continue/terminate)

### RAG 智能问答

```
用户问题 → 查询改写(规则/LLM) → Milvus 向量搜索(15候选)
         → DashScope 重排序(精排至3条) → 引用感知流式生成
```

- 集成 K12 课标知识库，按学段/年级/学科精准检索
- 支持用户上传文档的私有知识检索
- 输出带 `[N]` 引用标记的答案

### 文档管理与向量化

| 格式 | 解析器 | 分片策略 |
|------|--------|----------|
| PDF | PdfBox 3.0 | 按标题分节 → 按段落分片(800字/片, 重叠160字) |
| Word (.docx) | POI 5.4 | 同上 |
| Markdown | 自定义解析 | 保留标题面包屑，表格作为整体 |
| TXT | 纯文本解析 | 智能段落切分 |

### 对话记忆体系

```
L1 短期窗口 (Redis)       → 最近 6 轮对话，上限 2000 tokens
L2 摘要压缩               → 对话超过 5 轮且新增 ≥ 3 轮时 LLM 压缩
L3 长期语义记忆 (Redis+向量) → 结构化事实提取，7 类语义检索，30 天 TTL
```

---

## 技术栈

| 类别 | 技术 | 版本 | 说明 |
|------|------|------|------|
| **语言** | Java | 17 | LTS 版本 |
| **框架** | Spring Boot | 3.3.5 | 应用核心框架 |
| **AI 框架** | Spring AI Alibaba Agent | 1.1.0.0-RC2 | DashScope 集成 + Agent 编排 |
| **LLM** | DashScope (通义千问) | SDK 2.17.0 | 对话 / Embedding / Rerank |
| **向量数据库** | Milvus | SDK 2.6.10 / Server 2.5.10 | 语义检索 + L2 距离 |
| **关系数据库** | MySQL | 8.0 | JPA/Hibernate 持久化 |
| **缓存** | Redis | 7 (Lettuce) | 会话 / 记忆 / 锁 / 限流 |
| **安全** | Spring Security + JJWT | 0.12.5 | JWT 认证 + BCrypt 加密 |
| **熔断器** | Resilience4j | 2.2.0 | Milvus / DashScope 熔断降级 |
| **监控** | Micrometer + Actuator | Spring Boot 内置 | 指标采集 + 健康检查 |
| **PDF 生成** | Flying Saucer (iTextRenderer) | 9.1.22 | HTML → PDF 渲染 |
| **文档解析** | PdfBox / POI | 3.0.4 / 5.4.0 | PDF / Word 文本提取 |
| **API 文档** | SpringDoc OpenAPI | 2.6.0 | Swagger UI |
| **代码质量** | Checkstyle / JaCoCo | 3.3.1 / 0.8.12 | 代码规范 + 30% 覆盖率阈值 |

---

## 项目结构

```
GagneFlow/
├── agent-config/prompts/           # AI Agent Prompt 模板 (10个 .md)
│   ├── supervisor.md               #   Supervisor 调度 Agent
│   ├── planner.md                  #   任务规划 Agent
│   ├── executor.md                 #   工具执行 Agent
│   ├── retrieval.md                #   知识检索 Agent
│   ├── review.md                   #   教案审查 Agent
│   ├── decision_guide.md           #   决策类型定义
│   └── addrf/                      #   ADDRF 五阶段 Prompt
│       ├── addrf_analysis.md
│       ├── addrf_design.md
│       ├── addrf_development.md
│       └── addrf_review.md
│
├── lesson-plan-docs/               # K12 教学知识库
│   ├── k12_curriculum.json         #   课标知识 (小/初/高 · 语数英)
│   ├── subject-formats.json        #   学科格式规范 (9 学科)
│   └── templates/                  #   10 个教案模板 JSON
│
├── src/main/java/com/gagneflow/
│   ├── Main.java                   # Spring Boot 入口
│   ├── controller/                 # REST 控制器 (10 个)
│   │   ├── AuthController.java     #   认证 (注册/登录/刷新/注销)
│   │   ├── ChatController.java     #   流式对话 / 线程池状态
│   │   ├── LessonController.java   #   教案生成 / PDF导出 / Copilot
│   │   ├── RagController.java      #   RAG 检索问答
│   │   ├── SessionController.java  #   会话管理
│   │   ├── FileUploadController    #   文件上传与向量化
│   │   ├── PromptAdminController   #   Prompt 版本管理
│   │   ├── MilvusCheckController   #   健康检查
│   │   └── GlobalExceptionHandler  #   全局异常处理
│   │
│   ├── service/                    # 业务服务层
│   │   ├── lesson/                 #   ADDRF 流水线核心
│   │   │   ├── AddrfPipeline.java  #
│   │   │   └── FormatTool.java     #
│   │   ├── chat/                   #   智能对话
│   │   │   ├── ChatService.java    #
│   │   │   ├── ChatSession.java    #
│   │   │   └── ChatSessionService  #
│   │   ├── rag/                    #   RAG 检索增强
│   │   │   ├── RagService.java     #
│   │   │   ├── RerankService.java  #
│   │   │   └── QueryRewriter.java  #
│   │   ├── vector/                 #   向量引擎
│   │   │   ├── VectorEmbeddingService #
│   │   │   ├── VectorSearchService #
│   │   │   └── VectorIndexService  #
│   │   ├── document/               #   文档处理
│   │   │   ├── DocumentChunkService #
│   │   │   ├── K12CurriculumLoader #
│   │   │   ├── K12VectorInitializer #
│   │   │   └── SubjectFormatLoader #
│   │   ├── reader/                 #   文档解析器 (策略模式)
│   │   │   ├── DocumentReader.java #
│   │   │   ├── DocumentReaderFactory #
│   │   │   ├── PdfDocumentReader   #
│   │   │   ├── WordDocumentReader  #
│   │   │   ├── MarkdownDocumentReader #
│   │   │   └── PlainTextDocumentReader #
│   │   ├── memory/                 #   多级记忆
│   │   │   ├── ConversationMemoryManager #
│   │   │   ├── LongTermMemoryService #
│   │   │   └── TokenCounter.java   #
│   │   ├── prompt/                 #   Prompt 版本管理
│   │   │   ├── PromptRegistry.java #
│   │   │   ├── PromptExperiment    #
│   │   │   └── PromptMetricsCollector #
│   │   ├── pdf/PdfGenerator.java   #   HTML→PDF 渲染
│   │   └── metrics/PipelineMetrics #   Micrometer 指标
│   │
│   ├── agent/tool/                 # Agent 工具
│   │   ├── DateTimeTools.java      #   日期时间查询
│   │   └── InternalDocsTools.java  #   内部文档检索
│   │
│   ├── entity/                     # JPA 实体 (4 个)
│   │   ├── User.java
│   │   ├── SessionMeta.java
│   │   ├── SessionMessage.java
│   │   └── PromptVersion.java
│   │
│   ├── repository/                 # 数据访问层 (4 个)
│   ├── dto/                        # 数据传输对象 (9 个)
│   ├── config/                     # Spring 配置 (14 个)
│   │   ├── security/               #   JWT / Spring Security / CORS
│   │   ├── DashScopeConfig.java    #
│   │   ├── MilvusConfig.java       #
│   │   ├── RedisConfig.java        #
│   │   ├── ExecutorConfig.java     #
│   │   ├── PipelineStageConfig.java #
│   │   └── RateLimitInterceptor.java #
│   └── constant/                   # 常量定义
│
├── src/main/resources/
│   ├── application.yml             # 主配置文件
│   ├── application-dev.yml         # 开发环境配置
│   └── application-prod.yml        # 生产环境配置
│
├── src/test/java/                  # 测试 (18 个文件)
├── config/checkstyle/              # Checkstyle 规则
├── docs/                           # 项目文档
├── docker-compose.yml              # Docker 编排
├── Makefile                        # 构建自动化
├── Jenkinsfile                     # CI/CD 流水线
└── pom.xml                         # Maven 构建
```

---

## 快速开始

### 前置要求

- **JDK 17**+
- **Docker** + **Docker Compose**
- **Maven 3.8**+

### 1. 配置环境变量

```bash
cp .env.example .env
```

编辑 `.env`，填入必填项：

```ini
# 必填：阿里云 DashScope API Key
# 获取地址：https://dashscope.console.aliyun.com/apiKey
DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxx

# 必填：JWT 签名密钥 (推荐生成方式: openssl rand -base64 32)
JWT_SECRET=your-256-bit-secret-key

# MySQL 凭据 (开发环境)
DB_USERNAME=gagneflow
DB_PASSWORD=gagneflow123
```

### 2. 启动基础设施

```bash
# 启动 MySQL + Redis + Milvus + etcd + MinIO
docker compose up -d

# 验证服务状态
docker compose ps
```

### 3. 启动应用

```bash
# 一键启动 (Docker + 应用 + 文档上传)
make init

# 或者分步启动
mvn clean package -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 后台运行
make start
```

### 4. 验证服务

```bash
# 健康检查
curl http://localhost:9900/actuator/health

# Swagger API 文档
open http://localhost:9900/swagger-ui.html
```

---

## API 接口

### 认证接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 用户注册 |
| POST | `/api/auth/login` | 登录获取 JWT |
| POST | `/api/auth/refresh` | 刷新 Access Token |
| POST | `/api/auth/logout` | 注销 Token (黑名单) |

```bash
# 登录示例
curl -X POST http://localhost:9900/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 响应
{
  "token": "eyJhbGci...",        # Access Token (1小时有效)
  "refreshToken": "eyJhbGci...",  # Refresh Token (7天有效)
  "username": "admin"
}
```

> 后续请求在 Header 中携带：`Authorization: Bearer {token}`

### 对话接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat_stream` | 流式对话 (SSE) |
| GET | `/api/chat/history` | 会话列表 |
| GET | `/api/chat/pool-status` | 线程池状态 |

```bash
# 流式对话
curl -X POST http://localhost:9900/api/chat_stream \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"Id":"session-001","Question":"请帮我设计一节小学数学课"}'
```

### 教案生成接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/lesson_plan` | 生成教案 (SSE 流式) |
| POST | `/api/lesson_plan/action` | Copilot 阶段交互 |
| GET | `/api/lesson_plan/{subject}` | 获取学科占位符 |
| GET | `/api/lesson_plan/pdf/{sessionId}` | 导出 PDF |

```bash
# 教案生成
curl -X POST http://localhost:9900/api/lesson_plan \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{
    "stage": "小学",
    "grade": 3,
    "subject": "数学",
    "hours": 2,
    "goals": "掌握两位数乘法的计算方法，能正确进行计算",
    "mode": "quick"
  }'

# Copilot 模式交互 (暂停后反馈)
curl -X POST http://localhost:9900/api/lesson_plan/action \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"token":"xxxxx","action":"revise","instruction":"请增加更多互动环节"}'
```

请求参数说明：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `stage` | String | 是 | 学段：`小学` / `初中` / `高中` |
| `grade` | int | 是 | 年级：1-12 |
| `subject` | String | 是 | 学科名称 |
| `hours` | int | 是 | 课时数：1-20 |
| `goals` | String | 是 | 教学目标：2-500 字 |
| `mode` | String | 否 | 模式：`quick`(默认) / `copilot` |
| `uploadedFileNames` | List | 否 | 关联的已上传文件名 |

### RAG 检索问答

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rag/query` | RAG 检索问答 (SSE) |

```bash
curl -X POST http://localhost:9900/api/rag/query \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"Id":"session-001","Question":"三年级数学有哪些教学重点？"}'
```

### 会话管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/sessions` | 列出所有会话 |
| POST | `/api/sessions` | 创建会话 |
| DELETE | `/api/sessions/{sessionId}` | 删除会话 |
| DELETE | `/api/sessions/{sessionId}/messages` | 清空消息 |

### 文件上传

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/files/upload` | 上传文件并向量化 |

```bash
curl -X POST http://localhost:9900/api/files/upload \
  -H "Authorization: Bearer {token}" \
  -F "file=@document.pdf"

# 支持格式: txt, md, pdf, docx (最大 50MB)
```

### Prompt 管理 (管理员)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/prompts/admin/{name}` | 查看 Prompt 版本列表 |
| PUT | `/api/prompts/admin/{name}/active` | 切换活跃版本 |

---

## 配置说明

### 核心配置项

| 配置 | 环境变量 | 默认值 | 说明 |
|------|----------|--------|------|
| 服务端口 | `SERVER_PORT` | `9900` | 应用监听端口 |
| CORS 白名单 | `CORS_ALLOWED_ORIGINS` | `localhost:9900,localhost:5173` | 允许跨域来源 |
| DashScope API Key | `DASHSCOPE_API_KEY` | — | **必填** |
| Embedding 模型 | `DASHSCOPE_EMBEDDING_MODEL` | `text-embedding-v4` | 1024 维向量 |
| Rerank 模型 | `DASHSCOPE_RERANK_MODEL` | `gte-rerank` | 重排序模型 |
| RAG 对话模型 | `RAG_MODEL` | `qwen-max-latest` | 答案生成模型 |
| 摘要模型 | `DASHSCOPE_SUMMARY_MODEL` | `qwen-plus` | 对话摘要模型 |
| JWT 密钥 | `JWT_SECRET` | — | **必填**，≥32 字节 |
| MySQL 用户名 | `DB_USERNAME` | — | **生产必填** |
| MySQL 密码 | `DB_PASSWORD` | — | **生产必填** |
| Redis 地址 | `REDIS_HOST` | `localhost` | Redis 连接 |
| Milvus 地址 | `MILVUS_HOST` | `localhost` | Milvus 连接 |

### 调优参数

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `gagneflow.session.max-window-size` | `6` | 短期记忆窗口轮数 |
| `gagneflow.memory.max-history-tokens` | `3000` | 历史上下文 Token 上限 |
| `gagneflow.memory.summary-trigger-pairs` | `5` | 触发摘要的对话对数 |
| `gagneflow.pool.core-size` | `10` | 线程池核心线程数 |
| `gagneflow.pool.max-size` | `50` | 线程池最大线程数 |
| `document.chunk.max-size` | `800` | 文档分片最大字符数 |
| `document.chunk.overlap` | `160` | 分片重叠字符数 |
| `rag.top-k` | `3` | 最终返回文档数 |
| `rag.relevance-threshold` | `0.3` | 相关性过滤阈值 |
| `dashscope.rerank.top-n` | `3` | 重排序保留条数 |
| `dashscope.rerank.search-top-k` | `15` | 初次检索候选数 |

### Resilience4j 熔断

| 熔断器 | 失败阈值 | 开启等待时间 |
|--------|----------|-------------|
| `milvus` | 50% (10次窗口) | 30 秒 |
| `dashscope` | 50% (10次窗口) | 60 秒 |

### 限流策略

| 接口 | 限制 | 时间窗口 |
|------|------|---------|
| `/api/auth/login` | 5 次 | 60 秒 |
| `/api/chat_stream` | 10 次 | 60 秒 |
| `/api/lesson_plan` | 2 次 | 60 秒 |
| `/api/rag/query` | 30 次 | 60 秒 |

---

## 核心流程

### ADDRF 教案生成流程

```
POST /api/lesson_plan → JVM锁 + Redis锁 → 线程池异步执行
│
├─ Phase 1: ANALYSIS
│   ├─ 加载 K12 课标 (k12_curriculum.json)
│   ├─ 加载学科格式 (subject-formats.json)
│   ├─ LLM 生成: 学情分析 + 知识点清单 + 三维教学目标
│   └─ 输出缓存至 Redis
│
├─ Phase 2-3: DESIGN + DEVELOPMENT (并行)
│   ├─ Design: 重难点 + 课时分布 + 教学策略 + 板书 + 资源
│   └─ Development: 5 环节 × N 课时 (含师生活动 + 设计意图)
│
├─ Phase 4: FORMAT
│   └─ Markdown → HTML (A4 打印样式)
│
└─ Phase 5: REVIEW (异步)
    ├─ 5 维度评分 (100 分制)
    ├─ HITL 四规则检测
    │   ├─ 输出过短 (< 800 字符) → 需人工审核
    │   ├─ 评分过低 (< 60 分) → 需人工审核
    │   ├─ 质量降级 (连续下降) → 需人工审核
    │   └─ 不安全关键词 → 需人工审核
    ├─ 合格教案 → 回灌 Milvus (三级过滤: 评分≥70·内容>500·去重)
    └─ 持久化 MySQL + Redis (双写)
```

### RAG 检索流程

```
用户问题
  → QueryRewriter (规则改写: 关键词提取 + 历史拼接)
  → VectorEmbedding (text-embedding-v4 → 1024 维向量)
  → Milvus L2 搜索 (nprobe=16 自适应, 返回 15 候选)
  → DashScope Rerank (gte-rerank, 精排至 3 条)
  → 相关性过滤 (threshold=0.3)
  → 构建引用感知 Prompt
  → 流式生成答案 (SSE)
```

### 记忆管理流程

```
L1 短期窗口 (Redis ChatSession)
│  保留最近 6 轮对话，实时追加
│
├─ 触发条件: pairCount > 5 && newPairs >= 3
│
├─ L2 摘要压缩
│   ├─ LLM (qwen-plus) 生成结构化摘要
│   ├─ 压缩比检查 (>50% 则拒绝)
│   ├─ 注入 system prompt
│   └─ MySQL/Redis 双写替换旧历史
│
└─ L3 长期语义记忆 (Redis + 向量缓存)
    ├─ 从摘要提取结构化事实 (7 类 × max5)
    ├─ Redis 缓存: key=gagneflow:ltm:facts:{userId}
    ├─ 语义检索: 向量余弦相似度 (缓存优先)
    ├─ 关键词搜索兜底
    └─ TTL: 30 天
```

---

## 安全机制

| 层次 | 机制 | 实现 |
|------|------|------|
| **认证** | JWT (HMAC-SHA256) | Access Token 1h + Refresh Token 7d，强制 ≥256 bit 密钥 |
| **撤销** | Redis 黑名单 | 键: `gagneflow:jwt:blacklist:{jti}`，TTL 对齐剩余有效期 |
| **密码** | BCrypt | `BCryptPasswordEncoder` 加密存储 |
| **限流** | Redis Lua 滑动窗口 | 分级限流: 登录5/对话10/教案2/RAG30 (60s窗口) |
| **并发锁** | JVM + Redis 双重锁 | 教案生成防重复提交 |
| **SQL 注入** | JPA 参数绑定 | 使用 Spring Data JPA 预编译 |
| **日志安全** | Token 脱敏 | 仅展示前后各 4 位字符 |
| **CORS** | 白名单 | 可配置 `CORS_ALLOWED_ORIGINS` |

---

## 开发指南

### 构建命令

```bash
# 编译
mvn clean compile

# 运行测试
mvn test -Dspring.profiles.active=test

# 代码风格检查
mvn checkstyle:check

# 代码覆盖率报告
mvn test jacoco:report
# 报告路径: target/site/jacoco/index.html

# 打包
mvn clean package -DskipTests
```

### Makefile 快捷命令

| 命令 | 说明 |
|------|------|
| `make init` | 一键初始化 (Docker + 启动 + 文档上传) |
| `make up` | 启动 Docker Compose |
| `make down` | 停止 Docker Compose |
| `make start` | 后台启动应用 |
| `make stop` | 停止应用 |
| `make restart` | 重启应用 |
| `make check` | 检查服务状态 |
| `make upload` | 上传 `lesson-plan-docs/` 到知识库 |
| `make clean` | 清理临时文件 |
| `make status` | 查看 Docker 容器状态 |

### 添加新的文档格式支持

1. 实现 `DocumentReader` 接口
2. 返回支持的扩展名列表
3. Spring 自动发现并注册到 `DocumentReaderFactory`

```java
@Component
public class NewFormatReader implements DocumentReader {
    @Override
    public List<String> getSupportedExtensions() {
        return List.of("newfmt");
    }

    @Override
    public String readText(Path path) throws IOException {
        // 解析逻辑
    }
}
```

### Prompt 版本管理

Prompt 存放在 `agent-config/prompts/`，支持目录级版本：

```
agent-config/prompts/
├── v1/
│   ├── supervisor.md
│   └── addrf_analysis.md
└── v2/
    ├── supervisor.md      # 改进版
    └── addrf_analysis.md  # 改进版
```

启动时自动 seed 到数据库，可通过 API 热切换活跃版本。

---

## 部署运维

### Docker Compose 服务

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL 8.0 | `3306` | 持久化存储 |
| Redis 7 | `6379` | 缓存与会话 |
| etcd | — | Milvus 元数据 |
| MinIO | `9000` / `9001` | Milvus 对象存储 |
| Milvus Standalone | `19530` / `9091` | 向量数据库 |
| Attu | `8000` | Milvus GUI 管理 |

### CI/CD (Jenkins)

```
Checkout → Checkstyle → Unit Test + JaCoCo → Build → Archive
                                                     │
                                           failure ─→ 邮件通知
```

### 健康检查

| 端点 | 说明 |
|------|------|
| `GET /actuator/health` | 综合健康状态 |
| `GET /milvus/health` | Milvus 连接状态 |
| `GET /api/chat/pool-status` | 线程池活跃度 |
| `GET /actuator/metrics` | Prometheus 指标 |

### 监控指标 (Micrometer)

| 指标 | 说明 |
|------|------|
| `gagneflow.rag.search.*` | RAG 搜索次数 / 耗时 / 候选数 |
| `gagneflow.rag.citation.loss` | 引用丢失计数 |
| `gagneflow.addrf.stage.*` | ADDRF 阶段执行次数 / 耗时 |
| `gagneflow.memory.summary.compression` | 摘要压缩触发次数 |
| `gagneflow.hitl.trigger.total` | HITL 人工审核触发次数 |

---

**版本**: v1.0.0 | **许可证**: MIT
