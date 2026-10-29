"""NexusMind 离线评测模块（Week 8 切片 5）。

与切片 4 的在线内联 LLM-as-Judge（Java 侧 `JudgeResult`/`JudgeRequest`）共享
评分语义：综合分 `score` ∈ [0,1]，分项分 `dimensions` 维度名 → [0,1]。
本包是纯 Python、独立于 Maven reactor 的离线评测工具。
"""

from .judge_contract import (
    JudgeRequest,
    JudgeResult,
    clamp_score,
    validate_judge_result,
)
from .loaders import iter_faithfulness, iter_socratic

__all__ = [
    "JudgeRequest",
    "JudgeResult",
    "clamp_score",
    "validate_judge_result",
    "iter_faithfulness",
    "iter_socratic",
]
