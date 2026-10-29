# NexusMind 离线评测模块（`nexusmind-eval`）

> Week 8 切片 5：离线 **DeepEval** 批处理评测。与切片 4 的**在线内联 LLM-as-Judge**（Java 侧 `JudgeResult`/`JudgeRequest`）互补——
> 在线分用于实时质量感知与 Langfuse trace 下钻；离线分用于**分布级 / 回归级评测与发布门禁**。

---

## 0. 架构红线（reality-check 铁律）

- **绝不进 Maven reactor**：DeepEval 是 Python-only，**不向任何 `pom.xml` 的 `<modules>` 注入依赖**。本目录与 6 个 Maven 模块平级，Maven 全量构建完全感知不到它的存在。
- **独立 venv**：不共享本机 Java/Maven 工具链，venv 自管，零污染。
- **共享评分语义**：在线（切片 4）与离线（本模块）的 `score` ∈ [0,1]、`dimensions` 维度命名（faithfulness / depth / cognitive_conflict / guidance …）保持一致，可交叉对照。

---

## 1. 目录结构

```
nexusmind-eval/
├── pyproject.toml          # 仅声明 deepeval + openai + pytest（requires-python 3.9~3.11）
├── README.md
├── .gitignore              # Python 专属忽略（不影响根 .gitignore 的 Java 规则）
├── datasets/
│   ├── faithfulness_sample.json   # RAG 忠实度 / 上下文相关度标注（SEED）
│   └── socratic_sample.json       # Tutor 苏格拉底深度标注（SEED，对齐切片 4 tutor rubric）
├── src/nexusmind_eval/
│   ├── judge_contract.py   # 切片 4 契约的纯 Python 镜像（clamp / 容错解析，可单测）
│   ├── loaders.py          # 数据集加载 + 结构校验
│   ├── metrics.py          # DeepEval 指标构造（阈值即门禁）
│   └── llm_config.py       # 自托管 LLM 桥接（可选，含 deepeval.set_llm 模板）
├── tests/
│   ├── test_contract.py            # 纯 Python，不依赖 deepeval，可立即运行
│   ├── test_faithfulness.py        # DeepEval：Faithfulness / Contextual P-R / Answer Relevancy
│   └── test_socratic_geval.py      # DeepEval G-Eval：苏格拉底深度
├── report/                 # 评测报告输出（CI artifact，gitignore）
└── .github/workflows/offline-eval.yml   # 可选 CI 门禁
```

---

## 2. 环境搭建

```bash
cd nexusmind-eval
python3.11 -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install --upgrade pip
pip install -e ".[dev]"
```

> 推荐 Python 3.9~3.11（DeepEval 兼容性区间）。本机若只有 3.13 / 3.8，请另装 3.11 以避免 DeepEval 依赖冲突。

---

## 3. 运行

### 3.1 纯 Python 契约测试（**无需 LLM / deepeval**，推荐先跑）
验证数据集合法性、clamp 语义、容错解析：

```bash
pytest tests/test_contract.py
```

### 3.2 离线 DeepEval 评测（需 LLM 凭证）
DeepEval 默认走 OpenAI；提供以下任一凭证即可：

```bash
export OPENAI_API_KEY=sk-...                 # 走官方 OpenAI
# 或指向与切片 3/4 在线 judge 相同的自托管模型：
export NEXUSMIND_LLM_BASE_URL=http://host:8080/v1
export NEXUSMIND_LLM_API_KEY=xxx
export NEXUSMIND_LLM_MODEL=your-model

pytest tests/test_faithfulness.py tests/test_socratic_geval.py
```

> 无 LLM 凭证时，DeepEval 测试会**自动跳过**（不视为失败）；有凭证且低于阈值则**失败**（即 CI 门禁生效）。

### 3.3 自定义自托管 LLM（可选）
若要让离线评测打到与在线 judge 相同的自托管模型，编辑 `src/nexusmind_eval/llm_config.py`，
取消模板中 `NexusMindLLM` / `configure()` 的注释并在运行前调用：

```python
from nexusmind_eval.llm_config import configure
configure()   # deepeval.set_llm(NexusMindLLM())
```

---

## 4. 评测维度与阈值（门禁）

| 测试 | 指标 | 默认阈值 | 对齐切片 4 语义 |
|---|---|---|---|
| `test_faithfulness` | Faithfulness（忠实度） | 0.80 | RAG 不得幻觉 |
| `test_contextual_precision` | Contextual Precision | 0.70 | 检索上下文相关度 |
| `test_contextual_recall` | Contextual Recall | 0.70 | 上下文覆盖标准答案 |
| `test_answer_relevancy` | Answer Relevancy | 0.80 | 答案与 query 相关 |
| `test_socratic_depth` | G-Eval（深度/冲突/引导） | 0.60 | tutor rubric 三维度 |

阈值可在 `src/nexusmind_eval/metrics.py` 中调整。

---

## 5. 数据集扩充

当前 `datasets/*.json` 为 **SEED 种子样本**（手工构造、领域代表性）。生产化路径：

1. 从切片 3 的 Langfuse 导出 traces（或 supervisor 落库的 `EvolutionScanState` + `quality:<taskId>`）抽取真实 (query, context, output) 三元组。
2. 人工补 `expected_faithful` / `reference_depth_score` 标注。
3. 逐步从 5~10 条扩到数百条带标注集，作为回归基线。

`datasets/*.json` 约定结构：

```json
{ "meta": { "...": "..." }, "cases": [ { "id": "...", ... }, ... ] }
```

---

## 6. CI 门禁（可选）

`.github/workflows/offline-eval.yml` 提供 `workflow_dispatch`（手动）+ 每周一定时两种触发，
安装本模块后运行 DeepEval 测试，产物上传到 `report/`。需在仓库 Secrets 配置：

- `EVAL_OPENAI_API_KEY` 或 `EVAL_LLM_BASE_URL` / `EVAL_LLM_API_KEY` / `EVAL_LLM_MODEL`

> 建议作为**发版前 / 每日**低频卡点，不随线上流量放大成本。

---

## 7. 与切片 4 的衔接

- 在线（切片 4）：任务完成后一次 LLM 调用 → `JudgeResult`（score 0~1 + dimensions）→ 落 Redis + Langfuse `score.create`，实时、轻量、best-effort。
- 离线（切片 5，本模块）：对标注集跑 DeepEval 分布/回归评测 → 报告 + CI 门禁，低频、严格。
- 两者**共享 score / dimensions 命名**，在线异常点可用本模块做根因回归。
