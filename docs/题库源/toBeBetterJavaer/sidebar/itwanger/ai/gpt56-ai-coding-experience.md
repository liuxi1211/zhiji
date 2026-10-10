---
title: 面试官：“都 GPT-5.6 了，你说说你的优势是什么？”，我笑了：“没经验的人，GPT-5.6/Fable 5 写的代码也是一坨。”，面试官：“细聊经验”
shortTitle: AI 时代程序员的经验优势
description: GPT-5.6 时代程序员的优势是什么？用 GitClear、DORA 报告和 Anthropic 官方最佳实践拆解 AI 时代的经验四件套，上下文、计划、验证、纠偏，附 Claude Code 实操方法。
keywords:
  - GPT-5.6
  - Claude Code
  - AI 编程面试
  - 上下文工程
  - AI Coding 经验
tag:
  - 面试
category:
  - AI
author: 沉默王二
date: 2026-07-10
---

OpenAI 把 GPT-5.6 系列正式放出来了。

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710143818.png)

超大杯 Sol，配上大杯 Terra 和中杯 Luna，让整个AI圈又沸腾起来了。

但要我说，把Codex改名为ChatGPT就很low逼，已经是 Agent 时代了，GPT前面再加个Chat绝对是多此一举。

真不如把ChatGPT改名为Codex。

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710142740.png)

模型一年比一年猛，哦不，一天比一天猛，我们这些臭写代码的，优势还剩什么呢？

经验。

同样是Fable 5 和GPT-5.6，不同人的手里出来的东西肯定是天差地别。

>那接下来，我就把自己的经验Vibe Coding这些年（有一年了吧）的经验分享出来，系好安全带，我们粗粗粗发～

省流，记住这四条经验：

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710144018.png)

- 喂对上下文——维护 CLAUDE.md，提问带文件名，给出参照物
- 先计划后动手——先让模型读代码、出计划，认可了再放它写
- 小步走勤验证——每个任务先定义“怎么算完成”，测试先行
- 及时中断——敢打断、敢清上下文、敢回滚重来

## 01、喂对上下文

Claude Code 每次新开会话，会自动把项目里的 CLAUDE.md 加载进上下文窗口。

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710144704.png)

ps：GPT-5.6 生成的图，同样的Skill，感觉效果还没有GPT-5.5 好，真有点离谱了。

这绝对是最重要的一条。

我们需要在项目一开始就把常用命令、代码规范、测试方式、踩过的坑都写进去。Codex 对应的文件叫 AGENTS.md，机制一样。

没经验的人和有经验的人，差距从这个文件就拉开了。

前者的仓库里压根没有这个文件，每次提问都让模型盲猜项目结构（聪明的模型会自己去调研，但又很浪费Token）；后者会把“本项目用 pnpm 不用 npm”、“测试跑 make test”、“数据库字段的命名规则”这类约束固定在文件里，模型每一轮决策都带着这些信息。

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710144305.png)

提问方式也一样。

给出具体的文件名，比让模型自己满仓库搜快得多。“修一下登录的 bug”和“看 auth/login.go 里的 token 校验，过期分支好像没处理”，两个提示词的产出质量高下立判。

当然了，CLAUDE.md 这个文件不是一次写完的，建议当成孩子来养。

每次你觉得模型犯错了，就把经验心得用一句话写进去，下次它就不会再犯，这比每次口头重复约束省事得多。

还有一个容易被忽略的，参照物。

改界面就丢一张设计稿或者截图给它，仿一个功能就把参考实现的文件路径给它。模型有了具体的对照物，产出和期望的偏差会小很多。

Codex 在这方面的优势就比Claude Code终端大得多。

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710144839.png)

我做 PaiCLI 的时候，四个语言版本。

Python、Go、TypeScript我都没怎么学过，但能让 Codex 产出预期的结果，就是因为我在 CLAUDE.md 里写了语言约束和测试约束，给了它参照物。

知道模型缺什么，提前补给它。

## 02、先计划后动手

我怕有一套自己的四步工作流，探索、计划、编码、提交。

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710145819.png)

- 先让模型把相关代码读一遍，明确要求这个阶段不许写代码；
- 然后让它出一份实施计划；
- 计划审过了，再放它动手；
- 最后提交。

Claude Code 里的 Plan mode 就是给这个流程做的，按两下 Shift+Tab 就切换到这个模式。

为什么？

因为方向错误是 AI 开发里最昂贵的错误。模型写代码的速度是人的几十/几万倍，方向对了是杠杆；方向错了，它会用同样的速度生出上千行/上万行屎山代码，token 没了不说，返工的时间也搭进去了。

人自己写代码，往往会想清楚再动手。

可一旦有了AI，总觉得 AI 无所不能，就容易脑子一热，扔一句话给 AI 就让它干活了。

但往往这个时候才是最要命的，因为 AI 还没有发展到 AGI 的阶段，它的理解能力和推理能力虽然已经很强了，但还不够强。

审计划比审代码容易得多。

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710145715.png)

一份跑偏的计划，扫一眼就能发现方向不对，两分钟打回重写；等它变成两千行代码再发现，就只能推倒重来了。

所以有经验的人把审查的重心从代码前移到了计划，需求越复杂，这一步越值得去做。真正复杂的需求，我还会让模型先把方案写成一份规格文档，包括要动哪些文件、接口怎么变、哪些行为不能变，审完这份文档才开工。

我做 PaiCLI 的 Go 版只花了一个早上，不是我 Go 写得多好，我压根没写过 Go，是前三个版本的计划模板直接复用，模型拿着一份被验证过三次的计划开工，几乎没走弯路。

## 03、小步走，勤验证

具体到执行层面，两个动作。

第一个是测试先行。先让模型写测试用例，反正AI写代码快，这一步千万不要省略，就让它多写 case 测试，把边界条件都覆盖一下。

第二个是把“怎么算完成”写成明确条件。

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710150331.png)

Claude Code 有一个 /goal 命令和 /loop 命令。如果 Token 充足的话，一定要多试试这俩命令。

当然了，Token有限就不要乱用，贼费 Token。

/goal 的机制值得说一嘴。每轮任务跑完，由另一个小模型（默认 Haiku）判定完成条件是否达成，没达成就继续干。干活的模型和判卷的模型不是同一个，这个设计叫 maker-checker，出题人和阅卷人分离。

一个能兜底的完成条件长什么样？

三个要素：

- 一个可测量的终态，比如测试全部通过、构建退出码为 0、待办队列清空；
- 一个明确的验证方式，让模型说清它打算怎么证明；
- 再加上过程约束，哪些文件不许动、哪些行为不能变。

“把这个功能做好”不是完成条件，“tests/auth 目录下 12 个用例全绿，且不修改任何测试文件”才是。

## 04、大胆纠错

说句实在话，我一开始用Claude Code，不太敢 ESC，原因大家估计都懂，我就不讲了。

但后来我用多了发现，还是得及时打断，补充要求再继续；另外，上下文觉得乱掉了，要 /clear 直接清掉。

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710150802.png)

Codex 没有 clear，但 Codex 可以引导上下文。

就是你觉得之前的提示词不完整，再发一条，默认是进入队列，但可以点引导。

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710150915.png)

引导的提示词会进入上一轮对话的上下文。

另外还有一个，就是要多利用 Git，要敢回滚。

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710151156.png)

做 PaiCLI 的时候我遇到过好几次，模型在一个错误方案上越修越偏，每一轮都说“这次应该好了”，每一轮都没好。这种时候止损的方式不是继续对话，是 git 回滚到上一个干净节点，把这一路踩的坑总结成经验写进提示词，重开一个会话从头来。

四个语言版本做下来，我的纠错手感就是这么磨出来的，什么时候打断、什么时候清上下文、什么时候直接掀桌重来。

## ending

要我说，GPT-5.5 和 Opus 4.6 仍然是我心目中的最强拍档。

前者用来编码，后者用来文本。

我沉淀的很多Skills也都是基于这两个来的，但真切到了 GPT-5.6/Fable 5，就产出的结果来看，感觉模型没有稳稳接住我啊。

不知道大家什么感受？

但不管怎么样，这四条编码经验，是永远都不会过时的。

![](https://cdn.paicoding.com/stutymore/gpt56-ai-coding-experience-20260710151729.png)

只是需要在新的模型上重新验证一遍，重新梳理一遍，重新迭代一遍。

我们下期见。
