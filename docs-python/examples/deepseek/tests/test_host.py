# coding: utf-8
"""真实请求验证：用 starlette TestClient 打真实 REST 工厂装配出的应用。

TestClient 以 context manager 进入时会执行 ASGI lifespan，从而触发
`handler.start()` / `handler.stop()` 与就绪视图——这验证了
`docs/api/agent-runtime-python.md` 与 `docs/architecture/02-agent-runtime-python技术架构.md`
的生命周期路径，而不只是路由。
"""
from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

from deepseek_agent.runtime.handler import FixtureHandler
from deepseek_agent.runtime.application import MemorySessionStore, build_app

#: 文档 `api/agent-runtime-python.md` 的典型 REST 路径。
CONVERSATION = "conv-1"
STREAM_URL = f"/v1/project-a/agents/deepseek-validation-agent/conversations/{CONVERSATION}"


@pytest.fixture()
def client() -> TestClient:
    with TestClient(build_app()) as c:
        yield c


def _stream_body(*, stream: bool = True) -> dict:
    return {"input": {"query": "hello"}, "stream": stream}


def test_streaming_returns_sse_frames(client: TestClient) -> None:
    """流式路径：thought / tool_start 逐帧投影为 SSE 信封。

    当前 runtime 的 MobileBankChannel 会投影终答内容帧；终答仍必须在流
    正常结束前出现，非流式路径同时在 answer 字段承载聚合结果。
    """
    resp = client.post(STREAM_URL, json=_stream_body())
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/event-stream")
    frames = [json.loads(line[6:]) for line in resp.text.strip().split("\n\n")]
    event_types = [f["custom_rsp_data"]["event"] for f in frames]
    assert "thought" in event_types
    assert "tool_start" in event_types
    assert event_types[-1] == "final_answer_chunk"
    # 信封携带会话与智能体标识。
    assert frames[0]["conversation_id"] == CONVERSATION
    assert frames[0]["agent_id"] == "deepseek-validation-agent"


def test_aggregate_returns_envelope(client: TestClient) -> None:
    """非流式路径：聚合响应（success/answer 信封，终答由 answer 承载）。"""
    resp = client.post(STREAM_URL, json=_stream_body(stream=False))
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    assert body["answer"] == "fixture answer"


def test_channel_route_not_found_envelope(client: TestClient) -> None:
    """错误出口：路径不匹配返回 channel_route_not_found 信封（api/agent-runtime-python.md）。"""
    resp = client.post("/v1/foo/bar", json=_stream_body())
    assert resp.status_code == 404
    body = resp.json()
    assert body["success"] is False
    assert body["error"] == "channel_route_not_found"


def test_unsupported_media_type_envelope(client: TestClient) -> None:
    """错误出口：非 application/json 返回 unsupported_media_type（415）。"""
    resp = client.post(
        STREAM_URL, content="not json", headers={"content-type": "text/plain"}
    )
    assert resp.status_code == 415
    body = resp.json()
    assert body["success"] is False
    assert body["error"] == "unsupported_media_type"


def test_session_store_with_injected_store(client: TestClient) -> None:
    """注入同一 SessionRequestStore 后，请求快照确实落盘。"""
    store = MemorySessionStore()

    # 直接构造带注入 store 的应用。
    from fastapi import FastAPI
    from agent_runtime.bootstrap.rest_app import create_rest_app
    from agent_runtime.adapters.inbound.rest.mobile_bank import MobileBankChannel

    app: FastAPI = create_rest_app(
        FixtureHandler(),
        channel=MobileBankChannel(),
        session_store=store,
    )
    with TestClient(app) as c:
        c.post(STREAM_URL, json=_stream_body())

    import asyncio

    snap = asyncio.get_event_loop().run_until_complete(store.get_request(CONVERSATION))
    assert snap is not None
    assert snap["agent_id"] == "deepseek-validation-agent"
    # 快照五字段（headers/trace_id/agent_id/params/body），不承载 conversation_id。
    assert snap["body"]["input"]["query"] == "hello"


def test_config_boundary_backend() -> None:
    """配置边界：非 fixture 后端被拒绝（本宿主只支持确定性 fixture）。"""
    from deepseek_agent.runtime.configuration import HostConfig

    cfg = HostConfig.load({"RUNTIME_BACKEND": "agentcore"})
    assert cfg.backend == "agentcore"
    with pytest.raises(ValueError):
        build_app(cfg)
