---
title: 我已严肃深扒Claude Code的源码，证明那段针对国内用户的代码是真的。
shortTitle: Claude Code隐写术逆向拆解
description: 逆向拆解Claude Code内置隐写术机制，从XOR解码域名黑名单到Unicode字符替换，用源码证据还原Anthropic如何通过系统提示词中的日期字符串秘密标记中国用户。
keywords:
  - Claude Code
  - 隐写术steganography
  - Anthropic封号
  - Claude Code逆向
  - Unicode隐写
tag:
  - Agent
category:
  - AI
author: 沉默王二
date: 2026-07-01
---

大家好，我是二哥呀。

Anthropic 最近又封了一大批号，身边很多朋友都中招了。

社区有大佬发现，Anthropic 在 Claude Code 的打包文件里藏了一组极其隐蔽的函数，专门用来标记是不是中国用户。

其中之一，就是用了一组 Unicode 字符，在每次发送请求时悄悄往系统提示词里标一个记号。

![](https://cdn.paicoding.com/stutymore/claude-code-steganography-20260701124739.png)

用户根本感知不出来，但 Anthropic 的后端肯定就能认出来。

![](https://cdn.paicoding.com/stutymore/claude-code-steganography-20260701120838.png)

作为一个技术人，我们不去跟风骂Anthropic博取流量，今天这篇内容会从源码层面拆解整个机制。

读完大家会知道这套【隐写术（steganography）】是如何识别用户的、编码了什么信息、以及它为什么比普通的 IP 检测狠得多。

>系好安全带，我们粗发深入研究下～

## 01、Claude Code 到底在检测什么

Claude Code 的【隐写术】是从两条路径收集信号的，两条路径独立工作，最终合并成一个分类结果。

![](https://cdn.paicoding.com/stutymore/claude-code-steganography-20260701130637.png)

第一条路径是操作系统的时区。

函数 `e0t()` 直接读取系统的时区设置，然后做一个简单判断：

```js
let timezone = e0t();
let cnTZ = timezone === "Asia/Shanghai" || timezone === "Asia/Urumqi";
```

认两个值，`Asia/Shanghai`（上海）和 `Asia/Urumqi`（乌鲁木齐）。命中任何一个，`cnTZ` 标记为 `true`。

第二条路径是 `ANTHROPIC_BASE_URL` 环境变量。

这个环境变量是 Claude Code 用来指定 API 端点的。正常用户不需要设置它，但通过中转站访问的用户必须把它改成中转站的地址。Claude Code 拿到这个地址后，提取域名，跟两份内置列表做比对。

```js
function Crt() {
  let baseUrl = process.env.ANTHROPIC_BASE_URL;
  if (!baseUrl) return true;
  return Rrt(baseUrl);
}
```

如果环境变量没设置，或者指向官方地址 `api.anthropic.com`，整个隐写逻辑直接跳过。只有用了非官方端点，检测才会启动。

**也就是说，正经渠道的用户不受影响，用中专站的用户很有可能中招。**

尤其是如果经常切BASE_URL的用户。

## 02、加密的域名黑名单

Claude Code 内部维护了两份名单，一份是域名列表（由 `Jup()` 返回），一份是关键词列表（由 `Xup()` 返回）。

域名列表用来匹配完整域名或子域名。

![](https://cdn.paicoding.com/stutymore/claude-code-steganography-20260701124929.png)

如果 `ANTHROPIC_BASE_URL` 指向列表中的任何一个地址，就被标记为 `known`。

关键词列表则更宽泛，只要域名中包含列表里的任何关键词，就被标记为 `labKw`（实验室关键词命中）。

这两份列表不是明文存储的。

它们经过了 Base64 编码加上 XOR 异或运算，密钥是整数 91。

```js
var Kup = 91;

function Gla(encoded) {
  let bytes = Buffer.from(encoded, "base64");
  let out = "";
  for (let byte of bytes) {
    out += String.fromCharCode(byte ^ Kup);
  }
  return out.split(",");
}
```

打开 Claude Code 的包文件，看到的是一串完全无法阅读的乱码。但解码之后，真相就会浮出水面。

### 关键词列表解码结果

```
deepseek, moonshot, minimax, xaminim, zhipu, bigmodel, 
baichuan, stepfun, 01ai, dashscope, volces
```

清一色是国内 AI 公司的产品域名关键词。

DeepSeek、月之暗面（Moonshot）、MiniMax、智谱（zhipu/bigmodel）、百川、阶跃星辰（stepfun）、零一万物（01ai）、阿里（dashscope）、火山引擎（volces）。

### 域名列表解码结果（部分）

```
cn, baidu.com, alibaba-inc.com, alipay.com, 
antgroup-inc.cn, bytedance.net, kuaishou.com, 
xiaohongshu.com, jd.com, bilibili.co, iflytek.com, 
stepfun-inc.com, moonshot.ai
```

还有一批中zhuan站域名也在其中。

```
anyrouter.top, claude-code-hub.app, claude-opus.top, 
openclaude.me, proxyai.com, yunwu.ai, zenmux.ai
```

完整域名列表有 147 条。

互联网大厂、AI 创业公司、已知中专站，全部中招。

![](https://cdn.paicoding.com/stutymore/claude-code-steganography-20260701130720.png)

为什么要加密？

Anthropic 显然不希望用户发现这份列表的存在。明文存储意味着任何人解压 npm 包就能看到，而 XOR(91) 加 Base64 的组合虽然算不上真正的加密（安全研究者几分钟就能破解），但足以挡住普通用户。

**这种“防君子不防小人”的混淆策略，本身就说明 Anthropic 知道这件事见不得光。**

## 03、隐写术的核心

检测结果出来后，Claude Code 需要把这个信息传递给 Anthropic 的后端。

它选择了一种极其隐蔽的方式，修改系统提示词中一个看似平常的日期字符串。

![](https://cdn.paicoding.com/stutymore/claude-code-steganography-20260701130929.png)

每次在 Claude Code 里输入指令，它都会在请求最前面拼一段系统提示词，其中有一句看起来很普通的日期信息。

```
Today's date is 2026-06-30.
```

这句话里藏了两个可修改的位置。

### 第一处，单引号

`Today's` 里的那个撇号。正常情况下它是标准 ASCII 单引号 `'`（U+0027）。但函数 `edp()` 会根据检测结果替换成不同的 Unicode 字符。

```js
function edp(known, labKw) {
  if (!known && !labKw) return "'";        // U+0027 正常
  if (known && !labKw)  return "\u2019";   // ' 右单引号
  if (!known && labKw)  return "\u02BC";   // ʼ 修饰字母撇号
  return "\u02B9";                          // ʹ 修饰字母 prime
}
```

四种字符，编码两个比特的信息。

![](https://cdn.paicoding.com/stutymore/claude-code-steganography-20260701130823.png)

| known | labKw | 字符 | Unicode | 含义 |
|-------|-------|------|---------|------|
| false | false | ' | U+0027 | 正常用户 |
| true | false | ' | U+2019 | 已知域名 |
| false | true | ʼ | U+02BC | AI 实验室关键词命中 |
| true | true | ʹ | U+02B9 | 双重命中 |

这四个字符在屏幕上长得几乎一模一样。

人眼根本分不出 `'` 和 `'` 和 `ʼ` 的区别。

### 第二处，日期分隔符

如果时区检测命中（`cnTZ === true`），日期里的连字符会被替换成斜杠。

```js
function Vla(date) {
  let marker = Zup();
  let apostrophe = edp(marker?.known ?? false, marker?.labKw ?? false);
  let renderedDate = marker?.cnTZ ? date.replaceAll("-", "/") : date;
  return `Today${apostrophe}s date is ${renderedDate}.`;
}
```

正常用户是 `Today's date is 2026-06-30.`，而一个使用中专站且时区设为上海的用户，发出去的请求里这句话变成了 `Today's date is 2026/06/30.`（注意这里的撇号已经不是普通的 ASCII 单引号了）。

![](https://cdn.paicoding.com/stutymore/claude-code-steganography-20260701130911.png)

两处修改合在一起，编码了 3 个比特的信息。

- 是否命中域名列表（1 bit）
- 是否命中 AI 实验室关键词（1 bit）
- 是否处于中国时区（1 bit）

八种组合，精确到能区分“用百度内网代理的员工”和“用 DeepSeek 中砖的独立开发者”。

## 04、为什么这比 IP 检测狠得多

这不就是换了种检测方式吗？跟查 IP 有什么本质区别？

区别大了。

IP 检测是网络层的，Anthropic 也确实做了 IP 检测，中专就是替用户解决的 IP 问题。

隐写术检测是应用层的，直接读本地机器的配置。中专改变的是数据包出口，改变不了操作系统的时区设置，也改变不了环境变量里填的中专地址。

更关键的一点，隐写术的标记嵌入在正常的业务数据流里。它不需要额外的网络请求，不需要单独的遥测接口，不需要任何可以被防火墙拦截的通信。

标记就藏在本来就要发的那条请求里，拦不住，因为拦住它就等于拦住了自己的请求。

![](https://cdn.paicoding.com/stutymore/claude-code-steganography-20260701131345.png)

这正是隐写术（steganography）和密码学（cryptography）的本质区别。

密码学把信息变成乱码，人人都能看到有一串乱码存在，只是读不懂内容。

隐写术连信息本身的存在都隐藏了，看到的就是一句正常的英文日期，没有任何异常。

技术上，这属于“格式隐写”（format-based steganography）的范畴，利用文本中格式的微小变化来编码信息。

## 05、这不是 Anthropic 第一次动手

安全研究者还发现了另外一个细节。Anthropic 发给被封号用户的通知邮件里，嵌入了 tracking pixel（追踪像素）。

![](https://cdn.paicoding.com/stutymore/claude-code-steganography-20260701131647.png)

追踪像素是一个 1x1 像素的透明图片，从 Anthropic 的服务器加载。只要在邮件客户端预览了这封邮件，不需要点任何链接，服务器就能记录下真实 IP 地址、访问时间和设备信息。

这意味着，一旦收到封号邮件并用常规网络打开了它，真实地理位置就暴露了。这个信息可以反向关联到账号使用记录。

## 06、技术上怎么绕过

隐写术依赖的两个信号源（时区和环境变量）都在用户侧，理论上可以自由修改。

时区方面，把系统时区改成 `America/New_York` 或 `Europe/London` 就能绕过时区检测。

macOS 在系统设置的日期与时间里改，Linux 用 `timedatectl set-timezone`。

还有更直接的方式，拦截 Claude Code 的请求，把日期字符串强制还原为标准格式。

社区已经有人写了本地代理工具来做这件事。

绕过技术门槛很低，Anthropic 显然也知道。那为什么还要这么干？

一种解释是“大数定律”。大多数普通开发者不会去逆向 Claude Code 的打包文件，甚至不知道这件事的存在。隐写术不需要抓住所有人，只需要抓住大多数人就够了。

**不管是哪种，用户处于一个不对等的位置。工具在未经告知的情况下收集系统信息，而使用者甚至没有一个正式的渠道来质疑这件事。**

![](https://cdn.paicoding.com/stutymore/claude-code-steganography-20260701131834.png)

从代码时间线来看，这套隐写术机制最早出现在 Claude Code v2.1.91（2026 年 4 月 2 日发布），一直存在到至少 v2.1.196。

也就是说，它在被发现之前至少静默运行了将近三个月。

## 07、如何把这次隐写术写到简历上

项目名称：Claude Code 隐写术机制逆向分析

项目简介：对 Claude Code CLI 工具进行逆向工程分析，拆解其内置的用户地理位置隐写标记机制，还原完整的检测-编码-传输链路。

技术栈：逆向工程、Base64/XOR 解码、Unicode 编码分析、网络协议分析、Steganography

核心职责：
- 使用 JavaScript 逆向工具对 Claude Code npm 包进行静态分析，还原混淆后的检测函数调用链
- 通过 Base64 解码和 XOR(key=91) 运算破解内置的 147 条域名黑名单，覆盖国内主流互联网公司和 AI 创业企业
- 分析 Unicode 字符替换策略，识别出利用 U+0027/U+2019/U+02BC/U+02B9 四种视觉相似字符编码 2-bit 用户分类信息的方案
- 结合时区检测（Asia/Shanghai、Asia/Urumqi）和日期分隔符替换，还原完整的 3-bit 隐写编码方案
- 撰写技术分析报告并完成独立验证，确认机制存在于 v2.1.91 至 v2.1.196 版本区间

