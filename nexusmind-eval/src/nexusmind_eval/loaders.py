"""数据集加载与校验（Week 8 切片 5）。

数据集约定为 JSON 对象：
    {"meta": {...}, "cases": [ {...}, ... ]}

- `faithfulness_sample.json`：RAG 忠实度 / 上下文相关度标注
- `socratic_sample.json`：Tutor 苏格拉底提问深度标注（维度对齐切片 4 tutor rubric）

加载与校验均为纯 Python，不依赖 deepeval，可被 `tests/test_contract.py` 直接调用。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List

from .judge_contract import VALID_TASK_TYPES

DATASET_DIR = Path(__file__).resolve().parent.parent.parent / "datasets"


def _load(name: str) -> Dict:
    path = DATASET_DIR / name
    if not path.exists():
        raise FileNotFoundError(f"数据集不存在: {path}")
    with open(path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    if not isinstance(data, dict) or "cases" not in data:
        raise ValueError(f"数据集 {name} 必须是 {{'meta':..., 'cases':[...]}} 结构")
    return data


def load_dataset(name: str) -> Dict:
    return _load(name)


def iter_faithfulness() -> List[Dict]:
    return _load("faithfulness_sample.json")["cases"]


def iter_socratic() -> List[Dict]:
    return _load("socratic_sample.json")["cases"]


def validate_faithfulness_record(rec: Dict) -> None:
    required = {"id", "task_type", "query", "retrieval_context", "actual_output"}
    missing = required - rec.keys()
    if missing:
        raise ValueError(f"faithfulness 记录 {rec.get('id')} 缺字段: {missing}")
    if rec["task_type"] not in VALID_TASK_TYPES:
        raise ValueError(f"bad task_type: {rec['task_type']}")
    if not isinstance(rec["retrieval_context"], list):
        raise ValueError(f"retrieval_context 必须是数组: id={rec.get('id')}")


def validate_socratic_record(rec: Dict) -> None:
    required = {"id", "task_type", "input", "actual_output"}
    missing = required - rec.keys()
    if missing:
        raise ValueError(f"socratic 记录 {rec.get('id')} 缺字段: {missing}")
    if rec["task_type"] != "tutor":
        raise ValueError(f"socratic 记录 task_type 必须为 'tutor'，实为 {rec['task_type']}")
