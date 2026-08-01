GagneFlow（AI 教案生成 Agent）| 2026.05 - 2026.07 | 个人项目

技术栈：Java 17、Spring Boot 3.3、Spring AI Alibaba、MySQL 8.0、Redis 7、Milvus 2.5（IVF_FLAT，1024维）、DashScope（qwen-max-latest + text-embedding-v4 + gte-rerank）、Resilience4j、Docker Compose、GitHub Actions。

独立开发的 K12 教案生成工具，覆盖 3 学段 × 9 学科，文档库来自教育部官网。后端 82 个 Java 类，Docker Compose 6 容器一键编排，CI 422 用例全通过。

• 重构 Agent 思考范式：摒弃传统 ReAct 单步自由推理，改为基于 ADDIE 模型的确定性流水线（Analysis → Design → Development → Review → Format）——Workflow 层接管确定性逻辑（K12 课标上下文注入、学科格式约束、结果去重与 HTML 渲染），Agent 层专注各阶段专业策略输出，避免 LLM 在"该走哪步"上浪费推理。Design 与 Development 无数据依赖，CompletableFuture 并行执行（240s → 185s，降 23%），Review 异步后台不阻塞主流程；阶段支持配置增删调序，Prompt 外置 Markdown + MySQL 版本管理热切换，新学科/学段接入无需修改核心编排代码。

• 上下文与记忆分层治理：针对多阶段/多轮生成中 Prompt 膨胀导致的注意力稀释，短期层 Token 预算滑动窗口动态裁剪（超预算从头部 FIFO 淘汰）；中期层 LLM 摘要压缩（压缩率至 18%，压缩比 >50% 判定退化跳过）；长期层事实记忆独立治理——摘要触发时结构化提取（7 类关键词评分 + 每类上限 2 条防单一主题垄断），向量预计算缓存 Redis（TTL 30 天），新对话检索 Top-3 注入 System Prompt 实现跨会话复用，精确事实召回率 92%，相比纯关键词匹配提升 16pp。

• 人机协同分阶段确认：教案作为专业教学成果，全自动生成存在方向跑偏风险，故每阶段产出后设置人工闸门——SSE 实时推送阶段预览，BlockingQueue(capacity=1) 阻塞等待用户指令，支持继续/修改/终止三态（token 配对定位待确认阶段），修改指令拼接回输入重新生成，最多 5 轮修订，120s 超时默认接受当前结果降级继续。叠加 HITL 自动兜底：Review 评分 <70 自动回炉重生成、输出过长/危险关键词时标记 needsHumanReview 强制人工复核，形成"AI 生成 → 规则筛检 → 人工把关 → 反馈迭代"闭环。

• RAG 三步管线 + 文档分片："查询改写 → IVF_FLAT 初筛 → gte-rerank 精排"。QueryRewriter 拼接历史重构查询，解决短查询/指代词检索失效；向量 L2 归一化统一度量，nprobe 自适应（16→64）保召回。15 份文档上 Top-3 命中率 58% → 76%（+18pp），MRR 0.42 → 0.61（+45%）。分片支持 PDF/Word/Markdown/TXT，标题感知切割 + 表格保护（1.5 倍防截断）。


实习经历

在线学习教育平台（Learning-main）｜湖南文盾信息技术有限公司｜Java 后端开发实习｜2026.03 - 2026.05

技术栈：Spring Cloud + Nacos + Gateway、Spring Security OAuth2 + JWT（RSA 非对称签名）、MyBatis-Plus、MySQL、Redis、RabbitMQ、XXL-JOB、ElasticSearch 7.x、MinIO、Caffeine、Sentinel、JMeter、微信支付 V3 / 支付宝、华为云 ECS + Docker。

基于微服务架构的在线教育平台（6 个微服务、26+ 张业务表），覆盖课程发布→购买→学习→评价完整链路。实习期间与 2 名后端协作，参与核心库表设计（26+ 张表），承担课程管理模块版本迭代，落地大文件处理、缓存、异步解耦、压测等工程实践。

• 聚合查询重构：章节-内容-作业三表分散存储，课程详情接口按章节逐条查询产生 N+1 次数据库往返，响应 850ms；改为单次 JOIN 聚合查询、Mapper 层一次组装完整章节学习结构，响应降至 320ms（降幅 62%）。同期走查修复空指针、参数校验缺失、分页越界等历史缺陷 8 处。

• 大文件传输：课件直传 GB 级文件易中途断连，基于 MinIO 实现 5MB 分块上传 + 断点续传（仅重传失败分块）+ MD5 校验，续传成功率 100%；存储侧按视频/课件/作业分桶隔离 + 预签名 URL 限时下发，替代单桶公开读，阻断付费内容泄露路径。

• 缓存与异步分工：课程详情、章节列表属高读低写热点，采用 Caffeine（L1 本地）+ Redis（L2 分布式）分层缓存，XXL-JOB 承载课程状态同步、订单超时关闭等批处理，实测命中率 85%+、JMeter 压测 QPS 500+；课程发布等耗时操作经 RabbitMQ 按事件类型隔离队列异步解耦，核心接口响应从 1.2s 降至 650ms（降幅 46%）；订单超时单独采用 DelayQueue 而非 Redis 过期事件（key 失效时效不可控易丢），单机方案覆盖当前订单量。

• 支付与检索：支付回调属外部不可信请求，按微信 V3（回调验签 + AES 解密）与支付宝（异步通知验签）双通道分别实现签名校验与幂等处理；课程检索以 ElasticSearch 中文分词索引替代 MySQL LIKE 扫表，支撑课程名/描述/标签多维检索。

• 业务闭环：完整经历课程「创建→审核→上架→购买→学习→评价/作业」状态链路与支付-学习权益关系建模，沉淀的教育业务认知直接复用至 GagneFlow 教案生成的业务建模与 RAG 文档库治理，形成"教育业务理解 → 工程底座 → AI 应用落地"能力链。

---

# 简历素材整合（用于排版）

> 以下内容为简历排版时所需的其余模块素材，与上文项目经历配合使用。

## 基础信息

| 项目 | 内容 |
|:-----|:-----|
| 姓名 | 牛钰坤 |
| 年龄 | 21 岁 |
| 电话 | 18649565976 |
| 邮箱 | NIU230361900@QQ.COM |
| 求职意向 | AI 应用开发工程师 |

## 教育背景

中南大学 · 计算机科学与技术（本科）· 985/211
2023.09 — 2027.06
学业成绩：专业排名前 40%
核心课程：数据结构、操作系统、计算机网络、C 语言程序设计、数据库原理、离散数学

## 专业技能

**后端框架**：Java 17 · Spring Boot 3.3 · Spring Cloud · Spring AI Alibaba · Spring Security + JWT · MyBatis-Plus · JPA

**数据与中间件**：MySQL 8.0 · Redis 7 · Milvus 2.5（向量检索） · ElasticSearch 7.x · RabbitMQ · Nacos · Sentinel · XXL-JOB · MinIO

**AI / LLM**：RAG 管线（查询改写 → 向量检索 → 重排序） · Prompt Engineering · 向量 Embedding（DashScope text-embedding-v4） · LLM Agent 编排
