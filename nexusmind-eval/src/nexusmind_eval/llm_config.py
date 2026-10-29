"""自托管 LLM 桥接配置（可选，Week 8 切片 5）。

让离线 DeepEval 评测打到与切片 3/4 在线 judge 相同的自托管模型
（项目 `LLMClient` 使用可配置 base_url / api_key）。

本文件【不】被测试直接依赖；仅当希望离线评测复用自托管模型时才手动启用。
deepeval 默认走 OpenAI，若要切换为自托管 OpenAI 兼容端点，按下方模板把自定义
LLM 接入 `deepeval.set_llm(...)`（详见 README「自定义自托管 LLM」一节）。

本模块仅做环境读取 + OpenAI 客户端工厂，不 import deepeval，纯 Python 可单测。
"""

from __future__ import annotations

import os
from typing import Dict


def load_llm_env() -> Dict[str, str]:
    """读取自托管 LLM 环境变量，默认值对齐项目 LLMClient 约定。"""
    return {
        "base_url": os.getenv("NEXUSMIND_LLM_BASE_URL", "http://localhost:8080/v1"),
        "api_key": os.getenv("NEXUSMIND_LLM_API_KEY", "not-set"),
        "model": os.getenv("NEXUSMIND_LLM_MODEL", "default"),
    }


def build_openai_client():
    """构造 OpenAI 兼容客户端（自托管端点）。需 `openai` 已安装。"""
    try:
        from openai import OpenAI
    except ImportError as exc:  # pragma: no cover
        raise RuntimeError("openai 未安装，请先 pip install openai") from exc
    env = load_llm_env()
    return OpenAI(base_url=env["base_url"], api_key=env["api_key"])


# ---- 自定义 deepeval LLM 接入模板（按需启用，非必需）--------------------------
#
# from deepeval.models.base_model import DeepEvalBaseLLM
# from openai import OpenAI
#
# class NexusMindLLM(DeepEvalBaseLLM):
#     def __init__(self):
#         env = load_llm_env()
#         self.model = env["model"]
#         self.client = OpenAI(base_url=env["base_url"], api_key=env["api_key"])
#
#     def load_model(self):
#         return self.client
#
#     def generate(self, prompt: str) -> str:
#         r = self.client.chat.completions.create(
#             model=self.model, messages=[{"role": "user", "content": prompt}]
#         )
#         return r.choices[0].message.content
#
#     async def a_generate(self, prompt: str) -> str:
#         return self.generate(prompt)
#
#     # generate_with_response / a_generate_with_response 见 deepeval 文档，按需实现
#
# def configure():
#     import deepeval
#     deepeval.set_llm(NexusMindLLM())
