---
title: 本科生最佳就业城市排行榜出炉！
shortTitle: 本科生就业城市排行榜
description: 麦可思研究院发布2025届本科生就业报告，十强城市薪资排名出炉。以苏州为例拆解AI Agent岗位JD，分析企业需要什么技术栈，给出AI时代的求职准备路线图。
keywords:
  - 本科生就业城市排行榜
  - AI Agent 岗位
  - AI Agent 求职
  - 苏州就业
  - AI 时代求职准备
tag:
  - Agent
  - 求职
category:
  - AI
author: 沉默王二
date: 2026-07-08
---

大家好，我是二哥呀。

麦可思研究院上周发布了《中国本科生就业报告》，覆盖 19.5 万份 2025 届毕业生样本。

前10依次是：北京、上海、深圳、杭州、南京、苏州、广州、宁波、东莞、佛山。

![](https://cdn.paicoding.com/stutymore/sucai-20260708084704.png)

北上深杭依然是前四，剩下六个全是新一线。

以前是去一线城市才能拿高薪，现在的逻辑变了，产业在哪，高薪就在哪。

还有一点信息值得注意。深圳、广州、杭州、东莞、佛山这五座城市，同时出现在常住人口增量十强名单里。

这意味着，刚毕业的小伙伴可以选择这几个城市。

我花了半天时间，把十强城市的数据整理了一遍，又拿我之前呆过的苏州做样本，搜了一圈 AI Agent 岗位的 JD，看看现在企业到底在要什么样的人，我们又该怎么去准备。

> 系好安全带，我们粗粗发～

## 01、苏州的 AI Agent

老读者应该知道，我的第一份工作是在苏州。

所以这次我们的调研目标也聚焦到苏州，直接在老板直骗上搜【AI Agent】。

![](https://cdn.paicoding.com/stutymore/best-city-ai-agent-jd-20260708093156.png)

JD 主要集中在这些信息：

1. 计算机、人工智能相关专业，本科及以上学历，有 Agent 系统开发经验
2. 熟悉 Transformer 架构及主流 LLM 技术栈，熟悉 LLM 微调、推理优化及评估方法论
3. 具备 LLM 应用开发经验，理解 Prompt Engineering、RAG、Tool Calling 等技术
4. 熟悉至少一种 Agent 框架，如 OpenClaw、LangChain、Dify
5. 具备多智能体系统开发经验，掌握 ReAct、CoT 等推理框架

结合这些关键信息，我们来讲一讲 AI 时代的岗位要求。

## 02、AI 时代的岗位要求

就 Agent 产品来说。

目前做得最好的，国外有 Claude Code 和 Codex，国内有阿里的 Qoder 系列、腾讯的 WorkBuddy 系列。

这些 Agent 的核心就是 ReAct 循环，接收指令 → 思考（Reasoning）→ 选择工具 → 执行（Acting）→ 观察结果（Observation）→ 决定下一步。

![](https://cdn.paicoding.com/stutymore/best-city-ai-agent-jd-20260708094618.png)

开发 AI Agent 系统，就是把这个循环工程化。

让它能稳定跑起来，出了错能自动恢复，执行过程可观测可追溯。

### Tool Use / Function Calling

像 DeepSeek V4 和 GLM-5.2，都是文本模型。

要让他们搜网页、跑代码、读文件、查数据库，就需要 Tool Use。Function Calling 是目前主流的实现方式。

![](https://cdn.paicoding.com/stutymore/best-city-ai-agent-jd-20260708094910.png)

我们在实现 Agent 系统/应用的时候，要把工具的功能描述和参数格式注册给模型，让模型在推理过程中判断要不要调用、调用哪个、传什么参数等。

项目经验上，我们要突出表现的，就是工具的注册和发现机制、结果的解析和校验、工具执行过程中错误和超时的处理。

### Workflow / 自动化流程

Workflow 就是把多个 Agent 步骤编排成一条可复用的流水线。

和传统工作流引擎不同的是，AI 时代的 Workflow 节点可能是一次大模型推理，所以输出内容是不确定的，执行时间也不固定，中途还可能触发分支决策。

![](https://cdn.paicoding.com/stutymore/best-city-ai-agent-jd-20260708095800.png)

Dify、Coze 这类产品就是可视化的 AI Workflow 平台，拖拽式编排。如果是做后端研发，LangGraph 的工作流编排模块是这个方向的技术重点。

### Multi-Agent

单个 Agent 处理日常任务没问题，复杂项目就需要多个 Agent 分工协作。

一个 Agent 分析需求，一个生成代码，一个跑测试，一个做代码审查，各自有独立的提示词、工具集和上下文。

![](https://cdn.paicoding.com/stutymore/best-city-ai-agent-jd-20260708100016.png)

Claude Code 里面的 Sub-agent 机制就是多 Agent 协作的典型实现。主 Agent 把子任务分发给 Sub-agent，Sub-agent 完成后把结果返回。

这种模式下，协作架构要解决的核心问题有三个，任务怎么拆分和分配、上下文怎么在 Agent 之间传递、多个 Agent 的结果怎么汇总和冲突裁决。

### 长短期记忆架构

Agent 执行任务时，上下文窗口是有物理上限的。比如 GPT-5.5 的窗口是 256K token。

所谓的短期记忆，指的是当前会话的上下文，长期记忆指跨会话的持久化信息存储，包括用户偏好、项目元数据、历史决策记录等。

![](https://cdn.paicoding.com/stutymore/best-city-ai-agent-jd-20260708100414.png)

Claude Code 的 CLAUDE.md 就是一种长期记忆的工程实现，每次新开会话时自动加载进上下文。更复杂的方案需要引入向量数据库，通过 RAG 检索历史记忆中和当前任务相关的部分注入上下文。

记忆架构设计的核心取舍在于存什么、存多久、什么时候取出来、取多少。

存太多浪费 token，存太少 Agent 会失忆。

### LLM 应用工程化落地

跑通一个 demo 和上线一个可用的产品之间隔着一整套 Harness。比如说推理延迟要控制在用户可接受的范围，并发请求要扛得住，token 消耗要可控，异常情况要有降级方案。

![](https://cdn.paicoding.com/stutymore/best-city-ai-agent-jd-20260708100440.png)

具体技术点包括前缀缓存（prefix caching，命中时 token 成本只有原来的十分之一）、流式输出、批处理推理、模型路由（根据任务复杂度动态选择不同档次的模型降低成本）等等。这些都是典型的后端工程问题，只是服务对象从传统请求变成了大模型推理。

## 03、AI Agent 求职路线图

计算机基础，数据结构、算法、操作系统、计算机网络，在每一份技术岗的 JD 里都会出现，AI 并没有改变这一点。

![](https://cdn.paicoding.com/stutymore/best-city-ai-agent-jd-20260708101327.png)

Prompt Engineering、RAG、Tool Calling，这三项是 LLM 应用开发的最基本能力。

最快的上手路径就是对接一次 DeepSeek API（或其他大模型 API），从零搭一个支持 Function Calling 的 Agent 后端服务，把请求、流式响应、工具调用的全流程跑通。

再具体一点，给这个 Agent 接上三个工具，搜索工具、代码执行沙箱、文件读写工具。

然后让它完成一个端到端任务，比如"帮我分析这个 CSV 文件的销售数据并生成可视化图表"。从给 Agent 写系统提示词开始，到处理工具调用结果、管理多轮对话上下文、处理异常中断，整个流程走一遍，就理解了 Agent 系统 80% 的核心机制。

![](https://cdn.paicoding.com/stutymore/best-city-ai-agent-jd-20260708112907.png)

在应用层基础上选一个 Agent 框架深入实践。

构建一个有记忆（短期+长期）、有工具集、能多轮对话的完整 Agent。

![](https://cdn.paicoding.com/stutymore/best-city-ai-agent-jd-20260708101459.png)

进阶方向有两个。

第一个是多 Agent 协作，设计一个多 Agent 系统，让不同 Agent 分别承担规划、执行、验证的角色，通过消息传递协调工作。

第二个是上下文工程，这是 2026 年 AI 工程领域最热门的实践方向之一，核心思路不是写更好的单条 prompt，而是系统性地管理模型每一轮决策时能看到的所有信息，系统提示、对话历史、工具调用结果、RAG 检索到的文档、用户偏好，这些信息怎么排列、怎么压缩、怎么筛选，直接决定 Agent 的表现上限。


## 04、PaiCLI 如何写到简历上

给大家提供一个真实的 Agent 应用开发案例，也是我趁写文章期间用 Codex 开发出来的一个 Python 版的 Claude Code。

![](https://cdn.paicoding.com/stutymore/best-city-ai-agent-jd-20260708142704.png)

目前 PaiCLI 已经有 Java 版本、TypeScript 版本和 Python 版本了，下一步就是搞个 Go 版本。

讲良心话，AI 时代，语言这块确实已经不是我们的核心竞争力了，因为 Agent 可以搞定，任何语言它都很精通。我们要做的就是提示词工程、上下文工程。

回头对照一下前面那份 JD 的工作内容，PaiCLI 全覆盖了。

- Agent 系统研发 → PaiCLI 本身就是一个完整的终端 Agent
- Tool Use / Function Calling → 15+ 内置工具 + MCP 协议接入外部工具生态
- AI Workflow → ReAct 推理循环 + 任务规划与编排
- 多 Agent 协作 → 规划、执行、审核三角色分工，最大并行 4 线程
- 长短期记忆 → 三层记忆系统，对话上下文 + SQLite 持久化 + 上下文压缩
- LLM 工程化 → 流式输出、动态 Token 预算、自动触发上下文压缩

开发其实也没花多少时间，我把GitHub仓库地址也贴一下，需要的小伙伴可以参考：

>https://github.com/itwanger/PaiCLI-Python

抄也行，改也行，反正开源的。

项目名称：PaiCLI Agent AI 应用开发（2026.05 - 2026.07）

项目描述：类似 Claude Code 的终端 Agent CLI，支持 ReAct、Multi-Agent、MCP、三层记忆、RAG 代码库检索，可在终端中通过自然语言驱动代码开发和调试。

技术栈（Python 版）：Python 3.11+、httpx、MCP SDK、prompt-toolkit、Rich、Typer、SQLite

核心职责：

- 基于 ripgrep 和 Glob 组合实现精确代码检索，单次搜索延迟控制在 200ms 内，并以 RAG 语义检索作为兜底
- 实现 ReAct，并支持并行工具执行和动态 Token 预算管理，上下文占用达 90% 时自动触发摘要压缩
- 设计长任务持久化方案，基于 SQLite 存储任务状态，后台线程池异步执行，支持进程重启后自动恢复未完成任务
- 实现三层记忆，短期记忆管理当前对话上下文，跨会话的长期记忆用 SQLite 持久化，压缩模块做了边界感知的上下文压缩，支持 BM25 加余弦相似度混合检索
- 集成 MCP 协议接入外部工具生态，支持 stdio 和 HTTP 双传输协议，并通过人工审批机制实现高危工具调用的安全管控
- 构建多 Agent 协作，规划模块将复杂任务拆解为有向无环图，执行模块并行处理子任务，审核模块校验质量并支持自动重试，最大并行 4 线程