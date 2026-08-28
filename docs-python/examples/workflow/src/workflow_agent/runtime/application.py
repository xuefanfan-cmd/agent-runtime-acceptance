# coding: utf-8
"""组合根与服务入口。

显式调用 runtime 的 A2A 工厂完成服务装配，并把工作流注册挂进启动钩子。
"""
from __future__ import annotations

from typing import Any

from agent_runtime.bootstrap.a2a_app import create_a2a_app

from ..agent.definition import WORKFLOW_ID
from .configuration import HostConfig, build_handler, load_runtime_config, make_init_hook


def build_app(config: HostConfig | None = None) -> Any:
    """装配 A2A 应用（真实 runtime 工厂 + agent-core 工作流）。"""
    config = config or HostConfig.load()
    return create_a2a_app(
        build_handler(),
        name=WORKFLOW_ID,
        config=load_runtime_config(),
        init_hooks=(make_init_hook(config),),
    )


def main() -> None:
    """本地启动：`python -m workflow_agent.runtime.application`。"""
    import uvicorn

    config = HostConfig.load()
    uvicorn.run(build_app(config), host=config.host, port=config.port)


if __name__ == "__main__":
    main()
