#!/usr/bin/env python3
"""生成 Gold v2-hard 的公开资料控制语料、来源清单和 40 条高难金标。"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).parent
CORPUS_DIR = ROOT / "data/local/backend-interview-kb-v2-hard"
MANIFEST_PATH = ROOT / "hard-v2-corpus.manifest.json"
DATASET_PATH = ROOT / "rehevo-gold-v2-hard.jsonl"

DOCUMENTS = [
  ("01-transaction-propagation.md", "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html", """# Spring 事务传播：条件与资源

`PROPAGATION_REQUIRED` 会加入已经存在的外层事务；若没有外层事务才创建新事务。多个参与者通常映射到同一个物理事务，因此任一参与者把事务标为 rollback-only 后，外层提交点可能收到 `UnexpectedRollbackException`。

`PROPAGATION_REQUIRES_NEW` 总是挂起外层事务并使用独立的物理事务。它可以独立提交或回滚，但不会自动解决数据库提交与异步消息投递的时序问题；同时它可能额外占用连接，连接池应为并发外层事务预留容量。

`PROPAGATION_NESTED` 在支持保存点的事务管理器中允许局部回滚，外层物理事务仍然存在。是否选用传播行为，应先区分“调用是否进入代理”“失败是否允许影响外层”“连接与锁资源是否可承受”。"""),
  ("02-transaction-rollback-rules.md", "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html", """# Spring 回滚规则：不要只按异常名称猜测

默认声明式事务会在未处理的 `RuntimeException` 或 `Error` 上回滚；受检异常默认不触发回滚。业务需要不同规则时，应在 `@Transactional` 中显式使用 `rollbackFor` 或 `noRollbackFor`。

规则是在代理拦截到方法抛出的异常后应用的。若异常在事务方法内部已被捕获并正常返回，默认规则没有一个向外传播的异常可判断；此时若仍需回滚，应显式标记 rollback-only 或重新抛出合适异常。配置了最具体规则时，应以最具体匹配为准。"""),
  ("03-transaction-proxy-boundary.md", "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html", """# Spring 事务代理边界

声明式事务通常依赖 AOP 代理。默认 proxy 模式只能拦截从代理外部进入目标对象的方法调用；同一个 Bean 的 `this.someTransactionalMethod()` 属于自调用，通常不会经过事务代理。

排查“注解写了却没有事务”时，先确认 Bean 是否由 Spring 管理、调用是否经过代理、方法可见性与代理类型是否匹配，再讨论传播行为或回滚规则。把 `REQUIRES_NEW` 写在一个未经过代理的自调用方法上，不会让该传播行为神奇生效。"""),
  ("04-redis-xreadgroup.md", "https://redis.io/docs/latest/commands/xreadgroup/", """# Redis Stream：XREADGROUP 的新消息与待处理消息

消费组读取时，`XREADGROUP ... >` 表示只读取从未投递给任何消费者的新消息；Redis 会把已投递记录加入该消费者的 Pending Entries List（PEL）。读取成功本身不代表业务已经完成。

当使用具体 ID（例如 `0`）读取时，读取的是已经投递给当前消费者但仍处于 pending 状态的历史记录，而不是新的未投递消息。一个 Stream 可以有多个消费组；它们各自拥有独立的消费者状态与 PEL，同一条消息可以被不同组分别处理。"""),
  ("05-redis-pending-claim.md", "https://redis.io/docs/latest/commands/xpending/\nhttps://redis.io/docs/latest/commands/xautoclaim/", """# Redis Stream：观察 PEL 与恢复所有权

`XPENDING` 用于观察消费组 PEL 中未确认消息的汇总或明细，不会把消息所有权转交给其他消费者。它适合在恢复动作前确认积压、空闲时长和当前 owner。

`XAUTOCLAIM` 会在最小空闲时间条件满足时，把 pending 消息转移给指定消费者并返回消息。最小空闲时间不能随意设置得过小：原消费者可能仍在处理，过早接管会提高并发重复执行的概率。恢复逻辑还需要与幂等业务处理和成功后的确认配合。"""),
  ("06-redis-ack-idempotency.md", "https://redis.io/docs/latest/commands/xack/", """# Redis Stream：确认顺序与幂等

`XACK` 将消息从指定消费组的 PEL 中移除。通常应在业务副作用已经成功落地后再确认：若先确认再崩溃，消息可能无法重投；若先处理后崩溃，消息仍可能处于 pending 并被再次处理。

因此消费组提供的是至少一次投递语义，而不是端到端恰好一次。业务侧需用唯一业务键、去重记录或可重入状态机保证幂等；多个消费组的独立读取也不意味着全局只处理一次。"""),
  ("07-spring-ai-query-transform.md", "https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html", """# Spring AI RAG：查询变换属于召回前

查询变换在检索前把用户原问改写、扩展或拆分为更适合检索的表达，以减少口语、省略和术语差异造成的漏召回。它服务于候选覆盖，而不是替代最终回答时对原始问题的忠实响应。

多查询或重写会增加模型调用和检索次数，所以需要分别记录改写耗时、改写失败回退以及最终检索质量。后续重排仍应以用户原始问题与每个候选 Chunk 配对，避免改写时丢失约束后把错误候选排到前面。"""),
  ("08-spring-ai-post-retrieval.md", "https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html", """# Spring AI RAG：后检索处理

检索到候选文档后，可以执行过滤、压缩、重排等后检索处理，目的是在有限上下文窗口中保留更相关、更可靠的证据。后检索不是大规模召回的替代品：候选池里没有正确 Chunk 时，重排无法凭空找回它。

将 Cross-Encoder 用于“原问题 + 单个候选 Chunk”的相关性排序时，应先限制为有限 Top-N 候选。最终回答必须保留实际使用的证据标识；只记录模型生成的自然语言答案，无法定位错误来自召回、排序还是生成。"""),
  ("09-pgvector-hnsw-filter.md", "https://github.com/pgvector/pgvector#filtering", """# pgvector：HNSW 与过滤条件

HNSW 是近似最近邻索引，常以部分召回换取更低检索延迟。提高 `hnsw.ef_search` 通常会让查询考察更多候选，可能提高召回，同时增加查询代价。

带元数据过滤时，近似索引可能先返回候选再应用过滤，从而得到不足的结果。可结合更大的候选规模、调整 `hnsw.ef_search` 或使用迭代扫描；是否有效必须在固定 Gold 上同时观察 Recall@K 与延迟，不能只根据单次回答是否流畅判断。"""),
  ("10-pgvector-hybrid-rrf.md", "https://github.com/pgvector/pgvector#hybrid-search", """# pgvector：混合搜索与 RRF

向量召回适合语义相近表达，关键词或全文召回补充精确术语、编号、命令与代码标识。两路原始分数的量纲通常不同，不能简单相加后声称得到可靠排序。

Reciprocal Rank Fusion（RRF）基于候选在各列表中的名次融合，可先合并覆盖面再交给精排模型。RRF 后的 Cross-Encoder 分数仅用于同一次请求的候选相对排序；不应设定跨问题通用的固定分数阈值。"""),
  ("11-ragas-faithfulness.md", "https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/", """# RAGAS：回答忠实度与上下文精度

Faithfulness 关注回答中的陈述能否从实际检索上下文推出；它不等于回答恰好与参考答案逐字一致。若没有保存实际送入生成模型的完整检索片段，忠实度结果就无法复核。

Context Precision 关注排在前面的检索上下文是否对回答或参考答案有用。它应结合候选排序与证据标识分析：高分不代表没有漏召回，低分也需要区分是候选池噪声还是重排失效。"""),
  ("12-ragas-context-recall.md", "https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/", """# RAGAS：上下文召回与拒答边界

Context Recall 关注参考答案所需事实是否能由检索上下文支持，适合发现“生成模型会答、但关键证据并未被检索到”的风险。它依赖固定参考答案或参考证据，不能替代人工审阅高风险案例。

不可回答问题不应混入可回答样本的 Faithfulness、Context Precision、Context Recall 均值。它们应单独统计：系统是否明确说明缺少依据、是否避免编造事实，以及是否避免给出伪造或无关引用。"""),
]

CASES = [
  ("cross_document", "自调用的事务方法即使标了 REQUIRES_NEW，也没有开启独立事务。应先判断什么，再考虑传播语义？", True, ["03-transaction-proxy-boundary.md", "01-transaction-propagation.md"], "先确认调用是否经过 Spring 事务代理；自调用未经过代理时，REQUIRES_NEW 不会生效。"),
  ("cross_document", "业务想局部回滚但保留外层事务，同时内部方法当前是自调用。正确的排查和设计顺序是什么？", True, ["03-transaction-proxy-boundary.md", "01-transaction-propagation.md"], "先让调用经过代理，再在支持保存点的前提下评估 NESTED 的局部回滚语义。"),
  ("cross_document", "为什么先处理业务再 XACK 仍不能保证恰好一次？", True, ["06-redis-ack-idempotency.md", "05-redis-pending-claim.md"], "先处理后确认避免先确认丢消息，但崩溃或 claim 仍会导致重投，因此业务仍需幂等。"),
  ("cross_document", "Stream 恢复积压时，为什么应先 XPENDING 再按空闲时长 XAUTOCLAIM？", True, ["05-redis-pending-claim.md", "06-redis-ack-idempotency.md"], "XPENDING 先观察 PEL；过早 XAUTOCLAIM 可能接管仍在处理的消息并造成重复执行业务。"),
  ("cross_document", "查询改写和 Cross-Encoder 重排分别应使用什么问题文本，原因是什么？", True, ["07-spring-ai-query-transform.md", "08-spring-ai-post-retrieval.md"], "改写用于扩大召回候选；重排应使用原问题与候选 Chunk 配对，避免改写丢失约束。"),
  ("cross_document", "为什么重排不能弥补关键词和向量两路都没有召回到正确 Chunk 的问题？", True, ["08-spring-ai-post-retrieval.md", "10-pgvector-hybrid-rrf.md"], "重排只能排序已有候选；应先通过两路召回和 RRF 提高候选覆盖。"),
  ("cross_document", "HNSW 过滤后结果不足时，为什么不能只把 ef_search 调大就宣布优化成功？", True, ["09-pgvector-hnsw-filter.md", "11-ragas-faithfulness.md"], "ef_search 可能提高召回但增加成本；应在固定 Gold 上同时验证 Recall@K、延迟和证据质量。"),
  ("cross_document", "要证明一次 RAG 回答可信，除了答案文本还至少要保留哪两类可追溯信息？", True, ["08-spring-ai-post-retrieval.md", "11-ragas-faithfulness.md"], "保留实际使用的完整检索片段及其 Chunk 标识，并记录回答对应的评测输入或证据。"),
  ("cross_document", "为什么不可回答题不能和可回答题一起计算 RAGAS 三项平均分？", True, ["12-ragas-context-recall.md", "11-ragas-faithfulness.md"], "三项指标依赖可核对的参考事实和上下文；不可回答题应独立评估拒答与不伪造引用。"),
  ("cross_document", "REQUIRES_NEW 为什么既不能替代 Outbox，也需要关注连接池容量？", True, ["01-transaction-propagation.md", "06-redis-ack-idempotency.md"], "独立事务不保证数据库与消息投递时序，且可能额外占用连接；仍需可靠投递与幂等消费设计。"),
  ("constraint_reasoning", "内层 REQUIRED 捕获异常后将事务标记 rollback-only，外层继续正常返回并提交，会出现什么现象？", True, ["01-transaction-propagation.md"], "共享物理事务会在外层提交点实际回滚，并可能抛出 UnexpectedRollbackException。"),
  ("constraint_reasoning", "需要让受检异常触发回滚，但又不想影响其他异常的默认行为，应怎样配置？", True, ["02-transaction-rollback-rules.md"], "在 @Transactional 中针对该受检异常显式配置 rollbackFor，而不是假定所有受检异常默认回滚。"),
  ("constraint_reasoning", "异常被事务方法内部捕获并正常返回，但业务仍必须回滚。仅依赖默认规则是否足够？", True, ["02-transaction-rollback-rules.md"], "不够；没有向代理传播的异常可供默认规则判断，应显式标记 rollback-only 或重新抛出异常。"),
  ("constraint_reasoning", "同一消费者重启后想先补处理它自己的 pending 消息，应使用 > 还是具体 ID？", True, ["04-redis-xreadgroup.md"], "使用具体 ID；> 只读取从未投递的新消息。"),
  ("constraint_reasoning", "为了降低重复执行，把 XAUTOCLAIM 的最小空闲时间设得极小是否合理？", True, ["05-redis-pending-claim.md"], "不合理；原消费者可能仍在处理，过早接管会增加并发重复执行风险。"),
  ("constraint_reasoning", "需要精确命令名和语义改写都尽量不漏时，两路召回的融合规则应避免什么？", True, ["10-pgvector-hybrid-rrf.md"], "避免直接相加不同量纲的原始分数，应按名次使用 RRF 等融合方式。"),
  ("constraint_reasoning", "重排服务返回 0.72 时，能否把 0.7 作为所有问题的统一通过阈值？", True, ["10-pgvector-hybrid-rrf.md"], "不能；重排分数只适合同一次请求中候选的相对比较。"),
  ("constraint_reasoning", "RAGAS Faithfulness 很高，但 Context Recall 很低，最可能说明哪类问题？", True, ["11-ragas-faithfulness.md", "12-ragas-context-recall.md"], "已生成的回答大多受现有上下文支撑，但参考答案所需的关键事实仍有漏召回。"),
  ("constraint_reasoning", "排查事务失效时，为什么不能先讨论 rollbackFor？", True, ["03-transaction-proxy-boundary.md", "02-transaction-rollback-rules.md"], "调用未经过代理时事务规则根本不会被应用，应先确认代理边界。"),
  ("constraint_reasoning", "过滤条件导致 HNSW 命中不足时，应该同时记录哪两个量化结果？", True, ["09-pgvector-hnsw-filter.md"], "Recall@K 和检索延迟。"),
  ("distractor_disambiguation", "哪个 Redis 命令只观察 PEL 而不改变消息 owner？", True, ["05-redis-pending-claim.md"], "XPENDING。"),
  ("distractor_disambiguation", "XREADGROUP 里 >、0 和 XACK 分别不是同一类动作；其中哪个表示读取从未投递的新消息？", True, ["04-redis-xreadgroup.md"], ">。"),
  ("distractor_disambiguation", "XACK 的直接效果是创建新消费者、转移 owner，还是把消息移出某消费组 PEL？", True, ["06-redis-ack-idempotency.md"], "把消息移出指定消费组的 PEL。"),
  ("distractor_disambiguation", "RAG 链路中“原问题 + 单个候选 Chunk”应由查询改写器还是 Cross-Encoder 处理？", True, ["08-spring-ai-post-retrieval.md"], "Cross-Encoder 重排器。"),
  ("distractor_disambiguation", "RRF 融合时使用的主要信息是向量原始分、全文原始分，还是候选名次？", True, ["10-pgvector-hybrid-rrf.md"], "候选在各检索列表中的名次。"),
  ("distractor_disambiguation", "哪项 RAGAS 指标直接关心回答陈述能否从实际检索上下文推出？", True, ["11-ragas-faithfulness.md"], "Faithfulness。"),
  ("distractor_disambiguation", "哪项 RAGAS 指标用于发现参考答案所需事实没有被检索上下文支持？", True, ["12-ragas-context-recall.md"], "Context Recall。"),
  ("distractor_disambiguation", "PROPAGATION_REQUIRED 在存在外层事务时是挂起外层、加入外层，还是创建保存点？", True, ["01-transaction-propagation.md"], "加入外层事务。"),
  ("distractor_disambiguation", "默认声明式事务对哪类异常通常自动回滚：受检异常，还是未处理 RuntimeException/Error？", True, ["02-transaction-rollback-rules.md"], "未处理的 RuntimeException 或 Error。"),
  ("distractor_disambiguation", "自调用事务失效首先是传播级别选错，还是调用未经过代理？", True, ["03-transaction-proxy-boundary.md"], "调用未经过代理。"),
  ("boundary_refusal", "这套资料建议把 XAUTOCLAIM 的 min-idle-time 固定为多少毫秒？", False, ["05-redis-pending-claim.md"], "资料没有给出固定毫秒值，无法确认。"),
  ("boundary_refusal", "HNSW 的 ef_search 在这套资料中的默认值是多少？", False, ["09-pgvector-hnsw-filter.md"], "资料没有给出默认值，无法确认。"),
  ("boundary_refusal", "Cross-Encoder 在 Rehevo 当前线上使用的具体模型名称是什么？", False, ["08-spring-ai-post-retrieval.md"], "资料没有给出当前线上模型名称，无法确认。"),
  ("boundary_refusal", "哪些消费者的 pending 消息已经超过业务 SLA？", False, ["04-redis-xreadgroup.md", "05-redis-pending-claim.md"], "资料没有提供实时 PEL 或 SLA 数据，无法确认。"),
  ("boundary_refusal", "该项目的连接池最大连接数是多少，是否足够 REQUIRES_NEW？", False, ["01-transaction-propagation.md"], "资料没有提供连接池配置或并发量，无法确认。"),
  ("boundary_refusal", "本知识库中的所有受检异常是否都配置了 rollbackFor？", False, ["02-transaction-rollback-rules.md"], "资料只说明可显式配置规则，没有列出项目全部配置，无法确认。"),
  ("boundary_refusal", "RAGAS Faithfulness 达到多少才允许发布？", False, ["11-ragas-faithfulness.md"], "资料没有定义发布阈值，无法确认。"),
  ("boundary_refusal", "Context Recall 当前基线分数是多少？", False, ["12-ragas-context-recall.md"], "资料没有提供当前跑分，无法确认。"),
  ("boundary_refusal", "这套资料是否证明 Redis Stream 可以端到端恰好一次投递？", False, ["06-redis-ack-idempotency.md"], "不能；资料说明消费组是至少一次语义，需要业务幂等。"),
  ("boundary_refusal", "过滤后 HNSW 应该一定使用哪一种迭代扫描模式？", False, ["09-pgvector-hnsw-filter.md"], "资料只列出可选优化方向，没有规定唯一模式，无法确认。"),
]


def sha256(path: Path) -> str:
  return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
  CORPUS_DIR.mkdir(parents=True, exist_ok=True)
  hashes = {}
  sources = []
  for filename, source, content in DOCUMENTS:
    path = CORPUS_DIR / filename
    path.write_text(content + "\n", encoding="utf-8", newline="\n")
    digest = sha256(path)
    hashes[filename] = digest
    sources.append({"filename": filename, "sha256": digest, "source": source, "chunkIndex": 0})

  rows = []
  for index, (category, question, answerable, document_names, answer) in enumerate(CASES, start=1):
    expected = [] if not answerable else [
      {"documentSha256": hashes[name], "chunkIndex": 0} for name in document_names
    ]
    rows.append({
      "id": f"rehevo-gold-v2-hard-{index:03d}",
      "split": "test",
      "category": category,
      "question": question,
      "answerable": answerable,
      "expectedChunkRefs": expected,
      "referenceAnswer": answer,
      "sourceDocumentHashes": [hashes[name] for name in document_names],
      "reviewStatus": "reviewed",
    })

  MANIFEST_PATH.write_text(
    json.dumps({"version": "v2-hard", "documents": sources}, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8", newline="\n"
  )
  DATASET_PATH.write_text(
    "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows),
    encoding="utf-8", newline="\n"
  )
  print(json.dumps({"documents": len(DOCUMENTS), "cases": len(rows), "dataset": str(DATASET_PATH)}, ensure_ascii=False))


if __name__ == "__main__":
  main()
