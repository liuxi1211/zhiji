---
title: OpenHarness太硬了，刚开源就狂揽3.9k+星标！轻松复刻Claude Code。
shortTitle: OpenHarness实测
description: OpenHarness深度测评：港大开源的轻量级AI Agent框架，比Claude Code轻44倍，却实现了98%的核心功能7天破9000 Star，Go版OpenClaw 来了，附钉钉接入教程。
tag:
  - Agent
  - AI Coding
category:
  - AI
author: 沉默王二
date: 2026-04-05
---

大家好，我是二哥呀。

最近 Harness 是真的火，我前几天写的一篇内容就引来电子工业出版社的编辑私信聊出书的事。

![](https://cdn.paicoding.com/stutymore/openharness-review-c14d6b42600f7fe663f76b5cbde6f781.jpg)

不知道大家怎么看，我自己是觉得挺神奇的。

这不，香港大学数据科学实验室（HKUDS）也在 GitHub 上开源了一个 Harness 项目：OpenHarness。

1 万行 Python 代码，实现了 Claude Code 98% 的功能。体积只有后者的 1/44。

GitHub 上 3.9k Star，发布不到一周就冲上了 Trending 榜单。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405144905.png)

> 系好安全带，我们发车，滴滴滴。

## 01、OpenHarness 是什么来头

OpenHarness 不是要替代 Claude Code，而是让大家理解 Claude Code 是怎么工作的，并且能基于它构建自己的 Agent。

团队来自香港大学数据科学实验室，之前还开源过 Auto-Deep-Research、ClawTeam 等项目，在 Agent 领域有很深的积累。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405145537.png)

从架构上看，OpenHarness 把 Agent 的能力拆解成了五个核心模块：

**Agent Loop 引擎**：负责整个 Agent 的执行循环，包括流式工具调用、API 重试、并行执行、Token 计数等。

**Harness Toolkit**：46 个内置工具，涵盖文件操作、Shell 命令、Web 搜索、MCP 协议等，还支持动态加载 Skills。

**Context 与 Memory**：自动发现 CLAUDE.md 文件，支持上下文压缩，还有跨会话的持久化记忆。

**Governance 治理**：多级权限控制、路径和命令规则、工具使用前后的 Hooks 拦截。

**Swarm 协调**：多 Agent 协作，子 Agent 派生和任务委托，团队注册和任务管理。

这种模块化的设计让 OpenHarness 既可以用作完整的 Agent 产品，也可以拆解成零件嵌入其他项目。

### 深入看看 Agent Loop 的实现

Agent Loop 是整个系统的核心。它不是一个简单的 while 循环，而是一个状态机驱动的执行引擎。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405160254.png)

每个环节都有精细的控制。比如工具执行阶段，OpenHarness 会分析工具之间的依赖关系。如果两个工具没有数据依赖，就会并行执行；如果有依赖，则按顺序执行。

这种依赖分析是通过参数注入实现的。引擎会检查每个工具的输入参数是否依赖于其他工具的输出，自动构建执行图。

这比简单的顺序执行效率高很多，特别是在需要读取多个文件或执行多个独立查询的场景。

另一个细节是流式处理。从用户输入到最终输出，数据都是流式传递的。这意味着 Agent 可以在生成响应的同时，就把部分内容展示给用户，不需要等所有工具执行完再一次性返回。

## 02、5 分钟上手体验

OpenHarness 的安装非常简单：

```bash
git clone https://github.com/HKUDS/OpenHarness.git
cd OpenHarness
```

我更推荐使用 GitHub 桌面版下载，更方便。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405150507.png)

然后启动 Codex，阅读一下源码，看看怎么使用、怎么安装。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405150643.png)

装完之后，设置 API Key 就能跑：

第一步，执行 `uv sync --extra dev` 安装依赖。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405150931.png)

第二步，我们直接把 Claude Code 的配置搬运到 OpenHarness 中。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405152330.png)

配置成功后，我们直接执行 `uv run oh` 就能进入 OpenHarness 的交互式终端了。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405152602.png)

已经可以正常使用了。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405152640.png)

## 03、46 个工具开箱即用

OpenHarness 内置了 46 个工具，分为几大类：

**文件操作类**：读文件、写文件、列出目录、搜索文件、文件差异对比等。

**Shell 命令类**：执行 Bash 命令、获取命令帮助、查看环境变量等。

**代码搜索类**：Grep 搜索、Find 文件、代码语义搜索等。

**Web 类**：网页获取、网页搜索等。

**MCP 类**：Model Context Protocol 客户端，可以接入任何 MCP 服务器。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405152934.png)

这些工具都是开箱即用的，你不需要写一行代码就能调用它们。

如果你想让 Agent 熟悉你的项目规范，只需要写一个 CLAUDE.md：

```markdown
# 项目规范

## 代码风格

- 使用 Python 3.10+
- 类型注解必须完整
- 优先使用 pathlib 处理路径

## 项目结构

- src/ 存放源代码
- tests/ 存放测试
- docs/ 存放文档
```

Agent 启动时会自动读取这个文件，把规范注入到 System Prompt 里。

值得一提的是，OpenHarness 的工具调用是流式的。你可以实时看到 Agent 在想什么、调用了什么工具、参数是什么、结果是什么。这种透明度对于理解 Agent 行为和调试问题非常有帮助。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405153402.png)

另外，工具调用支持并行执行。如果 Agent 需要同时读取多个文件，或者同时执行多个独立的 Shell 命令，它会自动并行处理，提高效率。执行完成后，结果会按顺序返回给模型继续处理。

### 工具调用的底层机制

工具调用不是简单的函数执行。OpenHarness 实现了一套完整的工具生命周期管理：

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405160603.png)

**参数校验**：每个工具都有 JSON Schema 定义的参数格式。Agent 生成参数后，系统会先校验是否符合 schema，不符合会立即报错，不会执行到真正的工具函数。

**超时控制**：Shell 命令可能卡死，文件读取可能遇到超大文件。OpenHarness 为每个工具设置了默认超时（通常是 30 秒），超时会自动中断并返回错误。

**结果截断**：工具返回的结果可能很长（比如 grep 搜索返回几千行）。OpenHarness 会智能截断，保留关键信息，同时告诉 Agent 结果被截断了，避免上下文爆炸。

**错误分类**：工具执行失败分多种情况：参数错误、权限不足、资源不存在、执行超时。OpenHarness 会把错误分类，Agent 可以根据错误类型决定是重试、换种方式，还是向用户求助。

## 04、Skills 系统

Skill 不只是工具，更是一套完整的知识包。一个 Skill 可以包含：

- **工具定义**：这个 Skill 需要用到哪些工具
- **知识文档**：.md 格式的领域知识
- **Hooks**：工具调用前后的拦截逻辑
- **示例代码**：展示如何使用这个 Skill

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405153626.png)

OpenHarness 原生兼容 anthropics/skills 仓库的 Skill 格式。这意味着你可以直接用 Anthropic 官方提供的 Skills，也可以自己写。

举个例子，假设你经常需要处理 CSV 文件，可以写一个 CSV Skill：

```markdown
# CSV 处理 Skill

## 工具

- read_file: 读取 CSV 文件
- write_file: 写入 CSV 文件
- bash: 执行 csvkit 命令

## 知识

处理 CSV 文件时要注意：

1. 先用 file 命令检测编码
2. 大文件用 csvkit 处理，不要一次性读入内存
3. 注意处理引号和换行符

## 示例

# 查看 CSV 结构

csvstat data.csv

# 提取特定列

csvcut -c name,age data.csv
```

把这个文件放在.skills/csv.md，Agent 就能自动加载并使用这些知识。

你可以为不同的项目、不同的团队、不同的业务场景定制专属的 Skills。

### Skills 的加载机制

Skills 不是静态配置的，而是动态加载的。OpenHarness 启动时会扫描.skills 目录，解析所有.md 文件，构建 Skill 注册表。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405160659.png)

解析过程分为几步：

**Markdown 解析**：提取 Frontmatter（如果有）、各级标题、代码块、列表等结构。

**意图识别**：分析知识文档，提取关键词和实体，构建向量索引。这样 Agent 可以根据用户输入快速找到相关的 Skill。

**工具绑定**：把 Skill 中声明的工具和实际工具实现绑定。如果声明的工具不存在，会报错提示。

**热更新**：Skills 支持热更新。你在运行时修改了 Skill 文件，Agent 能立即感知并重新加载，不需要重启。

这种动态加载机制让 Skills 非常灵活。你可以根据任务类型动态切换 Skills 组合。比如处理数据时加载数据分析 Skills，写前端时加载前端 Skills，避免无关知识干扰 Agent 的决策。

还有一个细节：Skills 有优先级。如果多个 Skills 都涉及同一个主题，OpenHarness 会根据匹配度和 Skill 的显式优先级决定使用哪个。

## 05、Agent 的安全边界

Agent 能执行 Shell 命令、读写文件，这既是能力也是风险。OpenHarness 的 Governance 模块就是来解决这个问题的。

它提供了多级权限模式：

**Strict 模式**：所有危险操作都需要人工确认
**Auto 模式**：信任的操作自动执行，可疑操作询问确认
**Full 模式**：完全自动，适合 CI/CD 等无人值守场景

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405154236.png)

除了全局模式，还可以配置细粒度的规则：

```yaml
permissions:
  paths:
    allow:
      - ./src/**
      - ./tests/**
    deny:
      - ~/.ssh/**
      - /etc/**

  commands:
    allow:
      - git *
      - python *
      - pytest *
    deny:
      - rm -rf /
      - sudo *
```

更厉害的是 Hooks 系统。你可以在工具调用前后插入自定义逻辑：

```python
@hook("pre_tool_use")
def check_sensitive_files(tool, args):
    if tool.name == "write_file" and ".env" in args["path"]:
        return Confirm("确定要修改.env文件吗？")

@hook("post_tool_use")
def log_tool_usage(tool, args, result):
    logger.info(f"Tool {tool.name} executed with args {args}")
```

这种设计让 OpenHarness 可以适应企业级的安全合规要求。你可以审计每一个操作，拦截危险行为，甚至接入公司的 IAM 系统。

### Governance 的执行流程

权限检查不是简单的一次性判断，而是一个多阶段的决策流程：

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405161100.png)

**阶段 1：静态检查**，在 Agent 生成工具调用请求时，先进行静态规则匹配。检查工具名是否在白名单，参数中的路径是否在允许范围内。这个阶段不执行任何实际代码，纯字符串匹配，速度快。

**阶段 2：动态评估**，如果静态检查通过，进入动态评估。Hooks 在这个阶段执行。Hook 可以访问完整的上下文，包括当前会话状态、历史操作、用户身份等。Hook 可以返回三种结果：允许、拒绝、询问。

**阶段 3：用户确认**，如果前面两个阶段都通过，但权限模式是 Strict 或 Auto，还会弹出确认对话框。用户可以选择允许、拒绝、或者允许本次但记住选择（下次不再询问）。

**阶段 4：执行后审计**，工具执行完成后，PostToolUse Hooks 执行。这里可以记录日志、发送通知、更新统计。

静态检查拦截明显的危险操作，动态评估处理复杂的业务逻辑，用户确认保留最终决策权，执行后审计提供可追溯性。

Hook 的执行顺序也值得注意。多个 Hook 可以注册到同一个拦截点，它们按注册顺序执行。如果某个 Hook 返回拒绝，后续 Hook 不再执行。这种链式调用模式让权限控制可以模块化组合。

比如你可以写一个 Hook 检查敏感文件，另一个 Hook 检查操作时间（比如禁止深夜执行危险操作），再一个 Hook 记录审计日志。三个 Hook 独立维护，互不影响。

## 06、Memory：Agent 的持久记忆

很多 Agent 框架的上下文是临时的，会话结束就丢了。OpenHarness 的 Memory 模块解决了这个问题。

它会在项目根目录生成一个 MEMORY.md 文件，记录跨会话的持久信息：

```markdown
# Memory

## 用户偏好

- 喜欢使用 Python 类型注解
- 偏好函数式编程风格
- 常用测试框架是 pytest

## 项目知识

- 数据库使用 PostgreSQL
- 缓存使用 Redis
- 部署在 Kubernetes 上

## 历史决策

- 2026-04-01: 决定使用 FastAPI 而不是 Flask
- 2026-04-02: 确定使用 SQLAlchemy 2.0
```

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405154458.png)

Agent 每次启动都会读取这个文件，把记忆注入到上下文中。随着使用，它还会自动更新记忆内容。

Memory 还有一个妙用是记录项目决策。比如你们团队决定用 FastAPI 而不是 Flask，把这个决策写入 MEMORY.md，以后 Agent 在生成代码时就会自动遵循这个选择，不会每次都问“用 FastAPI 还是 Flask”。

另外，Memory 支持版本控制。因为 MEMORY.md 就是一个普通文本文件，你可以把它提交到 Git 仓库，跟踪记忆的变更历史。如果某次更新导致 Agent 行为异常，可以方便地回滚到之前的版本。

### Context 压缩的技术细节

上下文长度是 Agent 的大敌。模型有最大上下文限制（现在一般是 200k tokens），超出的部分会被截断。OpenHarness 实现了智能的上下文压缩策略。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405161704.png)

**滑动窗口**：对话历史不是全部保留，而是维护一个滑动窗口。最新的 N 轮对话完整保留，更早的对话会被压缩成摘要。

**分层摘要**：摘要不是简单截断，而是分层生成。首先把相邻的短对话合并，然后提取关键信息，最后生成高层次的摘要。这样即使是很早之前的对话，也能保留核心信息。

**重要性评分**：每条消息都有重要性评分。用户明确的要求、Agent 的错误、工具调用的结果，这些被认为是重要的，优先保留。闲聊内容、重复确认，这些会被优先压缩。

**Token 预算管理**：OpenHarness 会实时计算当前上下文的 token 使用量，根据模型的限制动态调整压缩策略。如果接近上限，会更激进地压缩历史；如果还有余量，会保留更多细节。

这种压缩策略的实际效果很好。在测试中，一个进行了 50 轮对话的会话，压缩后上下文从 150k tokens 降到了 80k tokens，但核心信息都保留了下来。Agent 依然能理解用户的长期意图，不会因为压缩而“失忆”。

还有一个细节：压缩是增量进行的。不是每次对话都重新压缩全部历史，而是只处理新增的部分。这样性能开销很小，不会拖慢响应速度。

## 07、Swarm：多 Agent 协作

复杂任务往往需要多个 Agent 协作完成。OpenHarness 的 Swarm 模块提供了多 Agent 协调能力。

你可以创建一个父 Agent，然后派生子 Agent 处理子任务：

```python
# 父Agent负责任务拆解和协调
parent = create_agent("project-manager")

# 派生子Agent处理具体任务
frontend_task = parent.spawn_subagent("frontend-dev", task="实现登录页面")
backend_task = parent.spawn_subagent("backend-dev", task="实现登录API")

# 等待子任务完成
frontend_result = frontend_task.wait()
backend_result = backend_task.wait()

# 整合结果
parent.integrate([frontend_result, backend_result])
```

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405155006.png)

提示词：

```
请阅读当前 OpenHarness 仓库源码，给我一份面向新贡献者的中文导读。重点讲清楚：
- 怎么启动
- 怎么使用
- Governance 怎么做
- Swarm / 多 agent 怎么做
- 从哪里入手读源码

要求以源码为准，不要只复述 README；如果合适，请并行使用多个 agent 分头阅读后汇总。
```

每个子 Agent 有独立的上下文和工具集，互不干扰。父 Agent 可以监控子 Agent 的执行状态，必要时进行干预。

这种父子 Agent 的模式在实际开发中非常有用。比如你要开发一个新功能，可以让父 Agent 先分析需求、拆解任务，然后派生专门的子 Agent 处理前端、后端、数据库等具体工作。每个子 Agent 专注于自己的领域，效率更高，质量也更有保障。

父 Agent 还负责任务的依赖管理。如果后端 API 还没完成，前端 Agent 就不能开始对接。OpenHarness 会自动处理这些依赖关系，确保任务按正确的顺序执行。

OpenHarness 团队还在开发 ClawTeam 集成，未来会支持更强大的团队协调能力，包括 Agent 之间的消息传递、共享状态、动态负载均衡等高级特性。

### Swarm 的任务调度原理

Swarm 不是一个简单的进程池，它实现了一套完整的分布式任务调度系统。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405163536.png)

**任务分解**：父 Agent 收到复杂任务后，会先进行任务分解。分解不是简单的字符串分割，而是基于对任务的理解。父 Agent 会分析任务涉及哪些模块、模块之间的依赖关系、哪些可以并行、哪些必须串行。

**资源分配**：每个子 Agent 创建时，会分配独立的资源上下文。包括独立的对话历史、独立的工具权限、独立的 Memory 空间。这种隔离确保子 Agent 之间不会互相干扰，一个子 Agent 出错不会影响其他子 Agent。

**状态同步**：子 Agent 的执行状态会实时同步给父 Agent。父 Agent 可以看到每个子 Agent 的当前步骤、已用 token 数、预计剩余时间。如果某个子 Agent 卡住，父 Agent 可以主动干预，比如调整参数、重新分配任务、或者直接终止。

**结果合并**：子任务完成后，父 Agent 需要合并结果。合并不是简单的字符串拼接，而是基于语义的整合。

父 Agent 会分析各个子结果之间的关系，解决冲突，补充缺失的上下文，生成一致的最终输出。

**错误恢复**：如果某个子 Agent 失败，Swarm 有几种处理策略：重试（同样的任务再派一个 Agent）、回退（撤销已完成的子任务，整体失败）、补偿（让其他 Agent 接手失败的任务）。策略可以在创建 Swarm 时配置。

这种设计让 Swarm 可以处理非常复杂的任务。比如重构一个大型项目，涉及几十个文件，Swarm 可以自动分解、并行处理、协调依赖、合并结果，整个过程对用户透明。

## ending

OpenHarness 的出现让我看到了开源 Agent 框架的新可能。

对于想学习 Agent 的开发者，OpenHarness 是一个很好的起点。代码量适中，架构清晰，功能完整。

对于想构建自己 Agent 产品的团队，OpenHarness 也提供了一个基础。可以基于它定制 UI、接入自己的模型、集成内部工具，快速构建符合业务需求的 Agent。

![](https://cdn.paicoding.com/stutymore/openharness-review-20260405155407.png)

技术发展的规律从来都是这样：先有大公司做出惊艳的产品，证明方向是对的。然后开源社区跟进，把能力民主化，让每个开发者都能用上。

OpenHarness 正在做的，正是这样一件事。

【真正的技术普惠，不是让每个人都用得起最好的产品，而是让每个人都能造出自己需要的产品。】

我们下期见！

