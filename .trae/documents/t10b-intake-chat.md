# T10b 个人档案 · 对话流改造（建档 PRD v2）· 实施记录

## Summary

按《PRD-个人档案》v2 与《PROMPT-技能条起草》对话版，把 T10 的旧交互（结构化表单 + AI 一次性起草 + 编辑器逐条拍板）改造为**对话头脑风暴流**：基础信息表单 → 对话起草/修订（首轮即出完整草案，轮次上限 5）→ 整版采纳（就用这版 = 拍板）→ 定稿核对（占位清零 + 备考计划）→ 简历 Markdown 导出 → 定稿后锚定映射。

## Changes

### 数据库（schema.sql 增量 + ProfileSchemaPatch）

- `prf_claim` 增列：`title`（小标题）、`anchor_status`（unmapped/mapped/pending/zero，对话流下锚点在定稿后映射补齐）；
- 新表 `prf_intake_msg`（建档对话消息：role=user 反馈原文 / assistant=reply+claims_json）；
- 新表 `prf_reference_claim`（参考技能条池：direction/title/content，脱敏打标）；
- `prf_profile` 增列 `good_at`（想突出的/擅长的，对话起草补充事实源）；
- 存量库迁移走 `ProfileSchemaPatch`（PRAGMA 检查 + ALTER，幂等，沿用 SourceSchemaPatch 模式）。

### 后端（profile 包）

- **删除** `ProfileAiService`（旧一次性起草）与 `prompts/profile_claims.txt`；
- **新增** `IntakeChatService`：对话起草/修订/历史/清空；轮次上限 5（`PRF_CHAT_ROUND_LIMIT`）、重复首轮 409（`PRF_CHAT_ALREADY_STARTED`）、条目归一（标题缺省、正文 ≤300、去重）；LLM 走 `PROFILE_DRAFT` 通路（system=intake_system + 参考池，user=intake_first 或对话转录）；
- **新增** `AnchorMapService`：定稿后映射——候选按技能条关键词 LIKE 搜 available kp（上限 60），模型只准从候选选 id 并给置信度，代码逐 id 复核；high→mapped、low→pending、none→zero；已 mapped 不重跑；
- **新增** `ReferencePoolService` + `ReferencePoolBootstrap`：池子渲染（≤80 条注入）与首批种子（51 条 java_backend，脱敏、去「精通」夸大）；
- **改造** `ClaimService`：技能条带小标题；锚点可空（采纳时 unmapped）；`acceptVersion` 整版采纳（替换 ai 来源、采纳即 confirmed、手动条目保留）；编辑不再回退拍板状态；`assertNoUnresolvedPlaceholders`（【…】正则，定稿/导出门槛 `PRF_PLACEHOLDER_UNRESOLVED`）；`renderMarkdown`；
- **改造** `ProfileController`：chat first/revise/history/clear、claims/accept、anchors/map、export 端点；finalize 前置占位核对（归属校验先行防信息泄露）；
- `prf_profile`/UpdateReq/详情视图透传 `goodAt`。

### 前端（frontend）

- `ProfileView.vue` 重写为对话流布局：对话头脑风暴（ChatPanel）→ 技能条（ClaimCard + 锚定映射按钮）→ 定稿与导出（占位清单 + 复制 Markdown）→ 项目素材 → 基础信息（折叠表单）；
- 新组件 `ChatPanel.vue`：历史渲染、首轮 CTA、修订输入（轮次计数）、快捷采纳「就用这版」、清空重聊；占位【】高亮（转义后注入 mark，防注入）；
- `ClaimCard.vue`：小标题编辑、锚定状态徽标（已锚定/待确认/未匹配/未锚定）、占位高亮；
- 基础信息表单：技术栈升级为 chip 编辑（预置点选 + 自由输入回车添加，防清单遗漏——用户验收意见落地）；新增「想突出的/擅长的」；
- types/api 全量对齐新契约（IntakeClaim / IntakeMessageView / ChatTurnView / AnchorStatus）。

### 测试（ProfileFlowTest 重写，13 组用例）

注册建默认档案、3 份上限、切换、越权 404、对话首轮/重复首轮/修订、整版采纳即拍板、占位定稿门槛、导出 Markdown、锚定映射（越界剔除/置信度落状态/已映射不重跑）、轮次上限与清空重聊、手动条状态机（编辑不回退）、职级推测、kp 搜索、素材 CRUD、参数校验。

## Verification（2026-10-12）

1. `mvn clean compile` 通过；
2. `mvn test` 全量通过（含 ProfileFlowTest 13 组）；
3. `cd frontend && npm run build`（含 vue-tsc）通过。

## 决策记录

| # | 决策 | 理由 |
|---|---|---|
| D-1 | 对话轮次口径 = 首轮即第 1 轮，修订最多 4 轮 | PRD「轮次上限 5」含首轮；ROUND n/5 直读 |
| D-2 | 采纳版本由客户端回传（claims/accept），服务端不做反查 | 采纳的是用户确认过的版本（定稿页还会编辑占位），且归属校验在先 |
| D-3 | 采纳只替换 source='ai' 条目 | 手动加条是用户独立劳动，不应被整版采纳清掉 |
| D-4 | 编辑措辞不再回退拍板状态 | 占位填写发生在采纳之后，回退会让用户重走无意义的拍板 |
| D-5 | 锚定映射 = 显式端点 + 前端在定稿后触发 | 同步 LLM 调用不宜藏在 finalize 请求里；总览展示 pending/zero 供确认 |
| D-6 | 锚点校验语义放宽：允许零锚点（unmapped），非空仍逐 id 校验 available | 对话流先出主张后补锚点；红线（不编造考点）不变 |
| D-7 | 存量库 anchor_status 迁移默认 'mapped' | 既有数据都带真实锚点，避免被误判为待映射 |
| D-8 | PROFILE_DRAFT purpose 复用于对话与映射 | 语义同为建档起草，LimitKey.NONE + 档案日合计熔断兜底（对齐 T10 D-2） |
