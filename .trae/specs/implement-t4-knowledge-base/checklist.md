# Checklist — T4 知识库模块（kb）

> 全部通过（2026-09-24 验收复核：22 项代码审查 + 三条验收命令全绿 + git status 工作区核对）。

## 表结构与契约

- [x] schema.sql 幂等追加 5 表（skill_domain / kp_cluster / kp_main / kp_prereq / kp_freq_signal），FK 依赖顺序正确，重复启动无报错
- [x] 常量三处同步齐全：sub_type、content_status、point_level、freq_source、freq_level、domain_status、level（CHECK 约束 = 后端 @ConstantGroup 枚举 = 前端 types.ts；point_level/level 存 JSON 列，按 SQLite 陷阱 E 由应用层校验兜底）
- [x] `GET /api/constants` 下发包含新增常量组（ConstantRegistryTest 覆盖，扫描路径覆盖 com.zhiji.kb.domain）

## 簇管理

- [x] 建簇/改簇可用，建簇必选 active 域，域缺失/停用给可读错误（KB_DOMAIN_INVALID）
- [x] 从零讲与完整参考方案用 Markdown 编辑器（实时预览）编辑
- [x] 合并簇：被并簇 retired + merged_into 指向目标，簇下知识点主簇归属整体迁移（事务内原子）
- [x] 簇无删除入口；簇编辑页展示簇下知识点清单

## 知识点核心

- [x] 知识点新建落 draft，编辑保存全结构，列表列齐全（题面/域/主簇/sub_type/难度五色/状态/最近校对时间/复核到期时间）
- [x] 题面模糊搜索 + 七项筛选（域/簇/sub_type/状态/难度/是否已标职级基线/复核到期）+ 分页可用
- [x] layer_key 红线：新增层分配 L{n} 递增（停用层占号）；停用层身份保留不复用；主问题层身份 main 不占 followups
- [x] 编辑保存的 layer_key 白名单校验：携带未知 layerKey 被拒（400 KB_LAYER_KEY_UNKNOWN）
- [x] 评分要点按层组织、basic/mid/senior 分级、pointId 稳定（新要点后端分配、未知 pointId 拒绝）；rubric 引用不存在层被拒
- [x] samples 三档 key 齐全、level_req 五档齐全且 required=true 时 stopLayer 必填
- [x] baseline_levels 实列随保存同步（stopLayer 非空档位重算），「未标职级基线」筛选可用
- [x] 前置多选挂载可跨簇，成环拒绝并返回环路径（A → B → C → A），retired 不可作前置

## 状态机与治理

- [x] 状态单向 draft → reviewed → active → retired；draft 直达 active 被拒（不校不上线）
- [x] active 修订原地保存、状态不变、review_due_at 重置为 now+90 天
- [x] 下线必填去向（废弃或合并）；合并迁移变体（去重）、追问链与评分要点不迁移、merged_into 指向保留；个人态引用检查预留（user_* 表随 T10+ 接入）
- [x] 频率信号人工标定（manual：档位+理由）upsert 写入并只读展示；聚合分不落库

## 前端

- [x] MarkdownView：代码高亮/表格/Mermaid/行内代码/列表/标题渲染正常；HTML 标签按纯文本展示（html:false + escapeHtml 注入防护）
- [x] Mermaid 语法非法时降级展示源码+提示，不白屏（单块降级 + 模块级兜底 catch）
- [x] MarkdownEditor 编辑/预览双区可用，四个 Markdown 白名单字段均使用
- [x] KpManageView 全量接线（七项筛选/五色难度/簇管理/编辑抽屉 9 区块/下线合并对话框/空域引导）；active 保存提示历史判定已快照
- [x] SkillsView 保持占位未破坏（域管理随 T3）
- [x] api/kb.ts 按后端模块组织（16 个函数与两个 Controller 逐一对应）；types.ts 与后端契约一致

## 权限

- [x] /api/admin/kp/** 与 /api/admin/clusters/**：员工 token 可用（admin/editor），客户 token 与匿名 401（KpTest/ClusterTest 权限用例覆盖）
- [x] 拦截器与权限矩阵无需改动即生效（落在既有 /api/admin/** 规则内）

## 样板数据与验收

- [x] sample-data.sql 幂等（域 INSERT OR IGNORE、簇/知识点 WHERE NOT EXISTS 自然键）、不挂入自动初始化；含 2 域 + 3 簇 + 10 样板知识点（8 八股/1 叙事/1 演进链）
- [x] 每条样板 ≥2 个职级档位 stop_layer 且含 mid；Markdown 覆盖代码块/表格/Mermaid；2~3 变体；追问链带停止条件；全部 active
- [x] 测试灌种子断言 10 条全过结构校验、baseline_levels ≥2 档（SampleDataTest 8 用例）
- [x] `mvn clean compile` 绿（随 mvn clean test 执行）
- [x] `mvn test` 全绿（Tests run: 72, Failures: 0, Errors: 0, Skipped: 2 为既有 LiveArkSmokeTest 环境跳过）
- [x] `cd frontend && npm run build` 绿（vue-tsc 类型检查 + vite 构建）
- [x] 未运行 npm run lint（项目无 lint）；未引入向量库/Spring Security/无关依赖；git status 核对改动仅限任务范围（docs/prd/技能库PRD.md 的删除为用户预先暂存操作，非本次改动）
