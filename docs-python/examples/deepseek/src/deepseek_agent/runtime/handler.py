from collections.abc import AsyncIterator

from agent_runtime.domain.context import ServeRequest
from agent_runtime.domain.result import QueryChunk, QueryResponse


class FixtureHandler:
    """确定性 Agent 语义实现；runtime 层只负责托管它。"""

    agent_id = "deepseek-validation-agent"
    priority = 0

    def is_healthy(self) -> bool:
        return True

    async def query(self, request: ServeRequest) -> QueryResponse:
        return QueryResponse(result="fixture answer", conversation_id=request.conversation_id)

    def stream_query(self, request: ServeRequest) -> AsyncIterator[QueryChunk]:
        async def events() -> AsyncIterator[QueryChunk]:
            yield QueryChunk.of_event("thought", content="inspect")
            yield QueryChunk.of_event("tool_start", content="fixture")
            yield QueryChunk.of_final_answer("fixture answer")

        return events()

    async def start(self) -> None:
        return None

    async def stop(self) -> None:
        return None

    async def clear_session(self, conversation_id: str) -> None:
        return None
