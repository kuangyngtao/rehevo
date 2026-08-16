#!/usr/bin/env python3
"""下载并冻结 T2Ranking dev 的固定 200 条外部检索评测样本。

只依赖 Python 标准库。完整 collection.tsv 体积较大，故本脚本只缓存
queries.dev.tsv 与 qrels.dev.tsv；候选文档语料由后续的重排评测阶段按同一
revision 另行物化，不允许用不同版本的数据替换本清单。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


DATASET = "THUIR/T2Ranking"
DEFAULT_REVISION = "2a369a430a70979223f1b9a41b1919774d46b432"
BASE_URL = "https://huggingface.co/datasets/THUIR/T2Ranking/resolve/{revision}/data/{name}"


def sha256_file(path: Path) -> str:
  digest = hashlib.sha256()
  with path.open("rb") as file:
    for block in iter(lambda: file.read(1024 * 1024), b""):
      digest.update(block)
  return digest.hexdigest()


def download(url: str, destination: Path) -> None:
  destination.parent.mkdir(parents=True, exist_ok=True)
  temporary = destination.with_name(destination.name + ".part")
  temporary.unlink(missing_ok=True)
  request = urllib.request.Request(url, headers={"User-Agent": "Rehevo-RAG-Evaluation/1.0"})
  try:
    with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
      while chunk := response.read(1024 * 1024):
        output.write(chunk)
    temporary.replace(destination)
  finally:
    temporary.unlink(missing_ok=True)


def read_queries(path: Path) -> dict[int, str]:
  queries: dict[int, str] = {}
  with path.open("r", encoding="utf-8") as file:
    header = file.readline().rstrip("\n").split("\t")
    if header != ["qid", "text"]:
      raise ValueError(f"queries.dev.tsv header invalid: {header}")
    for line_number, line in enumerate(file, start=2):
      qid, separator, text = line.rstrip("\n").partition("\t")
      if not separator or not text:
        raise ValueError(f"queries.dev.tsv line {line_number} invalid")
      parsed_qid = int(qid)
      if parsed_qid in queries:
        raise ValueError(f"duplicate qid: {parsed_qid}")
      queries[parsed_qid] = text
  return queries


def read_qrels(path: Path) -> dict[int, list[tuple[int, int]]]:
  qrels: dict[int, list[tuple[int, int]]] = {}
  with path.open("r", encoding="utf-8") as file:
    header = file.readline().rstrip("\n").split("\t")
    if header != ["qid", "-", "pid", "rel"]:
      raise ValueError(f"qrels.dev.tsv header invalid: {header}")
    for line_number, line in enumerate(file, start=2):
      parts = line.rstrip("\n").split("\t")
      if len(parts) != 4:
        raise ValueError(f"qrels.dev.tsv line {line_number} invalid")
      qid, _, pid, relevance = parts
      qrels.setdefault(int(qid), []).append((int(pid), int(relevance)))
  return qrels


def qid_sha256(qids: list[int]) -> str:
  canonical = "\n".join(str(qid) for qid in qids) + "\n"
  return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def main() -> int:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--target-cases", type=int, default=200)
  parser.add_argument("--seed", type=int, default=20260815)
  parser.add_argument("--revision", default=DEFAULT_REVISION)
  parser.add_argument("--cache-dir", type=Path, default=Path("data/local/t2ranking"))
  parser.add_argument("--manifest-path", type=Path, default=Path("external/t2ranking-200.manifest.json"))
  parser.add_argument("--refresh", action="store_true", help="重新下载源文件")
  arguments = parser.parse_args()

  if arguments.target_cases <= 0:
    raise ValueError("target-cases must be positive")

  cache_dir = arguments.cache_dir / arguments.revision
  source_files = {
    "queries.dev.tsv": cache_dir / "queries.dev.tsv",
    "qrels.dev.tsv": cache_dir / "qrels.dev.tsv",
  }
  for name, path in source_files.items():
    if arguments.refresh or not path.exists():
      print(f"downloading {name}", file=sys.stderr)
      download(BASE_URL.format(revision=arguments.revision, name=name), path)

  queries = read_queries(source_files["queries.dev.tsv"])
  qrels = read_qrels(source_files["qrels.dev.tsv"])
  eligible_qids = sorted(qid for qid in queries if qid in qrels)
  if len(eligible_qids) < arguments.target_cases:
    raise ValueError(f"only {len(eligible_qids)} qids have judgments")

  selected_qids = sorted(random.Random(arguments.seed).sample(eligible_qids, arguments.target_cases))
  relevance_counts = Counter(relevance for qid in selected_qids for _, relevance in qrels[qid])
  manifest = {
    "dataset": DATASET,
    "license": "Apache-2.0",
    "purpose": "中文候选排序与Cross-Encoder重排的外部回归，不用于端到端求职问答结论",
    "source": "https://huggingface.co/datasets/THUIR/T2Ranking",
    "split": "dev",
    "selection": {
      "method": "按有相关性标注的qid升序后，使用Python MT19937固定种子无放回抽样，再按qid升序保存",
      "seed": arguments.seed,
      "targetCases": arguments.target_cases,
      "status": "IMPORTED",
    },
    "datasetRevision": arguments.revision,
    "sourceFiles": {
      name: {"sha256": sha256_file(path), "localPath": str(path).replace("\\", "/")}
      for name, path in source_files.items()
    },
    "selectedQidSha256": qid_sha256(selected_qids),
    "selectedQids": selected_qids,
    "selectionStats": {
      "eligibleQids": len(eligible_qids),
      "judgedPairs": sum(len(qrels[qid]) for qid in selected_qids),
      "relevanceGradeCounts": {str(key): relevance_counts[key] for key in sorted(relevance_counts)},
    },
    "importedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
  }

  arguments.manifest_path.parent.mkdir(parents=True, exist_ok=True)
  arguments.manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
  print(json.dumps({"selectedCases": len(selected_qids), "selectedQidSha256": manifest["selectedQidSha256"], "cacheDir": str(cache_dir)}, ensure_ascii=False))
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
