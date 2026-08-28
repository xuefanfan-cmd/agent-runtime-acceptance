# coding: utf-8
"""配置边界：宿主侧运行配置。

本模块只读取宿主自己的 `RUNTIME_*` 旋钮，例如监听地址、端口和 fixture
后端。runtime 配置属于 `openjiuwen.service` 配置树，应由 runtime 自己的
`ConfigLoader` 绑定；本模块不复制或改写 runtime 的配置加载逻辑。
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from agent_runtime.bootstrap.config.loader import ConfigLoader, ConfigSource, SourceKind
from agent_runtime.bootstrap.config.runtime_config import RuntimeConfig

#: 本宿主默认端口（`compatibility.md`：RUNTIME_PORT 默认 8090）。
DEFAULT_PORT = 8090

#: 确定性后端标识：不依赖模型 / Redis / openjiuwen（`compatibility.md` 运行前置）。
FIXTURE_BACKEND = "fixture"


@dataclass(frozen=True)
class HostConfig:
    """宿主运行配置（只读）。"""

    backend: str = FIXTURE_BACKEND
    port: int = DEFAULT_PORT
    host: str = "0.0.0.0"
    #: 会话快照 TTL（秒）。MemorySessionStore 用它做过期兜底，生产用 Redis。
    session_ttl_s: int = 300
    #: 装配期显式声明的依赖边界：本宿主只依赖确定性 fixture，不接真实执行后端。
    dependencies: tuple[str, ...] = ("fastapi", "httpx", "sse-starlette", "a2a-sdk", "PyYAML")

    @classmethod
    def load(cls, env: dict[str, str] | None = None) -> "HostConfig":
        """从宿主自己的 `RUNTIME_*` 环境变量装载。"""
        env = dict(env or os.environ)
        backend = env.get("RUNTIME_BACKEND") or FIXTURE_BACKEND
        port = int(env.get("RUNTIME_PORT") or DEFAULT_PORT)
        host = env.get("RUNTIME_HOST") or "0.0.0.0"
        ttl = int(env.get("RUNTIME_SESSION_TTL_S") or 300)
        return cls(backend=backend, port=port, host=host, session_ttl_s=ttl)


def load_runtime_config(path: Path) -> RuntimeConfig:
    """用当前 runtime 的 ConfigLoader 绑定 `openjiuwen.service` 配置。"""
    return ConfigLoader().load(
        RuntimeConfig,
        sources=(ConfigSource(SourceKind.FILE, str(path)),),
    )
