"""纯 Python 契约测试（Week 8 切片 5）—— 不依赖 deepeval / LLM，可立即运行。

验证：
1. `clamp_score` 与切片 4 Java 侧 clamp 语义一致（越界收敛、脏数据/NaN→0.0）。
2. `validate_judge_result` 容错解析。
3. 两份种子数据集结构合法、字段齐全、task_type 受控。
"""

from nexusmind_eval.judge_contract import (
    JudgeResult,
    clamp_score,
    task_type_ok,
    validate_judge_result,
)
from nexusmind_eval.loaders import (
    iter_faithfulness,
    iter_socratic,
    validate_faithfulness_record,
    validate_socratic_record,
)


def test_clamp_score_bounds():
    assert clamp_score(0.5) == 0.5
    assert clamp_score(0.0) == 0.0
    assert clamp_score(1.0) == 1.0


def test_clamp_score_out_of_range():
    assert clamp_score(-0.3) == 0.0
    assert clamp_score(1.7) == 1.0


def test_clamp_score_dirty_data():
    assert clamp_score("not-a-number") == 0.0
    assert clamp_score(None) == 0.0
    assert clamp_score(float("nan")) == 0.0
    # bool 是 int 子类，float(True)=1.0 → 收敛为 1.0（边界可接受）
    assert clamp_score(True) == 1.0


def test_judge_result_zero():
    z = JudgeResult.zero()
    assert z.score == 0.0
    assert z.model == "n/a"
    assert z.dimensions == {}


def test_judge_result_post_init_clamps_dimensions():
    r = JudgeResult(score=2.0, dimensions={"depth": 5.0, "guidance": -1.0})
    assert r.score == 1.0
    assert r.dimensions["depth"] == 1.0
    assert r.dimensions["guidance"] == 0.0


def test_validate_dirty_json():
    r = validate_judge_result({"score": "abc", "dimensions": {"x": 9}})
    assert r.score == 0.0           # 脏 score → 0.0
    assert r.dimensions["x"] == 1.0  # 合法 dimension 仍被 clamp


def test_validate_missing_fields():
    r = validate_judge_result({})  # 完全缺失
    assert r.score == 0.0
    assert r.model == "n/a"


def test_task_type_ok():
    assert task_type_ok("tutor") is True
    assert task_type_ok("bogus") is False


def test_faithfulness_dataset_valid():
    cases = iter_faithfulness()
    assert len(cases) >= 3
    for rec in cases:
        validate_faithfulness_record(rec)
        assert rec["task_type"] in {"web_kb", "journal"}


def test_socratic_dataset_valid():
    cases = iter_socratic()
    assert len(cases) >= 3
    for rec in cases:
        validate_socratic_record(rec)
        assert rec["task_type"] == "tutor"
        # reference_depth_score 必须在 [0,1]
        assert 0.0 <= float(rec["reference_depth_score"]) <= 1.0
