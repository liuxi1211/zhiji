---
title: 腾讯一面，我霸气反问：“你说你们在做Agent项目，说说 SubAgent、Plan 模式、Skill 调用这些你们都是怎么做的？”面试官一直在擦汗。。
shortTitle: Agent面试12问
description: 腾讯Agent开发面试12道硬核问题全解，结合PaiCLI源码讲解SubAgent编排、Skill分层、记忆系统、上下文压缩等Agent工程化核心技术。
keywords:
  - Agent面试题
  - PaiCLI
  - Claude Code
  - SubAgent编排
  - Skill系统
tag:
  - 面试
category:
  - AI
author: 沉默王二
date: 2026-07-17
---

不知道大家有没有发现，大模型公司都在卷终端 Agent，包括 Qoder CLI、Kimi Code、ZCode 等等。

更别提Claude Code和Codex CLI了。

我自己也从0到1撸了一个，名叫 PaiCLI，各个版本都有，下面是 Python 版的截图。

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717145401.png)

为的就是能帮大家把 Agent 时代的核心技术栈过一遍：ReAct、Function Calling、RAG、MCP、Multi-Agent、Memory、Context 压缩等等。

>代码完全开源，放在 GitHub 上：https://github.com/itwanger/PaiCLI-Python

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717141236-a1f353fc.png)

同时，我也结合 PaiCLI 的源码，给大家整理了 16 道高频的 Agent面试题，照着背就一定能吊打面试官，😄。

1. 介绍一下 PaiCLI 这个项目和流程？
2. 有实现子 Agent 吗？
3. 支持后台任务吗？
4. 子 Agent 也支持 Plan 模式吗？
5. 子 Agent 是怎么调用 Skill 的？
6. skill分层体系是怎么做的，为什么这么设计？
7. 用户输入怎么和相关skill匹配？
8. 有skill沉淀机制么？还是只能用户自己构造？
9. 长短期记忆怎么设计的？
10. 为什么要静态长期记忆和动态长期记忆？
11. 什么时候触发长期记忆存储，有没有出现用户长期记忆快速积累，存的过多？
12. 大模型怎么决定长期记忆是否需要召回？
13. 压缩机制是怎么做的？上下文窗口总token多大？触发上限为什么选这个值？
14. 讲一下动态prompt和静态prompt？
15. 模型底座是哪个？例如写一千行代码，需要消耗多少token?成本是多少？你用的百万token计费是多少？
16. 你平时用你的PaiCLI么？

（全文比较肝，保证大家能学到很多很多，系好安全带，我们粗粗发～）

## content

### 01、介绍一下 PaiCLI 这个项目和流程

老王开门见山：“你简历上写了一个 PaiCLI 项目，对标 Claude Code？先介绍一下。”

我说：“PaiCLI 是一个 Python 写的 Agent 命令行工具，核心架构是 ReAct 循环。”

用户在终端输入一个任务，PaiCLI 把任务发给大模型，大模型决定要不要调用工具、调哪个、参数是什么。

工具执行完后把结果返回给大模型，大模型再决定下一步动作。直到大模型认为任务完成，返回最终答案。

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717141548-9eb77145.png)

“PaiCLI 有三种工作模式。”

默认是 ReAct 模式，适合日常的单步任务，改个文件、跑个命令这种。

执行 /plan 会进入 Plan-and-Execute 模式，先把复杂任务拆成多个步骤再逐步执行。

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717151243.png)

/team 会进入 Multi-Agent 模式，多个 Agent 分工协作。

“工具层面，内置了 9 个核心工具：文件读写、命令执行、代码搜索、目录浏览、web 搜索。通过 MCP 协议还可以接入外部工具，比如浏览器操作、数据库查询这些。”

### 02、有实现 Sub-agent 吗？怎么编排的？

老王追问：“多 Agent 场景下你是怎么编排的？”

“规划者（Planner）接收用户任务，拆解成一组带依赖关系的执行步骤。”

“比如用户说‘重构这个模块并写测试’，规划者会拆成：步骤 1 分析现有代码结构，步骤 2 执行重构（依赖步骤 1），步骤 3 写单元测试（依赖步骤 2），步骤 4 运行测试验证（依赖步骤 3）。”

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717141721-dc6bb70c.png)

“这些步骤按 DAG（有向无环图）组织。编排器做拓扑排序，找出所有依赖已满足的步骤，丢给执行者（Worker）并行跑。”

执行者工作池默认 2 个，用 asyncio.Queue 管理，谁空闲谁接活，避免任务堆在一个执行者身上。

“每个步骤执行完，检查者（Reviewer）会审查结果，输出一个 JSON 结构，包含是否通过、问题列表、摘要。”

审查不通过的步骤会带着检查者的反馈重新执行，最多重试 2 次。

如果整体进度不到 50% 就连续失败，编排器会触发重新规划，让规划者换一种拆解方式。

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717142006-0ee7c154.png)

老王点点头：“执行者之间的隔离怎么做的？”

“每个执行者有独立的消息历史和 Skill 上下文缓冲区。并行执行时，输出各自写入独立的字节缓冲区，全部完成后再按原始顺序合并到终端，保证展示不会乱序。”

### 03、支持后台任务吗？

老王问：“前台跑 Agent 的时候能同时跑后台任务吗？”

“支持。后台任务用 SQLite 做持久化队列。”

“状态机是 queued → running → completed 或 failed 或 canceled。”

Worker 认领任务后，会通过心跳续期。如果 Worker 崩溃了，当前周期过期后其他 Worker 可以重新认领这个任务。

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717142136-4bbdf520.png)

“启动时还有崩溃恢复：扫描所有 running 状态但租约已过期的任务，重置为 queued 重新排队。这样即使进程异常退出，未完成的任务也不会丢。”

### 04、Sub-agent 也支持 Plan 模式吗？Skill 怎么调用？

老王问：“Multi-Agent 里的执行者，能不能走 Plan 模式？”

我说：“可以。每个执行步骤有一个 mode 字段，值可以是 react 或 plan。”

规划者在拆解任务的时候可以判断某个步骤是否足够复杂，需要用 Plan 模式来执行。

“Plan 模式的流程是：先调规划者生成一份 JSON 格式的执行计划，每个步骤有 id、描述、类型和依赖列表。”

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717151357.png)

有一个简单的任务检测，如果用户输入不超过 30 个字，且不包含‘然后’‘并且’‘再’这些多步骤线索词，就跳过 LLM 规划，直接生成一个单步计划，省掉一次 LLM 调用。

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717142411-fe739448.png)

“计划经过拓扑排序确定执行顺序。没有依赖关系的步骤通过 asyncio.gather 并行执行，有依赖的串行等待。”

#### Sub-agent 是怎么调用 Skill 的？

老王紧跟着追问：“Skill 在 Sub-agent 里怎么工作？”

“系统提示词里注入了一份 Skill 索引，包含所有启用 Skill 的名称和描述，最多 20 个，总大小不超过 4KB。”

LLM 在处理任务时，如果判断当前任务和某个 Skill 的描述匹配，就会主动调用 load_skill 这个内置工具。

“加载后，Skill 的完整内容会写入一个上下文缓冲区。下一轮 LLM 请求时，这段内容自动注入到用户消息的前面。LLM 就相当于拿到了一份专家手册，按手册指引来执行任务。”

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717142545-0948a183.png)

“有个关键设计：每个 Sub-agent 有独立的 Skill 缓冲区，用 LRU 策略最多缓存 3 个 Skill。”

因为 Skill 内容注入后会从缓冲区清空（drain 操作），如果多个 Sub-agent 共享同一个缓冲区，一个 Worker 的 drain 会把另一个 Worker 还没读到的内容清掉。

### 05、Skill 分层体系是怎么做的，为什么这么设计？

老王说：“Skill 系统展开说说。”

我说：“三层加载，按优先级从低到高：内置 Skill 打包在程序里、用户级 Skill 放在 ~/.paicli/skills/ 目录、项目级 Skill 放在项目根目录的 .paicli/skills/ 目录。同名 Skill，优先级高的整体覆盖低的。”

“每个 Skill 就是一个目录，核心是 SKILL.md 文件，用 frontmatter 声明名称、描述、版本、标签，正文就是给 LLM 看的决策手册。可选的 references/ 目录放参考资料，scripts/ 放可执行脚本。”

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717142707-ff6ac79f.png)

“为什么这么分？”

内置 Skill 提供开箱即用的基础能力，比如 web 访问的浏览器策略手册。

用户级满足个人工作流定制，比如我自己写了一个小红书热榜抓取的 Skill。

项目级承载团队约定，比如代码审查规范，提交到仓库后团队所有人共享。

这个思路和 Claude Code 的设计一脉相承，Claude Code 也是内置 Skill、用户 Skill、项目 Skill 三层。

### 06、用户输入怎么和 Skill 匹配？有积累机制吗？

老王追问：“用户输入一段话，怎么知道该加载哪个 Skill？”

“打分制。每个 Skill 的得分由四个维度加权计算。精确名称匹配直接加 10000 分，确保用户点名要的 Skill 一定排第一。名称中的词项命中权重 ×12，标签词项 ×6，描述词项 ×2。”

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717143013-6dc20751.png)

“分词策略分中英文两套。”

英文转小写后按空格分词，过滤掉 and、for、the 这些停用词。

“中文不做传统分词，而是取单个汉字加上 2 字和 3 字的滑动窗口，这样能覆盖大部分中文短语。比如‘代码审查’会生成‘代’‘码’‘审’‘查’‘代码’‘码审’‘审查’‘代码审’‘码审查’这些特征。”

老王又问：“Skill 只能开发者预定义吗？还是用户也可以自己写？”

“用户可以在 ~/.paicli/skills/ 下创建自己的 Skill 目录，写一个 SKILL.md 就能生效。”

启用状态持久化在 skills.json 里，记录的是 disabled 列表，默认全部启用，用 /skill off 命令禁用。

“目前没做自动积累，比如根据用户操作习惯自动生成 Skill。主要是自动生成的决策手册质量不可控，一份写得不好的手册反而会误导 LLM 的判断。”

---

**简历亮点**

如果大家在简历上写 Agent 相关的项目，可以参考下面这种方式来包装（别忘了去 GitHub 上 star 一下 PaiCLI 哈～）：

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717143146-5eafe1ba.png)

项目名称：PaiCLI

项目简介：对标 Claude Code 的 Python Agent 命令行工具，支持多模型切换、多 Agent 协作、Skill 系统和记忆管理

技术栈：Python 3.11、asyncio、httpx、SQLite、prompt-toolkit、Rich、MCP

核心职责：

- 基于 ReAct 实现了多模型适配的 Agent 引擎，支持 DeepSeek、GLM、Kimi 等 6 种大模型 provider 的流式调用和运行时切换
- 设计并实现了 Multi-Agent 编排系统（规划者/执行者/检查者），通过 DAG 拓扑排序和 asyncio 异步队列实现任务依赖的并行调度
- 构建了三层 Skill 加载体系（内置/用户/项目），基于多维度加权评分算法实现用户意图到 Skill 的自动路由
- 设计了双轨长期记忆系统（静态项目记忆 + 动态 SQLite 记忆），配合 SHA256 去重和 FIFO 淘汰策略控制记忆膨胀
- 实现了基于滚动摘要的上下文压缩机制，在 80% 窗口阈值自动触发压缩，保留近 6 轮对话完整性，支持百万 token 级长对话

---

### 07、长短期记忆怎么设计的？

老王换了个方向：“Agent 的记忆系统怎么做的？”

我说：“三层。”

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717143321-74f77e12.png)

“短期记忆就是当前对话的消息历史。用户说了什么、LLM 回了什么、工具返回了什么，每轮 LLM 调用都带上。本质是一个不断增长的消息列表。”

“长期记忆用 SQLite 存储。”

每条记忆绑定了 scope（项目路径，实现项目隔离）、content（记忆内容）、importance（重要性，0 到 1）、confidence（置信度，0 到 1）、access_count（被召回的次数）、content_hash（SHA256 哈希，用于去重）。

“长期静态记忆就是 PAI.md 文件。项目根目录或 .paicli 目录下放一个 PAI.md，启动时自动加载到系统提示词里。功能类似 Claude Code 的 CLAUDE.md。”

### 08、为什么要分静态长期记忆和动态长期记忆？

老王追问：“为什么不统一成一种？”

“用途不同。”

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717143606-bd620fbe.png)

“静态记忆存的是团队规范和项目约定，比如代码风格、分支策略、部署流程。”

变化频率低，可以提交到代码仓库，团队所有成员共享同一份。PAI.md 还支持 @filename 导入其他文件，最大嵌套 3 层，总预算 16KB，避免撑爆上下文。”

“动态记忆存的是用户特定的事实和偏好，比如‘这个项目用的是 PostgreSQL 而不是 MySQL’。这些信息在交互过程中学习积累，按项目隔离存储在 SQLite 里。”

“分开存的好处是职责清晰：静态记忆由开发者维护，走版本控制；动态记忆由 Agent 自动管理，不会污染代码仓库。”

两者在 Prompt 组装时合并注入系统提示词，静态的先加载、动态的后加载。

### 09、什么时候触发记忆存储？会不会越存越多？

老王问：“长期记忆是怎么触发存储的？膨胀怎么控制？”

“触发有两种方式。用户执行 /save 命令主动存储，或者对话中说‘记住这个’‘帮我记一下’，LLM 会调用 save_memory 这个内置工具自动存。”

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717143747-df4313be.png)

“去重用 content_hash。把记忆内容做 Unicode 归一化后算 SHA256，如果哈希已存在，不创建新记录，只把 importance 和 confidence 取两者中的较大值更新上去。”

“防膨胀靠配额淘汰。设定一个 max_entries 上限，超出时按 importance、confidence、access_count、updated_at 综合排序，优先淘汰那些重要性低、置信度低、长时间没被召回过的旧记忆。”

#### 大模型怎么决定长期记忆是否需要召回？

老王紧跟着追问：“每次对话都把所有记忆塞进上下文吗？”

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717144107-abf10212.png)

“不是，按相关性评分召回。每次 LLM 请求前，用当前的用户消息作为查询，对所有记忆逐条打分，取分数最高的 6 条注入上下文。”

“评分公式是多维度加权。”

词汇覆盖率占 72%，把查询和记忆内容各自提取词项特征，算交集占比，加上子串完全匹配的额外加成。

重要性占 12%，置信度占 8%，时间衰减占 6%（半衰期 30 天，一条记忆 30 天没更新过，时间分就衰减到一半），访问频率占 2%（用对数函数平滑，避免高频访问的记忆垄断排名）。

“召回阈值设定为 0.05。这个值很低，因为宁可多召回几条不太相关的，也不要漏掉真正有用的。LLM 自己能判断哪些记忆和当前任务有关，不需要评分系统做太严格的过滤。”

### 10、上下文压缩机制是怎么做的？

老王问：“对话太长，上下文窗口装不下怎么办？”

我说：“自动压缩。触发条件满足任一个就启动：估算的 token 数超过可用输入 token 的 80%，或者消息数超过 100 条。”

“可用输入 token 的计算方式：上下文窗口总大小减去最大输出 token 数，再减去 1024 的预留空间。比如模型窗口 128K，最大输出 8K，可用输入约 119K，80% 触发阈值约 95K。”

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717144235-c01c5a39.png)

“压缩算法分三步。”

- 第一步，保留最近 6 轮对话不动，切割点必须落在用户消息的边界上。因为工具调用和工具结果必须成对出现在消息历史里，从中间切断会破坏消息协议。
- 第二步，保留范围之外的旧消息做滚动摘要，每条消息提取最多 500 字的摘要。
- 第三步，超长的工具返回结果截断到 4000 字。

“压缩目标是可用 token 的 55%。为什么 80% 触发、55% 压缩？留出来的空间给当前轮的输入、系统提示词里动态注入的部分（比如刚召回的记忆），以及 LLM 可能返回的多个工具调用。压缩得太晚太少，当前轮的请求可能直接超窗口。”

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717144431-482b48f8.png)

“token 估算用经验公式：中文字符按 1 token/字，英文按 3 个字符 1 个 token。偏保守，代码多的场景会高估。但高估比低估安全，低估可能导致请求超出模型窗口被截断。”

### 11、讲一下动态 Prompt 和静态 Prompt

老王说：“你的系统提示词是怎么组装的？”

“分成静态部分和动态部分。”

“静态部分在一个会话里不会变，构建一次就缓存。包括人格设定（语气风格）、核心行为准则（工具使用规范、安全策略）、项目指令（PAI.md 的内容）。”

“动态部分每次 LLM 请求都重新构建。包括当前时间和时区、工作目录路径、当前使用的模型名称和 provider、已启用的 Skill 索引、刚召回的相关长期记忆。”

“还有一个模式维度。ReAct 模式、Plan 模式、规划者/执行者/检查者各有独立的模式提示词文件，存在 resources/prompts/ 目录下。编排器根据当前角色选择对应的模式提示词，拼到系统提示词的对应位置。”

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717144721-215aa276.png)

“项目指令的加载有优先级链：用户全局的 PAI.md → 项目根目录的 PAI.md → .paicli 目录下的 PAI.md → PAI.local.md（本地覆盖，不提交仓库）。”

总预算 16KB，超出截断。用户还可以自定义覆盖任意一层的提示词文件，覆盖是整文件替换。

### 12、模型底座是哪个？成本怎么样？你平时用 PaiCLI 吗？

老王抛出最后的问题：“你用的什么模型？成本算过吗？”

“默认模型是 DeepSeek V4。”

PaiCLI 支持运行时切换模型，/model 命令可以在 DeepSeek、GLM、Kimi、StepFun 等 6 种 provider 之间切换，API Key 从配置文件、环境变量、.env 文件三个来源读取，优先级依次升高。

“1000 行代码大约 15000 到 20000 个 token。一次完整的编码任务，算上多轮 ReAct 循环，通常消耗 5 万到 10 万 token。”

用 DeepSeek V4 的话，成本大约 0.1 到 1 元之间。启用 Prompt Cache 后，重复的系统提示词和历史消息命中缓存，输入成本降到四分之一。

![](https://cdn.paicoding.com/stutymore/paicli-agent-mianshi-20260717145017-023ce7cb.png)

老王最后问：“你平时用你自己的 PaiCLI 吗？”

“用，每天都在用。写代码、查资料、自动化日常操作都用它。”

“有一说一，Claude Code 功能肯定比 PaiCLI 强。但自己写的工具，最大的好处不是功能，而是你完全理解每一个设计决策背后的取舍。”

“而且在写 PaiCLI 的过程中，我把 Agent 技术栈的核心概念全过了一遍。ReAct、Function Calling、RAG、MCP、Multi-Agent、Memory、Context 压缩。看文章看十遍不如自己动手实现一遍。”

## ending

Agent 面试考的不是能背多少概念，是有没有自己做过一遍完整的技术栈。

想要搞清楚 Agent，最有效的方式就是自己写一个 Agent 项目。

不需要做得比 Claude Code 好，但 ReAct 循环、记忆系统、上下文压缩、Skill 分层这些核心模块，每个都要自己实现一遍。

下期见。
