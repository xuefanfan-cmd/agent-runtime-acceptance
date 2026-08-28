# coding: utf-8
"""宿主组合根：把 runtime 的 REST 工厂装成一个可运行进程（最小真实宿主）。

对应 docs/examples/rest/src/rest_agent/runtime/rest.py 与 deploy/host_app.py 的形态。只做装配，
不重复实现 runtime 已提供的东西。

**显式传 `MobileBankChannel()`**：docs/how-to/custom-rest.md 记录了一个真实缺口——
当前契约检查发生在默认赋值之前，省略 `channel` 可能在启动期失败。宿主按文档
显式传入，避免踩该顺序缺陷（文档已如实记录，故不视为文档不足，而是「文档救了我」）。
"""
from __future__ import annotations

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from .handler import FixtureHandler
from . import configuration as config
from agent_runtime.adapters.inbound.rest.mobile_bank import MobileBankChannel
from agent_runtime.bootstrap.rest_app import create_rest_app
from .state.session_store import MemorySessionStore


def create_app() -> FastAPI:
    """装配 REST 应用：真实 runtime 工厂 + 显式通道 + 会话存储 + fixture 处理器。"""
    handler = FixtureHandler(delay_s=config.fixture_delay_s())

    app: FastAPI = create_rest_app(
        handler,
        channel=MobileBankChannel(),
        session_store=MemorySessionStore(),
    )

    # 健康检查属宿主装配层（host_app.py 的差异表第 1 行：/health 由宿主提供）。
    @app.get("/health")
    async def _health() -> JSONResponse:
        return JSONResponse(
            {"status": "healthy", "service": "A2A Service", "agent": config.agent_id()}
        )

    return app


app = create_app()
