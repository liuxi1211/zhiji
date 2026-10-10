---
title: 推荐10个高质量的Codex视频
shortTitle: 10个Codex视频推荐
description: 从B站上百个Codex视频中精选10个，覆盖入门教程、实战案例、进阶功能、国内接入、知识管理和行业格局，附评论区高赞避坑经验。
keywords:
  - Codex
  - Codex 教程
  - Codex 视频
  - AI 编程
  - Codex MCP
tag:
  - Agent
category:
  - AI
author: 沉默王二
date: 2026-07-02
---

大家好，我是二哥呀。

给大家整理了 10 个 B 站上值得严肃学习的 Codex 视频。

并且按照学习路径排好了序。

主要的目的就是降低大家信息筛选的成本。

![](https://cdn.paicoding.com/stutymore/codex-bilibili-top10-20260702164159.png)

我个人觉得，工具这玩意，最重要的还是用，你用得越多，就越熟练，Codex 也不例外。

并且也没要说追求太多Skill、MCP、插件这些额外的能力，够用就好。

我平常用Codex就是写代码，生图，做一些自动化，比如说将我的markdown上传到飞书，将我的代码提交到GitHub。Codex比Claude Code好的一点就是，结实。

基本上随便折腾，心里没有一点担惊受怕的。

## 01、入门篇

先推荐三个入门级教程。这三个视频覆盖了 Codex 的全部基础功能，风格各有侧重，挑一个跟着做就能上手。

第一个是**技术爬爬虾**的《Codex APP 保姆级全攻略》。

![](https://cdn.paicoding.com/stutymore/codex-bilibili-top10-20260702112537.png)

> https://www.bilibili.com/video/BV1Kk9kBAEJv/

技术爬爬虾是 B 站 AI 编程领域产出密度最高的创作者之一。

这期视频覆盖了 12 个功能模块，从安装到 MCP 到电脑自动化，按使用频率排列，适合当手册反复翻。

温馨提醒，刚开始用 Codex 的小伙伴，建议先把这段内容贴到 AGENTS.md。

```markdown
禁止批量删除文件或目录。
不要使用：

- `del /s`
- `rd /s`
- `rmdir /s`
- `Remove-Item -Recurse`
- `rm -rf`

需要删除文件时，只能一次删除一个明确路径的文件。
```

第二个是**秋芝 2046**的《全网最全！40 分钟全面掌握 Codex》。这个视频覆盖了 10 个实战场景，每个场景都会等操作跑完再往下讲，适合跟着同步操作。

![](https://cdn.paicoding.com/stutymore/codex-bilibili-top10-20260702113242.png)

> https://www.bilibili.com/video/BV1Nd596vEyU/

评论区有热心网友总结了 5 条使用心得，信息密度很高。

- 20 刀的额度现在缩水严重，5 小时额度大概半小时就用完，说人话，就是建议 100 刀起步
- 中转站速度比官方慢，但确实便宜（一直没想明白为什么会比官方便宜）
- 用 image2 生成图片要明确指定数量，否则默认只给 1 张
- 同时开 5 个以上编程任务会一直卡在打字状态

第三个是**枫枫知道**的《Codex 新手保姆级教程》。枫枫知道是全栈开发方向的博主，所有课程文档都同步发布在博客上。

![](https://cdn.paicoding.com/stutymore/codex-bilibili-top10-20260702113610.png)

这是三个入门视频里最长的一个，接近 2 小时，覆盖了从安装配置到项目实战的完整流程。

## 02、实操篇

**PAPAYA**的《一个 AI 助手打趴整套 Office 软体》。

PAPAYA 是 IT 教学博主，以办公效率工具教学著称，讲话节奏缓、表达清晰，即使完全没用过 Codex 也能跟上。

![](https://cdn.paicoding.com/stutymore/codex-bilibili-top10-20260702113827.png)

这个视频的价值在于它用“举办校庆活动”这个完整业务场景把 Codex 的核心功能串了起来。

设定 AGENTS.md、管理文件、建立 Skills、安装 Plugin、生成邀请函图片、合并列印、发邮件、做 HTML 仪表板、设定自动化排程、建网站部署到 Netlify。

对于非程序员背景的用户，学习效率更高，因为每个功能都绑定了具体的使用场景，不用自己想“这个功能我什么时候能用到”。

## 03、进阶篇

想继续深入，这四个视频分别覆盖高级功能、第三方模型接入、MCP 生态和完整开发流程。

**马克的技术工作坊**的《Codex 从 0 到 1 全攻略》。

![](https://cdn.paicoding.com/stutymore/codex-bilibili-top10-20260702114216.png)

内容覆盖了 Codex 的全部进阶功能，包括 Annotate（标注对话节点）、Fork（分支对话）、Archive（归档会话）、Plan（计划模式）、Plugin（插件系统）、Skill（技能包）。

举个例子，Fork 功能很多人只知道“能分支对话”，但什么时候该用 Fork、分支之后怎么合并结果、Fork 和新建会话的区别是什么，马克在视频里用具体的编程场景演示了。

Plan 模式也是，不是简单地“让 Codex 先做计划再执行”，而是演示了怎么通过 Plan 让 Codex 在复杂项目中保持方向感，避免改着改着偏离目标。

第二个进阶视频是**枫枫知道**的《CC Switch 王炸更新》。

CC Switch 是目前让 Codex 接入第三方模型的主流方案。这个视频是我找到的最全的 CC Switch 教程，从安装讲到 DeepSeek 接入、中转站接入、GPT 账号接入，还覆盖了应用增强、统一会话历史和解锁 Codex 插件。

第三个是**技术爬爬虾**的《用过上百款编程 MCP，只有这 15 个真正好用》。这个视频不只讲 Codex，而是用 Claude Code 和 Codex 分别实战演示了 15 个编程 MCP，包括 Chrome DevTools（浏览器调试）、Neon（Serverless PostgreSQL）、Supabase（一体化后端）、Figma（设计稿转代码）等。

第四个是**技术胖**的《Codex 开发程序 一次全部讲明白》。

> https://www.bilibili.com/video/BV15zjF6eEji/

![](https://cdn.paicoding.com/stutymore/codex-bilibili-top10-20260702120848.png)

技术胖是 B 站老牌程序员博主，做开发教学很多年了。这个视频从项目结构搭建到 CodeGraph 集成、多语言开发、测试和部署，把用 Codex 做真实开发的完整流程走了一遍。

## 04、破局篇

**林粒粒呀**的《小白速通 Codex 安装 + 国产大模型接入》。

![](https://cdn.paicoding.com/stutymore/codex-bilibili-top10-20260702114632.png)

> https://www.bilibili.com/video/BV1aREu6wEk9/

林粒粒呀擅长把复杂的配置过程压缩到 10 分钟以内讲清楚，这个视频就告诉你一件事，无需 ChatGPT 订阅，国内直连 DeepSeek。

用 CC Switch 的小伙伴可能会遇到：

- 代理返回 502 Bad Gateway
- 模型列表为空（没有可选模型）
- CC Switch 配置完成但 Codex 无响应。

大部分问题的根源集中在两个方面，一是 CC Switch 和 Codex 之间的模型名称不一致，二是本地代理端口没有正确启动。

Codex 不只是写代码的工具。

**Xuan\_酱**的《Codex 联动 Obsidian，搭建卡帕西同款知识库》。

![](https://cdn.paicoding.com/stutymore/codex-bilibili-top10-20260702115032.png)

Andrej Karpathy 之前分享过自己用 AI 搭建自动化知识库的思路，Xuan\_酱这个视频是目前 B 站上最完整的落地实现。

Codex 负责执行自动化任务（抓取、整理、定时触发），Obsidian 负责存储和展示知识体系。

两者通过 Codex 的 Plugin 和定时任务机制联动，实现了定时抓取热点、自动归类、定期复盘、输出文档和 PPT 的完整流程。

---

如果你想要文档类型，可以看看 AI 进阶之路。

> https://ai.javabetter.cn

![](https://cdn.paicoding.com/stutymore/codex-bilibili-top10-20260702115402.png)

如果你正在琢磨怎么上手 Codex，不妨看看上面的这些视频，跟着动手实践一下，比啥都管用。
