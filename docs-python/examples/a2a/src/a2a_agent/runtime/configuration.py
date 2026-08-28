# coding: utf-8
"""配置边界：宿主从环境变量取装配参数（compatibility.md 的运行前置基线）。

按文档事实源分工，依赖版本以 `agent_runtime/requirements.txt` 为准，宿主不重复
声明版本基线；这里只承载**宿主自己的装配参数**。环境变量是最终覆盖层，本最小
应用直接读环境变量，不引入文件配置，保持「最小但真实」。

对齐 docs/examples/a2a/src/a2a_agent/runtime/a2a.py 与 docs/how-to/a2a.md 的装配面：
create_a2a_app 需要 name / description / url 等公开地址，其余可选注入。
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


def agent_name() -> str:
    """卡片声明的 Agent 身份（create_a2a_app 的 `name`，兼服务身份）。"""
    return _env("RUNTIME_AGENT_NAME", "a2a-fixture-agent")


def agent_description() -> str:
    return _env("RUNTIME_AGENT_DESCRIPTION", "标准 A2A 验收 Agent（确定性替身后端）")


def agent_version() -> str:
    return _env("RUNTIME_AGENT_VERSION", "1.0.0")


def self_url() -> str:
    """对外公开可调用地址（卡片 `url`）。默认按本地端口推导。"""
    port = runtime_port()
    return _env("RUNTIME_SELF_URL", f"http://127.0.0.1:{port}/a2a/")


def fixture_delay_s() -> float:
    """替身帧间延迟（秒）。默认 0 = 确定性、立即返回。"""
    return float(_env("RUNTIME_FIXTURE_DELAY_S", "0"))


def task_store_backend() -> str:
    """任务存储后端：memory（默认，InMemoryTaskStore）或 sqlite（最小可写替身）。"""
    value = _env("RUNTIME_TASK_STORE", "memory").lower()
    if value not in ("memory", "sqlite"):
        raise SystemExit(
            f"RUNTIME_TASK_STORE 取值须为 memory 或 sqlite，收到 {value!r}"
        )
    return value
