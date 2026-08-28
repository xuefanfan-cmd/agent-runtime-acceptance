# coding: utf-8
"""组合根与服务入口。

显式调用 runtime 的公开工厂完成服务装配。

暴露形态取 A2A：卡片元数据、技能项与能力位来自 `resources/application.yml` 的
`openjiuwen.service.a2a_access` 段。
"""
from __future__ import annotations

from typing import Any

from agent_runtime.bootstrap.a2a_app import create_a2a_app

from ..agent.definition import AGENT_ID
from .configuration import HostConfig, build_handler, load_runtime_config


def build_app(config: HostConfig | None = None) -> Any:
    """装配 A2A 应用（真实 runtime 工厂 + agent-core ReAct Agent）。"""
    config = config or HostConfig.load()
    return create_a2a_app(
        build_handler(config),
        name=AGENT_ID,
        config=load_runtime_config(),
    )


def main() -> None:
    """本地启动：`python -m react_agent.runtime.application`。"""
    import uvicorn

    config = HostConfig.load()
    uvicorn.run(build_app(config), host=config.host, port=config.port)


if __name__ == "__main__":
    main()
