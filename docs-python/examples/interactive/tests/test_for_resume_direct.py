# coding: utf-8
"""Handler 级直接验证 `ServeRequest.for_resume` 续接路径（不经过 REST wire）。

文档定义的续接路径是 `ServeRequest.for_resume`（how-to/interrupt-and-resume.md：
「续接通过 ServeRequest.for_resume 重走同一个 stream_query SPI」）。
本测试直接构造 ServeRequest，验证首轮 interrupt、续接轮 resume 的纯领域语义。
"""

from __future__ import annotations

import pytest

from agent_runtime.domain.context import ServeRequest
from agent_runtime.domain.result import QueryChunk

from interactive_agent.runtime.configuration import INTERACTION_ID, INTERRUPT_CONTENT, RESUME_SUPPLEMENT
from interactive_agent.runtime.handler import InteractiveHandler


async def _drain(stream) -> list[QueryChunk]:
    return [chunk async for chunk in stream]


def test_direct_first_turn_is_interrupt() -> None:
    handler = InteractiveHandler()
    req = ServeRequest.of_text("帮我查余额", conversation_id="c1")
    chunk = _async(_drain(handler.stream_query(req)))[0]
    assert chunk.type == QueryChunk.TYPE_INTERRUPT
    assert chunk.data["content"] == INTERRUPT_CONTENT
    assert chunk.data["interaction_id"] == INTERACTION_ID


def test_direct_resume_via_for_resume() -> None:
    handler = InteractiveHandler()
    original = ServeRequest.of_text("帮我查余额", conversation_id="c2")
    resume = original.for_resume(
        user_supplement=RESUME_SUPPLEMENT,
        recovery_point_id=INTERACTION_ID,
    )
    assert resume.is_resume is True
    assert resume.resume_user_supplement == RESUME_SUPPLEMENT
    assert resume.resume_recovery_point_id == INTERACTION_ID

    chunks = _async(_drain(handler.stream_query(resume)))
    assert len(chunks) == 1
    assert chunks[0].type == QueryChunk.TYPE_CHUNK
    assert chunks[0].is_final_answer
    assert RESUME_SUPPLEMENT in chunks[0].content
    assert INTERACTION_ID in chunks[0].content

    # 计数器：首轮未走，续接命中
    assert handler.interrupts_seen == 0
    assert handler.resumes_seen == 1


def _async(coro):
    import asyncio
    return asyncio.run(coro)
