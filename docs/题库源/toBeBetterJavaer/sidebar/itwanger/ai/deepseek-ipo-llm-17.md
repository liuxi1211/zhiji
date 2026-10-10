---
title: DeepSeek准备上市，有点猛啊。
shortTitle: DeepSeek大模型面试17问
description: DeepSeek启动IPO筹备估值710亿美元，17道大模型核心面试题全解，覆盖Transformer、Attention、MoE、KV Cache、RLHF等高频考点。
keywords:
  - DeepSeek
  - 大模型面试题
  - Transformer
  - Attention机制
  - MoE架构
tag:
  - 面试
category:
  - AI
author: 沉默王二
date: 2026-07-15
---

《安娜·卡特琳娜》一书的开头曾有这样一句话。

“幸福的家庭都是相似的，不幸的家庭各有各的不幸。”

放在AI圈也是完美适配。 

据彭博社报道，DeepSeek已启动上市筹备工作，计划于2027年完成IPO，最快可能在今年底就迈出这关键一步。

与之配套的新一轮融资也在同步推进，DeepSeek拟以约710亿美元的估值募集约15亿美元资金。

换句话说，我们这些普通的从业者，不管是已经工作的，还是将要找工作的，重心都要往 AI Agent 这个方向倾斜。

真吃香喝辣。

其他行业，你可能事倍功半；但这个方向，你可能事半功倍。

我身边有很多朋友，都是在短短的一年/半年时间里，乘 AI 这股风，扶摇直上三万里。

上次我也给大家分析了一波DeepSeek官方摇人的岗位诉求：[DeepSeek 开始摇人](https://mp.weixin.qq.com/s/FQ67RC2ciSUeDJ3zlGeYEQ)，很多小伙伴表示学到了很多，了解到接下来该往哪个方向去发力。

那今天换个视角——如果你去 DeepSeek 或者任何一家做大模型的公司面试，面试官会考察什么？

（可能面AI应用开发也会考察）

我翻了 DeepSeek-V3 和 R1 的技术报告，对照 Llama 3 的训练文档和 Google 的 Transformer 论文，整理了 17 道大模型面试高频题，覆盖架构、训练、推理三个维度。

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-01-knowledge-map-20260715124829-5f726b04.png)

>系好安全带，我们粗粗粗粗粗发～

## content

### 01、什么是大语言模型（LLM）？

老王开场第一题：“大语言模型，一句话定义一下。”

我回答：“大语言模型是基于 Transformer 架构、在海量文本上通过自监督学习训练出来的神经网络模型。核心能力是根据上文预测下一个 token。输入一段文字，输出最可能跟在后面的那个词。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-02-parameter-scale-20260715124830-2d28d4f2.png)

“参数规模通常在数十亿到数万亿之间。GPT-4 据传约 1.8 万亿参数，DeepSeek-V3 是 6710 亿参数。参数量大到一定程度之后，模型会涌现出推理、代码生成这些训练时没有显式教过的能力，学术上叫涌现能力（Emergent Ability）。”

### 02、大模型与传统机器学习模型有什么区别？

老王追问：“和传统的机器学习模型比呢？本质区别在哪？”

“三个维度。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-03-llm-vs-ml-20260715124830-66ae2582.png)

“数据需求不同。传统模型依赖人工标注的结构化数据，一个情感分类任务可能要标注几万条评论。大模型用自监督学习，直接吃下互联网上海量的原始文本，不需要人工标注。Llama 3 的训练数据有 15 万亿 token，靠人标注根本不现实。”

“能力边界不同。传统模型一个任务训一个模型，情感分析训一个，命名实体识别训另一个，彼此之间不通用。大模型是一个模型覆盖多种任务，同一个 Claude Opus 4.8 能写代码、能翻译、还能做数学推理。”

“学习方式不同。传统模型靠特征工程，由人来设计特征、选择特征。大模型通过预训练自动学习语言的统计规律，下游任务只需要写 Prompt 或者做少量微调就能适配。”

### 03、什么是 Transformer 架构？

老王说：“聊点底层的，Transformer 架构了解多少？”

我说：“Transformer 是 Google 2017 年在论文《Attention is All You Need》里提出的。原始结构由 Encoder 和 Decoder 两部分组成。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-04-transformer-20260715124831-bb67b906.png)

“Encoder 负责理解输入。文本先经过 Tokenizer 切成 token 序列，加上位置编码（Positional Encoding），然后依次通过多头自注意力层和前馈神经网络层，每层后面接残差连接和 LayerNorm。”

“Decoder 负责生成输出。结构和 Encoder 类似，但多了一层掩码自注意力（Masked Self-Attention），生成第 N 个 token 的时候，只能看前 N-1 个 token，不能偷看后面的答案。”

### 04、为什么 Transformer 能取代 RNN？

老王紧跟着追问：“Transformer 之前大家都用 RNN，为什么被取代了？”

“两个致命问题。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-05-rnn-transformer-20260715124831-7845b2dc.png)

“第一，没法并行。RNN 必须按顺序处理序列——读完第 1 个词才能读第 2 个，读完第 2 个才能读第 3 个。一条 512 个 token 的序列，RNN 要跑 512 步。GPU 最擅长并行计算，RNN 完全浪费了这个能力。Transformer 所有位置同时计算，一步到位。”

“第二，长距离依赖衰减。信息在 RNN 里是逐步传递的，传到后面前面的内容就快没了。一篇 1000 个词的文章，第 1 个词的信息传到第 1000 个词时基本只剩零头。后来 LSTM 加了门控机制缓解了一些，但本质上还是顺序传递，衰减变慢了，没根治。”

“Transformer 的注意力机制直接解决了这两个问题：并行计算，任意两个 token 之间的距离都是 1。”

### 05、什么是 Attention 机制？

老王说：“那注意力机制到底怎么工作的？展开说说。”

“Attention 的核心是让每个 token 去‘查询’序列里的所有 token，按相关性分配不同的权重，然后做加权汇总。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-06-attention-flow-20260715124832-496c3e11.png)

“计算分三步。”

“第一步，每个 token 通过三个不同的权重矩阵变换出三个向量：Query（我要找什么信息）、Key（我有什么信息可以匹配）、Value（我携带的实际内容）。”

“第二步，用 Query 和所有 Key 做点积，得到注意力分数。分数越高，两个 token 越相关。然后除以 √d_k（d_k 是 Key 向量的维度），再过 Softmax 归一化成概率分布。除以 √d_k 是为了防止点积值过大导致 Softmax 输出趋近于 one-hot，梯度几乎为零。”

“第三步，用归一化后的概率对所有 Value 做加权求和，得到最终输出。”

### 06、什么是 Self-Attention？

老王追问：“Self-Attention 和刚才说的 Attention 有什么区别？”

“区别在 Q、K、V 的来源。”

“普通 Attention 里，Query 可以来自一个序列，Key 和 Value 来自另一个序列。比如机器翻译场景，Query 来自目标语言的 Decoder，Key 和 Value 来自源语言的 Encoder。这叫 Cross-Attention。”

“Self-Attention 里，Q、K、V 全部来自同一个序列。一句话里的每个词都在和这句话里的其他词做匹配，计算彼此之间的关联强度。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-07-self-attention-20260715124832-42819d0c.png)

“举个例子：‘小明把球传给小红，因为她跑位好。’这里的‘她’指的是谁？Self-Attention 会计算‘她’和句中所有词的注意力分数。‘小红’的分数最高，模型就知道‘她’是‘小红’，不是‘小明’。这种能力叫指代消解（Coreference Resolution），RNN 做不好的长距离语义关联，Self-Attention 天然擅长。”


### 07、为什么要使用多头注意力？

老王点点头：“为什么不用单个 Attention，要搞多个头？”

我说：“单个 Attention Head 只能捕捉一种注意力模式。但语言里的关系是多维的——有语法搭配关系、有语义关联关系、有位置邻近关系、有指代跳跃关系。一个头装不下这么多维度的信息。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-08-multi-head-20260715124833-0b63c293.png)

“多头注意力（Multi-Head Attention）的做法是把 Q、K、V 拆成 h 组，每组独立做一次 Attention 计算，最后把 h 组结果拼起来过一个线性变换。”

“训练过程中不同的头会自动学到不同的关注模式。研究者可视化之后发现，有的头专门关注相邻词的语法搭配，有的头跟踪远距离的指代关系，有的头捕捉动词和宾语之间的依赖。就像一个审稿团队，每个人从不同角度审同一份材料，最后汇总出一份全面的评审意见。”

### 08、什么是 MoE 架构？

老王说：“既然聊到 DeepSeek，MoE 架构得好好说说。”

“MoE 全称混合专家模型（Mixture of Experts）。核心思想是：模型里有一堆‘专家’网络，每次推理只激活其中一小部分，其余的跳过。”

“具体做法是把 Transformer 里的前馈网络（FFN）层替换成多个并行的专家网络，外加一个门控路由（Router）。每个 token 进来时，Router 给所有专家打分，选 Top-K 个激活，剩下的不参与计算。”

“DeepSeek-V3 的数据：256 个路由专家加 1 个共享专家，每个 token 激活 8 个。总参数 6710 亿，单次推理的激活参数只有 370 亿，约为总量的 5.5%。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-09-moe-20260715124833-304e5ca1.png)

“模型容量可以做得非常大（参数多意味着知识多），推理成本却控制在较低水平（每次只用一小部分参数）。”

“代价是训练和部署的工程复杂度。Router 如果总把 token 往少数几个专家身上导，其他专家就废了，这叫负载不均衡问题。DeepSeek-V3 用了无辅助损失的负载均衡策略，不加额外的损失函数，通过动态偏置调整让 Router 自然地均匀分配负载。”

老王拍了一下桌子：“行，这个讲得清楚。换个方向，训练流程说说。”

### 09、什么是预训练？

“预训练（Pre-training）是大模型训练的第一个阶段，目标是让模型在大规模无标注文本上学会语言的统计规律。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-10-pretraining-20260715124833-5c9a48e5.png)

“方法是自回归语言建模（Causal Language Modeling）：给一段文字，盖住最后一个词让模型猜。猜对了更新权重、猜错了也更新权重，不断让模型猜得越来越准。”

“数据量级通常是万亿 token。训练周期通常数周到数月。”

“预训练完成后得到的叫基座模型（Base Model）。它什么都会一点，但不太听指令——问它‘今天天气怎么样’，它可能接一句‘明天天气也不错’，因为它学到的是‘接话’，不是‘回答问题’。要让它听话，还得做后续的指令微调。”

### 10、大模型如何处理训练数据？

老王追问：“万亿 token 的数据量，怎么处理的？”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-11-data-pipeline-20260715124834-1ccba9a2.png)

“五步。”

- 数据采集：从 Common Crawl（互联网快照）、书籍、学术论文、GitHub 代码仓库等多个来源收集原始文本
- 去重：用 MinHash + LSH 做近似去重，去掉重复或高度相似的文档。重复数据会让模型死记硬背特定文本，而不是学习泛化规律
- 质量过滤：专门训一个分类器给文档打分，过滤掉广告、乱码、机器生成的垃圾内容。
- 敏感内容清洗：去除个人隐私信息、有害内容、版权敏感材料
- 分词（Tokenization）：用 BPE（Byte Pair Encoding）算法把文本切成 token 序列。BPE 从字符级别开始，不断合并出现频率最高的相邻字符对，直到词表大小达到预设规模（GPT-4 的词表约 10 万个 token）

“五步走完，有效数据通常只有原始采集量的 10% 到 30%。大量低质量和重复的内容在去重和质量过滤阶段被淘汰了。”

### 11、大模型中的“微调”是什么？

老王说：“预训练完了直接能用吗？”

“不能。基座模型只会‘接话’，不会‘听指令’。得通过微调（Fine-tuning）在预训练好的模型基础上，用一批标注数据继续训练，让它适配特定的任务或行为模式。”

“微调有两种主要方式。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-12-lora-20260715124834-0cdba21d.png)

“全量微调（Full Fine-tuning）：更新模型所有参数，效果最好，成本最高。一个 70B 参数的模型做全量微调，至少需要 4 张 A100 80GB 的显卡。”

“参数高效微调（PEFT）：只更新一小部分参数，冻结大部分原始权重。最常用的方法是 LoRA（Low-Rank Adaptation），在原始权重矩阵旁边插入两个小矩阵（低秩分解），只训练这两个小矩阵的参数。可训练参数量压缩到原来的 0.1%，一张消费级显卡就能跑起来。”

“实际工程中大多数场景都用 LoRA 或它的变体 QLoRA（把模型量化到 4-bit 再做 LoRA），成本和效果之间的平衡最好。”

### 12、大模型中的“SFT”是什么？

老王追问：“SFT 和微调什么关系？”

“SFT（Supervised Fine-Tuning，有监督微调）是微调的一种。训练数据是一组指令-回答对：一条指令，比如‘帮我写一封请假邮件’，配一条高质量的回答。模型要学会在用户给出指令时，该怎样组织语言来回答。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-13-sft-20260715124835-0ea952d9.png)

“SFT 是预训练之后的第一步。预训练给了模型语言能力，SFT 教它怎么响应人类指令。”

“这里有一个很重要的经验结论：数据质量远比数量重要。InstructGPT 的论文里提到，1.3 万条高质量 SFT 数据就能让模型的指令遵循能力大幅提升。反过来，用几十万条低质量数据训出来的模型，效果可能更差。高质量意味着指令覆盖多样化的任务类型，回答要准确、完整、符合人类期望的格式。”


### 13、大模型中的“RL”是什么？

老王说：“SFT 之后还有 RL 对吧？”

“RL 在大模型语境里主要指 RLHF（Reinforcement Learning from Human Feedback），基于人类反馈的强化学习。”

“流程分三步。第一步，用 SFT 模型对同一个问题生成多个不同的回答。第二步，人类标注员对这些回答做排序，哪个好，哪个差。用这些排序数据训练一个奖励模型（Reward Model），让它学会自动给回答打分。第三步，用 PPO（Proximal Policy Optimization）算法，推动 SFT 模型朝着奖励模型打高分的方向优化参数。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-14-rlhf-20260715124835-8d3e3131.png)

SFT 教模型怎么说话，RLHF 教模型怎么把话说好。

“DeepSeek-R1 走了一条不同的路线，用 GRPO（Group Relative Policy Optimization）替代了传统的 RLHF。GRPO 不需要单独训一个 Reward Model，直接在一组回答内部做相对排序作为奖励信号。训练流程简化了，在数学推理和代码生成任务上的表现也不逊色。”

### 14、大模型中的“温度（Temperature）”是什么？

老王若有所思：“推理阶段的参数问一个，Temperature 干什么用的？”

“Temperature 控制模型输出的随机性。”

“大模型生成下一个 token 的时候，会给词表里的每个候选 token 算一个原始分数（logits）。Temperature 的作用是在 Softmax 之前，把所有 logits 除以 Temperature 值。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-15-temperature-20260715124836-d3f3d17e.png)

“温度 = 1.0 时，概率分布不变。温度 < 1.0 时，比如 0.2，分布变得更尖锐，高概率的 token 概率进一步拉高，低概率的更低，模型倾向于选最可能的词，输出更确定、更保守。温度 > 1.0 时，比如 1.5，分布变得更平坦，各 token 之间的概率差距缩小，模型更可能选到‘意外’的词，输出更随机、更有创意。”

“温度 = 0 是一个特例：直接选概率最高的 token，叫贪心解码（Greedy Decoding）。同样的输入永远得到同样的输出，输出完全确定。”

“实际使用中，代码生成任务推荐 0 到 0.2（要精确不要发散），创意写作可以用 0.7 到 1.0（要多样性），日常对话一般 0.5 到 0.7。”

### 15、什么是 KV Cache？

老王抛出一道工程性很强的题目：“KV Cache 了解吗？”

我说：“KV Cache 是大模型推理阶段最关键的加速手段。”

“大模型的文本生成是自回归的——每次生成一个 token，然后把这个 token 加到输入序列里，再计算下一个。问题在于，每次计算新 token 的 Attention 时，需要用到前面所有 token 的 Key 和 Value 向量。如果每次都从头算一遍，计算量会随着序列长度平方级增长。”

“KV Cache 的做法是：前面 token 的 Key 和 Value 算过一次之后存进缓存，后面直接取缓存，不再重复计算。每次生成新 token 时，只需要计算新 token 自己的 Q、K、V，然后从缓存里取出历史所有的 K 和 V 做 Attention。”

“代价是显存。以 Llama 2 70B 为例，上下文长度 4096 的情况下，KV Cache 大约占 2.5GB 显存。如果上下文扩展到 128K，KV Cache 的显存占用会线性增长到约 80GB，可能比模型参数本身占的显存还大。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-16-kv-cache-20260715124836-a8de8682.png)

“针对 KV Cache 显存开销的优化有两个主流方案。GQA（Grouped Query Attention）：让多个 Query Head 共享同一组 Key 和 Value，减少需要缓存的 KV 对数量。Llama 3 用的就是 GQA。MLA（Multi-head Latent Attention）：DeepSeek-V2 提出的方案，把 Key 和 Value 压缩到一个低维潜在空间再缓存，缓存量比 GQA 还要小。DeepSeek-V3 沿用了这个设计。”

### 16、什么是大模型“幻觉”问题？

老王说：“部署层面的问题聊一个，幻觉怎么理解？”

“幻觉（Hallucination）是指模型生成的内容看起来流畅通顺，但和事实不符。”

“根源在于大模型的运作方式是概率预测——它不是在‘回忆’事实，而是在根据统计规律‘编排’最可能的下一个词。当训练数据中某个知识点的覆盖不够，或者问题超出了训练数据的范围，模型会用统计规律‘补’一个看起来合理但实际上不正确的答案。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-17-hallucination-20260715124837-164beb55.png)

“幻觉分两种类型。事实性幻觉（Factual Hallucination）：编造不存在的事实，比如给一篇不存在的论文编造标题、作者和发表年份，给一个真实人物编造虚假的经历。忠实性幻觉（Faithfulness Hallucination）：回答和给定的上下文矛盾，比如在 RAG 场景里，检索到的文档明明写着‘A 公司 2020 年成立’，模型回答的时候却说‘2018 年’。”

“缓解方案有几条路。RAG 引入外部知识库做事实锚定，把模型的回答和可靠信源做交叉校验。Prompt 层面明确约束‘不确定就说不知道’。用更大、训练数据更新的模型，覆盖面更广。部署后加一层事实核查模块做兜底。没有银弹，这是概率模型的本质局限。”

### 17、大模型中的“泛化”是什么？

老王抛出最后一题：“泛化能力怎么衡量？”

“泛化（Generalization）是指模型在训练时没见过的数据上也能表现良好的能力。和过拟合（Overfitting）是一对反义概念。”

![](https://cdn.paicoding.com/stutymore/deepseek-ipo-llm-17-18-generalization-20260715124837-f78e095a.png)

“过拟合是模型把训练数据背住了——原题满分，换个说法就不会做。泛化好的模型是真正学到了规律，换个场景照样管用。”

“大模型的泛化能力主要来自三个方面。训练数据的多样性，数据来源越广泛，覆盖的语言模式和知识领域越多，模型见过的‘场景’越丰富，泛化能力就越强。模型规模，参数量足够大的模型能捕捉到更细粒度的语言规律和知识关联。正则化手段，比如 Dropout（训练时随机关闭部分神经元）、权重衰减（限制参数增长），这些技术防止模型过度拟合训练数据。”

“衡量方法很直接：看模型在独立测试集上的表现。训练集准确率 99% 但测试集只有 60%，就是典型的过拟合、泛化能力差。两者都高且数值接近，泛化就好。大模型的通用泛化能力评测通常看 MMLU、HumanEval、GSM8K 这些标准化 Benchmark 的分数。”

## ending

DeepSeek 710 亿美元估值的背后，是 MoE 架构省推理成本，是 GRPO 省训练成本，是 MLA 压缩 KV Cache 省显存。面试官问的每一道题，都不是在为难谁，是在确认——你有没有理解这些技术选择背后的取舍。

**【面试拼的不是背了多少题，是理解了多少个“为什么”。】**

学习的快乐也正基于此，以前没有大模型，我们学习这样的知识曲线非常陡峭，现在呢？

有什么不懂不会的，直接问 ChatGPT、Claude、DeepSeek，很快就能get到真伪，我们只需要让我们的脑子动起来，去理解他们。

加油吧，兄弟姐妹们。

下期见。
