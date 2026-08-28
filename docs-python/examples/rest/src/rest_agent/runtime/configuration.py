# coding: utf-8
"""配置边界：宿主从环境变量取装配参数（compatibility.md 的运行前置基线）。

按文档事实源分工，依赖版本以 `agent_runtime/requirements.txt` 为准，宿主不重复
声明版本基线；这里只承载**宿主自己的装配参数**。环境变量是最终覆盖层
（ConfigLoader 合并顺序：文件 → 机密目录 → 环境变量），本最小应用直接读环境变量，
不引入文件配置，保持「最小但真实」。
"""
from __future__ import annotations

import os


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default).strip()


def runtime_port() -> int:
    """监听端口；文档默认 `RUNTIME_PORT=8090`。"""
    try:
        return int(_env("RUNTIME_PORT", "8090"))
    except ValueError as exc:  # pragma: no cover - 部署期配置错误
        raise SystemExit(f"RUNTIME_PORT 须为整数，收到 {os.environ.get('RUNTIME_PORT')!r}") from exc


def agent_id() -> str:
    """服务身份；写进会话上下文与对外报文（host_app.py 的 AGENT_ID 同形）。"""
    return _env("RUNTIME_AGENT_ID", "mobile_bank_agent")


def backend() -> str:
    """后端选择：fixture（确定性替身，默认）或 agentcore（真实执行后端）。"""
    value = _env("RUNTIME_BACKEND", "fixture")
    if value not in ("fixture", "agentcore"):
        raise SystemExit(
            f"RUNTIME_BACKEND 取值须为 fixture 或 agentcore，收到 {value!r}"
        )
    return value


def fixture_delay_s() -> float:
    """替身帧间延迟（秒）。文档默认 0。"""
    return float(_env("RUNTIME_FIXTURE_DELAY_S", "0"))


def self_url() -> str:
    """对外基址；仅 A2A 卡片使用，REST 最小应用不强制。"""
    port = runtime_port()
    return _env("RUNTIME_SELF_URL", f"http://127.0.0.1:{port}/a2a/")
