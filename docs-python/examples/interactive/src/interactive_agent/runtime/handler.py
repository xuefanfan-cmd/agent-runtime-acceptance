# coding: utf-8
"""确定性交互式 Handler：验证 interrupt → resume/continue 语义。

关键语义（对齐 docs/how-to/interrupt-and-resume.md 与 api/runtime-ports.md）：

- 处理器只产三值（chunk / interrupt / error）；**续接不新增 SPI 方法**——
  adapter 在 `stream_query` 内识别续接标记（`ServeRequest.for_resume`），
  重走同一个 `stream_query` SPI。
- 第一轮流：产 `QueryChunk.of_interrupt`（携带 `interaction_id` 作恢复锚点）。
- 续接轮（`request.is_resume`）：从 `resume_user_supplement` /
  `resume_recovery_point_id` 取回交回的输入，产终答 chunk 后正常结束流。

本 Handler 是**确定性替身**（无模型、无网络、无存储），让逐字节断言可重复。
计数器暴露每次调用分别命中多少轮 interrupt / resume，供测试与报告核对。
"""

from __future__ import annotations

from collections.abc import AsyncIterator

from agent_runtime.domain.context import ServeRequest
from agent_runtime.domain.result import QueryChunk, QueryResponse

from ..runtime.configuration import (
    AGENT_ID,
    INTERACTION_ID,
    INTERRUPT_CONTENT,
    PRIORITY,
)


class InteractiveHandler:
    """确定性交互式 Handler：首轮 interrupt，续接轮出终答。"""

    agent_id = AGENT_ID
    priority = PRIORITY

    def __init__(self) -> None:
        # 可观测计数（供测试与报告断言语义路径命中）。
        self.interrupts_seen = 0
        self.resumes_seen = 0
        self.interaction_id = INTERACTION_ID

    def is_healthy(self) -> bool:
        return True

    # -- 非流式聚合：直接 drain 自身 stream_query（编排层同一份聚合规则）--
    async def query(self, request: ServeRequest) -> QueryResponse:
        from agent_runtime.domain.aggregate import aggregate_stream

        result = await aggregate_stream(self.stream_query(request))
        return QueryResponse(result=result, conversation_id=request.conversation_id)

    # -- 流式：SPI 唯一执行入口，续接也重走这里 --
    def stream_query(self, request: ServeRequest) -> AsyncIterator[QueryChunk]:
        async def events() -> AsyncIterator[QueryChunk]:
            if request.is_resume:
                # 续接轮：取回 client 交回的补充输入与恢复锚点，产出终答。
                self.resumes_seen += 1
                supplement = request.resume_user_supplement
                recovery = request.resume_recovery_point_id
                yield QueryChunk.of_final_answer(
                    (
                        f"已收到您的输入「{supplement}」"
                        f"(recovery_point_id={recovery})，处理完成。"
                    ),
                    event_type=QueryChunk.EVENT_FINAL_ANSWER_CHUNK,
                )
                return

            # 首轮：产交互式中断帧（→ Task INPUT_REQUIRED），等用户输入续接。
            self.interrupts_seen += 1
            yield QueryChunk.of_interrupt(
                content=INTERRUPT_CONTENT,
                interaction_id=self.interaction_id,
            )

        return events()

    async def start(self) -> None:
        return None

    async def stop(self) -> None:
        return None

    async def clear_session(self, conversation_id: str) -> None:
        return None
