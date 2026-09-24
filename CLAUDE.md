# CLAUDE.md

本文件面向在此仓库工作的 AI 编码助手（Claude Code 等）。内容只讲**动手改代码时需要知道的事**：怎么跑、哪些约束不能碰、哪些坑已经踩过。

项目是什么、怎么部署、文档在哪，见 [README.md](README.md)。

---

## 一、常用命令

```bash
node start.js all        # 同时起前后端（起前端前会等后端就绪）
node start.js backend    # 只起后端
node start.js frontend   # 只起前端
node start.js build      # 编译后端（mvn clean package -DskipTests）
```

端口被占用时脚本会自动结束占用进程；`--no-kill` 可改为只报错。日志按 `[后端]` / `[前端]` 加前缀。

前后端手动命令：

```bash
mvn spring-boot:run              # 后端，8080
mvn clean compile                # 只编译（验证改动最快的方式）

cd frontend && npm run dev       # 前端，5173
cd frontend && npm run build     # 类型检查 + 构建（提交前务必跑）
cd frontend && npm run typecheck # 仅类型检查
```

**验证改动的最低要求**：

- 改 Java → `mvn clean compile`
- 改前端/TS → `cd frontend && npm run build`

### 当前没有的东西，别假设存在

| 没有 | 说明 |
|---|---|
| Lint / Prettier | 未配置 ESLint 或 Prettier，**不要运行 `npm run lint`** |
| Maven Wrapper | 没有 `mvnw`，依赖 PATH 上的 `mvn` |
| 认证体系 | T1 已实现：双表双入口 + JWT 拦截器 + AuthContext（基础架构PRD）。**新增接口必须先在基础架构PRD 3.3 权限矩阵登记**——B 端接口路径 `/api/admin/**`，C 端 `/api/**`，公开接口仅登录注册 / 常量下发 / 分享 |
| 向量库 | 刻意不引：同题归一的语料量级在万级以内，进程内向量 + 暴力余弦足够（知识库PRD 九-3）；量级超限或出现在线语义检索需求时再评估，**不要顺手引 Milvus / Qdrant 等** |
| Spring Security | 刻意不引：轻量拦截器 + JWT + 当前用户上下文（基础架构PRD 第三节）；需要 OAuth2 / 方法级权限时再评估，**不要顺手引入** |

> **llm 模块是唯一的例外：刻意引入 Spring AI 2.0**（T2，决策清单 D28）。理由：全计划唯一外部服务依赖，需统一屏蔽 provider（火山方舟 / DeepSeek / GLM 走 OpenAI 兼容端点），换服务商只改配置。约束：**provider 调用逻辑一律走 `llm.api`（LlmClient），业务模块不得直连 Spring AI 或自带 HTTP 调用**；密钥只走本地 `config/application-local.properties` / 环境变量，**不进库、不进设置页、不入库、不进 jar**；新增 LLM 入口（用途）在 `LlmPurpose` 枚举登记（自动进常量组下发）。

> 涉密配置的落点（所有模块通用）：真实密钥写运行目录的 `config/application-local.properties`（由 `config/application-local.properties.example` 模板复制而来，`config/*.properties` 已在 .gitignore 排除、只放行模板）。开发时自动加载（`spring.profiles.default=local`），显式激活其他 profile（测试 `test`、部署 `prod`）时不参与——**不要在 `src/main/resources` 里放任何密钥，那里会被打进 jar**。

> 测试基建（T0 已引入）：spring-boot-starter-test（JUnit 5 + MockMvc），`mvn test` 是合法验收命令；测试用独立 SQLite 测试库，不 mock 数据层。常量组走后端注解扫描 + `GET /api/constants` 下发（基础架构PRD 第二节），前端 label 免维护。

---

## 二、架构约束（不可违反）

### 后端模块分层

每个业务模块内部四层，方向自上而下，**禁止跨层反向调用**：

```
module/
├── api/       对外接口定义（供其他模块或 web 层调用）
├── service/   业务编排（事务边界在这一层）
├── domain/    领域对象与规则
└── repo/      MyBatis-Plus Mapper（只被本模块 service 使用）
```

- **跨模块调用只允许走对方的 `api`**，`repo` 不出模块。这是将来能拆微服务的前提。
- **web 层只依赖领域模块**，领域模块不反向依赖 web。Controller 只做参数校验和 DTO 转换。
- Mapper 必须放在 `@MapperScan("com.zhiji.**.repo")` 覆盖的包下，否则启动失败（实测踩过）。

### 领域不变量

这几条是产品设计的根基，改动时若与之冲突，说明方向错了：

1. **LLM 只做对齐判断，不做知识判断。** 预制评分要点是尺子，LLM 只把用户回答对齐到尺子。判定规则（四分流、档位公式、引用强制）必须写在 `judge` 模块自己实现，不能交给模型。
2. **分是公式算的，LLM 从不碰分数。** 档位由命中要点的深度分布确定性计算。
3. **引用强制**：命中要点必须附用户原话引用，引不出算未命中。
4. **`layer_key` 是追问层的稳定身份**，永不复用、永不修改。数组下标只作展示排序，**禁止用下标作身份**（会导致历史判定错位）。它贯穿三处：`followups[].layer_key`、`rubric`、`answer_log.layer_key`。
5. **库/态分离**：全局内容资产进 `kp_*`，个人私有数据进 `user_*`。**用户的独一无二内容绝不进全局库表**。
6. **库校对不碰用户进度**：重开方只重写 `user_path`，`user_kp_state` 原地不动。
7. **知识点状态机**：`draft → reviewed → active`，下线或合并转 `retired` 并保留指向。未过 `reviewed` 的内容不许进用户路径。
8. **账号双表双入口**：客户（sys_customer）与后台人员（sys_staff）分表、分登录入口，两套会话互不通用；公开注册路径永远写不到后台身份（D18）。

### 前端结构

- `api/` 按**后端模块**组织（每个模块一个文件，如 `profile.ts` / `account.ts`），与接口清单一一对应。
- `views/` 按**功能页**组织：`views/learn/` 是 C 端、`views/admin/` 是 B 端；`views/auth/`（客户登录 / 注册与后台登录）与 `views/share/`（知识卡分享公开页）是免登录公共页。
- 接口契约的枚举（`SubType` / `VerdictLevel` / `NextAction` 等）必须写进 `api/types.ts`，取值与后端 CHECK 约束、核心模块定义附录 A 保持一致。**这是选 TypeScript 的主要理由**，不要用 `string` 糊过去。
- B 端侧边菜单收口在 `AdminLayout` 的**菜单注册表**数组：新增页面先在 B端管理台PRD 页面总览表登记，再加菜单行（带可见角色字段，editor 隐藏"系统"组）。
- C 端与 B 端在同一工程内靠路由区分（`/` 与 `/admin`），部署为一份构建产物。

---

## 三、SQLite 实测陷阱（必读）

换成 SQLite 后有几处与 MySQL 行为不同，**都是实际踩过的**：

| # | 陷阱 | 表现 | 应对 |
|---|---|---|---|
| A | 不自动创建数据库父目录 | 启动直接失败 `path to './data/zhiji.db' does not exist` | `ZhijiApplication.main()` 里先 `Files.createDirectories()` |
| B | 约束异常不被 Spring 归类 | SQLite 驱动抛约束异常时 SQL state 为 **null**，落到 `UncategorizedSQLException` 而非 `DataIntegrityViolationException` | `GlobalExceptionHandler` 必须显式接住并翻译成 4xx，否则用户看到 500 |
| C | 默认不开启外键 | FK 形同注释 | 连接串必须带 `foreign_keys=on` |
| D | 单写锁 + 弱类型 | 写操作串行；`INTEGER` 列可能存进字符串 | 用 `journal_mode=WAL`；类型正确性靠应用层 Bean Validation |
| E | 无 JSON 类型 | — | JSON 存 `TEXT`，`json_extract` 等函数可用；**但按 JSON 子字段查询无法走索引**，需要聚合的字段必须提成实列 |

连接串（勿改动参数含义）：

```
jdbc:sqlite:./data/zhiji.db?journal_mode=WAL&foreign_keys=on&busy_timeout=5000
```

约束执行是**双层防护，两层都要有**：应用层 Bean Validation 给出可读中文错误，数据库 CHECK/UNIQUE/FK 防住绕过应用的写入（手工 SQL、批处理）。

---

## 四、Spring Boot 4 注意事项

项目已从 3.5.16 升级到 **4.1.1**。以下是升级后的实际状态，改动依赖时别改回去：

| 项 | 正确写法 | 说明 |
|---|---|---|
| Web starter | `spring-boot-starter-webmvc` | 4.1 里旧名 `starter-web` 已**删除**，不是废弃 |
| MyBatis-Plus | `mybatis-plus-spring-boot4-starter` | 用 Boot3 版会不兼容 |
| JSON | Jackson 3（`tools.jackson`） | 包名从 `com.fasterxml.jackson` 变更 |
| JDK | 21 | Boot 4 底线 17，Java 21 无需变更 |

**前端 TypeScript 钉在 5.8.3，不要升到 7.x。** TS 7 是 Go 重写的编译器，`vue-tsc` 依赖其编译器 API，升上去大概率导致类型检查失败。等生态跟上再升。

---

## 五、编码约定

- **不写注释**，除非确有必要解释"为什么"（而非"是什么"）。现有代码里的中文注释都是在记录**设计意图或踩过的坑**，例如某处为何必须这样写。这类注释要保留。
- Java 用构造器注入，不用字段注入。
- 后端接口统一返回后端的错误体 `{ status, code, message }`，前端 `api/request.ts` 已做透传。
- 前端组件用 `<script setup lang="ts">` + Composition API。
- 数据库表名、字段名用 snake_case，Java 侧由 MyBatis-Plus 的 `map-underscore-to-camel-case` 转换。
- SQL 语句用 `CREATE TABLE IF NOT EXISTS` 保持幂等（`schema.sql` 每次启动都执行）。

---

## 六、当前状态与下一步

`com.zhiji.demo` 包与 `demo_kp` 表**只是环境验证用的探针**，不属于正式业务，最终会被删除。`schema.sql` 目前也只有这张演示表。

按设计文档的落地顺序，下一步是——**任务拆分与顺序的真相源是 [开发计划](docs/开发计划.md)（决策清单 D24：B 端先行、基础公共最前置，每任务「PRD → 建表 → 后端 → 前端 → 测试 → 验收」）**：

- 当前任务：**T8 按簇成稿**（T0-T7 已完成——T2 llm 支撑：Spring AI 2.0 模型接入、按 purpose 路由、限额与熔断（D32 修订：全站总熔断取消，C 端按用户维度限额，B 端生产类不限）、连通性自检与用量对照、B 端系统配置页；T3 已缩编为设计任务：技能条改 LLM 两阶段生成 + 考点 ID 锚定、B 端域管理页取消、skill 包解散，产出技能库PRD，生成实现并入 T10，见决策清单 D30；T4 知识库：kb 模块与 10 样板已上线；T5 知识卡分享：公开接口与卡图已上线；T6 素材库：导入解析拆题三路已上线；T7 同题归一与知识地图：三层漏斗 / 语料簇 / 地图页已上线，归一题向已有知识点轻量映射含自动标 built + 解除映射纠错口）。设计见知识库PRD 九-4 与 B端管理台PRD 7.2
- 随后：T8 按簇成稿 → T9 校对流转（里程碑：50 个知识点 B 端闭环）→ T10-T16 C 端（各域 PRD 先行）→ T17 收尾

合规清单（备案 / HTTPS / 隐私说明 / 备份）公网开放时再办、不阻塞开发，见核心模块定义第七节。

涉及数据库、接口契约的改动，**先读对应设计文档再动手**（参考顺序：企划书 → 核心模块定义 → 各域 PRD）：

- 表归属矩阵、枚举契约、判定 JSON 契约 → [核心模块定义](docs/核心模块定义.md) 附录 A（**现阶段只写需求 PRD，不建独立研发设计文档**；字段级 DDL 以 kb 模块落 schema.sql 时为准）
- 内容资产要求、判定规则细节 → [知识库PRD](docs/prd/知识库PRD.md)
- 产品流程与判定规则 → [项目企划书](docs/项目企划书.md)
- B 端页面功能详设 → [B端管理台PRD](docs/prd/B端管理台PRD.md)
- 基础公共契约（统一响应 / 常量组 / 权限矩阵 / 测试）→ [基础架构PRD](docs/prd/基础架构PRD.md)
- 技能域字典与技能条目组装 → [技能库PRD](docs/prd/技能库PRD.md)
- 开发任务拆分与顺序（T0-T17）→ [开发计划](docs/开发计划.md)
- 决策索引与待观察事项（现行口径一律以上方核心文档为准）→ [文档审核决策清单](docs/文档审核决策清单.md)

设计文档是真相来源。**代码与文档冲突时，先确认是哪一方需要修正，不要默默按代码改文档。**

### 开发工作流（2026-09-22 拍板，2026-09-23 收紧）

- **以 [核心模块定义](docs/核心模块定义.md) 为开发任务的基础**，按其模块清单逐个开发，每个模块是一段独立任务。
- **业务域会陆续抽出独立 PRD，统一收口在 docs/prd/ 目录（2026-09-23 拍板，决策清单 D25）**：知识库已抽出 [知识库PRD](docs/prd/知识库PRD.md)、B 端已抽出 [B端管理台PRD](docs/prd/B端管理台PRD.md)、技能库已抽出 [技能库PRD](docs/prd/技能库PRD.md)；后续模拟考、抽卡等域在开发该模块前同样抽出独立 PRD 详细完善。开发某模块时，先看核心模块定义该模块一节，再看对应域 PRD（若已抽出）。
- **拍板决策的迁移规则（2026-09-23 收紧）**：所有拍板结论以三份核心文档（项目企划书 / 知识库PRD / 核心模块定义）与各域 PRD 的现行口径为准；[文档审核决策清单](docs/文档审核决策清单.md) 只承担**决策索引与未决事项追踪**（每条一句话结论 + 现行口径落点），**不承载现行口径、不写长篇过程分析**——发现两者冲突时，以核心文档为准并回改决策清单。
