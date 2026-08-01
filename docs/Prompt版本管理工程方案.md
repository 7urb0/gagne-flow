# GagneFlow Prompt 版本管理工程方案

> 设计日期：2026-07-28 | 涉及模块：PromptRegistry / PromptExperiment / AddiePipeline / PipelineMetrics
> 设计目标：让 Prompt 成为一等公民，具备版本化存储、A/B 实验分流、效果指标采集能力

---

## 一、现状与问题

| 维度 | 当前做法 | 问题 | 期望解决方式 |
|------|---------|------|-------------|
| 存储 | `agent-config/prompts/addie_*.md` 纯文件 | 修改 Prompt 直接覆盖原文件，无历史记录 | 存入 MySQL `prompt_versions` 表，每条记录含版本号、内容、活跃标记。文件系统作为 seed 源，DB 是 source of truth |
| 加载 | `PromptLoader.load("addie/analysis")` | 无版本概念，无法同时加载多个版本 | 引入 `PromptRegistry`，支持 `getContent(name, version)` 按版本号查询。`loadPrompt()` 内部走 Registry |
| 切换 | 无 | 想试新 Prompt = 改文件 + 重启服务 | Admin API `POST /api/admin/prompts/{name}/{version}/activate` 即时切换活跃版本，不重启 |
| 实验 | 无 | 无法 A/B 对比两个版本的教案质量和评分 | 引入 `PromptExperiment`，基于 **userId hash** 做确定性分流（同一用户始终走同一版本）。配置化比例：v1=70%, v2=30% |
| 审计 | 无 | 面试官问 "你改过几版 Prompt，效果差异多大" 只能靠回忆 | 所有版本保留在 `prompt_versions` 表，`metrics` 通过 Micrometer 实时聚合。`/compare` API 输出 v1 vs v2 的 avgScore 和用量对比 |

---

## 二、目标架构

核心原则：**注册中心只负责查询内容，实验分流由调用方负责**。

```
                        ┌─────────────────────┐
                        │   Admin API          │
                        │ GET/POST /api/admin/ │
                        │   prompts            │
                        └──────────┬──────────┘
                                   │
┌──────────────────┐    ┌──────────▼──────────┐
│ agent-config/    │───▶│  PromptRegistry     │
│ prompts/         │启动 │  (注册中心)          │
│ v1/addie_*.md    │导入 │                     │
│ v2/addie_*.md    │    │  - 维护所有版本元数据  │
└──────────────────┘    │  - 返回指定版本内容   │
                        │  - 查询活跃版本号     │
                        └──────────┬──────────┘
                                   │
                        ┌──────────▼──────────┐
                        │  prompt_versions     │
                        │  (MySQL JPA 实体)    │
                        │                     │
                        │  prompt_name        │
                        │  version_number     │ ← 整数版本号，非字符串
                        │  content TEXT       │
                        │  active BOOLEAN     │
                        │  description        │
                        │  created_at         │
                        └────────────────────┘

┌─────────────────────┐    ┌──────────────────────┐
│  AddiePipeline      │───▶│  PromptExperiment    │
│  callAgent() 内部   │    │  (A/B 分流器)         │
│  1. 查活跃版本号    │    │                      │
│  2. 问 Experiment   │    │  - 基于 userId hash  │
│     选版本          │◀───│    做确定性分配        │
│  3. 查 Registry     │    │  - 按比例分流          │
│     取内容          │    └──────────┬───────────┘
│  4. 调 callAgent()  │               │
└─────────────────────┘    ┌──────────▼───────────┐
                           │  PromptMetrics       │
                           │  (Micrometer 指标)    │
                           │                      │
                           │  - 只读不走 MySQL     │
                           │  - 实时聚合对比        │
                           └──────────────────────┘
```

---

## 三、核心组件设计

### 3.1 PromptVersion — JPA 实体

```java
@Entity
@Table(name = "prompt_versions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"prompt_name", "version_number"}))
public class PromptVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String promptName;       // "addie_analysis", "addie_review"

    @Column(nullable = false)
    private int versionNumber;       // 1, 2, 3 ... 整数版本号

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;          // 完整的 Prompt 文本

    @Column(nullable = false)
    private boolean active;          // 是否为当前活跃版本

    @Column(length = 256)
    private String description;      // 改了什么、为什么改

    @Column(nullable = false)
    private Instant createdAt;

    // getters / setters / toString ...
}
```

**索引**：`(prompt_name, version_number) UNIQUE`，`(prompt_name, active WHERE active=true)` 部分索引。

**设计决策**：
- `versionNumber` 用 `int` 而非 `String`——支持排序、比较、自增。文件系统中 `v1` / `v2` 仅是展示名
- content 用 `TEXT` 而非文件路径——避免文件系统依赖，支持运行时写入
- **不自带 `metrics` 字段**——指标全走 Micrometer，不写 MySQL（见 3.4）

---

### 3.2 PromptRegistry — 注册中心

```java
@Service
public class PromptRegistry {
    private final PromptVersionRepository repo;
    private final PromptLoader fileLoader;

    @PostConstruct
    void seedFromFiles() {
        // 扫描 agent-config/prompts/{version}/ 目录
        // 目录名解析为整数版本号：v1 → 1, v2 → 2
        // 每个 .md 文件如果 DB 中不存在对应 (name, versionNumber) 则 INSERT
        // 最大版本号自动设为 active=true
        // 已有记录不改动——DB 是 source of truth
    }

    // === 查询 API ===

    /** 获取当前活跃版本的内容 */
    String getContent(String promptName);

    /** 获取指定版本的内容 */
    String getContent(String promptName, int versionNumber);

    /** 获取当前活跃版本号 */
    int getActiveVersionNumber(String promptName);

    /** 列出所有版本（带元数据，不含 content） */
    List<PromptVersion> listVersions(String promptName);

    // === 管理 API ===

    /** 切换活跃版本 */
    PromptVersion activate(String promptName, int versionNumber);
}
```

**关键变化**：`getContent` 是纯函数——给定 (name, version) 返回固定内容，不含任何随机/分流逻辑。

---

### 3.3 PromptExperiment — A/B 分流器

**核心设计变化**：
1. 分流逻辑从 `PromptRegistry` 剥离，由调用方组合
2. 用 `userId` hash 做确定性分配，同一用户始终走同一版本
3. 配置支持动态加载（不重启）

```java
@Component
@ConfigurationProperties(prefix = "gagneflow.prompt.experiment")
public class PromptExperiment {
    private boolean enabled = false;
    // map: {"addie_review": {1: 0.7, 2: 0.3}} — 整数版本号 → 比例
    private Map<String, Map<Integer, Double>> splits = new HashMap<>();

    /**
     * 基于 userId 做确定性版本分配。
     * 同一 userId 始终返回同一版本，保证一个 session 内 Prompt 一致。
     *
     * @param promptName  prompt 名称
     * @param activeVersion 当前活跃版本号
     * @param userId      用户标识（用于 hash 取模）
     * @return 分配的版本号
     */
    public int selectVersion(String promptName, int activeVersion, Long userId) {
        if (!enabled) return activeVersion;

        Map<Integer, Double> ratios = splits.get(promptName);
        if (ratios == null || ratios.isEmpty()) return activeVersion;

        // 基于 userId 做确定性 hash，同一用户始终走同一版本
        int bucket = Math.abs(userId.hashCode()) % 100;
        double roll = bucket / 100.0;

        double cumulative = 0.0;
        for (Map.Entry<Integer, Double> entry : ratios.entrySet()) {
            cumulative += entry.getValue();
            if (roll <= cumulative) return entry.getKey();
        }
        return activeVersion;
    }
}
```

**为什么 userId hash 而不是 random**：

| 方案 | 同一 session 一致性 | 实验数据质量 | 实现复杂度 |
|------|:---:|:---:|:---:|
| `random.nextDouble()` | ❌ 每次不同 | 低（跨版本污染） | 低 |
| **userId hash** | ✅ 始终相同 | 高 | 低 |
| sessionId hash | ✅ 始终相同 | 高 | 中（需透传 sessionId） |

**配置示例**：
```yaml
gagneflow:
  prompt:
    experiment:
      enabled: true
      splits:
        addie_review:
          1: 0.7    # v1 占 70%
          2: 0.3    # v2 占 30%
        addie_analysis:
          1: 0.5
          2: 0.5
```

---

### 3.4 PromptMetrics — 指标采集

**核心变化**：指标**只读不走 MySQL**。`PromptVersion.metrics` 字段不保留。所有数据通过 Micrometer 实时采集，`compare` API 实时聚合。

```java
@Component
public class PromptMetricsCollector {

    // === 定义 Micrometer 指标 ===

    // prompt.usage.total{name="addie_review", version="2"}
    private final Counter totalUsageCounter;

    // prompt.usage.active{name="addie_review", version="2"}
    private final Gauge activeVersionGauge;

    // prompt.score{name="addie_review", version="2"}
    private final DistributionSummary scoreDistribution;

    // === 记录 ===

    void recordUsage(String promptName, int versionNumber) {
        totalUsageCounter.increment();
    }

    void recordScore(String promptName, int versionNumber, int score) {
        scoreDistribution.record(score);
    }

    // === 查询（实时聚合，不落盘） ===

    Map<Integer, VersionStats> getStats(String promptName) {
        // 从 Micrometer MeterRegistry 中按 tag 聚合
        // 返回 {1: {avgScore, usageCount, p50, p95}, 2: {...}}
    }

    PromptComparison compare(String promptName, int versionA, int versionB) {
        // 对比两个版本的实时指标
    }

    public static class VersionStats {
        int versionNumber;
        double avgScore;
        long usageCount;
        double p50DurationMs;
        double p95DurationMs;
    }

    public static class PromptComparison {
        int versionA, versionB;
        double avgScoreA, avgScoreB;
        long usageCountA, usageCountB;
        String summary;  // e.g. "v2 avgScore 84.3 vs v1 79.1, v2 wins +6.5%"
    }
}
```

**为什么 metrics 不走 MySQL**：
- 指标更新频率高（每次 prompt 调用都记录），MySQL UPDATE 无法承受
- Micrometer 内置 `MeterRegistry` 支持内存聚合 + 定时导出到 Prometheus/Grafana
- `compare` API 需要的不是精确持久化数据，而是近期趋势——Micrometer 的 sliding window 足够
- 面试官问 "你的 Prompt 效果数据怎么来的"——"实时指标聚合，不走 DB 落盘延迟"

---

### 3.5 AddiePipeline 集成改动

```java
// AddiePipeline.java 中 loadPrompt() 的改动

@Autowired
private PromptRegistry promptRegistry;

@Autowired
private PromptExperiment promptExperiment;

@Autowired
private PromptMetricsCollector promptMetrics;

private String loadPrompt(String name, Long userId) {
    // 1. 查活跃版本
    int activeVersion = promptRegistry.getActiveVersionNumber(name);

    // 2. 实验分流选版本
    int actualVersion = promptExperiment.selectVersion(name, activeVersion, userId);

    // 3. 取内容
    String content = promptRegistry.getContent(name, actualVersion);

    // 4. 记录指标
    promptMetrics.recordUsage(name, actualVersion);

    return content;
}
```

**注意**：`loadPrompt` 新增 `userId` 参数，调用方需从 `@CurrentUser` 或 `SecurityContextHolder` 获取。如果用户未登录（`DEFAULT_USER_ID=0L`），Experiment 降级为返回 activeVersion。

---

### 3.6 AdminController — 管理 API

```java
@RestController
@RequestMapping("/api/admin/prompts")
@PreAuthorize("hasRole('ADMIN')")  // 需要 ADMIN 角色
public class PromptAdminController {

    // GET    /api/admin/prompts/{name}                   → 列出所有版本
    // POST   /api/admin/prompts/{name}/{version}/activate → 切换活跃版本
    // GET    /api/admin/prompts/{name}/compare?v1&v2     → 两个版本实时指标对比
    // GET    /api/admin/prompts/{name}/experiment         → 查看当前实验配置
    // POST   /api/admin/prompts/{name}/experiment         → 更新实验配置
}
```

**认证**：依赖已有 SecurityConfig 的 JWT 拦截，额外校验 ADMIN role。不开放给普通用户。

---

## 四、数据流

### 4.1 正常请求流（非实验模式）

```
AddiePipeline.callAgent()
  → loadPrompt("addie_review", userId=42)
    → promptRegistry.getActiveVersionNumber("addie_review") → 2
    → promptExperiment.selectVersion("addie_review", 2, 42) → 2 (未启用实验)
    → promptRegistry.getContent("addie_review", 2) → "你是一个专业的AI教育助手..."
    → promptMetrics.recordUsage("addie_review", 2)
  → 拼接用户输入 → callAgent()
  → 完成后 → promptMetrics.recordScore("addie_review", 2, 85)
```

### 4.2 A/B 实验流

```
AddiePipeline.callAgent()
  → loadPrompt("addie_review", userId=42)
    → promptRegistry.getActiveVersionNumber("addie_review") → 2
    → promptExperiment.selectVersion("addie_review", 2, 42)
      → hash("42") % 100 = 65
      → splits: v1=0.7, v2=0.3 → 65 > 70 → v2
      → 返回 2
    → promptRegistry.getContent("addie_review", 2) → v2 版本
    → promptMetrics.recordUsage("addie_review", 2)

// 用户 43 的请求（同一 session）
  → hash("43") % 100 = 25
  → 25 <= 70 → v1
  → 返回 1  → 使用 v1 版本
  → promptMetrics.recordUsage("addie_review", 1)
```

**确定性验证**：用户 42 每次请求都 hash 到 65→v2，不会跨版本污染。

---

## 五、改动文件清单

| 文件 | 操作 | 说明 |
|------|:----:|------|
| `entity/PromptVersion.java` | 新建 | JPA 实体，`versionNumber` 用 int |
| `repository/PromptVersionRepository.java` | 新建 | Spring Data JPA |
| `service/prompt/PromptRegistry.java` | 新建 | 注册中心 + 文件导入（不含分流） |
| `service/prompt/PromptExperiment.java` | 新建 | A/B 分流器（基于 userId hash） |
| `service/prompt/PromptMetricsCollector.java` | 新建 | 指标采集（Micrometer，不写 MySQL） |
| `controller/PromptAdminController.java` | 新建 | 管理 API（需 ADMIN role） |
| `service/lesson/AddiePipeline.java` | 修改 | `loadPrompt()` 改为三步调用 |
| `service/metrics/PipelineMetrics.java` | 修改 | 新增 per-version 指标方法 |
| `application.yml` | 修改 | experiment 配置段 |

**总改动量**：6 个新文件 + 3 个修改 + 0 个删除

---

## 六、版本演进能力

| 操作 | 方法 | 影响范围 |
|------|------|----------|
| 新建 Prompt 版本 | 文件系统加 `v3/` 目录 → 重启自动导入，或 POST API | 数据库新增记录 |
| 切换版本 | POST `/api/admin/prompts/addie_review/2/activate` | 新请求立即使用 v2 |
| A/B 实验 | 配置 `splits` 比例 → 重启 / 动态刷新 | 按 userId hash 分配 |
| 回滚 | 激活旧版本即可 | 即时生效 |
| 对比效果 | GET `/api/admin/prompts/addie_review/compare?v1=1&v2=2` | 返回实时聚合指标 |
| 审计追溯 | 查询 `prompt_versions` 表 | 所有历史版本完整记录 |

面试时能说："Prompt 迭代了 4 个版本，v2 review 评分 82.3 → v3 评分 89.1，基于 userId hash 做 A/B 分流验证后全量切换，同一用户 session 内版本一致无污染。"

---

## 七、与已有系统的关系

| 已有组件 | 影响 |
|----------|------|
| `PromptLoader` | 保留，`PromptRegistry` 启动导入时依赖它读取文件 |
| `AddiePipeline.loadPrompt()` | 改为三步骤，新增 `userId` 参数 |
| `PipelineMetrics` | 新增 `recordPromptUsage()` / `recordPromptScore()` 方法，不改已有接口 |
| `ChatController` | `lessonPlanV2()` / `chatStream()` 中调用 `loadPrompt` 时传入 `@CurrentUser` |
| MySQL `gagneflow` 库 | 新增 `prompt_versions` 表，JPA `ddl-auto=update` 自动建表 |
| Micrometer | 已有依赖，`PromptMetricsCollector` 复用 |

**零破坏性变更**：`PromptLoader` 的公开 API 保留，`PromptRegistry` 作为增强层叠加其上。

---

## 八、设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 版本号类型 | String / int | **int** | 支持排序、比较，避免 "v9" > "v10" 问题 |
| 分流位置 | Registry 内部 / 调用方组合 | **调用方组合** | Registry 保持纯函数，Experiment 专注分流 |
| 随机方式 | random / userId hash | **userId hash** | 同一用户 session 内版本一致 |
| 指标存储 | MySQL JSON / Micrometer | **Micrometer** | 高频率写入不压 DB，实时聚合 |
| Admin API 认证 | 开放 / JWT + role | **JWT + ADMIN role** | 不能登录就能切 Prompt 版本 |

---

*文档版本: v2.0（基于审查意见修正） | 状态: 待实现 | 预计实现时间: 4-6 小时（含测试）*
