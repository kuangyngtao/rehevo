# Rehevo RAG 固定测评集

本目录保存 RAG 改造的可复现评测契约和金标数据，不保存大规模公开语料，也不保存用户上传的原始简历或知识库文件。

## 两层测评集

| 层级 | 数据 | 用途 | 结论边界 |
| --- | --- | --- | --- |
| 外部回归 | T2Ranking 开发集的 200 条固定抽样 | 验证中文候选排序和 Cross-Encoder 重排是否退化 | 不能代表 Rehevo 的求职问答效果 |
| 领域金标 | Rehevo Gold v1，目标 60 条 | 验证混合召回、RRF、重排、引用和回答忠实度 | 项目量化成果只引用这一层的端到端结果 |
| 冒烟样本 | Orchid-417 的 3 条验收问题 | 检查上传、向量化、查询和指标链路 | 不计入任何质量指标 |

T2Ranking 的数据与代码采用 Apache-2.0；完整集体积较大，不纳入仓库。下载、筛选后只将 `qid` 清单、源版本和文件校验值写入 `external/t2ranking-200.manifest.json`。原始数据放在本机忽略目录 `data/local/`。

首次冻结或重建本地缓存：

```powershell
cd observability/experiments/rag-evaluation
python .\import-t2ranking-dev-sample.py
```

该脚本仅下载 `queries.dev.tsv` 与 `qrels.dev.tsv`，并按锁定 revision、种子和 qid 校验值生成清单；它不下载 32GB 的 `collection.tsv`。后续做候选排序或 Cross-Encoder 重排时，必须使用清单中的同一 revision 物化候选文档，不能更换集合后沿用该结果。

## Rehevo Gold v1 标注要求

正式集目标为 60 条，四类各 15 条：

1. 事实定位：材料中存在单一、可核对答案。
2. 多段综合：需要至少两个 Chunk 才能完整回答。
3. 语义改写：原问与材料的词面不完全重合，用于验证向量与关键词融合。
4. 不可回答：知识库没有依据时必须明确说明，不得编造引用。

每条必须标注 `expectedChunkRefs`，并为可回答问题提供简短 `referenceAnswer`；不可回答问题设为 `answerable=false` 且 `expectedChunkRefs=[]`。同一材料的相邻 Chunk 不得同时充当所有问题的唯一证据，避免只评到单一文档。

使用方式：复制 `rehevo-gold-v1.template.jsonl` 为未提交的本地文件，按 `schema.json` 补齐数据；完成 60 条双人复核或同一标注人二次复核后，将去敏后的金标文件提交为 `rehevo-gold-v1.jsonl`。

当前已提交的 Gold v1 是**公开资料控制集**：6 份经中文归纳的官方后端技术学习卡片、60 条人工复核题（四类各 15 条）。该语料用于验证评测链路和算法相对变化；以后接入用户真实知识库时，必须新建独立 Gold 版本，不能把两类结论混合。

第一组纯向量检索基线及其适用边界见 [baseline-results.md](baseline-results.md)。

## 固定运行条件

- 运行前固定知识库版本：记录每个文档 SHA-256、Chunk 参数、Embedding 模型和向量库状态。
- 基线：当前 Query Rewrite + 向量召回；目标：关键词/向量候选 + RRF + Cross-Encoder 重排。
- 相同的 Gold v1、同一知识库版本、同一 Top-K 和回答模型下比较；模型或文档变更必须创建新 run，不覆盖旧结果。
- 检索指标：`Recall@5`、`MRR@10`、`nDCG@10`；端到端指标：回答正确率、引用正确率、不可回答拒答准确率，以及 RAGAS 的忠实度/上下文相关性。
- 每次运行写入 `runs/`（本地忽略）：配置、Git commit、时间、原始检索结果、汇总指标和失败样本。不得只保留汇总百分比。

## RAGAS 回答评测

RAGAS 是独立的离线工具，不加入 Rehevo 服务运行时。运行器调用 `/api/knowledgebase/evaluation/answers`：该接口返回**实际进入回答模型的完整检索片段**，且不会增加知识库提问计数；不要改用面向前端的 240 字 `contentPreview`。

```powershell
cd observability/experiments/rag-evaluation
python -m venv data/local/ragas-venv
.\data\local\ragas-venv\Scripts\python.exe -m pip install -r requirements-ragas.txt --timeout 180 --retries 4
$env:RAGAS_JUDGE_API_KEY = $env:ALI-API-KEY # 仅复制到当前终端，不打印、不写入文件
.\data\local\ragas-venv\Scripts\python.exe .\run-ragas-evaluation.py --dry-run
```

首轮只运行 Gold 的可回答样本，默认关闭 Query Rewrite，以便和现有纯向量基线的检索条件一致；使用 `--rewrite` 必须新建 run，不能覆盖基线。输出会保存完整回答、实际检索片段、单题得分和汇总到被忽略的 `runs/`。

首批指标为 `faithfulness`、`context_precision`、`context_recall`。它们分别检验回答是否由实际片段支撑、排序靠前的片段是否有用、金标答案所需事实是否被召回。不可回答题不进入这三项 RAGAS 均值，仍以“正确拒答且不伪造引用”单独统计。

## 通过门槛

在未取得基线前不预设“提升百分比”。目标方案只有同时满足以下条件才可作为优化结论：

1. Gold v1 的 `Recall@5` 与基线持平或提升；
2. `MRR@10` 或 `nDCG@10` 提升，且失败样本可解释；
3. 引用正确率、拒答准确率不低于基线；
4. 记录检索与重排耗时，避免以质量提升掩盖不可接受的延迟。
