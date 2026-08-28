# coding: utf-8
"""A2A Agent 的程序级启动入口。

启动参考宿主（docs/how-to/setup-and-run.md / docs/how-to/a2a.md）：

    RUNTIME_TASK_STORE=memory RUNTIME_PORT=8090 python run_server.py

注意：本应用依赖 runtime（agent_runtime 包）与 a2a-sdk，需在装了依赖的 Python
解释器下运行（使用装有 runtime 与协议依赖的解释器）。
"""
from __future__ import annotations

import os

import uvicorn

from . import configuration as config
from .a2a import app


def main() -> None:
    uvicorn.run(
        "a2a_agent.runtime.a2a:app",
        host=os.environ.get("RUNTIME_HOST", "127.0.0.1"),
        port=config.runtime_port(),
        reload=False,
    )


if __name__ == "__main__":
    main()
