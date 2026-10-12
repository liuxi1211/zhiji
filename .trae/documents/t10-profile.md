# T10 个人档案模块（核心模块定义 4.1）· 实施计划

## Summary

实现 C 端第一个业务模块「个人档案」（开发计划 T10）：新建 `com.zhiji.profile` 包 + 三张 `prf_*` 表 + C 端 `/api/profile/**` 接口 + `/profile` 页面真实实现。交付能力：

1. **档案管理**：每用户最多 3 份简历档案，进度隔离；注册即创建默认档案直接进建档（兑现核心模块定义 6.3 与 T1 决策 D-5 的预告）；档案切换（当前档案标记，供后续学习路径/复习/模拟考消费）。
2. **建档流程**：粘贴简历全文 + 结构化补齐表单（年限/目标岗位/JD/技术栈/备考周期/每日可投入时间）→ AI 起草技能条（`profile_draft` purpose，锚点必须真实存在于题库，对不上即拒绝）→ 逐条拍板（改措辞/增删锚点/确认/删除/手动加条）→ 定稿。
3. **画像状态机**：draft → confirmed → finalized；存在未确认技能条不得定稿；未定稿不参与学习路径生成（状态字段一期就位，消费方在 T11+）。
4. **项目素材**：建档时沉淀项目素材（背景/数字/冲突/结果），为叙事练习与模拟考追问铺数据。
5. **年限→职级缺省推测**：0-2 年→初级、3-5 年→中级、6-8 年→高级、≥9 年→资深；专家档仅手选（映射为暂定缺省，标定按核心模块定义第八节后置）。

验收 = `mvn test` 全绿 + `mvn clean compile` + `cd frontend && npm run build` + 冒烟清单（本文 Verification 节）全过。

## 模块选择依据

按核心模块定义第八节建设顺序（知识库管理 ✅ → 判定引擎 ✅ → **个人档案** → 学习路径与练习 → …），B 端已核对完成（2026-10-11 会话结论），个人档案是 C 端依赖最轻的第一站：只依赖已有的 `sys_customer`（注册登录已通）、`kb_kp`（锚点校验与候选搜索）、LLM 网关（`PROFILE_DRAFT` purpose 已预留）。它是后续所有 C 端模块的"个人上下文锚点"（核心模块定义 4.1）。

## Current State Analysis

### 已有（直接复用）

| 能力 | 位置 | 用法 |
|---|---|---|
| 客户会话 | [AuthContext.java](file:///d:/ideaProject/zhiji/src/main/java/com/zhiji/common/security/AuthContext.java) `customerId()`；[AuthInterceptor.java](file:///d:/ideaProject/zhiji/src/main/java/com/zhiji/account/security/AuthInterceptor.java) 第 6 条规则：非 admin 的 `/api/**` 一律客户会话 | 新 Controller 挂 `/api/profile` 即自动受保护，零配置 |
| LLM 网关 | [LlmClient.java](file:///d:/ideaProject/zhiji/src/main/java/com/zhiji/llm/api/LlmClient.java) `completeJson`；`LlmRequest.user(purpose, profileId, prompt)`（profileId=档案 id 作计量键） | `LlmPurpose.PROFILE_DRAFT`（Side.C，LimitKey.NONE）已预留，限额走"档案日合计+全站熔断"兜底 |
| Prompt 约定 | [PromptTemplate.java](file:///d:/ideaProject/zhiji/src/main/java/com/zhiji/llm/api/PromptTemplate.java) `{{var}}` 渲染；模板放 `src/main/resources/prompts/*.txt` | 新增 `profile_claims.txt`，readTemplate/render 照抄 SourceAiService 私有方法 |
| JSON 列先例 | [KbKp.java](file:///d:/ideaProject/zhiji/src/main/java/com/zhiji/kb/domain/KbKp.java)：JSON 列为 String，service 层 `tools.jackson` JsonMapper 序列化 | `prf_claim.anchor_ids` 照此办理 |
| 分页/异常 | [PageResult.java](file:///d:/ideaProject/zhiji/src/main/java/com/zhiji/common/web/PageResult.java)、[BizException.java](file:///d:/ideaProject/zhiji/src/main/java/com/zhiji/common/exception/BizException.java)（错误码分段 `PRF_*`） | 档案列表不分页（≤3 条），异常用静态工厂 |
| 知识点检索 | [KbContentService.java](file:///d:/ideaProject/zhiji/src/main/java/com/zhiji/kb/service/KbContentService.java) `search/searchPage`（LIKE 标题/概述/正文） | C 端锚点候选搜索复用其内部逻辑，新增轻量 C 端入口 |
| 测试基建 | [SourceFlowTest.java](file:///d:/ideaProject/zhiji/src/test/java/com/zhiji/source/SourceFlowTest.java) 模式：`@SpringBootTest + @ActiveProfiles("test") + MockMvc + @Primary StubLlmTransport`；test profile 验证码关闭，register 即登录态工厂 | 新建 ProfileFlowTest 照抄骨架 |
| 前端骨架 | [AuthView.vue](file:///d:/ideaProject/zhiji/frontend/src/views/auth/AuthView.vue)（el-form label-position=top + reactive 表单 + ElMessage 错误）、[LearnLayout.vue](file:///d:/ideaProject/zhiji/frontend/src/layouts/LearnLayout.vue)（移动优先、920px 单列、`.zj-page-head`、不上卡片）、constants store（`items('target_level')` 下拉） | ProfileView 整页替换 16 行占位 |
| 路由守卫 | [router/index.ts](file:///d:/ideaProject/zhiji/frontend/src/router/index.ts) learn 树要求 `isCustomerLoggedIn` | `/profile` 已在保护内 |

### 缺口

- 后端无任何 profile 表与接口；`CustomerAuthService.register` 不建默认档案（T1 决策 D-5 明确顺延至本任务）。
- 前端 `types.ts` 零 Profile 类型；`ProfileView.vue` 为占位；无档案 store。
- 无 C 端知识点候选搜索入口（现有 `GET /api/admin/kb/search` 是员工会话）。
- 无目标职级五档常量组（`kb_kp.level_rules`/`judge_session.level` 内是裸字符串 `junior|mid|senior|expert|master`，C 端下拉需要常量组下发）。

## 领域决策（已拍板，实现不再选择）

| # | 决策 | 依据 |
|---|---|---|
| R1 | 简历输入一期仅**粘贴文本**（≤20000 字符），上传 docx/pdf 解析后置二期 | 文档解析属纯技术底座（核心模块定义第二节说明）；遵循"刻意不引组件" |
| R2 | 补齐信息用**结构化表单**（年限/岗位/JD/技术栈/备考周期/每日分钟数），AI 用"简历文本+表单"一次性起草技能条；对话式多轮补齐后置二期 | 对话式需会话状态管理+流式，一期成本不可控 |
| R3 | 年限→职级**固定缺省映射**：0-2→junior、3-5→mid、6-8→senior、≥9→expert；master 仅手选；用户随时可改 | 核心模块定义 4.1"没明确说按年限推测缺省值"；映射标定在一期后置项 |
| R4 | 项目素材一期**建表+基础 CRUD**（在建档页内维护，不做 AI 起草） | 消费方（叙事练习/模拟考追问）在 T11+，但表归建档（4.1），先立结构避免二期返工 |
| R5 | 技能条 = 一段措辞 + **锚定知识点集合**（多选 kp）；AI 起草产物**不落库**，回前端草稿区逐条采纳；锚点不存在的条目整体丢弃（"对不上的直接拒绝"） | 核心模块定义 4.1 边界 + 6.4 AI 边界 |
| R6 | 状态机自动推进：全部 claim confirmed → profile 自动 confirmed；新增/回退 unconfirmed claim → 自动回 draft；finalize 需 status=confirmed 且 targetLevel 非空；unfinalize 回 confirmed（定稿后仍可编辑主张） | 4.1 状态机"存在未确认条目时不得定稿" |
| R7 | 当前档案用 `prf_profile.is_active` 部分唯一索引（`WHERE is_active=1`）保证每客户至多一个，切换在事务内先清后设；不动 `sys_customer` 表 | 避免改既有表（SQLite 加列迁移成本）；档案切换即切换全部上下文的落点 |
| R8 | 目标职级五档枚举 `junior/mid/senior/expert/master` 与 `kb_kp.level_rules`、`judge_session.level` 的 CHECK 取值完全对齐 | 6.5 命名表"目标职级（Target Level）五档" |
| R9 | C 端知识点候选搜索挂 `/api/kp/search`（kb 包新 Controller，轻量字段，仅 available），同一能力后续服务复盘录题（5.2"知识点模糊搜索…C 端侧消费同一能力"） | 归属知识库管理，不在 profile 包重复实现 |
| R10 | AI 起草的锚点候选 = 后端先按简历文本/技术栈关键词 LIKE 搜 available kp 取 top 50，连同措辞要求喂给 LLM 从中挑选；代码二次校验 | 6.4"AI 只做选择与组织…不允许模型编造考点" |

## Proposed Changes

### A. 数据库（`src/main/resources/db/schema.sql` 追加）

```sql
-- T10 个人档案模块（核心模块定义 4.1）：简历档案/技能条/项目素材。
-- 状态机 draft→confirmed→finalized（存在未确认技能条不得定稿）；未定稿不参与学习路径生成（T11+ 消费）。
CREATE TABLE IF NOT EXISTS prf_profile (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_id    INTEGER NOT NULL REFERENCES sys_customer (id),
    name           TEXT    NOT NULL,                    -- 档案名（如"Java 后端"）
    resume_text    TEXT    NOT NULL DEFAULT '',         -- 简历全文（粘贴文本，一期不上传文件）
    years          INTEGER,                             -- 工作年限（补齐表单，职级缺省推测依据）
    position       TEXT    NOT NULL DEFAULT '',         -- 目标岗位
    jd_text        TEXT    NOT NULL DEFAULT '',         -- 目标岗位 JD
    tech_stack     TEXT    NOT NULL DEFAULT '',         -- 技术栈（自由文本）
    target_level   TEXT    CHECK (target_level IS NULL OR target_level IN ('junior','mid','senior','expert','master')),
    plan_weeks     INTEGER,                             -- 备考周期（周）
    daily_minutes  INTEGER,                             -- 每日可投入时间（分钟）
    status         TEXT    NOT NULL DEFAULT 'draft'
                   CHECK (status IN ('draft','confirmed','finalized')),
    is_active      INTEGER NOT NULL DEFAULT 0,          -- 当前档案标记，部分唯一索引保证每客户至多一个
    finalized_at   TEXT,
    created_at     TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at     TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_prf_profile_customer ON prf_profile (customer_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_prf_profile_active ON prf_profile (customer_id) WHERE is_active = 1;

-- 技能条（Skill Claim）：一段简历措辞 + 锚定知识点集合；锚点必须真实存在且 available（代码校验，红线：AI 不编造考点）。
CREATE TABLE IF NOT EXISTS prf_claim (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id   INTEGER NOT NULL REFERENCES prf_profile (id),
    wording      TEXT    NOT NULL,                    -- 简历措辞
    anchor_ids   TEXT    NOT NULL DEFAULT '[]',       -- 锚定知识点 id JSON 数组 [kpId]（String 列，service 层序列化，先例 KbKp）
    source       TEXT    NOT NULL DEFAULT 'ai' CHECK (source IN ('ai','manual')),
    status       TEXT    NOT NULL DEFAULT 'unconfirmed' CHECK (status IN ('unconfirmed','confirmed')),
    sort_order   INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_prf_claim_profile ON prf_claim (profile_id, sort_order);

-- 项目素材（Case）：建档沉淀的项目细节（背景/数字/冲突/结果），供叙事练习与模拟考追问消费（T11+）。
CREATE TABLE IF NOT EXISTS prf_case (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id   INTEGER NOT NULL REFERENCES prf_profile (id),
    project_name TEXT    NOT NULL,
    background   TEXT    NOT NULL DEFAULT '',
    metrics      TEXT    NOT NULL DEFAULT '',
    conflict     TEXT    NOT NULL DEFAULT '',
    result       TEXT    NOT NULL DEFAULT '',
    created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_prf_case_profile ON prf_case (profile_id);
```

均为 `CREATE TABLE IF NOT EXISTS` 纯增量，不动既有表与数据。

### B. 后端（新建 `src/main/java/com/zhiji/profile/`，四层对齐 source/kb 包结构）

| 文件 | 内容 |
|---|---|
| `domain/PrfProfile.java` | `@TableName("prf_profile")`，`@TableId(type = IdType.AUTO)`；字段对齐 A 节 |
| `domain/PrfClaim.java` | `@TableName("prf_claim")`；`anchorIds` 为 String（JSON） |
| `domain/PrfCase.java` | `@TableName("prf_case")` |
| `domain/ProfileStatus.java` | 枚举 draft/confirmed/finalized，实现 ConstantItem，`@ConstantGroup("profile_status")` |
| `domain/ClaimStatus.java` | 枚举 unconfirmed/confirmed，`@ConstantGroup("claim_status")` |
| `domain/TargetLevel.java` | 枚举 junior/mid/senior/expert/master（中文名 初级/中级/高级/资深/专家），`@ConstantGroup("target_level")`；含静态 `suggestByYears(int)` 实现固定映射 R3 |
| `repo/PrfProfileMapper.java`、`repo/PrfClaimMapper.java`、`repo/PrfCaseMapper.java` | MyBatis-Plus BaseMapper（`@MapperScan("com.zhiji.**.repo")` 已覆盖） |
| `service/ProfileService.java` | ①`listByCustomer`（含每档案 claim 计数）；②`create`（校验 ≤3 → 409 `PRF_PROFILE_LIMIT`；首份档案自动 is_active=1）；③`getOwned(id)`（校验归属，越权一律 404 `COMMON_NOT_FOUND` 防枚举）；④`update`（name/resumeText/years/position/jdText/techStack/targetLevel/planWeeks/dailyMinutes；resumeText ≤20000）；⑤`activate`（@Transactional：清旧 active→设新）；⑥`finalize`（校验 status=confirmed 且 targetLevel 非空 → finalized+finalized_at）；⑦`unfinalize`（→confirmed）；⑧`createDefaultProfile(customerId)`（注册调用，name="我的档案"，active=1）；⑨`recomputeStatus(profileId)`：claim 变更后重算档案状态（有 unconfirmed→draft，否则→confirmed；finalized 态下出现变更自动降级），供 ClaimService 调 |
| `service/ClaimService.java` | ①`listByProfile`（视图带 anchors:[{id,title}]，锚点 title 实时反查 kb_kp）；②`add`（wording ≤200 字、anchorIds 逐个校验为 available kp（KbKpMapper 批查+status 过滤），非法锚点整体拒绝 400 `PRF_ANCHOR_INVALID`；source 按调用方传 ai/manual；每档案 ≤30 条）；③`update`（措辞/锚点改后 status 回 unconfirmed）；④`confirm`（拍板）；⑤`delete`；以上全部 @Transactional 并在末尾调 `profileService.recomputeStatus` |
| `service/CaseService.java` | 项目素材 CRUD（每档案 ≤20 条），同样校验档案归属 |
| `service/ProfileAiService.java` | `draftClaims(profile)`：①输入不足（resumeText 与 position/techStack 全空）→ 400 `COMMON_VALIDATION`；②候选 kp：按 resumeText+techStack 关键词调 KbContentService 搜 available top 50（id/title/summary）；③渲染 `prompts/profile_claims.txt`（简历/岗位/技术栈/候选清单编号列表），`llmClient.completeJson(LlmRequest.user(LlmPurpose.PROFILE_DRAFT, profile.getId(), prompt).build(), DraftResp.class)`；④产物逐条校验锚点 ∈ 候选且 available，含非法锚点或零锚点的条目**直接丢弃**（R10，日志留痕）；⑤返回建议列表（不落库），LLM 失败/超限异常向上透传由 Controller 统一处理 |
| `api/ProfileController.java` | `@RestController @RequestMapping("/api/profile") @Validated`，内嵌 record Req/View（View 定义可放 Service，对齐 SourceService 先例）。端点：`GET ``/`（我的档案列表）、`POST ``/`（创建）、`GET /{id}`（详情=档案+claims+cases）、`PUT /{id}`、`POST /{id}/activate`、`POST /{id}/finalize`、`POST /{id}/unfinalize`、`POST /{id}/ai/claims`（AI 起草，长超时提示）、`POST /{id}/claims`、`PUT /{id}/claims/{claimId}`、`POST /{id}/claims/{claimId}/confirm`、`DELETE /{id}/claims/{claimId}`、`POST /{id}/cases`、`PUT /{id}/cases/{caseId}`、`DELETE /{id}/cases/{caseId}`、`GET /level-suggestion?years=`（返回推测职级，供表单预填）。全部从 `AuthContext.customerId()` 取当前客户（null → `BizException.unauthorizedToken()`） |
| `src/main/resources/prompts/profile_claims.txt` | 起草 prompt：角色=简历教练；输入={{resumeText}}/{{position}}/{{jdText}}/{{techStack}}/{{years}}+候选知识点清单（`{{kpCandidates}}`，每行 `id. 题面 — 概述`）；规则：输出 JSON `{"claims":[{"wording":"第一人称简历句式","anchorKpIds":[候选清单中的 id]}]}`；措辞覆盖技术栈主干与项目亮点、5-12 条、锚点只能从候选清单选、宁缺毋滥 |

**kb 包新增（C 端检索入口）**：

| 文件 | 内容 |
|---|---|
| `kb/api/KbCustomerController.java` | `GET /api/kp/search?q=&limit=`（客户会话，拦截器第 6 条自动覆盖）：调 `KbContentService` 现有 search 逻辑，**仅 available kp**，返回轻量视图 `{id, title, summary, difficulty, domainCode, topicName}`（不含评分要点/追问链/职级规则），limit 默认 10 上限 20。R9：同一能力后续服务复盘录题 |

**llm 包微调（仅核对，预计零改动）**：`LlmConfigInitializer`/`LlmConfigService.defaultRouteConfig()` 需确认已含 `profile_draft` 路由条目（LlmPurpose 枚举已有该值；实现时验证，缺则补一条默认路由）。

### C. 前端（`frontend/`）

| 文件 | 内容 |
|---|---|
| `src/api/types.ts`（改） | 追加契约类型（字符串字面量联合对齐 CHECK）：`ProfileStatus`、`ClaimStatus`、`ClaimSource`、`TargetLevel`、`ProfileListItem`（id/name/status/targetLevel/isActive/claimCount/updatedAt）、`ProfileView`（全字段）、`ClaimView`（id/wording/anchorIds/anchors/source/status/sortOrder）、`CaseView`、`KpCandidate`、`ClaimDraft`（AI 草稿：wording/anchorKpIds/anchors） |
| `src/api/profile.ts`（新） | 具名 async 函数 + `.data` 剥壳（对齐 auth.ts）：listProfiles/createProfile/getProfile/updateProfile/activateProfile/finalizeProfile/unfinalizeProfile/draftClaims(120s 超时)/addClaim/updateClaim/confirmClaim/deleteClaim/addCase/updateCase/deleteCase/getLevelSuggestion |
| `src/stores/profile.ts`（新） | pinia：档案列表缓存 + `activeProfile`（localStorage `zj_active_profile` 持久化 id），`setActive(id)` 同步调后端 activate。后续学习路径/复习/模拟考统一从这里取当前档案 |
| `src/views/learn/ProfileView.vue`（重写，替换 16 行占位） | 移动优先单列、`.zj-page-head` 页头、不用 el-card（留白+标尺线分组，只用 `--zj-*` 令牌）。三态：①无档案 → 创建第一份档案引导；②档案列表态 → 档案卡片式列表（名称/状态/职级/技能条数/当前档案标记）+ 新建 + 点击进入详情 + 切换当前档案；③档案详情态 → 顶部档案切换器 + 三段：**备考计划**（el-form：档案名/粘贴简历/年限/目标岗位/JD/技术栈/目标职级下拉（constants `target_level`，未选时按年限建议预填 `level-suggestion`）/备考周期/每日时间）、**技能条**（清单逐条拍板 + "AI 起草技能条"按钮 + 手动加条 + 定稿/撤销定稿按钮与状态提示）、**项目素材**（列表+增删改）。表单提交对齐 AuthView 模式（reactive + loading + try/catch + `ElMessage.error(err.message)`） |
| `src/components/profile/ClaimCard.vue`（新） | 单条技能条卡片：措辞展示/行内编辑、锚点 chips（增删触发 KpAnchorPicker）、确认/删除按钮、来源与状态标识；emit 更新事件由父页调 API |
| `src/components/profile/KpAnchorPicker.vue`（新） | 知识点锚点选择弹层：输入关键词 → `searchKpCandidates` → 候选列表（题面+领域+难度）勾选，选中回填；防抖 300ms |
| `src/components/profile/CaseEditor.vue`（新） | 项目素材编辑表单（项目名/背景/数字/冲突/结果四段 textarea） |
| `src/views/auth/AuthView.vue`（改，一行注释） | 类注释"默认简历档案随建档模块（T10）创建"更新为已落地（注册即建默认档案） |

### D. account 包微调（注册即建默认档案）

| 文件 | 内容 |
|---|---|
| `account/service/CustomerAuthService.java`（改） | `register`：①加 `@Transactional`（建账号+建默认档案两写）；②`CustomerAuthResp` 增加 `customerId` 字段返回 |
| `account/api/CustomerAuthController.java`（改） | 类注释更新（默认档案已随 T10 落地）；register 返回体透传 customerId |
| `account/api/CustomerAuthController.java` 依赖方向说明 | Controller 层调 `ProfileService.createDefaultProfile(customerId)`（api 层编排，避免 account.service → profile.service 的底座反向依赖；profile 不回依赖 account，无环） |

**前端 auth.ts/types.ts**：`CustomerAuthResp` 类型加 `customerId` 字段（AuthView 无需消费，仅对齐契约）。

### E. 测试（新建 `src/test/java/com/zhiji/profile/ProfileFlowTest.java`）

骨架照抄 SourceFlowTest：`@SpringBootTest + @ActiveProfiles("test")` + MockMvc（webAppContextSetup）+ 内嵌 `@TestConfiguration` 提供 `@Primary StubLlmTransport`；setUp 里 `stub.reset()` + 重置路由/限额/用量 + register 拿客户 token（test profile 验证码关闭，用户名 `System.nanoTime()` 保证唯一）。

用例清单：

1. **注册即建默认档案**：register → `GET /api/profile` 返回 1 条 active 且 status=draft；
2. **档案上限**：连建 3 份成功，第 4 份 409 `PRF_PROFILE_LIMIT`；
3. **切换当前档案**：activate 第二份 → 列表仅第二份 is_active=1（部分唯一索引兜底）；
4. **越权防护**：注册第二个客户，用其 token 访问第一个客户的档案 → 404；
5. **AI 起草**：先造 available kb_kp 种子（直接 Mapper 插入），`stub.respond(合法 JSON 含 1 条合法锚点 + 1 条非法锚点)` → 调 `POST /{id}/ai/claims` → 非法锚点条目被丢弃、合法条目在返回草稿中；`stub.fail` → 错误透传；
6. **拍板与状态机**：采纳草稿（add）→ 全部 confirm → profile 自动 confirmed；再 add 一条 unconfirmed → 自动回 draft；finalize 在有 unconfirmed 时 400，补 confirm 后 finalize 成功且 finalized_at 非空；unfinalize 回 confirmed；
7. **职级缺省**：`GET /api/profile/level-suggestion?years=4` → mid；years=10 → expert；
8. **C 端 kp 搜索**：`GET /api/kp/search?q=` 只返回 available（插一条 pending 与一条 available 验证）；
9. **项目素材**：增改删 + 上限校验；
10. **参数校验**：resumeText 超长 400、wording 空白 400。

## Assumptions & Decisions 记录

| # | 决策 | 理由 |
|---|---|---|
| D-1 | 四个产品决策按推荐项锁定：R1 粘贴文本 / R2 结构化表单 / R3 固定映射 / R4 项目素材一期建表（用户在确认提问时跳过，采纳推荐项；如需推翻只需改对应小节） | 与用户跳过确认后的默认推进约定 |
| D-2 | 复用已预留的 `LlmPurpose.PROFILE_DRAFT`（LimitKey.NONE），不新增 purpose；限额由"档案日合计+全站熔断"兜底 | 语义完全吻合（建档起草）；避免动 LlmLimits/LlmRateGuard |
| D-3 | 错误码新分段 `PRF_*`：`PRF_PROFILE_LIMIT`（上限 409）、`PRF_ANCHOR_INVALID`（锚点非法 400）、`PRF_FINALIZE_BLOCKED`（未确认条目存在 400）、`PRF_FINALIZE_NEED_LEVEL`（缺目标职级 400） | 对齐 AUTH_*/KB_*/SOURCE_* 分段惯例 |
| D-4 | AI 起草产物不落库，采纳才建 claim 行；每档案技能条 ≤30、项目素材 ≤20 | 防滥用 + 与 AI 限额三道闸（验证码/限流/熔断）呼应 |
| D-5 | 锚点校验双保险：prompt 限定只能选候选清单 + 代码逐 id 校验 available；非法即丢弃该条目 | 6.4"不允许模型编造考点" |
| D-6 | `ux_prf_profile_active` 部分唯一索引（SQLite WHERE 索引）数据库级保证每客户至多一个当前档案 | 比纯代码校验强；不动 sys_customer 免迁移 |
| D-7 | 状态自动推进/回退集中在 `ProfileService.recomputeStatus`，ClaimService 所有变更后调用 | 状态机规则单点维护，避免散落 |
| D-8 | 越权访问统一 404（不泄露存在性），对齐防枚举惯例 | — |
| D-9 | 依赖方向：account.api → profile.service → kb.service → source.service → llm，单向无环；kb 依赖 source 已有先例（KbAdminController 委托 SourceAiService） | 单体内可接受的分层依赖 |
| D-10 | 本文件即 T10 任务文档（.trae/documents 命名约定 t{编号}-*.md），实现完成后不另写收尾文档；`docs/开发计划.md` 已不存在（2026-10-09 文档收敛），无需回改 | 仓库现状 |

## Verification

1. `mvn test` 全绿（新增 ProfileFlowTest 10 组用例，既有测试不回归）；
2. `mvn clean compile` 通过；
3. `cd frontend && npm run build` 通过（含 vue-tsc 类型检查）；
4. 冒烟清单（需 `config/application-local.properties` 配真实 LLM key）：
   - 注册新客户 → 自动进 /profile → 已存在默认档案（draft、当前档案标记）；
   - 粘贴简历 + 填表单 → "AI 起草技能条"返回草稿（锚点均为真实知识点）→ 逐条改措辞/增删锚点/确认 → 状态自动转 confirmed → 定稿成功；补一条未确认技能条后档案自动退回 draft，此时定稿按钮被拒；
   - 新建第 2、3 份档案并可切换当前档案；第 4 份被拒；
   - 未登录访问 /profile 重定向 /login；员工 token 请求 `/api/profile/**` 被拒（401）；
   - `GET /api/constants` 新增 `profile_status`、`claim_status`、`target_level` 三组；
5. 数据兼容：对已有本地库（含素材/知识点数据）直接启动，`CREATE TABLE IF NOT EXISTS` 纯增量无迁移报错。
