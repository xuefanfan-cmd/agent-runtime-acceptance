# coding: utf-8
"""Versatile 远端代理 Agent —— 接受度集成测试。

用**真实 runtime** 的 VersatileAgentHandler + VersatileClient（httpx），
驱动**本地 fake 远端 HTTP 服务**（真实 TCP socket），验证：

- 成功流：增量块 + 终答 + 流正常结束（完成）
- HTTP 错误（500）→ VERSATILE_HTTP_STATUS_ERROR（传输失败，FAILED）
- 远端 error 事件帧 → VersatileRemoteError（业务失败，FAILED）
- 流关闭但无终态 → VERSATILE_STREAM_CLOSED_WITHOUT_TERMINAL（失败）
- 结束信号无业务终态 → of_interrupt（远端在等输入）
- 远端不可达 → VERSATILE_TRANSPORT_ERROR（传输失败，FAILED）

运行：PYTHONPATH=<reference> python -m pytest tests/ -v
参考 runtime 只读引入，本测试不修改参考仓。
"""
from __future__ import annotations

import asyncio
import socket
from typing import AsyncIterator

import pytest
import pytest_asyncio

from agent_runtime.adapters.outbound.versatile import VersatileAgentHandler, VersatileConfig
from agent_runtime.adapters.outbound.versatile.client import VersatileHttpStatusError
from agent_runtime.adapters.outbound.versatile.stream_adapter import (
    STREAM_CLOSED_WITHOUT_TERMINAL,
    VersatileFrameTranslator,
    VersatileRemoteError,
    VersatileStreamState,
)
from agent_runtime.domain.context import CALLER_PARAMS_KEY, ServeRequest
from agent_runtime.domain.result import QueryChunk

from versatile_agent.runtime.fake_remote_service import FakeVersatileService


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="module")
def service() -> FakeVersatileService:
    svc = FakeVersatileService().start()
    yield svc
    svc.stop()


@pytest_asyncio.fixture
async def handler(service: FakeVersatileService) -> VersatileAgentHandler:
    # 函数级作用域：每个用例新建处理器。VersatileClient 的常驻 httpx.AsyncClient
    # 绑定**首个**事件循环（client._ensure_client），而 pytest-asyncio 每个用例独立建环；
    # 模块级复用会让后续用例在另一循环里复用旧客户端 → "Event loop is closed" /
    # "different task" 错误。生产宿主单循环运行，不存在此问题；测试按用例隔离即可。
    cfg = VersatileConfig(url_template=service.url_template(), timeout_s=10.0)
    h = VersatileAgentHandler(cfg)
    yield h
    # 在同一事件循环内关闭常驻客户端，避免 httpx 流在环已关闭后收尾
    await h.stop()


def _request(conversation_id: str, *, caller_params: dict | None = None) -> ServeRequest:
    return ServeRequest(
        conversation_id=conversation_id,
        tenant_id="tenant-test",
        user_id="user-test",
        space_id="space-test",
        messages=[{"role": "user", "content": "帮我查一下余额"}],
        stream=True,
        metadata={CALLER_PARAMS_KEY: caller_params} if caller_params else {},
    )


async def _drain(agen: AsyncIterator[QueryChunk]) -> tuple[list[QueryChunk], BaseException | None]:
    chunks: list[QueryChunk] = []
    try:
        async for c in agen:
            chunks.append(c)
        return chunks, None
    except Exception as exc:  # noqa: BLE001
        return chunks, exc


# ── 成功流 ────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_success_stream_completes(handler: VersatileAgentHandler) -> None:
    chunks, exc = await _drain(handler.stream_query(_request("conv-success-t1")))
    assert exc is None, f"成功流不应有异常，实际: {exc!r}"
    kinds = [c.event_type or c.type for c in chunks]
    assert "versatile_chunk" in kinds
    assert any(c.is_final_answer for c in chunks)
    final = next(c for c in chunks if c.is_final_answer)
    assert final.content == "hello from remote"
    # 终态语义：完成由流正常结束表达（无异常即完成）
    assert not any(c.type == QueryChunk.TYPE_ERROR for c in chunks)


@pytest.mark.asyncio
async def test_success_query_aggregates(handler: VersatileAgentHandler) -> None:
    """阻塞路径：query() 应聚合出终答且不抛异常。"""
    from agent_runtime.domain.result import QueryResponse

    resp: QueryResponse = await handler.query(_request("conv-success-t2"))
    assert isinstance(resp, QueryResponse)
    assert resp.result == "hello from remote"


# ── HTTP 错误（4xx/5xx）→ 传输失败 ─────────────────────────

@pytest.mark.asyncio
async def test_http_status_error_classified(handler: VersatileAgentHandler) -> None:
    chunks, exc = await _drain(handler.stream_query(_request("conv-http-error-t1")))
    assert isinstance(exc, VersatileHttpStatusError)
    assert exc.status_code == 500
    # 对外帧带失败位与分类码，且不泄漏 URL（文案只带状态码）
    errs = [c for c in chunks if c.type == QueryChunk.TYPE_ERROR]
    assert len(errs) == 1
    assert errs[0].code == "VERSATILE_HTTP_STATUS_ERROR"
    assert errs[0].data.get("kind") == "transport"
    assert "远端返回 HTTP 500" in errs[0].message
    assert "http://" not in errs[0].message  # 不泄漏内网地址


# ── 远端业务错误（error 事件帧）→ 业务失败 ─────────────────

@pytest.mark.asyncio
async def test_remote_error_frame_classified(handler: VersatileAgentHandler) -> None:
    chunks, exc = await _drain(handler.stream_query(_request("conv-remote-error-t1")))
    assert isinstance(exc, VersatileRemoteError)
    assert exc.code == "E_NO_MATCH"
    errs = [c for c in chunks if c.type == QueryChunk.TYPE_ERROR]
    assert len(errs) == 1
    assert errs[0].code == "E_NO_MATCH"
    assert errs[0].data.get("kind") == "remote"
    assert "未找到匹配的意图" in errs[0].message


# ── 流关闭但无终态 → 失败 ──────────────────────────────────

@pytest.mark.asyncio
async def test_stream_closed_without_terminal(handler: VersatileAgentHandler) -> None:
    chunks, exc = await _drain(handler.stream_query(_request("conv-no-terminal-t1")))
    assert isinstance(exc, VersatileRemoteError)
    assert exc.code == STREAM_CLOSED_WITHOUT_TERMINAL
    errs = [c for c in chunks if c.type == QueryChunk.TYPE_ERROR]
    assert len(errs) == 1
    assert errs[0].code == STREAM_CLOSED_WITHOUT_TERMINAL


# ── 结束信号但无业务终态 → 中断（远端在等输入）────────────────

@pytest.mark.asyncio
async def test_end_signal_without_terminal_is_interrupt(handler: VersatileAgentHandler) -> None:
    chunks, exc = await _drain(handler.stream_query(_request("conv-interrupt-t1")))
    assert exc is None, "中断块后流正常结束，不应有异常"
    assert any(c.type == QueryChunk.TYPE_INTERRUPT for c in chunks)


# ── 远端不可达 → 传输失败 ──────────────────────────────────

@pytest.mark.asyncio
async def test_unreachable_classified() -> None:
    dead_port = _free_port()
    cfg = VersatileConfig(
        url_template=f"http://127.0.0.1:{dead_port}/versatile/{{conversation_id}}",
        timeout_s=3.0,
    )
    h = VersatileAgentHandler(cfg)
    chunks, exc = await _drain(h.stream_query(_request("conv-unreachable-t1")))
    assert exc is not None
    errs = [c for c in chunks if c.type == QueryChunk.TYPE_ERROR]
    assert len(errs) == 1
    assert errs[0].code == "VERSATILE_TRANSPORT_ERROR"
    assert errs[0].data.get("kind") == "transport"


# ── 调用方查询串透传（出站 URL 原样拼接）────────────────────

@pytest.mark.asyncio
async def test_caller_params_forwarded(handler: VersatileAgentHandler) -> None:
    # conv-success 忽略查询串，此处仅验证不抛异常且仍完成（透传由存量/上游契约保证）
    chunks, exc = await _drain(
        handler.stream_query(
            _request("conv-success-t3", caller_params={"workspace_id": "1", "type": "controller"})
        )
    )
    assert exc is None
    assert any(c.is_final_answer for c in chunks)


# ── 单元级：帧翻译分类（不依赖网络）────────────────────────

def _cfg() -> VersatileConfig:
    return VersatileConfig(url_template="http://x/{{conversation_id}}")


def test_translate_content_frame() -> None:
    tr = VersatileFrameTranslator(_cfg())
    state = VersatileStreamState()
    chunk = tr.translate({"event": "message", "type": "text", "data": {"content": "hi"}}, state)
    assert chunk is not None
    assert chunk.event_type == "versatile_chunk"
    assert chunk.content == "hi"
    assert not state.has_terminal


def test_translate_end_node_marks_terminal() -> None:
    tr = VersatileFrameTranslator(_cfg())
    state = VersatileStreamState()
    tr.translate({"event": "message", "data": {"node_type": "End", "is_finished": True}}, state)
    assert state.has_terminal


def test_translate_error_event_raises() -> None:
    tr = VersatileFrameTranslator(_cfg())
    state = VersatileStreamState()
    with pytest.raises(VersatileRemoteError) as ei:
        tr.translate({"event": "error", "data": {"code": "X9", "message": "boom"}}, state)
    assert ei.value.code == "X9"
    assert "boom" in str(ei.value)


def test_on_stream_closed_terminal_returns_final_answer() -> None:
    tr = VersatileFrameTranslator(_cfg())
    state = VersatileStreamState()
    state.mark_terminal()
    state.append_text("done")
    chunk = tr.on_stream_closed(state)
    assert chunk is not None
    assert chunk.is_final_answer
    assert chunk.content == "done"


def test_on_stream_closed_no_signal_raises() -> None:
    tr = VersatileFrameTranslator(_cfg())
    with pytest.raises(VersatileRemoteError) as ei:
        tr.on_stream_closed(VersatileStreamState())
    assert ei.value.code == STREAM_CLOSED_WITHOUT_TERMINAL
