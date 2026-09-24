-- 知己 · 演示表（仅用于验证 SQLite 环境，非最终 schema）
-- 目的：验证 CHECK 约束生效、JSON 存储、外键、自增主键

CREATE TABLE IF NOT EXISTS demo_kp (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,           -- 自增主键
    biz_id      TEXT    NOT NULL UNIQUE,                     -- 业务 id，模拟 {簇}-{slug}
    title       TEXT    NOT NULL,                            -- 知识点标题
    sub_type    TEXT    NOT NULL DEFAULT 'default'           -- 题型：default 默认八股 / design_chain 设计演进链 / narrative 项目叙事
                CHECK (sub_type IN ('default','design_chain','narrative')),
    difficulty  INTEGER NOT NULL                             -- 难度 1~5
                CHECK (difficulty BETWEEN 1 AND 5),
    followups   TEXT    NOT NULL DEFAULT '[]',               -- 追问链（模拟 JSON 列）
    created_at  TEXT    NOT NULL DEFAULT (datetime('now'))   -- 创建时间
);

CREATE INDEX IF NOT EXISTS idx_demo_kp_sub_type ON demo_kp (sub_type);

-- 知己 · 正式表（自 T1 起按开发计划逐模块追加；字段级 DDL 以各模块落库时定稿）

CREATE TABLE IF NOT EXISTS sys_staff (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,         -- 自增主键
    username      TEXT    NOT NULL UNIQUE,                   -- 登录名
    password_hash TEXT    NOT NULL,                          -- 口令散列（不存明文）
    role          TEXT    NOT NULL                           -- 角色：admin 管理员（全权）/ editor 内容维护者
                  CHECK (role IN ('admin','editor')),
    status        TEXT    NOT NULL DEFAULT 'active'          -- 账号状态：active 正常 / disabled 停用（停用即会话失效并拒绝登录）
                  CHECK (status IN ('active','disabled')),
    created_at    TEXT    NOT NULL DEFAULT (datetime('now')),   -- 建号时间（管理员创建，不经公开路径产生）
    last_login_at TEXT                                         -- 最近登录时间
);

CREATE TABLE IF NOT EXISTS sys_customer (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,         -- 自增主键
    username      TEXT    NOT NULL UNIQUE,                   -- 登录名
    password_hash TEXT    NOT NULL,                          -- 口令散列（不存明文）
    status        TEXT    NOT NULL DEFAULT 'active'          -- 账号状态：active 正常 / disabled 停用（停用即会话失效并拒绝登录）
                  CHECK (status IN ('active','disabled')),
    registered_at TEXT    NOT NULL DEFAULT (datetime('now')),   -- 注册时间
    last_login_at TEXT                                         -- 最近登录时间
);

-- T2 llm 支撑模块：运行参数（模型路由 / 限额 / 全站熔断）存配置表，B 端 /admin/settings 读写，改动即生效。
CREATE TABLE IF NOT EXISTS sys_config (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,           -- 自增主键
    config_key   TEXT    NOT NULL UNIQUE,                     -- 运行参数键（唯一）
    config_value TEXT    NOT NULL,                            -- 参数值
    description  TEXT,                                        -- 参数说明
    updated_at   TEXT    NOT NULL DEFAULT (datetime('now'))   -- 更新时间
);

-- LLM 调用计数（入口 × 天）。profile_id 用 0 哨兵而非 NULL：
-- SQLite UNIQUE 索引里 NULL 互不相等，用 NULL 会让「同日同入口的无档案计数」产生多行、upsert 失效。
-- purpose 刻意不加 CHECK：入口随业务模块持续增加（T6-T12），而 CREATE TABLE IF NOT EXISTS 改不了既有约束，
-- 加了 CHECK 会让旧库写入新入口直接失败；取值对齐由 LlmPurpose 枚举与前端 types.ts 保证（附录 A 8.2）。
CREATE TABLE IF NOT EXISTS llm_usage_daily (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,             -- 自增主键
    stat_date  TEXT    NOT NULL,                              -- 统计日（计数粒度：天）
    purpose    TEXT    NOT NULL,                              -- LLM 调用入口（用途），取值对齐 LlmPurpose 枚举
    profile_id INTEGER NOT NULL DEFAULT 0,                    -- 归属档案 id，0 为无档案 / B 端人工触发的哨兵
    call_count INTEGER NOT NULL DEFAULT 0,                    -- 当日该入口调用次数
    fail_count INTEGER NOT NULL DEFAULT 0,                    -- 当日该入口失败次数
    updated_at TEXT    NOT NULL DEFAULT (datetime('now'))    -- 更新时间
);

CREATE INDEX IF NOT EXISTS idx_llm_usage_date ON llm_usage_daily (stat_date);

-- T4 知识库模块（kb）：技能域先落表（T3 跳过期的最小边界，管理接口随 T3），簇/知识点/前置/频率信号。
-- 枚举口径对齐 docs/核心模块定义.md 附录 A 8.2；字段级 DDL 以本文件为准。

CREATE TABLE IF NOT EXISTS skill_domain (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,            -- 自增主键
    name        TEXT    NOT NULL UNIQUE,                      -- 域名（唯一）
    description TEXT,                                         -- 域说明
    sort_order  INTEGER NOT NULL DEFAULT 0,                   -- 排序值（决定候选清单与关卡组默认序的展示次序）
    status      TEXT    NOT NULL DEFAULT 'active'             -- 状态：active 启用 / disabled 停用
                CHECK (status IN ('active','disabled')),
    created_at  TEXT    NOT NULL DEFAULT (datetime('now')),   -- 创建时间
    updated_at  TEXT    NOT NULL DEFAULT (datetime('now'))    -- 更新时间
);

CREATE TABLE IF NOT EXISTS kp_cluster (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,              -- 自增主键
    domain_id             INTEGER NOT NULL REFERENCES skill_domain (id),  -- 所属技能域
    name                  TEXT    NOT NULL,                               -- 簇名
    description           TEXT,                                           -- 簇说明
    zero_shot_md          TEXT,                                           -- 从零讲：面向完全没底子的读者（几百字一篇，簇内共享）
    reference_solution_md TEXT,                                           -- 完整参考方案：设计类知识点理想答案的来源（簇内共享）
    status                TEXT    NOT NULL DEFAULT 'draft'                -- 内容状态机：draft → reviewed → active，下线 / 合并转 retired
                          CHECK (status IN ('draft','reviewed','active','retired')),
    merged_into           INTEGER REFERENCES kp_cluster (id),             -- 被并入的目标簇（本簇转 retired 并保留指向）
    last_reviewed_at      TEXT,                                           -- 最近校对时间
    review_due_at         TEXT,                                           -- 复核到期时间（到期内容进人工复核队列）
    created_at            TEXT    NOT NULL DEFAULT (datetime('now')),     -- 创建时间
    updated_at            TEXT    NOT NULL DEFAULT (datetime('now'))      -- 更新时间
);

CREATE INDEX IF NOT EXISTS idx_kp_cluster_domain ON kp_cluster (domain_id);
CREATE INDEX IF NOT EXISTS idx_kp_cluster_review_due ON kp_cluster (review_due_at);

CREATE TABLE IF NOT EXISTS kp_main (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,            -- 自增主键
    cluster_id          INTEGER NOT NULL REFERENCES kp_cluster (id),  -- 所属主题簇
    sub_type            TEXT    NOT NULL DEFAULT 'default'            -- 题型：default 默认八股 / design_chain 设计演进链 / narrative 项目叙事
                        CHECK (sub_type IN ('default','design_chain','narrative')),
    title               TEXT    NOT NULL,                             -- 知识点标题
    variants            TEXT    NOT NULL DEFAULT '[]',                -- 问法变体（防背追问链，允许 AI 在评分要点范围内改写）
    narrative_framework TEXT,                                         -- 叙述框架：给「大概懂但讲不完整」的人做回忆结构
    followups           TEXT    NOT NULL DEFAULT '[]',                -- 追问链（各层带 layer_key 稳定身份与 dir 方向）
    rubric              TEXT    NOT NULL DEFAULT '[]',                -- 分级评分要点（basic/mid/senior，LLM 只做对齐判断的尺子）
    samples             TEXT    NOT NULL DEFAULT '{}',                -- 三档标准回答样例：优秀 / 良好 / 及格
    level_req           TEXT    NOT NULL DEFAULT '{}',                -- 职级要求（每档 required 是否必问 + stop_layer 下钻停止层）
    baseline_levels     TEXT    NOT NULL DEFAULT '',                  -- level_req 的职级基线查询实列（SQLite 陷阱 E 实列化先例）
    difficulty          INTEGER NOT NULL                              -- 难度 1~5（决定学习路径排期，与职级要求互不推导）
                        CHECK (difficulty BETWEEN 1 AND 5),
    status              TEXT    NOT NULL DEFAULT 'draft'              -- 内容状态机：draft → reviewed → active，下线 / 合并转 retired
                        CHECK (status IN ('draft','reviewed','active','retired')),
    merged_into         INTEGER REFERENCES kp_main (id),              -- 合并去向（并入后本条转 retired 并保留指向）
    last_reviewed_at    TEXT,                                         -- 最近校对时间
    review_due_at       TEXT,                                         -- 复核到期时间（到期内容进人工复核队列）
    created_at          TEXT    NOT NULL DEFAULT (datetime('now')),   -- 创建时间
    updated_at          TEXT    NOT NULL DEFAULT (datetime('now'))    -- 更新时间
);

CREATE INDEX IF NOT EXISTS idx_kp_main_cluster ON kp_main (cluster_id);
CREATE INDEX IF NOT EXISTS idx_kp_main_status ON kp_main (status);
CREATE INDEX IF NOT EXISTS idx_kp_main_review_due ON kp_main (review_due_at);

CREATE TABLE IF NOT EXISTS kp_prereq (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,           -- 自增主键
    kp_id        INTEGER NOT NULL REFERENCES kp_main (id),    -- 当前知识点
    prereq_kp_id INTEGER NOT NULL REFERENCES kp_main (id)     -- 前置知识点（先懂它才懂 kp_id；写入时防环）
);

CREATE INDEX IF NOT EXISTS idx_kp_prereq_target ON kp_prereq (prereq_kp_id);

-- signal_count 列名刻意不用 count：count 在 SQL 语境易与聚合函数混淆，列名规避。
CREATE TABLE IF NOT EXISTS kp_freq_signal (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,           -- 自增主键
    kp_id        INTEGER NOT NULL REFERENCES kp_main (id),    -- 所属知识点
    source       TEXT    NOT NULL                             -- 信号来源：interview 面经 / coverage 题库覆盖 / manual 人工标定
                 CHECK (source IN ('manual','interview','coverage')),
    signal_level TEXT                                         -- 标定档位：high / mid / low（非人工来源可空）
                 CHECK (signal_level IS NULL OR signal_level IN ('high','mid','low')),
    signal_count INTEGER NOT NULL DEFAULT 1,                  -- 信号计数（面经出现次数 / 题库覆盖源数）
    note         TEXT,                                        -- 标定理由 / 备注
    created_at   TEXT    NOT NULL DEFAULT (datetime('now'))   -- 创建时间
);

CREATE INDEX IF NOT EXISTS idx_kp_freq_kp ON kp_freq_signal (kp_id);

-- T6 素材库（pipeline）：素材文档与候选条目。枚举口径对齐核心模块定义附录 A 8.2；
-- 版权红线：raw_text 是过程材料，只留素材文档侧供 B 端预览，绝不进 kp_* 内容表。

CREATE TABLE IF NOT EXISTS kp_source_doc (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,           -- 自增主键
    batch_id     TEXT    NOT NULL,                            -- 导入批次号（批次是查询维度非实体，批次面板按此聚合）
    file_name    TEXT    NOT NULL,                            -- 文件名（zip 解包时含相对目录，作来源线索）
    source_ref   TEXT,                                        -- 来源出处：链接 / 书名·手册章节（D17，随管道向下传递）
    form         TEXT,                                        -- 内容形态：question_bank 题库 / reading 资料 / interview 面经
                                                              -- （LLM 识别 + 人工修正；未解析时为空，故不设 CHECK）
    status       TEXT    NOT NULL DEFAULT 'pending'           -- source_doc_status 状态机：待解析/解析成功/解析失败/已拆题
                 CHECK (status IN ('pending','parsed','parse_failed','split')),
    parse_error  TEXT,                                        -- 解析失败原因（扫描件/超限/格式损坏等，显式落状态不静默）
    raw_text     TEXT,                                        -- 解析文本（过程材料，只进 B 端预览）
    heading_tree TEXT    NOT NULL DEFAULT '[]',               -- 标题树 JSON [{level,title,line}]（章节切分依据）
    char_count   INTEGER NOT NULL DEFAULT 0,                  -- 解析文本字符数
    created_at   TEXT    NOT NULL DEFAULT (datetime('now')),  -- 导入时间
    updated_at   TEXT    NOT NULL DEFAULT (datetime('now'))   -- 更新时间
);

CREATE INDEX IF NOT EXISTS idx_kp_source_doc_batch ON kp_source_doc (batch_id);
CREATE INDEX IF NOT EXISTS idx_kp_source_doc_status ON kp_source_doc (status);

CREATE TABLE IF NOT EXISTS kp_candidate (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,        -- 自增主键
    doc_id          INTEGER NOT NULL REFERENCES kp_source_doc (id),  -- 溯源回素材文档
    batch_id        TEXT    NOT NULL,                         -- 继承批次（免 join 过滤）
    title           TEXT    NOT NULL,                         -- 候选题面
    answer_evidence TEXT,                                     -- 答案证据：题库型=答案段 / 资料型=AI 提炼要点 / 面经型=追问记录
    section_path    TEXT,                                     -- 章节路径（如 "Java集合 > HashMap"）
    form            TEXT    NOT NULL                             -- 片段级形态（混合文档片段级标签可人工修正）
                         CHECK (form IN ('question_bank','reading','interview')),
    status          TEXT    NOT NULL DEFAULT 'pending'           -- candidate_status 状态机（附录 A 8.2）
                         CHECK (status IN ('pending','matched','converting','converted','merged','discarded')),
    canonical_id    INTEGER,                                  -- 同题归一指向（kp_canonical 随 T7 落库，先留普通列）
    confidence      TEXT,                                     -- 拆题置信：high 结构命中 / low LLM 兜底（批量确认策略依据）
    note            TEXT,                                     -- 人工备注（放弃原因等）
    created_at      TEXT    NOT NULL DEFAULT (datetime('now')),   -- 入池时间
    updated_at      TEXT    NOT NULL DEFAULT (datetime('now'))    -- 更新时间
);

CREATE INDEX IF NOT EXISTS idx_kp_candidate_doc ON kp_candidate (doc_id);
CREATE INDEX IF NOT EXISTS idx_kp_candidate_status ON kp_candidate (status);

-- T7 同题归一（pipeline 二）：归一题是语料层聚合节点，距用户路径还隔成稿与校对两道关。
-- 覆盖源数参与排序与统计，实列化（陷阱 E）；入库转 kp_freq_signal 发生在成稿入库时，本表只存值。
-- cluster_id / built_kp_id 是跨模块逻辑引用（kp_cluster / kp_main 归 kb），不加 FK，口径同 answer_log.attempt_id。
CREATE TABLE IF NOT EXISTS kp_canonical (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,    -- 自增主键
    title          TEXT    NOT NULL,                     -- 归一题面（组内覆盖源数最多成员的题面）
    cluster_id     INTEGER,                              -- 归属语料簇（draft 态 kp_cluster）
    coverage_count INTEGER NOT NULL DEFAULT 0,           -- 覆盖源数：去重素材文档数（最硬的高频证据）
    status         TEXT    NOT NULL DEFAULT 'pending'    -- canonical_status 状态机（附录 A 8.2）：AI 聚合待确认/人工确认入图/已建设
                   CHECK (status IN ('pending','confirmed','built')),
    built_kp_id    INTEGER,                              -- 已建设知识点指向（演化锚点 D21）
    judge_basis    TEXT,                                 -- 聚合依据：structural 结构强命中 / exact 极高相似 / llm LLM 精判（人工抽检过滤维度）
    created_at     TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at     TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_kp_canonical_cluster ON kp_canonical (cluster_id);
CREATE INDEX IF NOT EXISTS idx_kp_canonical_status ON kp_canonical (status);
