# T4 标准知识库（核心模块定义 5.2）+ 新增向导（5.1 补课）· 实施计划

## Summary

实现知识库管理模块的剩余两块，设计真相源为[核心模块定义 5.1/5.2/5.3/6.2/6.4](d:\ideaProject\zhiji\docs\核心模块定义.md)：

1. **5.2 标准知识库**（新建 `com.zhiji.kb` 后端包 + 三个 B 端前端页面）：知识点（面试题线）/ 资料讲解（文章线）两套标准内容实体、主题表（组织结构表化）、审核队列（进门质量门，四项强制校验人工放行）、同位去重与合并（可视化合并页 + AI 一键合并）、内容生产台（审核队列/同位管理/复核到期）、知识库目录（目录树/内容编辑/组织调整/直达新建/模糊搜索）、复核到期机制、下线与恢复。
2. **5.1 新增向导**（前端为主，后端端点已就绪）：`/admin/wizard` 四步引导页（编写 → 拆分 → 概述难度分类 → 入标准库），补齐缺失的**难度建议** AI 端点；素材库「进标准库」按钮接通向导（已就绪步骤跳过）；**直达新建**（不经素材库直提进审核）；素材 `submit` 从"仅改状态"升级为"创建标准库内容 + 进审核队列"。

关键现状：素材侧向导端点（split/ai-*/submit）后端已全部存在且前端 API 封装已写好（`api/source.ts`），只差前端向导页消费；标准库实体与审核/合并/目录完全不存在，从零建。

**不做**（一期后置项，文档口径）：定时 LLM 自动预审、复核到期自动扫描（一期列表人工巡检）、C 端消费标准内容（学习/分享属后续模块）、语音、申诉复核自动化。

## Current State Analysis

### 已有（直接复用）
- **后端**：Spring Boot 4.1.1 + MyBatis-Plus 3.5.17 + SQLite(WAL)；模块四层 `api/service/domain/repo`；`BizException` + `GlobalExceptionHandler`；常量组体系（`@ConstantGroup` 枚举 → `ConstantRegistry` → `GET /api/constants` 下发）；`AuthInterceptor` 已覆盖 `/api/admin/**`（员工会话，admin/editor 均可，无需改动）；LLM 门面 `LlmClient.complete/completeJson`（purpose 路由 + 限额 + 计数 + JSON 重试）。
- **素材库（T3）**：`com.zhiji.source`。`SourceController` 已含向导素材侧全部端点：`split / split-preview / ai-rewrite / ai-split / ai-summary / ai-classify / ai-infer / domain-topics / submit`；`submit` 已校验概述门槛、子素材成组，但目前**只把素材标 `submitted`，不产生标准库内容**。
- **前端**：Vue 3.5 + Element Plus 2.14 + Pinia + markdown-it；`DocView.vue`（素材库页）完整实现，`api/source.ts` 已封装向导全部 API；素材库「进标准库」按钮是占位提示，[DocView.vue:253](d:\ideaProject\zhiji\frontend\src\views\admin\DocView.vue#L253) 的 TODO 明确指向 `router.push({ path: '/admin/wizard', query: { sourceId: String(item.id) } })`。
- **领域常量**：`Domain`（7 领域，5.3 草案已固化为枚举）、`DomainTopic`（20 个种子主题，注释明确"随 T4 标准库落地后主题以表为准，本组退化为种子数据"）、`Difficulty`（四档 junior/mid/senior/expert）。
- **测试先例**：`SourceFlowTest`（`@SpringBootTest` + `StubLlmTransport` + 员工登录 + MockMvc/service 双轨）。

### 缺口
1. 标准库实体（kp/doc）、主题表、审核记录表均不存在（schema.sql 无 kb 表）。
2. 审核队列、同位合并、内容生产台、知识库目录完全不存在。
3. AI 用途缺 `KB_MERGE`（同位语料合并）与 `SOURCE_DIFFICULTY`（难度建议，6.4 用途清单之一）。
4. 向导前端页不存在；直达新建不存在；`domain-topics` 端点现从常量组取（需切主题表）。
5. 前端 4 个旧版口径空占位页（KpManageView/CandidateView/KnowledgeMapView/ReviewFlowView）与旧菜单命名待清理。

## 领域决策（已拍板，实现不再选择）

| # | 决策 | 依据 |
|---|---|---|
| D1 | 知识点**完整结构化**：变体/追问链/评分要点/三档样例/职级规则/前置依赖全部 JSON 列，编辑 UI 一期支持题面、叙述框架、追问链、评分要点、三档样例的实用编辑 | 5.2 知识点结构；判定引擎 T12 直接消费 |
| D2 | 删除 4 个旧占位页，菜单重组为新口径四页 | 新版核心模块定义 5.2 页面结构 |
| D3 | 合并产物**新行顶替**：产物落库为 pending 新行；审核通过后其余源标 `merged`（merged_into 指向产物，数据保留可恢复），覆盖度频率由源转入 | 5.2"旧内容逻辑删除""新产物审核通过后替换更新既有知识点（位置）" |
| D4 | 审核范围 = 向导提交 + 合并产物 + 送复审；**人工编辑 available 内容直接生效不重审**（刷新 updated_at，不动状态） | 5.2"进门先过审核门"只约束两个入口；知识库本体维护线允许日常编辑 |
| D5 | 追问层身份：编辑器新增层时前端生成 `fu-<uuid>`，保存永不变更；增减走新增/停用（status=active/disabled），停用不复用 | 6.2 红线 2 |
| D6 | 复核周期默认 90 天，放行时可改；到期判定 `review_due_at <= 今天`；"送复审"= available → pending + 留痕 | 5.2 复核到期机制 + 一期人工巡检口径 |
| D7 | 直达新建**同样受概述门槛**（第 3 步补概述与分类，文档原话），提交即 pending | 5.2 直达新建段 |
| D8 | 直达新建的 AI 能力（改写/概述/分类建议/难度建议）经 `POST /api/admin/kb/ai/*` 通用端点提供（内容不绑定素材行），内部委托 `SourceAiService`（其方法本就接受 content 字符串） | 6.4"向导与素材库共用同一套 AI 辅助能力" |
| D9 | 频率信号一期落两源：人工标定（编辑页）+ 合并覆盖度转入；面经信号列保留恒 0 | 5.2 频率信号三来源 |
| D10 | doc 溯源：素材 `source_url` 提交时自动带入 `kb_doc.source_url`；kp 不带（面试题线无溯源展示需求，素材侧留痕即可） | 5.1③ 溯源记录只留在素材侧 + 红线 7 进门即断联 |
| D11 | 素材图片 `asset:source/{id}/{file}` 引用原样保留，B 端渲染时经 admin 资产端点转换；C 端上线前需迁移资产（登记后置项，不在本次范围） | 红线 7"进门即快照"的图片边界，一期 B 端内部使用 |
| D12 | 审核角色：admin/editor 均可审核（B 端不做角色矩阵） | 6.3 人员管理 |
| D13 | 主题表化：`kb_topic` 表为真相源；`DomainTopic` 常量组经 `KbBootstrap` 幂等种子；`domain-topics`/分类建议候选切表 | 5.2 组织结构可改、5.3 清单可配置 |

## Proposed Changes

### A. 数据库（src/main/resources/db/schema.sql 追加）

```sql
-- T4 标准知识库（核心模块定义 5.2）：主题为组织节点（第二层），内容按定类分流两套实体（红线 7：进门即独立）。
-- 内容状态机：draft(退回/恢复) → pending(待审) → available(上线)；pending→deprecated(废弃)；
-- available→deprecated(弃用下线)；合并通过后源→merged(合并下线, merged_into 指向产物)。

CREATE TABLE IF NOT EXISTS kb_topic (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    domain_code    TEXT NOT NULL,                 -- 领域（常量组 domain，人工维护边界）
    name           TEXT NOT NULL,                 -- 主题名：前端可见、可改名（5.2 组织结构可改）
    status         TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','merged')),
    merged_into_id INTEGER,
    created_at     TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at     TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (domain_code, name)
);

CREATE TABLE IF NOT EXISTS kb_kp (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    domain_code     TEXT NOT NULL,
    topic_id        INTEGER NOT NULL REFERENCES kb_topic (id),
    title           TEXT NOT NULL,                -- 题面
    summary         TEXT,                         -- 可读摘要（目录/同位管理用，继承素材概述）
    framework_md    TEXT NOT NULL DEFAULT '',     -- 叙述框架/正文（Markdown）
    variants        TEXT NOT NULL DEFAULT '[]',   -- 问法变体 [{"text"}]
    followups       TEXT NOT NULL DEFAULT '[]',   -- 追问链 [{"id","layer","direction","content","status"}] id 永久（红线 2）
    scoring_points  TEXT NOT NULL DEFAULT '[]',   -- 分级评分要点 [{"id","level":"pass|good|excellent","content"}] 判定引擎尺子
    answer_samples  TEXT NOT NULL DEFAULT '[]',   -- 三档样例 [{"level":"pass|good|excellent","content"}]
    difficulty      TEXT NOT NULL CHECK (difficulty IN ('junior','mid','senior','expert')),
    level_rules     TEXT NOT NULL DEFAULT '[]',   -- 职级要求与停止点 [{"level":"junior|mid|senior|expert|master","required","stop_layer"}]
    prerequisites   TEXT NOT NULL DEFAULT '[]',   -- 前置依赖 [kpId]
    freq_manual     INTEGER NOT NULL DEFAULT 0,   -- 频率信号·人工标定
    freq_signal     INTEGER NOT NULL DEFAULT 0,   -- 频率信号·面经回流（一期恒 0）
    freq_coverage   INTEGER NOT NULL DEFAULT 0,   -- 频率信号·覆盖度（合并通过时由源转入）
    status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('draft','pending','available','deprecated','merged')),
    merged_into_id  INTEGER REFERENCES kb_kp (id),
    merge_sources   TEXT,                         -- 非空即合并产物：源 id JSON 数组，pass 时据此处理源
    review_due_at   TEXT,                         -- 复核到期时间（pass 时设置，D6）
    created_by      INTEGER NOT NULL,
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_kb_kp_topic ON kb_kp (topic_id, status);

CREATE TABLE IF NOT EXISTS kb_doc (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    domain_code    TEXT NOT NULL,
    topic_id       INTEGER NOT NULL REFERENCES kb_topic (id),
    title          TEXT NOT NULL,
    summary        TEXT,
    content_md     TEXT NOT NULL,                 -- 讲解正文 Markdown 快照
    difficulty     TEXT NOT NULL CHECK (difficulty IN ('junior','mid','senior','expert')),
    source_url     TEXT,                          -- 来源溯源（素材 URL 自动带入，D10）
    kp_id          INTEGER REFERENCES kb_kp (id), -- 挂载的知识点（挂载即人工发布决策）
    status         TEXT NOT NULL DEFAULT 'pending'
                   CHECK (status IN ('draft','pending','available','deprecated','merged')),
    merged_into_id INTEGER REFERENCES kb_doc (id),
    merge_sources  TEXT,
    review_due_at  TEXT,
    created_by     INTEGER NOT NULL,
    created_at     TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at     TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_kb_doc_topic ON kb_doc (topic_id, status);

-- 审核留痕（5.2：留痕可回溯）：动作式流水，每次动作一行
CREATE TABLE IF NOT EXISTS kb_review (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    content_type TEXT NOT NULL CHECK (content_type IN ('kp','doc')),
    content_id   INTEGER NOT NULL,
    action       TEXT NOT NULL CHECK (action IN ('submit','resubmit','recheck','pass','reject','discard')),
    source       TEXT NOT NULL DEFAULT 'wizard' CHECK (source IN ('wizard','direct','merge','recheck')),
    checks       TEXT,                            -- 四项强制校验 {"correctness","judgeable","infringement","quality","notes"}
    comment      TEXT,
    operator_id  INTEGER NOT NULL,
    created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_kb_review_content ON kb_review (content_type, content_id);
```

### B. 后端新包 com.zhiji.kb（四层）

**domain**（5 个）：`KbTopic`、`KbKp`、`KbDoc`、`KbReview`（MyBatis-Plus 实体，JSON 列用 String 承载）、`KbContentStatus`（`@ConstantGroup("kb_status")` 枚举：draft/pending/available/deprecated/merged，前端 types.ts 同步——常量三处同步口径）。

**repo**（4 个）：`KbTopicMapper`、`KbKpMapper`、`KbDocMapper`、`KbReviewMapper`（`@MapperScan("com.zhiji.**.repo")` 已覆盖）。

**service**（4 个）：

1. `KbTopicService`：list(domainCode)（active，附内容计数）、create、rename、merge(id→targetId)（内容 topic_id 迁移 + 本主题标 merged）、move(id, domainCode)（改领域并级联内容 domain_code）。
2. `KbContentService`：
   - `createFromSource(SourceItem, contentType, topicId)`：素材 → kb_kp/kb_doc（pending、summary/difficulty 带入、doc 带 source_url）+ 审核记录（action=submit, source=wizard）；供 `SourceService.submit` 调用。
   - `directSubmit(...)`：直达新建（校验概述门槛 D7）→ 同上（source=direct）。
   - `getKp/updateKp/getDoc/updateDoc`：编辑（available 与 pending、draft 可编辑；followups id 不做任何改写——红线 2 的服务端保证：保存时仅追加新 id、不改不删已有 id）；updateKp 校验 prerequisites 指向非下线 kp（红线：下线后封死）；updateDoc 挂载 kp_id 校验目标 kp 非 deprecated/merged。
   - `offline/restore`：弃用下线（available→deprecated）、恢复（deprecated/merged→draft，须重审）。
   - `catalog(domainCode)`、`listByTopic(topicId, type, status)`、`search(keyword, type)`（LIKE 题面/框架/正文/变体，服务复盘 5.2 边界）、`peers(topicId, type)`（同位清单，排除已下线）。
3. `KbReviewService`：queue(contentType)、detail(type,id)（内容全量 + kb_review 历史）、pass(type,id,checks,comment,dueDays)（校验四项全 pass、doc 线 judgeable=na → available + review_due_at + 留痕；**若 merge_sources 非空：源标 merged/merged_into=产物 id、freq_coverage=Σ源频率**，D3/D9）、reject（→draft 留痕）、discard（→deprecated 留痕）、resubmit（draft→pending 留痕）、due 列表（available 且到期）、recheck（available→pending 留痕）。
4. `KbMergeService`：validate(type, ids)（1–3 份、同 type 同 topic、均非下线，D3）；aiMerge(type, ids)（调 `LlmClient.completeJson` purpose=KB_MERGE，产物 JSON 不落库回人手里，含 conflicts 矛盾点单列）；submit(type, ids, product)（产物落库为 pending 新行 + merge_sources + 审核记录 source=merge）。

**api**（3 个）：

- `KbAdminController`（`/api/admin/kb`）：topics CRUD + merge/move、catalog、kp/doc get/put、direct-submit、search、peers、kp/doc offline/restore、merge/validate|ai|submit、`GET /ai/rewrite|summary|classify|difficulty`（通用 AI 端点，D8，委托 `SourceAiService`；difficulty 为新方法）。
- `KbReviewController`（`/api/admin/kb/reviews`）：queue、kp/{id}/detail、doc/{id}/detail、pass/reject/discard/resubmit/recheck、due。
- `KbBootstrap`（config）：`ApplicationRunner`，`kb_topic` 空表时按 `DomainTopic` 枚举幂等种子（先例 `AdminBootstrap`）。

### C. LLM 扩展

- `LlmPurpose` 追加：`KB_MERGE("kb_merge","同位语料合并",LimitKey.NONE,Tier.QUALITY)`、`SOURCE_DIFFICULTY("source_difficulty","难度建议",LimitKey.NONE,Tier.QUALITY)`（自动进常量下发与用量计数）。
- 新 prompt 模板 `src/main/resources/prompts/kb_merge.txt`：输入定类 + ≤3 份语料；面试题线输出知识点全结构 JSON（题面收敛、问法变体、叙述框架、追问链含层号与方向、分级评分要点、三档回答样例、职级差异、多源矛盾点 conflicts 单列）；文章线输出标准讲解。`source_difficulty.txt`：输出四档难度建议 + 理由。
- `SourceAiService` 增加 `difficulty(content)` 方法（返回 `{difficulty, reason}`，越界档降级"mid"——沿用 infer 的兜底风格）。

### D. 素材侧改造（com.zhiji.source）

1. `SourceController.SubmitReq` 扩展为 `{domainCode, topicId, contentType}`；`SourceService.submit` 改造：校验概述门槛 + 领域合法 + topicId 属于该领域 → 对母素材与 pending 子素材逐条调 `KbContentService.createFromSource`（跨包依赖方向 source→kb，kb 不依赖 source，无环）→ 素材标 submitted（原语义不变）。**sub 语义变化点：提交后进入审核队列而非直接"已入库"展示口径**——素材状态仍为 submitted（素材侧只关心"已送出"），前端文案"已提交进标准库（待审核）"。
2. `SourceController` 新增 `POST /{id}/ai/difficulty`（向导第 3 步 AI 难度建议，补 6.4 用途缺口）。
3. `SourceService.topicCandidates(domainCode)` 改为注入 `KbTopicService.listNames(domainCode)`（常量组 `domain_topics` 退化为种子，D13）。
4. `SourceFlowTest.submitRequiresSummary` 语义跟随（断言补充：提交后产生 pending 标准内容）。

### E. 前端

**新增文件**：
1. `api/kb.ts`：上述全部端点封装 + `KbTopic/KpContent/DocContent/ReviewCheck/KbMergeProduct` 类型（types.ts 追加，与 schema JSON 列结构一一对应）。
2. `views/admin/WizardView.vue`（`/admin/wizard`，四步引导页）：
   - 支持 `query.sourceId`（素材接续：加载素材；概述与分类已就绪则落到第 3 步直达确认）与无参直达新建（D8 通用 AI 端点）。
   - Step1 编写：Markdown 编辑 + 预览（markdown-it + asset 引用转换，抽取 DocView 的图片渲染规则为公共工具 `utils/markdown.ts` 复用）+ AI 改写（产物回编辑器，展示定类建议）。
   - Step2 拆分（仅素材模式且可选）：AI 一键拆分按 anchor 预填、逐条增删改合并重划 → 确认调 `split` → 转逐条模式；"不拆分继续"跳过。直达新建显示"跳过拆分"。
   - Step3 概述、难度与分类：逐条卡片——概述（100 字内 + AI 生成）、难度四档（AI 建议/人工拍板）、领域（常量 domain 下拉）→ 主题（`GET /api/admin/kb/topics` 候选 + AI 分类建议采纳/无视）；素材模式即时 `PUT /sources/{id}` 持久化（保证提交门槛数据齐备）。
   - Step4 入标准库：定类（面试题线 kp / 文章线 doc，默认取改写定类建议）+ 汇总确认 → 素材模式 `POST /sources/{母id}/submit`（子素材成组）｜直达模式 `POST /api/admin/kb/direct-submit` → 成功提示 + "去审核队列"入口。
3. `views/admin/ProductionDeskView.vue`（`/admin/production`，待办队列三 tab）：
   - 审核队列：pending 列表 → 审核详情抽屉（`KpDisplay`/`DocDisplay` 只读渲染 = 用户侧预览所见即所得 + 审核留痕时间线）+ 四项强制校验逐项 pass/fail（doc 时 judgeable 固定 na）+ 放行（全 pass 才可，复核周期默认 90 可改）/退回（意见必填）/废弃。
   - 同位管理：选领域 → 选主题 → 该主题内容清单 → 勾选 ≤3 → 跳合并页。
   - 复核到期：due 列表 → 送复审。
4. `views/admin/MergeView.vue`（`/admin/production/merge?type=&ids=`，不进菜单）：三栏并排 `KpDisplay`/`DocDisplay` 对照 + 顶部产物编辑区（`KpEditor`/`DocEditor`）+「AI 一键合并」（产物含 conflicts 单列展示、不阻断，流回编辑器人工调整）或人工参照手写 + 保存（`merge/submit` → 进审核队列）。
5. `views/admin/CatalogView.vue`（`/admin/catalog`，资产家底）：
   - 左侧领域 → 主题树（新建主题/改名/合并主题/调整领域归属），右侧选中主题的内容列表 + 全局模糊搜索 + 状态筛选（含"已下线"查看与恢复）+ 行操作：编辑（抽屉 `KpEditor`/`DocEditor`）、弃用下线。
   - 直达新建按钮 → `/admin/wizard`；质量巡检入口：复核到期计数（跳生产台）+ 职级规则为空的 available 知识点抽查列表（D1 简化版巡检）。
6. `components/kb/KpEditor.vue`：知识点全结构编辑（题面/概述/叙述框架 Markdown/变体/追问链[新增层前端生成 `fu-<uuid>`、停用开关]/评分要点[档位+内容]/三档样例/难度/职级规则简单表格/前置依赖搜索选择/频率人工标定）——目录编辑与合并页共用。
7. `components/kb/DocEditor.vue`：资料讲解编辑（标题/正文/难度/挂载知识点搜索选择）。
8. `components/kb/KpDisplay.vue` + `DocDisplay.vue`：只读渲染（审核预览 + 合并三栏对照共用）；难度 chip 复用现有 `DifficultyChip`。
9. `src/test` 无前端测试框架，不新增。

**修改文件**：
- `api/types.ts`：kb 相关类型 + `SourceSubmitReq` 扩展。
- `api/source.ts`：`submitSource` 参数改为 `{domainCode, topicId, contentType}`；新增 `aiDifficulty(id)`。
- `router/index.ts`：删 `admin-kp/candidates/knowledge-map/review-flow` 四路由；加 `admin-wizard`、`admin-production`、`admin-merge`（隐藏）、`admin-catalog`。
- `layouts/AdminLayout.vue`：菜单重组——内容资产组＝知识库目录、技能库（占位保留）；内容生产组＝新增向导、素材库、内容生产台；图标增 wizard/production/catalog、删旧四键。
- `views/admin/DocView.vue`：TODO 落地——「进标准库」改 `router.push('/admin/wizard?sourceId=')`；提交状态文案对齐"已提交进标准库（待审核）"。
- **删除**：`views/admin/KpManageView.vue`、`CandidateView.vue`、`KnowledgeMapView.vue`、`ReviewFlowView.vue`。

### F. 测试（src/test/java/com/zhiji/kb/KbFlowTest.java）

沿用 `SourceFlowTest` 模式（`@SpringBootTest` + `StubLlmTransport` + 员工登录 + asStaff 直调 service）：

1. topicSeedAndRename：bootstrap 种子齐、改名、新建、合并主题（内容迁移）。
2. directSubmitCreatesPending：直提 → pending + 审核留痕；无概述被拒（D7）。
3. sourceSubmitCreatesStandardContent：素材 submit → kp/doc 两线 pending 内容（summary/difficulty/sourceUrl 带入）、素材 submitted、子素材成组。
4. reviewGate：四项校验缺一不可；pass → available + review_due_at；reject → draft 可 resubmit；discard → deprecated；全流程留痕可查。
5. mergeFlow：>3 份/跨主题/含下线被拒；AI 合并产物落库 pending（merge_sources）；pass 后源标 merged + freq_coverage 汇总；目录不再显示源；恢复源 → draft。
6. recheckAndDue：到期列表仅含 available 且过期；recheck → pending。
7. offlineRedLines：deprecated/merged 不可作合并目标、不可作前置依赖、不可被 doc 挂载。
8. followupIdImmutable：保存后已有层 id 不变、新增层有新 id。
9. searchCatalog：题面/框架模糊命中。
10. staffSessionRequired：`/api/admin/kb/**` 未登录 401。
11. aiDifficultyAndGenericAi：难度建议与通用改写端点（stub 返回）。

## Assumptions & Decisions

见上文"领域决策"表（D1–D13）。补充边界说明：

- **人工编辑 available 不重审**（D4）是对文档的显式取舍：审核门只守"进门"（提交/合并产物/复审），常驻编辑走知识库本体维护线；若后续要求"编辑即重审"，仅需在 updateKp/updateDoc 加状态回 pending 一处改动。
- **标准库内容不回写素材**（红线 7）：`kb_*` 表无任何素材外键；素材侧溯源只留在 `src_item`。
- **图片资产**（D11）：一期 B 端内可达即可，C 端消费前的资产迁移登记为后置项。

## Verification

1. `mvn clean compile` —— 改 Java 的最低验证。
2. `mvn test` —— KbFlowTest + 既有测试全绿（重点：SourceFlowTest.submitRequiresSummary 语义跟随改动）。
3. `cd frontend && npm run build` —— 类型检查 + 构建（提交前必跑）。
4. 手动全链路（`node start.js all`）：
   - 素材库建素材 → 「进标准库」→ 向导四步（AI 改写/拆分/概述/难度建议/分类建议各按一次）→ 提交；
   - 内容生产台审核：四项校验 → 放行；再造一条退回、一条废弃；
   - 同主题建 2–3 条同位内容 → 同位管理勾选 → 合并页 AI 一键合并 → 保存 → 审核通过 → 目录中源已合并下线、产物可用、覆盖度已转入；
   - 知识库目录：编辑知识点（追问链增层/停用）、主题改名、模糊搜索、直达新建走一遍；
   - 复核到期：手工把一条 review_due_at 改到昨天（SQL）→ 生产台到期 tab 出现 → 送复审。

## 实施顺序

1. schema.sql 四张表 → 2. kb domain/repo → 3. KbTopicService + Bootstrap 种子 → 4. KbContentService + 直提/编辑/搜索 → 5. KbReviewService → 6. KbMergeService + KB_MERGE prompt → 7. LlmPurpose/SOURCE_DIFFICULTY + SourceAiService.difficulty → 8. 素材侧改造（submit/ai/difficulty/topicCandidates）→ 9. KbFlowTest 跑绿 → 10. 前端 types/api/kb.ts → 11. KpEditor/DocEditor/KpDisplay/DocDisplay 组件 → 12. WizardView → 13. ProductionDeskView + MergeView → 14. CatalogView → 15. 路由/菜单/DocView 接线 + 删旧页 → 16. npm run build + 手动全链路验收。
