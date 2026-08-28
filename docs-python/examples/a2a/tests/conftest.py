# coding: utf-8
"""pytest 根 conftest：把本工程 `src/` 与 runtime 检出纳入 sys.path。

本工程**只读引用** runtime，不修改它。runtime 检出路径由环境变量 `RUNTIME_ROOT`
给出——它指向 agent-solution 仓的 `common/agent-runtime-ext-python`，即 Python 侧的
runtime 本体。已经把 runtime 装进当前环境（`pip install -e`）时可以不设。
"""
from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path

_SRC_ROOT = Path(__file__).resolve().parents[1] / "src"
if str(_SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(_SRC_ROOT))

_RUNTIME_ROOT = os.environ.get("RUNTIME_ROOT")
if _RUNTIME_ROOT and _RUNTIME_ROOT not in sys.path:
    sys.path.insert(0, _RUNTIME_ROOT)
elif not _RUNTIME_ROOT and importlib.util.find_spec("agent_runtime") is None:
    raise RuntimeError(
        "找不到 agent_runtime。设置 RUNTIME_ROOT 指向 runtime 检出"
        "（agent-solution 仓的 common/agent-runtime-ext-python），"
        "或先把 runtime 装进当前环境。"
    )
