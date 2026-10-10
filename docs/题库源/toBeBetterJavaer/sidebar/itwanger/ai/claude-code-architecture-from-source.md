---
title: 老板：“刚刚，阿里全面禁用Claude，我们要不要跟风？”，我：“Claude Code的底层我刚严肃学习，别上头。”
shortTitle: Claude Code 底层架构拆解
description: 拆解 Claude Code 的 6 大核心抽象和 7 级权限模式，从 Query Loop 的 async generator 设计到递归 Agent 架构，附 5 个可直接复用的设计模式。
keywords:
  - Claude Code
  - Agent 架构
  - Query Loop
  - Claude Code 源码分析
  - AI Agent 开发
tag:
  - Agent
category:
  - AI
author: 沉默王二
date: 2026-07-03
---

我发现了。

写Claude Code的时候大家求Codex，写Codex的时候大家求Claude Code。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703114206.png)

所以我的建议是翻翻历史文章，我其实已经写了不少Claude Code和Codex的内容。

当然了，Claude Code的视频也会帮大家整理了。

发现2。

大多数人对 Claude Code 的认知还停留在“一个能读写代码的终端 Agent”。但其实他能干的事情远不止这些。如果你想真正掌握它，就必须得从底层深扒。

有没有想过？

我们在终端按下回车后，Claude Code 都干了啥？和传统的 CLI 有什么区别？

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703111717.png)

## 01、CC 和传统 CLI 的区别

传统 CLI 的行为模式是确定的。

grep 搜索文本，curl 发送请求，git 管理版本。每个命令做什么、怎么做、输出什么格式，在执行前就已经确定了。输入一样，输出一定一样。

Claude Code 的运行方式完全不同。

它接收一段自然语言，自己决定需要调用哪些工具，按照当前情况规划执行顺序，然后不断迭代直到任务完成。

注意“不断迭代”这四个字。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703112535.png)

文字简单描述下。

用户输入进入 Query Loop → 调用模型 API → 模型返回工具调用指令 → 执行工具 → 执行结果追加到上下文 → 上下文再传给模型 → 模型决定继续调用工具还是结束。结束条件有：模型主动停止、外部约束触发（token 预算耗尽、最大轮次、用户中止）等。

整个系统建立在 6 个核心之上：

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703133154.png)

- **Query Loop**，系统中枢，驱动 Agent 循环
- **Tool System**，工具注册、调度和执行
- **Tasks**，后台工作单元，主要是子 Agent
- **Memory**，跨会话的持久化上下文，分项目级、用户级、团队级三个层次，会话启动时用 LLM 筛选当前对话相关的记忆注入上下文
- **Hooks**，用户自定义的生命周期钩子，支持 shell 命令、单次 LLM 调用、多轮 Agent 对话、HTTP webhook 四种执行方式

其实所有的Agent工具，不管是终端还是桌面，都在做类似的事情。

## 02、Query Loop

Query Loop 是整个 Claude Code 的核心。

它本质上是一个异步生成器（async generator）。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703125936.png)

可能大家对 async generator 不太熟悉，但如果用过 Python 的 yield，或者 Java 的 Iterator，思路是一样的。简单地说，它不是一次性把所有结果处理完再返回，而是一边运行一边往外产出事件。

用伪代码表示大概是这样：

```typescript
for await (const event of query(input)) {
  render(event)
}
```

外面每消费一个事件，里面才继续生产下一个。

这个机制带来了三个好处。

第一。如果大家用过 TCP 滑动窗口，会对这个概念特别熟悉。接收方处理不过来的时候，发送方就不能无限制地发送数据。

Query Loop 的 generator 模式是一样的道理。UI 渲染跟不上模型输出速度时，generator 自动暂停。如果换成 callback，里面产生事件的速度完全不受外面控制，消息一堆积就完蛋了。

第二。用户按了 Ctrl+C，或者 token 预算耗尽了，这时候模型可能还在跑、工具可能还在执行。

如果是 callback，取消操作意味着要手动追踪每一个注册过的回调并逐一解除，漏掉一个就是内存泄露。但 generator 只需要调一次 `.return()`。

第三。Query Loop 返回了 6 种终止原因。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703130050.png)

- 正常完成（end_turn）
- 用户中止（user abort）
- Token 预算耗尽（budget exhaustion）
- Stop Hook 干预（hook intervention）
- 最大轮次（max turns）
- 不可恢复错误（unrecoverable error）

调用方拿到返回值后做一下模式匹配，就能精确知道循环为什么停了，不需要去检查各种全局 flag。如果换成 callback，到底是用户取消、工具失败还是系统异常，就说不清楚了。

理解了 generator 的设计，我们来完整地走一遍请求路径。以“给登录函数加上错误处理”这个指令为例。

1. 用户在终端输入任务，按下回车
2. 消息交给 Query Loop
3. Query Loop 调用模型 API
4. 模型流式返回内容和工具调用指令
5. 模型指示调用 Read 工具读取登录函数
6. 执行结果追加到历史消息，触发下一轮迭代
7. 模型指示 Agent 调用 Edit 工具修改代码
8. 模型判断任务完成，不再生成工具调用，generator 返回 Terminal

一般的 Agent 实现是这样做的，模型先完整输出一段回复，Agent 看完回复发现里面有工具调用，然后开始执行工具，工具执行完成后再把结果交回模型。

Claude Code 不是这样做的。

它有一个 StreamingToolExecutor，只要看到一个工具调用的参数已经生成完毕，并且这个工具声明了并发安全，就可以先执行，不用等模型把后面的内容全部输出完。模型还在继续生成后面的 token，文件可能已经读完了。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703132556.png)

这种方式的代价是什么？

如果后面模型输出改变了前面的结果，那前面已执行的工具调用就白跑了。不过实际运行中这种情况极少出现，token 已经输出了，后续大概率是在这个决策上继续推进。Claude Code 用可能浪费的少量算力，换来了整体延迟的降低。

但并非所有工具都能被推测执行。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703130447.png)

Read、Grep 这类只读操作声明了并发安全，可以提前跑。Write、Bash 这类会改状态的工具，必须串行等待。

这就引出了下一个问题，工具系统是怎么组织的？

## 03、工具系统与子 Agent

工具就是 Agent 在电脑世界里能干的活。

读文件、跑 shell 命令、编辑代码、搜索网页内容，都是工具。

传统的做法是搞一个中央管理器，统一注册工具、分配任务、做权限检查。Claude Code 不一样，**每个工具自己声明自己的一切**。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703130538.png)

每个 Tool 实现 5 个维度的接口。

- **Identity**，我叫什么，我能干什么
- **Schema**，我接受什么参数（JSON Schema 定义）
- **Execution**，我的执行逻辑
- **Permissions**，用我之前需要什么级别的授权
- **Rendering**，执行过程和结果在终端里怎么展示

如果大家写过 Spring 框架的代码，可以类比一下，有点像 Spring 的 `@Component` 加自定义注解的思路。

组件自己声明自己的能力，容器只负责扫描和调度，不需要中央配置表。

工具执行的批处理逻辑也有讲究。

当模型一次返回多个工具调用时，系统先做分组，声明了并发安全的放进并发组同时启动，未声明的放进串行组逐个执行。

回到 Agent 的组合能力。

Claude Code 的每个 Task 遵循一个状态机：`pending → running → completed | failed | killed`。

负责创建 Task 的是 AgentTool。AgentTool 做的事情就是生成一个**新的 Query Loop 实例**。注意，是同一个 Query Loop，只不过这个新实例拥有独立的消息历史、独立的工具集、独立的权限模式。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703131224.png)

这意味着 Claude Code 的多 Agent 和 主 Agent 是**同一个 Agent 循环的多个实例**。

主 Agent 跑 Query Loop，Sub-agent 也跑 Query Loop，只不过历史消息隔离、权限不同。

注意：Sub-agent 的权限默认是 `bubble` 模式。bubble 就是冒泡，想象一下水里的气泡一直往水面上浮的画面。当 Sub-agent 遇到危险操作（比如删文件、改 git 历史），必须上报，最终由用户来决定。

有点Java中的双亲委派模型。

## 04、权限模式

Claude Code 能在机器上执行任何 shell 命令，能改文件、能开子进程、能发网络请求、能改 git 历史。如果没有权限系统对 Agent 进行控制，后果不堪设想。

它的权限控制不是基于检查点的。

代码里没有 `if (hasPermission('write_file'))` 这样的条件判断。它定义了一组权限模式，所有权限决策通过模式路由。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703131511.png)

从源码来看，一共有 7 级模式，从宽松到严格依次排列。

| 模式 | 行为 |
|------|------|
| `bypassPermissions` | 一切放行，不做检查（仅限内部测试） |
| `dontAsk` | 所有操作放行但记录日志 |
| `auto` | 轻量级 LLM 分类器基于对话上下文判断放行或拒绝 |
| `acceptEdits` | 文件编辑自动批准，其他变更需用户确认 |
| `default` | 标准交互模式，变更操作需用户确认 |
| `plan` | 只读模式，所有写操作被阻止 |
| `bubble` | 决策上报给父 Agent |



这 7 级里面，bypass、dontAsk、acceptEdits、plan 这四个是静态策略，逻辑写死的。default 是人工确认。bubble 是向上抛出。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703132452.png)

有意思的是 `auto` 模式（我平常就这个模式，省得不停确认）。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703131001.png)

auto 模式的内部实现不是硬编码规则，而是额外跑了一个**轻量级 LLM 分类器**。这个分类器的输入是当前对话的完整上下文，输出是一个二元判断：当前操作是否和用户的原始意图一致。

举个例子，用户说“帮我重构 login 模块的错误处理”。Agent 要编辑 `login.ts`，分类器判断和用户意图一致，放行。

Agent 要读取测试文件看看测试覆盖，也放行。

但如果 Agent 要删除 `config.yaml` 或者改 SSH 配置，分类器发现用户从未提到这些文件，拒绝。

本质上，auto 模式是在“全手动确认”和“权限完全放开”之间，加了一层自动审批。

它用 LLM 的语义理解能力来判断操作的合理性，比硬编码规则灵活得多。代价是每次权限检查需要一次额外的模型调用，但分类器用的是轻量级模型，延迟可控。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703131807.png)

权限解析还有一层优先级设计。

系统先检查用户是否在 Hooks 里配置了匹配当前操作的规则。命中 Hook 规则的，直接按 Hook 执行，不进入权限模式判断。没有命中任何 Hook 时，才走权限模式的路由逻辑。

这给了用户一个精确的覆盖入口。

确定信任的操作写进 Hook 直接放行，免去每次确认。Hook 没覆盖的操作，依然受权限模式的管控。覆盖范围是局部的，兜底策略是全局的。

## ending

AI 时代，每个个体的命运都不尽相同。

有人因为 AI 顺势腾飞，有人挣扎，有人 out。

就我个人的经验来看，没必要对自己太苛刻，因为算法这种东西，喜欢你的时候疯狂喜欢，你做什么都对。

不喜欢的时候你再努力也没用。

有点不可抗力的感觉，就像 Fable 5 一样。

![](https://cdn.paicoding.com/stutymore/claude-code-architecture-from-source-20260703135523.png)

能用的时候用一把，用不了强求也没用。

我估计，Opus 4.6 和 GPT-5.5，可能会是很长一段时间内，我们能享受到的最顶级的模型了。

至少几个月吧。

且行且珍惜，趁这几天时间，把额度用完。