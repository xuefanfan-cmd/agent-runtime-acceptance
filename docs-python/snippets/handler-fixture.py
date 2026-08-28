from collections.abc import AsyncIterator

from agent_runtime.domain.context import ServeRequest
from agent_runtime.domain.result import QueryChunk, QueryResponse


class FixtureHandler:
    agent_id = "fixture"
    priority = 0

    def is_healthy(self) -> bool:
        return True

    async def query(self, request: ServeRequest) -> QueryResponse:
        return QueryResponse(result="ok", conversation_id=request.conversation_id)

    async def stream_query(self, request: ServeRequest) -> AsyncIterator[QueryChunk]:
        yield QueryChunk.of_event("thought", content="inspect")
        yield QueryChunk.of_final_answer("ok")

    async def start(self) -> None: ...
    async def stop(self) -> None: ...
    async def clear_session(self, conversation_id: str) -> None: ...
