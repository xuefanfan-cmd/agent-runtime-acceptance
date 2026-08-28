# coding: utf-8
"""远端代理型 Agent 接受度探针：用**真实 runtime** 的 VersatileAgentHandler
驱动**本地 fake 远端 HTTP 服务**，验证成功流与失败/不可达终态分类。

运行前提（真实 runtime 依赖，只读引入，不修改参考仓）：
    PYTHONPATH=$RUNTIME_ROOT

覆盖场景：
  success      -> 远端业务跑完，终答到达，流正常结束（完成）
  http_error   -> 远端回 500 -> VERSATILE_HTTP_STATUS_ERROR（传输失败，FAILED）
  remote_error -> 远端 error 事件帧 -> VersatileRemoteError（业务失败，FAILED）
  no_terminal  -> 有内容但无终态、连接断开 -> VERSATILE_STREAM_CLOSED_WITHOUT_TERMINAL
  interrupt    -> end 结束信号但无业务终态 -> of_interrupt（远端在等输入）
  unreachable  -> 连一个没有监听的端口 -> VERSATILE_TRANSPORT_ERROR（不可达）
"""
from __future__ import annotations

import asyncio
import logging
import sys
from typing import AsyncIterator

from agent_runtime.adapters.outbound.versatile import (
    VersatileAgentHandler,
    VersatileConfig,
)
from agent_runtime.adapters.outbound.versatile.client import VersatileHttpStatusError
from agent_runtime.adapters.outbound.versatile.stream_adapter import (
    STREAM_CLOSED_WITHOUT_TERMINAL,
    VersatileRemoteError,
)
from agent_runtime.domain.context import CALLER_PARAMS_KEY, ServeRequest
from agent_runtime.domain.result import QueryChunk, QueryResponse

from .fake_remote_service import FakeVersatileService

logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(name)s: %(message)s")
log = logging.getLogger("probe")


async def drain(agen: AsyncIterator[QueryChunk]) -> tuple[list[QueryChunk], BaseException | None]:
    """排空一条异步结果流：返回 (chunks, exception)。exception 非 None 表示失败终态。"""
    chunks: list[QueryChunk] = []
    try:
        async for c in agen:
            chunks.append(c)
        return chunks, None
    except Exception as exc:  # noqa: BLE001 - 探针要捕获任意终态异常以分类
        return chunks, exc


def chunk_labels(chunks: list[QueryChunk]) -> list[str]:
    out = []
    for c in chunks:
        if c.type == QueryChunk.TYPE_ERROR:
            out.append(f"error[{c.code}/{c.message}]")
        elif c.is_final_answer:
            out.append(f"final[{c.content!r}]")
        elif c.type == QueryChunk.TYPE_INTERRUPT:
            out.append("interrupt")
        else:
            out.append(f"{c.event_type or c.type}[{c.content!r}]")
    return out


def make_request(conversation_id: str, *, caller_params: dict | None = None) -> ServeRequest:
    return ServeRequest(
        conversation_id=conversation_id,
        tenant_id="tenant-probe",
        user_id="user-probe",
        space_id="space-probe",
        messages=[{"role": "user", "content": "帮我查一下余额"}],
        stream=True,
        metadata={CALLER_PARAMS_KEY: caller_params} if caller_params else {},
    )


async def run_scenario(
    handler: VersatileAgentHandler, conversation_id: str
) -> dict:
    request = make_request(conversation_id)
    chunks, exc = await drain(handler.stream_query(request))
    return {"conversation_id": conversation_id, "chunks": chunks, "exc": exc}


def classify(result: dict) -> str:
    exc = result["exc"]
    if exc is None:
        return "COMPLETED"
    if isinstance(exc, VersatileRemoteError):
        return f"FAILED(remote, code={exc.code})"
    if isinstance(exc, VersatileHttpStatusError):
        return f"FAILED(transport, http={exc.status_code})"
    return f"FAILED({type(exc).__name__})"


async def main() -> int:
    svc = FakeVersatileService().start()
    print(f"fake remote listening: {svc.base_url}")
    url_template = svc.url_template()
    cfg = VersatileConfig(url_template=url_template, timeout_s=10.0)
    handler = VersatileAgentHandler(cfg)
    await handler.start()

    scenarios = [
        ("conv-success-1", "success"),
        ("conv-http-error-1", "http_error"),
        ("conv-remote-error-1", "remote_error"),
        ("conv-no-terminal-1", "no_terminal"),
        ("conv-interrupt-1", "interrupt"),
    ]

    results = {}
    for conv_id, label in scenarios:
        res = await run_scenario(handler, conv_id)
        res["label"] = label
        results[label] = res
        print(f"\n[scenario:{label}] conv={conv_id}")
        print(f"  chunks: {chunk_labels(res['chunks'])}")
        print(f"  terminal: {classify(res)}")

    # 不可达：连一个没有监听的端口（先起一个再关掉拿一个空闲端口）
    import socket

    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        dead_port = s.getsockname()[1]
    unreachable_cfg = VersatileConfig(
        url_template=f"http://127.0.0.1:{dead_port}/versatile/{{conversation_id}}",
        timeout_s=3.0,
    )
    unreachable_handler = VersatileAgentHandler(unreachable_cfg)
    res = await run_scenario(unreachable_handler, "conv-unreachable-1")
    res["label"] = "unreachable"
    results["unreachable"] = res
    print(f"\n[scenario:unreachable] conv=conv-unreachable-1 (port {dead_port} closed)")
    print(f"  chunks: {chunk_labels(res['chunks'])}")
    print(f"  terminal: {classify(res)}")

    await handler.stop()
    svc.stop()

    print("\n==== SUMMARY ====")
    ok = True
    expected = {
        "success": "COMPLETED",
        "http_error": "FAILED(transport, http=500)",
        "remote_error": "FAILED(remote, code=E_NO_MATCH)",
        "no_terminal": "FAILED(remote, code=VERSATILE_STREAM_CLOSED_WITHOUT_TERMINAL)",
        "interrupt": "COMPLETED",  # 中断块后流正常结束（无异常）
        "unreachable": "FAILED(ConnectError)",
    }
    for label, want in expected.items():
        got = classify(results[label])
        flag = "PASS" if got == want else "FAIL"
        if got != want:
            ok = False
        print(f"  [{flag}] {label:14s} expected={want!r} got={got!r}")
    print("\nOVERALL:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
