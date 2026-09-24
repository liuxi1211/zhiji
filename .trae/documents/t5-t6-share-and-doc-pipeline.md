# T5 补完 + T6 文档解析与素材库 实施计划

> 依据：[开发计划](d:/ideaProject/zhiji/docs/开发计划.md) T5/T6 明细、[核心模块定义](d:/ideaProject/zhiji/docs/核心模块定义.md) 3.3/4.2/附录 A、[知识库PRD](d:/ideaProject/zhiji/docs/prd/知识库PRD.md) 7.5/九、[B端管理台PRD](d:/ideaProject/zhiji/docs/prd/B端管理台PRD.md) 六/七、决策清单 D21/D22/D30/D31/D32。

## 一、Summary

按开发计划顺序交付两个任务：

1. **T5 知识卡分享（先补，当前 T5 未实现）**：kb 包新增无鉴权公开只读接口 `GET /api/share/kp/{kpId}`（仅输出 active 知识点公开视图）+ 前端 `/share/:kpId` 公开页接线（翻面看答案、追问链逐层、从零讲、卡图保存）。零 LLM 调用、不新增表。
2. **T6 文档解析与素材库（pipeline 一）**：新建 pipeline 模块（四层）+ docparse 支撑包；建 `kp_source_doc` / `kp_candidate` 两表；md/txt 导入（zip 解包）、解析与标题树还原、LLM 形态识别路由（题库/资料/面经）、三路拆分汇成候选条目、批次与解析状态机、溯源与删除约束；前端 DocView 全量接线（候选池页留占位，研判工作流随 T7）。
3. **随 T6 落地 D32 的 LLM 限额调整**：B 端生产类入口（profileId=0）豁免限额与熔断；全站每日总熔断降级为默认关闭的可选护栏（启用时仅统计 C 端消费类）；设置页补护栏开关。
4. **顺手清理（D30 遗留）**：移除前端 SkillsView 占位页与菜单/路由中的技能库项。

用户已拍板：T5 先行；形态识别用 LLM（新 purpose）；D32 随 T6 落地；候选池页留占位。

## 二、Current State Analysis

### 已具备（探索确认）

- **后端**：account（双表双入口 + 拦截器 `AuthInterceptor`，`/api/share/` 已在白名单 L31）、kb（四层完整，`KpService` / `KpStructureValidator` / `ClusterService`，表 kp_cluster/kp_main/kp_prereq/kp_freq_signal/skill_domain）、llm（`LlmClient` 编排：路由→限额→调用→JSON 重试→计数；`PromptTemplate.render` 极简 `{{var}}` 渲染；`LlmConfigInitializer` 启动自举默认路由与限额）。
- **前端**：Vue3 + `<script setup>` + Element Plus；`api/` 按后端模块组织；`DocView.vue`、`ShareView.vue`、`CandidateView.vue` 均为占位页；菜单注册表在 [AdminLayout.vue](d:/ideaProject/zhiji/frontend/src/layouts/AdminLayout.vue#L38)（含 docs/candidates/knowledge-map/review-flow 行）；路由 [index.ts](d:/ideaProject/zhiji/frontend/src/router/index.ts#L122) 已有 `/share/:kpId` 公共路由；`MarkdownView` 组件可复用。
- **测试**：spring-boot-test + MockMvc + 独立 SQLite 测试库（`@ActiveProfiles("test")`），`KpTest` 是集成测试范例；`StubLlmTransport` 可复用来 stub LLM。
- **T5 现状**：后端无任何 share Controller（Grep 确认）；ShareView 是占位页。

### 需要解决的缺口

| 缺口 | 处理 |
|---|---|
| LLM 路由兼容：sys_config 里已有的 `llm.route` JSON 不含新增 purpose，`target()` 返回 null 会 500 | `LlmConfigInitializer.load()` 后补齐缺失 purpose 的默认路由并写回 |
| 新增 3 个 LLM 入口无枚举 | `LlmPurpose` 加 `DOC_FORM_DETECT` / `QUESTION_GEN` / `INTERVIEW_EXTRACT`；前端 types.ts 同步（常量三处同步：DB 刻意无 CHECK，见 schema L52-54 注释） |
| D32：`LlmRateGuard` 现状对所有调用查全站熔断（[LlmRateGuard.java](d:/ideaProject/zhiji/src/main/java/com/zhiji/llm/service/LlmRateGuard.java#L29)） | B 端（profileId=0）直接放行；全站熔断加 `siteCircuitEnabled` 开关（默认 false），启用时仅统计 `profile_id != 0` 的计数 |
| 限额用户聚合维度（档案→账号） | 依赖 user_profile 表（T10 落表），**本次只预留不实现**（用户已确认） |
| kp_candidate 需指向 T7 的 kp_canonical | DDL 预留 `canonical_id INTEGER` 普通列（不加 REFERENCES，目标表尚不存在），T7 补逻辑约束 |

## 三、Proposed Changes

### 第一部分：T5 知识卡分享

#### T5-1 后端：kb 包公开分享接口

**新建** `src/main/java/com/zhiji/kb/api/ShareController.java`：

- `GET /api/share/kp/{kpId}`，无鉴权（拦截器白名单已覆盖 `/api/share/`，无需改拦截器；权限矩阵已登记 `/api/share/**` 无会话）。
- 委托 `KpService` 新方法 `shareDetail(long id)`：
  - 非 active（draft/reviewed/retired/不存在）→ 抛 `BizException` 404 `KP_NOT_FOUND`（不泄露存在性）。
  - 输出公开视图 record `ShareKpView`：id、title、subType、difficulty、variants、narrativeFramework、followups（全层含 layerKey/dir/text/expect/stopCondition，**不含 levelReq 与 rubric**——目标刻度依赖个人职级，公开页不渲染；评分要点是判定尺子不外露）、samples（三档）、domainName、clusterName、zeroShotMd、referenceSolutionMd（簇共享讲解，按 7.5 第 4 条公开页可见）。
  - **红线**：接口纯只读、零 LLM 调用、不含任何个人数据（KpService 既有 detail 即库侧数据，无个人字段）。

**修改** `KpService.java`：新增 `shareDetail` 方法与 `ShareKpView` record，复用既有 JSON 解析（variants/followups/samples 反序列化逻辑与 `detail` 同源，抽私有方法避免复制）。

#### T5-2 前端：ShareView 接线 + 卡图

**修改** `frontend/src/api/kb.ts`：新增 `getShareKp(kpId)`（公开接口不带 token——`request.ts` 的 `PUBLIC_PREFIXES` 已含 `/share/`，无需改）。

**修改** `frontend/src/api/types.ts`：新增 `ShareKpDetail` 接口。

**重写** `frontend/src/views/share/ShareView.vue`：

- 按 `route.params.kpId` 拉取；404 显示「内容不存在或已下线」空态。
- 卡面结构（自包含样式，不依赖 CSS 变量，为卡图渲染兜底）：正面 = 题面 + 难度五色条 + 技能域标签 + 「还有 N 层追问」提示 + 产品水印；翻面 = 三档样例（默认展开及格档，良好/优秀可展开）；追问链 accordion 按 layerKey 逐层展开；「看不懂？」展开从零讲（MarkdownView 渲染）；设计链簇显示完整参考方案折叠区。
- 底部「用知己练这道题」→ 跳 `/`（7.5 找回来语义）；标注「在知己中会按你的目标职级标出停止点」替代目标刻度。
- **保存卡图按钮**：引入依赖 `html2canvas-pro`（处理现代 CSS 色彩空间的 html2canvas 修复版，自带类型）渲染卡面 DOM 为 PNG 下载；卡面含水印与「在知己查看答案」提示文案（卡图只带题面钩子，答案引导回站内）。
- 接收端「翻面」前不显示答案区（题面钩子优先，点击「翻面看答案」后展开）。

**T5 验收**（对应知识库PRD 12.2 分享项）：active 样板知识点公开链接可访问、翻面/追问/从零讲可展开、卡图可保存；draft 知识点 404。

### 第二部分：T6 文档解析与素材库

#### T6-1 建表（schema.sql 追加，幂等）

**修改** `src/main/resources/db/schema.sql`，追加：

```sql
-- T6 素材库：kp_source_doc（素材文档）与 kp_candidate（候选条目）
CREATE TABLE IF NOT EXISTS kp_source_doc (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    batch_id     TEXT    NOT NULL,                    -- 导入批次号（批次是查询维度不是实体，批次面板按此聚合）
    file_name    TEXT    NOT NULL,                    -- 文件名（zip 解包时含相对目录，作来源线索）
    source_ref   TEXT,                                -- 来源出处：链接 / 书名·手册章节（D17，随管道向下传递）
    form         TEXT,                                -- 内容形态：question_bank 题库 / reading 资料 / interview 面经（LLM 识别 + 人工修正）
                 -- CHECK 因 form 可能空（未解析）不设 CHECK，取值对齐由枚举与前端 types 保证
    status       TEXT    NOT NULL DEFAULT 'pending'   -- source_doc_status 状态机（附录 A 8.2）
                 CHECK (status IN ('pending','parsed','parse_failed','split')),
    parse_error  TEXT,                                -- 解析失败原因（扫描件/超限/编码错误等，显式落状态）
    raw_text     TEXT,                                -- 解析文本（版权红线：过程材料，只留素材文档、只进 B 端预览，绝不进 kp_* 表）
    heading_tree TEXT    NOT NULL DEFAULT '[]',       -- 标题树 JSON（[{level,title,line}]，章节切分依据）
    char_count   INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_kp_source_doc_batch ON kp_source_doc (batch_id);
CREATE INDEX IF NOT EXISTS idx_kp_source_doc_status ON kp_source_doc (status);

CREATE TABLE IF NOT EXISTS kp_candidate (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    doc_id          INTEGER NOT NULL REFERENCES kp_source_doc (id),  -- 溯源回素材文档
    batch_id        TEXT    NOT NULL,                   -- 继承批次（免 join 过滤）
    title           TEXT    NOT NULL,                   -- 候选题面
    answer_evidence TEXT,                               -- 答案证据：题库型=答案段 / 资料型=AI 提炼要点 / 面经型=追问记录
    section_path    TEXT,                               -- 章节路径（如 "Java集合 > HashMap"）
    form            TEXT    NOT NULL,                   -- 片段级形态（可人工修正，混合文档片段级标签）
                    CHECK (form IN ('question_bank','reading','interview')),
    status          TEXT    NOT NULL DEFAULT 'pending'  -- candidate_status 状态机（附录 A 8.2）
                    CHECK (status IN ('pending','matched','converting','converted','merged','discarded')),
    canonical_id    INTEGER,                            -- 同题归一指向（T7 落 kp_canonical 后补 FK 与逻辑约束，先留普通列）
    confidence      TEXT,                               -- 拆题置信：high 结构命中 / low LLM 兜底（批量确认策略依据）
    note            TEXT,                               -- 人工备注
    created_at      TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_kp_candidate_doc ON kp_candidate (doc_id);
CREATE INDEX IF NOT EXISTS idx_kp_candidate_batch ON kp_candidate (batch_id);
CREATE INDEX IF NOT EXISTS idx_kp_candidate_status ON kp_candidate (status);
```

#### T6-2 后端：docparse 支撑包（新）

**新建** `src/main/java/com/zhiji/docparse/`：

- `api/DocParseService.java`（对外契约）：`ParsedDoc parse(String fileName, String content)` → `record ParsedDoc(String plainText, List<Heading> headings, int charCount)`、`record Heading(int level, String title, int line)`。
- `service/MarkdownParser.java`：逐行扫描 `#{1,6} ` 还原标题树（记录行号）；正文保留原文（AI 重写输入保留 markdown 代码块/表格语义）；剔除导航/目录类噪音行（连续链接行、`[toc]`）。
- `service/PlainTextParser.java`：txt 按空行分组、序号行启发式识别标题。
- 格式拒收：按扩展名路由，docx/doc/pdf → 抛 `BizException` 400 `DOC_FORMAT_UNSUPPORTED`（文案按 D31：「docx 请用 pandoc 预转为 md 后上传；pdf 文字版用专用工具转 md，扫描版不支持」）。不引入 POI/PDFBox。

#### T6-3 后端：pipeline 模块四层（新）

**新建** `src/main/java/com/zhiji/pipeline/`，依赖 kb api（meta）、llm api（LlmClient/PromptTemplate/LlmPurpose）、docparse api；repo 不出模块。

- `domain/`：`KpSourceDoc`、`KpCandidate`（MyBatis-Plus 实体）、枚举 `SourceDocStatus`（pending/parsed/parse_failed/split）、`CandidateStatus`（pending/matched/converting/converted/merged/discarded）、`SourceForm`（question_bank/reading/interview）——均 `@ConstantGroup` 注册常量下发（`source_doc_status`、`candidate_status`、`source_form` 三个新常量组）。
- `repo/`：`KpSourceDocMapper`、`KpCandidateMapper`（含批次聚合、按状态过滤的自定义 SQL）。
- `service/`：
  - `DocService`：导入（多文件 + zip 解包，`java.util.zip` 内置；batchId = `B`+时间戳+4 位随机）、批次聚合视图、文档详情、删除（存在 matched/converting 在途候选则拒绝 409）、形态修正（form 变更仅限 parsed 态）、重试。
  - `ParseService`：调 docparse → 落 raw_text/heading_tree/char_count → 调 LLM 形态识别（`completeJson`，prompt 输出 `{form, confidence, reason}`）→ status=parsed 或 parse_failed（显式落 parse_error）。
  - `SplitService`：按形态路由三路拆分：
    - **题库型**：三档策略——①标题层级（H2/H3 为题、其后正文为答，confidence=high）→ ②题号正则（`第?N[题.、:：]` / `QN` / `【题N】` 等，high）→ ③LLM 兜底（`SPLIT_FALLBACK`，已有 purpose，low）。
    - **资料型**：按标题树切章节片段落为「章节片段清单」（本次不自动出题）；**反推出题按片段勾选触发**（D22/B端PRD 6.3：反推是 B 端 LLM 成本大头，不默认全量跑）→ 每片段调 `QUESTION_GEN` 输出 1~3 个 `{question, points[]}` → 每问一条候选（answer_evidence=要点拼接）。
    - **面经型**：调 `INTERVIEW_EXTRACT` 抽 `{questions:[{question, followups[]}]}` → 每题一条候选（answer_evidence=追问记录，form=interview）。
    - 产物统一落 `kp_candidate`（status=pending，confidence 标记），doc.status=split；单次 LLM 调用输入按片段/分批截断（≤6000 字符）防超长。
  - `PromptLoader`：从 `classpath:prompts/` 读取模板 + `PromptTemplate.render` 渲染（模板归业务模块 resources，llm 只提供渲染入口——既有口径）。
- `api/DocAdminController.java`（`/api/admin/docs`，员工会话，拦截器 `/api/admin/**` 汇总行已覆盖，无需改拦截器）：
  - `POST /import`（multipart：files[] + 可选 sourceRef；zip 自动解包，目录结构进 file_name）
  - `GET /batches`（批次聚合：batchId、文件数、各状态计数、失败清单）
  - `GET /batches/{batchId}`（批次文件清单）
  - `GET /{id}`（详情：解析文本、标题树、候选条目列表——拆题预览数据源）
  - `POST /{id}/parse`、`POST /{id}/retry`（重试=重跑 parse）、`DELETE /{id}`
  - `POST /{id}/form`（形态修正）
  - `POST /{id}/split`（按当前形态执行拆题；重复拆题先清空该 doc 的 pending 候选再重拆）
  - `POST /{id}/inverse-gen`（body: 片段索引数组，资料型专用）
  - `PUT /candidates/{id}`（题面/答案证据编辑 + form 标签修正——拆题后人工修正入口）
- `resources/prompts/`：`form_detect.md`、`inverse_question.md`、`interview_extract.md`、`split_fallback.md`（强制 JSON 契约 + few-shot 一例）。

#### T6-4 后端：LlmPurpose 扩展 + D32 限额改造

**修改** `src/main/java/com/zhiji/llm/api/LlmPurpose.java`：新增三项（均 B 端生产类，`LimitKey.NONE`，Tier.QUALITY）：
- `DOC_FORM_DETECT("doc_form_detect", "素材形态识别")`
- `QUESTION_GEN("question_gen", "资料反推出题")`
- `INTERVIEW_EXTRACT("interview_extract", "面经抽题")`

**修改** `LlmLimits.java`：record 加第 5 个组件 `boolean siteCircuitEnabled`（Jackson 3 反序列化旧 JSON 缺字段时 boolean 原始类型默认 false，正合「默认关闭」语义）；`defaults()` 同步（`200, 20, 300, 1000, false`）。

**修改** `LlmRateGuard.java`（D32）：
```java
public void check(LlmPurpose purpose, long profileId) {
    if (profileId == 0L) return; // D32：B 端生产类不设限额、不计任何熔断
    LlmLimits limits = configService.limits();
    if (limits.siteCircuitEnabled()
            && usageService.todayCustomerTotal() >= limits.siteDailyTotal()) { ... } // 仅统计 profile_id != 0
    if (usageService.todayTotalByProfile(profileId) >= limits.perProfileDailyTotal()) { ... }
    // judgeDaily 软上限口径不变；variant 硬上限口径不变
}
```
`LlmUsageService` / `LlmUsageDailyMapper` 加 `todayCustomerTotal()`（`SUM WHERE profile_id != 0`）。settings 的用量对照接口照常读全量（B 端用量仍可观测）。

**修改** `LlmConfigInitializer.java`：`load()` 后对 `routeConfig.purposes()` 补齐缺失的 purpose（按 `defaultRouteConfig()` 对应 tier 填充并写回 sys_config）——解决旧库存量路由 JSON 缺新入口导致 500 的问题。

**修改** `SettingsView.vue` + `api/settings.ts` + types：限额表单加「全站熔断护栏」开关（默认关，说明文案引用 D32）。

#### T6-5 前端：DocView 接线（候选池留占位）

**新建** `frontend/src/api/pipeline.ts`（按后端模块组织）：上述接口的调用函数。

**修改** `frontend/src/api/types.ts`：新增 `SourceDocStatus`、`CandidateStatus`、`SourceForm` 类型联合（对齐附录 A 8.2 与后端枚举）、`SourceDocView`、`BatchSummary`、`CandidateView`、`ParsedHeading`；`LlmPurpose` 联合类型追加三个新值。

**重写** `frontend/src/views/admin/DocView.vue`：

- **批次面板**：批次列表（文件数 / pending·parsed·parse_failed·split 计数），点击进入批次文件清单；失败清单一眼可见。
- **上传对话框**：多文件选择 + zip；来源出处（sourceRef）输入 + **固定出处提示文案**（B端PRD 6.4：「各来源协议与授权各异，维护者只作参考输入，成品一律走 AI 参考重写 + 人工审核管道；docs/题库源/ 素材仅限本地使用」）。
- **文件表格**：文件名、形态、状态、字符数、失败原因、操作（解析 / 拆题 / 重试 / 预览 / 删除）。
- **预览抽屉**（逐文件确认，B端PRD 6.3）：左侧解析文本（MarkdownView）+ 标题树大纲；右侧拆题结果（候选条目列表：题面可编辑、答案证据预览、confidence 标记、删条目）；资料型显示章节片段列表 + 按片段勾选触发反推出题；拆题入池后显示「已入池 N 条，可回跳溯源」。
- 批量操作：parsed 文件多选批量拆题（结构命中高置信的才建议批量，低置信文件行提示「建议逐个人工看」）。
- 非活跃态友好提示：解析失败显式原因（含 D31 格式拒收文案）。

#### T6-6 顺手清理（D30）

- 删除 `frontend/src/views/admin/SkillsView.vue`；`AdminLayout.vue` 菜单注册表移除技能库行；`router/index.ts` 移除 `/admin/skills` 路由。

### 第三部分：测试

**后端**（参照 `KpTest` / `StubLlmTransport` 模式，`@SpringBootTest + @ActiveProfiles("test")`）：

- 新建 `src/test/java/com/zhiji/pipeline/DocPipelineTest.java`（MockMvc 集成）：
  1. 导入 md/txt/zip → 批次聚合正确；docx/pdf 拒收并返回 D31 文案；
  2. 解析 → 状态 parsed、标题树与 raw_text 落库、LLM 形态识别生效（stub）；
  3. 题库型拆题：标题层级命中走结构策略（不发 LLM）、无结构文件走 LLM 兜底（stub），候选落库 confidence 正确；
  4. 资料型：片段切分正确、反推出题按片段触发（stub）；面经型抽题落库；
  5. 形态修正、重复拆题清 pending 重拆、解析失败显式落 parse_error 与重试；
  6. 删除约束：存在 matched（手工置态）候选时 409，全 pending 可删且候选级联删除；
  7. 鉴权：客户 token 打 /api/admin/docs 401（拦截器既有语义）。
- 新建 `src/test/java/com/zhiji/kb/ShareKpTest.java`：active 知识点无 token 200 且结构完整（含三档样例与追问链、不含 levelReq/rubric）；draft → 404；retired → 404。
- 新建 `src/test/java/com/zhiji/llm/RateGuardD32Test.java`：profileId=0 永远放行（即使超全站阈值）；`siteCircuitEnabled=false`（默认）全站超限仍放行；`=true` 时 C 端超限抛 SYS_CIRCUIT_OPEN；路由 JSON 缺新 purpose 时启动补齐。
- docparse 纯单测：md 标题树还原、txt 启发式、格式拒收。

**前端**：`npm run build`（含 vue-tsc 类型检查）为验收门槛。

## 四、Assumptions & Decisions

| # | 决策 | 依据 |
|---|---|---|
| 1 | T5 先于 T6 交付 | 用户拍板（推荐项） |
| 2 | 形态识别用 LLM（新 purpose `doc_form_detect`），预览页可人工改 | 用户拍板；知识库PRD 九-2「AI 识别内容形态」 |
| 3 | T6 新增 3 个 LLM 入口，均 Tier.QUALITY / LimitKey.NONE（B 端生产类不限额） | D32：B 端生产类不设限额不计熔断 |
| 4 | D32 本次落地范围：B 端豁免 + 全站护栏开关；用户聚合维度留 T10（依赖 user_profile 表） | 用户拍板（推荐项） |
| 5 | 批次不建独立表，`batch_id` 为查询维度（批次面板按 GROUP BY 聚合） | 附录 A 23 张表无 batch 表；批次是进度单位非业务实体 |
| 6 | 拆题即落候选池（pending），「批量确认」务实化为批量执行拆题；预览页承担人工修正（编辑题面/删条目） | B端PRD 6.3：「拆题产物只是候选池条目，距离用户路径还隔着研判、成稿、校对三道关」 |
| 7 | 候选池页（/admin/candidates）本次不接线，研判工作流（AI 匹配建议/并入/成稿触发）随 T7/T8 | 用户拍板；T6 明细仅 DocView |
| 8 | `kp_candidate.canonical_id` 先留普通列不加 FK（kp_canonical T7 才建） | SQLite CREATE TABLE IF NOT EXISTS 无法给既有表加列 |
| 9 | 卡图用 `html2canvas-pro` 渲染 DOM→PNG；卡面组件用自包含内联样式规避 CSS 变量兼容 | 零依赖手绘 SVG 断行成本高；html2canvas 对 oklch 支持差故选 -pro 分支 |
| 10 | 权限：全部新接口落 `/api/admin/**` 汇总行（员工会话）与 `/api/share/**` 白名单，无需改拦截器与矩阵 | 基础架构PRD 3.3 既有登记 |
| 11 | 解析文本（raw_text）仅 B 端素材预览可见，绝不进 kp_* 表 | B端PRD 6.3 预览需展示 vs 版权红线「不进知识点内容」，域 PRD 为准 |
| 12 | 常量三处同步：新枚举进后端 `@ConstantGroup`（DB 刻意无 CHECK 不用动）+ 前端 types.ts | T0 规范；schema L52-54 既有说明 |

## 五、实施顺序（执行时用 TodoWrite 跟踪）

1. T5 后端（ShareController + KpService.shareDetail）→ T5 测试
2. T5 前端（kb.ts/types/ShareView 重写 + html2canvas-pro 依赖）
3. D32 llm 改造（LlmPurpose 三入口、LlmLimits、LlmRateGuard、Initializer 补路由、SettingsView 开关）→ RateGuardD32Test
4. T6 建表（schema.sql 追加）
5. docparse 包 + 单测
6. pipeline 四层（domain/repo/service/api/prompts）→ DocPipelineTest
7. 前端 pipeline.ts / types.ts / DocView 重写
8. D30 清理（SkillsView 三处移除）
9. 全量验证：`mvn clean compile` → `mvn test` → `cd frontend && npm run build` → 冒烟清单

## 六、Verification（验收清单）

- **T5**：知识库PRD 12.2 分享项——用任一 active 样板知识点验证：`GET /api/share/kp/{id}` 无 token 可达、公开页翻面/追问/从零讲可展开、卡图可保存；draft/retired 404。
- **T6**：B端管理台PRD 十四-2 之素材库部分——一批 md 素材（开发期用 docs/题库源/JavaGuide 下若干 md 本地导入验证，≥30 文件路径可跑通）从导入 → 解析 → 形态路由 → 拆题入池全程走 DocView 完成；失败文件显式原因可重试可移除；溯源回跳正确；解析文本不出现在任何 kp_* 表。
- **D32**：B 端拆题/反推调用不计熔断（profileId=0 放行）；全站护栏默认关；设置页开关读写生效。
- **回归**：`mvn test` 全绿（含既有 18+ 用例）；`npm run build` 绿；知识地图/候选池/校对流转占位页不受影响。
