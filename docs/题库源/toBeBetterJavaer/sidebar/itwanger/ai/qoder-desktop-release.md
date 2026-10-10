---
title: 阿里Qoder桌面版正式发布，直接给到夯。
shortTitle: Qoder 桌面版正式发布
description: Qoder Desktop 桌面版正式发布评测，Quest 独立视窗实战用 Qwen 3.7 夜间模式开发 PaiCLI TypeScript 版本，附夜间折扣省钱攻略。
keywords:
  - Qoder Desktop
  - Quest 独立视窗
  - Qwen 3.7 夜间模式
  - AI编程
  - PaiCLI TypeScript
tag:
  - Qoder
  - AI编程
category:
  - AI
author: 沉默王二
date: 2026-06-28
---

大家好，我是二哥呀。

就国内的 AI 产品来说，Qoder 毫无疑问是我最喜欢的，甚至不需要加之一（真心话。

不论是 Qoder Desktop，还是后续的 Qoder CLI、QoderWork、QoderWake、Qoder Cloud Agents、Qoder Mobile/Web App，都已经成为我日常工作流中不可或缺的工具。

PaiCLI 这个终端 Agent 在交互上就参考了不少 Qoder CLI。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628171214.png)

那今天我要告诉大家另外一个好消息。前不久，Qoder Desktop 1.0 版本正式发布了，Qoder 正式从 AI IDE 全面升级为智能体自主开发工作台。

其中最让我兴奋的就是 Quest 的独立视窗。

面向任务委派、自主编程、自主进化。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628171448.png)

接下来，我将用她实现一个 PaiCLI 的 TypeScript 版本，并开源出来。

其实很早之前我就想做这件事了，只是一直腾不出来时间。

刚好 Qoder 全系最近上线了 Qwen 3.7 的夜间折扣，Max 模型 2 折、Plus 模型 4 折。嗯，**Qwen 3.7-Max 白天也是半价呢**。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628171544.png)

>https://qoder.com/desktop

这让我更有底气把这件事交给 Quest 全权去做。

现在的 Agent 都在卷长程任务，但长程任务真的是 Token 吞金兽。

我之前用 Codex 跑 goal 任务（做一个技术派的微信小程序版本），这玩意根本没有停不下来的意思，一直跑了 4 个半小时，我的 Pro max 用量全部耗尽才停下来。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628171821.png)

Qoder 的夜间模式让我敢把这次长程任务交给 Quest 去跑。

好，来看我跑了一晚上的实测结果。

【视频1】

瞧瞧，不错吧，PaiCLI TypeScript 0.1 版已经完全跑通了，基本的交互，tool use、联网搜索、ReAct、Token预算，这些基本的功能都有了。

一个晚上能有这样的产出，不知道大家感觉怎么样，我个人是挺满意的。😄

>GitHub地址：https://github.com/itwanger/paicli-ts

想要学习的小伙伴可以直接 clone 这个仓库，按照 readme 安装依赖，运行 `pnpm paicli` 就可以体验了。

## 01、安装 Qoder Desktop 桌面版

接下来，我们先来安装 Qoder Desktop 桌面版。

访问：`https://qoder.com/desktop`

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628172505.png)

Windows、Linux 和 macOS 版本都有，下载后直接安装即可。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628172608.png)

完成安装后，点击右上角【打开Quest】按钮，就可以进入 Quest 独立视窗。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628172746.png)

有了 Quest 独立视窗，我们就可以在一个窗口里专注于古法编程（当然也可以让Agent干活了），另一个窗口里让 AI 自主执行任务。

两个窗口之间的上下文是可以无缝衔接的。

只不过 Quest 更注重 Agent 全自动化。

## 02、Quest 独立视窗小试牛刀

Quest 的任务管理是这次升级中我认为最大的变化。

每个任务都有完整的流水线——任务拆解、代码变更、终端输出、浏览器预览、测试结果，全部结构化展示，可以追溯 Agent 在每一步做了什么决策。

举个例子，我给 Quest 提了一个任务：

> 帮我给 PaiAgent 项目的 workflow 模块添加一个“条件分支”节点，参考现有的 LLM 节点实现方式，支持基于上一步输出的 JSON 字段做条件判断，走不同的下游分支。写完之后跑一下单元测试。

Quest 接到任务后自己拆步骤、读代码、写实现、跑测试。

![](https://cdn.paicoding.com/paicoding/d61ee715931d9a14c78cc90083a61d57.jpg)

Quest 还接入了浏览器控制能力，任务完成后它会自动打开浏览器帮忙做验收测试。

![](https://cdn.paicoding.com/paicoding/91335faf9700b234dc463cccf97ee9e2.png)

在浏览器这边也能看到 Quest 在操作。

![](https://cdn.paicoding.com/paicoding/f86bd334315a95f3410fc62848900eb6.jpg)

Experts 专家团模式也完全集成进了 Quest 视窗。

简单任务用单 Agent 就够了，复杂任务切到 Experts，Leader Agent 自动拆任务、分配给规划、调研、编码、审查、测试五类专家，流水线协同交付。

![](https://cdn.paicoding.com/paicoding/48ef992424b7dca18a971d97aaa2083d.png)

搞清楚 Quest 的能力后，我们就来让她来完成 PaiCLI TypeScript 版本的开发。

## 03、夜间模式开发 PaiCLI TS 版

简单提一嘴。

PaiCLI 是我用 Java 实现的 Agent 产品，对标 Claude Code，已经有了 ReAct、Tool Use、联网搜索、MCP、Multi-Agent、Skill、微信通道、摘要压缩、Memory 机制等一整套能力。

![](https://cdn.paicoding.com/stutymore/paicli-20260611161157.png)

Java 版本上线后，我就想做 TypeScript 版本了，毕竟主流的桌面版 Agent 和终端 Agent，技术栈都是 TS。

正好 Qoder 上线了 Qwen 3.7 的夜间折扣，咱就用 Quest 来干这件事。

到底夯不夯，实测说话。

好，我们让专家团调研一下。

```
我想实现一个PaiCLI的 typescript版本，我手头已有的资料先告诉你。
第一、我已经实现了一个Java版本的PaiCLI：/Users/itwanger/Documents/GitHub/paicli 你可以先调研一下。
第二，Claude Code的源码在这里：/Users/itwanger/Documents/GitHub/claude-code-source-code
第三，这是一个终端Agent产品，我希望用typescript技术栈，交互也可以参考Qoder CLI：https://qoder.com/zh/cli
```

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628175522.png)

生成 Spec。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628175706.png)

之所以选择专家团模式，是因为这个任务比较复杂，涉及到大量的调研工作，包括现有的 Java 版本、Claude Code 的源码、Qoder CLI 的交互方式等。

多个专家并行调研，效率更高。于是我们看到：

调研员 Alex 负责研究 Java 版的 PaiCLI 架构。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628175908.png)

调研员 Sam 负责研究 Claude Code的源码架构。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628175806.png)


调研员 Tina 负责研究 Qoder CLI 的交互方式。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628175957.png)

然后，一份严谨的 Spec 就生成了。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628180045.png)

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628180113.png)

计划出来后，我们可以直接执行，也可以设置定时，让 Quest 在夜间模式下自动执行任务。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628180233.png)

当然了，我们也可以新建一个Quest任务，输入：

```
今晚 22:01 开始执行这份PaiCLI_TypeScript_实现方案_1ddab77d.md
```

Quest 就会帮我们新建一个定时任务，到点开始干活。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628180842.png)

记得模型切到 Qwen3.7-Max 或 Qwen3.7-Plus。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628181036.png)

### 夜间模式的折扣力度

每天 22:00 到次日 08:00（北京时间），共 10 个小时，含周末和节假日。

在任意 Qoder 产品里切到 Qwen3.7-Max 或 Qwen3.7-Plus，这个时段内发起的请求自动按夜间价计费。

对，Qoder 全系支持，包括 Qoder CLI。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628223132.png)

折扣力度如下：

- Qwen3.7-Max：标准倍率 0.5x → 夜间 0.1x，2 折，优惠 80%
- Qwen3.7-Plus：标准倍率 0.1x → 夜间 0.04x，4 折，优惠 60%

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628173845.png)

嗯，省钱，这下是真的省钱了。

并且还能让 AI 这个牛马疯狂加班替我们干活，反正她不知道累。

嘿嘿。

## 04、夜间模式前的准备工作

这里我们可以先做一点准备工作，比如说看看 Node.js 和 pnpm 是否已安装？

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628184723.png)

顺带创建必要的源文件。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628184828.png)

对了，记得编辑定时任务。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628185516.png)


开启【保持电脑处于唤醒状态】，如果目标比较确定的话，还可以开启【Goal】模式。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628185328.png)

Goal 模式下，Quest 会在任务执行过程中不断自我优化，直到达到目标为止。

## 05、夜间模式后的实测体验

时间一到，Quest 就开始猛猛干活了。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628190132.png)

我们什么也不用管，睡个大觉，醒来就好了。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628190244.png)

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628211514.png)

OK，所有任务搞定后，我们来运行 PaiCLI，真正体验一把。

键入 `pnpm paicli`，就可以看到运行结果了。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628234804.png)

整体还不错哈。

键入 `联网搜一下沉默王二是谁啊？`，来看看整体的效果。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628234944.png)

tool use 可用，Web search 工具也可用。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628235020.png)

思考过程和最终的response也都正确。

真不错，真不错。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628235059.png)

底部的 toolbar 也能实时看到效果。

现在是晚上 23：54分，回到 Qoder 的 Quest 独立视窗里，就能看到错峰折扣已生效的提示。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628235340.png)

OK，我们启用专家团再帮我们做一全套spa，看看有没有bug。

>目前我们已经完成了一个版本，现在我希望专家团帮我对照一下Spec，看看还有哪里没有实现；还有哪些bug，哪里需要优化。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628235910.png)

OK，没什么问题。

>https://github.com/itwanger/paicli-ts

代码已经提交到 GitHub，大家可以直接 clone 体验。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260629083526.png)

当然了，这只是 V0.1 版本，要实现真正工程级别的 Harness，还需要继续迭代，也欢迎大家提 issue，或者直接 PR。

就目前来说，终端Agent和桌面Agent的开发经验，包括Harness这块的开发经验，非常宝贵，也是AI原生岗位的核心能力之一。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260629074439.png)

从 Qoder 专家团给我的反馈里来看，PaiCLI TS 版本算是完成了 75% 的 Spec。

一次性达到这个目标，我觉得已经非常不错了。

看一眼 Token 的消耗，也就 20%，我是 pro plus 套餐，夜间模式还是太划算了。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260629074550.png)

有了夜间模式，每天晚上睡觉前设置个 Goal，第二天醒来就能看到效果，这才是真正 Agent 该有的味道。

### PaiCLI 如何写到简历上？

**PaiCLI-TS（TypeScript 版 Agent CLI）**

项目简介：类似 Qoder CLI 的终端 Agent CLI，支持 ReAct 推理、Multi-Agent 协作、MCP 工具集成、三层记忆系统、RAG 代码库检索等，可在终端中通过自然语言驱动代码开发和调试。

核心职责：
- 基于 ripgrep + Glob + read_file 的组合实现 Agentic Search 的精确检索，单次搜索延迟控制在 200ms 内；并以 RAG search_code 工具作为语义检索兜底，提升仓库代码定位与分析效率。
- 基于 ReAct 实现 Agent 推理，支持并行执行和动态 Token 预算，上下文压缩算法会在 90% 上下文占用时自动触发摘要压缩。
- 集成 MCP 接入外部工具生态，支持 stdio/HTTP 双传输协议和 Schema 自动裁剪，并通过 HITL 审批机制实现工具调用的安全管控。
- 将 web_search 和 web_fetch 作为 Function Calling 工具注册到 Agent 的工具链中，实现了 LLM 自主判断联网时机的智能工具选择

## ending

一个夜晚，PaiCLI 的 TypeScript 版就从零跑通了。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260629074919.png)

如果不是夜间模式，6000 个 Credits 真要耗完了。

但有了夜间模式，只用了 1174 个 Credits，不到 20%。

夜间模式改变的不是“AI 能写多快的代码”，而是“敢不敢把一整件事交给 Qoder”。

当成本降到五分之一的时候，决策跟着变了。以前我会犹豫“这个任务值不值得消耗这么多 Credits”，现在直接扔进去就行。

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260629075007.png)

>建议大家抓紧时间去试试，戳👉【阅读原文】可直达 Qoder 官网下载桌面版体验。

白天做设计、做架构，夜里让 Quest 猛猛干活。

这大概就是我目前试过的最舒服的 AI 编程节奏了。

【**终于不用在 Credits 和任务规模之间做取舍了**。】

该加班的是AI，不是人。

哈哈。

我们下期见～
