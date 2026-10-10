---
title: 又一个神级 AI 终端诞生了！让 Claude Code/Codex 用得更爽！
shortTitle: Kaku 终端评测
description: Kaku 是 Pake 作者 tw93 开源的 AI 终端，基于 WezTerm 深度定制，内置命令修复、自然语言转命令和 AI 聊天面板。实测安装配置，并用同一个提示词让 Fable 5 和 GPT-5.6 分屏对决生成千里江山图。
keywords:
  - Kaku 终端
  - AI 终端
  - Claude Code
  - Codex
  - 提示词模板
tag:
  - Agent
category:
  - AI
author: 沉默王二
date: 2026-07-14
---

大家好，我是二哥呀。

有没有想过，一个终端窗口里，左边跑 Claude Code，右边跑 Codex，两个 Agent 同时给我们干活是什么体验？

最近发现一款新的AI终端 Kaku。

![](https://cdn.paicoding.com/stutymore/sucai-20260714113838.png)

用起来真的很不错，推荐给大家试试。

深色模式和浅色模式我都很喜欢。

对于我个人来说，除了要处理一些图片、视频外，我会习惯用 Codex或者Claude 的桌面 Agent。

其余场景，我还是更喜欢在终端里。

Coding 场景我现在几乎已经不review代码了，所以 IDE 这些工具我基本上不碰了。

## 01、安装 Kaku

安装方式两选一，官网下载 DMG 拖进应用程序文件夹，或者一行 Homebrew 命令搞定：

```bash
brew install tw93/tap/kaku
```

Kaku 用 Rust 写的，基于 WezTerm 深度定制，启动秒开。

基于 MIT 协议，完全免费。

安装完真的非常极客风。

![](https://cdn.paicoding.com/stutymore/sucai-20260714110206.png)

按下 enter 键进行配置。

![](https://cdn.paicoding.com/stutymore/sucai-20260714110309.png)

授权安装后，字体、配色、zsh 插件这些全都是预设好的，JetBrains Mono 字体，macOS 原生渲染。

用过 iTerm2 的小伙伴知道，它的强大是靠配置堆出来的，装完之后挑配色、选字体、配 Oh My Zsh。Kaku 的思路反过来，把这些决定提前替我们做了，而且做得有品位。想改也行，`kaku config` 会打开一个配置 TUI，透明度、字体、快捷键都在里面调。

![](https://cdn.paicoding.com/stutymore/sucai-20260714110446.png)

接着输入 `kaku ai` 进入 Codex/Claude Code 的模型配置，认证方式支持 codex 登录。

![](https://cdn.paicoding.com/stutymore/sucai-20260714112049.png)

## 02、Kaku AI

配置完成后，使用快捷键 `command + L` 可以进入 AI 模式。

![](https://cdn.paicoding.com/stutymore/sucai-20260714112203.png)

按下 `shift + tab` 可以快速切换模型。

在输入框键入 `@`，Kaku 会显示：

- @cwd：附加当前项目概况。
- @tab：附加当前终端画面。
- @selection：附加进入 AI 模式前选中的终端文本。

提示词：

```
@cwd 先扫描当前项目。

请完成：
1. 判断项目类型、核心目录和入口文件。
2. 解释主要模块之间的关系。
3. 找出构建、测试和本地启动方式。
4. 列出最值得先阅读的 10 个文件，并说明原因。

本轮只读，不修改任何文件。结论必须基于实际文件和命令结果。
```

![](https://cdn.paicoding.com/stutymore/kaku-terminal-review-20260714122542.png)

或者先在终端选中报错文本，再按 `Cmd + L`：

```
@selection 分析这段错误。

请定位根因，不要只解释错误表面。
检查当前项目中相关配置和源码，给出最小修复方案。
本轮先不要修改文件。
```

如果报错很多，可以改用 `@tab 检查当前终端输出，找出刚才命令失败的根因。`。

Kaku AI 很适合终端报错分析、项目理解、代码审查和中小型 bug 修复。

涉及跨模块大改、长时间自主开发、完整 Git/PR 工作流时，完整 Codex CLI 或 Claude Code 更合适。

除了 AI，它还把终端老炮的必装工具直接内置了：

①、Lazygit，`Cmd+Shift+G` 一键唤起，暂存、提交、分支、rebase 全部可视化操作，不用背 git 命令

②、Yazi 文件管理器，`Cmd+Shift+Y` 打开，同样是 Rust 写的，预览图片和代码都快

![](https://cdn.paicoding.com/stutymore/kaku-terminal-review-20260714122858.png)

③、`z proj` 式的目录跳转，访问过的目录敲个名字片段就能到，不用一层层 cd


④、`Cmd+Opt+I` 广播输入，一次键入，所有分屏同时执行，管多台服务器或者同时指挥多个 Agent 的时候非常爽

![](https://cdn.paicoding.com/stutymore/kaku-terminal-review-20260714123156.png)

⑤、SSH 远程文件挂载，远程机器的文件直接在本地浏览


我把常用的快捷键整理成了一张表格，建议收藏：

| 操作 | 快捷键 |
|------|--------|
| 新建标签 / 左右分屏 | `Cmd+T` / `Cmd+D` |
| AI 聊天面板 | `Cmd+L` |
| 粘贴 AI 修复命令 | `Cmd+Shift+E` |
| Lazygit / Yazi | `Cmd+Shift+G` / `Cmd+Shift+Y` |
| 查找标签和分屏 | `Cmd+Shift+O` |
| 广播输入到所有分屏 | `Cmd+Opt+I` |
| 切换 AI 模型 | `Shift+Tab` |
| 设置面板 | `Cmd+,` |

## 03、Kaku case

`Cmd+D` 左右分屏，左边启动 Claude Code；右边启动 Codex。

两边喂同一个提示词，让当前最强的两个 Agent，在同一个终端里现场对决。

提示词：

```
你是一名资深 Creative Frontend Engineer 和交互设计师。

请在当前目录从零实现一个“可拨动的中国文字珠帘数字文物展览”网站。

参考效果：
- GitHub：https://github.com/aigc17/Chinese-PhoenixCrown
- 在线演示：https://chinese-phoenixcrown.vercel.app

参考项目只用于理解交互原理和视觉方向。请独立实现，不直接复制它的源码、品牌名称、文案或图片资产。

一、总体目标

制作一个全屏沉浸式数字展览：

- 午夜蓝黑色背景，带极轻的纸张或漆面纹理。
- 页面中央展示一件透明背景的中国传统器物 PNG。
- 从器物底部轮廓自然垂落大量由汉字组成的文字珠帘。
- 鼠标或触控指针拂过文字帘时，文字链被拨开、摆动、回弹。
- 指针移动越快，文字摆幅越大。
- 拨动时通过 Web Audio API 生成轻微布料摩擦声和金属风铃声。
- 支持 Scene 单件展览和 Gallery 三件陈列两种视图。
- 切换器物及视图时使用平滑的 shared-element/magic-move 过渡。
```

提示词非常长，限于篇幅，我随后会在技术派上分享完整版本。

![](https://cdn.paicoding.com/stutymore/kaku-terminal-review-20260714123651.png)

Codex 用时 15 分钟。

![](https://cdn.paicoding.com/stutymore/kaku-terminal-review-20260714131819.png)

来看一下最终的效果。

【录屏】

是不是非常的酷。

![](https://cdn.paicoding.com/stutymore/kaku-terminal-review-20260714132128.png)

这里再教大家一个逆向提示词的方法，把你想要的case效果给到 Codex，然后让他帮你反推。

![](https://cdn.paicoding.com/stutymore/kaku-terminal-review-20260714132025.png)

## 04、ending

有一点需要说明，kaku 目前只支持macOS，在GitHub上已有 5.6k star，官方指南在这里：

>https://kaku.fun/docs/guide

![](https://cdn.paicoding.com/stutymore/kaku-terminal-review-20260714133206.png)

以前写代码，主要是 IDE，但随着 Codex 和Claude Code的进化，终端成了替代品。

基本上只要我的电脑不关机，终端就会一直开着。所以很有必要被认真对待一次。

【工具好不好用，不在于他有多让人惊叹，在于用着用着就忘了它的存在，和我们的日常工作融为一体。】

建议喜欢泡在终端里的小伙伴，花 3 分钟装一个试试，合手就留下，不合手就删掉，反正免费。

我们下期见。