# coding: utf-8
"""宿主组合根：把 runtime 的 `create_a2a_app` 装成一个标准 A2A 可运行进程。

对应 docs/examples/a2a/src/a2a_agent/runtime/a2a.py、docs/how-to/a2a.md 与 deploy/host_app.py 的
形态。**必须优先调用真实 runtime 的 `create_a2a_app`**，只做装配，不重复实现
runtime 已提供的东西。

装配内容：
- 确定性 `FixtureHandler`（AgentHandler 契约）。
- `name` / `description` / `version` / `url` → Agent Card。
- 任务存储：未显式注入时用 a2a-sdk 的 `InMemoryTaskStore`（docs 记录的默认，
  无外部依赖）；宿主可经 `RUNTIME_TASK_STORE=sqlite` 切换到一个最小可写替身
  （见 task_store.py，验证「TaskStore 或最小可用替身」两种形态）。

健康检查 / 就绪端点属宿主装配层（docs 的 H-LIFE-1B：视图由 runtime 提供、
端点由宿主自建），故在组合根补一个 `/health`。
"""
from __future__ import annotations

from typing import Any

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from .handler import FixtureHandler
from . import configuration as config
from agent_runtime.bootstrap.a2a_app import create_a2a_app


def _build_task_store(backend: str) -> Any:
    """按配置返回任务存储；`memory` 用 a2a-sdk 默认 InMemoryTaskStore。"""
    if backend == "sqlite":
        from .state.task_store import SqliteTaskStore  # noqa: PLC0415

        return SqliteTaskStore()
    from a2a.server.tasks import InMemoryTaskStore  # noqa: PLC0415

    return InMemoryTaskStore()


def create_app_for_store(backend: str) -> FastAPI:
    """按给定的任务存储后端装配标准 A2A 应用（供测试两种 TaskStore 形态）。"""
    handler = FixtureHandler(delay_s=config.fixture_delay_s())
    task_store = _build_task_store(backend)

    return _assemble(handler, task_store)


def create_app() -> FastAPI:
    """装配标准 A2A 应用：真实 runtime 工厂 + 确定性处理器 + 卡片 + 任务存储。"""
    return create_app_for_store(config.task_store_backend())


def _assemble(handler: FixtureHandler, task_store: Any) -> FastAPI:
    """组合根：调用真实 runtime 的 create_a2a_app 并补宿主健康端点。"""

    app: FastAPI = create_a2a_app(
        handler,
        name=config.agent_name(),
        description=config.agent_description(),
        version=config.agent_version(),
        url=config.self_url(),
        task_store=task_store,
    )

    # 健康检查属宿主装配层（视图由 runtime 提供、端点由宿主自建）。
    @app.get("/health")
    async def _health() -> JSONResponse:
        return JSONResponse(
            {
                "status": "healthy",
                "service": "A2A Service",
                "agent": config.agent_name(),
            }
        )

    return app


app = create_app()
