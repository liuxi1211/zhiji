# 知己 · 面试陪练

把"拿着大而全的题库从头瞎刷"换成"只练自己简历会被问到的、自己又讲不出来的"。

知己是一个面试备考工具：粘简历或对话建档 → 生成个性化学习路径 → 单考点学练（看资料 → 追问 → 合卷练 → 隔天复测）→ 模拟考 → 真实面试后复盘回流。核心资产是一个**考点库**，AI 只负责把用户的自由回答对齐到预制评分要点，**不做知识判断、不碰分数**。

> 定位是单人自用工具，商业化不在当前考虑范围。内测阶段目标 5~10 人。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | JDK 21 · Spring Boot 4.1.1（Spring Framework 7） · MyBatis-Plus 3.5.17 |
| 数据库 | SQLite（单文件、零运维，WAL 模式） |
| 缓存 | Caffeine 进程内缓存（刻意不引 Redis） |
| 前端 | Vue 3.5 · Vite 8 · TypeScript 5.8 · Element Plus · Pinia · Vue Router |
| 构建 | Maven 3.9+ · npm |

选型理由见 [系统架构设计](docs/系统架构设计.md) 第二节。简言之：单机单用户规模下，微服务、Redis、MQ 的运维成本换不来任何收益，因此刻意选最简形态。

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
├── docs/                       # 设计文档（见下）
├── data/                       # SQLite 数据库与上传素材（不入库）
├── frontend/                   # Vue 3 前端
│   └── src/
│       ├── api/                # 按后端模块组织；types.ts 固化接口契约枚举
│       ├── layouts/            # LearnLayout（C 端）/ AdminLayout（B 端）
│       ├── router/             # C 端 + B 端两棵路由树
│       └── views/
│           ├── learn/          # C 端：today / path / practice / exam / review / profile
│           └── admin/          # B 端：dashboard / kp / candidates / docs / review-flow
└── src/main/
    ├── java/com/zhiji/
    │   ├── ZhijiApplication.java
    │   ├── config/             # 缓存配置、全局异常处理
    │   └── demo/               # 环境验证用，待替换为真实业务模块
    └── resources/
        ├── application.yml
        └── db/schema.sql       # 当前为演示表，待替换为 19 张正式表
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

## 文档导航

设计文档是**本项目的真相来源**，改动代码前应先读对应文档：

| 文档 | 内容 |
|---|---|
| [00-项目企划书](docs/00-项目企划书.md) | 产品定位、核心流程、分阶段计划 |
| [考点库设计](docs/考点库设计.md) | 核心资产设计：19 张表、评分要点分级制、内容生产管道 |
| [系统架构设计](docs/系统架构设计.md) | 模块划分、接口清单、数据落地、SQLite 适配 |
| [原型-学习路径交互](docs/原型-学习路径交互.html) | 学练主循环交互原型 |
| [原型-建档模拟考复盘交互](docs/原型-建档模拟考复盘交互.html) | 建档 / 模拟考 / 复盘交互原型 |

原型可直接用浏览器打开。

## 当前进度

已完成：

- 技术栈选型验证（SQLite 约束、Caffeine 缓存、MyBatis-Plus 均已在真实运行中验证）
- 后端升级至 Spring Boot 4.1.1
- 前后端工程骨架、双端路由、API 层、开发代理
- 跨平台启动脚本

待办（按依赖顺序）：

1. 后端 `kb` 模块先行，把 19 张表的 SQLite 建表脚本落进 `schema.sql`，替换 `demo` 包
2. 跑通「考点入库 → 今日卡 → 作答判定」链路
3. 前端接入真实数据（从 `/today` 开始）

> `demo` 包与 `demo_kp` 表仅为环境验证用，不属于正式业务。

## 开发约定

代码约定、架构约束与常见陷阱见 [CLAUDE.md](CLAUDE.md)。
