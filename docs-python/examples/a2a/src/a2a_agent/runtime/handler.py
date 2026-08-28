# coding: utf-8
"""确定性 FixtureHandler：契约档的处理器（docs/snippets/fixture-handler.py 形态）。

**不依赖任何外部服务**——无模型、无网络、无存储。这让验收用例可重复执行：
同一请求任意次数得到同一事件序列与终答。终答作为**内容帧** `final_answer_chunk`
投递，成功完成由流正常结束表达（compatibility.md 已知兼容边界 #1：不得臆造
COMPLETED chunk；docs/how-to/a2a.md 第 1 条：Handler 只产 QueryChunk，
A2A 入口 adapter 会把它转成协议事件与终态）。
"""
from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

from agent_runtime.domain.context import ServeRequest
from agent_runtime.domain.result import QueryChunk, QueryResponse


#: 固定事件序列。用例的预期值以此为准。
FIXTURE_EVENTS: list[tuple[str, str, str, dict]] = [
    ("thought", "先查看账户", "", {}),
    ("tool_start", "调用余额查询", "query_balance", {}),
]

#: 替身终答文本；聚合 query / 流式终答 / 终态消息都取它。
FIXTURE_ANSWER = "余额为 100.00 元"


class FixtureHandler:
    """产出固定结果序列的处理器（对齐 docs/examples/rest/src/rest_agent/runtime/rest.py 的 FixtureHandler）。"""

    #: 装配期契约校验按 AgentHandler 成员面判；agent_id / priority 是协议要求的类属性。
    agent_id = "a2a-fixture-agent"
    priority = 0

    def __init__(self, delay_s: float = 0.0) -> None:
        self._delay_s = delay_s

    def is_healthy(self) -> bool:
        return True

    async def stream_query(self, request: ServeRequest) -> AsyncIterator[QueryChunk]:
        """流式执行：只产 QueryChunk（docs/how-to/a2a.md 第 1 条）。"""
        for event_type, content, plugin, data in FIXTURE_EVENTS:
            if self._delay_s:
                await asyncio.sleep(self._delay_s)
            yield QueryChunk.of_event(event_type, content=content, data=data, plugin=plugin)
        yield QueryChunk.of_final_answer(FIXTURE_ANSWER)

    async def query(self, request: ServeRequest) -> QueryResponse:
        """非流式聚合：drain 自身流取终答。返回类型须是 QueryResponse。"""
        answer = ""
        async for chunk in self.stream_query(request):
            if chunk.data.get("final") or chunk.data.get("event_type") == QueryChunk.EVENT_FINAL_ANSWER_CHUNK:
                answer = chunk.data.get("content", "")
        return QueryResponse(result=answer, conversation_id=request.conversation_id)

    async def start(self) -> None:
        return None

    async def stop(self) -> None:
        return None

    async def clear_session(self, conversation_id: str) -> None:
        return None
