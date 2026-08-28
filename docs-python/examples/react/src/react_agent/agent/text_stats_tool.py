# coding: utf-8
"""ToolCard（语义契约）+ LocalFunction（本地执行体）。

工具的**元数据与执行函数**属于语义层，**注册进运行资源**属于服务层
（见 `runtime/configuration.py`）。

本模块只依赖 `openjiuwen` agent-core，不依赖 runtime 与 Web 框架。
"""
from __future__ import annotations

from typing import Any

from openjiuwen.core.foundation.tool import LocalFunction, ToolCard

#: 工具标识。ReAct 循环按它选工具，运行资源按它登记执行体，两处必须一致。
TOOL_ID = "text_stats"


def execute(text: str = "") -> dict[str, Any]:
    """统计输入文本的字符数、词数与行数。

    `LocalFunction.invoke` 以 `func(**inputs)` 调用本函数（`function.py` 的
    `LocalFunction.invoke`），因此形参名必须与 `input_params` 的属性名逐字一致。
    """
    chars = len(text)
    words = len(text.split()) if text.strip() else 0
    lines = len(text.splitlines()) if text else 0
    return {"chars": chars, "words": words, "lines": lines}


def create_text_stats_tool() -> LocalFunction:
    """构造工具实例。不在此注册——注册是服务层职责。"""
    card = ToolCard(
        id=TOOL_ID,
        name=TOOL_ID,
        description="统计输入文本的字符数、词数与行数",
        input_params={
            "type": "object",
            "properties": {"text": {"type": "string", "description": "待统计的文本"}},
            "required": ["text"],
        },
    )
    return LocalFunction(card=card, func=execute)
