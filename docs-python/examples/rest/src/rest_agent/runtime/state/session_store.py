# coding: utf-8
"""SessionRequestStore 实现：单进程会话请求快照存储。

实现 `agent_runtime/ports/session.py` 的 `SessionRequestStore` 协议面——只有两个方法：
`get_request` / `put_request_if_absent`（「不存在才写」，单命令原子）。

**只适合单进程验收**（docs/examples/rest/src/rest_agent/runtime/rest.py 的 MemorySessionStore 同形）；
生产环境应替换为 Redis / 数据库实现（compatibility.md：Redis 是状态外置、会话快照
的运行前置）。
"""
from __future__ import annotations

from typing import Any, Optional

from agent_runtime.ports.session import SessionRequestStore


class MemorySessionStore:
    """满足 SessionRequestStore 协议面最小实现的单进程替身。"""

    def __init__(self) -> None:
        self._snapshots: dict[str, dict[str, Any]] = {}

    async def get_request(self, conversation_id: str) -> Optional[dict]:
        return self._snapshots.get(conversation_id)

    async def put_request_if_absent(
        self, conversation_id: str, snapshot: dict, *, ttl_s: int
    ) -> bool:
        # 端口要求「不存在才写」，单命令原子（本内存实现天然原子）。
        if conversation_id in self._snapshots:
            return False
        self._snapshots[conversation_id] = dict(snapshot)
        return True
