---
name: "note-capture"
description: "Summarize the current AI conversation and archive it as a note into the centralized java-dev-notes knowledge base. Invoke when user explicitly says '记一下' / '沉淀' / '保存笔记' / '归档' / 'note this' — works across ANY project (source repos included), always writing to the fixed notebook path."
---

# Note Capture — 跨项目对话沉淀笔记

把当前对话总结成一篇笔记，写入个人知识库 `java-dev-notes`。

> 本 skill **可被任意项目调用**，笔记永远写入下面的固定路径，不写入调用方项目目录。

---

## 0. 笔记本固定路径

```
d:\lcProject\java-dev-notes
```

- 所有笔记必须写入此根目录之下。
- 跨项目调用时，front-matter 填 `project: {仓库名}`，文件名带项目前缀（如 `nacos-registry-flow.md`）。
- 跨项目源码分析默认进 `10-源码阅读/`；引用源码时标注 `仓库路径:行号` 或 commit hash。

---

## 1. 触发条件

仅当用户 **显式调用** 时执行，**不要主动沉淀**。关键词（中英）：
- "记一下" / "沉淀" / "保存笔记" / "归档" / "存档"
- "note this" / "save this" / "capture this"

唯一例外：纯一次性问答（如"JDK 17 哪年发布"）、纯寒暄闲聊——这类可简短告知"信息密度不高，建议不沉淀"并终止。其余情况用户要记就记。

---

## 2. 归档到哪个目录

按下表从上到下扫，**命中第一条即定**，不反复权衡：

| # | 内容特征 | 归档目录 | 文件名 |
|---|---|---|---|
| 1 | 用户显式指定目录 | 用户指定 | 用户指定 |
| 2 | 贴源码、讲调用栈、分析某项目实现 | `10-源码阅读/` 或子目录 | `{项目前缀}-{模块}.md` |
| 3 | 故障 / Bug / 性能调优 / 踩坑 | `11-故障排查/` 或子目录 | `YYYYMMDD-{主题}.md` |
| 4 | 随笔 / 想法 / 灵感（用户说"写随笔"） | `00-未分类/` | `YYYYMMDD-{短标题}.md` |
| 5 | 命中领域关键词（见下表） | 对应一级目录或子目录 | `{主题}.md` |
| 6 | 业务项目 / side project 设计文档 | `09-业务项目/{项目名}/` | `{主题}.md` |
| 7 | 以上都不命中 / 拿不准 | `00-未分类/`（兜底） | `YYYYMMDD-{主题}.md` |

**子目录策略**：未命中子目录就放一级目录根下，不为单篇笔记强行建子目录；某主题累积 5 篇以上再抽子目录。

### 一级目录速查

| 目录 | 关键词 |
|---|---|
| `01-java/` | java / jvm / gc / 并发 / 线程 / 锁 / spring |
| `02-搜索/` | elasticsearch / es / lucene / solr / 搜索 / 倒排 / 向量检索 |
| `03-ai/` | llm / 大模型 / agent / rag / prompt / 推理 / embedding |
| `04-python/` | python / django / flask / fastapi / pandas |
| `05-架构/` | 架构 / 分布式 / 微服务 / 高并发 / 系统设计 / 中间件 |
| `06-数据库/` | mysql / redis / mongodb / sql / 索引 / 事务 |
| `07-devops/` | linux / docker / k8s / ci/cd / nginx / 部署 / 监控 |
| `08-算法/` | 算法 / 数据结构 / leetcode / 动态规划 / 面试题 |
| `12-读书笔记/` | 读书 / 论文 / 读后感 |

---

## 3. 执行流程

1. **判断价值**：一次性问答 / 闲聊 → 不记；其余按用户要求记。
2. **选目录**：用 §2 决策表一次定。
3. **写笔记**：顶部 front-matter（硬约束）+ 正文（AI 自由发挥，见 §4）。
4. **写入文件**：`00-未分类/` 扁平存放不建子目录；归入一级目录时，若有 `INDEX.md` 则追加，无则跳过、不主动创建。
5. **回复用户**：简洁告知路径、标签、一句话摘要，不复述全文。

---

## 4. 笔记怎么写

### front-matter（硬约束，必须有）

```yaml
---
title: {标题}
date: YYYY-MM-DD
tags: []
source: {ai-conversation | knowledge | source-reading | learning | essay}
project: {可选，跨项目时填}
---
```

- `source` 大致对应：对话沉淀 `ai-conversation`、体系笔记 `knowledge`、源码 `source-reading`、学习 `learning`、随笔 `essay`——AI 自行判断，不必纠结。
- 跨项目源码笔记建议补 `repo:` / `commit:`。

### 正文（完全自由，AI 自己决定）

**不使用任何模板，不预设任何栏目。** AI 根据对话内容自行决定：
- 写长还是写短（详细还是简略，由内容价值决定）
- 分章节还是一段话
- 列点还是叙述
- 贴不贴代码、画不画图

核心原则：**忠实还原对话中有价值的内容，形式服务于内容。** 对话聊得深就记得详细，聊得浅就记得简略；是源码分析就贴源码，是随笔就忠于思考状态。不要为了凑形式而凑形式，实在不清楚可以让用户决策。

### 其他约定

- 文件名连字符分隔；日期取 env 中的 Today's date。
- 正文中文为主，术语保留英文。
- 代码块标注语言与版本。
- 跨目录引用用相对路径 markdown 链接。
- 不修改已有笔记（除非用户要"更新/补充"）；不写虚构内容。

---

## 5. 标签（建议参考，数量自定）

`java` `jvm` `concurrency` `spring` `search` `elasticsearch` `lucene` `vector-search` `rag` `llm` `agent` `prompt` `python` `architecture` `database` `mysql` `redis` `devops` `algorithm` `source-reading` `troubleshooting` `snippets`

可扩展；源码分析类建议加 `source-reading` + 项目名。

---

## 6. 示例

### 示例 A — 故障复盘

用户："这次关于 ES 分片迁移卡住的讨论，记一下"

- 目录：命中第 3 条 → `11-故障排查/`
- 文件名：`20260806-es-shard-relocation.md`
- 标签：`[search, elasticsearch, troubleshooting]`

```
✅ 已沉淀
📄 路径：11-故障排查/20260806-es-shard-relocation.md
🏷️ 标签：search, elasticsearch, troubleshooting
📌 摘要：ES 分片迁移的触发时机、relocation 机制、常见阻塞原因与排查路径
```

### 示例 B — 跨项目源码分析

用户在 `d:\projects\nacos` 仓库分析注册中心源码后说"记一下"

- 目录：命中第 2 条 → `10-源码阅读/`
- 文件名：`nacos-registry-flow.md`（带项目前缀）
- front-matter：`source: source-reading`、`project: nacos`
- 标签：`[source-reading, nacos, architecture]`

```
✅ 已沉淀
📄 路径：10-源码阅读/nacos-registry-flow.md
🏷️ 标签：source-reading, nacos, architecture
📌 摘要：Nacos 注册中心的服务注册、心跳、服务发现主流程与关键数据结构
🔗 源自项目：nacos
```

---

## 7. 反例（不要这样做）

- ❌ 用户没说"记一下"，AI 主动创建笔记
- ❌ 把一次性"JDK 17 哪年发布"问答沉淀成笔记
- ❌ 文件名用中文/空格
- ❌ 跨项目调用时把笔记写进了业务仓库（必须写固定笔记本路径）
- ❌ 源码分析笔记未标注 `project` 来源
- ❌ 沉淀后在回复里复述笔记全文
