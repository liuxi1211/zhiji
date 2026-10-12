-- 知己 · 演示表（仅用于验证 SQLite 环境，非最终 schema）
-- 目的：验证 CHECK 约束生效、JSON 存储、外键、自增主键

CREATE TABLE IF NOT EXISTS demo_kp (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    biz_id      TEXT    NOT NULL UNIQUE,                 -- 业务 id，模拟 {簇}-{slug}
    title       TEXT    NOT NULL,
    sub_type    TEXT    NOT NULL DEFAULT 'default'
                CHECK (sub_type IN ('default','design_chain','narrative')),
    difficulty  INTEGER NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
    followups   TEXT    NOT NULL DEFAULT '[]',           -- 模拟 JSON 列
    created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_demo_kp_sub_type ON demo_kp (sub_type);

-- 知己 · 正式表（自 T1 起按开发计划逐模块追加；字段级 DDL 以各模块落库时定稿）

CREATE TABLE IF NOT EXISTS sys_staff (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,
    role          TEXT    NOT NULL CHECK (role IN ('admin','editor')),
    status        TEXT    NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
    created_at    TEXT    NOT NULL DEFAULT (datetime('now')),
    last_login_at TEXT
);

CREATE TABLE IF NOT EXISTS sys_customer (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,
    status        TEXT    NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
    registered_at TEXT    NOT NULL DEFAULT (datetime('now')),
    last_login_at TEXT
);

-- T2 llm 支撑模块：运行参数（模型路由 / 限额 / 全站熔断）存配置表，B 端 /admin/settings 读写，改动即生效。
CREATE TABLE IF NOT EXISTS sys_config (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key   TEXT    NOT NULL UNIQUE,
    config_value TEXT    NOT NULL,
    description  TEXT,
    updated_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- LLM 调用计数（入口 × 天）。profile_id 用 0 哨兵而非 NULL：
-- SQLite UNIQUE 索引里 NULL 互不相等，用 NULL 会让「同日同入口的无档案计数」产生多行、upsert 失效。
-- purpose 刻意不加 CHECK：入口随业务模块持续增加（T6-T12），而 CREATE TABLE IF NOT EXISTS 改不了既有约束，
-- 加了 CHECK 会让旧库写入新入口直接失败；取值对齐由 LlmPurpose 枚举与前端 types.ts 保证（附录 A 8.2）。
CREATE TABLE IF NOT EXISTS llm_usage_daily (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    stat_date  TEXT    NOT NULL,
    purpose    TEXT    NOT NULL,
    profile_id INTEGER NOT NULL DEFAULT 0,
    call_count INTEGER NOT NULL DEFAULT 0,
    fail_count INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT    NOT NULL DEFAULT (datetime('now')),
    UNIQUE (stat_date, purpose, profile_id)
);

CREATE INDEX IF NOT EXISTS idx_llm_usage_date ON llm_usage_daily (stat_date);

-- T3 素材库模块：素材（Source Item，核心模块定义 5.1）。素材库是 B 端内部数据资产，
-- 入库（提交进标准库）后长期保留、原文隔离不对任何外部用户开放（6.2 红线 5/7）。
-- 状态机：pending（待加工）→ submitted（已提交入库）/ discarded（已放弃）。
CREATE TABLE IF NOT EXISTS src_item (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_id    INTEGER,                                   -- 拆分产出的子素材指向母素材；null 即母素材
    title        TEXT    NOT NULL,
    content_md   TEXT    NOT NULL DEFAULT '',               -- Markdown 正文；图片引用形如 asset:source/{itemId}/{file}
    summary      TEXT,                                      -- 素材概述（百字内）：齐备是提交入库的门槛（5.1 边界）
    difficulty   TEXT    CHECK (difficulty IS NULL OR difficulty IN ('junior','mid','senior','expert')),
    domain_code  TEXT,                                      -- 领域代码（常量组 domain，人工点选；AI 分类��在所选领域内）
    topic_name   TEXT,                                      -- 主题位置（AI 建议或人工填写，仅入库前的工作位信息）
    source_type  TEXT    NOT NULL DEFAULT 'manual'
                 CHECK (source_type IN ('manual','import','url','inferred')),  -- 手写/导入/URL抓取/反推出题
    source_url   TEXT,                                      -- 原始出处：URL 自动带入或人工登记，服务版权追溯
    status       TEXT    NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending','submitted','discarded')),
    submitted_at TEXT,                                      -- 提交入库时间（素材保留不删的起点）
    created_by   INTEGER NOT NULL,                          -- 操作员工 id（sys_staff.id）
    created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (parent_id) REFERENCES src_item (id)
);

CREATE INDEX IF NOT EXISTS idx_src_item_status ON src_item (status);
CREATE INDEX IF NOT EXISTS idx_src_item_parent ON src_item (parent_id);

-- T4 标准知识库模块（核心模块定义 5.2）：主题为第二层组织节点，内容按定类分流两套实体，
-- 数据分开存放（kb_kp 面试题线 / kb_doc 文章线）；标准库与素材库进门即断联（6.2 红线 7），
-- 内容新版本只能由标准库内同位语料合并重写或人工编辑产生。
-- 内容状态机：draft(退回/恢复暂存) → pending(待审) → available(上线)；
-- pending → deprecated(废弃)；available → deprecated(弃用下线)；
-- 合并产物审核通过后源 → merged(合并下线，merged_into 指向产物，数据保留可恢复)。
CREATE TABLE IF NOT EXISTS kb_topic (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    domain_code    TEXT NOT NULL,                 -- 领域（常量组 domain，人工维护边界，5.3）
    name           TEXT NOT NULL,                 -- 主题名：前端可见、可人工改名（5.2 组织结构可改）
    status         TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','merged')),
    merged_into_id INTEGER,                       -- 主题合并指向的目标主题
    created_at     TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at     TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (domain_code, name)
);

CREATE INDEX IF NOT EXISTS idx_kb_topic_domain ON kb_topic (domain_code);

CREATE TABLE IF NOT EXISTS kb_kp (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    domain_code    TEXT NOT NULL,
    topic_id       INTEGER NOT NULL REFERENCES kb_topic (id),
    title          TEXT NOT NULL,                 -- 题面
    summary        TEXT,                          -- 可读摘要（目录与同位管理始终有摘要，概述是入库门槛的落点）
    framework_md   TEXT NOT NULL DEFAULT '',      -- 叙述框架/正文（Markdown 快照）
    variants       TEXT NOT NULL DEFAULT '[]',    -- 问法变体 [{"text":"..."}]
    followups      TEXT NOT NULL DEFAULT '[]',    -- 追问链 [{"id","layer","direction","content","status"}]
                   -- id 经分配永不复用、永不修改（红线 2），层的增减走新增/停用（status=active/disabled）
    scoring_points TEXT NOT NULL DEFAULT '[]',    -- 分级评分要点 [{"id","level":"pass|good|excellent","content"}]（判定引擎的尺子）
    answer_samples TEXT NOT NULL DEFAULT '[]',    -- 三档回答样例 [{"level":"pass|good|excellent","content"}]
    difficulty     TEXT NOT NULL CHECK (difficulty IN ('junior','mid','senior','expert')),
    level_rules    TEXT NOT NULL DEFAULT '[]',    -- 职级要求与停止点 [{"level":"junior|mid|senior|expert|master","required":0/1,"stop_layer":N}]
    prerequisites  TEXT NOT NULL DEFAULT '[]',    -- 前置依赖 [kpId]（学习维度，与追问层/主题正交）
    freq_manual    INTEGER NOT NULL DEFAULT 0,    -- 频率信号·人工标定
    freq_signal    INTEGER NOT NULL DEFAULT 0,    -- 频率信号·面经回流（复盘模块 T9 接入，一期恒 0）
    freq_coverage  INTEGER NOT NULL DEFAULT 0,    -- 频率信号·覆盖度（合并通过时由覆盖源数转入）
    status         TEXT NOT NULL DEFAULT 'pending'
                   CHECK (status IN ('draft','pending','available','deprecated','merged')),
    merged_into_id INTEGER REFERENCES kb_kp (id),
    merge_sources  TEXT,                          -- 非空即合并产物：源 id JSON 数组，审核通过时据此把源标 merged
    review_due_at  TEXT,                          -- 复核到期时间（放行时设置，到期回审核队列复检）
    created_by     INTEGER NOT NULL,              -- 操作员工 id（sys_staff.id）
    created_at     TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_kb_kp_topic ON kb_kp (topic_id, status);

CREATE TABLE IF NOT EXISTS kb_doc (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    domain_code    TEXT NOT NULL,
    topic_id       INTEGER NOT NULL REFERENCES kb_topic (id),
    title          TEXT NOT NULL,
    summary        TEXT,
    content_md     TEXT NOT NULL,                 -- 讲解正文（Markdown 快照）
    difficulty     TEXT NOT NULL CHECK (difficulty IN ('junior','mid','senior','expert')),
    source_url     TEXT,                          -- 来源溯源（素材侧 URL 自动带入，版权追溯）
    kp_id          INTEGER REFERENCES kb_kp (id), -- 挂载的知识点（挂载动作即人工发布决策）
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

-- 审核留痕（5.2「不校不上线」「留痕可回溯」）：动作式流水，每次动作一行，只插不改。
CREATE TABLE IF NOT EXISTS kb_review (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    content_type TEXT NOT NULL CHECK (content_type IN ('kp','doc')),
    content_id   INTEGER NOT NULL,
    action       TEXT NOT NULL CHECK (action IN ('submit','resubmit','recheck','pass','reject','discard')),
    source       TEXT NOT NULL DEFAULT 'wizard' CHECK (source IN ('wizard','direct','merge','recheck')),
    checks       TEXT,                            -- 四项强制校验 {"correctness","judgeable","infringement","quality","notes"}
    comment      TEXT,                            -- 审核意见（退回原因等）
    operator_id  INTEGER NOT NULL,
    created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_kb_review_content ON kb_review (content_type, content_id);

-- T6 判定引擎（核心模块定义 6.1）：把自由作答对齐到评分要点，答况四分流、引用强制、档位公式全在代码。
-- 会话=一个知识点 × 目标职级的连续作答；作答行只插不改（快照即真相，内容改版后历史可回溯可重算）。
CREATE TABLE IF NOT EXISTS judge_session (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    kp_id       INTEGER NOT NULL REFERENCES kb_kp (id),
    level       TEXT NOT NULL CHECK (level IN ('junior','mid','senior','expert','master')),
    stop_layer  INTEGER,                       -- 判定范围界（创建时快照自 level_rules；空=未标定，取最大 active 层）
    status      TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','closed')),
    grade       TEXT CHECK (grade IS NULL OR grade IN ('excellent','good','pass','fail')),
    grade_note  TEXT,                          -- 无法评级原因/公式说明
    degraded    INTEGER NOT NULL DEFAULT 0,    -- 会话中出现降级自评
    closed_at   TEXT,
    created_by  INTEGER NOT NULL,              -- 员工 id（sys_staff.id，试判台=内容维护者）
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_judge_session_kp ON judge_session (kp_id, created_at);

CREATE TABLE IF NOT EXISTS judge_answer (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id     INTEGER NOT NULL REFERENCES judge_session (id),
    followup_id    TEXT,                       -- null=题面作答；否则追问层 id（永久身份，红线 2）
    question_text  TEXT NOT NULL,              -- 作答时的问题快照（题面/追问层内容）
    layer          INTEGER NOT NULL DEFAULT 0, -- 0=题面，追问层为层号
    answer_text    TEXT NOT NULL,
    verdict        TEXT CHECK (verdict IS NULL OR verdict IN ('correct','missing','wrong','empty')),
    source         TEXT NOT NULL DEFAULT 'llm' CHECK (source IN ('llm','self','code')),  -- code=空答短路，self=降级自评
    points_result  TEXT NOT NULL DEFAULT '[]', -- 评分要点全量快照 [{"id","level","content","hit","quote"}] 含未命中项
    wrong_note     TEXT,
    error_note     TEXT,                       -- 判定失败留痕（解锁自评的前置证据）
    created_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_judge_answer_session ON judge_answer (session_id);

-- T10 个人档案模块（核心模块定义 4.1）：简历档案/技能条/项目素材，全产品的个人上下文锚点。
-- 档案状态机 draft(建档中) → confirmed(技能条齐) → finalized(定稿)；存在未确认技能条不得定稿，
-- 未定稿不参与学习路径生成（消费方 T11+）。is_active 为当前档案标记，部分唯一索引保证每客户至多一个。
CREATE TABLE IF NOT EXISTS prf_profile (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_id    INTEGER NOT NULL REFERENCES sys_customer (id),
    name           TEXT    NOT NULL,                   -- 档案名（如"Java 后端"）
    resume_text    TEXT    NOT NULL DEFAULT '',        -- 简历全文（一期粘贴文本，上传解析后置）
    years          INTEGER,                            -- 工作年限（补齐表单，职级缺省推测依据）
    position       TEXT    NOT NULL DEFAULT '',        -- 目标岗位
    jd_text        TEXT    NOT NULL DEFAULT '',        -- 目标岗位 JD
    tech_stack     TEXT    NOT NULL DEFAULT '',        -- 技术栈（自由文本）
    target_level   TEXT    CHECK (target_level IS NULL OR target_level IN ('junior','mid','senior','expert','master')),
    plan_weeks     INTEGER,                            -- 备考周期（周）
    daily_minutes  INTEGER,                            -- 每日可投入时间（分钟）
    status         TEXT    NOT NULL DEFAULT 'draft'
                   CHECK (status IN ('draft','confirmed','finalized')),
    is_active      INTEGER NOT NULL DEFAULT 0,         -- 当前档案标记
    finalized_at   TEXT,
    created_at     TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at     TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_prf_profile_customer ON prf_profile (customer_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_prf_profile_active ON prf_profile (customer_id) WHERE is_active = 1;

-- 技能条（Skill Claim，6.5）：一段简历措辞 + 锚定知识点集合。锚点必须真实存在且 available（代码校验，
-- 红线：AI 不编造考点）；anchor_ids JSON 数组 [kpId]，String 列由 service 层序列化（先例 KbKp）。
CREATE TABLE IF NOT EXISTS prf_claim (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id   INTEGER NOT NULL REFERENCES prf_profile (id),
    wording      TEXT    NOT NULL,                    -- 简历措辞
    anchor_ids   TEXT    NOT NULL DEFAULT '[]',       -- 锚定知识点 id [kpId]
    source       TEXT    NOT NULL DEFAULT 'ai' CHECK (source IN ('ai','manual')),  -- AI 起草采纳 / 手动添加
    status       TEXT    NOT NULL DEFAULT 'unconfirmed' CHECK (status IN ('unconfirmed','confirmed')),
    sort_order   INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_prf_claim_profile ON prf_claim (profile_id, sort_order);

-- 项目素材（Case，6.5）：建档沉淀的项目细节（背景/数字/冲突/结果），供叙事练习与模拟考追问消费（T11+）。
CREATE TABLE IF NOT EXISTS prf_case (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id   INTEGER NOT NULL REFERENCES prf_profile (id),
    project_name TEXT    NOT NULL,
    background   TEXT    NOT NULL DEFAULT '',         -- 背景
    metrics      TEXT    NOT NULL DEFAULT '',         -- 数字
    conflict     TEXT    NOT NULL DEFAULT '',         -- 冲突/难点
    result       TEXT    NOT NULL DEFAULT '',         -- 结果
    created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_prf_case_profile ON prf_case (profile_id);