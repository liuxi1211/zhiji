# T7 同题归一与知识地图（pipeline 二）实施计划

> 依据：[开发计划](d:/ideaProject/zhiji/docs/开发计划.md) T7 明细、[核心模块定义](d:/ideaProject/zhiji/docs/核心模块定义.md) 3.3 / 附录 A、[知识库PRD](d:/ideaProject/zhiji/docs/prd/知识库PRD.md) 九-3、[B端管理台PRD](d:/ideaProject/zhiji/docs/prd/B端管理台PRD.md) 九 / 十四-2、决策清单 D21/D22。

## 一、Summary

交付语料先行阶段②（地图归一）：**同题归一三层漏斗 + 语料簇 + 知识地图页**。

1. **建表**：`kp_canonical`（归一题，语料层聚合节点），`canonical_status` 枚举三处同步。
2. **后端**：pipeline 新增归一引擎——①结构信号投票（免费）→ ②embedding 召回（进程内暴力余弦）→ ③LLM 精判（中间带）——产出归一题；归一题再聚成语料簇（draft 态 kp_cluster）+ AI 命名/归属域建议；归一确认 / 拆分回退 / 调簇 / 簇合并编排 / 覆盖率统计接口；归一题向已有知识点轻量映射（极高相似标 built）。
3. **前端**：KnowledgeMapView 重写接线——覆盖率统计条 + 三层视图（域 → 簇 → 归一题）+ 归一确认 / 拆分 / 调簇 / 簇操作（AI 命名建议、合并、改名走既有簇接口）+ 源候选溯源回跳素材原文。
4. **跨模块补口**：pipeline 需创建 draft 簇 / 读 active 知识点题面，在 `kb.api` 新增服务级接口（跨模块只走对方 api 的红线）。
5. **边界（用户已确认按推荐项）**：按簇成稿触发与候选池页接线随 T8（开发计划明细口径）；T5/T6 已完成，候选池保持占位。

## 二、Current State Analysis

### 已具备（探索确认）

- **T5/T6 已落地**（CLAUDE.md「当前任务 T5」的表述已过时）：kb ShareController；pipeline 四层（DocService / ParseService / SplitService / PromptLoader / ModelJson），表 kp_source_doc / kp_candidate；**[KpCandidate.canonicalId](d:/ideaProject/zhiji/src/main/java/com/zhiji/pipeline/domain/KpCandidate.java#L29) 已预埋普通列**（T6 计划决策 #8：T7 落表后补逻辑约束）。
- **LLM 支撑就位**：[LlmClient](d:/ideaProject/zhiji/src/main/java/com/zhiji/llm/api/LlmClient.java)（complete / completeJson / embedAll）；**`LlmPurpose.CLUSTER_DEDUP`（聚类去重）与 `EMBEDDING` 已在枚举**——T7 零枚举新增，B 端入口 profileId=0 不限额不计熔断（D32 已落地）。
- **kb 簇能力**：ClusterService（createDraft 语义 = status=draft 落表 / merge = 迁移 + retired 保留指向）、前端 kb.ts 簇 CRUD 与 getKpMeta 可复用；**但 kb.api 目前只有 Controller，无服务级接口**——pipeline 依赖 kb 需补口。
- **前端就位**：KnowledgeMapView.vue / CandidateView.vue 为占位页，菜单与路由已注册（AdminLayout L50）；DocView 预览抽屉存在但**不支持 `?docId=` 深链**（溯源回跳需小改）。
- **测试模式**：`DocPipelineTest`（MockMvc + 独立 SQLite 测试库）为范例；`StubLlmTransport` 可用但 embed 恒返回同向量，归一测试需可控向量的专用 stub。
- **候选状态机口径**（B端PRD 7.1）：被归一吸收的候选**保持 pending + canonical_id 非空**（状态机无新增值），候选池页只展示 canonical_id 为空的散题——两页共享状态机、入口不同。

### 缺口

| 缺口 | 处理 |
|---|---|
| kp_canonical 表不存在 | 本次建表（schema.sql 追加，幂等） |
| canonical_status 无后端枚举 / 前端类型 / DB CHECK | 三处同步（新表可加 CHECK；`@ConstantGroup` 自动下发；types.ts） |
| pipeline → kb 无 api 通道 | `kb.api.KbClusterApi` 接口（建 draft 簇 / 簇合并 / active 域清单 / active 知识点题面清单） |
| 归一漏斗、聚类、确认/拆回、覆盖率全部缺失 | CanonicalService + 纯计算漏斗 + KnowledgeMapController |
| 素材删除约束未覆盖「候选已被归一吸收」 | DocService.delete 校验扩展 |
| 误归并需可拆回、聚合准确率需人工抽检 | 拆分回退接口 + judge_basis 聚合依据字段（抽检过滤维度） |

## 三、Proposed Changes

### 3.1 建表（schema.sql 追加）

```sql
-- T7 同题归一（pipeline 二）：归一题是语料层聚合节点，距用户路径还隔成稿与校对两道关。
-- 覆盖源数参与排序与统计，实列化（SQLite 陷阱 E）；入库转频率信号发生在 T8 成稿入库时，本表只存值。
CREATE TABLE IF NOT EXISTS kp_canonical (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    title          TEXT    NOT NULL,                    -- 归一题面（组内覆盖源数最多成员的题面）
    cluster_id     INTEGER,                             -- 归属语料簇（draft 态 kp_cluster；跨模块逻辑引用不设 FK，口径同 answer_log.attempt_id）
    coverage_count INTEGER NOT NULL DEFAULT 0,          -- 覆盖源数：去重素材文档数（最硬的高频证据）
    status         TEXT    NOT NULL DEFAULT 'pending'   -- canonical_status（附录 A 8.2）：pending AI 聚合待确认 / confirmed 人工确认入图 / built 已建设
                   CHECK (status IN ('pending','confirmed','built')),
    built_kp_id    INTEGER,                             -- 已建设知识点指向（演化锚点 D21；跨模块逻辑引用不设 FK）
    judge_basis    TEXT,                                -- 聚合依据：structural 结构强命中 / exact 极高相似 / llm LLM 精判（人工抽检的过滤维度）
    created_at     TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at     TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_kp_canonical_cluster ON kp_canonical (cluster_id);
CREATE INDEX IF NOT EXISTS idx_kp_canonical_status ON kp_canonical (status);
```

### 3.2 后端：domain / repo

**新建** `pipeline/domain/CanonicalStatus.java`：`@ConstantGroup("canonical_status")`，`PENDING("pending","AI 聚合待确认")` / `CONFIRMED("confirmed","人工确认入图")` / `BUILT("built","已建设成知识点")`，`fromCode` 照 CandidateStatus 模式。

**新建** `pipeline/domain/KpCanonical.java`：MyBatis-Plus 实体，字段同 DDL。

**新建** `pipeline/repo/KpCanonicalMapper.java`：BaseMapper + 自定义聚合查询（覆盖率按簇 GROUP BY）。

**修改** `pipeline/repo/KpCandidateMapper.java`：补 `selectUnabsorbedPending()`（status=pending AND canonical_id IS NULL）与按 canonical 批量查/清。

### 3.3 后端：kb.api 服务级接口（跨模块补口）

**新建** `kb/api/KbClusterApi.java`（接口）：

```java
public interface KbClusterApi {
    long createDraft(Long domainId, String name, String description);  // 校验 active 域，status=draft 落表
    void merge(long sourceId, long targetId);                          // 复用 ClusterService.merge 语义
    List<DomainOption> listActiveDomains();                            // AI 命名建议的域约束
    List<KpTitleRef> listActiveKpTitles();                             // 知识点映射比对用（id + title）
    record DomainOption(long id, String name) {}
    record KpTitleRef(long id, String title) {}
}
```

**新建** `kb/service/KbClusterApiImpl.java`：委托 ClusterService 与既有 Mapper 查询（active 域清单复用 meta 既有查询；无则补一条 selectList）。repo 不出模块红线不破——pipeline 只见接口。

### 3.4 后端：归一引擎（pipeline 核心）

**新建** `pipeline/service/NormalizationFunnel.java`（纯计算，无 Spring 依赖，便于单测）：

- 输入：散题列表（id/title/sectionPath/docId/answerEvidence）、既有 canonical 列表（id/title）、题面→向量映射、active 知识点题面向量。
- 阶段①**结构信号投票**（免费）：
  - 强信号 `structural`：同 doc + 同 section_path + 标题归一化（去空白/标点/全半角）相等 → 归并边；
  - 弱信号：跨 doc 的 section_path 末段 token 归一化相等 → 归并边（组置信取最低，仅作粗聚）。
- 阶段②**embedding 召回**：题面余弦相似度——`≥ EXACT(0.92)` → 归并边（`exact`，跳过 LLM）；`[SUGGEST(0.82), 0.92)` → 中间带送 LLM；散题对既有 canonical 同口径比对（吸收挂靠）。
- 阶段③**LLM 精判**（`CLUSTER_DEDUP`，completeJson）：中间带对送「题面A+答案要点A vs 题面B+答案要点B」→ `{same, reason}`；same=true → 归并边（`llm`）。
- **Union-Find** 合并所有边成组；**组置信 = 组内最强边**（structural 强/exact → confirmed；llm / 弱结构 → pending）。
- 单成员组不动（留在候选池散题）；canonical 之间互不重聚（已聚合层稳定，只吸收新散题）。
- 阈值常量集中本类（调参=发版，与「Prompt 不在线编辑」同口径）。

**新建** `pipeline/service/CanonicalService.java`（编排 + 落库，`@Transactional` 整体一个事务，LLM 调用入事务为 T6 SplitService 既有先例）：

- `run()`（增量归一，幂等——重复 run 只处理未吸收散题）：
  1. 取未吸收 pending 散题 + 全部既有 canonical；embedAll（`EMBEDDING`，profileId=0）题面——**向量只活在本次 run 内存里，不落任何存储**（PRD 九-3 字面口径；每次 run 只 embed 本轮散题 + 既有 canonical 题面，量级可控）；
  2. 跑漏斗 → 新组建 canonical（title=组内覆盖源数最多成员题面、coverage_count=去重 doc 数、status/judge_basis 按组置信），散题挂 canonical_id；吸收进既有 canonical 的只重算 coverage_count；
  3. **知识点轻量映射**：极高相似（exact 阈值）命中 active 知识点题面 → canonical 直接 `built` + built_kp_id（覆盖率「已建设」口径含样板期内容；映射关系在地图页可见，人工拆回可纠错）；
  4. **语料簇聚类**：对无簇归属的 canonical 按 section_path 首段众数 + 题面相似（`CLUSTER_SIM(0.60)`）聚组 → 经 `KbClusterApi.createDraft` 建 draft 簇（域建议：section_path 首段与 active 域名匹配，失配落第一个 active 域）→ 回写 cluster_id；
  5. **AI 命名建议**：一次批量调用（新簇的 canonical 题面样例 + 章节路径 + 域名清单 → `[{index, name, domainName}]`，**prompt 返回域名由后端映射 ID**，防 LLM 编 ID）；失败降级 = 簇名取组内最高频题面截断。返回 run 结果摘要（新建归一题数 / 吸收数 / 建簇数 / 映射已建设数）。
- `confirm(id, clusterId?)`：pending → confirmed（built 拒绝 409）；可顺带调簇。
- `split(id, mode, candidateIds?)`：**拆分回退**（误归并兜底，验收硬指标）——built 拒绝 409；`all` = 整题解散（canonical 物理删——语料工作草稿允许，附录 A 仅 built 保留为锚点），成员候选 canonical_id=null 回散题；`partial` = 指定候选拆出，coverage 重算；**剩余 0 条或（pending 态剩余 1 条）→ 解散**。
- `moveCluster(id, clusterId?)`：调整簇归属。
- `mergeClusters(sourceId, targetId)`：先移 canonical 归属再调 `KbClusterApi.merge`（同一事务，防孤儿指向）。
- `coverage()`：页头统计 {domainCount, clusterCount(挂有 canonical 的 draft 簇), canonicalCount, builtCount, pendingCount} + 簇排行 [{clusterId, name, domainName, canonicalCount, coverageSum, builtCount}]（coverageSum 倒序 = 按簇建设排队依据）。
- `tree(domainId?)` / `canonicals(clusterId?, status?)` / `detail(id)`：三层视图数据；detail 含源候选列表（源问法 / 答案证据预览 / sectionPath / docFileName+sourceRef / docId 供回跳）。

**Prompt 模板**（`resources/prompts/`，强制 JSON + few-shot 一例）：
- `canonical_judge.md`：同题精判（few-shot 用 PRD 原例：「HashMap 扩容机制」vs「HashMap 树化阈值」→ same=false）；
- `cluster_name.md`：批量簇命名与域归属建议。

### 3.5 后端：接口层 + 既有约束修补

**新建** `pipeline/api/KnowledgeMapController.java`（`/api/admin/knowledge-map`，员工会话由拦截器 `/api/admin/**` 汇总行覆盖，权限矩阵零改动）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/run` | 运行增量归一（返回 run 摘要） |
| GET | `/coverage` | 覆盖率统计条 + 簇排行 |
| GET | `/tree` | 域 → 簇层级（含计数） |
| GET | `/canonicals` | 归一题列表（clusterId / status 过滤，覆盖源数倒序；未归簇用 clusterId=none） |
| GET | `/canonicals/{id}` | 详情 + 源候选列表（溯源） |
| POST | `/canonicals/{id}/confirm` | 确认同题（body 可带 clusterId） |
| POST | `/canonicals/{id}/split` | 拆分回退（body: mode all/partial + candidateIds） |
| POST | `/canonicals/{id}/cluster` | 调整簇归属 |
| POST | `/clusters/{id}/merge` | 簇合并编排（body: targetId；前端调此接口而非 kb 直连） |
| POST | `/clusters/{id}/suggest-name` | AI 命名建议（**只建议不落库**，人工确认后走既有 `PUT /api/admin/clusters/{id}`） |

**修改** `pipeline/service/DocService.java`：删除校验扩展——候选 `canonical_id IS NOT NULL`（即使 pending）也拒删（409，文案指向知识地图拆回），防覆盖源数统计被孤儿化。

### 3.6 前端

**修改** `frontend/src/api/types.ts`：
- `export type CanonicalStatus = 'pending' | 'confirmed' | 'built'`；
- `CanonicalItem`（id/title/clusterId/coverageCount/status/judgeBasis/builtKpId）、`CanonicalDetail`（含 sources: 候选溯源列表）、`CoverageView`（统计条 + 簇排行）、`TreeItem`（域 → 簇）、`RunSummary`；`LlmPurpose` 零改动（cluster_dedup / embedding 已在）。

**修改** `frontend/src/api/pipeline.ts`（知识地图同属 pipeline 模块，追加而非新建文件）：上述 10 个接口的调用函数。

**重写** `frontend/src/views/admin/KnowledgeMapView.vue`（替换占位页）：
- **页头统计条**：域数 / 簇数 / 归一题数 / 已建设 / 待确认 + 「运行归一」按钮（run 摘要结果提示：新建 N · 吸收 N · 建簇 N · 映射已建设 N）。
- **三层视图**：左侧域列表（计数）→ 中列簇卡片（簇名 / 域 tag / 归一题数 / 覆盖源合计 / 已建设数，按排行序；簇操作：AI 命名建议→确认后调既有簇更新接口、改名、调整域、合并（选目标簇）、「待聚簇」入口）→ 右侧归一题表（默认覆盖源数倒序；列：题面 / 覆盖源数 / 状态 tag / 聚合依据 / 操作）。
- **归一题抽屉**：源候选列表（各源问法、答案证据预览、形态、章节路径、出处文件名 + sourceRef）+「回跳素材原文」（`/admin/docs?docId=`）；操作：确认同题（可调簇）/ 拆分（对话框：整题解散 or 勾选部分候选拆出，文案注明「拆出的候选退回候选池重新研判」）/ 调整簇归属。
- built 归一题显示指向的知识点（builtKpId），不可确认/拆分（后端 409 拦截 + 前端置灰）。

**修改** `frontend/src/views/admin/DocView.vue`：支持 `?docId=` 进入时自动打开该文档预览抽屉（溯源回跳落点，约 5 行）。

### 3.7 测试

**新建** `src/test/java/com/zhiji/pipeline/ScriptedLlmTransport.java`（测试专用 @Primary 网络替身）：chat 按调用序/内容返回脚本 JSON；embed 支持按文本映射预设向量（可控余弦）。

**新建** `src/test/java/com/zhiji/pipeline/NormalizationFunnelTest.java`（纯单测）：标题归一化、强/弱结构信号、余弦分带（exact / suggest / below）、Union-Find 组装、组置信判定、剩余 1 条解散规则。

**新建** `src/test/java/com/zhiji/pipeline/CanonicalPipelineTest.java`（MockMvc 集成，照 DocPipelineTest 模式）：
1. 拆题后 run：同 doc 同 section 同题强结构 → canonical(confirmed, structural)；跨 doc 中间带 + LLM stub same=true → canonical(pending, llm)；same=false 不归并；
2. exact 阈值归并跳过 LLM；吸收既有 canonical：二次 run 新散题挂靠、coverage 重算；
3. 知识点映射：active kp 题面极高相似 → built + builtKpId；覆盖率「已建设」计数含它；
4. 语料簇：建 draft 簇、命名建议 stub 生效（域名→ID 映射）、回写 cluster_id；
5. confirm / partial·all 拆分回退（候选回散题、canonical 删除）/ built 拆分 409 / 调簇 / 簇合并（canonical 指向新簇、源簇 retired）；
6. coverage 统计数字与排行序；重复 run 幂等不重建；
7. 素材删除：候选被吸收后拒删（409）；
8. 鉴权：客户 token 打 /api/admin/knowledge-map 401。

**前端**：`npm run build`（vue-tsc 类型检查）为门槛。

### 3.8 文档收尾（任务约定动作）

- `docs/开发计划.md`：T7 条目打 ✅ + 实现记录（照 T4/T5/T6 格式）。
- `CLAUDE.md`「当前任务」行纠偏：T5 表述已过时（T5/T6 已完成），更新为「T8 按簇成稿（T0-T7 已完成）」。

## 四、Assumptions & Decisions

| # | 决策 | 依据 |
|---|---|---|
| 1 | 范围严格按开发计划 T7 明细：按簇成稿触发、候选池研判页接线随 T8 | 用户确认推荐项；开发计划任务边界 |
| 2 | T7 做轻量知识点映射：run 时 exact 阈值命中 active kp → canonical 标 built 指向；变体吸收/冲突复核工作流随 T8 成稿与 T9 校对 | 用户确认推荐项；知识库PRD 九-3；否则覆盖率「已建设」漏计样板期内容 |
| 3 | 向量只在单次 run 进程内存在，不落任何存储；每次 run 只 embed 本轮散题 + 既有 canonical 题面 | 用户确认推荐项；PRD 九-3 字面（进程内缓存不落专用存储）；万级以内暴力余弦毫秒级 |
| 4 | 相似度阈值（0.92/0.82/0.60）写死在 NormalizationFunnel 常量，调参=发版 | 「Prompt 模板不做在线编辑」同口径（B端PRD 十）；避免为调参扩 sys_config 跨模块读 |
| 5 | 拆分解散 = 物理删 canonical 行（pending/confirmed 可拆、built 不可拆 409） | 附录 A：仅 built「保留为演化锚点」；语料层是工作草稿允许激进操作 |
| 6 | 被吸收候选保持 pending + canonical_id 非空，状态机零新增值 | B端PRD 7.1「两页共享同一套状态机」+ 附录 A 枚举已定稿 |
| 7 | 归一触发 = 地图页手动「运行归一」（增量），导入后不自动跑 | D22 先例（反推出题按需触发，B 端成本大头人工控制）；「增量归一，地图自动更新」语义=增量处理后地图即最新 |
| 8 | 新接口全部落 `/api/admin/**` 汇总行，权限矩阵零改动 | 基础架构PRD 3.3 既有登记 |
| 9 | canonical_status 三处同步：DB CHECK（新表可加）+ `@ConstantGroup` 自动下发 + types.ts | T0 常量规范；对比 llm_usage_daily.purpose 无 CHECK 的原因（旧表改不了约束）在此不适用 |
| 10 | LLM 精判与命名共用 `CLUSTER_DEDUP` purpose，embedding 用 `EMBEDDING`；均 B 端入口不限额 | LlmPurpose 既有定义（T2 为 T7 预留）；D32 |
| 11 | AI 命名建议「只建议不落库」，采纳走既有簇更新接口 | B端PRD 九「AI 的簇命名与域归属建议可一键采纳」——采纳动作留人 |
| 12 | canonical 之间互不重聚（只吸收新散题） | 已聚合层保持稳定，避免每次 run 全图重排 |

## 五、实施顺序（执行时用 TodoWrite 跟踪）

1. 建表 schema.sql + CanonicalStatus / KpCanonical / KpCanonicalMapper / KpCandidateMapper 扩展
2. kb.api.KbClusterApi + KbClusterApiImpl
3. NormalizationFunnel 纯计算 + 单测
4. Prompt 模板（canonical_judge / cluster_name）
5. CanonicalService（run / confirm / split / moveCluster / mergeClusters / coverage / tree / detail）
6. KnowledgeMapController + DocService 删除约束扩展 → ScriptedLlmTransport + CanonicalPipelineTest
7. 前端 types.ts / pipeline.ts / KnowledgeMapView 重写 / DocView 深链
8. 全量验证：`mvn clean compile` → `mvn test` → `cd frontend && npm run build` → 冒烟清单
9. 文档收尾：开发计划 T7 打勾 + CLAUDE.md 当前任务纠偏

## 六、Verification（验收清单）

- **对齐 B端管理台PRD 十四-2 之归一部分**：
  - 冒烟路径（开发期用 docs/题库源/JavaGuide 若干 md 导入）：导入 → 拆题 → 「运行归一」→ 三层视图浏览（域 → 簇 → 归一题，覆盖源数倒序）→ 归一题抽屉看 N 条源候选 → 确认同题 / 误归并拆分回退（候选回到候选池散题态）→ 簇操作（AI 命名建议采纳、合并、调整域）→ 覆盖率统计条数字正确。
  - **自动聚合准确率抽检 ≥85%**：抽检confirmed 归一题人工核对（judge_basis 过滤逐档抽查），记录抽检结果。
  - **误归并可拆回**：partial / all 拆分全程可走通，拆出候选 canonical_id 置空、状态回 pending。
- **增量吸收**：二次导入拆题后 run，新散题挂靠既有 canonical、coverage 重算、幂等不重建。
- **回归**：`mvn test` 全绿（既有 DocPipelineTest / kb / llm / account 用例不受影响）；`npm run build` 绿；候选池占位页与 T8 边界不受影响。
