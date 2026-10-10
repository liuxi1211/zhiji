---
title: Claude Code 爽用 Qwen3.6，Token 不限量。
shortTitle: Claude Code 爽用 Qwen3.6 阿里版Claude Code发布，夯爆了。阿里版Claude Code发布，爽用 Qwen3.6。阿里版Claude Code发布，Token 不限量。阿里版Claude Code发布，爽用 Qwen 不限量。
description: 手把手教你在 Claude Code 中接入讯飞星辰 MaaS 平台的 Qwen3.6 模型，6 折定价加阶梯返利，企业级调用量越多越划算。
keywords:
  - Claude Code
  - Qwen3.6
  - 讯飞星辰 MaaS
  - CC Switch
  - AI Agent
tag:
  - Agent
category:
  - AI
author: 沉默王二
date: 2026-07-15
---

大家好，我是二哥呀。

不管是Codex还是Claude Code，讲良心话，即便是他们俩不再升级迭代，我仍然觉得已经是最顶级的 Agent 产品了。

真正核心的问题，不是选哪个产品，而是：

Token用不起。

如果说非要替换 Claude Code，就是觉得A➗恶心人，我觉得阿里的 Qoder CLI就很不错，绝对是 Claude Code 的最佳替代。

尤其是 Qoder 系列上架了 BYOK（Bring Your Own Key）功能后，我们可以在 Qoder CLI 中爽用各种国产模型，包括 Qwen、Kimi、GLM 等等。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715180038.png)

压根不用 CC-Switch，就能轻松切换到我们喜欢的自定义模型。

这对于需要控制成本的企业，尤其是需要大量 Token 的企业来说，绝对是天大的利好。

能省一笔巨款啊。

像前段时间我给大家推荐的讯飞星辰 MaaS 平台，[就推出过限免 Token 的活动](https://mp.weixin.qq.com/s/5WzLce6AYphwMzQMxwxtjw)，当时很多小伙伴都去薅了。

>https://maas.xfyun.cn/modelSquare?ch=MaaS-jgkol-7Q9Z

最近他们又上架了一个新的活动，针对 Qwen3.6-35B-A3B 和 Qwen3.5-35B-A3B 两款模型，除了 6 折之外，还推出了阶梯返利机制，日均调用量越大，返利越多。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260716102315.png)

规则简单说明一下。

- 日均 30 亿 → 返 30%金额代金券
- 日均 100 亿 → 返还全额代金券；
- 代金券可用于平台内其他模型调用，不锁定某一个模型
- 达到门槛后，添加技术支持的微信，完成返利配置，后续按周结算返还。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715184421.png)

特别适合那些需要大量调用 Token 的企业级场景，比如智能客服、知识库问答、文档批量处理、代码辅助与审查等等。

接下来，我将以 Qoder CLI为例，来给大家演示一下如何在 Qoder CLI 中接入 Qwen3.6/Qwen3.5 模型。

>接入Claude Code的方式我们之前演示过了，用 CC-Switch 或者 PaiSwitch 就可以。

系好安全带，我们粗粗粗粗发～

## 01、为什么选 Qwen3.6？

Qwen3.6-35B-A3B 是阿里通义千问团队发布的中型 MoE 模型，350 亿总参数，实际推理只激活 30 亿参数。

这个架构下的模型容量大，但每次推理的计算开销小，响应速度快、单次成本低。对于需要频繁调用的 Agent 场景，MoE 架构天然就有成本优势。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715192616.png)

讯飞星辰 MaaS 平台上 Qwen3.6 打了 6 折，输入 1.08 元/百万 Token，输出 6.48 元/百万 Token。

拿同平台的其他热门模型做个参照：Kimi K2.7 Code 输入 6.5 元、输出 27 元，GLM-5.2 输入 8 元、输出 28 元。Qwen3.6 的单价大概只有它们的六分之一到八分之一。

同系列还有 Qwen3.5-35B-A3B，6 折后输入只要 0.24 元/百万 Token，输出 1.92 元/百万 Token。

适合对精度要求没那么极致、但调用量特别大的批处理场景，比如日志分析、文档分类、数据清洗这些。

## 02、获取 Qwen3.6

第一步，打开讯飞星辰 MaaS 平台的模型集市。

> 地址：https://maas.xfyun.cn/modelSquare?ch=MaaS-jgkol-7Q9Z

第二步，选择 Qwen3.6-35B-A3B，点击【API调用】。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715185133.png)

系统会提示创建一个应用。

名称随便填，比如“Claude Code 接入”或者“企业客服系统”都可以，纯粹是给自己做标记用的。

选择要授权的应用，点击确定。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260716103940.png)

应用创建成功后，页面上会展示三个关键信息。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715185534.png)

后面配置 Claude Code/Qoder CLI 全靠这三样东西：

- **modelId**：模型的唯一标识
- **API Key**：选择 HTTP 协议下面的那个密钥，不要选错了
- **请求地址**：API 的调用地址

## 03、配置Qoder CLI（含魔改）

在终端键入 qodercli 进入 Qoder CLI。

键入 `/model`，选择【自定义模型】。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715185711.png)

不过很遗憾，Qoder CLI目前还不支持纯粹的自定义，但不要慌。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715191810.png)

咱们技术人有技术人的解决手段。

这才是决定你是初级还是高级工程师的关键一步。

可能初级的小伙伴看到 Qoder CLI没有提供智谱、DeepSeek之外的自定义模型方法，到这一步就放弃了。

如果你技高一筹，那就不再是青铜玩家了。

好，方法直接告诉你，打开 Codex，输入以下提示词。

```
我想知道Qoder CLI怎么通过配置文件来配置第三方的模型，目前我已经通过/model 命令配置了GLM-5.2和DeepSeek V4，但没有那种配置自定义模型的，我想直接通过配置文件加入进去，所以需要你调研一下，我授权给你所有我本地的权限。
```

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715201402.png)

然后我们把讯飞提供的三项关键信息交给 Codex，让他直接帮我们配置好。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715202230.png)

### 测试连通性

这时候，我们再启动Qoder CLI，然后键入 `/model`，就会看到我们刚刚已经成功配置的 Qwen3.6 模型了。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715202443.png)

选择他，能看到模型已经成功切换到讯飞 xopqwen36v35b了。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715202404.png)

随便输入一个提示词，看看模型能不能返回信息。

如果能返回，就说明我们的魔改已经成功了。

如果测试失败，大概率是以下三个原因之一：

- API Key 复制的时候多了空格或者少了字符
- 请求地址选错了
- 模型 ID 填错了

回去逐项核对。

## 04、畅玩Qoder CLI

我们来创建一个子 Agent，在Qoder CLI中键入 `/agents`，然后输入以下提示词。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715205721.png)

```
创建一个项目级 Subagent，名称为 byok-researcher。

它是 PaiCLI 的 BYOK 架构调研专家，负责研究 Qoder CLI 如何添加、保存、选择和调用自定义模型，并对照 PaiCLI 当前代码给出实现方案。

要求：
- model: inherit，继承当前讯飞千问 3.6 模型
- 只使用 Read、Grep、Glob、Bash、WebSearch、WebFetch
- 禁止 Write、Edit 和创建其他 Agent
- 不读取或输出任何 API Key
- 优先查 Qoder 官方文档和本机只读命令
- 检查 PaiCliConfig、LlmClientFactory、/config、/model、CLI、TUI、Subagent 和 Runtime API
- 区分“已验证、合理推断、尚未确认”
- 本轮只调研，不修改代码

最终输出：Qoder BYOK 流程、PaiCLI 能力盘点、差距矩阵、唯一推荐方案、分阶段实施清单和验收 Case。
```

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715205753.png)

然后输入新的提示词，用我们刚刚创建的专业级 Agent 来调研 Qoder CLI 的 BYOK 实现。

```
@byok-researcher 调研 Qoder CLI 的 BYOK 实现，并为 PaiCLI 给出可落地方案。本轮只调研，不修改代码。
```

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715205849.png)

上下箭头还可以查看当前Agent的进度。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715210012.png)

可以在讯飞星辰的 MaaS 平台查看实时的Token调用。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260716103621.png)

爽用爽用，舒服的了。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715210418.png)

调研结果也出来了。

## 05、哪些场景最烧 Token

以下这些场景就非常适合拿 Qwen3.6 来做 Agent 的后端模型，比如说智能客服、知识库问答、批量文档处理、代码辅助与审查等等。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715204030.png)

### 智能客服系统

电商、金融、SaaS 类企业的客服系统，每天要处理成千上万轮用户对话。

Qwen3.6 按 6 折定价，一轮客服对话大约消耗 2000 到 5000 Token，折算下来每轮对话的成本控制在几分钱。

### 知识库问答

企业内部的产品文档、技术手册、操作指南构建成向量知识库，用户提问时先检索相关文档片段，再把检索结果喂给 Qwen3.6 生成精准回答。

这种 RAG 架构是当前企业知识管理的主流方案。

### 批量文档处理

合同审查、财报信息提取、邮件自动分类、会议纪要生成——这些文档密集型任务是企业 AI 应用的另一个大头。

### 代码辅助与审查

前面的实战 Case 已经展示了 Qwen3.6 的编码水平。把它接入 Claude Code 或者其他编码 Agent 后，研发团队每天的代码补全、代码审查、测试用例生成、技术文档撰写，都会产生大量 Token 消耗。

### 怎么添加工作人员领取返利

注意，阶梯返利需要联系讯飞星辰的技术支持完成配置。流程很简单，三步就搞定。

**第一步：扫码添加技术支持微信**，在 Qwen3.6 模型详情页顶部，移动鼠标到【更多优惠咨询】按钮，就会滑出技术支持的微信二维码。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260716103249.png)

**第二步：把你的企业调用需求告诉对方**——日均大概多少 Token、主要用在什么业务上。技术人员会根据你的调用量级，确认适用的返利档位（30 亿返 30% 还是 100 亿全额返）。

**第三步：技术支持会在后台帮你完成返利配置**。后续的返利按周自动结算，代金券直接发到你的平台账户里，可以用于讯飞星辰平台内所有模型的调用。

根据我之前的经验，你可以把带有绑定应用ID的模型截图发给他们就行，就是下面这个东西。

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260716103832.png)

## 07、ending

Claude Code/Qoder CLI 搭配 Qwen3.6/Qwen3.5，对 Token 需求量大的企业来说，觉得是非常划算的一个选择。

除了 6 折的优惠，还有阶梯返利。30 亿 Token 返 30%，100 亿 Token 全额返——调用量越大，每个 Token 的实际成本越低。

【**用对的模型，花对的钱，把预算花在刀刃上，让 Agent 替你干活，而不是替你烧钱**。】

讯飞星辰 MaaS 平台入口：

> https://maas.xfyun.cn/modelSquare?ch=MaaS-jgkol-7Q9Z

![](https://cdn.paicoding.com/stutymore/claude-code-qwen36-maas-20260715210331.png)

算一笔具体的账。

假设一个企业团队日均消耗 30 亿 Token，输入和输出各占一半（按 Qwen3.6 的 6 折价格）：

- 日成本 = 1500 百万 × 1.08 + 1500 百万 × 6.48 = 1620 + 9720 = 11340 元
- 周成本 = 11340 × 7 = 79380 元
- 周返 30% 代金券 = 23814 元
- 实际周净支出 = 55566 元

返还的 23814 元代金券又能投入到其他模型的调用中，进一步摊薄了企业整体的 AI 推理成本。

PS：阶梯返利的配置和领取需要通过讯飞星辰平台的技术支持来完成，记得按照前面的步骤添加哦。
