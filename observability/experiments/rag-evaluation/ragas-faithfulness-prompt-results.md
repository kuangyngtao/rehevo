# 回答证据约束 Prompt 结果

## 改动与假设

H1 Gold v1 全量基线的 Context Recall 已达到 1.0000，Faithfulness 的主要失败模式是回答在正确结论之外加入知识库未明确支持的工程扩展。本轮只调整知识库回答 Prompt：要求每项主张有直接证据、先给直接结论、禁止无依据延伸，并控制默认回答长度；不改检索、融合权重、重排、知识库、回答模型和评审器。

## 固定低分切片

从 Prompt 改动前的 45 条 H1 RAGAS 基线中，冻结 Faithfulness 低于 0.9 的 11 个 caseId。该切片用于快速诊断，不是独立测试集：

`007, 013, 019, 025, 026, 030, 031, 032, 035, 038, 045`

固定条件：Gold v1、知识库 ID 2–7、`HYBRID`、关闭 Query Rewrite、Rehevo 当前默认回答模型、RAGAS 0.4.3、`qwen-plus` temperature 0。改动后本地证据位于被 Git 忽略的 `runs/ragas-20260816T130908Z/`，11 个 caseId 均唯一，三项指标均为有限值。

## 同题对比

| 指标 | 改动前 | 改动后 | 变化 |
| --- | ---: | ---: | ---: |
| Faithfulness | 0.7962 | 0.9482 | +0.1520（相对 +19.1%） |
| Context Precision | 0.9848 | 0.9848 | 0 |
| Context Recall | 1.0000 | 1.0000 | 0 |
| 平均回答字符数 | 469.7 | 199.5 | -57.5% |

11 条中 10 条 Faithfulness 提升，8 条达到 1.0000。`rehevo-gold-v1-031` 从 0.8824 降到 0.6667：新回答把“消息至少一次投递”强化成“保证业务至少执行一次”，后者不是原文的直接表述。该失败说明证据约束已明显减少扩展，但仍不能完全阻止语义强化。

## 完整回归状态与结论边界

首次启动相同条件下的 45 条完整回归时，回答服务在第三批返回 DashScope `403 Free quota exhausted`。百炼将 `qwen3.7-flash` 别名和 `qwen3.7-flash-2026-07-15` 快照视为两个独立免费额度池，因此后续将回答模型切换为仍有额度的日期快照，并从头生成新 run，避免混合两个模型的回答。

日期快照 run 位于被 Git 忽略的 `runs/ragas-faithfulness-full-snapshot-20260816/`：45/45 条回答已落盘，回答模型 ID 已冻结为 `qwen3.7-flash-2026-07-15`。RAGAS 使用原评审器 `qwen-plus` 完成 39/45 后，阿里云返回 `Arrearage` 并阻止包括免费快照在内的所有模型调用。该 39 条部分结果保留为中断证据，但不作为正式全量结论。

这次中断同时暴露出回答采样只在全部完成后落盘的问题。运行器现已支持 `--run-dir`：每批持久化回答，续跑前校验数据集、case ID、回答模型和检索配置；同配置两次 dry-run 的续跑契约已通过。评分器新增独立 `--output-dir` 和 `judge.json` 契约，避免不同评审器结果混写；默认评审器切换为 `qwen3.7-plus`，并显式关闭思考模式。

## 日期快照全量结果

充值恢复后没有用 `qwen3.7-plus` 只补剩余 6 条，而是复用同一份 45 条 `ragas-input.jsonl`，从头统一评分，避免把 `qwen-plus` 与 `qwen3.7-plus` 混成一个正式指标。结果位于被 Git 忽略的 `runs/ragas-faithfulness-full-qwen37plus-20260817/`，摘要另存为 `ragas-faithfulness-full-qwen37plus-result.json`。

| 指标 | `qwen3.7-plus` 45 条全量结果 |
| --- | ---: |
| Faithfulness | 0.9768 |
| Context Precision | 0.9741 |
| Context Recall | 0.9889 |

45/45 条均完成，三项指标全部为有限值。分类结果中，事实查找 15 条达到 Faithfulness 1.0000、Context Recall 1.0000；多跳问题的 Context Precision 与 Context Recall 均为 0.9667；语义改写的 Faithfulness 最低，为 0.9521，是下一轮优先诊断方向。

在与旧 `qwen-plus` 已完成的同一组 39 条上，`qwen3.7-plus` 的 Faithfulness 高 0.0103，Context Precision 低 0.0128，Context Recall 低 0.0128。这说明评审器本身会带来约 1 个百分点的波动，因此正式报告只引用 45 条统一评审结果，不把新旧分数差解释为 RAG 链路变化。由于回答模型从完整基线的 `qwen3.7-flash` 别名变为日期快照，本结果应标注为“日期快照全量结果”，不能包装成严格的 Prompt 单变量对照。
