# coding: utf-8
"""REST Agent 的程序级启动入口。

启动参考宿主（docs/how-to/setup-and-run.md）：

    RUNTIME_BACKEND=fixture RUNTIME_PORT=8090 python run_server.py
"""
from __future__ import annotations

import os

import uvicorn

from . import configuration as config
from .rest import app


def main() -> None:
    uvicorn.run(
        "rest_agent.runtime.rest:app",
        host=os.environ.get("RUNTIME_HOST", "127.0.0.1"),
        port=config.runtime_port(),
        reload=False,
    )


if __name__ == "__main__":
    main()
