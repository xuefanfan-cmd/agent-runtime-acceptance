# coding: utf-8
"""交互式语义验证：interrupt → input_required → resume/continue。

用**真实 runtime REST 工厂**（create_rest_app + MobileBankChannel）经
Starlette TestClient（httpx 传输层）验证完整交互链路：
  1. 首轮 POST（stream=true）→ SSE 中出现 `interrupt_start`（success=True），
     该会话被登记为等待续接（input_required）。
  2. 同会话第二条 POST → 被判为续接（resume/continue）→ 重走 stream_query，
     产出 `final_answer_chunk` 终答。

同时验证非流式（stream=false）路径的中断语义，与处理器计数器命中情况。

注意：REST 等待续接登记表是进程内、按 conversation 记的（无 task_store 的降级
装配）。故每个用例用**独立 app + handler + 独立 conversation**，避免状态串扰。
"""

from __future__ import annotations

import json
import uuid

import pytest
from fastapi.testclient import TestClient

from interactive_agent.runtime.configuration import (
    AGENT_ID,
    INTERACTION_ID,
    INTERRUPT_CONTENT,
    PROJECT_ID,
    RESUME_SUPPLEMENT,
)
from interactive_agent.runtime.rest import create_app


@pytest.fixture()
def ctx():
    """每个用例一个独立装配：app + handler + 独立 conversation_id。"""
    app, handler = create_app()
    conversation_id = f"conv-{uuid.uuid4().hex[:12]}"
    path = f"/v1/{PROJECT_ID}/agents/{AGENT_ID}/conversations/{conversation_id}"
    return {
        "app": app,
        "handler": handler,
        "conversation_id": conversation_id,
        "path": path,
    }


def _sse_events(resp) -> list[dict]:
    """解析 TestClient 流式响应体为事件信封列表。

    SSE 形态：`data: <信封 JSON>\n\n`，流结束即结束（无哨兵帧）。
    """
    events: list[dict] = []
    for line in resp.text.splitlines():
        line = line.strip()
        if line.startswith("data:"):
            payload = line[len("data:"):].strip()
            if payload:
                events.append(json.loads(payload))
    return events


def _rest_body(query: str, *, stream: bool) -> dict:
    return {"input": {"query": query}, "stream": stream}


def test_first_turn_emits_interrupt(ctx) -> None:
    """首轮（新会话）应产出 interrupt_start 帧，并登记等待续接。"""
    app, handler, path = ctx["app"], ctx["handler"], ctx["path"]

    resp = TestClient(app).post(path, json=_rest_body("帮我查余额", stream=True))

    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/event-stream")
    events = _sse_events(resp)
    assert events, "首轮应至少产出一帧"
    interrupt_frames = [e for e in events if e["custom_rsp_data"]["event"] == "interrupt_start"]
    assert interrupt_frames, "首轮应投影出 interrupt_start 帧"
    frame = interrupt_frames[0]
    assert frame["success"] is True
    assert frame["custom_rsp_data"]["content"] == INTERRUPT_CONTENT
    # 处理器确实走了首轮 interrupt 分支
    assert handler.interrupts_seen == 1
    assert handler.resumes_seen == 0


def test_second_turn_resumes_and_completes(ctx) -> None:
    """同会话第二条消息应被识别为续接，重走 stream_query 出终答。"""
    app, handler, path = ctx["app"], ctx["handler"], ctx["path"]
    client = TestClient(app)

    # 首轮：触发 interrupt 并登记等待续接
    r1 = client.post(path, json=_rest_body("帮我查余额", stream=True))
    assert r1.status_code == 200
    assert handler.interrupts_seen == 1

    # 第二轮：同会话，输入用户补充 → 应走续接
    r2 = client.post(path, json=_rest_body(RESUME_SUPPLEMENT, stream=True))
    assert r2.status_code == 200
    events = _sse_events(r2)
    final = [e for e in events if e["custom_rsp_data"]["event"] == "final_answer_chunk"]
    assert final, "续接轮应产出 final_answer_chunk 终答"
    content = final[0]["custom_rsp_data"]["content"]
    assert RESUME_SUPPLEMENT in content
    assert INTERACTION_ID in content  # recovery_point_id 被交回

    # 处理器确实命中续接分支，首轮 interrupt 计数不变
    assert handler.interrupts_seen == 1
    assert handler.resumes_seen == 1


def test_non_stream_first_turn_returns_interrupt_content(ctx) -> None:
    """非流式（stream=false）首轮：interrupt 不是错误，聚合不抛异常。"""
    app, path = ctx["app"], ctx["path"]
    resp = TestClient(app).post(path, json=_rest_body("帮我查余额", stream=False))
    # 非流式聚合对 interrupt 帧：既非终答也非 chunk/error，答案聚合为空但不报错。
    assert resp.status_code == 200


def test_different_conversation_is_not_resume(ctx) -> None:
    """同 app 下，不同会话的新请求不应被误判为续接。"""
    app, handler, conversation_id = ctx["app"], ctx["handler"], ctx["conversation_id"]
    client = TestClient(app)

    # 先让本用例的主会话中断一次
    client.post(ctx["path"], json=_rest_body("帮我查余额", stream=True))
    assert handler.interrupts_seen == 1

    # 不同会话的新请求 → 应走首轮 interrupt，而非续接
    other_conv = f"conv-other-{uuid.uuid4().hex[:8]}"
    other_path = f"/v1/{PROJECT_ID}/agents/{AGENT_ID}/conversations/{other_conv}"
    resp = client.post(other_path, json=_rest_body("新问题", stream=True))
    assert resp.status_code == 200
    events = _sse_events(resp)
    ev = [e for e in events if e["custom_rsp_data"]["event"] == "interrupt_start"]
    assert ev, "新会话应走首轮 interrupt，而非续接"
    assert handler.interrupts_seen == 2
    assert handler.resumes_seen == 0
