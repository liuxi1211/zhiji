---
title: 面试官坏笑：“本周我们只要 Loop Engineering 不要 Prompt Engineering 了。”我：“不就是 /loop /goal，谁不会啊！”
shortTitle: Loop Engineering 硬核教程
description: 深度拆解 Loop Engineering 六大组件，从定时任务到 Worktree 隔离，实测 Claude Code /loop 和 Codex /goal 命令，附完整 demo 和配置指南。
keywords:
  - Loop Engineering
  - Claude Code /loop
  - Codex /goal
  - Agent 自动化
  - AI 编程教程
tag:
  - Agent
category:
  - AI
author: 沉默王二
date: 2026-06-16
---

大家好，我是二哥呀。

Prompt Engineering、Context Engineering、Harness Engineering，AI 圈造新词的速度比大模型迭代还快。

这不，Loop Engineering 新鲜出炉了。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616134312.png)

有人说，Prompt Engineering 是手动挡，Loop Engineering 是自动挡。

但要我说，都特喵的一样，好吧。

只是我们不再写简单的 Prompt 等 LLM 回复后写下一条，而是尽量写一套更复杂的提示词，让 Agent 多消耗一点 token。

哦不。

尽量榨干 LLM 的能力，让它在一个 loop 里自己转起来，自己找信息，自己验证结果，自己调整方案，直到完成目标。

## 01、Loop Engineering 是什么

Prompt Engineering 时期，大家研究的是“怎么写一句好 Prompt”，措辞、格式，不断打磨单次输入的质量。

Context Engineering 时期，关注点从写什么转向给模型看什么。CLAUDE.md、RAG、摘要压缩、上下文窗口管理，核心问题变成了如何在有限的上下文窗口里塞进最有价值的信息。

再后来，Harness Engineering 把 Agent 当成一个工程系统来设计，涵盖工具集成、状态持久化、Sub-agent 编排，关注的是整个执行框架。

Loop Engineering 站在 Harness 之上，解决的是怎么让这套系统自己转起来。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616141212.png)

一个 loop 能自己启动，知道去哪找信息，做完一轮知道怎么检查结果，失败了知道要不要重试，每轮把进展记到指定位置，也知道什么时候该停下来交给人。

Harness 是基础设施，Loop 是让基础设施自动运转的设计方式。

## 02、Loop 的六大组件

在我看来，Loop 的六大组件为：定时任务、Worktree、Skill、MCP、Sub-agent、Memory。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616144600.png)

这六个组件构成了一个完整的 Loop Engineering 技术栈。缺一不可，缺了哪个都不算真正的 loop。

### 定时任务

在 Claude Code 中，/loop 命令就是一个定时任务触发器。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616143347.png)

`/loop 5m /code-review` 表示每 5 分钟触发一次代码审查。

传统 cron 执行的是确定性脚本（执行 A 脚本、输出到 B 文件），而 loop 里的定时任务触发的是一个 Agent 会话。

Agent 会根据当前上下文自主决策下一步做什么。

### Worktree

Worktree 允许在同一个仓库下创建多个工作目录，每个工作目录对应一个独立的分支。

在 loop 场景中，一个 Agent 在 worktree A 里改代码，另一个 Agent 在 worktree B 里做审查，彼此的文件操作互不干扰。

没有隔离的多 Agent 并行操作，文件冲突是必然的。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616143538.png)

Claude Code 提供了 `--worktree` 参数启动隔离实例，Sub-agent 也支持 `isolation: 'worktree'` 配置项。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616143833.png)

启用后，Agent 在一个独立的 Git worktree 中工作，改动不会影响主工作目录。如果 Agent 没有产生任何修改，worktree 会被自动清理，不会留下无用的分支。

```bash
# 在独立 worktree 中启动 Claude Code
claude --worktree "修复 issue #42 的内存泄漏问题"
```

### Skill

Skill 是技能包。一个 `SKILL.md` 文件加上可选的 `references/` 目录，记录一个工作流的工作规范、构建命令、审查标准、常见问题的处理方式。

没有 Skill，Agent 不知道项目用什么构建工具，不知道测试怎么跑，不知道代码风格偏好。Skill 把这些信息持久化下来，让 loop 的每一轮都能站在同样的知识基础上执行。

Claude Code 和 Codex 都使用相同的 Skill 格式，一份 Skill 写好之后两个工具都能直接使用。

一个典型的 Skill 结构如下。

```
.claude/skills/daily-review/
├── SKILL.md          # 主文件：审查策略、输出格式、判断标准
└── references/
    ├── coding-style.md    # 项目编码规范
    └── common-bugs.md     # 历史高频 bug 清单
```

loop 每一轮启动时加载 Skill，Agent 就知道“用什么标准审查”而不是“凭感觉审查”。

### MCP

MCP 是连接器，让 loop 能执行真实的跨系统操作。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616144414.png)

没有 MCP，Agent 只能读写本机文件和执行本机命令。有了 MCP，Agent 可以创建 PR、更新远程任务、发送 IM 消息、查询数据库、操作浏览器。

MCP 已经成为 Agent 工具生态的事实标准。一个 MCP 服务器写好之后，Claude Code、Codex 都能接入，不需要为每个工具单独开发插件。

loop 的目标往往不只是修改代码，还包括提交 PR、通知团队、更新项目管理工具。这些操作全靠 MCP 完成。

举个例子，一个每天早上 9 点运行的 loop，扫描仓库里所有过期的依赖，自动创建升级 PR，然后在飞书通知团队审查。这里面“创建 PR”需要 GitHub MCP，“发飞书消息”需要 Feishu MCP，Agent 本身只负责决策和编排。

### Sub-agent

一个 Agent 负责修改（maker），另一个 Agent 负责审查（checker）。绝对不能修改者给自己的作业打分，这是工程领域的基本常识。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616145215.png)

在 loop 中，这种分离通过 Sub-agent 实现。

Claude Code 的 Sub-agent 机制会为每个子任务启动一个独立的 ReAct 循环，带有过滤后的工具注册表。Sub-agent 完成后，结果返回给主 Agent 整合。搭配 worktree 隔离使用，多个 Sub-agent 可以并行修改代码而不产生冲突。

```javascript
// Workflow 脚本中的 maker-checker 模式
const fix = await agent('修复这个安全漏洞', {
  label: 'maker',
  isolation: 'worktree'
})

const review = await agent('审查这个修复方案，检查是否引入新问题', {
  label: 'checker',
  agentType: 'code-reviewer'
})
```

maker 在隔离环境中修改代码，checker 独立审查修改结果。两个 Agent 互不干扰，判断也互不影响。

### Memory

Memory 是 loop 跨轮次保持状态的机制。

模型没有记忆。

每次对话开始时，上一轮的所有上下文都消失了。如果 loop 第 3 轮需要知道第 1 轮做了什么修改，这个信息必须写在模型之外的地方，比如文件系统、数据库、或者其他外部存储。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616145247.png)

Claude Code 的 CLAUDE.md 文件和 memory 目录就是一种 Memory 实现。每轮 loop 结束时，Agent 可以把关键结论写入文件；下一轮启动时读取这些文件，就能接续上一轮的进度。

Memory 是六个组件中最容易被忽视的，也是 loop 能否真正“积累经验”的关键。没有 Memory，loop 的每一轮都是独立的，它能重复执行，但不能从过去的执行中学到东西。

一个典型的场景，loop 第 1 轮扫描到 10 个 bug，修复了 3 个，剩余 7 个写入 `memory/pending-bugs.md`。第 2 轮启动时读取这个文件，继续修复剩下的 7 个，而不是重新扫描一遍、重新发现同样的 bug。

## 03、Claude Code 中的 /loop

/loop 是 Claude Code 内置的定时任务命令，语法如下。

```bash
/loop <interval> <prompt or slash command>
```

间隔时间支持分钟（m）和小时（h），默认 10 分钟，最小间隔 1 分钟。不指定间隔直接 `/loop /code-review` 就是每 10 分钟执行一次。

### 三个实际场景

**场景一，定时代码审查**

```bash
/loop 30m /code-review --fix
```

每 30 分钟扫描当前 diff，找出潜在的 bug 和优化点，能自动修的直接修。适合长时间开发过程中保持代码质量。

**场景二，监控 CI 状态**

```bash
/loop 5m 检查当前分支的 CI 状态，如果有失败的 job，分析原因并尝试修复
```

每 5 分钟检查一次 CI pipeline，发现失败就自动排查。PR 提交后不用一直盯着 CI 面板，Agent 会在后台持续跟进。

**场景三，技术债扫描**

```bash
/loop 4h 扫描最近4小时新增的 TODO 和 FIXME 注释，整理成清单写入 docs/tech-debt.md
```

## 04、用 Loop 回复 GitHub Issue

PaiAgent 是一个工作流编排项目，类似扣子和dify，刚好GitHub上有一些issue没有回复，我们就可以借助Claude Code的 /loop 命令让 Agent 自动回复这些 issue。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616151955.png)

三个问题都很简单：

- #6 “请问这个项目已经开发完成了吗？还是在进行中？”
- #5 “这个和 PaiFlow 有什么区别”
- #4 “可以二开吗？”

但需要了解项目背景才能准确回答。

这正是 loop 适合接管的场景：高频重复、规则明确、有明确的完成标准（issue 被准确回复）。

### 一条命令启动

```bash
/loop 30m 检查 itwanger/PaiAgent 仓库的 open issue，对没有回复的 issue 根据项目 README 和已有信息生成准确的回复并提交评论。已回复过的跳过，不要重复评论。
```

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616152237.png)

这条命令拆开来看，每一部分都对应 loop 的一个设计要素。

`30m` 是心跳频率，每 30 分钟唤醒一次 Agent。

中间那段自然语言是任务描述，Agent 每轮需要做什么：扫描 issue → 判断是否已回复 → 生成回复 → 提交。

“已回复过的跳过”是防重机制。没有这句话，Agent 每轮都会对所有 open issue 重新评论。

### Agent 的执行过程

Agent 启动后，实际的执行过程是这样的。

第一步，通过 GitHub CLI 拉取所有 open issue 的标题、正文和已有评论。这一步依赖 MCP 或者命令行工具，对应六大组件中的 MCP。

```bash
gh issue list --repo itwanger/PaiAgent --state open
gh issue view 6 --repo itwanger/PaiAgent --json title,body,comments
```

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616152413.png)

第二步，Agent 判断哪些 issue 需要回复。#3 和 #1 已经有 owner 的评论了，跳过；#6、#5、#4 没有任何回复，标记为待处理。

![截图为二次演示时的截图，所有issue都处理过了](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616152435.png)

第三步，Agent 读取项目的 README 和仓库信息。它需要知道 PaiAgent 是什么、用了哪些技术栈、和 PaiFlow 的关系、是否允许二次开发，这些信息全靠项目文档提供。这一步对应六大组件中的 Skill——项目知识的持久化。

知识的质量直接决定回复的质量。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616152543.png)

第四步，生成回复并逐条提交。

```bash
gh issue comment 6 --repo itwanger/PaiAgent --body "项目目前仍在持续开发中..."
gh issue comment 5 --repo itwanger/PaiAgent --body "PaiAgent 和 PaiFlow 的定位不同..."
gh issue comment 4 --repo itwanger/PaiAgent --body "当然可以，PaiAgent 是开源项目..."
```

三条评论在同一轮 loop 内全部提交完毕。

### 回复效果

看一下 Agent 实际提交的回复。

**#6 的回复**——问项目进度的：Agent 准确说明了项目在持续开发中，列出了已完成的核心功能（可视化编辑器、DAG 引擎、LangGraph4j 引擎、Skills 技能系统、多模型接入），并给出了教程链接。

**#5 的回复**——问 PaiAgent 和 PaiFlow 区别的：Agent 从架构层面区分了两个项目，PaiAgent 是轻量级单体架构适合学习，PaiFlow 是微服务架构面向企业生产环境。这个信息是 Agent 综合了 README 和 #3 里 owner 的历史回复推断出来的。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616152621.png)

**#4 的回复**——问能否二次开发的：Agent 确认可以，并给出了 Fork → 开发 → 保留引用说明的建议流程。

这三条回复都不是模板化的敷衍，而是基于项目实际情况生成的。

### 这个 Loop 用到了哪些组件

回头对照六大组件。

- **定时任务**：`/loop 30m` 提供了每 30 分钟一次的心跳
- **MCP**：通过 GitHub CLI 读取 issue、提交评论，连接了外部系统
- **Skill/项目知识**：README 和仓库元信息为 Agent 提供了回复所需的上下文
- **Memory**：Agent 通过检查已有评论判断是否重复，避免同一个 issue 被回复多次

Worktree 和 Sub-agent 在这个场景里没用到——因为不涉及并行修改代码，也不需要写查分离。

### 更进一步

这个 demo 只是最简单的一层。还可以做得更多。

第一，加上 Skill 文件 `issue-reply-guide.md`，定义回复的语气、格式、哪些问题需要转交人工处理，Agent 就不只是回答问题，而是按照你的标准回答问题。

第二，加上 Memory，让 Agent 记录“#5 提问者关心的是架构选型”这类信息。下次同类问题进来，Agent 可以直接引用之前的回答，而不是每次都从头推理一遍。

第三，加上 Sub-agent，一个负责分类 issue（bug report、feature request、usage question），另一个负责生成回复。分类 Agent 的判断决定了回复 Agent 用什么策略——回答使用问题时引用文档，确认 bug 时给出排查步骤。

从一条 /loop 命令开始，逐步叠加组件，loop 的能力边界就这样被不断扩大了。

## 05、Loop 的代价

上 loop 之前，三个问题必须想清楚。

### Token 消耗

loop 一旦跑起来，就不是问一次答一次的计费模式了。Agent 会反复读上下文、反复调用工具、反复验证结果，有时还会启动多个 Sub-agent 同时工作。

换句话说，这玩意虽然牛逼，但如果设计不当，可能会烧掉大量 Token。

就比如说，loop 中的 Agent 调用了一个有 bug 的 MCP 工具，5 分钟内重试了 400 次。工具每次返回错误，Agent 每次都觉得“再试一次应该可以”。没有熔断机制的 loop 就是一台烧钱机器。

几个控制成本的手段。

- 给 loop 设置合理的最大迭代次数，防止 Agent 在失败循环中空转
- /loop 的每轮执行有独立的上下文窗口，不会在多轮之间无限累积上下文

### 安全边界

loop 里的 Agent 有文件读写权限、命令执行权限，还可能有 MCP 授予的外部系统访问权限。一个设计不当的 loop 有可能在凌晨 3 点自动向生产环境推送未经审查的代码。

![](https://cdn.paicoding.com/stutymore/loop-engineering-guide-20260616150025.png)

### 什么场景适合 Loop

适合 loop 的场景有几个共同特征，高频重复、规则明确、有自动化验证手段。

- 代码审查，每隔 N 分钟扫描 diff，有 linter 和测试做验证
- CI 修复，CI 失败后自动分析日志并尝试修复，测试通过就是验证
- 文档同步，代码变了自动更新文档，diff 可以检查
- 依赖升级，自动创建升级 PR 并跑测试
- 安全扫描，定期检查已知漏洞
