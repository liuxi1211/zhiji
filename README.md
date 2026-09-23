# 知己 · 面试陪练

把"拿着大而全的题库从头瞎刷"换成"只练自己简历会被问到的、自己又讲不出来的"。

知己是一个面试备考工具：粘简历或对话建档 → 按简历把知识库裁成专属关卡（基础关组 + 各技能域关卡组）→ 闯关学练（闪卡快过 / 练习模式扎实过）→ 抽卡复习（复测/抽查/弱点三信号混抽）→ 模拟考（全套真实模拟，每天一次）→ 真实面试后复盘回流。核心资产是一个**知识库**，AI 只负责把用户的自由回答对齐到预制评分要点，**不做知识判断、不碰分数**。

> 定位是小规模公开服务（决策清单 D16-D18）：腾讯云部署、客户开放注册，几位同事以内容维护者（editor）身份在 B 端协作生产题库；商业化仍不启动。引流通路为 CSDN / 小红书发帖。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | JDK 21 · Spring Boot 4.1.1（Spring Framework 7） · MyBatis-Plus 3.5.17 |
| 数据库 | SQLite（单文件、零运维，WAL 模式） |
| 缓存 | Caffeine 进程内缓存（刻意不引 Redis） |
| 前端 | Vue 3.5 · Vite 8 · TypeScript 5.8 · Element Plus · Pinia · Vue Router |
| 构建 | Maven 3.9+ · npm |

选型理由见 [CLAUDE.md](CLAUDE.md) 第三、四节与 [核心模块定义](docs/核心模块定义.md) 第五节。简言之：小规模量级下，微服务、Redis、MQ 的运维成本换不来任何收益，因此刻意选最简形态。

## 环境要求

- **JDK 21+**（必须是 JDK，含 `javac`，不是 JRE）
- **Maven 3.6.3+**
- **Node.js `^20.19.0 || >=22.12.0`**（Vite 8 的要求，本项目在 22.x 上开发）

> 各机器 JDK 安装路径不同，`start.js` 会自动探测，无需写死。详见下方「配置」。

## 快速开始

项目根目录提供跨平台启动脚本（Windows / macOS / Linux 通用）：

```bash
node start.js              # 交互菜单
node start.js all          # 同时启动前端 + 后端（推荐日常使用）
node start.js backend      # 只启动后端
node start.js frontend     # 只启动前端
node start.js build        # 编译后端（mvn clean package -DskipTests）
node start.js help         # 查看全部参数
```

`all` 模式会先清端口、启动后端并等待其就绪，再启动前端，日志按 `[后端]` / `[前端]` 加前缀区分。按 `Ctrl+C` 停止全部服务。

启动后：

| 服务 | 地址 |
|---|---|
| 前端 | http://localhost:5173 |
| 后端 | http://localhost:8080 |

### 手动启动（不想用脚本时）

```bash
# 后端
mvn spring-boot:run

# 前端
cd frontend
npm install
npm run dev
```

### 前端其他命令

```bash
cd frontend
npm run build      # 类型检查 + 生产构建
npm run typecheck  # 仅类型检查
npm run preview    # 预览构建产物
```

## 配置

首次运行 `start.js` 会自动生成 `start.config.json`（**不入库**，每台机器各留一份）。模板见 [start.config.example.json](start.config.example.json)。

```jsonc
{
  "javaHome": "",        // 留空 = 自动探测；探测不到再填本机 JDK 路径
  "mavenCommand": "",    // 留空 = 有 mvnw 用 mvnw，否则用 PATH 上的 mvn
  "backendPort": 8080,
  "frontendPort": 5173,
  "autoKillPort": true   // 端口被占用时是否自动结束占用进程
}
```

**换电脑时**：`javaHome` 留空通常就能自动找到 JDK。脚本按以下优先级探测，配置里路径失效时只告警并继续往下找，不会崩：

命令行 `--java-home` → 环境变量 `ZHIJI_JAVA_HOME` → 配置文件 → 环境变量 `JAVA_HOME` → 各平台常见安装目录扫描 → `PATH` 上的 java 反查 → `mvn -v` 报告的 runtime 路径。

常用临时参数：`--java-home=<路径>`、`--backend-port=<端口>`、`--no-kill`、`--install`。

## 目录结构

```
zhiji/
├── start.js                    # 跨平台启动脚本
├── start.config.example.json   # 本机配置模板
├── pom.xml
├── docs/                       # 设计文档（参考顺序见下）
├── data/                       # SQLite 数据库与上传素材（不入库）
├── frontend/                   # Vue 3 前端
│   └── src/
│       ├── api/                # 按后端模块组织；types.ts 固化接口契约枚举
│       ├── layouts/            # LearnLayout（C 端）/ AdminLayout（B 端，含菜单注册表）
│       ├── router/             # C 端 + B 端 + 公共页三棵路由
│       └── views/
│           ├── learn/          # C 端：map / gacha / exam / review / profile
│           ├── admin/          # B 端：dashboard / skills / kp / candidates / knowledge-map / docs / review-flow / accounts / settings
│           ├── auth/           # 公共页：客户登录 / 注册与后台登录（双表双入口，D18）
│           └── share/          # 公共页：知识卡分享公开页（D14）
└── src/main/
    ├── java/com/zhiji/
    │   ├── ZhijiApplication.java
    │   ├── config/             # 缓存配置、全局异常处理
    │   └── demo/               # 环境验证用，待替换为真实业务模块
    └── resources/
        ├── application.yml
        └── db/schema.sql       # 当前为演示表，待替换为 23 张正式表
```

前端 C 端与 B 端在**同一个工程**内，靠路由区分（`/` 与 `/admin`），部署时是一份构建产物。

## 前端与后端的连接方式

- **开发期**：Vite 代理把 `/api` 与 `/demo` 转发到 `localhost:8080`，无跨域问题。
- **生产期**：由 nginx 托管前端静态产物并反代 `/api` 到后端。

前端 `baseURL` 用的是相对路径 `/api`，因此两种环境同一份代码，不需要按环境切换配置。

部署到 nginx 时需注意：路由使用 history 模式，必须配置 SPA 回退，否则直接访问 `/path`、`/admin/kp` 等地址会 404。

```nginx
location / {
    root /path/to/frontend/dist;
    try_files $uri $uri/ /index.html;
}
location /api/ {
    proxy_pass http://127.0.0.1:8080;
}
```

公网部署（决策清单 D17）还有三件前置：域名 ICP 备案（大陆服务器对外提供网站服务的要求）、HTTPS 证书（登录口令与简历数据的传输保护）、`data/` 目录定期备份（SQLite 单文件，复制即备份）。

## 设计文档（真相源与参考顺序）

设计文档是**本项目的真相来源**，写需求、写代码按以下顺序参考：

1. [项目企划书](docs/项目企划书.md) — 定位与方法论（为什么做）
2. [核心模块定义](docs/核心模块定义.md) — 模块边界、依赖方向与四条领域红线；附录 A 为表归属矩阵（23 张）与枚举契约的真相源
3. 各域 PRD — 功能详设（怎么做）：
   - [基础架构PRD](docs/基础架构PRD.md) — 基础公共契约：统一响应与错误码、常量组、会话与权限矩阵、测试策略
   - [知识库PRD](docs/知识库PRD.md) — 内容资产：存什么、怎么问、怎么判、怎么生产、怎么验收
   - [B端管理台PRD](docs/B端管理台PRD.md) — B 端九页：知识点/簇 CRUD、多源素材导入（形态路由）、候选研判、知识地图（同题归一/按簇建设）、校对流转与账号管理
4. [文档审核决策清单](docs/文档审核决策清单.md) — 决策索引：D1-D24 一句话结论 + 现行口径落点、待观察事项；现行口径一律以上述文档为准
5. [开发计划](docs/开发计划.md) — 开发任务拆分与顺序（T0-T17）：B 端先行、基础公共最前置；每任务「PRD → 代码 → 测试」

拍板一项新决策只做两个动作：改对应核心文档的口径 + 决策索引加一行。两者冲突时以核心文档为准。

现阶段只写需求 PRD，不建独立的研发设计文档；字段级 DDL 以各模块落 `schema.sql` 时为准。

## 当前进度

已完成：

- 技术栈选型验证（SQLite 约束、Caffeine 缓存、MyBatis-Plus 均已在真实运行中验证）
- 后端升级至 Spring Boot 4.1.1
- 前后端工程骨架：C 端五页 + B 端九页（四分组菜单 + 注册表）+ 公共页（客户登录/注册、后台登录、知识卡分享）、API 层、开发代理
- 跨平台启动脚本
- 需求设计收口：企划书 / 核心模块定义 / 知识库PRD / B端管理台PRD 四份文档口径统一，决策索引 D1-D24

待办：按 [开发计划](docs/开发计划.md)（决策清单 D24）顺序推进——**B 端先行、基础公共最前置**，每任务「PRD → 建表 → 后端 → 前端 → 测试 → 验收」：

- 当前：**T2 技能库模块**（skill_domain + B 端技能库页；T1 账号认证已完成——双表双入口、JWT 拦截器、账号管理页，测试 9/9 全绿）
- 随后：T2 技能库 → T3 知识库（10 样板）→ T4 知识卡分享 → T5 llm 支撑 → T6 素材库 → T7 知识地图 → T8 按簇成稿 → T9 校对流转（里程碑：50 个知识点 B 端闭环）
- 之后：T10-T16 C 端各功能（各域 PRD 先行）→ T17 收尾与内测准备（明细见开发计划）

> `demo` 包与 `demo_kp` 表仅为环境验证用，不属于正式业务。

## 开发约定

代码约定、架构约束与常见陷阱见 [CLAUDE.md](CLAUDE.md)。
