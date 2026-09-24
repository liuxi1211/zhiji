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

-- T4 知识库模块（kb）：技能域先落表（T3 跳过期的最小边界，管理接口随 T3），簇/知识点/前置/频率信号。
-- 枚举口径对齐 docs/核心模块定义.md 附录 A 8.2；字段级 DDL 以本文件为准。

CREATE TABLE IF NOT EXISTS skill_domain (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL UNIQUE,
    description TEXT,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    status      TEXT    NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
    created_at  TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS kp_cluster (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    domain_id             INTEGER NOT NULL REFERENCES skill_domain (id),
    name                  TEXT    NOT NULL,
    description           TEXT,
    zero_shot_md          TEXT,
    reference_solution_md TEXT,
    status                TEXT    NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','reviewed','active','retired')),
    merged_into           INTEGER REFERENCES kp_cluster (id),
    last_reviewed_at      TEXT,
    review_due_at         TEXT,
    created_at            TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at            TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_kp_cluster_domain ON kp_cluster (domain_id);
CREATE INDEX IF NOT EXISTS idx_kp_cluster_review_due ON kp_cluster (review_due_at);

CREATE TABLE IF NOT EXISTS kp_main (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    cluster_id          INTEGER NOT NULL REFERENCES kp_cluster (id),
    sub_type            TEXT    NOT NULL DEFAULT 'default' CHECK (sub_type IN ('default','design_chain','narrative')),
    title               TEXT    NOT NULL,
    variants            TEXT    NOT NULL DEFAULT '[]',
    narrative_framework TEXT,
    followups           TEXT    NOT NULL DEFAULT '[]',
    rubric              TEXT    NOT NULL DEFAULT '[]',
    samples             TEXT    NOT NULL DEFAULT '{}',
    level_req           TEXT    NOT NULL DEFAULT '{}',
    baseline_levels     TEXT    NOT NULL DEFAULT '',
    difficulty          INTEGER NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
    status              TEXT    NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','reviewed','active','retired')),
    merged_into         INTEGER REFERENCES kp_main (id),
    last_reviewed_at    TEXT,
    review_due_at       TEXT,
    created_at          TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at          TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_kp_main_cluster ON kp_main (cluster_id);
CREATE INDEX IF NOT EXISTS idx_kp_main_status ON kp_main (status);
CREATE INDEX IF NOT EXISTS idx_kp_main_review_due ON kp_main (review_due_at);

CREATE TABLE IF NOT EXISTS kp_prereq (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    kp_id        INTEGER NOT NULL REFERENCES kp_main (id),
    prereq_kp_id INTEGER NOT NULL REFERENCES kp_main (id),
    UNIQUE (kp_id, prereq_kp_id)
);

CREATE INDEX IF NOT EXISTS idx_kp_prereq_target ON kp_prereq (prereq_kp_id);

-- signal_count 列名刻意不用 count：count 在 SQL 语境易与聚合函数混淆，列名规避。
CREATE TABLE IF NOT EXISTS kp_freq_signal (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    kp_id        INTEGER NOT NULL REFERENCES kp_main (id),
    source       TEXT    NOT NULL CHECK (source IN ('manual','interview','coverage')),
    signal_level TEXT    CHECK (signal_level IS NULL OR signal_level IN ('high','mid','low')),
    signal_count INTEGER NOT NULL DEFAULT 1,
    note         TEXT,
    created_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_kp_freq_kp ON kp_freq_signal (kp_id);