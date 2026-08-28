# coding: utf-8
"""标准 A2A 验收测试（TestClient 过 ASGI）。

覆盖 docs 描述的 A2A 对外契约逐项成立：
- Agent Card 发现（三条路径 + 内容 + 公开 endpoint）
- Task 提交 / 事件（流式 SSE 帧 + 状态机 SUBMITTED→WORKING→COMPLETED）
- 终态（SendMessage 聚合终态 + GetTask 终态查询 + ListTasks）
- 健康（宿主 /health）与基本协议请求（JSON-RPC 信封、错误码）

对**两种任务存储**（a2a-sdk 默认 InMemoryTaskStore 与自写 SqliteTaskStore 替身）
各跑一遍，验证 `create_a2a_app(task_store=...)` 接受协议库 TaskStore 注入。
"""
from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

from a2a_agent.runtime import a2a as app_module


def _msg(method: str, params: dict, rid: str = "1") -> dict:
    return {"jsonrpc": "2.0", "id": rid, "method": method, "params": params}


_SEND = {
    "message": {
        "messageId": "m1",
        "contextId": "c1",
        "role": "ROLE_USER",
        "parts": [{"text": "查询余额"}],
    }
}

A2A_HEADERS = {"A2A-Version": "1.0"}


def _data_frames(text: str) -> list[dict]:
    """从 SSE 文本里取全部 `data:` 行并解析成 JSON。"""
    frames = []
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("data:"):
            frames.append(json.loads(line[len("data:"):].strip()))
    return frames


@pytest.fixture(params=["memory", "sqlite"], ids=["InMemoryTaskStore", "SqliteTaskStore"])
def client(request: pytest.FixtureRequest) -> TestClient:
    """对两种任务存储各建一个应用实例。"""
    app = app_module.create_app_for_store(request.param)
    with TestClient(app) as c:
        yield c


# ── 健康与基本协议请求 ────────────────────────────────────────────


def test_health(client: TestClient) -> None:
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "healthy"
    assert body["agent"] == "a2a-fixture-agent"


def test_unknown_method_yields_32601(client: TestClient) -> None:
    """未知 method → JSON-RPC -32601（协议错误信封，不是裸 HTTP 错误）。"""
    r = client.post("/a2a/", json=_msg("no/such/method", {}, rid="X9"), headers=A2A_HEADERS)
    body = r.json()
    assert body["jsonrpc"] == "2.0"
    assert body["id"] == "X9"  # 错误响应必须回带请求 id
    assert body["error"]["code"] == -32601


def test_no_trailing_slash_serves_directly(client: TestClient) -> None:
    """`POST /a2a` 直接承载 JSON-RPC（MUST，不依赖 307 重定向）。"""
    r = client.post(
        "/a2a", json=_msg("SendMessage", _SEND), headers=A2A_HEADERS, follow_redirects=False
    )
    assert r.status_code == 200
    assert "result" in r.json()


# ── Agent Card ────────────────────────────────────────────────────


def test_standard_root_card_path(client: TestClient) -> None:
    r = client.get("/.well-known/agent-card.json")
    assert r.status_code == 200
    card = r.json()
    assert card["name"] == "a2a-fixture-agent"
    assert card["version"] == "1.0.0"
    assert card["capabilities"]["streaming"] is True


def test_compat_root_card_path(client: TestClient) -> None:
    assert client.get("/.well-known/agent.json").status_code == 200


def test_mounted_prefix_card_path(client: TestClient) -> None:
    assert client.get("/a2a/.well-known/agent-card.json").status_code == 200


def test_three_card_paths_identical(client: TestClient) -> None:
    bodies = [
        client.get(p).content
        for p in ("/.well-known/agent-card.json", "/.well-known/agent.json", "/a2a/.well-known/agent-card.json")
    ]
    assert bodies[0] == bodies[1] == bodies[2]


def test_card_exposes_public_endpoint(client: TestClient) -> None:
    """卡片必须在 supportedInterfaces[0].url 声明公开可调用地址（非 localhost 硬编码）。"""
    card = client.get("/.well-known/agent-card.json").json()
    url = card["supportedInterfaces"][0]["url"]
    assert url.endswith("/a2a/")
    assert url.startswith("http://")


# ── Task 提交 / 事件 / 终态 ───────────────────────────────────────


def test_send_message_reaches_completed_terminal(client: TestClient) -> None:
    """SendMessage（阻塞）→ result.task 达 COMPLETED 终态，终答随终态上线。"""
    r = client.post("/a2a/", json=_msg("SendMessage", _SEND), headers=A2A_HEADERS)
    assert r.status_code == 200
    task = r.json()["result"]["task"]
    assert task["status"]["state"] == "TASK_STATE_COMPLETED"
    assert task["status"]["message"]["parts"][0]["text"] == "余额为 100.00 元"


def test_send_message_emits_artifacts(client: TestClient) -> None:
    """一次执行出三样 artifact：thought → tool_start → final_answer_chunk。"""
    r = client.post("/a2a/", json=_msg("SendMessage", _SEND), headers=A2A_HEADERS)
    artifacts = r.json()["result"]["task"]["artifacts"]
    kinds = [a["metadata"]["type"] for a in artifacts]
    assert kinds == ["thought", "tool_start", "final_answer_chunk"]
    # 终答内容不丢
    assert artifacts[-1]["parts"][0]["data"]["content"] == "余额为 100.00 元"


def test_streaming_state_sequence_reaches_completed(client: TestClient) -> None:
    """SendStreamingMessage：SSE 帧状态机 SUBMITTED→WORKING→…→COMPLETED。"""
    with client.stream(
        "POST", "/a2a/", json=_msg("SendStreamingMessage", _SEND), headers=A2A_HEADERS
    ) as r:
        assert r.status_code == 200
        assert "text/event-stream" in r.headers.get("content-type", "")
        body = "".join(r.iter_text())

    frames = _data_frames(body)
    assert frames, "流式响应没有 data 帧"
    # 状态序列：首帧提交回执（result.task.status），随后是 statusUpdate 帧。
    seq = []
    for f in frames:
        res = f.get("result", {})
        if "task" in res:
            seq.append(res["task"]["status"]["state"])
        elif "statusUpdate" in res:
            seq.append(res["statusUpdate"]["status"]["state"])
    assert seq[0] == "TASK_STATE_SUBMITTED"
    assert "TASK_STATE_WORKING" in seq
    assert seq[-1] == "TASK_STATE_COMPLETED"
    # 事件帧把三样 artifact 带上线
    events = [f for f in frames if "artifactUpdate" in f.get("result", {})]
    kinds = [e["result"]["artifactUpdate"]["artifact"]["metadata"]["type"] for e in events]
    assert kinds == ["thought", "tool_start", "final_answer_chunk"]


def test_streaming_is_jsonrpc_envelope(client: TestClient) -> None:
    """流式 data 帧是 JSON-RPC 响应信封（event: jsonrpc 语义）。"""
    with client.stream(
        "POST", "/a2a/", json=_msg("SendStreamingMessage", _SEND), headers=A2A_HEADERS
    ) as r:
        body = "".join(r.iter_text())
    frames = _data_frames(body)
    assert all("jsonrpc" in f and f["jsonrpc"] == "2.0" for f in frames)


def test_get_task_returns_terminal_state(client: TestClient) -> None:
    """GetTask 按 id 取回任务快照，终态 COMPLETED 与终答俱在。"""
    r = client.post("/a2a/", json=_msg("SendMessage", _SEND), headers=A2A_HEADERS)
    tid = r.json()["result"]["task"]["id"]
    g = client.post("/a2a/", json=_msg("GetTask", {"id": tid}), headers=A2A_HEADERS)
    task = g.json()["result"]
    assert task["status"]["state"] == "TASK_STATE_COMPLETED"
    assert task["status"]["message"]["parts"][0]["text"] == "余额为 100.00 元"


def test_list_tasks_contains_submitted(client: TestClient) -> None:
    """ListTasks 列出已提交任务。"""
    client.post("/a2a/", json=_msg("SendMessage", _SEND), headers=A2A_HEADERS)
    l = client.post("/a2a/", json=_msg("ListTasks", {"contextId": "c1"}), headers=A2A_HEADERS)
    tasks = l.json()["result"]["tasks"]
    assert len(tasks) >= 1
    assert any(t["status"]["state"] == "TASK_STATE_COMPLETED" for t in tasks)
