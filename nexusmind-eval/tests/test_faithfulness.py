"""DeepEval 离线 RAG 忠实度 / 上下文相关度评测（Week 8 切片 5）。

依赖 `deepeval`（在 eval venv 中 `pip install -e .` 后可用）；未安装则整模块跳过。
无 LLM 凭证时跳过（避免无密钥误报失败）；有凭证且低于阈值则失败（CI 门禁）。
"""

import os

import pytest

pytest.importorskip(
    "deepeval",
    reason="需要 deepeval，请在 eval venv 中 `pip install -e .` 后运行",
)

from deepeval.test_case import LLMTestCase

from nexusmind_eval.loaders import iter_faithfulness, validate_faithfulness_record
from nexusmind_eval.metrics import (
    answer_relevancy_metric,
    contextual_precision_metric,
    contextual_recall_metric,
    faithfulness_metric,
)

# 无 LLM 凭证：离线评测无法调用模型，整模块跳过（不视为失败）
if not (os.getenv("OPENAI_API_KEY") or os.getenv("NEXUSMIND_LLM_API_KEY")):
    pytest.skip(
        "无 LLM 凭证（OPENAI_API_KEY / NEXUSMIND_LLM_API_KEY），跳过离线评测",
        allow_module_level=True,
    )


@pytest.fixture(scope="module")
def records():
    recs = iter_faithfulness()
    for r in recs:
        validate_faithfulness_record(r)
    return recs


def test_faithfulness(records):
    """生成答案是否忠实于检索上下文（不得幻觉 / 编造）。"""
    m = faithfulness_metric()
    failures = []
    for r in records:
        case = LLMTestCase(
            input=r["query"],
            actual_output=r["actual_output"],
            retrieval_context=r["retrieval_context"],
        )
        m.measure(case)
        if not m.is_successful():
            failures.append((r["id"], m.score))
    assert not failures, f"faithfulness 低于阈值: {failures}"


def test_contextual_precision(records):
    """检索上下文对生成答案的相关度。"""
    m = contextual_precision_metric()
    failures = []
    for r in records:
        case = LLMTestCase(
            input=r["query"],
            actual_output=r["actual_output"],
            retrieval_context=r["retrieval_context"],
        )
        m.measure(case)
        if not m.is_successful():
            failures.append((r["id"], m.score))
    assert not failures, f"contextual precision 低于阈值: {failures}"


def test_contextual_recall(records):
    """检索上下文对『标准答案』的覆盖度（需 expected_output 字段）。"""
    m = contextual_recall_metric()
    failures = []
    for r in records:
        if "expected_output" not in r:
            continue
        case = LLMTestCase(
            input=r["query"],
            actual_output=r["expected_output"],
            retrieval_context=r["retrieval_context"],
        )
        m.measure(case)
        if not m.is_successful():
            failures.append((r["id"], m.score))
    assert not failures, f"contextual recall 低于阈值: {failures}"


def test_answer_relevancy(records):
    """生成答案与用户 query 的相关度。"""
    m = answer_relevancy_metric()
    failures = []
    for r in records:
        case = LLMTestCase(
            input=r["query"],
            actual_output=r["actual_output"],
            retrieval_context=r["retrieval_context"],
        )
        m.measure(case)
        if not m.is_successful():
            failures.append((r["id"], m.score))
    assert not failures, f"answer relevancy 低于阈值: {failures}"
