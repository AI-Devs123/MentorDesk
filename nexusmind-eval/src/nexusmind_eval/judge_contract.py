"""切片 4 在线评分契约的纯 Python 镜像（Week 8 切片 5）。

严格对齐 `nexusmind-api` 中 `JudgeResult` / `JudgeRequest` 的语义：
- `score` 与 `dimensions` 均为 0~1，越界由本层 clamp（与 Java 侧 `QualityJudgeServiceImpl`
  的 parse/clamp 行为一致）。
- 脏数据（非数值 / 缺失 / NaN）→ 降级为 0.0，绝不抛异常，与在线评测的 best-effort 铁律一致。

该层不依赖 deepeval 或任何 LLM SDK，可被纯单元测试直接运行验证。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional


# 与切片 4 `KnowledgeEvolutionService.mapTaskType` / `QualityJudgePrompt.rubricFor` 保持一致
VALID_TASK_TYPES: set = {"journal", "code", "web_kb", "tutor"}


def clamp_score(value) -> float:
    """将任意输入收敛到 [0,1]；非数值 / None / NaN 一律降级为 0.0。

    与 Java 侧 `QualityJudgeServiceImpl.parse` 的 clamp 语义对齐：
    越界强制 clamp，脏数据降级 0.0，主链路零感知。
    """
    try:
        f = float(value)
    except (TypeError, ValueError):
        return 0.0
    if f != f:  # NaN
        return 0.0
    if f < 0.0:
        return 0.0
    if f > 1.0:
        return 1.0
    return f


@dataclass
class JudgeResult:
    """镜像 Java `JudgeResult`（score / dimensions / rationale / model）。"""

    score: float
    dimensions: Dict[str, float] = field(default_factory=dict)
    rationale: str = ""
    model: str = "n/a"

    def __post_init__(self) -> None:
        self.score = clamp_score(self.score)
        clean = {}
        for k, v in (self.dimensions or {}).items():
            clean[k] = clamp_score(v)
        self.dimensions = clean

    @classmethod
    def zero(cls, rationale: str = "未评分（降级）") -> "JudgeResult":
        return cls(score=0.0, dimensions={}, rationale=rationale, model="n/a")

    def to_dict(self) -> Dict[str, object]:
        return {
            "score": self.score,
            "dimensions": dict(self.dimensions),
            "rationale": self.rationale,
            "model": self.model,
        }


@dataclass
class JudgeRequest:
    """镜像 Java `JudgeRequest`（task_type / content_to_judge / rubric / context_json）。"""

    task_type: str
    content_to_judge: str
    rubric: str = ""
    context_json: Optional[str] = None


def validate_judge_result(raw: Dict) -> JudgeResult:
    """容错解析 LLM-as-Judge 的 JSON 产出 → `JudgeResult`。

    与 Java 侧逻辑对齐：score 缺失 / 脏 → 0.0；dimensions 逐项 clamp。
    """
    if not isinstance(raw, dict):
        return JudgeResult.zero("非法评测结果（非对象）")
    score = clamp_score(raw.get("score"))
    dims_raw = raw.get("dimensions") or {}
    dimensions: Dict[str, float] = {}
    if isinstance(dims_raw, dict):
        for k, v in dims_raw.items():
            dimensions[str(k)] = clamp_score(v)
    rationale = raw.get("rationale") or ""
    model = raw.get("model") or "n/a"
    return JudgeResult(score=score, dimensions=dimensions, rationale=rationale, model=model)


def task_type_ok(task_type: str) -> bool:
    return task_type in VALID_TASK_TYPES
