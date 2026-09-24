# T4 知识库模块（kb）Spec

## Why

T4 是产品核心资产（知识点与主题簇的内容模型、B 端维护闭环、10 样板数据上线），是 M1 内容生产闭环的地基。T3（技能库）按拍板跳过：其硬前置「技能域字典」按用户决策**先建表、管理功能后续补**（T3 时实现），种子域数据随样板配套预置。本 spec 依据：开发计划 T4 明细、知识库PRD 四/五/4.7/8.1、核心模块定义 3.2 与附录 A、B端管理台PRD 第五节与十四-1。

## What Changes

- **建表**（schema.sql 幂等追加，外键开启）：`skill_domain`（仅 DDL + 种子，管理接口随 T3）、`kp_cluster`、`kp_main`、`kp_prereq`、`kp_freq_signal`
- **后端新增 kb 包四层**（api/service/domain/repo）：簇 CRUD/合并/状态机、知识点全结构 CRUD、追问层管理（layer_key 红线）、评分要点结构校验、职级要求与停止点、前置挂载防环、下线与合并、复核到期字段、频率信号人工标定、题面模糊搜索与筛选
- **常量组**：新增 `content_status` / `point_level` / `freq_source` / `domain_status`，后端补 `sub_type` 枚举——三处同步（CHECK 约束、后端 @ConstantGroup 枚举、前端 types.ts）
- **前端**：KpManageView 全量接线（列表检索筛选、簇管理、知识点编辑、下线合并）；共用 Markdown 渲染组件（代码高亮 / 表格 / Mermaid，禁 HTML 防注入）；新增 `api/kb.ts`；SkillsView 保持占位不动（域管理随 T3）
- **样板种子**：`sample-data.sql` 幂等种子（配套域 + ES 写入链路簇 + 10 个样板知识点：8 默认八股 + 1 项目叙事 + 1 设计演进链，各含 2 个职级档位停止点、三档 Markdown 样例、变体、追问链），配套测试自动校验种子过结构校验
- **依赖引入**（前端）：`markdown-it` + `highlight.js` + `mermaid`
- 无 **BREAKING** 变更（全新模块，既有接口不动）

## Impact

- Affected specs：无既有 spec（本项目首个）
- Affected code：
  - `src/main/resources/db/schema.sql`（追加 5 表）、新增 `src/main/resources/db/sample-data.sql`
  - 新增 `src/main/java/com/zhiji/kb/**`（四层）；拦截器与权限矩阵无需改动（kb 接口全部落在 `/api/admin/**` 既有规则内）
  - `frontend/src/views/admin/KpManageView.vue`（占位重写）、新增 `frontend/src/api/kb.ts`、新增 `frontend/src/components/MarkdownView.vue` 与 `MarkdownEditor.vue`、`frontend/src/api/types.ts`（补充枚举与视图类型）、`frontend/package.json`（3 个渲染依赖）
  - 新增 `src/test/java/com/zhiji/kb/**` 测试

## 范围外（本 spec 不做）

- **T5 知识卡分享**：`GET /api/share/kp/{kpId}` 公开接口与 ShareView 卡图——独立任务
- **域字典管理功能**：域 CRUD 接口与 SkillsView 接线随 T3；T4 仅落 DDL + 种子数据 + kb 内只读元数据接口（供下拉）
- **复核到期 scheduler 扫描**与校对流转页（T9）；C 端卡面预览（T9）
- **管道侧**：素材库/候选池/知识地图（T6-T8）、kp_cluster 的 draft 态兼作语料聚合簇（T7，D21）
- **个人态引用检查的实查询**：知识点合并的前置检查（user_kp_state / user_kp_signal）随 T10+ 落地后接入；T4 阶段个人态表不存在，检查恒通过，接口结构预留
- C 端消费接口（搜索的 C 端形态、关卡裁剪数据）随 T11-T16 定义

## ADDED Requirements

### Requirement: 表结构与枚举契约

系统 SHALL 在 schema.sql 幂等追加 5 张表（`CREATE TABLE IF NOT EXISTS`，依赖顺序 skill_domain → kp_cluster → kp_main → kp_prereq / kp_freq_signal，连接串外键已开启）：

- `skill_domain`：name（UNIQUE）、description、sort_order、status（CHECK domain_status）、时间戳。T4 不提供管理接口。
- `kp_cluster`：domain_id（FK → skill_domain）、name、description、zero_shot_md 与 reference_solution_md（从零讲/完整参考方案，Markdown TEXT）、status（CHECK content_status）、merged_into（可空自引用 FK，retired 指向）、last_reviewed_at、review_due_at（实列 + 索引，SQLite 陷阱 E）、时间戳。
- `kp_main`：cluster_id（FK → kp_cluster，挂且只挂一个主簇）、sub_type（CHECK）、title（题面，纯文本）、variants（JSON 数组）、narrative_framework（叙述框架，Markdown TEXT）、followups（JSON：追问层 [{layerKey, dir, text, expect, stopCondition, status}]）、rubric（JSON：按层组织评分要点 [{layerKey, points: [{pointId, level, text}]}]）、samples（JSON：三档样例 {pass, good, excellent}，Markdown TEXT）、level_req（JSON：五档 {required, stopLayer}）、baseline_levels（实列 TEXT，已标职级基线档位列表，用于筛选）、difficulty（CHECK 1~5）、status（CHECK content_status）、merged_into（可空自引用 FK）、last_reviewed_at、review_due_at（实列 + 索引）、时间戳。索引：cluster_id、status、review_due_at。
- `kp_prereq`：kp_id（FK）+ prereq_kp_id（FK），UNIQUE(kp_id, prereq_kp_id)，反查索引。
- `kp_freq_signal`：kp_id（FK）、source（CHECK：manual/interview/coverage）、signal_level（可空 CHECK：high/mid/low，人工标定档位）、count（INTEGER，面经次数/覆盖源数）、note（理由/来源岗位等）、created_at。

枚举三处同步（CLAUDE.md 通用约定）：`sub_type`（附录 A 既有取值，后端补枚举）、`content_status`（draft/reviewed/active/retired）、`point_level`（basic/mid/senior，与职级五档是两套刻度）、`freq_source`（manual/interview/coverage）、`domain_status`（active/disabled）——后端枚举标注 @ConstantGroup 自动下发，前端 types.ts 同步类型联合。

#### Scenario: 重复启动幂等
- **WHEN** 应用多次启动执行 schema.sql
- **THEN** 无报错、无重复建表，既有数据不受影响

#### Scenario: 常量组下发
- **WHEN** 前端拉取 `GET /api/constants`
- **THEN** 包含 sub_type / content_status / point_level / freq_source / domain_status 五组，取值与 CHECK、types.ts 一致

### Requirement: 技能域数据前置（T3 跳过期的最小边界）

- kb 模块 SHALL 提供只读元数据接口（如 `GET /api/admin/kp/meta`）返回域列表（含域下非 retired 知识点计数）与簇下拉数据，供筛选器与表单使用；**不得**实现域的写接口
- 建簇 SHALL 必须选择 active 状态的域；域缺失或停用时给出可读错误
- 种子数据 SHALL 预置样板所需的域（如 Elasticsearch、Java 后端）

#### Scenario: 域为空时引导
- **WHEN** 库中无任何域（种子未导入）
- **THEN** KpManageView 提示先由 T3 域管理或种子数据建立域，不阻塞簇/知识点功能的渲染

### Requirement: 主题簇管理

- 建簇 / 改簇：簇名、所属域、描述、从零讲与完整参考方案（Markdown 编辑 + 实时预览）
- 簇与知识点走同一套 content_status 状态机（draft → reviewed → active → retired）
- **合并簇**：目标簇保留，被并簇转 retired 并保留 merged_into 指向，簇下知识点主簇归属整体迁移到目标簇（只改归属不改内容）
- 不提供删除；簇先于知识点存在（kp_main 外键决定）
- 簇编辑页展示簇下知识点清单（共享范围提示）

#### Scenario: 合并簇
- **WHEN** 管理员将簇 A 合并到簇 B
- **THEN** A 转 retired 且 merged_into 指向 B，A 名下全部知识点 cluster_id 更新为 B，B 状态与内容不变

### Requirement: 知识点内容模型与 CRUD

知识点结构：题面（纯文本）、问法变体（预存 2~3 条，上限 5，同题其他问法非独立知识点）、叙述框架（Markdown）、追问链、分级评分要点、三档标准回答样例（Markdown）、职级要求、难度 1~5（对应卡面白/绿/黄/橙/红五色）、主簇、前置依赖、频率信号。

- 新建 SHALL 落 draft；编辑保存全结构；列表支持题面模糊搜索（LIKE，复盘录题 T16 复用同源能力）与筛选（域 / 主簇 / sub_type / 内容状态 / 难度 / 是否已标职级基线 / 复核是否到期）、分页
- 列表列：题面、技能域、主簇、sub_type、难度五色、状态、最近校对时间、复核到期时间
- sub_type=narrative 的库侧承载与 default 同构（题面模板 + 指标清单评分要点，不含任何个人内容），不为其单设校验分支

#### Scenario: 检索命中
- **WHEN** 以关键词搜索题面
- **THEN** 返回题面命中（模糊匹配）的知识点分页列表，筛选项叠加生效

### Requirement: 追问层管理与 layer_key 红线（领域红线 2）

- `layer_key` 是追问层的稳定身份：**永不复用、永不修改，禁止用数组下标作身份**；数组顺序只作展示排序
- 主问题层身份固定为 `main`、dir 固定 main（由题面承载，不占 followups 数组）；追问层 layer_key 采用 `L1`、`L2`… 递增分配，分配取历史最大序号 +1（**含停用层占号**）
- 层的增减只走「新增层 / 停用层」两个独立动作；停用层在 followups 中保留（status=disabled），其 layer_key 不得再分配给新层
- 全结构编辑保存时 SHALL 做 layer_key 白名单校验：提交数据中的 layerKey 集合必须与库中既有集合一致（防止绕过新增层接口伪造身份）；层文本、顺序、dir（主问题层除外）、期望与停止条件随时可改

#### Scenario: 停用层不复用
- **WHEN** 知识点已有 L1、L2，停用 L2 后新增一层
- **THEN** 新层分配 L3（而非复用 L2），L2 保留原身份与文本

#### Scenario: 白名单拒绝伪造身份
- **WHEN** 编辑保存请求携带库中不存在的 layerKey
- **THEN** 保存被拒绝（400），提示必须走新增层接口

### Requirement: 评分要点与结构校验

- 评分要点按层组织、带三级分级（basic/mid/senior）；rubric.layerKey 必须是 `main` 或 followups 中任一既有层 key；每条要点带稳定 pointId（前端新要点不带 ID，后端分配；编辑保留既有 ID）——判定快照（附录 A 8.3）引用 pointId
- 保存前系统做结构校验：层引用完整、level 取值合法、samples 三档 key 齐全、level_req 五档 key 齐全（required=true 时 stopLayer 必填且取值为 main 或既有层 key）、变体条数 ≤5 且去重、layerKey 唯一
- 「要点可判定性」是人工校对的事（T9），系统只管结构完整性
- 校验失败返回 400 + 字段级错误信息（复用统一错误体 fields）

#### Scenario: 层引用缺失
- **WHEN** rubric 引用了不存在的 layerKey
- **THEN** 保存被拒，错误信息指明缺失的层

### Requirement: 职级要求与停止点

- 五档职级（junior/mid/senior/staff/expert）分别设置是否必问（required）与下钻停止层（stopLayer，取值 main 或任一层 key）
- 保存时同步重算 `baseline_levels` 实列（已标档位列表），支撑「是否已标职级基线」筛选——这是 T9 校对完整性检查的数据基础
- stopLayer 指向的层序即「讲到第 N 层算够」的目标刻度，渲染映射由消费方（C 端）负责

#### Scenario: 基线筛选
- **WHEN** 按「未标职级基线」筛选
- **THEN** 返回 baseline_levels 为空的知识点列表

### Requirement: 前置依赖与防环

- 前置可多选挂载、可跨簇、每个知识点 0~多个（多数 0~2，不硬凑）；kp_prereq UNIQUE 约束兜底
- 保存时对全库前置关系做环检测，成环 SHALL 拒绝保存并在错误信息中展示环路径（A → B → C → A）
- retired 知识点不得被挂为前置（候选与校验双重限制）

#### Scenario: 成环拒绝
- **WHEN** 将 A 的前置设为 B，而 B 的前置链已包含 A
- **THEN** 保存被拒，错误信息含完整环路径

### Requirement: 内容状态机与下线合并（不校不上线红线）

- 状态流转单向：draft → reviewed → active → retired；**draft 不得直达 active**（不校不上线）；非法流转返回 400
- active 内容修订：原地保存、状态不变、复核到期重置（last_reviewed_at = now，review_due_at = now + 90 天，周期为常量、T9 接 scheduler 时再评估挪 sys_config）；reviewed 内容编辑状态不变
- 知识点无物理删除，只有**下线（retired）**：必须填写去向——废弃，或合并到某知识点
- **知识点合并**：本条转 retired 并保留 merged_into 指向；变体清单迁入目标（去重）；追问链与评分要点**不自动迁移**；合并前做个人态引用检查（T4 阶段恒通过，接口结构预留）
- 簇级内容同状态机；簇合并语义见主题簇管理 Requirement

#### Scenario: 不校不上线
- **WHEN** 请求将 draft 知识点直接置为 active
- **THEN** 被拒绝（400），提示先经 reviewed

#### Scenario: 合并迁移变体
- **WHEN** 知识点 X 合并到 Y
- **THEN** X 转 retired、merged_into=Y；X 的变体并入 Y（重复项去重）；X 的追问链与评分要点原样保留在 X 上不迁移

### Requirement: 频率信号

- 人工标定入口：在知识点编辑内标定频率档位（high/mid/low）与一句理由，写 kp_freq_signal（source=manual）
- 面经信号（interview）与题库覆盖（coverage）随管道 T6/T7 写入，T4 仅落表结构与枚举、详情只读展示既有信号
- 聚合分不落库，消费时推导（知识库PRD 8.4）

#### Scenario: 人工标定
- **WHEN** 在编辑页标定某知识点为「高频」并填理由
- **THEN** 写入一条 source=manual 的信号记录，详情页可见

### Requirement: Markdown 白名单渲染组件

- 共用组件 `MarkdownView`：受控白名单渲染——代码块（带语言高亮）、表格、Mermaid 流程图/架构图、行内代码、列表与标题；**禁入 HTML 标签**（渲染器 html:false 防注入），仅 Markdown 语法
- `MarkdownEditor`：编辑 + 实时预览（代码高亮 / 表格 / Mermaid），用于从零讲、完整参考方案、叙述框架、三档样例四个 Markdown 白名单字段
- Mermaid 渲染失败 SHALL 降级展示源码与错误提示，不白屏
- C 端与 B 端共用同一组件；图片边界：渲染支持图片语法（B 端自制图），T4 不做上传功能

#### Scenario: 注入防护
- **WHEN** Markdown 内容含 `<script>` 或 HTML 标签
- **THEN** 按纯文本展示，不执行、不渲染为 HTML

#### Scenario: Mermaid 降级
- **WHEN** Mermaid 代码块语法非法
- **THEN** 该代码块位置展示错误提示与源码，页面其余部分正常渲染

### Requirement: 权限

- kb 全部接口路径落在 `/api/admin/kp/**` 与 `/api/admin/clusters/**`，按权限矩阵既有 `/api/admin/**` 行执行：员工会话（admin / editor 均可），客户 token 与匿名请求 401；拦截器无需改动
- 全部写接口经 AuthContext 记录操作人（操作审计字段随 D19 触发再加，T4 不落库）

#### Scenario: 客户 token 被拒
- **WHEN** 携带客户 token 请求 `/api/admin/kp`
- **THEN** 返回 401

### Requirement: 样板数据（知识库PRD 12.1 阶段 2）

- 新增 `src/main/resources/db/sample-data.sql`（**不**挂入 spring.sql.init 自动执行，手工/验收时导入），幂等写入（防重复导入）
- 内容：配套技能域（至少含 Elasticsearch、Java 后端）+ ES 写入链路簇 + 10 个样板知识点——8 个默认八股（每条：难度、评分要点带层身份与分级、三档 Markdown 样例【至少一处覆盖代码块/表格/Mermaid 各类语法】、2~3 个问法变体、追问链带停止条件）、1 个项目叙事（六要素模板骨架）、1 个设计演进链（条件升级语义追问链）；**每条至少标定 2 个职级档位停止点（stop_layer），基础关卡组按中级（mid）标定组成**
- 状态全部为 active（模拟已过校对上线），last_reviewed_at / review_due_at 齐备
- 测试 SHALL 读取该种子灌入测试库，断言：10 条知识点全部通过后端结构校验、2 档停止点齐备、域/簇外键完整

#### Scenario: 种子合规
- **WHEN** 测试执行种子导入并运行结构校验
- **THEN** 10 条知识点全部通过，且每条 baseline_levels 至少含 2 个档位

### Requirement: 测试与验收命令

- 后端测试（mvn test，Spring Boot Test + SQLite 测试库、不 mock 数据层）覆盖：簇与知识点 CRUD 闭环、layer_key 停用不复用与白名单、结构校验拒绝、防环、状态机与不校不上线、合并变体迁移、检索筛选、种子数据合规、权限（客户 token 401）
- 验收命令：`mvn clean compile`、`mvn test`、`cd frontend && npm run build` 全绿；前端无 lint 命令（不运行 npm run lint）
