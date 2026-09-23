# T1 账号认证模块 · 实施计划

## Summary

实现开发计划 T1：账号认证模块（account 包）+ 基础公共基建。按 [基础架构PRD](d:\ideaProject\zhiji\docs\prd\基础架构PRD.md) 与 [核心模块定义 2.7/3.4](d:\ideaProject\zhiji\docs\核心模块定义.md)、[B端管理台PRD 第十一节](d:\ideaProject\zhiji\docs\prd\B端管理台PRD.md) 实现：

1. **common 基建**：常量组系统（注解 + 扫描 + 接口下发）、BizException、JWT 工具、口令哈希、AuthContext、AuthInterceptor、图形验证码；
2. **account 模块**（四层）：sys_staff / sys_customer 双表、客户注册/登录（验证码）、后台登录、双树拦截器（权限矩阵照表拦截）、B 端账号管理接口；
3. **前端接线**：AuthView（客户登录/注册）、StaffLoginView、AccountsView、auth/constants 两个 store、request.ts 401/403 处理、路由守卫、AdminLayout 菜单角色过滤；
4. **测试**：spring-boot-starter-test（pom 已有），MockMvc 流程用例 + 拦截器权限用例；
5. **文档收尾**：开发计划 T1 标完成、CLAUDE.md 认证行更新。

验收 = B端管理台PRD 十四-4 全条：客户 token 打不进 /admin 接口、员工 token 打不进 C 端 /api 接口、停用即时失效、注册→建档入口可跑。

## Current State Analysis

- **后端**：Spring Boot 4.1.1 + MyBatis-Plus 3.5.17 + SQLite；`@MapperScan("com.zhiji.**.repo")` 已覆盖新包；`GlobalExceptionHandler` 已有错误体 `{status, code, message, fields?}`（code 现为 `VALIDATION_FAILED` 等旧命名）；`spring-boot-starter-test` 已在 pom（test scope）。schema.sql 仅 demo_kp 表。
- **前端**：Vue 3 + Pinia（main.ts 已 createPinia）+ Element Plus；`request.ts` 为 axios 实例（baseURL `/api`，仅错误透传拦截器，无 Authorization 头）；AuthView / StaffLoginView / AccountsView 均为占位空页；AdminLayout 菜单注册表已带 `roles` 字段但未接线过滤。
- **约束**：不引 Spring Security / 向量库等组件（CLAUDE.md）；不写无"为什么"的注释；构造器注入；账号双表双入口是领域不变量 #8。

## Proposed Changes

### A. 后端 · common 基建（新建 `src/main/java/com/zhiji/common/`）

| 文件 | 内容 |
|---|---|
| `common/exception/BizException.java` | 业务异常（`code`、`status`、`message`、`fields`），service 层抛出 |
| `config/GlobalExceptionHandler.java`（改） | 增加 `@ExceptionHandler(BizException.class)`；既有 4 个 code 对齐新分段命名：`VALIDATION_FAILED→COMMON_VALIDATION`、`DUPLICATE_KEY→COMMON_CONFLICT`、`CHECK_VIOLATION→COMMON_CHECK_VIOLATION`、`FOREIGN_KEY_VIOLATION→COMMON_FOREIGN_KEY`、`NOT_NULL_VIOLATION→COMMON_NOT_NULL`、`CONSTRAINT_VIOLATION→COMMON_CONSTRAINT`（前端仅展示 message，无 code 依赖） |
| `common/constant/ConstantItem.java` | 接口：`getCode()` / `getName()` |
| `common/constant/ConstantGroup.java` | 注解：`@ConstantGroup("staff_role")`，标注枚举类 |
| `common/constant/ConstantRegistry.java` | 启动扫描（`ContextRefreshedEvent` 中扫 `com.zhiji.**` 枚举类反射读注解）→ 内存注册表；`items(group)` / `exists(group)` |
| `common/constant/ConstantController.java` | `GET /api/constants`（全量 Map）、`GET /api/constants/{group}`（单组，不存在返回 404 `COMMON_NOT_FOUND`）；公开接口 |
| `common/security/JwtSupport.java` | 自研 HMAC-SHA256 三段式 token（base64url(header).base64url(payload).base64url(HMAC)），payload：`{typ: staff|customer, uid, role, exp}`；`issue()/parse()`；密钥读配置 `zhiji.auth.jwt-secret` |
| `common/security/PasswordHasher.java` | PBKDF2WithHmacSHA256（JDK 内置，600_000 迭代，16B 随机盐），自描述格式 `pbkdf2-sha256$iter$salt$hash`；`hash(raw)` / `verify(raw, stored)` |
| `common/security/AuthContext.java` | ThreadLocal 当前操作人：`staffId()/customerId()/staffRole()`，`clear()` 由拦截器 afterCompletion 调用 |
| `common/security/AuthInterceptor.java` | 照基础架构PRD 3.3 权限矩阵拦截：解析 token（无效 → 401 `AUTH_TOKEN_INVALID`）、每请求查对应表 status（disabled → 401 `AUTH_TOKEN_INVALID`，即时失效）、注入 AuthContext、admin-only 路径校验角色（不足 → 403 `AUTH_FORBIDDEN`） |
| `config/WebMvcAuthConfig.java`（改/新） | 注册 AuthInterceptor：excludeName 公开白名单（`/api/auth/**`、`/api/constants**`、`/api/share/**`、`/demo/**`、`/error`）；矩阵按最长前缀：`/api/admin/accounts/**`、`/api/admin/settings/**` → admin；`/api/admin/**` → staff；其余 `/api/**` → customer |
| `common/security/CaptchaService.java` | 自研 SVG 图形验证码：4 位随机字符、内存 Map（5 分钟过期、一次性消费）、`generate()` 返回 svg + id、`verify(id, code)`；不引第三方依赖 |

### B. 后端 · account 模块（新建 `src/main/java/com/zhiji/account/`）

| 文件 | 内容 |
|---|---|
| `account/domain/StaffAccount.java` | `@TableName("sys_staff")`：id、username、passwordHash、role、status、createdAt、lastLoginAt |
| `account/domain/CustomerAccount.java` | `@TableName("sys_customer")`：id、username、passwordHash、status、registeredAt、lastLoginAt |
| `account/domain/StaffRole.java` | 枚举 admin/editor，实现 ConstantItem，标 `@ConstantGroup("staff_role")` |
| `account/domain/AccountStatus.java` | 枚举 active/disabled，实现 ConstantItem，标 `@ConstantGroup("account_status")` |
| `account/repo/StaffAccountMapper.java`、`account/repo/CustomerAccountMapper.java` | MyBatis-Plus BaseMapper |
| `account/service/TokenService.java` | 签发与解析（包装 JwtSupport + 账号状态校验查询） |
| `account/service/StaffAuthService.java` | 后台登录（校验口令 / 状态，更新 last_login_at，签发 staff token）、me（用户名 + 角色） |
| `account/service/CustomerAuthService.java` | 注册（验证码校验、用户名唯一、创建账号）、登录、me |
| `account/service/AccountAdminService.java` | 后台人员列表（按注册时间倒序）、新增（初始口令）、停用 / 启用（停用即登录被拒）、口令重置；全部仅 admin |
| `account/api/CustomerAuthController.java` | `POST /api/auth/customer/register`、`POST /api/auth/customer/login`、`GET /api/auth/customer/captcha`（SVG）、`GET /api/auth/customer/me` |
| `account/api/StaffAuthController.java` | `POST /api/auth/staff/login`、`GET /api/auth/staff/me` |
| `account/api/AccountAdminController.java` | `GET /api/admin/accounts`、`POST /api/admin/accounts`、`POST /api/admin/accounts/{id}/status`、`POST /api/admin/accounts/{id}/reset-password` |
| `config/AdminBootstrap.java`（新） | `ApplicationRunner`：sys_staff 为空时创建 `admin / admin123`（role=admin），WARN 日志提示修改初始口令 |
| `src/main/resources/db/schema.sql`（追加） | `sys_staff`（username UNIQUE、password_hash、role CHECK(admin/editor)、status CHECK(active/disabled)、created_at、last_login_at）+ `sys_customer`（username UNIQUE、password_hash、status、registered_at、last_login_at），均 `CREATE TABLE IF NOT EXISTS`；demo 表保留（T4 随 kb 删除） |
| `src/main/resources/application.yml`（追加） | `zhiji.auth.jwt-secret`（默认开发值，生产必改）、`zhiji.auth.token-ttl-days: 7`、`zhiji.auth.captcha-ttl-minutes: 5` |

### C. 测试（新建 `src/test/java/com/zhiji/` + `src/test/resources/application-test.yml`）

- `application-test.yml`：`zhiji.data-dir` 指向临时目录、`zhiji.captcha.enabled: false`（验证码可关闭，测试免取码）、测试专用 jwt-secret；
- `account/AuthFlowTest`（MockMvc）：客户注册 → 登录 → me 全流程；重复用户名 409；口令错误 401 `AUTH_LOGIN_FAILED`；
- `account/StaffAuthTest`：admin 登录 → 建账号 → editor 登录 → editor 访问 accounts 接口 403 → admin 停用 editor → editor 请求 401（即时失效）；
- `account/InterceptorScopeTest`：客户 token 访问 `/api/admin/**` 401、员工 token 访问 `/api/auth/customer/me` 401（双会话互不通用，D18 硬验收）；
- `common/ConstantRegistryTest`：/api/constants 含 staff_role 与 account_status 两组。

### D. 前端（接线既有占位页）

| 文件 | 内容 |
|---|---|
| `api/auth.ts`（新） | 客户注册/登录/captcha/me、后台登录/me |
| `api/account.ts`（新） | 后台人员列表 / 新增 / 停用启用 / 重置口令 |
| `stores/auth.ts`（新） | 双 token（localStorage `zj_staff_token` / `zj_customer_token`）+ 当前用户信息 + login/logout/clear |
| `stores/constants.ts`（新） | 启动拉 `/api/constants`；`name(group, code)` / `items(group)` |
| `api/request.ts`（改） | 请求拦截器按 URL 选 token（`/api/admin/**` → staff，其余非公开 → customer）；响应 401 → 清对应会话跳登录（admin 接口 → /admin/login，否则 /login）；403 → ElMessage 提示 |
| `views/auth/AuthView.vue`（改） | 登录 / 注册双模式表单（用户名 / 口令 / SVG 验证码 `<img>`），成功后跳 `/map` |
| `views/auth/StaffLoginView.vue`（改） | 后台登录表单（无验证码），成功跳 `/admin/dashboard` |
| `views/admin/AccountsView.vue`（改） | 后台人员表格（用户名/角色/创建时间/最近登录/状态）+ 新增对话框 + 停用/启用 + 重置口令 |
| `layouts/AdminLayout.vue`（改） | 菜单注册表按 auth store 的 staffRole 过滤（无 roles 字段的组不过滤；非 admin 隐藏"系统"组） |
| `router/index.ts`（改） | 全局 beforeEach：`/admin/**`（除 /admin/login）校验 staff 会话，`/learn/**` 与 `/` 校验客户会话，未登录重定向对应登录页 |

### E. 文档收尾（实现验收后）

- [开发计划](d:\ideaProject\zhiji\docs\开发计划.md) T1 标 ✅（附验收记录）；
- [CLAUDE.md](d:\ideaProject\zhiji\CLAUDE.md) 认证行改"T1 已实现"（防手贱提示改为"新接口必须先在基础架构PRD 权限矩阵登记"）；
- [README](d:\ideaProject\zhiji\README.md) 当前进度：T1 移入已完成。

## Assumptions & Decisions

| # | 决策 | 理由 |
|---|---|---|
| D-1 | 口令哈希用 PBKDF2WithHmacSHA256（JDK 内置，600k 迭代），不引 BCrypt 库 | 遵循"刻意不引组件"；JDK 内置标准算法，自描述存储格式可平滑升级参数 |
| D-2 | token 自研 HMAC-SHA256 三段式（JWT 兼容格式），不引 jjwt | 约 50 行工具类，避免为一个签名引依赖；密钥可配置 |
| D-3 | 验证码自研 SVG（4 位字符，内存 Map，5 分钟一次性） | 不引第三方验证码库；支持 `zhiji.captcha.enabled=false` 关闭（测试用） |
| D-4 | 首个管理员自举：启动时 sys_staff 为空则创建 `admin / admin123`（WARN 日志提示修改） | 否则没有第一个能登录后台的账号；单机小规模最简方案 |
| D-5 | 注册暂不创建默认简历档案 | profile 模块（T10）未建，user_profile 表不存在；T1 注册仅建账号，登录后进 /profile 占位页；"注册即建默认档案"在 T10 落地（基础架构PRD 承诺顺延至该任务） |
| D-6 | 双 token 分开存（localStorage 两个 key），request.ts 按 URL 前缀选 token | 双会话互不通用的前端侧实现；互不覆盖 |
| D-7 | 会话校验 = JWT 签名 + 每请求查账号 status | 不加会话表即满足"停用即时失效"（D18） |
| D-8 | staff 登录无验证码（仅客户注册/登录有） | 后台入口不公开引流（基础架构PRD 既有口径），加强初始口令线下交付 |

## Verification

1. `mvn test` 全绿（新增 4 个测试类）；
2. `mvn clean compile` + `cd frontend && npm run build` 全绿；
3. 冒烟清单（= B端管理台PRD 十四-4）：
   - 启动后日志出现 admin 自举 WARN；admin/admin123 登录后台成功；
   - 客户注册（验证码）→ 登录 → 进 /map；未登录访问 /map 被重定向 /login；
   - admin 在账号管理页创建 editor → editor 从 /admin/login 登录 → "系统"组菜单隐藏 → 访问 /api/admin/accounts 返回 403；
   - 客户 token 直接请求 /api/admin/accounts 被拒（401）；员工 token 请求客户接口被拒；
   - 停用 editor → editor 下一次请求即 401；
   - GET /api/constants 返回 staff_role / account_status 两组。
4. 文档收尾（E 节三项）完成。
