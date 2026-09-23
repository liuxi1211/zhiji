# T2 llm 支撑模块 · 实施计划

## Summary

实现[开发计划](d:\ideaProject\zhiji\docs\开发计划.md) T2：llm 支撑模块（`com.zhiji.llm` 包）。设计真相源为[核心模块定义 4.2](d:\ideaProject\zhiji\docs\核心模块定义.md) 与 [B端管理台PRD 第十节](d:\ideaProject\zhiji\docs\prd\B端管理台PRD.md)、[附录 A](d:\ideaProject\zhiji\docs\核心模块定义.md)。本任务是全计划唯一外部服务依赖，为后续所有 AI 能力（判定引擎 T12 / 建档 T10 / 内容管道 T6-T8）提供统一的模型接入与成本治理地基。

核心做法：

1. **接入 Spring AI 2.0.1**（`spring-ai-starter-model-openai`），用 **OpenAI 兼容适配器**统一屏蔽 provider 差异——火山方舟 / DeepSeek / GLM 都提供 OpenAI 兼容端点，差异只落在「base-url + api-key + 模型名」，加一家 provider 只加配置、不改业务代码；
2. **llm 模块四层**（api / service / domain / repo）：模型路由（按 purpose）、chat 与 embedding 调用、Prompt 渲染入口、强制 JSON 校验 + 失败重试、按「入口 × 天」落库计数、按档案限额与全站每日熔断；
3. **建两张表**：`sys_config`（运行参数 key-value）+ `llm_usage_daily`（调用计数，归属 llm，需回改附录 A）；
4. **前端 SettingsView 接线**：模型路由 / 限额 / 连通性自检 / 用量对照；
5. **文档收尾**：附录 A 补 `llm_usage_daily`、决策清单加一行、CLAUDE.md / README 记录 Spring AI 为刻意引入。

验收 = 开发计划 T2「限额生效、熔断触发走降级、连通性自检可用」+ `mvn test` 绿 + `npm run build` 绿。

## 关键澄清：sys_config 是干什么的（为什么必须建这张表）

**一句话**：`sys_config` 是 B 端 `/admin/settings` 页面的**后端存储**——把"改模型、调限额"变成**不重启、不发版、即改即生效**。

它只存**两类运行参数**，不存密钥、不存业务数据：

1. **模型路由**：哪个用途（判定 / 总评 / 起草 / 成稿…）用哪个 provider 的哪个模型、温度多少；
2. **限额与熔断**：各入口每天多少次、单档案每日合计上限、全站每日总熔断阈值。

**为什么必须存库**——这是设计文档的硬要求，不是实现自选：

> 核心模块定义 4.2：「限额与模型路由均为运行参数，B 端**系统配置页可调整，改动即生效**；参数存配置表（llm 包拥有），**不写死在代码**。」
>
> 附录 A 8.1 表归属矩阵：`sys_config | llm | 运行参数：LLM 限额、模型路由、全站每日 LLM 总熔断等（B 端 /admin/settings 读写）`。

三种存法对比（前两种被需求否掉）：

| 存法 | 改模型 / 调限额要做什么 | 满足"页面可调、改动即生效" |
|---|---|---|
| 写死在 Java 代码 | 改代码 → 重新编译发版 | ✗ |
| 放 application.yml | 改配置 → **重启服务** | ✗（要重启） |
| **放 sys_config + 内存快照** | 页面点保存 → 刷新快照 | ✓ 且重启不丢 |

**与 application.yml 的分工（关键边界）**：

| 配置项 | 放哪 | 谁改 | 为什么 |
|---|---|---|---|
| provider 的 `base-url` | application.yml | 运维/开发者 | 换服务商是部署行为 |
| provider 的 `api-key` | 本地 `config/application-local.properties` / 环境变量 | 本机（运维） | 安全：绝不入库、绝不进页面、绝不进 jar |
| 模型**名**、温度 | **sys_config** | 管理员在设置页 | 需求要求页面可调、即改即生效 |
| 各入口**限额**、熔断阈值 | **sys_config** | 管理员在设置页 | 同上 |

**为什么是 key-value + JSON**（而非给每个参数开一列）：运行参数会持续增加（每加一个 LLM 入口就多一条限额），key-value 结构加参数不用改表；且这些 JSON 只在"整份读取 / 整份写回"时使用，不按子字段查询，因此不受 CLAUDE.md 陷阱 E（按 JSON 子字段查询无法走索引）约束。

> 若不做这张表，则"设置页调整限额 / 模型"无处落库，只能退回改代码或改 yml 重启——直接违背 T2 验收项「限额生效」与核心模块定义 4.2 的"运行参数"口径。

## Current State Analysis

- **后端**：Spring Boot **4.1.1**（Spring Framework 7、Jackson 3）+ MyBatis-Plus 3.5.17 + SQLite。`@MapperScan("com.zhiji.**.repo")` 已覆盖新包；`@ConfigurationPropertiesScan` 已开启（`AuthProperties` 用同一机制）。
- **既有可复用基建**：
  - `BizException.of(status, code, message)` + `GlobalExceptionHandler`（错误体 `{status, code, message, fields?}`）；
  - 常量组系统：`@ConstantGroup` 注解 + `ConstantItem` 接口 + `ConstantRegistry` 启动扫描 + `GET /api/constants` 下发（新增枚举自动进下发）；
  - `AuthContext`（拦截器注入）与 `AuthInterceptor`/`WebMvcAuthConfig`：`/api/admin/settings/**` **已登记为仅 admin**（基础架构PRD 3.3 权限矩阵），无需改拦截器；
  - `CacheConfig`（Caffeine，进程内）；
  - 启动自举先例：`AdminBootstrap`（`ApplicationRunner`，空则建默认数据）。
- **前端**：Vue 3.5 + Pinia + Element Plus；`views/admin/SettingsView.vue` 为占位空页；`router/index.ts` 已有 `admin-settings` 路由；`AdminLayout` 菜单注册表已有「系统配置」项（仅 admin 可见）；`api/request.ts` 已处理双会话 token / 401 / 403。
- **schema.sql**：现有 `demo_kp` + `sys_staff` + `sys_customer`，全部 `CREATE TABLE IF NOT EXISTS`；按任务增量追加。
- **约束（CLAUDE.md）**：四层分层、跨模块只走 api、repo 不出模块、构造器注入、不写无意义注释、SQLite 陷阱（WAL / 约束异常归类 / 外键 / 实列化 / 需先建目录）、常量三处同步（DB CHECK + 后端枚举 + 前端 types.ts）。
- **兼容性核实（已查官方）**：Spring AI **2.0.x 明确支持 Spring Boot 4.0.x / 4.1.x**，基于 Spring Framework 7 + **Jackson 3**（与本项目一致），JDK 21 满足。2.0 起 provider 差异走 OpenAI 兼容端点（官方 Perplexity / NVIDIA 等文档即以 `base-url + model` 复用 OpenAI client）。火山方舟 coding plan 的 OpenAI 兼容端点：`https://ark.cn-beijing.volces.com/api/coding/v3`（注意**不是** `/api/v3`，后者另计费）。

## Proposed Changes

### A. 依赖与配置（pom.xml / application.yml）

**pom.xml** 新增：

```xml
<properties>
  <spring-ai.version>2.0.1</spring-ai.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>${spring-ai.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
  </dependency>
</dependencies>
```

**application.yml** 追加：

```yaml
spring:
  ai:
    model:
      chat: none        # 关闭 OpenAiChatModel 自动装配（provider 由 llm 模块按 yml 手动构建）
      embedding: none   # 同上

zhiji:
  llm:
    timeout-seconds: 15
    providers:          # 密钥只在此处/环境变量，绝不入库、绝不进设置页
      ark:
        base-url: https://ark.cn-beijing.volces.com/api/coding/v3
        api-key: ${ZHIJI_LLM_ARK_KEY:}
```

- **密钥边界（已拍板）**：provider 的 `base-url` 走 `application.yml`，`api-key` 只走本机 `config/application-local.properties`（模板入库、真实配置不入库）或环境变量；设置页**不展示明文、不可编辑密钥**，只在下拉里选 provider 名。
- 关闭自动装配用 `spring.ai.model.chat/embedding: none`；若 2.0.1 该属性不生效，则退回在 `@SpringBootApplication(exclude=...)` 排除 `OpenAiChatAutoConfiguration` / `OpenAiEmbeddingAutoConfiguration`（落地时以编译/启动实测为准）。

### B. 建表（追加 `src/main/resources/db/schema.sql`）

```sql
CREATE TABLE IF NOT EXISTS sys_config (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key   TEXT    NOT NULL UNIQUE,
    config_value TEXT    NOT NULL,                 -- JSON 文本
    description  TEXT,
    updated_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS llm_usage_daily (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    stat_date  TEXT    NOT NULL,                   -- yyyy-MM-dd
    purpose    TEXT    NOT NULL,                   -- LlmPurpose code
    profile_id INTEGER NOT NULL DEFAULT 0,         -- 0 = 无档案（B端人工触发/全站）；不用 NULL，见下
    call_count INTEGER NOT NULL DEFAULT 0,
    fail_count INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT    NOT NULL DEFAULT (datetime('now')),
    UNIQUE (stat_date, purpose, profile_id)
);
CREATE INDEX IF NOT EXISTS idx_llm_usage_date ON llm_usage_daily (stat_date);
```

- **profile_id 用 `0` 哨兵而非 NULL**：SQLite（及多数库）UNIQUE 索引里 NULL 互不相等，用 NULL 会让「同一天同一 purpose 的无档案计数」产生多行、upsert 失效（属 CLAUDE.md 陷阱 D 的应用层落地）。此点写入代码注释。
- 计数为「入口 × 天」，upsert 自增；「单档案当日合计」= `SUM(call_count) WHERE profile_id=? AND stat_date=?`；「全站当日合计」= `SUM(call_count) WHERE stat_date=?`（实列化，可走索引）。

### C. 模块分层与文件清单（新建 `src/main/java/com/zhiji/llm/`）

严格四层，方向 api → service → domain → repo；对外只暴露 `api`。

#### C1. `llm/api/`（业务模块唯一依赖面）

| 文件 | 内容 |
|---|---|
| `LlmPurpose.java` | 调用入口枚举，实现 `ConstantItem`，标 `@ConstantGroup("llm_purpose")`（前端设置页 label 免维护）。取值见下方「入口矩阵」；带 `limitKey()`（`JUDGE`/`VARIANT`/`NONE`）与 `defaultTier()`（`JUDGE`/`QUALITY`/`EMBED`） |
| `LlmRequest.java` | `record LlmRequest(LlmPurpose purpose, Long profileId, String systemPrompt, String userPrompt, Double temperature, Integer maxTokens)` + 静态 `of(...)` 便捷构造 |
| `LlmClient.java` | 业务模块注入的接口：`String complete(LlmRequest)`、`<T> T completeJson(LlmRequest, Class<T>)`、`List<double[]> embedAll(List<String>)` |
| `PromptTemplate.java` | 极简 Prompt 渲染入口（`{{var}}` 占位替换，JDK 正则实现，不引模板引擎）。模板文本归各业务模块 resources，llm 只提供渲染 |
| `LlmConnectivityReport.java` | 连通性自检结果 DTO：`record Item(String purpose, String provider, String model, boolean ok, String detail)`，`record Report(boolean allOk, List<Item> items)` |

**入口矩阵（LlmPurpose，代码内置，与核心模块定义 4.2 三层入口一致）**：

| purpose | 中文 | limitKey | 默认 tier |
|---|---|---|---|
| `judge_practice` | 练习模式判定 | JUDGE（软 200/档案/天） | JUDGE（便宜模型，温度 0） |
| `judge_gacha` | 抽卡判定 | JUDGE（同上） | JUDGE |
| `judge_exam` | 模拟考逐问判定 | NONE（仅全局兜底） | JUDGE |
| `profile_draft` | 建档整理 | NONE | QUALITY |
| `narrative_draft` | 叙事故事起草 | NONE | QUALITY |
| `exam_summary` | 教练总评 | NONE | QUALITY |
| `variant_rewrite` | 变体现场改写 | VARIANT（硬 20/档案/天） | QUALITY |
| `split_fallback` | 拆题兜底 | NONE | QUALITY |
| `cluster_dedup` | 聚类去重 | NONE | QUALITY |
| `ai_compose` | AI 成稿 | NONE | QUALITY |
| `embedding` | 向量化 | NONE | EMBED |

>T2 只落地入口枚举与路由/限额骨架；实际调用方在 T6-T12 接入，本任务不改任何业务模块。

#### C2. `llm/domain/`

| 文件 | 内容 |
|---|---|
| `SysConfig.java` | `@TableName("sys_config")`：id、configKey、configValue、description、updatedAt |
| `LlmUsageDaily.java` | `@TableName("llm_usage_daily")`：id、statDate、purpose、profileId、callCount、failCount、updatedAt |
| `LlmRouteConfig.java` | 路由 JSON 反序列化模型：`record RouteTarget(String provider, String model, Double temperature, Integer maxTokens, boolean embedding)` + `Map<String,RouteTarget> purposes` |
| `LlmLimits.java` | 限额 JSON 模型：`record LlmLimits(int judgeDaily, int variantRewriteDaily, int perProfileDailyTotal, int siteDailyTotal)` |

#### C3. `llm/repo/`

`SysConfigMapper`、`LlmUsageDailyMapper`（MyBatis-Plus `BaseMapper`）。计数 upsert 用 `LlmUsageDailyMapper` 的 `@Insert` 原生语句 `INSERT ... ON CONFLICT(stat_date,purpose,profile_id) DO UPDATE SET call_count=call_count+1`（SQLite 支持 upsert）。

#### C4. `llm/service/`（业务编排与核心逻辑）

| 文件 | 内容 |
|---|---|
| `LlmProperties.java`（放在 `llm/config/`） | `@ConfigurationProperties("zhiji.llm")`：`timeoutSeconds`、`Map<String,Provider> providers`（Provider：baseUrl、apiKey） |
| `LlmConfigService.java` | 读写 `sys_config` 的 `llm.route` / `llm.limits` 两个 key（JSON），维护**内存快照**（`volatile`）——启动加载、写后刷新，做到「改动即生效」；提供默认值 |
| `LlmTransport.java` | **可替换网络缝（seam）**：`String call(String provider, String model, double temperature, Integer maxTokens, String system, String user, int timeoutSeconds)`、`List<double[]> embed(String provider, String model, List<String> texts)`。实现见下 |
| `SpringAiTransport.java` | `LlmTransport` 的 Spring AI 实现：`Map<provider, OpenAiChatModel>` / `Map<provider, OpenAiEmbeddingModel>` 启动时按 `LlmProperties.providers` 用 builder 构建（`OpenAiApi.builder().baseUrl().apiKey()`，模型/温度按调用参数传入），调用即网络出口 |
| `LlmRateGuard.java` | 调用前校验：①该 purpose 的 limitKey 软/硬限额（按档案当日）；②单档案当日合计硬熔断；③全站当日合计硬熔断。硬超限抛 `BizException(429,"SYS_RATE_LIMITED",...)` / `(429,"SYS_CIRCUIT_OPEN",...)`；软限额不阻断（仅在用量视图标记告警） |
| `LlmUsageService.java` | 计数 upsert（call/fail）、当日按 purpose / 按档案合计 / 全站合计、近 7 天聚合 |
| `LlmGatewayService.java` | **实现 `LlmClient`**：路由解析 → 限额守卫 → transport 调用 → 强制 JSON 校验（剥 markdown 围栏 + 解析）+ 失败重试一次 → 计数落库。JSON 两次仍失败/超时抛 `BizException(502,"SYS_PROVIDER_ERROR",...)`，由业务层降级（如判定解锁自评） |
| `LlmHealthService.java` | 连通性自检：对当前路由去重后的每个 (provider, model) 发一次最小请求，逐项返回可读原因（含「未配置」）；embedding 单独标记，缺额度不 crash |
| `LlmConfigInitializer.java` | `ApplicationRunner`：`sys_config` 无 `llm.route` / `llm.limits` 时写入默认值（默认 provider=ark，判定/总评模型给可改的种子值，embedding 默认未配置） |

**降级契约（已拍板）**：限额/熔断一律**抛业务异常**，不静默返回假数据；业务层据 code 决定降级动作。文案示例：`SYS_RATE_LIMITED`「今日调用已达上限」、`SYS_CIRCUIT_OPEN`「服务繁忙，请稍后再试」。

**路由解析**：`LlmConfigService.route(purpose)` → `RouteTarget`；provider 名映射到 `LlmProperties.providers` 取 base-url/key。改模型 = 改 `sys_config`；换 provider = 改 yml。业务代码不认识任何具体模型名。

#### C5. `llm/api/SettingsController.java`（B 端系统配置接口，`@RequestMapping("/api/admin/settings")`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/settings` | 返回 `{ routes, limits, providers(name+baseUrl掩码) }`（**不含密钥明文**） |
| PUT | `/api/admin/settings/routes` | 更新路由 JSON（按 purpose 的 provider/model/temperature） |
| PUT | `/api/admin/settings/limits` | 更新限额 JSON |
| POST | `/api/admin/settings/connectivity-check` | 触发连通性自检，返回 `Report` |
| GET | `/api/admin/settings/usage` | 用量对照：各 purpose 当日计数、各 limitKey 限额、单档案/全站合计与熔断状态、近 7 天序列 |

- 权限：`/api/admin/settings/**` 已在基础架构PRD 3.3 矩阵登记为**仅 admin**，拦截器已覆盖，无需改动；仅在计划文档中注明本任务在该路径下新增了具体接口。
- Controller 只做参数校验与 DTO 转换，业务在 `LlmConfigService` / `LlmHealthService` / `LlmUsageService`。

### D. 测试（新建 `src/test/java/com/zhiji/llm/`）

用 stub 替换 `LlmTransport`（`@MockitoBean` 或测试子类），测试不触网：

| 文件 | 覆盖点 |
|---|---|
| `LlmGatewayTest` | 路由按 purpose 命中目标；JSON 合法直接返回；非法 JSON → 重试一次 → 成功；两次均失败 → `SYS_PROVIDER_ERROR`；计数被写入 |
| `LlmRateGuardTest` | 判定软限额超限不阻断（标记告警）；变体硬限额超限抛 `SYS_RATE_LIMITED`；单档案合计 300 抛 `SYS_RATE_LIMITED`；全站熔断抛 `SYS_CIRCUIT_OPEN`（SQLite 测试库） |
| `LlmUsageServiceTest` | upsert 自增；profileId=0 无档案计数不重复建行；当日合计 / 近 7 天聚合正确 |
| `SettingsControllerTest`（MockMvc） | GET 需 admin（editor 403）；PUT routes/limits 落库并刷新快照；connectivity-check 走 stub 返回可读结果 |

沿用既有测试基建（`spring-boot-starter-test` + 独立 SQLite 测试库）。测试需把 `spring.ai.model.chat/embedding` 保持 `none`、不提供真实 key。

### E. 前端（接线既有占位页）

| 文件 | 内容 |
|---|---|
| `api/settings.ts`（新） | `getSettings` / `updateRoutes` / `updateLimits` / `connectivityCheck` / `getUsage` |
| `api/types.ts`（改） | 新增 `LlmPurpose`（联合类型，取值与后端枚举、附录 A 对齐）、`LlmRouteTargetView` / `LlmLimitsView` / `LlmUsageView` / `LlmConnectivityReport` |
| `views/admin/SettingsView.vue`（改） | 三块：①模型路由表单（按 purpose 选 provider + 模型名 + 温度）②限额表单（judge/variant/单档案合计/全站）③「连通性自检」按钮 + 逐项结果（成功/失败原因）④用量对照表（各入口当日计数 vs 限额、熔断状态、近 7 天）。label 用 `constantsStore.name('llm_purpose', code)` |
| `AdminLayout.vue` / `router/index.ts` | 无需改动（菜单与路由已就位） |

### F. 测试/构建命令

- 后端：`mvn clean compile`、`mvn test`
- 前端：`cd frontend && npm run build`

### G. 文档收尾（实现验收后）

| 文档 | 改动 |
|---|---|
| [核心模块定义](d:\ideaProject\zhiji\docs\核心模块定义.md) 附录 A 8.1 | 表归属矩阵补 `llm_usage_daily`（llm，调用计数：入口 × 天，profile_id=0 为无档案）；4.2 落点句补一句「Spring AI 2.0 承载模型接入」 |
| [文档审核决策清单](d:\ideaProject\zhiji\docs\文档审核决策清单.md) | 加一行（一句话结论）：引入 Spring AI 2.0 作模型接入、OpenAI 兼容适配器屏蔽 provider、密钥不进库进 yml、新增 llm_usage_daily 归属 llm |
| [CLAUDE.md](d:\ideaProject\zhiji\CLAUDE.md) | 「刻意不引组件」表补一行说明：**llm 模块刻意引入 Spring AI 2.0（多 provider 可移植）**，并要求「不要把 provider 逻辑写进业务模块，一律走 llm.api」；「当前状态」更新为 T2 进行/完成 |
| [README.md](d:\ideaProject\zhiji\README.md) | 技术栈表加 Spring AI 2.0.1；当前进度更新 T2 |
| [开发计划](d:\ideaProject\zhiji\docs\开发计划.md) T2 | 完成后标 ✅ 并附验收记录 |

## Assumptions & Decisions

| # | 决策 | 理由 |
|---|---|---|
| D-1 | 引入 **Spring AI 2.0.1**（`spring-ai-starter-model-openai` + BOM） | 官方明确支持 Boot 4.0/4.1 + Framework 7 + Jackson 3，与本项目栈一致；统一 ChatClient 抽象，换 provider 不改业务代码 |
| D-2 | **统一 OpenAI 兼容适配器**，provider 只差 base-url + key + model | 火山方舟 / DeepSeek / GLM 均兼容 OpenAI 协议；加 provider 只加一条 yml，依赖最轻 |
| D-3 | 密钥只走本地 `config/application-local.properties` / 环境变量，**不进库、不进设置页、不入库、不进 jar** | 安全边界；设置页只调模型名 / 路由 / 限额，无可泄露面。**2026-09-23 收紧**：密钥不再落 `application.yml`（该文件在 `src/main/resources`，会被打进 jar），模板 `config/application-local.properties.example` 入库、真实文件由 .gitignore 排除；`spring.profiles.default=local` 让本地开发自动加载，测试/部署 profile 下不参与 |
| D-4 | **按 purpose（用途）路由**，业务只认 purpose 不认模型名 | 「换模型服务商只改配置不改业务代码」；purpose 用常量组下发 label |
| D-5 | 限额/熔断**抛业务异常**，业务层降级 | 不静默返回假数据；code 语义化（SYS_RATE_LIMITED / SYS_CIRCUIT_OPEN）便于前端按 code 分支 |
| D-6 | 软限额（判定 200）**不阻断**、仅在用量视图告警；硬限额（变体 20 / 单档案合计 300 / 全站）抛异常 | 贴合 4.2 表「软上限提示后可继续」与「硬熔断降级」的差异；变体硬超限由业务回退预存变体 |
| D-7 | 计数落**新表 `llm_usage_daily`**（入口 × 天） | 支撑概览近 7 天用量与每日熔断判定、重启不丢；按子字段查询可走索引（陷阱 E 实列化）；需回改附录 A |
| D-8 | `sys_config` 用**通用 key-value（JSON 值）** | 加参数不改表结构，贴合「运行参数存配置表」；读写由 `LlmConfigService` 统一收口 |
| D-9 | `profile_id = 0` 哨兵代替 NULL | SQLite UNIQUE 中 NULL 互不相等，会破坏 upsert；哨兵保证「无档案计数」单行 |
| D-10 | 引入 `LlmTransport` 网络缝，Spring AI 实现可替换 | 让路由/限额/JSON 重试/计数全部可单测而不触网；也为 Spring AI 万一不兼容时留最小回退（同 api 契约换 transport 实现） |
| D-11 | embedding 只做**接口预留 + 连通自检**，不要求真实额度 | 火山方舟 coding plan 未必含 embedding；T7 同题归一才真正消费，避免 T2 被外部额度阻塞。**实地验证（2026-09-23）**：coding plan 明确拒绝纯文本 embedding 模型（`UnsupportedModel`），`doubao-embedding-vision-251215` 可用（2048 维）——默认路由直接给该模型，连通自检能全绿 |
| D-12 | Prompt 模板文本归各业务模块 resources，llm 只提供 `PromptTemplate` 渲染入口 | 遵守核心模块定义 4.2「模板文本归各业务模块」；改 prompt 是发版行为 |
| D-13 | 超时 15s、JSON 失败重试一次为 llm 内部默认 | 与核心模块定义 4.1 判定降级口径（15s、重试一次后解锁自评）一致。**超时必须显式写进 options**：Spring AI 的 OpenAiChatOptions 未指定时填 60s、maxRetries 填 3，且 prompt 自带 options 时不合并模型默认 options——不显式设置就是 60s × 多层重试（已修，见 `SpringAiTransport`） |

## Verification

1. **编译/测试**：`mvn clean compile` 绿；`mvn test` 全绿（新增 4 个测试类）；`cd frontend && npm run build` 绿。
2. **启动与建表**：启动后 `sys_config` / `llm_usage_daily` 建表成功；`LlmConfigInitializer` 写入 `llm.route` / `llm.limits` 默认值（日志可见）。
3. **常量下发**：`GET /api/constants` 含 `llm_purpose` 组。
4. **设置页（冒烟，= 开发计划 T2 验收）**：
   - admin 打开 `/admin/settings`，可见路由 / 限额表单与当前 provider 列表；
   - 改模型名保存后重进页面值已更新（改动即生效）；
   - 「连通性自检」对当前路由各目标各发一次最小请求，成功/失败均给出可读原因（embedding 未配置时显示「未配置」而非报错崩溃）；
   - 「用量对照」展示各入口当日计数、限额、单档案/全站合计与熔断状态、近 7 天。
5. **限额与熔断（测试覆盖）**：`LlmRateGuardTest` 证明软限额不阻断、硬限额抛 `SYS_RATE_LIMITED`、全站熔断抛 `SYS_CIRCUIT_OPEN`。
6. **权限**：editor 访问任一 `/api/admin/settings/**` 返回 403（`SettingsControllerTest`）。
7. **文档收尾**：附录 A / 决策清单 / CLAUDE.md / README / 开发计划 T2 五处更新完成。
8. **真实 provider 冒烟（2026-09-23，火山方舟 coding plan key）**：
   - `LiveArkSmokeTest`（提供 `ZHIJI_LLM_ARK_KEY` 才运行，无 key 自动跳过、不触网）：判定链路 `completeJson` 走真实方舟返回可解析 JSON 且当日计数 +1，`embedAll` 返回向量；单次调用约 5s，远在 15s 超时内。
   - 实际服务 HTTP 冒烟（隔离数据目录 + 8081）：连通性自检 3 个去重目标全绿（11 入口 → 3 目标，13.3s）；路由非法输入（未配置 provider / 未知入口 / 空 body）均 400；全量 11 入口保存即生效；用量对照字段（全站计数、单档案峰值、软硬标记、单档案兜底）完整。

> **落地风险与回退**：若 Spring AI 2.0.1 在 Boot 4.1.1 下自动装配或 Jackson 3 出现不兼容导致无法启动，优先用 `spring.ai.model.chat/embedding=none` 或排除对应 AutoConfiguration；仍不可用则保留 `llm.api` 契约，把 `SpringAiTransport` 换成基于 Spring `RestClient` 的 OpenAI 兼容实现（D-10 已留缝），业务代码零改动。
