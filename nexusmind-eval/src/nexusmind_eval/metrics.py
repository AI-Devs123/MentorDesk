"""DeepEval 指标构造（Week 8 切片 5 离线评测）。

仅在 `deepeval` 已安装时由 `tests/test_faithfulness.py` / `tests/test_socratic_geval.py`
导入。阈值（threshold）即 CI 门禁卡点。

维度命名与切片 4 `QualityJudgePrompt` 的 tutor rubric 对齐：
    提问深度(depth) / 认知冲突张力(cognitive_conflict) / 引导性(guidance)
"""

from __future__ import annotations

from deepeval.metrics import (
    AnswerRelevancyMetric,
    ContextualPrecisionMetric,
    ContextualRecallMetric,
    FaithfulnessMetric,
    GEval,
)
from deepeval.test_case import LLMTestCaseParams

# 与切片 4 QualityJudgePrompt.rubricFor("tutor") 三维度一致
SOCRATIC_CRITERIA = (
    "你是一个教育评测专家。请评估以下『苏格拉底式提问』的质量，综合三个维度给 0~1 分：\n"
    "1. 提问深度(depth)：是否触及核心概念、促使深层思考，而非浅层问答。\n"
    "2. 认知冲突张力(cognitive_conflict)：是否制造认知失衡、暴露学生前概念矛盾。\n"
    "3. 引导性(guidance)：是否在冲突后提供可攀爬的脚手架，而非直接给答案。\n"
    "评分需严格反映上述维度：0 分=机械问答或直接给答案；1 分=高水平苏格拉底对话。"
)


def faithfulness_metric(threshold: float = 0.8) -> FaithfulnessMetric:
    return FaithfulnessMetric(threshold=threshold)


def contextual_precision_metric(threshold: float = 0.7) -> ContextualPrecisionMetric:
    return ContextualPrecisionMetric(threshold=threshold)


def contextual_recall_metric(threshold: float = 0.7) -> ContextualRecallMetric:
    return ContextualRecallMetric(threshold=threshold)


def answer_relevancy_metric(threshold: float = 0.8) -> AnswerRelevancyMetric:
    return AnswerRelevancyMetric(threshold=threshold)


def socratic_geval_metric(threshold: float = 0.6) -> GEval:
    return GEval(
        name="Socratic Depth",
        criteria=SOCRATIC_CRITERIA,
        evaluation_params=[
            LLMTestCaseParams.INPUT,
            LLMTestCaseParams.ACTUAL_OUTPUT,
        ],
        threshold=threshold,
    )
