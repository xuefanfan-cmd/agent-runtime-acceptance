# coding: utf-8
"""REST / SSE 验收测试（TestClient）。验证 docs 描述的 wire 形态逐项成立。"""
from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

from rest_agent.runtime.rest import app


@pytest.fixture(scope="module")
def client() -> TestClient:
    return TestClient(app)


CONV = "conv-test-1"
URL = f"/v1/proj1/agents/example-agent/conversations/{CONV}"


def _stream_frames(text: str) -> list[dict]:
    frames = []
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("data: "):
            frames.append(json.loads(line[len("data: "):]))
    return frames


def test_health(client: TestClient) -> None:
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "healthy"


def test_stream_sse_shape_and_final_answer(client: TestClient) -> None:
    """流式：SSE 帧序 thought → tool_start → final_answer_chunk，终答在流结束前。"""
    r = client.post(URL, json={"input": {"query": "查询余额"}, "stream": True})
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("text/event-stream")
    frames = _stream_frames(r.text)
    events = [f["custom_rsp_data"]["event"] for f in frames]
    assert events == ["thought", "tool_start", "final_answer_chunk"]
    # 终答内容必须在流正常结束前出现，且无哨兵帧（compatibility 边界 #1）
    assert frames[-1]["custom_rsp_data"]["content"] == "余额为 100.00 元"
    assert "completed" not in events


def test_stream_headers(client: TestClient) -> None:
    r = client.post(URL, json={"input": {"query": "x"}, "stream": True})
    assert r.headers.get("cache-control") == "no-cache"
    assert r.headers.get("x-accel-buffering") == "no"


def test_aggregate_non_streaming(client: TestClient) -> None:
    """非流式：聚合 JSON `{success, answer}`，answer 位取终答。

    注意：聚合响应**不含** conversation_id/agent_id 字段（与流式每帧的信封不同）——
    这是实际 wire 形态，doc 未明确写出，需探测确认（记入 VERIFY-REPORT 缺口）。
    """
    r = client.post(URL, json={"input": {"query": "查询余额"}, "stream": False})
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is True
    assert body["answer"] == "余额为 100.00 元"


def test_channel_route_not_found_envelope(client: TestClient) -> None:
    """路径不匹配 → 404 + channel_route_not_found 信封（docs 记录的对外错误信封）。"""
    r = client.post("/v1/not-a-channel", json={"input": {"query": "x"}})
    assert r.status_code == 404
    body = r.json()
    assert body["success"] is False
    assert body["error"] == "channel_route_not_found"


def test_bad_content_type_415(client: TestClient) -> None:
    """媒体类型不符 → 415 unsupported_media_type 信封。"""
    r = client.post(
        URL, data="x", headers={"content-type": "text/plain"}
    )
    assert r.status_code == 415
    assert r.json()["error"] == "unsupported_media_type"


def test_custom_data_inputs_query_form(client: TestClient) -> None:
    """query 也可经 custom_data.inputs.query 传入（MobileBankChannel._extract_query 路径）。"""
    r = client.post(
        URL,
        json={"custom_data": {"inputs": {"query": "查询余额"}}, "stream": False},
    )
    assert r.status_code == 200
    assert r.json()["answer"] == "余额为 100.00 元"
