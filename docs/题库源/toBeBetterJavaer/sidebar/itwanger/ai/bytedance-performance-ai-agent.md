---
title: 字节跳动绩效迎来大调整！
shortTitle: 字节绩效大调整
description: 字节跳动2026半年绩效调整落地，激励基数从月薪扩到月总包，奖金投入提升35%。拆解字节AI Agent招聘JD，分析技术栈要求，附PaiCLI-Go简历包装模板。
keywords:
  - 字节跳动绩效调整
  - AI Agent 招聘
  - 字节跳动 AI Agent
  - PaiCLI Go
  - AI Agent 求职
tag:
  - Agent
  - 求职
category:
  - AI
author: 沉默王二
date: 2026-07-09
---

大家好，我是二哥呀。

字节对内全员通知了 2026 的半年绩效评估将于 7 月 15 日启动。

这次调整主要有两个方面：

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709141428.png)

**第一，E 及以上绩效小伙伴的半年激励力度加大了。**

原来的计算基数是月薪，现在扩到了月总包，也就是月薪加上每月的期权和 RSU（直接给你股票或等值现金，归属后就是你的，不需要额外花钱购买）。

发放方式也改了，25% 现金+ 75% 绩效期权或 RSU，按月匀速归属，最长不超过两年。归属后 55% 可以立刻申请公司回购变现，剩下的 45% 在三年内分年度逐步回购。

还有一个容易被忽略的细节，年终奖超过 3 个月的部分，以前是 100% 发期权，现在变成 25% 现金加 75% 期权。

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709141452.png)

**第二，字节范和领导力原则在绩效评级里的权重进一步明确。**

这个就属于软实力了。

意味着，以后再要比offer，不能只看月薪，还要看总包里期权占比多少、归属节奏怎么安排、回购比例是多少。

不得不说，字节在国内是有实力啊。

卷是卷了点，但钱也是真的舍得给。我接触到的，几个跳槽到字节的小伙伴，涨幅都是超预期。

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-b8f42c3c0b02261e6b939724d14664f5.jpg)

>接下来，就是我们的新传统，分析字节的AI Agent岗位到底需要什么样的人才，这样好猛猛冲。

## 01、字节的 AI Agent 岗位

我去字节官网搜了一圈 AI Agent 相关的岗位，挑了两个有代表性的实习岗来给大家分析。

一个是在基础设施团队做 Agent Infra，一个是在抖音团队做 Agent 应用开发，分别代表了 Agent 方向的两条主流路径。

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709142533.png)

先来看第一个，构建面向大模型与 AI Agent 时代的 AI-Native Infra。

工作内容有三块：

- 构建高性能、多语言版本的 Agent 开发框架
- 设计企业级 Agent 系统，涵盖多 Agent 协作、记忆、知识、鉴权、观测等全生命周期
- 探索业界最新技术和工具，比如 Agent Skills、OpenCode 等

再来看第二个，面向抖音的 C 端产品。

工作内容集中在：

- 构建 AI Agent，实现自动化规划、任务拆解及执行
- 设计 Multi-Agent 系统，确保多 Agent 间任务高效运转
- 探索 Multi-Agent 协作机制，推动复杂任务的规划和拆解

要求理解 Multi-Agent 系统、任务分解、自动化规划、Prompt Engineering 等技术领域，有 Agent 相关实践项目优先。加分项里还提到了“对 AI Driven IDE 有实践经验”——这说明字节内部已经在用 AI 驱动的开发工具了，有这方面实践经验的候选人会更受青睐。

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709143109.png)

两个 JD 的共同硬要求有四个：

- LLM 原理和主流技术栈
- Agent 框架开发经验
- Multi-Agent 系统设计
- Prompt Engineering

注意一点，抖音团队甚至没有指定语言。对于还在纠结学什么语言的小伙伴来说，语言不再是壁垒，Agent 系统的工程能力才是。

## 02、AI Agent 岗求职准备

计算机基础，数据结构、算法、操作系统、计算机网络，每一份技术岗的 JD 里都会出现，AI 并没有改变这一点。所以八股该背还是得背，手撕该练还是得练。

在这个基础上，AI Agent 方向需要重点准备三个层次。

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709143650.png)

### 第一层，LLM 应用开发基础

Prompt Engineering、RAG、Tool Calling，这些是最基本的能力。

最快的上手路径就是对接一次 DeepSeek API，从零搭一个支持 Function Calling 的 Agent 后端服务，把请求、流式响应、工具调用的全流程跑通。

如果你没有目标项目，可以试试我最近开源的PaiCLI，Go版、TypeScript版、Python版都有，代码已经完整提交到GitHub。

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709143415.png)

Go版是今天早上刚完成的。五脏六腑，一应俱全。

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709143000.png)

>https://github.com/itwanger/paicli-go

不要用封装好的框架，直接用 HTTP 客户端发请求，这样才能理解每个字段的含义。

给这个 Agent 接上三个工具，搜索工具、代码执行沙箱、文件读写工具。然后让它完成一个端到端任务，比如“联网搜一下沉默王二是谁”。

从系统提示词开始，到处理工具调用结果、管理多轮对话上下文、处理异常中断，整个流程走一遍，就理解了 Agent 系统 80% 的核心机制。

### 第二层，Agent 项目实践

在应用层基础上选一个 Agent 项目深入实践，构建一个有记忆（短期加长期）、有工具集、能多轮对话的完整 Agent。

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709144016.png)

这一层重点要搞清楚 ReAct 循环。

ReAct 的全称是 Reasoning + Acting，模型推理一步、执行一步、观察结果、再推理下一步，循环往复直到任务完成或达到最大轮数。Claude Code、Codex、Qoder 这些 Agent 产品的核心都是 ReAct。

MCP 是目前工具接入的行业标准，解决的是“Agent 怎么发现和调用外部工具”的问题，支持 stdio 和 HTTP 两种传输协议。

Skills 本质上就是一套可复用的指令集，像我平常给大家生成的配图，都是通过 itwanger-image 这个 Skills 在 Codex 中完成的。

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709143910.png)

我也不藏着掖着，后面如果大家需要，我也可以把我的Skill开源出来。

### 第三层，进阶方向

Multi-Agent 协作，设计一个多 Agent 系统，让不同 Agent 分别承担规划、执行、验证的角色，通过消息传递协调工作。

具体来说，Plan-and-Execute 是最常见的 Multi-Agent 编排模式。

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709145509.png)

一个规划者 Agent 拿到任务后先拆解成分步计划，然后把每一步分配给执行者 Agent，执行完毕后由审核者 Agent 校验结果质量。三个 Agent 各自维护独立的系统提示词和工具集，通过上下文传递协调工作。

Claude Code/Codex 里的 Sub-agent 机制就是多 Agent 协作的典型实现。

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709144816.png)

什么是上下文工程？

记住这个类比。由 AI 圈的明星人物卡帕西提出，LLM 是 CPU，上下文窗口是内存，而你，是操作系统。操作系统干什么？决定什么数据被加载进内存。CPU 再强，内存里装的全是垃圾，照样算不出正确结果。

这就是上下文工程的核心，给 LLM 足够多，且足够有用的上下文信息，但不是绝不是多多益善。

## 03、PaiCLI 如何写到简历上？

Go 版的核心依赖只有 6 个（Bubble Tea、Cobra、Glamour、Lipgloss、doublestar、go/ast），整体非常轻量。17 个内置工具、8 个 LLM 厂商适配、ReAct + Plan-and-Execute + Multi-Agent 三种推理模式，该有的全有了。

回头对照一下字节那两份 JD 的工作内容，PaiCLI 全覆盖了。

- Agent 系统研发 → PaiCLI 本身就是一个终端 Agent
- Multi-Agent 协作 → 规划者、执行者、审核者三角色串行编排，自动传递上下文
- Agent 记忆和知识库 → 三层记忆系统加 RAG 代码检索，Go AST 提取函数级语义块
- Agent Skills → 三级 Skill 加载机制，内置、用户级、项目级按目录自动发现
- MCP 协议接入 → 支持 stdio 和 HTTP 双传输协议，JSON-RPC 消息循环处理工具发现和远程调用

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709145126.png)

代码完全开源，启动方式也很简单。

```
git clone https://github.com/itwanger/paicli-go.git
cd paicli-go
go run ./cmd/paicli doctor
go run ./cmd/paicli --once "你好，介绍一下当前项目"
go run ./cmd/paicli
```

项目名称：PaiCLI-Go 终端 Agent CLI（2026.05 - 2026.07）

项目描述：类似 Claude Code 的终端 Agent 命令行工具（Go 版），支持 ReAct、Plan-and-Execute、Multi-Agent、MCP、Skill、长期记忆、RAG 代码检索等，可在终端中通过自然语言驱动代码开发和调试。

技术栈：Go 1.26、Bubble Tea、Cobra、net/http、encoding/json、go/ast

核心职责：

- 基于 Bubble Tea 框架构建事件驱动的终端 TUI，实现流式文本输出、工具调用状态面板、Markdown 渲染，利用 Glamour 和 Lipgloss 做终端美化
- 实现 ReAct 推理循环，支持 17 个内置工具的并行调度（goroutine + semaphore 控制并发上限为 4），内置重复调用检测防止死循环
- 基于 OpenAI-compatible 协议实现多模型适配层，统一 DeepSeek、GLM、Kimi、Step、Agnes 等 8 个厂商的流式推理接口，可根据任务自动切换模型
- 实现 MCP 客户端，支持 stdio 和 HTTP 双传输协议，JSON-RPC 消息循环处理工具发现、远程调用和资源读取，8MB 缓冲区处理大响应
- 设计三级 Skill 加载机制（内置、用户级、项目级），支持 YAML frontmatter 元数据解析和延迟注入，系统提示中维护 Skill 索引供模型触发匹配
- 构建 RAG 代码检索模块，基于 Go AST 提取函数级 Chunks 和 Import 关系图，使用 token 频率向量做余弦相似度评分，路径匹配和符号匹配分别加权 0.4 和 0.8
- 构建 Multi-Agent 编排，规划者生成计划、执行者完成任务、审核者校验质量，三角色串行执行并自动传递上下文

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709150102.png)

Go 版本的仓库地址（TypeScript 版、Python 版也都在 GitHub 上）：

> https://github.com/itwanger/paicli-go

## 04、ending

AI 时代，机会与挑战并存。

对于我们程序员来说，冲击是最大的，这一点毋庸置疑。但是，机会也是最大的。

因为我们是第一波真正掌握顶尖模型和顶尖 Agent 工具的人。

像我，以前也没有写过Go、Python的代码，但并不妨碍我做出来 Go 和Python版本的 PaiCLI。

因为我具备了 Agent 工具的使用技巧，以及和 LLM battle 的工程能力。

我知道怎么纠错，怎么让 Agent 重回正轨，怎么让它产出我想要的预期结果。

![](https://cdn.paicoding.com/stutymore/bytedance-performance-ai-agent-20260709150826.png)

很多时候，事情并没有我们想象中的那么难，我们所要做的，就是去实践，去体验，去感受。

久而久之，你就会发现，原来你也是一个 Agent 高级工程师。😄

加油吧，兄弟姐妹们。我们下期见。
