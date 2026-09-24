# Tasks — T4 知识库模块（kb）

> 执行约定：遵循 CLAUDE.md（四层分层、跨层禁反向调用、不写注释除非记录设计意图/坑、构造器注入、SQLite 陷阱、Boot 4 写法、TS 5.8.3 不升级）。设计真相源为 docs/ 三份核心文档与本目录 spec.md；字段级 DDL 落 schema.sql 时定稿。每任务完成即勾选。

- [x] Task 1: 建表与常量契约（spec：表结构与枚举契约）
  - [x] 1.1 schema.sql 追加 5 表：skill_domain → kp_cluster → kp_main → kp_prereq / kp_freq_signal（依赖顺序建 FK，CREATE TABLE IF NOT EXISTS 幂等，CHECK 约束对齐附录 A 8.2 取值，review_due_at 实列 + 索引，kp_prereq UNIQUE 对，baseline_levels 实列）
  - [x] 1.2 新建 kb 包 domain 层枚举：SubType / ContentStatus / PointLevel / FreqSource / DomainStatus（@ConstantGroup 注册，实现 ConstantItem，组名与附录 A 8.2 / spec 一致）
  - [x] 1.3 前端 types.ts 同步：新增 FreqSource / DomainStatus 类型联合与 kb 相关视图类型骨架（KpFollowup / KpRubricPoint / KpLevelReq / KpSampleSet 等）
  - [x] 1.4 验证：`mvn clean compile` 绿；启动日志无 schema 报错（可借助现有测试启动）

- [x] Task 2: kb 后端——簇管理（spec：主题簇管理；依赖 Task 1）
  - [x] 2.1 domain/repo：KpCluster 实体 + ClusterMapper（BaseMapper 即可）
  - [x] 2.2 service：ClusterService——建簇/改簇（校验域存在且 active）、状态流转（draft→reviewed→active 单向校验）、合并簇（retired + merged_into + 簇下知识点归属整体迁移，事务边界在此层）
  - [x] 2.3 api：ClusterAdminController（/api/admin/clusters：列表含簇下知识点数 / 新建 / 更新 / 状态 / 合并），record + Bean Validation，只做参数校验与 DTO 转换
  - [x] 2.4 测试：簇 CRUD 与状态机、合并簇归属迁移、域校验、权限（匿名/客户 token 401）

- [x] Task 3: kb 后端——知识点全功能（spec：知识点 CRUD / 层管理 / 要点校验 / 职级 / 防环 / 状态机合并 / 频率信号 / 检索；依赖 Task 2）
  - [x] 3.1 domain/repo：KpMain / KpPrereq / KpFreqSignal 实体 + Mapper（JSON 字段以 TEXT 承载，序列化用 Jackson 3 `tools.jackson`）
  - [x] 3.2 结构校验器：layer_key 白名单、rubric 层引用与分级、samples/level_req 键齐全、变体去重上限、KB_* 错误码 + fields
  - [x] 3.3 层分配器：新增层 L{n} 递增（含停用占号）、停用层动作独立接口
  - [x] 3.4 防环检测：全库前置图 DFS，成环返回环路径；retired 不可作前置
  - [x] 3.5 KpService：新建/编辑保存（active 修订重置 review_due_at = now+90d）/ 状态流转（draft 不得直达 active）/ 下线必填去向 / 合并（变体迁移去重、merged_into、个人态检查预留点）/ baseline_levels 同步 / 频率信号人工标定（manual）/ 检索筛选分页（题面 LIKE + 七项筛选）/ meta 接口（域只读列表含知识点计数 + 簇下拉）
  - [x] 3.6 api：KpAdminController（/api/admin/kp 全套路由，见 spec 接口清单）
  - [x] 3.7 测试：CRUD 闭环、停用层不复用、白名单拒绝、结构校验拒绝、成环拒绝含路径、不校不上线、合并迁移、检索筛选、baseline 筛选、频率信号、meta、权限

- [x] Task 4: 前端基础设施（spec：Markdown 白名单渲染；可与 Task 2/3 并行，依赖 Task 1 的 types.ts）
  - [x] 4.1 package.json 引入 markdown-it + highlight.js + mermaid（含必要的 @types）
  - [x] 4.2 components/MarkdownView.vue：html:false 渲染、代码高亮、表格、Mermaid 异步渲染失败降级源码+提示
  - [x] 4.3 components/MarkdownEditor.vue：编辑/预览双区，白名单四字段复用
  - [x] 4.4 api/kb.ts：按后端模块组织封装 Task 2/3 全部接口
  - [x] 4.5 验证：`cd frontend && npm run build` 绿

- [x] Task 5: 前端 KpManageView 全量接线（spec：检索筛选 / 簇管理 / 知识点编辑 / 下线合并；依赖 Task 3 + 4）
  - [x] 5.1 列表区：题面搜索、七项筛选、分页、难度五色、状态与复核到期列
  - [x] 5.2 簇管理区：建簇/改簇对话框（域下拉、空域引导提示）、合并簇、簇级从零讲与参考方案 MarkdownEditor 编辑、簇下知识点清单
  - [x] 5.3 知识点编辑抽屉：分区块表单（题面与变体 / 追问链【新增层/停用层/排序/方向】/ 评分要点与三档样例【MarkdownEditor】/ 职级要求五档 required+stopLayer / 难度与前置多选 / 主簇归属 / 频率信号标定）
  - [x] 5.4 下线与合并对话框（去向二选一：废弃 / 合并目标选择）；active 保存时提示"本次改动影响未来判定，历史判定已快照"
  - [x] 5.5 验证：`npm run build` 绿；接口行为由 MockMvc 测试覆盖，node start.js all 人工页面走查留待用户验收

- [x] Task 6: 样板种子数据（spec：样板数据；依赖 Task 3 的结构校验器）
  - [x] 6.1 编制 src/main/resources/db/sample-data.sql：幂等（防重复导入）、配套域（Elasticsearch / Java 后端）+ ES 写入链路簇 + 10 样板知识点（8 默认八股 / 1 叙事 / 1 设计演进链，每条 ≥2 档 stop_layer 且含 mid、三档 Markdown 样例覆盖代码块/表格/Mermaid、2~3 变体、追问链带停止条件、全部 active）
  - [x] 6.2 测试：读取种子灌测试库 → 断言 10 条全过结构校验、baseline_levels ≥2 档、外键完整
  - [x] 6.3 验证：`mvn test` 绿

- [x] Task 7: 全量验证（依赖全部前置任务）
  - [x] 7.1 `mvn clean compile` 绿
  - [x] 7.2 `mvn test` 全绿
  - [x] 7.3 `cd frontend && npm run build` 绿
  - [x] 7.4 对照 checklist.md 逐项复核并勾选

# Task Dependencies

- Task 1 → Task 2 → Task 3 →（Task 5、Task 6 可并行）→ Task 7
- Task 1 → Task 4（仅依赖 types.ts 部分）；Task 4 与 Task 2/3 并行；Task 5 依赖 Task 3 + Task 4
- 禁止并行修改同一文件：Task 2/3 同属 kb 包串行执行；Task 5 只动前端（与 Task 6 无交集）
