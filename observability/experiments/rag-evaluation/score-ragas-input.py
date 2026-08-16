#!/usr/bin/env python3
"""对已落盘的 RAGAS 输入分批评分，支持断点续跑。"""

from __future__ import annotations

import argparse
import json
import math
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


METRICS = ("faithfulness", "context_precision", "context_recall")


def read_jsonl(path: Path) -> list[dict[str, Any]]:
  if not path.exists():
    return []
  return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def finite_mean(values: list[Any]) -> float | None:
  finite = [float(value) for value in values if value is not None and math.isfinite(float(value))]
  return sum(finite) / len(finite) if finite else None


def is_complete_score(row: dict[str, Any]) -> bool:
  return all(row.get(metric) is not None and math.isfinite(float(row[metric])) for metric in METRICS)


def write_summary(path: Path, score_rows: list[dict[str, Any]], total_cases: int) -> None:
  complete_rows = [row for row in score_rows if is_complete_score(row)]
  summary = {
    "scores": {metric: finite_mean([row.get(metric) for row in score_rows]) for metric in METRICS},
    "scoredCases": len(complete_rows),
    "totalCases": total_cases,
    "complete": len(complete_rows) == total_cases,
    "updatedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
  }
  path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--input", type=Path, required=True)
  parser.add_argument("--batch-size", type=int, default=5)
  parser.add_argument("--max-batches", type=int, default=0, help="本次最多处理多少批，0 表示直到完成")
  parser.add_argument("--judge-api-key-env", default="RAGAS_JUDGE_API_KEY")
  parser.add_argument("--judge-base-url", default="https://dashscope.aliyuncs.com/compatible-mode/v1")
  parser.add_argument("--judge-model", default="qwen-plus")
  parser.add_argument("--judge-timeout", type=int, default=180)
  parser.add_argument("--judge-workers", type=int, default=4)
  parser.add_argument("--judge-max-tokens", type=int, default=8192)
  arguments = parser.parse_args()
  if arguments.batch_size <= 0 or arguments.max_batches < 0:
    raise ValueError("batch-size 必须为正数，max-batches 不能为负数")

  rows = read_jsonl(arguments.input)
  if not rows:
    raise ValueError("RAGAS 输入为空")
  if len({row["caseId"] for row in rows}) != len(rows):
    raise ValueError("RAGAS 输入包含重复 caseId")
  api_key = os.getenv(arguments.judge_api_key_env)
  if not api_key:
    raise RuntimeError(f"未设置评审模型密钥环境变量: {arguments.judge_api_key_env}")

  from openai import OpenAI
  from ragas import evaluate
  from ragas.dataset_schema import EvaluationDataset, SingleTurnSample
  from ragas.llms import llm_factory
  from ragas.metrics import ContextPrecision, ContextRecall, Faithfulness
  from ragas.run_config import RunConfig

  score_path = arguments.input.parent / "ragas-scores.jsonl"
  summary_path = arguments.input.parent / "summary.json"
  score_rows = read_jsonl(score_path)
  completed_ids = {row["caseId"] for row in score_rows if is_complete_score(row)}
  pending = [row for row in rows if row["caseId"] not in completed_ids]
  if not pending:
    write_summary(summary_path, score_rows, len(rows))
    print(json.dumps({"status": "COMPLETE", "scoredCases": len(score_rows)}, ensure_ascii=False))
    return 0

  judge = llm_factory(
    arguments.judge_model,
    provider="openai",
    client=OpenAI(api_key=api_key, base_url=arguments.judge_base_url),
    temperature=0,
    max_tokens=arguments.judge_max_tokens,
  )
  batches_processed = 0
  for offset in range(0, len(pending), arguments.batch_size):
    if arguments.max_batches and batches_processed >= arguments.max_batches:
      break
    batch_rows = pending[offset:offset + arguments.batch_size]
    samples = [
      SingleTurnSample(
        user_input=row["user_input"],
        retrieved_contexts=row["retrieved_contexts"],
        retrieved_context_ids=row["retrieved_context_ids"],
        reference=row["reference"],
        reference_context_ids=row["reference_context_ids"],
        response=row["response"],
      )
      for row in batch_rows
    ]
    result = evaluate(
      EvaluationDataset(samples=samples),
      metrics=[Faithfulness(), ContextPrecision(), ContextRecall()],
      llm=judge,
      run_config=RunConfig(
        timeout=arguments.judge_timeout,
        max_retries=2,
        max_workers=arguments.judge_workers,
        seed=20260815,
      ),
      raise_exceptions=False,
      show_progress=True,
      experiment_name="rehevo-ragas-gold-resumable",
    )
    new_scores = [
      {"caseId": batch_rows[index]["caseId"], "category": batch_rows[index]["category"], **score}
      for index, score in enumerate(result.scores)
    ]
    replaced_ids = {score["caseId"] for score in new_scores}
    score_rows = [score for score in score_rows if score["caseId"] not in replaced_ids]
    score_rows.extend(new_scores)
    order = {row["caseId"]: index for index, row in enumerate(rows)}
    score_rows.sort(key=lambda score: order[score["caseId"]])
    with score_path.open("w", encoding="utf-8", newline="\n") as output:
      for score in score_rows:
        output.write(json.dumps(score, ensure_ascii=False) + "\n")
    write_summary(summary_path, score_rows, len(rows))
    batches_processed += 1
    print(json.dumps({
      "batchCompleted": batches_processed,
      "scoredCases": sum(is_complete_score(row) for row in score_rows),
      "totalCases": len(rows),
    }, ensure_ascii=False), flush=True)

  scored_cases = sum(is_complete_score(row) for row in score_rows)
  status = "COMPLETE" if scored_cases == len(rows) else "PARTIAL"
  print(json.dumps({"status": status, "scoredCases": scored_cases, "totalCases": len(rows)}, ensure_ascii=False))
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
