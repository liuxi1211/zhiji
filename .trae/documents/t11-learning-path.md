# T11 学习路径与练习模块（核心模块定义 4.2）· 实施计划

## Summary

实现 C 端日常学习主入口「学习路径与练习」：新建 `com.zhiji.learn` 包 + 一张 `learn_state` 表 + C 端 `/api/learn/**` 接口 + 学习路径页（替换 MapView 占位）+ 单元页 + 练习页。交付能力：

1. **学习路径**：按当前档案把题库组织成有序阶段——基础阶段（目标职级必问集，跨域不分域）+ 各领域阶段（阶段内按主题分学习单元，单元内按难度与频率排序）。内容由四路信号取并集准入：简历主张锚定知识点（主攻）、目标职级基线必问集（打底）、JD 命中内容（定制）、复盘信号回流（一期空集，枚举预留）。并集之外的内容不进路径、不留痕。
2. **双节奏练习**：速览模式（先自测后看答案、自评直接推进——"会"进抽查范围、"不会"进弱点加权）；练习模式（闭卷作答，复用判定引擎逐层判定驱动下一步：下钻/换角度/回退/切题，判定通过才算完成）。
3. **知识点学习状态**（6.5 命名表：学习与复习共用的数据源）：每档案 × 每知识点一行，五态状态机，速览/判定/放弃/恢复全在此落账；T12 随机复习直接消费。
4. **放弃与重生成**：用户主动放弃是唯一人工裁剪例外（移出单元、可选填原因、可恢复）；路径不落表、每次生成即视图，重生成天然不动已积累进度（4.2"重生成只动阶段与单元序列"）。
5. **跨域前置锁定**：单元内任一知识点的前置依赖未达成（前置知识点未推进）则该单元锁定。

验收 = `mvn test` 全绿 + `mvn clean compile` + `cd frontend && npm run build` + 冒烟清单（本文 Verification 节）全过。

## 模块选择依据

按核心模块定义第八节建设顺序（知识库管理 ✅ → 判定引擎 ✅ → 个人档案 ✅ → **学习路径与练习** → 随机复习 → …），前三站已核对完成（T10 于 2026-10-11 落地）。本模块全部依赖就绪：`prf_profile`（finalized 状态机 + target_level）、`kb_kp`（available 状态、level_rules 职级基线/停止点、prerequisites 前置依赖、三路频率信号）、`judge_session/judge_answer`（四分流判定 + 档位公式）、LLM 网关（判定 purpose 已通）。它是 C 端价值主链（学习 → 复习 → 模拟考）的第一个消费方，也为 T12 随机复习备好"复习池"数据源（quick/pass/weak 三态知识点集合）。

## Current State Analysis

### 已有（直接复用）

| 能力 | 位置 | 用法 |
|---|---|---|
| 客户会话 | [AuthContext.java](file:///d:/ideaProject/zhiji/src/main/java/com/zhiji/common/security/AuthContext.java) `customerId()`；拦截器规则：非 admin 的 `/api/**` 一律客户会话 | 新 Controller 挂 `/api/learn` 自动受保护，零配置 |
| 档案上下文 | [ProfileService.java](file:///d:/ideaProject/zhiji/src/main/java/com/zhiji/profile/service/ProfileService.java)：`prf_profile.is_active` 唯一当前档案；status finalized；target_level 五档 | 路径生成的画像输入；activeProfile 前端 store 已有 |
| 技能条锚点 | `prf_claim.anchor_ids`（JSON 数组，service 层序列化） | 信号一路：主张锚定 kp 并集 |
| 职级基线与停止点 | `kb_kp.level_rules`（`[{"level","required","stop_layer"}]`）；`prerequisites`（`[kpId]`） | 信号二路与单元锁定依据；停止点快照进判定会话（JudgeService.createSession 已实现） |
| 判定引擎 | [JudgeService.java](file:///d:/ideaProject/zhiji/src/main/java/com/zhiji/judge/service/JudgeService.java)：`createSession(kpId, level)` / `submitAnswer` / `submitEmpty` / `selfEval` / `close` / `get`，档位公式在 GradeCalculator | 练习模式全部转发复用，仅加 owner 归属与限额计量 |
| 知识点展示组件 | `frontend/src/components/kb/`：KpDisplay（题面/变体/追问链/样例）、DocDisplay（资料讲解） | 练习页直接复用，所见即 B 端预览 |
| 判定作答交互参考 | `frontend/src/views/admin/JudgeTrialView.vue` + `frontend/src/api/judge.ts` | C 端练习页照此交互骨架，端点换 learn 转发 |
| 常量下发 | `@ConstantGroup` + ConstantController（`GET /api/constants`） | 新增 `learn_status` 组，前端状态色/文案免维护 |
| LLM 限额计量 | `LlmRequest.user(purpose, profileId, prompt)`，`llm_usage_daily.profile_id` | 练习判定以档案 id 作计量键（试判台现传 0） |
| schema 机制 | `spring.sql.init.mode: always` + schema.sql（CREATE TABLE IF NOT EXISTS 幂等） | 新表纯增量追加；judge_session 加列走启动补丁（见 R7） |
| 测试基建 | SourceFlowTest / ProfileFlowTest 模式：`@SpringBootTest + ActiveProfiles("test") + MockMvc + @Primary StubLlmTransport` | 新建 LearnFlowTest 照抄骨架 |

### 缺口

- 后端无任何 learn 表与接口；`judge_session` 无归属标记（created_by 现为员工 id，客户/员工会话无法区分）；判定链路的 LlmRequest 未携带 profileId（试判台传 0 哨兵），C 端练习判定无法计入用户限额。
- 前端 `MapView.vue` 为 16 行占位；无学习路径/单元/练习页；`types.ts` 零 Learn 类型。
- 路由树缺单元页与练习页两条路由（learn 守卫树内）。

## 领域决策（已拍板，实现不再选择）

| # | 决策 | 依据 |
|---|---|---|
| R1 | **学习路径不落表**：阶段/单元由 PathService 每次实时推导（并集算法，毫秒级），个人进度全挂 `learn_state`。重生成 = 重新请求视图，天然不动进度 | 4.2"重生成只动阶段与单元序列，不动已积累的学习进度"；免维护路径快照与进度同步 |
| R2 | 一期阶段序列：**基础阶段**（目标职级 required=1 的 kp，跨域不分域，阶段内按主题分单元）→ **各领域阶段**（P−基线 按 domain_code 分组；单元内排序：JD 命中与主张锚定优先并打标识，其余按三路频率信号降序 → 难度升序）。"JD 相关阶段/高频阶段"不独立成段，以领域阶段内的排序与标识体现；阶段顺序按域内 kp 数降序 | 4.2 组织规则"每个领域一个学习阶段"为硬规则，"基础→JD→高频→其余"为默认顺序建议项；独立 JD 段会把同一领域的 kp 拆散在两处 |
| R3 | JD 命中一期用 **token 化 LIKE 匹配**：jd_text 按非中英文数字正则分词，取长度≥2 的 token（内置小停用词表：熟悉/掌握/了解/精通/经验/优先/相关等），对 available kp 的 title/summary/variants/framework_md 做包含判断（内存遍历） | 6.4"语义去重与相似度匹配不做"；500 kp 量级内存遍历可接受 |
| R4 | 复盘信号一路一期**空集**，PathService 留信号枚举位与注释，T13 接入 | 复盘模块未建；并集准入不变 |
| R5 | `learn_state` 五态：`learning`（进行中）/`quick`（速览自评会=抽查范围）/`pass`（练习判定通过）/`weak`（速览自评不会=弱点加权）/`abandoned`（已放弃）。**quick/pass/weak 三态均算已推进**（weak 也进复习池、接受加权复现）；单元完成度按三态计 | 4.3 三条抽题信号（到期/验伪/弱点）的数据源口径 + 6.5 抽查范围定义 |
| R6 | 前置锁定：kp 的 prerequisites 中存在**路径集内**的前置 kp 未达 quick/pass/weak → 该 kp locked；单元锁定 = 单元内任一成员 kp locked；**前置 kp 不在路径集内时忽略该前置**（用户无学习通道，锁定即死局；复盘回流下次生成纳入后自动生效） | 4.2"前置阶段未推进到位，后一阶段的单元保持锁定"+ 防死局 |
| R7 | 练习复用 JudgeService：`judge_session` 加 `owner_type` 列（`staff`/`customer`，默认 staff）；判定方法加 `profileId` 参数透传 LlmRequest（客户限额计量，试判台传 0）。旧库加列由启动补丁 `PRAGMA table_info` 检查后 `ALTER TABLE ADD COLUMN` 幂等补齐（spring.sql.init 的 CREATE TABLE IF NOT EXISTS 改不了已有表） | C 端判定必须过"单用户限额+硬熔断"闸（6.3）；归属可审计 |
| R8 | 练习完成口径：会话关闭时 `grade ∈ {pass, good, excellent}` → state=pass；`fail` 或未评级（未完成）→ 保持 learning 可重练；degraded（降级自评）会话按 grade 同口径 | 6.1"判定通过才算完成"；判定不是单点故障，降级路径兜底 |
| R9 | 练习入口对**任意 available kp** 开放（路径内 kp 为主场景），state 行按需 upsert；路径视图仍按并集准入 | 4.2 未禁止路径外练习；为复盘录题后"先练一题"等场景留口 |
| R10 | 叙事练习（prf_case 消费、叙事模板六要素）**一期不做**：题库侧叙事模板 kp 尚未建设，无练习对象；登记暂缓清单口径 | 暂缓清单"叙事练习的口述形态"同类后置 |
| R11 | 学习路径入口 = 当前档案（is_active）；档案未 finalized → `400 LEARN_PROFILE_NOT_FINALIZED`，前端引导去 /profile 定稿 | 4.1 边界"未定稿的画像不参与学习路径生成" |
| R12 | 前端文案对齐 6.5 命名表：学习路径/学习阶段/学习单元/速览模式/练习模式；路由 `/map` 与 MapView 组件名保留（避免动路由表），页内文案全按新命名 | 领域命名真相源；游戏化命名不回用 |

## Proposed Changes

### A. 数据库（`src/main/resources/db/schema.sql` 追加）

```sql
-- T11 学习路径与练习模块（核心模块定义 4.2）：知识点学习状态（6.5，学习与复习共用的数据源）。
-- 学习路径不落表：阶段/单元由 PathService 每次实时推导（四路信号并集准入，R1/R2），
-- 个人进度全挂本表；重生成只动阶段与单元序列，不动已积累进度。
-- 状态机：learning → quick|pass|weak；weak → quick|pass；abandoned → learning（恢复）；任意 → abandoned。
-- quick=速览自评会（抽查范围，T12 验伪）；pass=练习判定通过；weak=速览自评不会（弱点加权）；
-- 三态均算已推进（单元完成度与复习池入池口径），progressed_at 为 T12 间隔重复的时间起点。
CREATE TABLE IF NOT EXISTS learn_state (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id     INTEGER NOT NULL REFERENCES prf_profile (id),
    kp_id          INTEGER NOT NULL REFERENCES kb_kp (id),
    status         TEXT NOT NULL DEFAULT 'learning'
                   CHECK (status IN ('learning','quick','pass','weak','abandoned')),
    session_id     INTEGER REFERENCES judge_session (id),  -- 最近一次练习判定会话
    abandon_reason TEXT,                                   -- 放弃原因（可选填，放弃清单展示）
    progressed_at  TEXT,                                   -- 达成 quick/pass/weak 的时间
    created_at     TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at     TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (profile_id, kp_id)
);

CREATE INDEX IF NOT EXISTS idx_learn_state_profile ON learn_state (profile_id, status);
```

`judge_session` 加列（schema.sql 的建表定义同步加列供新库 + 启动补丁兜底旧库）：

```sql
ALTER TABLE judge_session ADD COLUMN owner_type TEXT NOT NULL DEFAULT 'staff';
-- staff=试判台（B 端员工）customer=练习模式（C 端客户档案）
```

启动补丁：新建 `com.zhiji.config.SchemaPatcher.java`（ApplicationRunner，`@Order` 早于业务）：`PRAGMA table_info(judge_session)` 无 `owner_type` 则执行 ALTER（JdbcTemplate 单句），幂等可重复启动。

### B. 后端（新建 `src/main/java/com/zhiji/learn/`，四层对齐 profile 包结构）

| 文件 | 内容 |
|---|---|
| `domain/LearnState.java` | `@TableName("learn_state")`；字段对齐 A 节 |
| `domain/LearnStatus.java` | 枚举 learning/quick/pass/weak/abandoned（中文名 进行中/速览通过/判定通过/待加强/已放弃），实现 ConstantItem，`@ConstantGroup("learn_status")` |
| `repo/LearnStateMapper.java` | MyBatis-Plus BaseMapper |
| `service/LearnStateService.java` | ①`getOwned(profileId, kpId)`（不建行，null 视为 learning）；②`upsertLearning(profileId, kpId, sessionId)`（practice 时建/更新行，覆盖 session_id；不动已 pass/quick 状态外的 progressed_at）；③`markQuick(profileId, kpId, known)`（known→quick / unknown→weak，progressed_at=now，若原 abandoned 先复活）；④`abandon(profileId, kpId, reason)` / `restore(profileId, kpId)`（→learning，清 progressed_at）；⑤`markPassed(profileId, kpId, sessionId)`（close 回写，progressed_at 仅首次设置）；⑥`progressedSet(profileId)`（quick/pass/weak 的 kpId 集合，锁推导用）；⑦`abandonedSet(profileId)` |
| `service/PathService.java` | `buildPath(profile)` 纯算法：①信号采集 S1 主张锚定（prf_claim.anchor_ids 并集）/S2 职级基线（level_rules 含 `{level=targetLevel, required=1}` 的 available kp，解析 JSON 列）/S3 JD 命中（R3 token 化包含匹配）/S4 复盘信号（空集，枚举位）；②并集 P=S1∪S2∪S3∪S4（均 available，过滤 deprecated/merged）；③组织（R2）：基础阶段=P∩S2 按主题分单元；领域阶段=剩余按 domain_code 分组→topic 分单元→kp 排序（JD/主张标识优先→频率三路降序→难度升序），空域不出阶段，阶段按域内 kp 数降序；④锁定（R6）：progressedSet + prerequisites 推导 kp/单元 locked；⑤输出 PathView（stages[]/units[]/kps[] 含 state/signals/locked/done/total + abandoned[] 分区 + summary 总进度）——kp 行仅含轻量字段（id/title/summary/difficulty/signals/state），详情练习时再取 |
| `service/PracticeService.java` | ①`start(kpId)`：active 档案校验（R11）→ kp available 校验 → `judgeService.createSession(kpId, profile.targetLevel, 0, "customer")` → `learnStateService.upsertLearning` → 返回 SessionView（含 stop_layer 目标刻度）；②`answers/empty/selfEval` 转发 JudgeService 对应方法，前置归属校验：会话 owner_type=customer 且其 kp 在当前档案的 learn_state 行（越权 404）；③`close(sessionId)`：转发 close → 按 R8 回写 state（grade∈pass/good/excellent → markPassed；否则不动）→ 返回会话终态 |
| `api/LearnController.java` | `@RestController @RequestMapping("/api/learn")`。端点：`GET /path`（当前档案学习路径全量视图）、`POST /kp/{kpId}/quick`（body `{verdict:"known"\|"unknown"}`）、`POST /kp/{kpId}/abandon`（body `{reason?}`）、`DELETE /kp/{kpId}/abandon`（恢复）、`POST /kp/{kpId}/practice`（开判定会话）、`GET /sessions/{id}`（会话详情，含 kp 展示结构：题面/变体/追问链/三档样例/资料挂载/level_rules 目标刻度）、`POST /sessions/{id}/answers`、`POST /sessions/{id}/empty`、`POST /sessions/{id}/self-eval`、`POST /sessions/{id}/close`。全部 `AuthContext.customerId()` 取当前客户（null → unauthorizedToken） |

**judge 包微调（3 处）**：

| 文件 | 内容 |
|---|---|
| `domain/JudgeSession.java`（改） | 加 `ownerType` 字段（String，默认 staff） |
| `service/JudgeService.java`（改） | `createSession` 加重载 `createSession(kpId, level, profileId, ownerType)`：owner_type 落库；`submitAnswer/submitEmpty` 内部 LlmRequest 的 profileId 由会话归属决定——试判台传 0，练习传档案 id。实现方式：JudgeService 各方法加 `long profileId` 参数（员工 Controller 传 0），或会话行冗余 profile_id 列——**采用方法参数方案**（不动表结构），JudgeController 现有调用点全部传 0 |
| `api/JudgeController.java`（改） | 调用点适配新签名（传 0），类注释补"试判台=staff 归属" |

**profile 包微调（1 处）**：`ProfileService` 暴露 `getActiveByCustomer(customerId)` 公有方法（现可能为私有/内部逻辑，核对后开放）供 learn 校验与取画像。

### C. 前端（`frontend/`）

| 文件 | 内容 |
|---|---|
| `src/api/types.ts`（改） | 追加：`LearnStatus`、`KpSignal`（`'claim'\|'baseline'\|'jd'`）、`PathKp`、`PathUnit`、`PathStage`、`PathView`（stages/abandoned/summary）、`QuickVerdict`、`PracticeSessionView`（会话+kp 展示结构）等契约类型（字符串字面量对齐 CHECK） |
| `src/api/learn.ts`（新） | 具名 async 函数 + `.data` 剥壳（对齐 judge.ts）：getPath/getAbandoned?/quickEval/abandonKp/restoreKp/startPractice(60s 超时)/getLearnSession/submitAnswer/submitEmpty/selfEval/closeSession(120s 超时) |
| `src/views/learn/MapView.vue`（重写，替换占位） | 页头 `.zj-page-head`（当前档案名+目标职级+总进度）+ 三态：①无当前档案 → 引导去 /profile；②未定稿 → `LEARN_PROFILE_NOT_FINALIZED` 引导定稿；③路径视图：**基础阶段**置顶（跨域标识）、**领域阶段**手风琴（默认收起非首阶段，首屏暴露"下一步学哪"=首个未完成未锁定单元高亮）；单元卡：主题名/领域色点/进度 done/total/锁定锁 icon/主攻&JD&基线 tag；点击单元进 `/learn/unit/:topicId`；底部**放弃清单**分区（原因展示+恢复按钮）。移动优先单列，只用 `--zj-*` 令牌 |
| `src/views/learn/UnitView.vue`（新） | 单元 kp 列表：状态色点（learning 灰/quick 青/pass 绿/weak 橙）、难度色、信号 tag、锁定灰态；顶部单元进度与返回路径入口；点击 kp 进 `/learn/practice/:kpId` |
| `src/views/learn/PracticeView.vue`（新） | 顶部：题面自测（KpDisplay 复用）+ 目标刻度条（"到你目标职级（中级）讲到第 N 层算够"，level_rules 快照）+ "看不懂"展开资料讲解（DocDisplay 复用）。双节奏切换：**速览**=先自测→展开三档样例→自评"我会/我不会"（调 quick，成功后返回单元页）；**练习**=开判定会话→作答流（题面→逐层追问，作答提交判定：verdict 图标+要点命中+用户原话引用，对→下钻/缺→继续钻/错→纠正后同层换角度/空→切题，交互照 JudgeTrialView 骨架）→"结束练习"→小结（四档 grade+grade_note）→回写后返回。判定超时/失败时按返回的 degraded 提示走自评 |
| `src/router/index.ts`（改） | learn 守卫树内加两条：`/learn/unit/:topicId` → UnitView、`/learn/practice/:kpId` → PracticeView；MapView 的 name/title 保持（"学习路径"文案在页内） |

### D. 常量与 prompts

- 无新增 LLM purpose、无新增 prompt 模板（本模块纯算法 + 复用判定链路）；`GET /api/constants` 新增 `learn_status` 组随 LearnStatus 枚举自动下发。

### E. 测试（新建 `src/test/java/com/zhiji/learn/LearnFlowTest.java`）

骨架照抄 ProfileFlowTest：`@SpringBootTest + ActiveProfiles("test")` + MockMvc + 内嵌 `@TestConfiguration` 提供 `@Primary StubLlmTransport`。种子工厂：员工造 available kb_kp（携带 level_rules/prerequisites/variants/scoring_points）、客户注册 + 档案补齐 + finalize。

用例清单：

1. **未定稿拦截**：档案 draft 态 `GET /api/learn/path` → 400 `LEARN_PROFILE_NOT_FINALIZED`；finalize 后 200；
2. **基线必问进基础阶段**：造 kp A（level_rules mid required=1）→ 路径出现基础阶段且含 A（signal=baseline）；
3. **主张锚定进领域阶段**：claim 锚定 kp B → B 在所属领域阶段（signal=claim）；
4. **JD 命中**：jd_text 填含 kp C 标题词的文本 → C signal=jd；停用词不误报；
5. **并集准入**：available 但无任何信号的 kp D 不出现在任何阶段；
6. **速览自评**：quick known → state=quick、单元完成度 +1；unknown → weak；abandoned 后 known 恢复活账；
7. **放弃与重生成**：abandon kp B（带原因）→ B 移出单元进 abandoned 分区；重新 GET path 决策保留；restore → 回单元 learning；
8. **前置锁定**：kp B2 prerequisites=[A]，A 未推进 → 含 B2 的单元 locked；A quick 后解锁；
9. **练习流**：practice 开会话（stop_layer 快照）→ answers（stub 判定 JSON：hit+quote）→ close（stub grade=good）→ state=pass、progressed_at 非空；stub grade=fail → state 保持 learning；
10. **空答与降级**：empty 短路（verdict=empty, source=code）；self-eval 走 self 通道；
11. **越权**：客户乙访问客户甲的会话 → 404；员工 token 请求 `/api/learn/**` → 401；
12. **限额计量**：练习判定后 `llm_usage_daily` 该 purpose 行 profile_id=档案 id 非零（试判台路径 profile_id=0 不回归）。

## Assumptions & Decisions 记录

| # | 决策 | 理由 |
|---|---|---|
| D-1 | R2 阶段序列与核心模块定义 4.2"默认顺序建议"存在一处取舍（JD/高频不独立成段，并入领域阶段排序标识）：组织硬规则（每域一阶段、按主题分单元、并集准入）全部遵守，顺序建议按"建议"性质实现 | 独立 JD 段把同域 kp 拆两处、且 JD 命中量小不成段；如需推翻只需改 PathService 组织段 |
| D-2 | 本模块无 AI 新用途：路径生成是纯算法（并集/排序），判定复用既有 purpose；6.4 用途清单无需登记新条目 | "AI 只做组织不做裁决"，本模块连组织都不需要模型 |
| D-3 | 错误码新分段 `LEARN_*`：`LEARN_PROFILE_NOT_FINALIZED`（未定稿 400）、`LEARN_KP_NOT_AVAILABLE`（kp 不可练 400）、`LEARN_SESSION_OWNED`（会话归属不符 404） | 对齐 PRF_*/KB_* 分段惯例 |
| D-4 | `judge_session.owner_type` 默认 'staff'：旧数据（试判台）无需回填；新库建表定义与启动补丁双保险 | spring.sql.init mode=always 只能增量建表，改列走 PRAGMA 补丁 |
| D-5 | path 视图实时计算不加缓存：50–500 kp 量级单次 <50ms，缓存反而引入失效复杂度 | YAGNI；量级变化时再评估 |
| D-6 | 练习对路径外 available kp 开放（R9）：state 行 upsert，不污染路径视图 | 复盘录题（T13）"先练一题"的前置口子 |
| D-7 | weak 算推进：产品语义上"速览自评不会"已消耗学习动作、进入弱点加权复现通道，与"完全没学"不同 | 4.3 弱点加权源口径；如产品侧不同意改 R5 一处即可 |
| D-8 | 本文件即 T11 任务文档（.trae/documents 命名约定 t{编号}-*.md），实现完成后不另写收尾文档 | 对齐 T10 先例 D-10 |

## Verification

1. `mvn test` 全绿（新增 LearnFlowTest 12 组用例，既有测试不回归）；
2. `mvn clean compile` 通过；
3. `cd frontend && npm run build` 通过（含 vue-tsc 类型检查）；
4. 冒烟清单（需 `config/application-local.properties` 配真实 LLM key）：
   - B 端造 3-5 个 available 知识点（一个设为 mid 必问、一个设前置关系、各领域分布）；
   - C 端注册 → /profile 建档案（粘贴含关键词简历、JD 填关键词、职级选中级）→ 逐条确认技能条（锚点含上述 kp）→ 定稿；
   - /map：出现基础阶段（必问 kp）+ 领域阶段（主张/JD 标识优先排序）；无信号 kp 不出现；前置未完成的单元显示锁定；
   - 单元页点开速览：题面自测 → 展开三档样例 → 自评"我会"→ 状态变青、进度 +1；
   - 练习模式：闭卷作答 → 判定返回命中与引用 → 对则下钻 → "结束练习"出四档小结 → 状态变绿；
   - 放弃一道题 → 移入放弃清单 → 恢复回单元；
   - 前置 kp 通过后刷新，锁定单元解锁；
   - 未定稿/未登录态引导正确；`GET /api/constants` 新增 learn_status 组；
5. 数据兼容：对已有本地库（含 B 端内容与档案数据）直接启动——`learn_state` 纯增量建表成功，启动补丁为旧库补 `owner_type` 列无报错，试判台功能不回归。
