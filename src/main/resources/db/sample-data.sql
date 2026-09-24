-- ============================================================================
-- T4 知识库 · 10 个样板知识点种子数据（知识库PRD 12.1 阶段 2）
--
-- 用途：样板数据集——8 条默认八股（ES 写入链路簇）+ 1 条项目叙事 + 1 条设计演进链，
--       全部走完草稿到已上线，用于走通「路径排序 / 学习卡片 / 模拟考判定」三类消费方。
--
-- 幂等语义：本文件可重复执行不产生重复数据——
--       技能域 name 有 UNIQUE，用 INSERT OR IGNORE；
--       簇（自然键 name）与知识点（自然键 title）无唯一约束，
--       用 INSERT ... SELECT ... WHERE NOT EXISTS（自然键 = 常量）模式。
--       前置依赖按 (kp_id, prereq_kp_id) 有 UNIQUE 之外再补 NOT EXISTS；频率信号按
--       (kp_id, source='manual') 判重，避免手工导入与测试执行叠加。
--
-- 用法：sqlite3 zhiji.db < db/sample-data.sql 手工导入，或由 SampleDataTest 执行。
--       刻意不挂入 spring.sql.init（application.yml 只挂 schema.sql），不在启动时自动执行。
--
-- JSON 列约定：variants/followups/rubric/samples/level_req 存 TEXT，
--       字段名与 KpStructureValidator 的 record 组件一致（layerKey/stopCondition/pointId/stopLayer）。
--       Markdown 换行写成 \n 转义；JSON 内双引号写成 \"；SQL 字符串内不出现单引号。
--       pointId 由种子显式分配（p00000001 起连续唯一）——种子直插 SQL 不经过后端
--       pointId 分配流程，必须自带稳定身份。baseline_levels 与 level_req 的 stopLayer
--       非空档位保持一致（junior,mid,senior,staff,expert 枚举序逗号拼接）。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 一、技能域（2 条，name UNIQUE）
-- ----------------------------------------------------------------------------

INSERT OR IGNORE INTO skill_domain (name, description, sort_order, status)
VALUES ('Elasticsearch', '分布式检索引擎，覆盖写入链路、检索原理与集群容灾', 1, 'active');

INSERT OR IGNORE INTO skill_domain (name, description, sort_order, status)
VALUES ('Java 后端', '服务端工程能力，覆盖高并发架构、缓存体系与项目表达', 2, 'active');

-- ----------------------------------------------------------------------------
-- 二、主题簇（3 条，自然键 name）
-- ----------------------------------------------------------------------------

INSERT INTO kp_cluster (domain_id, name, description, zero_shot_md, reference_solution_md, status, last_reviewed_at, review_due_at)
SELECT d.id, 'ES 写入链路',
       '从一条写入看 ES：buffer、translog、refresh、flush 与副本复制',
       '# 从零讲：ES 写入链路\n\n你往 ES 里写一条数据，它并不是立刻能被搜到，也不是立刻就安全落盘。整条链路是：请求先到协调节点，按文档路由找到主分片；主分片把数据追加进内存 buffer，同时追加一份 translog 当持久化保险；默认每隔 1 秒执行一次 refresh，把 buffer 生成一个新的段放进文件系统缓存，这时数据才可搜；最后由 flush 把段真正 fsync 到磁盘并清空 translog。副本分片按同样流程回放一遍。理解了这条链，近实时、不丢数据、写入快这三件事就都有了答案。\n\n练习建议：先用自己的话把这条链讲顺，再逐层应答追问。',
       '## 参考方案：一条写入的生命周期\n\n1. 协调节点校验并路由到主分片；\n2. 主分片写 buffer 与 translog，refresh 生成可搜新段；\n3. flush 时段 fsync 落盘、translog 换代；\n4. 主分片并行复制给副本，全部活跃副本确认后才返回成功。\n\n```mermaid\nflowchart TD\n  A[写入请求] --> B{协调节点}\n  B -->|路由 hash| C[主分片]\n  C --> D[(translog 顺序追加)]\n  C --> E[内存 buffer]\n  E -->|refresh 默认 1s| F[新段 · 可搜]\n  F -->|flush| G[(磁盘 fsync + translog 换代)]\n  C -->|并行复制| H[副本分片回放]\n```\n\n常见追问入口：路由公式、refresh 与 flush 分工、副本失败语义。',
       'active', datetime('now'), datetime('now', '+90 day')
FROM skill_domain d
WHERE d.name = 'Elasticsearch'
  AND NOT EXISTS (SELECT 1 FROM kp_cluster c WHERE c.name = 'ES 写入链路');

INSERT INTO kp_cluster (domain_id, name, description, zero_shot_md, reference_solution_md, status, last_reviewed_at, review_due_at)
SELECT d.id, '高并发系统设计',
       '从约束推结构的架构演进题，覆盖缓存、一致性、降级与取舍',
       '# 从零讲：高并发商品详情\n\n这类题不是背架构图，而是从约束推结构：先问读写比与峰值口径，再做量级估算，然后按十万级、百万级逐级加缓存与降级预案。每一级的动作都要能回答：这一层扛多少量、失手了谁兜底。\n\n| 层 | 承担 | 容量来源 |\n|---|---|---|\n| CDN | 静态资源与图片 | 命中率 90% 以上 |\n| 本地缓存 | 热点商品读 | 应用机内存，TTL 秒级 |\n| Redis | 长尾读 | 分片数 x 单分片 10 万 QPS |\n| MySQL | 写与兜底 | 按峰值写 QPS 压测定容 |\n\n估算先行，结构随后，取舍收尾。',
       '## 参考方案骨架\n\n1. 接入层：静态化、限流、热点探测；\n2. 缓存层：本地缓存存热点，Redis 分片存长尾，回源只允许单飞；\n3. 存储层：binlog 订阅失效缓存，DB 按压测容量熔断保护；\n4. 降级链：裁剪字段 -> 兜底页 -> 限流，恢复按写入侧到读取侧逆序。\n\n```java\n// 回源保护：本地缓存未命中时同一 key 只放一个请求回源\nObject value = localCache.get(key, k -> redisClient.get(k));\nif (value == null) {\n    value = loadFromDbWithFuse(key);\n}\n```\n\n每层都要给出失效时谁兜底的答案，答不出兜底的层就是单点。',
       'active', datetime('now'), datetime('now', '+90 day')
FROM skill_domain d
WHERE d.name = 'Java 后端'
  AND NOT EXISTS (SELECT 1 FROM kp_cluster c WHERE c.name = '高并发系统设计');

INSERT INTO kp_cluster (domain_id, name, description, zero_shot_md, reference_solution_md, status, last_reviewed_at, review_due_at)
SELECT d.id, '通用项目叙事',
       '六要素模板下的项目表达练习，验证叙事结构与事实确认',
       '# 从零讲：项目叙事六要素\n\n面试官要的不是项目说明书，而是三分钟内判断你做了什么、想得多深。固定骨架：背景与目标、我的角色、方案与冲突、结果数字、复盘、可迁移经验。每个要素一两句话，先结论后展开，冲突要真实、数字要能对上目标、复盘要落到动作。',
       '## 参考叙事骨架\n\n1. 背景：业务痛点一句话；\n2. 目标：量化口径一句话；\n3. 冲突：分歧点加上自己的推进动作；\n4. 结果：与目标对得上的数字；\n5. 复盘：一条具体的、已验证的改进。\n\n三分钟收口：时间分配建议背景一成、冲突与方案五成、结果与复盘四成。',
       'active', datetime('now'), datetime('now', '+90 day')
FROM skill_domain d
WHERE d.name = 'Java 后端'
  AND NOT EXISTS (SELECT 1 FROM kp_cluster c WHERE c.name = '通用项目叙事');

-- ----------------------------------------------------------------------------
-- 三、默认八股（8 条，挂「ES 写入链路」簇，难度覆盖 2/3/4/5）
-- ----------------------------------------------------------------------------

-- 1. 写入流程（难度 3，junior/mid 档）
INSERT INTO kp_main (cluster_id, sub_type, title, variants, narrative_framework, followups, rubric, samples, level_req, baseline_levels, difficulty, status, last_reviewed_at, review_due_at)
SELECT c.id, 'default',
       'ES 写入一条数据的完整流程是什么？',
       '["ES 里一条数据从写入到可搜经历了什么","describe 一下 ES 的写入流程","ES 写入数据的底层过程"]',
       NULL,
       '[{"layerKey":"L1","dir":"深入","text":"协调节点在这一步做了哪些事？路由到主分片的规则是什么？","expect":"能说出路由公式 hash(routing) 取模主分片数与转发","stopCondition":"答出路由公式并提到主分片数不可变更","status":"active"},{"layerKey":"L2","dir":"深入","text":"主分片写成功后，副本分片没写成功怎么办？","expect":"能说出 in-sync 副本组与写入等待语义、超时降级","stopCondition":"答出 in-sync 与部分副本失败的处理","status":"active"},{"layerKey":"L3","dir":"实践","text":"写入耗时分布里哪一段最可能成为瓶颈？怎么定位？","expect":"能说 refresh 频率与段生成，用慢日志与写入线程池指标定位","stopCondition":"给出至少一个定位手段","status":"active"}]',
       '[{"layerKey":"main","points":[{"pointId":"p00000001","level":"basic","text":"能按顺序说全 buffer、段、translog、可搜、落盘的主干"},{"pointId":"p00000002","level":"mid","text":"能说清 refresh 使数据可搜而 flush 才落盘，两者职责不同"}]},{"layerKey":"L1","points":[{"pointId":"p00000003","level":"mid","text":"能写出路由公式并解释 routing 默认取文档 _id"},{"pointId":"p00000004","level":"senior","text":"能解释主分片数为何建索引后不可再改"}]},{"layerKey":"L2","points":[{"pointId":"p00000005","level":"mid","text":"能说出 in-sync 副本组概念与等待活跃副本的写入语义"}]},{"layerKey":"L3","points":[{"pointId":"p00000006","level":"senior","text":"能结合写入队列拒绝率、refresh 耗时等指标给出定位路径"}]}]',
       '{"pass":"写入请求先到协调节点，按文档路由找到主分片；主分片把数据写进内存 buffer，同时追加一条 translog；refresh 时 buffer 生成新段进入文件系统缓存，数据变得可搜；后台 flush 时段被 fsync 落盘并清空 translog；主分片写完后并行复制到副本分片。","good":"在 pass 主干上补充：协调节点先做合法性校验再按 hash(routing) 取模主分片数路由；refresh_interval 默认 1s；translog 默认 index.translog.durability=request，每请求 fsync 成功才向客户端确认；flush 由 translog 大小（默认 512MB）或定时（默认 5 分钟）触发；副本全部写成功后协调节点才返回。","excellent":"在 good 基础上带实践数字与取舍：大促前把 refresh_interval 从 1s 调到 30s，写入吞吐从 8k/s 提到 2w/s；批量导入时临时关副本、调大 translog.flush_threshold_size，导完恢复；代价是故障时可能丢最近 30s 数据、可搜延迟变长，靠 translog 重放兜底——写吞吐与可搜延迟之间的取舍要能说出口。"}',
       '{"junior":{"required":true,"stopLayer":"main"},"mid":{"required":true,"stopLayer":"L2"},"senior":{"required":false,"stopLayer":null},"staff":{"required":false,"stopLayer":null},"expert":{"required":false,"stopLayer":null}}',
       'junior,mid', 3, 'active', datetime('now'), datetime('now', '+90 day')
FROM kp_cluster c
WHERE c.name = 'ES 写入链路'
  AND NOT EXISTS (SELECT 1 FROM kp_main k WHERE k.title = 'ES 写入一条数据的完整流程是什么？');

-- 2. 近实时（难度 2，junior/mid 档；前置：写入流程）
INSERT INTO kp_main (cluster_id, sub_type, title, variants, narrative_framework, followups, rubric, samples, level_req, baseline_levels, difficulty, status, last_reviewed_at, review_due_at)
SELECT c.id, 'default',
       '为什么 Elasticsearch 是近实时的？',
       '["ES 写入后多久可以搜到，为什么","为什么 ES 不是实时搜索引擎","ES 的近实时是什么意思"]',
       NULL,
       '[{"layerKey":"L1","dir":"深入","text":"refresh 具体做了什么，代价是什么？","expect":"能说出 refresh 把 buffer 生成新段并打开供搜索、带来 IO 与段数量代价","stopCondition":"说出 refresh 生成新段及代价","status":"active"},{"layerKey":"L2","dir":"实践","text":"什么场景你会调大 refresh_interval？怎么权衡？","expect":"能给出写多读少场景调大、牺牲可搜实时性换吞吐","stopCondition":"给出场景与权衡","status":"active"}]',
       '[{"layerKey":"main","points":[{"pointId":"p00000007","level":"basic","text":"能说出写入后默认约 1 秒可搜，不是立刻可搜"},{"pointId":"p00000008","level":"mid","text":"能解释近实时来自内存 buffer 到可搜段之间的 refresh 间隔"}]},{"layerKey":"L1","points":[{"pointId":"p00000009","level":"mid","text":"能说出 refresh 生成新段、打开段供搜索，段打开有 IO 与句柄成本"}]},{"layerKey":"L2","points":[{"pointId":"p00000010","level":"senior","text":"能结合业务场景给出 refresh_interval 取值与读写权衡"}]}]',
       '{"pass":"写入的数据先进内存 buffer，默认每 1 秒 refresh 一次，把 buffer 生成一个新的段放进文件系统缓存，搜索搜的是段；所以写入成功到可搜之间最多差一个 refresh 周期，这就是近实时而不是实时。","good":"补充：refresh_interval 可动态按索引调整，日志类场景常调到 30s；refresh 只保证可搜不保证持久，持久靠 translog；例外是按 _id 的 get 是实时读，能读到未 flush 的内容。","excellent":"带实践数字与取舍：日志集群把 refresh_interval 1s 调到 30s 后，segment 数从 3 万降到 2 千，CPU 省 15%；对个别文档用 ?refresh=wait_for 精准刷新，替代全局缩短 refresh 间隔；取舍是可搜延迟与段膨胀、refresh 风暴之间的平衡。"}',
       '{"junior":{"required":true,"stopLayer":"main"},"mid":{"required":true,"stopLayer":"L1"},"senior":{"required":false,"stopLayer":null},"staff":{"required":false,"stopLayer":null},"expert":{"required":false,"stopLayer":null}}',
       'junior,mid', 2, 'active', datetime('now'), datetime('now', '+90 day')
FROM kp_cluster c
WHERE c.name = 'ES 写入链路'
  AND NOT EXISTS (SELECT 1 FROM kp_main k WHERE k.title = '为什么 Elasticsearch 是近实时的？');

-- 3. 写入为什么快（难度 5，mid/senior 档；前置：倒排索引；good 样例含 sql 代码块）
INSERT INTO kp_main (cluster_id, sub_type, title, variants, narrative_framework, followups, rubric, samples, level_req, baseline_levels, difficulty, status, last_reviewed_at, review_due_at)
SELECT c.id, 'default',
       'Elasticsearch 写入为什么快？',
       '["ES 写入性能为什么这么高","ES 为什么写快读也快","ES 用什么数据结构支撑快速写入"]',
       NULL,
       '[{"layerKey":"L1","dir":"深入","text":"顺序追加写的段结构为什么对磁盘友好？","expect":"能说出顺序写对比随机写、LSM 思想、页缓存","stopCondition":"说出顺序写与 LSM 类比","status":"active"},{"layerKey":"L2","dir":"对比","text":"和 MySQL 的 B+ 树写入路径对比，代价差在哪？","expect":"能对比原地更新与追加写、写放大来源","stopCondition":"给出写放大或随机 IO 对比","status":"active"},{"layerKey":"L3","dir":"容灾","text":"快是有代价的，这些设计把什么复杂度转移到了查询与合并侧？","expect":"能说出段合并、删除标记、查询需合并多段","stopCondition":"说出至少两项代价","status":"active"}]',
       '[{"layerKey":"main","points":[{"pointId":"p00000011","level":"basic","text":"能说出倒排索引加顺序追加写是快的根源"},{"pointId":"p00000012","level":"mid","text":"能说出写路径几乎无随机 IO，主要成本转移到 refresh、merge、flush"},{"pointId":"p00000013","level":"senior","text":"能从 LSM 树角度解释写放大转移：写侧省下的 IO 在合并侧偿还"}]},{"layerKey":"L1","points":[{"pointId":"p00000014","level":"mid","text":"能解释段不可变带来顺序写与页缓存友好的原因"}]},{"layerKey":"L2","points":[{"pointId":"p00000015","level":"senior","text":"能对比 B+ 树原地更新与 LSM 追加写的写放大差异"}]},{"layerKey":"L3","points":[{"pointId":"p00000016","level":"senior","text":"能说出段合并占用 IO、删除标记延迟回收、多段查询合并三项代价"}]}]',
       '{"pass":"因为底层是倒排索引加段式存储：写入是顺序追加生成新段，没有随机 IO；段不可变，可以被页缓存高效兜住；删除和更新只打标记不改老数据，所以写路径非常短。","good":"补充：posting list 有 FOR、Roaring Bitmap 等压缩；doc values 列存支撑排序聚合不占堆；对比 MySQL 更新一行的 B+ 树原地改页还要写 undo/redo，是随机 IO，而 ES 全程顺序写。给一张对照：\n\n```sql\n-- 写路径对照（示意）\n-- ES   ：append 新段文件 + translog 顺序追加，磁盘顺序 IO\n-- MySQL：定位页 -> 改页内行 -> 写 redo/undo，多次随机 IO\n```","excellent":"带实践与取舍：批量导入从逐条改成 2MB 一批的 bulk 后，写入从 3k/s 到 2.5w/s；代价是段合并高峰把查询 P99 从 80ms 抬到 300ms，限流 index.merge.scheduler.max_thread_count=1 并错峰导入；快不是免费的，是把 IO 成本转移到了合并侧与查询侧。"}',
       '{"junior":{"required":false,"stopLayer":null},"mid":{"required":true,"stopLayer":"L1"},"senior":{"required":true,"stopLayer":"L3"},"staff":{"required":false,"stopLayer":null},"expert":{"required":false,"stopLayer":null}}',
       'mid,senior', 5, 'active', datetime('now'), datetime('now', '+90 day')
FROM kp_cluster c
WHERE c.name = 'ES 写入链路'
  AND NOT EXISTS (SELECT 1 FROM kp_main k WHERE k.title = 'Elasticsearch 写入为什么快？');

-- 4. refresh 与 flush（难度 4，mid/senior 档；excellent 样例含 json 代码块）
INSERT INTO kp_main (cluster_id, sub_type, title, variants, narrative_framework, followups, rubric, samples, level_req, baseline_levels, difficulty, status, last_reviewed_at, review_due_at)
SELECT c.id, 'default',
       'Elasticsearch 的 refresh 和 flush 有什么区别？',
       '["refresh 和 flush 分别做了什么","ES 里 fsync 发生在哪个阶段","refresh、flush、translog 三者关系"]',
       NULL,
       '[{"layerKey":"L1","dir":"深入","text":"translog 在这两个动作之间扮演什么角色？","expect":"能说出 refresh 后数据未落盘靠 translog 兜底，flush 以 translog 清空为收尾","stopCondition":"说出 translog 兜底与清空时机","status":"active"},{"layerKey":"L2","dir":"实践","text":"线上你怎么观察这两个动作的频率与耗时？","expect":"能说出 indices stats 的 refresh、flush 指标与慢日志","stopCondition":"给出至少一个观测手段","status":"active"}]',
       '[{"layerKey":"main","points":[{"pointId":"p00000017","level":"basic","text":"能说出 refresh 让数据可搜、flush 让数据真正落盘"},{"pointId":"p00000018","level":"mid","text":"能说清两者触发条件与各自开销的量级差异"}]},{"layerKey":"L1","points":[{"pointId":"p00000019","level":"mid","text":"能解释节点重启恢复时 translog 重放到上次 flush 提交点"}]},{"layerKey":"L2","points":[{"pointId":"p00000020","level":"senior","text":"能结合 _stats 与 flush、refresh 线程池指标定位写入毛刺"}]}]',
       '{"pass":"refresh 是把内存 buffer 生成新段让数据可搜，数据还在文件系统缓存里没真正落盘；flush 是把段 fsync 到磁盘并清空 translog，是真正的持久化动作。refresh 秒级高频发生，flush 低频得多、代价也大得多。","good":"补充触发条件：refresh 默认 1s 或被请求触发；flush 由 translog 大小（默认 512MB）或定时（默认 5 分钟）触发；flush 要做全量 fsync 并生成新一代 translog，开销远高于 refresh。","excellent":"带数字与观测：写高峰 flush 会造成 200ms 级写入毛刺，把 translog.durability 从 request 改为 async、flush_threshold_size 提到 1GB 后毛刺消失，代价是故障丢失窗口变大、恢复时间变长；用 _stats 观察：\n\n```json\n{\"_all\":{\"primaries\":{\"refresh\":{\"total\": 120000},\"flush\":{\"total\": 340}}}}\n```\nrefresh 与 flush 相差三个数量级属于正常水位。"}',
       '{"junior":{"required":false,"stopLayer":null},"mid":{"required":true,"stopLayer":"L1"},"senior":{"required":true,"stopLayer":"L2"},"staff":{"required":false,"stopLayer":null},"expert":{"required":false,"stopLayer":null}}',
       'mid,senior', 4, 'active', datetime('now'), datetime('now', '+90 day')
FROM kp_cluster c
WHERE c.name = 'ES 写入链路'
  AND NOT EXISTS (SELECT 1 FROM kp_main k WHERE k.title = 'Elasticsearch 的 refresh 和 flush 有什么区别？');

-- 5. translog（难度 3，junior/mid 档）
INSERT INTO kp_main (cluster_id, sub_type, title, variants, narrative_framework, followups, rubric, samples, level_req, baseline_levels, difficulty, status, last_reviewed_at, review_due_at)
SELECT c.id, 'default',
       'translog 在 Elasticsearch 写入中起什么作用？',
       '["translog 是干什么的","没有 translog 会出什么问题","ES 怎么保证写入不丢"]',
       NULL,
       '[{"layerKey":"L1","dir":"深入","text":"translog 的 durability 有哪几种配置？差别是什么？","expect":"能说出 request 级每请求 fsync 与 async 异步刷、各自的丢失窗口","stopCondition":"说出 request 与 async 的丢失窗口","status":"active"},{"layerKey":"L2","dir":"容灾","text":"节点断电重启后，ES 怎么用 translog 恢复数据？","expect":"能说出从上次 flush 提交点重放 translog","stopCondition":"说出重放机制","status":"active"}]',
       '[{"layerKey":"main","points":[{"pointId":"p00000021","level":"basic","text":"能说出 translog 先于可搜存在，是数据不丢的保证"},{"pointId":"p00000022","level":"mid","text":"能说出 translog 是顺序追加文件，fsync 成功才向客户端确认"}]},{"layerKey":"L1","points":[{"pointId":"p00000023","level":"mid","text":"能对比 request 与 async 的丢失窗口与吞吐差异"}]},{"layerKey":"L2","points":[{"pointId":"p00000024","level":"senior","text":"能说出基于 translog 的恢复流程与恢复时长权衡"}]}]',
       '{"pass":"数据写进内存 buffer 还没落盘时节点挂了就丢了，所以每次写入同时追加一条到 translog；translog 落盘成功才向客户端确认，重启时用它重放未 flush 的写入，保证已确认的数据不丢。","good":"补充：index.translog.durability=request 是默认，每请求 fsync；async 模式每 5s 刷一次，最多丢 5 秒；translog 按代（generation）分文件，flush 时切换新代并删旧代。","excellent":"带数字与取舍：金融类索引用 request 保零丢失，压测吞吐 1.2w/s；日志类改 async 后 2.1w/s，接受最多丢 5 秒；恢复时长与 translog 大小成正比，2GB translog 重放约 40 秒，所以大批量导入后主动 flush 一把，缩短故障恢复窗口。"}',
       '{"junior":{"required":true,"stopLayer":"main"},"mid":{"required":true,"stopLayer":"L2"},"senior":{"required":false,"stopLayer":null},"staff":{"required":false,"stopLayer":null},"expert":{"required":false,"stopLayer":null}}',
       'junior,mid', 3, 'active', datetime('now'), datetime('now', '+90 day')
FROM kp_cluster c
WHERE c.name = 'ES 写入链路'
  AND NOT EXISTS (SELECT 1 FROM kp_main k WHERE k.title = 'translog 在 Elasticsearch 写入中起什么作用？');

-- 6. 段合并（难度 4，mid/senior 档）
INSERT INTO kp_main (cluster_id, sub_type, title, variants, narrative_framework, followups, rubric, samples, level_req, baseline_levels, difficulty, status, last_reviewed_at, review_due_at)
SELECT c.id, 'default',
       'Elasticsearch 段合并是怎么发生的？',
       '["ES 的 segment merge 是怎么工作的","段什么时候合并，合并有什么影响","ES 删除的文档什么时候真正释放空间"]',
       NULL,
       '[{"layerKey":"L1","dir":"深入","text":"合并的触发条件和挑选策略是什么？","expect":"能说出合并策略按段大小分层挑选、看 deleted 文档比例","stopCondition":"说出分层挑选策略","status":"active"},{"layerKey":"L2","dir":"深入","text":"删除的文档到底是什么时候真正消失的？","expect":"能说出删除是标记，合并时物理丢弃","stopCondition":"说出合并时物理删除","status":"active"},{"layerKey":"L3","dir":"对比","text":"forcemerge 什么时候用、什么时候千万别用？","expect":"能说出只读索引归档前用，写中索引禁用","stopCondition":"给出正反场景","status":"active"}]',
       '[{"layerKey":"main","points":[{"pointId":"p00000025","level":"basic","text":"能说出为什么要段合并：小段太多导致查询变慢、文件句柄紧张"},{"pointId":"p00000026","level":"mid","text":"能说出合并把小段合成大段并顺带物理清除已删文档"},{"pointId":"p00000027","level":"senior","text":"能解释合并 IO 与查询性能的矛盾并给出限流手段"}]},{"layerKey":"L1","points":[{"pointId":"p00000028","level":"mid","text":"能说出 tiered 策略按段大小分层挑选与单段默认 5GB 上限"}]},{"layerKey":"L2","points":[{"pointId":"p00000029","level":"senior","text":"能解释删除标记如何拖慢查询并给出合并收敛的判断依据"}]},{"layerKey":"L3","points":[{"pointId":"p00000030","level":"senior","text":"能给出 forcemerge 的适用边界（只读归档可用、写中禁用）与误用后果"}]}]',
       '{"pass":"后台会把一堆小段合并成大段，合并时把打了删除标记的文档真正扔掉。不合并的话段越来越多，查询要扫的段数上涨会变慢，文件句柄也扛不住。","good":"补充：默认 tiered 合并策略，一次挑大小相近的约 10 个段；单段默认上限 5GB；合并吃大量 IO 与 CPU，需要限流；已删文档合并前只是被标记跳过，合并后才真正释放磁盘空间。","excellent":"带数字与取舍：日滚索引每天 3 亿条，靠自然 merge 收敛到每天 30 个段左右；归档索引 forcemerge 到单段后查询 P95 从 120ms 降到 70ms，但 300GB 的 forcemerge 要 2 小时 IO 打满，只能在只读归档时做；线上对写中索引手动 forcemerge 曾把写入打到拒绝——教训是写中让 merge 自然发生，只干预归档。"}',
       '{"junior":{"required":false,"stopLayer":null},"mid":{"required":true,"stopLayer":"L2"},"senior":{"required":true,"stopLayer":"L3"},"staff":{"required":false,"stopLayer":null},"expert":{"required":false,"stopLayer":null}}',
       'mid,senior', 4, 'active', datetime('now'), datetime('now', '+90 day')
FROM kp_cluster c
WHERE c.name = 'ES 写入链路'
  AND NOT EXISTS (SELECT 1 FROM kp_main k WHERE k.title = 'Elasticsearch 段合并是怎么发生的？');

-- 7. 倒排索引（难度 2，junior/mid 档）
INSERT INTO kp_main (cluster_id, sub_type, title, variants, narrative_framework, followups, rubric, samples, level_req, baseline_levels, difficulty, status, last_reviewed_at, review_due_at)
SELECT c.id, 'default',
       '倒排索引在 Elasticsearch 里是怎么构成的？',
       '["倒排索引的数据结构长什么样","term index 和 posting list 是什么关系","ES 检索为什么快，从倒排说起"]',
       NULL,
       '[{"layerKey":"L1","dir":"深入","text":"分词器在这一步做了什么？中文和英文处理有什么不同？","expect":"能说出 analyzer 的分词与归一化，以及中文分词难题","stopCondition":"说出 analyzer 结构与中文难点","status":"active"}]',
       '[{"layerKey":"main","points":[{"pointId":"p00000031","level":"basic","text":"能说出倒排是 term 到文档列表的映射，正排是文档到内容"},{"pointId":"p00000032","level":"mid","text":"能说出 term dictionary、term index、posting list 的分层结构"}]},{"layerKey":"L1","points":[{"pointId":"p00000033","level":"mid","text":"能说出 analyzer 由字符过滤、分词、归一化组成，中文需外置分词器"},{"pointId":"p00000034","level":"senior","text":"能解释 FST 前缀索引如何省内存、以及 posting list 的压缩"}]}]',
       '{"pass":"文档写入时先分词，把每个词和包含它的文档 id 建成映射：词到文档列表，这就是倒排；查询时先对查询串分词，再到映射里拿文档列表。正排是文档找内容，倒排是词找文档。","good":"补充结构分层：term index 用 FST 放内存做前缀定位，term dictionary 有序存磁盘，posting list 压缩存文档 id；分词由 analyzer 完成，中文要装 ik 这类分词器，英文默认按空格切分加词干归一。","excellent":"带数字与取舍：一亿文档的索引 term index 常驻内存几百 MB 量级；把 ik_max_word 换成 ik_smart 后索引体积小约 30% 但召回下降，按业务选；短标题字段单独配 analyzer、正文用另一套，是常见的内容结构取舍。"}',
       '{"junior":{"required":true,"stopLayer":"main"},"mid":{"required":true,"stopLayer":"L1"},"senior":{"required":false,"stopLayer":null},"staff":{"required":false,"stopLayer":null},"expert":{"required":false,"stopLayer":null}}',
       'junior,mid', 2, 'active', datetime('now'), datetime('now', '+90 day')
FROM kp_cluster c
WHERE c.name = 'ES 写入链路'
  AND NOT EXISTS (SELECT 1 FROM kp_main k WHERE k.title = '倒排索引在 Elasticsearch 里是怎么构成的？');

-- 8. 主副分片交互（难度 4，mid/senior 档）
INSERT INTO kp_main (cluster_id, sub_type, title, variants, narrative_framework, followups, rubric, samples, level_req, baseline_levels, difficulty, status, last_reviewed_at, review_due_at)
SELECT c.id, 'default',
       '主分片和副本分片在写入时是怎么交互的？',
       '["一条写入是怎么同步到副本的","主分片和副本分片的写入顺序","副本没写成功怎么办"]',
       NULL,
       '[{"layerKey":"L1","dir":"深入","text":"副本写入失败，这次写入算成功还是失败？","expect":"能说出 in-sync 副本数满足 quorum 即成功、失败副本移出后追赶","stopCondition":"说出 quorum 与失败上报","status":"active"},{"layerKey":"L2","dir":"容灾","text":"主分片所在节点这时挂了会怎样？","expect":"能说出重新选主、reroute、用序列号去重避免重复","stopCondition":"说出重新选主与一致性兜底","status":"active"}]',
       '[{"layerKey":"main","points":[{"pointId":"p00000035","level":"basic","text":"能说出先写主分片再并行复制到副本，副本提供读扩展与容灾"},{"pointId":"p00000036","level":"mid","text":"能说清主分片并行扇出、全部活跃副本确认才返回"}]},{"layerKey":"L1","points":[{"pointId":"p00000037","level":"mid","text":"能说出 in-sync 副本集语义与 quorum 写入条件"},{"pointId":"p00000038","level":"senior","text":"能解释 wait_for_active_shards 默认 quorum 的可用性代价"}]},{"layerKey":"L2","points":[{"pointId":"p00000039","level":"senior","text":"能说主分片切换后用 sequence number 去重保证不重不丢"}]}]',
       '{"pass":"协调节点按路由把请求交给主分片，主分片本地写成功后把请求并行转发给所有副本分片，副本也写成功后主分片向协调节点汇报，协调节点才向客户端返回成功。","good":"补充失败语义：副本失败时只要 in-sync 副本数仍满足 quorum 就算写入成功，掉队的副本被移出 in-sync 后走恢复流程追赶；wait_for_active_shards 默认 quorum，凑不齐就拒绝写入保一致性。","excellent":"带实践与取舍：曾把 wait_for_active_shards 设为 all，一个副本节点抖动导致整索引写入拒绝，改回 quorum；主分片切换期间靠 sequence number 加 primary term 让追赶中的副本丢弃过期操作，避免重复写；副本数从 1 加到 2，读吞吐提升约 60%，写延迟涨 15%。"}',
       '{"junior":{"required":false,"stopLayer":null},"mid":{"required":true,"stopLayer":"L1"},"senior":{"required":true,"stopLayer":"L2"},"staff":{"required":false,"stopLayer":null},"expert":{"required":false,"stopLayer":null}}',
       'mid,senior', 4, 'active', datetime('now'), datetime('now', '+90 day')
FROM kp_cluster c
WHERE c.name = 'ES 写入链路'
  AND NOT EXISTS (SELECT 1 FROM kp_main k WHERE k.title = '主分片和副本分片在写入时是怎么交互的？');

-- ----------------------------------------------------------------------------
-- 四、项目叙事（1 条，挂「通用项目叙事」簇，难度 2，junior/mid 档）
-- 红线：公共库不含个人内容，样例只给叙事结构示例（占位叙述「某电商系统」）
-- ----------------------------------------------------------------------------

INSERT INTO kp_main (cluster_id, sub_type, title, variants, narrative_framework, followups, rubric, samples, level_req, baseline_levels, difficulty, status, last_reviewed_at, review_due_at)
SELECT c.id, 'narrative',
       '讲讲你负责过的最有挑战的项目',
       '["介绍一个你最有成就感的项目","挑一个你深度参与的项目讲三分钟","说一个你从头到尾负责的项目"]',
       '六要素：背景与目标、我的角色、方案与冲突、结果数字、复盘不足、可迁移经验。三分钟收口，先结论后展开，冲突讲推进动作，数字对得上目标，复盘落到动作。',
       '[{"layerKey":"L1","dir":"背景目标","text":"这个项目的背景和你的目标是什么？目标是怎么量化的？","expect":"能给出业务背景与可量化的目标值","stopCondition":"说出量化目标","status":"active"},{"layerKey":"L2","dir":"方案冲突","text":"方案上遇到的最大分歧或冲突是什么，你怎么推进的？","expect":"能给出真实冲突与本人的推进动作","stopCondition":"说出具体冲突与解法","status":"active"},{"layerKey":"L3","dir":"结果数字","text":"最后的结果用数字说是什么？","expect":"能给出与目标对得上的结果数字","stopCondition":"给出结果数字","status":"active"},{"layerKey":"L4","dir":"复盘","text":"如果重做一次，你会改掉什么？","expect":"能给出具体不足与改进动作，而不是泛泛而谈","stopCondition":"给出具体复盘点","status":"active"}]',
       '[{"layerKey":"main","points":[{"pointId":"p00000040","level":"basic","text":"三分钟内讲完，先结论后展开，主线不断"},{"pointId":"p00000041","level":"basic","text":"说清自己的角色与职责边界，不是复述团队"},{"pointId":"p00000042","level":"basic","text":"六要素里至少覆盖四个"}]},{"layerKey":"L1","points":[{"pointId":"p00000043","level":"basic","text":"目标给出了数字或明确的完成口径"}]},{"layerKey":"L2","points":[{"pointId":"p00000044","level":"basic","text":"冲突是真实的方案分歧并落到自己的推进动作"}]},{"layerKey":"L3","points":[{"pointId":"p00000045","level":"basic","text":"结果数字与开头目标能对上"}]},{"layerKey":"L4","points":[{"pointId":"p00000046","level":"basic","text":"复盘具体到动作而不是态度"}]}]',
       '{"pass":"结构示例：『某电商系统的库存改造，我负责下单链路。目标是把超卖率压到万分之五以下。方案上服务拆分与 DBA 有分歧，我拿压测数据推动了对账兜底方案。最终超卖率万分之二。复盘是压测没覆盖秒杀脉冲。』——骨架完整，但数字与冲突都只有一句话。","good":"在 pass 骨架上把每个要素展开成两三句：背景讲业务痛点与时间线；冲突讲双方依据与推进过程；结果给三组数字（超卖率、接口 P99、人力节省）；复盘给一条已落地的改进；全程三分钟收口。","excellent":"在 good 基础上带取舍与迁移：解释为什么当时不用分布式锁而选对账兜底（吞吐差一个数量级）；说明这套方案迁到新系统的前提与坑；复盘同时给出『当时对』与『现在错』的判断——展示的是判断力而不只是执行力。"}',
       '{"junior":{"required":true,"stopLayer":"main"},"mid":{"required":true,"stopLayer":"L3"},"senior":{"required":false,"stopLayer":null},"staff":{"required":false,"stopLayer":null},"expert":{"required":false,"stopLayer":null}}',
       'junior,mid', 2, 'active', datetime('now'), datetime('now', '+90 day')
FROM kp_cluster c
WHERE c.name = '通用项目叙事'
  AND NOT EXISTS (SELECT 1 FROM kp_main k WHERE k.title = '讲讲你负责过的最有挑战的项目');

-- ----------------------------------------------------------------------------
-- 五、设计演进链（1 条，挂「高并发系统设计」簇，难度 5）
-- followups 六层语义为条件升级；rubric 取舍类要点全放 senior 级
-- ----------------------------------------------------------------------------

INSERT INTO kp_main (cluster_id, sub_type, title, variants, narrative_framework, followups, rubric, samples, level_req, baseline_levels, difficulty, status, last_reviewed_at, review_due_at)
SELECT c.id, 'design_chain',
       '设计一个支撑百万级 QPS 的商品详情页系统',
       '["设计一个电商商品详情页，支撑百万 QPS","百万级 QPS 的读系统怎么设计","给你一亿 SKU，怎么做详情页服务"]',
       NULL,
       '[{"layerKey":"L1","dir":"约束澄清","text":"先澄清约束：读写比例是多少？峰值百万 QPS 是读还是写？允许多强的一致性？","expect":"先估量级再动手：区分读写 QPS、给出一致性等级选择","stopCondition":"完成量级估算与约束澄清","status":"active"},{"layerKey":"L2","dir":"条件升级","text":"十万 QPS 时数据库开始扛不住，你的第一级演进是什么？","expect":"多级缓存：本地缓存加分布式缓存加 CDN，DB 只兜底","stopCondition":"给出多级缓存结构","status":"active"},{"layerKey":"L3","dir":"条件升级","text":"热点商品把单个 Redis 分片打满了怎么办？","expect":"热点 key 探测加本地副本，或再分片多副本读","stopCondition":"给出热点 key 方案","status":"active"},{"layerKey":"L4","dir":"条件升级","text":"缓存和数据库的一致性怎么保证？先删缓存还是先更新库？","expect":"延迟双删或 binlog 订阅失效，容忍最终一致窗口","stopCondition":"给出一致性方案与代价","status":"active"},{"layerKey":"L5","dir":"条件升级","text":"缓存集群整体不可用时，怎么保证系统不被 DB 冲垮？","expect":"降级预案：限流、静态兜底页、DB 熔断保护","stopCondition":"给出降级链路","status":"active"},{"layerKey":"L6","dir":"复盘取舍","text":"为什么不用 X？比如为什么不上强一致协议、为什么不用内存数据库扛读写？","expect":"能对关键选型给出成本维度的取舍理由","stopCondition":"给出至少两项取舍论证","status":"active"}]',
       '[{"layerKey":"main","points":[{"pointId":"p00000047","level":"basic","text":"能给出整体分层结构：接入层、缓存层、存储层"},{"pointId":"p00000048","level":"mid","text":"能主动做量级估算而不是直接背架构图"}]},{"layerKey":"L1","points":[{"pointId":"p00000049","level":"mid","text":"读写比、峰值均值比、一致性等级三项澄清至少占两项"}]},{"layerKey":"L2","points":[{"pointId":"p00000050","level":"mid","text":"能说出多级缓存的位置与各自命中率目标"},{"pointId":"p00000051","level":"senior","text":"能给出缓存容量与回源比的估算"}]},{"layerKey":"L3","points":[{"pointId":"p00000052","level":"mid","text":"能说出热点 key 的识别手段"},{"pointId":"p00000053","level":"senior","text":"能对比本地副本与再分片两种方案的代价"}]},{"layerKey":"L4","points":[{"pointId":"p00000054","level":"mid","text":"能说出至少一种缓存失效策略及其不一致窗口"},{"pointId":"p00000055","level":"senior","text":"能论证为什么最终一致在此场景可接受"}]},{"layerKey":"L5","points":[{"pointId":"p00000056","level":"mid","text":"能给出限流阈值怎么定（容量推算）"},{"pointId":"p00000057","level":"senior","text":"能说出降级期间的用户体验兜底与恢复顺序"}]},{"layerKey":"L6","points":[{"pointId":"p00000058","level":"senior","text":"能对方案中的关键技术选型给出成本维度的取舍论证"}]}]',
       '{"pass":"最简版加量级：商品详情读多写少，前端 CDN 静态化加 Redis 缓存加 MySQL 兜底；峰值百万 QPS 按读写比 100:1 估算，缓存层扛大头，DB 按一万 QPS 写容量与一万读 QPS 兜底准备。","good":"演进到 L3：十万 QPS 时引入本地缓存加分布式缓存两级，本地缓存存热点商品 JSON，TTL 秒级；热点 key 用滑动窗口探测，前 100 热点在每台应用机存本地副本，Redis 只承担长尾流量。","excellent":"全链加取舍：一致性用 binlog 订阅异步失效加延迟双删兜底，不一致窗口秒级可接受（价格晚一秒可见）；降级三级预案——非核心字段裁剪、静态兜底页、DB 前熔断限流按压测容量八折设阈值；取舍论证：不用内存数据库因为单机内存放不下全量 SKU 且成本是 Redis 三倍，不上强一致协议因为读多写少下共识开销买不来业务价值。"}',
       '{"junior":{"required":true,"stopLayer":"main"},"mid":{"required":true,"stopLayer":"L2"},"senior":{"required":true,"stopLayer":"L4"},"staff":{"required":true,"stopLayer":"L6"},"expert":{"required":false,"stopLayer":null}}',
       'junior,mid,senior,staff', 5, 'active', datetime('now'), datetime('now', '+90 day')
FROM kp_cluster c
WHERE c.name = '高并发系统设计'
  AND NOT EXISTS (SELECT 1 FROM kp_main k WHERE k.title = '设计一个支撑百万级 QPS 的商品详情页系统');

-- ----------------------------------------------------------------------------
-- 六、前置依赖（先懂前置才懂本条：kp_id 的前置是 prereq_kp_id，不成环）
--   近实时 ← 写入流程；refresh 与 flush ← 写入流程；写入为什么快 ← 倒排索引
-- ----------------------------------------------------------------------------

INSERT INTO kp_prereq (kp_id, prereq_kp_id)
SELECT a.id, b.id FROM kp_main a, kp_main b
WHERE a.title = '为什么 Elasticsearch 是近实时的？'
  AND b.title = 'ES 写入一条数据的完整流程是什么？'
  AND NOT EXISTS (SELECT 1 FROM kp_prereq p WHERE p.kp_id = a.id AND p.prereq_kp_id = b.id);

INSERT INTO kp_prereq (kp_id, prereq_kp_id)
SELECT a.id, b.id FROM kp_main a, kp_main b
WHERE a.title = 'Elasticsearch 的 refresh 和 flush 有什么区别？'
  AND b.title = 'ES 写入一条数据的完整流程是什么？'
  AND NOT EXISTS (SELECT 1 FROM kp_prereq p WHERE p.kp_id = a.id AND p.prereq_kp_id = b.id);

INSERT INTO kp_prereq (kp_id, prereq_kp_id)
SELECT a.id, b.id FROM kp_main a, kp_main b
WHERE a.title = 'Elasticsearch 写入为什么快？'
  AND b.title = '倒排索引在 Elasticsearch 里是怎么构成的？'
  AND NOT EXISTS (SELECT 1 FROM kp_prereq p WHERE p.kp_id = a.id AND p.prereq_kp_id = b.id);

-- ----------------------------------------------------------------------------
-- 七、频率信号（人工标定 2 条，(kp_id, source='manual') 判重）
-- ----------------------------------------------------------------------------

INSERT INTO kp_freq_signal (kp_id, source, signal_level, signal_count, note)
SELECT k.id, 'manual', 'high', 3, 'ES 岗位 JD 与面经双高：写入链路是必考主干题'
FROM kp_main k
WHERE k.title = 'ES 写入一条数据的完整流程是什么？'
  AND NOT EXISTS (SELECT 1 FROM kp_freq_signal s WHERE s.kp_id = k.id AND s.source = 'manual');

INSERT INTO kp_freq_signal (kp_id, source, signal_level, signal_count, note)
SELECT k.id, 'manual', 'high', 2, '百万级 QPS 设计题是高级岗压轴高频，覆盖缓存、一致性、降级多重条件'
FROM kp_main k
WHERE k.title = '设计一个支撑百万级 QPS 的商品详情页系统'
  AND NOT EXISTS (SELECT 1 FROM kp_freq_signal s WHERE s.kp_id = k.id AND s.source = 'manual');
