#!/usr/bin/env python3
"""将 v2-hard 按类别交替冻结为等量 dev/test，并输出可校验清单。"""

from __future__ import annotations

import hashlib
import json
import subprocess
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "rehevo-gold-v2-hard.jsonl"
DEV = ROOT / "rehevo-gold-v2-hard-dev.jsonl"
TEST = ROOT / "rehevo-gold-v2-hard-test.jsonl"
MANIFEST = ROOT / "hard-v2-splits.manifest.json"


def sha256(path: Path) -> str:
  digest = hashlib.sha256()
  with path.open("rb") as source:
    for block in iter(lambda: source.read(65536), b""):
      digest.update(block)
  return digest.hexdigest()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
  return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_jsonl(path: Path, cases: list[dict[str, Any]]) -> None:
  content = "\n".join(json.dumps(case, ensure_ascii=False, separators=(",", ":")) for case in cases) + "\n"
  path.write_text(content, encoding="utf-8", newline="\n")


def source_revision() -> str | None:
  try:
    return subprocess.check_output(
      ["git", "log", "-1", "--format=%H", "--", SOURCE.name],
      cwd=ROOT,
      text=True,
    ).strip() or None
  except (OSError, subprocess.CalledProcessError):
    return None


def summarize(cases: list[dict[str, Any]]) -> dict[str, Any]:
  return {
    "cases": len(cases),
    "answerable": sum(bool(case["answerable"]) for case in cases),
    "unanswerable": sum(not bool(case["answerable"]) for case in cases),
    "categories": dict(sorted(Counter(case["category"] for case in cases).items())),
  }


def main() -> int:
  cases = read_jsonl(SOURCE)
  if len(cases) != 40 or len({case["id"] for case in cases}) != 40:
    raise ValueError("v2-hard 必须包含 40 个唯一 ID")

  by_category: dict[str, list[dict[str, Any]]] = defaultdict(list)
  for case in cases:
    by_category[case["category"]].append(case)
  if set(by_category) != {
    "cross_document", "constraint_reasoning", "distractor_disambiguation", "boundary_refusal"
  } or any(len(group) != 10 for group in by_category.values()):
    raise ValueError("v2-hard 必须保持四类各 10 条")

  splits: dict[str, list[dict[str, Any]]] = {"dev": [], "test": []}
  for category in sorted(by_category):
    for index, original in enumerate(sorted(by_category[category], key=lambda case: case["id"])):
      split = "dev" if index % 2 == 0 else "test"
      case = dict(original)
      case["split"] = split
      splits[split].append(case)
  for split in splits:
    splits[split].sort(key=lambda case: case["id"])

  write_jsonl(DEV, splits["dev"])
  write_jsonl(TEST, splits["test"])
  manifest = {
    "version": "hard-v2-stratified-alternating-v1",
    "rule": "sort each category by id; odd position -> dev; even position -> test",
    "source": {
      "path": SOURCE.name,
      "sha256": sha256(SOURCE),
      "gitRevision": source_revision(),
    },
    "dev": {"path": DEV.name, "sha256": sha256(DEV), **summarize(splits["dev"])},
    "test": {"path": TEST.name, "sha256": sha256(TEST), **summarize(splits["test"])},
    "contract": {
      "tuneOn": "dev",
      "evaluateOnceOn": "test",
      "rewriteDefault": False,
      "primaryMetrics": ["Recall@5", "MRR@10", "nDCG@10"],
    },
  }
  MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
  print(json.dumps(manifest, ensure_ascii=False, indent=2))
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
