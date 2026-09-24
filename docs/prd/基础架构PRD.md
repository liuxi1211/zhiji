---
title: 知己 · 基础架构 PRD
date: 2026-09-23
tags: [产品, 面试, 基础架构, 常量, 权限]
source: ai-conversation
project: 知己
---

# 知己 · 基础架构 PRD（T0 产出）

> **定位声明**：本文是开发计划 T0 的产出，是**基础公共部分的契约真相源**：统一响应与错误码、常量组系统、会话与权限、当前用户上下文、测试策略。业务口径仍以各核心文档为准；实现细节的最终裁定权在本文（这是它区别于域 PRD 的地方——它就是研发契约）。

## 一、统一响应与错误码

- **成功响应**：HTTP 200，body 直接为业务 JSON，不包壳。
- **错误响应**：统一 `{ status, code, message, fields? }`（既有约定延续），`fields` 为字段级校验错误（Bean Validation 输出）。
- **错误码格式**：`分段_语义`，全大写下划线。分段对应后端包：

| 分段 | 域 | 示例 |
|---|---|---|
| AUTH | 账号与会话 | AUTH_TOKEN_INVALID / AUTH_TOKEN_EXPIRED / AUTH_FORBIDDEN / AUTH_LOGIN_FAILED / AUTH_CAPTCHA_INVALID / AUTH_USERNAME_TAKEN |
| COMMON | 通用 | COMMON_VALIDATION / COMMON_NOT_FOUND / COMMON_CONFLICT |
| SYS | 系统与 LLM 支撑 | SYS_RATE_LIMITED（LLM 限额）/ SYS_CIRCUIT_OPEN（全站熔断，已按 D32 降级为默认关闭的可选护栏，命中时仍抛此码）/ SYS_PROVIDER_ERROR |
| KB / PIPELINE / LEARNING / EXAM / JUDGE | 各业务包 | 随模块落地扩充（KP_NOT_FOUND、PIPE_PARSE_FAILED…） |

- 前端按 `code` 分支处理（401 跳登录、SYS_CIRCUIT_OPEN 显示降级提示），`message` 直接展示。

## 二、常量组系统

方案（拍板）：**后端自定义注解 + 启动扫描 + 内存注册表 + 接口下发**。

- **后端**：自定义注解 `@ConstantGroup("candidate_status")` 标注枚举类；枚举统一实现 `ConstantItem` 接口（`getCode()` / `getName()`）。启动时类路径扫描全部 `@ConstantGroup` 枚举，注册进 `ConstantRegistry`（内存单例）。
- **接口（公开，免会话）**：
  - `GET /api/constants` —— 全量：`{ [groupName]: [{ code, name }] }`；
  - `GET /api/constants/{group}` —— 单组。
- **来源唯一**：附录 A 8.2 枚举真相源 ↔ 常量组一一对应。新增枚举的同步从"三处"降为"两处"：数据库 CHECK 约束 + 后端枚举类（前端 label 由接口下发，免维护）。
- **前端**：启动时拉全量入 Pinia `constantsStore`；用法 `constants.name(group, code)`、`constants.items(group)`（遍历渲染下拉等）。`types.ts` 仍保留 TS 类型联合做**编译期**校验——类型防错（编译期）与 label 下发（运行时）并存。
- **边界**：难度五色等纯视觉映射留在前端展示层，不进后端常量组；运行参数（限额、模型路由）走 sys_config，不进常量组。

## 三、会话与权限

### 3.1 框架决策：不引 Spring Security

理由：①双入口双会话是自定义结构（Security 双 filter chain 可做但配置面大于收益）；②当前权限模型只有"接口树级 + staff_role 两值"，拦截器足够；③v1 用不上其核心收益（OAuth2、方法级安全、企业密码策略）；④项目"刻意不引组件"的既定原则。自研鉴权收敛在 account 模块单包内，后续替换成本低。

**再评估触发条件**：需要 OAuth2 第三方登录、方法级细粒度权限、安全合规要求时。

### 3.2 会话方案

- **JWT（HS256，密钥存配置）**，payload 含 `{ userType: staff | customer, userId, role }`；
- **每请求校验签名 + 账号状态**（按 payload 查 staff / customer 表 status 列，主键查询）——停用账号即时失效（D18 硬要求），不加会话表；
- 传递：`Authorization: Bearer <token>`；过期时间：员工与客户均 7 天（记住我后续版本）。

### 3.3 权限矩阵（拦截器照表实现）

| 路径 | 会话要求 | 角色 |
|---|---|---|
| /api/auth/customer/**（login / register / captcha） | 无 | — |
| /api/auth/staff/login | 无 | — |
| /api/constants** | 无 | — |
| /api/share/** | 无 | — |
| /api/**（其余） | 客户会话 | — |
| /api/admin/** | 员工会话 | admin / editor |
| /api/admin/accounts/** | 员工会话 | 仅 admin |
| /api/admin/settings/** | 员工会话 | 仅 admin |

- 新增接口必须先在本矩阵登记再实现；矩阵匹配按"最长前缀优先"。
- **401** = 无会话 / token 无效 / 账号停用；**403** = 已登录但角色不足。前端 request.ts 拦 401 清会话跳登录、403 提示无权限。

## 四、当前用户上下文（用户概念）

- account 模块提供 `AuthContext`（ThreadLocal）：`getStaffId()` / `getCustomerId()` / `getStaffRole()`，由拦截器在鉴权后注入，请求结束清理。
- **所有 B 端写接口可随时取当前操作人**——审计日志表现在不建（D19 触发条件①），但操作人取用能力从第一天就有，加审计只是"加一张表 + 落一笔"。
- 后续功能扩展点全部收敛在 account 模块内：修改口令、找回密码（当前走管理员重置）、第三方登录（若引入 Security 届时替换拦截器）。

## 五、测试策略

- **框架**：spring-boot-starter-test（JUnit 5 + AssertJ + MockMvc）。
- **分层口径**：service 层单测 + 关键接口 MockMvc 集成测试；**数据库用独立 SQLite 测试库**（临时文件，不用内存 mock——SQLite 方言必须真库，陷阱 C/D/E 才真实）。
- **每任务验收** = `mvn test` 绿 + `npm run build` 绿 + 冒烟清单。
- **优先覆盖**（确定性逻辑）：账号会话与权限拦截、判定档位公式与引用强制、SM-2 计算、归一相似度阈值逻辑；不追求覆盖率指标。
- schema.sql 测试隔离：测试配置指向临时库文件，启动建表幂等。

## 六、前端基建

- **constantsStore**：启动拉取全量常量（见第二节）。
- **request.ts**：401 拦截跳登录、403 提示；Authorization 头自动携带。
- **路由守卫**：`/learn/**` 与 `/admin/**` 分别校验客户 / 员工会话；`/admin/**` 下 editor 隐藏"系统"组菜单（AdminLayout 注册表 roles 字段接线）。

## Tags

- tags: [产品, 面试, 基础架构, 常量, 权限]
- date: 2026-09-23
- source: ai-conversation
- project: 知己
