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