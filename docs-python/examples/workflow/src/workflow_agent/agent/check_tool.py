# coding: utf-8
"""最小工具实现：ToolCard（id / 描述 / 输入 JSON Schema）+ 执行函数。

返回 dict；接入 `ToolComponent` 后，下游经 `${check.data.risk}` 引用——非 RESTful
工具的返回被框架包在 `data` 键下。
"""
from __future__ import annotations

from typing import Any

from openjiuwen.core.foundation.tool import LocalFunction, ToolCard

TOOL_ID = "check"

#: 风险阈值。超过即需要人工确认，走 HITL 分支。
RISK_THRESHOLD = 1000


def execute(total: float = 0) -> dict[str, Any]:
    """校验输入并给出风险等级。"""
    return {"risk": "high" if float(total) > RISK_THRESHOLD else "none"}


def create_check_tool() -> LocalFunction:
    card = ToolCard(
        id=TOOL_ID,
        name=TOOL_ID,
        description="校验输入并给出风险等级",
        input_params={
            "type": "object",
            "properties": {"total": {"type": "number", "description": "合计"}},
            "required": ["total"],
        },
    )
    return LocalFunction(card=card, func=execute)
