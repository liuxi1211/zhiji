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
| 测试套件 | `src/test` 不存在，没有单元测试。**不要运行 `mvn test` 并声称通过** |
| Lint / Prettier | 未配置 ESLint 或 Prettier，**不要运行 `npm run lint`** |
| Maven Wrapper | 没有 `mvnw`，依赖 PATH 上的 `mvn` |

新增测试或 lint 配置属于额外决策，先问，不要顺手加。

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
7. **考点状态机**：`draft → reviewed → active`。未过 `reviewed` 的内容不许进用户路径。

### 前端结构

- `api/` 按**后端模块**组织（`profile.ts` / `path.ts` / `practice.ts` …），与接口清单一一对应。
- `views/` 按**功能页**组织，`views/learn/` 是 C 端、`views/admin/` 是 B 端。
- 接口契约的枚举（`SubType` / `VerdictLevel` / `NextAction` 等）必须写进 `api/types.ts`，取值与后端 CHECK 约束保持一致。**这是选 TypeScript 的主要理由**，不要用 `string` 糊过去。
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

按架构文档的落地顺序，下一步是：

1. 后端 `kb` 模块先行，把 19 张表的 SQLite 版建表脚本落进 `schema.sql`，替换 `demo` 包
2. 跑通「考点入库 → 今日卡 → 作答判定」链路
3. 前端从 `/today` 开始接入真实数据

涉及数据库、接口契约的改动，**先读对应设计文档再动手**：

- 表结构、字段含义、评分要点分级制 → [考点库设计](docs/考点库设计.md) 第四节
- 接口清单（I-01 ~ I-20）、SQLite 方言适配 → [系统架构设计](docs/系统架构设计.md) 第五、六节
- 产品流程与判定规则 → [00-项目企划书](docs/00-项目企划书.md)

设计文档是真相来源。**代码与文档冲突时，先确认是哪一方需要修正，不要默默按代码改文档。**
