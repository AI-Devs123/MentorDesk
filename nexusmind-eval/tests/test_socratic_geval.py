"""DeepEval G-Eval 评估 Tutor 苏格拉底提问深度（Week 8 切片 5）。

维度与切片 4 `QualityJudgePrompt.rubricFor("tutor")` 对齐：
    提问深度 / 认知冲突张力 / 引导性。
`reference_depth_score` 为人工标注锚点（不参与自动评分，仅用于离线对照）。
"""

import os

import pytest

pytest.importorskip(
    "deepeval",
    reason="需要 deepeval，请在 eval venv 中 `pip install -e .` 后运行",
)

from deepeval.test_case import LLMTestCase

from nexusmind_eval.loaders import iter_socratic, validate_socratic_record
from nexusmind_eval.metrics import socratic_geval_metric

if not (os.getenv("OPENAI_API_KEY") or os.getenv("NEXUSMIND_LLM_API_KEY")):
    pytest.skip(
        "无 LLM 凭证（OPENAI_API_KEY / NEXUSMIND_LLM_API_KEY），跳过离线评测",
        allow_module_level=True,
    )


@pytest.fixture(scope="module")
def records():
    recs = iter_socratic()
    for r in recs:
        validate_socratic_record(r)
    return recs


def test_socratic_depth(records):
    """G-Eval 综合三维度给苏格拉底提问质量打分，低于阈值即判失败。"""
    m = socratic_geval_metric()
    failures = []
    for r in records:
        case = LLMTestCase(
            input=r["input"],
            actual_output=r["actual_output"],
        )
        m.measure(case)
        if not m.is_successful():
            failures.append(
                (r["id"], m.score, r.get("reference_depth_score"))
            )
    assert not failures, f"苏格拉底深度低于阈值(含人工锚点): {failures}"
