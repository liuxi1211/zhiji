# T6 判定引擎（核心模块定义 6.1）· 实施计划

## Summary

实现全局能力模块**判定引擎**：把自由作答对齐到知识库预制的评分要点上，输出结构化判定。设计真相源为[核心模块定义 6.1](d:\ideaProject\zhiji\docs\核心模块定义.md)（三条硬规则：答况四分流、引用强制、档位公式）与 [6.5 领域命名表](d:\ideaProject\zhiji\docs\核心模块定义.md)。

交付内容：

1. **后端 `com.zhiji.judge` 包**（四层 api/service/domain/repo）：判定会话 + 逐问作答判定 + 档位计算 + 评分要点全量快照落库 + 降级自评通道；
2. **B 端「试判台」页面**（`/admin/judge`）：选知识点 → 选目标职级 → 题面与追问层逐层作答 → 看判定明细与档位报告。它同时是 5.2 审核项「评分要点可判定性」与职级停止点标定完整性的人工验证工具（内容维护者的质检工具，不是纯调试页）；
3. **测试**：档位公式纯单元测试 + 判定流程集成测试（复用 `StubLlmTransport` 套路）。

**不做**（边界）：C 端消费（练习模式 UI、模拟考逐问判定流、随机复习判定入口）不在本次——引擎只保证 API 契约就绪；变体现场改写（缺答→换角度再问）归消费方模块；申诉标记归模拟考自己的作答日志（暂缓清单 1.2）；不做语音作答。

## 模块选择依据

核心模块定义第三节建设顺序：「知识库管理 → **判定引擎** → 个人档案 → 学习路径与练习 → 随机复习 → 模拟考 → 复盘 → 知识点分享」。第 5 章（知识库管理）已完成（T4/T5，`kb` 包 + 四表 + 四页面已上线），判定引擎是顺序上的下一个；且 6.1 明言「学习与模拟考都消费它」，学习路径（模块 2）开工前必须先有这把尺子。

## Current State Analysis

### 已有（直接复用）

- **判定尺子已齐**：[KbContentService.java](d:\ideaProject\zhiji\src\main\java\com\zhiji\kb\service\KbContentService.java) 结构契约 `Followup(id, layer, direction, content, status)`（id 永久，红线 2）、`ScoringPoint(id, level: pass|good|excellent, content)`、`LevelRule(level, required, stopLayer)` 五档职级；`kb_kp` 表 `scoring_points/followups/level_rules` JSON 列已落库（schema.sql）。
- **LLM 门面**：[LlmClient.java](d:\ideaProject\zhiji\src\main\java\com\zhiji\llm\api\LlmClient.java) `completeJson` 自带「非法 JSON 重试一次 → 抛 SYS_PROVIDER_ERROR」——正好承接 6.1「重试一次 → 解锁自评」降级路径；限额（JUDGE 软上限 200/天）与计数已内建。
- **判定用途已注册**：[LlmPurpose.java](d:\ideaProject\zhiji\src\main\java\com\zhiji\llm\api\LlmPurpose.java) `JUDGE_PRACTICE`（judge_practice，Tier.JUDGE 温度 0 便宜模型）；`defaultRouteConfig()` 按枚举全量生成路由，T2 起库内路由已含该键，**llm 包零改动**。
- **鉴权**：`AuthInterceptor` 已覆盖 `/api/admin/**`（员工会话），新端点零配置接入。
- **Prompt 套路**：`resources/prompts/*.txt` + `PromptTemplate.render({{var}})`（SourceAiService 的 readTemplate 模式）。
- **测试基建**：`StubLlmTransport`（脚本化响应/异常/计数）+ `@SpringBootTest` 集成测试先例（KbFlowTest）。
- **前端**：Vue3 + Element Plus + request.ts；管理端页面/菜单/路由注册模式成熟（ProductionDeskView 等）；`api/types.ts` 已有 KbFollowup/KbScoringPoint/KbLevelRule 类型。

### 缺口

1. judge 包、judge_session/judge_answer 表、判定 API 完全不存在。
2. 评分要点不绑定追问层（知识点级结构）——命中按「整个会话内累计命中」聚合，公式需处理跨层要点。
3. 试判台页面不存在。

## 领域决策（已拍板，实现不再选择）

| # | 决策 | 依据 |
|---|---|---|
| D1 | **答况判定：模型只报事实，结论由代码推导**。模型输出 `{wrong: bool, wrong_note, points: [{id, hit, quote}]}`；代码推导答况：空文本（trim 后为空）→`empty`（不调 LLM）；`wrong=true`→`wrong`（硬否决）；任一 pass 级要点 hit=true→`correct`；否则→`missing` | 6.1「分是公式算的」红线 1 的推广：答况四值同样由代码裁决，模型只做对齐与错误检出 |
| D2 | **引用强制在代码侧执行**：hit=true 但 quote 为空、或 quote（去空白归一后）不是用户作答的子串 → 强制改 miss 并记录。未知要点 id 忽略，漏报要点默认 miss | 6.1 硬规则 2「引不出原话算未命中」 |
| D3 | **档位公式（严格制，代码写死）**：题面未答 → 无法评级（null）；任一 in-scope 作答 `wrong` → `fail`（硬否决）；pass 级要点全命中 → `pass`；pass+good 全命中 → `good`；pass+good 全命中且 excellent ≥1 → `excellent`；否则 `fail`。pass 级要点集合为空 → null + 说明（内容标定缺陷，试判台负责暴露） | 6.1 硬规则 3；「说反概念的挂、讲得浅的过」 |
| D4 | **判定范围以停止点为界**：`level_rules` 中目标职级的 `stop_layer` 为界，层号 > stop 的追问层可作答可判定但不计入档位；职级未标定或 stop_layer 空 → 取最大 active 层为界。pass 要点若依赖停止点之外的层导致永远不可达，属内容标定缺陷，公式不做补偿、试判台暴露 | 6.1「停止点之下的层不参与档位」；5.2 质量巡检 |
| D5 | **命中跨问累计**：评分要点是知识点级的，每次作答都带全量要点对齐；某要点在任一 in-scope 作答中命中即累计为命中 | 现有数据结构（要点不绑层）的唯一合理解法 |
| D6 | **会话模型**：`judge_session`（kpId + 目标职级 + 停止点快照 + 状态 + 档位）+ `judge_answer`（逐问判定流水，只插不改）。试判台与后续练习/模拟考共用���契约（练习=单问会话，模拟考消费逐问判定） | 6.1「每次判定保存评分要点集的全量快照（含未命中项）」 |
| D7 | **降级自评**：`completeJson` 抛 SYS_PROVIDER_ERROR 时，作答行落 verdict=null + error_note，返回 `degraded=true`；前端解锁自评按钮，`POST .../self-eval` 提交四值之一，服务端校验「仅 verdict 为空的行可自评、仅一次」，自评行 source=self 并计入档位（会话标 degraded=1） | 6.1「判定永远不是单点故障」 |
| D8 | **快照即真相**：每行作答落 `points_result`（全量要点 + 命中结果 + 引用）与 `question_text`（题面/追问层内容快照），内容改版后历史可回溯可重算 | 6.1 配套机制 |
| D9 | LLM 用途复用 `JUDGE_PRACTICE`（试判台就是练习判定的 B 端质检用法，B 端 profileId=null 走 0 哨兵计量）；`JUDGE_GACHA` 显示名「抽卡判定」→「随机复习判定」（仅 getName 文案，code 不动避免破坏既有路由数据） | 6.4 用途清单；6.5 游戏化命名退役 |
| D10 | 会话/作答校验：kp 必须 available；level 五档合法；close 需 ≥1 行作答且题面已答 | 试判台可用性底线 |

## Proposed Changes

### A. 数据库（src/main/resources/db/schema.sql 追加）

```sql
-- T6 判定引擎（核心模块定义 6.1）：把自由作答对齐到评分要点，答况四分流���引用强制、档位公式全在代码。
-- 会话=一个知识点 × 目标职级的连续作答；作答行只插不改（快照即真相，改版后历史可重算）。
CREATE TABLE IF NOT EXISTS judge_session (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    kp_id       INTEGER NOT NULL REFERENCES kb_kp (id),
    level       TEXT NOT NULL CHECK (level IN ('junior','mid','senior','expert','master')),
    stop_layer  INTEGER,                       -- 判定范围界（创建时快照自 level_rules；空=未标定取最大层）
    status      TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','closed')),
    grade       TEXT CHECK (grade IS NULL OR grade IN ('excellent','good','pass','fail')),
    grade_note  TEXT,                          -- 无法评级/公式说明
    degraded    INTEGER NOT NULL DEFAULT 0,    -- 会话中出现降级自评
    closed_at   TEXT,
    created_by  INTEGER NOT NULL,              -- 员工 id（试判台=内容维护者）
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_judge_session_kp ON judge_session (kp_id, created_at);

CREATE TABLE IF NOT EXISTS judge_answer (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id     INTEGER NOT NULL REFERENCES judge_session (id),
    followup_id    TEXT,                       -- null=题面作答；否则追问层 id（永久身份，红线 2）
    question_text  TEXT NOT NULL,              -- 作答时问题快照（题面/追问层内容）
    layer          INTEGER NOT NULL DEFAULT 0, -- 0=题面，追问层为层号
    answer_text    TEXT NOT NULL,
    verdict        TEXT CHECK (verdict IS NULL OR verdict IN ('correct','missing','wrong','empty')),
    source         TEXT NOT NULL DEFAULT 'llm' CHECK (source IN ('llm','self','code')),  -- code=空答短路
    points_result  TEXT NOT NULL DEFAULT '[]', -- 全量要点快照 [{"id","level","content","hit","quote"}] 含未命中
    wrong_note     TEXT,
    error_note     TEXT,                       -- 判定失败留痕（degraded 前置）
    created_at     TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_judge_answer_session ON judge_answer (session_id);
```

### B. 后端（新建 src/main/java/com/zhiji/judge/）

| 文件 | 内容 |
|---|---|
| `judge/domain/JudgeSession.java` | `@TableName("judge_session")` 实体 |
| `judge/domain/JudgeAnswer.java` | `@TableName("judge_answer")` 实体 |
| `judge/domain/Verdict.java` | 枚举 correct/missing/wrong/empty，实现 ConstantItem，标 `@ConstantGroup("judge_verdict")`（答况四值下发前端） |
| `judge/domain/Grade.java` | 枚举 excellent/good/pass/fail |
| `judge/repo/JudgeSessionMapper.java`、`JudgeAnswerMapper.java` | MyBatis-Plus BaseMapper |
| `judge/service/GradeCalculator.java` | 纯静态档位公式（D3）：入参 in-scope 作答行（verdict + points_result）+ 全量要点 → Grade + note。无 Spring 依赖，独立单测 |
| `judge/service/JudgeAiService.java` | 单问对齐：读 prompt 模板 → `LlmClient.completeJson(JUDGE_PRACTICE, null, …)` → 结果校验（D2 引用强制、未知 id 忽略）→ 返回结构化命中集。SYS_PROVIDER_ERROR 透传给上层 |
| `judge/service/JudgeService.java` | 会话编排：createSession（kp available 校验、level 校验、stop_layer 快照）、submitAnswer（空答短路不调 LLM→verdict=empty/source=code；LLM 调用失败→verdict=null+error_note 返回 degraded；成功→落行返回明细）、selfEval（D7 校验）、close（题面已答校验、in-scope 过滤、GradeCalculator、落 grade）、会话/作答查询视图（B 端历史） |
| `judge/api/JudgeController.java` | `POST /api/admin/judge/sessions {kpId, level}`；`POST /api/admin/judge/sessions/{id}/answers {followupId?, answer}`；`POST /api/admin/judge/sessions/{id}/self-eval {answerId, verdict}`；`POST /api/admin/judge/sessions/{id}/close`；`GET /api/admin/judge/sessions?status=&page=`；`GET /api/admin/judge/sessions/{id}`（含作答明细）。kp 选择器复用 kb 现有目录检索端点（实现时确认 KbAdminController 已有的 kp 列表/详情签名，前端直接消费；若缺 available 过滤则补一个只读端点 `GET /api/admin/judge/kps?keyword=`） |
| `resources/prompts/judge.txt` | 判定官 prompt：角色（面试判定官）、输入（{{question}} 题面/当前问题、{{answer}} 用户作答、{{points}} 要点 JSON 数组）、要求（逐要点给 hit+verbatim 引用；单独判断作答是否含概念性错误 wrong/wrong_note；禁止给分）、输出 JSON schema |

判定会话视图（创建时返回，供前端组织问答流）：kp 题面、追问层列表（active，标注层号/方向/in-scope）、全量要点（B 端质检工具可见）、停止点。

### C. 前端

| 文件 | 内容 |
|---|---|
| `frontend/src/api/judge.ts` | 会话/作答/自评/close/历史 API 封装 |
| `frontend/src/api/types.ts`（追加） | JudgeVerdict/JudgeGrade/JudgeSessionView/JudgeAnswerView/JudgePointResult 等类型 |
| `frontend/src/views/admin/JudgeTrialView.vue`（新建） | 试判台三段式：①选知识点（关键词搜索，仅 available）+ 选目标职级 → 建会话；②问答区：当前问题（题面→按层推进，判定结果给下一步建议：对→下一层/缺→同层再答/错→看纠正提示/空→下一层）、作答框、判定明细面板（答况徽标 + 逐要点命中/引用 + wrong_note）、降级时解锁自评四值按钮；③报告区（close 后）：档位、grade_note、命中分布、逐问答况时间线 |
| `frontend/src/router/index.ts` + `frontend/src/layouts/AdminLayout.vue` | 注册 `/admin/judge` 路由与菜单项「试判台」（staff 可见） |

### D. llm 包微调（仅文案）

- [LlmPurpose.java](d:\ideaProject\zhiji\src\main\java\com\zhiji\llm\api\LlmPurpose.java)：`JUDGE_GACHA` 显示名「抽卡判定」→「随机复习判定」（code `judge_gacha` 不动，不破坏既有 sys_config 路由数据；Java 常量名的整体更名涉及路由数据迁移，登记暂缓）。

### E. 测试

| 文件 | 覆盖 |
|---|---|
| `judge/service/GradeCalculatorTest.java` | 纯单元：各档位路径、wrong 硬否决、pass 空集→null、跨问累计命中、未命中不进档 |
| `judge/JudgeFlowTest.java` | `@SpringBootTest` + `StubLlmTransport`：空答短路不调 LLM（callCount=0）；标准命中→correct + 快照含未命中项；引用缺失强制 miss；wrong 硬否决→close 得 fail；provider error→degraded→self-eval 落库且仅一次；close 落 grade；客户 token 打不进 `/api/admin/judge/**`（401/403） |

## Assumptions & Decisions 记录

- 用户跳过了范围/公式确认提问，按推荐方案执行：交付含试判台（它服务 5.2 审核项「评分要点可判定性」的人工验证，属内容维护者工作台的一部分，不是纯调试页）；档位公式取严格制。
- 表名/状态值按 6.5 命名表口径（judge/verdict/grade/stop layer），无游戏化命名。
- 一期不做：变体改写驱动、申诉、C 端入口、LLM 自动预审。

## Verification

1. `mvn clean compile` 通过；
2. `mvn test` 全绿（新增 GradeCalculatorTest + JudgeFlowTest）；
3. `cd frontend && npm run build` 通过（含类型检查）；
4. 手工冒烟：`node start.js all` → 管理端登录 → 试判台选一个 available 知识点 → 题面作答（好答案/错答案/空答案三态）→ 追问层推进 → close 出档位报告。
