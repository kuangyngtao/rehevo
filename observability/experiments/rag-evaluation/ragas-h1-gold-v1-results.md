# H1 Gold v1 RAGAS 结果

## 固定条件

| 项目 | 值 |
| --- | --- |
| 运行代码提交 | `b2eeaaf02be0dc5b27acb3200bb9e643baf5148e` |
| 数据集 | `rehevo-gold-v1.jsonl` 的 45 条可回答样本 |
| 知识库 | ID 2–7，6 个正式 Chunk |
| 检索模式 | `HYBRID`，关闭 Query Rewrite |
| 回答模型 | Rehevo 当前默认模型 |
| 评审器 | RAGAS 0.4.3 + `qwen-plus`，temperature 0 |
| 指标 | Faithfulness、Context Precision、Context Recall |
| 本地证据 | `runs/ragas-20260816T110959Z/`，包含 45 条回答、输入和单题分数；该目录被 Git 忽略 |

## 全量结果

45 个唯一 caseId 全部完成，三项指标均为有限值，无 NaN：

| 指标 | 均值 | 最低分 | 满分样本数 |
| --- | ---: | ---: | ---: |
| Faithfulness | 0.9325 | 0.6154 | 24 / 45 |
| Context Precision | 0.9852 | 0.5000 | 43 / 45 |
| Context Recall | 1.0000 | 1.0000 | 45 / 45 |

按题型拆分：

| 题型 | 样本数 | Faithfulness | Context Precision | Context Recall |
| --- | ---: | ---: | ---: | ---: |
| fact_lookup | 15 | 0.9652 | 0.9889 | 1.0000 |
| multi_hop | 15 | 0.9099 | 1.0000 | 1.0000 |
| semantic_paraphrase | 15 | 0.9224 | 0.9667 | 1.0000 |

## 失败切片

Faithfulness 低于 0.8 的 4 条中，最低三条为：

1. `rehevo-gold-v1-026`，0.6154：为什么不能把全文检索分数与向量分数直接相加？
2. `rehevo-gold-v1-038`，0.7200：为什么混合检索要先融合再重排？
3. `rehevo-gold-v1-030`，0.7273：重排分数为什么不能跨不同问题设置统一阈值？

这些回答的核心结论正确，但回答模型在金标短答案之外扩展了额外工程解释；RAGAS 将部分扩展判断为未被实际上下文充分支撑。Context Precision 的最低样本是 `rehevo-gold-v1-017`（0.5000）：事务里开新线程后还会自动沿用原事务吗？

## 结论边界

- H1 在当前公开控制集上没有遗漏金标答案所需事实，Context Recall 为 1.0；但语料只有 6 个 Chunk，存在明显天花板，不能外推到真实用户知识库。
- 当前主要质量瓶颈不是召回，而是回答过度扩展：Faithfulness 为 0.9325，且 multi_hop、semantic_paraphrase 低于事实定位题。
- 这是 H1 的绝对质量基线，未运行同回答模型下的 V0 RAGAS 对照，因此不能把该分数写成“相对提升百分比”。
- RAGAS 结构化评审存在数分钟长尾，只适合作为离线质量门。运行器已增加分批落盘、NaN 重试和断点续跑，不能放入线上请求链路。

下一轮应先收紧回答 Prompt，要求逐项依据上下文、避免无证据扩展，再用独立版本复跑低分切片和 45 条回归集；通过门槛是 Context Recall 不降、Faithfulness 提升且 Context Precision 不退化。
