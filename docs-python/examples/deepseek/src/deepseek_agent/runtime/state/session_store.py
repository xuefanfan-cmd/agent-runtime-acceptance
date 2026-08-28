from typing import Any


class MemorySessionStore:
    """单进程验收用 SessionStore；生产环境应替换为共享后端。"""

    def __init__(self) -> None:
        self._snapshots: dict[str, dict[str, Any]] = {}

    async def get_request(self, conversation_id: str) -> dict[str, Any] | None:
        return self._snapshots.get(conversation_id)

    async def put_request_if_absent(
        self, conversation_id: str, snapshot: dict[str, Any], *, ttl_s: int
    ) -> bool:
        if conversation_id in self._snapshots:
            return False
        self._snapshots[conversation_id] = dict(snapshot)
        return True
