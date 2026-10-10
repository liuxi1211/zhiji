---
title: Hy3 + WorkBuddy = 国产顶级 Agent（附完整提示词）。
shortTitle: Hy3 + WorkBuddy 实测
description: 腾讯混元 Hy3 正式版发布，用 WorkBuddy 实测工具使用、规划、长程执行、多源理解四项 Agent 能力，附完整提示词和官方数据。
keywords:
  - 混元 Hy3
  - WorkBuddy
  - 腾讯混元
  - Agent 测评
  - 国产大模型
tag:
  - Agent
category:
  - AI
author: 沉默王二
date: 2026-07-06
---

大家好，我是二哥呀。

评论区一直有小伙伴喊测评一下 WorkBuddy，那今天来了，带着鹅厂最新发布的混元 Hy3 模型。

我相信，伴随着混元 Hy3 正式版的发布，WorkBuddy 也将成为更多小伙伴首选的桌面 Agent。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706180248.png)

先说参数。

Hy3 是一个快慢思考融合的混合专家模型（MoE），总参数 295B，激活参数 21B，最大支持 256K 上下文。

内部有 192 个路由专家，每次激活 top-8，外加 1 个常驻的共享专家。推理力度分 no_think、low、high 三档，可以按任务复杂度决定要不要展开深度思考。

我们直接来小试牛刀一下，用 WorkBuddy + Hy3 生成一张关于 MoE 的海报图。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706181807.png)

提示词非常简单。

```
@视觉海报 设计一张混元Hy3发布的海报，参数信息在这里：https://mp.weixin.qq.com/s/X2x1GF09bFbTzc3M1981BQ?scene=1&click_id=1351723181
```

打开 WorkBuddy，选择 Hy3 模型，技能选择视觉海报，输入提示词。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706181933.png)

WorkBuddy 会先读取微信公众号的内容，提取关键信息，比如说幻觉率 12.5%→5.4%、任务解决率 72%→90%、耗时 -34%、Token 效率文档 -47.4%/PPT -49.0% 等等。

然后准备画布、开始设计。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706182332.png)

期间会进行必要的深度思考，工具调用等。

绘图完成后，还会主动校验，修复视觉上的错误。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706182454.png)

最后落盘。

我直接从画布区域复制粘贴过来，大家可以看到，海报里所有的参数信息都正确无误。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706182554.png)

对了，Hy3 正式版上架后，有两周的免费额度，需要海量Token的小伙伴一定要把握住机会啊。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706182745.png)

就我个人的使用体验来看，Hy3和WorkBuddy的适配度非常高，几个case 测下来，代码能力和 Deepseek V4 Pro 非常接近。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706182838.png)

我设计了五个 case，覆盖前端规划、工具使用、长程执行、复杂推理四个能力维度。

前三条对应 Coding Agent 升级，后两条对应 Working Agent 的办公主场。

每个 case 的提示词我都原样贴出来，大家可以在 WorkBuddy 里一字不改地复现。

## 01、新粗野主义的前端

第一个 case 测规划和前端。前端也是 Hy3 这次升级的重点。

我给了 WorkBuddy 一段 Neo-Brutalism（新粗野主义）风格的设计提示词，让它从零生成一个完整的单页网站。这个风格最近在设计圈很火，特点是粗描边、硬阴影、高饱和糖果色块，看起来像彩色积木拼出来的界面。

提示词如下。

```
设计一个单页网站，风格走 Neo-Brutalism（新粗野主义）。

色彩：用高饱和的糖果色做主色块——柠檬黄 #FFE156、荧光粉 #FF6B9D、电光蓝 #4ECDC4、薄荷绿 #95E77E，背景用奶白 #FFF8F0 或浅灰打底。每个功能区域一个主色，色块之间硬切，不要渐变过渡。

边框与阴影：所有卡片和按钮加 3px 黑色实线描边；阴影用纯黑色块，向右下方偏移 6px，不做模糊，像是积木投下的硬影子。hover 时阴影缩短到 2px，模拟按下去的手感。

字体：标题用 900 字重的无衬线体，正文用等宽字体。标题可以故意倾斜 -2° 到 3°，制造手贴标签的随意感。

布局：用不对称网格，卡片大小不一，有的占两列有的只占一格。允许元素之间轻微重叠（比如一个贴纸压在两个色块的交界处）。圆角和直角混用——大色块用 16px 圆角，小标签用 0 圆角。

质感细节：至少一个区域的背景加细密噪点纹理；用 2-3 个手绘风格的装饰符号（星号、箭头、波浪线），用 SVG 内联，颜色跟随所在区域的主色。

整体气质：像一个用彩色胶带和马克笔拼出来的杂志版面——张扬、有态度、但信息层级清晰。不要精致感，要粗粝的生命力。
```

这段提示词里的约束密度很高：4 个指定色值、描边粗细和阴影偏移量精确到像素、hover 交互有具体参数、圆角混用规则、SVG 内联要求。能不能把这些视觉规范一次性吃进去统一出图，直接反映模型的需求理解和规划能力。


![](https://files.mdnice.com/user/3903/1f4305dd-f0ac-4a3b-bd6d-40bb4f0b30eb.png)


![](https://files.mdnice.com/user/3903/bb67cf68-45a5-46ff-a6db-5d5ee3d3588b.png)



## 02、让 Hy3 画一张动态名片

第二个 case 还是测前端。

我的想法是让 Hy3 画它自己——用网页动画演示 MoE 模型的 token 路由过程。这个题目有点损，模型得先真正理解自己的架构，才能把它画对。

提示词如下。

```
帮我做一个单页网页，主题是「Hy3 的自画像」，用动画演示 MoE 模型的 token 路由过程。
要求，页面顶部放三张数字卡片，分别是 295B 总参数、21B 激活参数、256K 上下文；
中间是路由动画，一个 token 流进来，192 个专家里点亮 8 个，共享专家常亮；底部放一个滑块，拖动可以调整每秒流入的 token 数量，动画速度随之变化；
暗色背景，腾讯蓝配色；
纯 HTML、CSS、JS 单文件实现，不允许引入外部库。
```

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706185822.png)

这段提示词里有五个约束，数字卡片、专家点亮逻辑、滑块联动、配色、单文件。

约束越多，越能看出模型是通读需求后统一规划，还是看一句做一句。

为方便演示，我让 WorkBuddy 生成了 GIF 动图。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-hy3-self-portrait.gif)

并且针对微信公众号对GIF的要求特意做了帧数处理。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706191455.png)

## 03、让Hy3核对自己的数据

第三个 case 测工具使用，重点是浏览器导航和信息提取精度。

```
打开三个页面，GitHub 的 Tencent-Hunyuan/Hy3 仓库、HuggingFace 的 tencent/Hy3 模型卡、OpenRouter 的 Hy3 页面。
把三个来源里关于上下文长度、开源协议、定价的信息分别提取出来，做成一张对比表。如果三个来源的数据对不上，单独列出冲突项并标明出处。不确定的信息直接写「未找到」，不要猜。
```

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706190854.png)

这个 case 的巧妙之处在于我提前知道答案。三个来源的数据本来就有出入，比如 OpenRouter，Hy3 其实就没有上架。

能看得出来，WorkBuddy 会打开三个网页，然后提取关键信息，做成一张对比表。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706191031.png)

并且会明确告诉我们：

- GitHub 与 HuggingFace 的全量 Hy3 数据一致——上下文 256K、协议 Apache 2.0
- OpenRouter 全量 Hy3 页面不存在，仅有 Hy3 preview（262K）

## 04、一句话出 Excel 加 PPT

第四个 case 测长程执行，走 Working Agent 的主场。

```
这个文件夹里是我博客 AI 专栏的全部 Markdown 文章，每篇的开头有标题、日期、标签。
第一步，整理成一张 Excel，列包括标题、发布日期、分类、正文字数；第二步，统计每个月的发文数量和各分类占比，生成图表；第三步，基于分类结果产出一份 10 页以内的《AI Agent 学习路线》PPT，最后一页放数据统计图。
全部完成后，自查一遍 Excel 里的数据和原文件是否一致，再交给我。
```

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706191718.png)

三步任务，跨文件读取、表格、图表、PPT 四种工具，最后还要求自检。

长程执行的关键不是每一步都惊艳，是后面的步骤要记得之前步骤的数据，交付前能自己发现错漏。

这类任务我拿别的 Agent 工具跑过不止一次，最常见的翻车点有两个。

一是字数统计，模型懒得真去数，直接估一个差不多的数字填进去；二是 PPT 里的统计图和 Excel 里的原始数据对不上，两边各算各的。

所以提示词最后那句「自查一遍再交给我」不是客套话。

据说 Hy3 可以稳定支撑近 500 步的 Agent 工作流，真的很强很强了。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706193402.png)

来看一下最终的产物。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706193922.png)

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706194038.png)

为了生成更美观的PPT，我们这里装一下臧师傅的 PPT Skill。

```
装一下这个https://github.com/op7418/guizang-ppt-skill
```

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706194357.png)

好，我们来生成Swiss Style的 PPT。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706194427.png)

这次任务量可不小，中间还做了几次上下文压缩。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706195628.png)

OK，任务搞定，我们来看看效果。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706195919.png)

我是真没想到，3月份我竟然发表了 45 篇内容。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706200142.png)

长程执行考验的不只是单步能力，更是上下文保持。

Hy3 在这个 case 里全程保持了数据一致性。

这其实就是 Working Agent 的典型任务：先读一堆长文本，再抽结构化数据，最后跨 Excel、图表、PPT 多个工具交付结果。

## 05、让 Hy3 来一道复杂推理

最后一个 case 测复杂推理。

Agentic Reasoning 是 Hy3 正式版的第三大升级方向，我们就来出一道需要数学推导 + 编码 + 执行 + 可视化的串联题。

题目是经典的错排问题。

```
100 个人排成一排，每人头上有一顶编号帽子，帽子被打乱随机分配。至少有 1 个人拿到自己编号帽子的概率是多少？
要求：
1. 先给出数学精确解的推导过程
2. 用 Python 写蒙特卡洛模拟，跑 100 万次
3. 画一张收敛曲线图，横轴是模拟次数，纵轴是概率估计值，同时标注数学精确解的水平线
4. 对比模拟结果和精确解的误差
```


![](https://files.mdnice.com/user/3903/28fdc95f-f31b-471b-bf89-48d84cc02d6f.png)


为什么选这道题？

因为它同时考察了 Hy3 正式版重点升级的两个方向：深度推理（容斥原理的推导不是一步就能跳到答案的，需要多步逻辑推演）和工具使用（Python 编码 + 执行 + Matplotlib 画图）。

五个环节环环相扣：问题理解 → 数学推导 → 代码编写 → 执行模拟 → 可视化对比。

先看数学推导。


![](https://files.mdnice.com/user/3903/e6f8cdca-c7af-48de-8105-ff00dc309456.png)


重点看 Hy3 的推导过程完不完整。

有没有写出容斥公式？有没有说明为什么 n 足够大时可以用 1/e 近似？最终数值有没有算对？如果直接跳到“答案大约是 0.63”但没有推导过程，那不叫推理。

最终验证：模拟 100 万次后的概率估计值和数学精确解 0.63212 的误差应该在千分位级别（±0.001 以内）。


![](https://files.mdnice.com/user/3903/71116418-10a3-4afc-a565-cfabb5fe574e.png)

顺便说一下这个能力在日常工作里的用法。

数据分析、量化建模、A/B 实验结果验证，都需要“先推公式再跑数据再画图”的串联能力。

## ending

五个 case 跑完，说说整体感受。

工具使用看能不能自己开网页、找信息、标出处；规划能力看能不能把多约束需求拆成可执行步骤；长程执行看跨多步任务时会不会丢状态；复杂推理看能不能先推导、再写代码、再执行验证。

WorkBuddy+Hy3的表现完全符合预期。

![](https://cdn.paicoding.com/stutymore/hunyuan-hy3-workbuddy-20260706211039.png)

并且 WorkBuddy 里的 Hy3 现在免费试用两周，大家也可以抓住这个窗口期跑一些自己之前想做但一直没有做过的任务。

我们下期见。

