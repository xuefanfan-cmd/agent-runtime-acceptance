# coding: utf-8
"""组合根：用真实 runtime 的 REST 工厂装配最小可运行宿主。

按 `docs/api/agent-runtime-python.md` 与 `docs/examples/rest/src/rest_agent/runtime/rest.py` 装配：
    create_rest_app(handler, channel=MobileBankChannel(), session_store=...)

必须显式传入 `MobileBankChannel()`——文档明示「不能依赖工厂对默认 channel 的
隐式装配，因为契约检查发生在默认赋值之前」（`api/agent-runtime-python.md`）。

依赖边界（`compatibility.md` 运行前置）：
  - 契约档使用确定性 FixtureHandler，不依赖模型 / Redis / openjiuwen；
  - REST 入口虽不直接暴露 A2A 协议，但导入链会经过 `a2a.types.a2a_pb2`，
    因此必须安装与 a2a-sdk 匹配的协议依赖（见 requirements.txt）。
"""
from __future__ import annotations

from fastapi import FastAPI

from agent_runtime.adapters.inbound.rest.mobile_bank import MobileBankChannel
from agent_runtime.bootstrap.rest_app import create_rest_app

from .handler import FixtureHandler
from .configuration import HostConfig
from .state.session_store import MemorySessionStore


def build_app(config: HostConfig | None = None) -> FastAPI:
    """装配 REST 应用（真实 runtime 工厂 + 确定性 FixtureHandler）。"""
    config = config or HostConfig.load()
    if config.backend != "fixture":
        raise ValueError(
            f"本验收宿主仅支持确定性 fixture 后端，收到 backend={config.backend!r}。"
            "真实 workflow 需 openjiuwen 执行后端并按宿主配置注册 workflow。"
        )
    return create_rest_app(
        FixtureHandler(),
        channel=MobileBankChannel(),
        session_store=MemorySessionStore(),
    )


#: 供 `uvicorn deepseek_agent.runtime.application:app` 直接加载。
app: FastAPI = build_app()
