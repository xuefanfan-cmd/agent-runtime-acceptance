# coding: utf-8
"""交互式 Agent 应用的组合根：真实 runtime REST 工厂 + 确定性交互式 Handler。

使用**真实 runtime REST 工厂** `create_rest_app`（只读引用 runtime，不修改它），
显式传入 `MobileBankChannel()` 与单进程 `MemorySessionStore`。

对 REST 续接语义的关键点（docs/api/agent-runtime-python.md、how-to/interrupt-and-resume.md、
how-to/custom-rest.md）：
- 当前实现必须显式传 `MobileBankChannel()`（工厂契约检查在默认赋值之前）。
- 续接走 `ServeRequest.for_resume` 重走 `stream_query`，不新增 SPI。
- REST 路由：`POST /v1/{project}/agents/{agent}/conversations/{conv}`；SSE 帧
  `data: <信封 JSON>\n\n`，流结束即结束（无哨兵帧）。
- 中断帧投影为 `interrupt_start`（success=True）；终答投影为 `final_answer_chunk`。

运行方式（在本工程根目录，`RUNTIME_ROOT` 指向 runtime 检出）：
    PYTHONPATH=src:$RUNTIME_ROOT \\
        uvicorn interactive_agent.runtime.rest:app --host 127.0.0.1 --port 8090
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Any

from agent_runtime.adapters.inbound.rest.mobile_bank import MobileBankChannel
from agent_runtime.bootstrap.rest_app import create_rest_app

from .handler import InteractiveHandler


class MemorySessionStore:
    """满足 REST 工厂所需最小方法面的单进程会话快照替身（仅单进程验收）。"""

    def __init__(self) -> None:
        self._snapshots: dict[str, dict[str, Any]] = {}

    async def get_request(self, conversation_id: str) -> dict[str, Any] | None:
        return self._snapshots.get(conversation_id)

    async def put_request_if_absent(
        self, conversation_id: str, snapshot: dict[str, Any], *, ttl_s: int
    ) -> bool:
        if conversation_id in self._snapshots:
            return False
        self._snapshots[conversation_id] = dict(snapshot)
        return True


def build_handler() -> InteractiveHandler:
    """构造确定性 Handler（保持可引用实例用于计数断言）。"""
    return InteractiveHandler()


def create_app(handler: InteractiveHandler | None = None):
    """装配一个新的 REST app（真实 runtime 工厂）。

    每次调用得到独立的 handler 与 session store，避免跨用例状态串扰
    （REST 等待续接登记表是进程内、按 conversation 记的）。
    返回 (app, handler) 二元组。
    """
    handler = handler if handler is not None else build_handler()
    application = create_rest_app(
        handler,
        channel=MobileBankChannel(),
        session_store=MemorySessionStore(),
    )
    return application, handler


#: 模块级单例：供 uvicorn 真实 HTTP 启动使用。
_HANDLER = build_handler()

app = create_app(_HANDLER)[0]


def get_handler() -> InteractiveHandler:
    """返回装配进模块级 `app` 的那个 handler 实例（供计数断言）。"""
    return _HANDLER
