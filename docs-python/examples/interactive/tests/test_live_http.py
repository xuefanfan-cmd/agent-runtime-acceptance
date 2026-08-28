# coding: utf-8
"""真实 Uvicorn + HTTP 端到端验证：interrupt → input_required → resume/continue。

启动真实 uvicorn（子进程，独立端口），用 httpx 发真实 HTTP POST 两次：
  1. 首轮：读 SSE 字节，断言出现 interrupt_start（success=True）且内容正确；
  2. 同会话第二轮：读 SSE 字节，断言续接被识别、产出 final_answer_chunk 终答，
     且补充输入与 recovery_point_id 被交回。

验证重点是**原始 SSE 字节 / 事件顺序 / 终答内容 / 媒体类型**，不能只断言 JSON。

用法（在本工程根目录）：
    RUNTIME_ROOT=/path/to/agent-runtime-ext-python \\
      PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests/test_live_http.py
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
import uuid

import httpx

from interactive_agent.runtime.configuration import AGENT_ID, INTERACTION_ID, INTERRUPT_CONTENT, PROJECT_ID, RESUME_SUPPLEMENT


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _parse_sse(body: str) -> list[dict]:
    events: list[dict] = []
    for line in body.splitlines():
        line = line.strip()
        if line.startswith("data:"):
            payload = line[len("data:"):].strip()
            if payload:
                events.append(json.loads(payload))
    return events


def main() -> int:
    port = _free_port()
    conversation_id = f"conv-live-{uuid.uuid4().hex[:12]}"
    path = f"/v1/{PROJECT_ID}/agents/{AGENT_ID}/conversations/{conversation_id}"

    # 用当前解释器起子进程：它已经装好 runtime 与协议依赖，换机器不用改代码
    venv_python = sys.executable
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    env = dict(os.environ)
    # runtime 检出路径由 RUNTIME_ROOT 给出；已把 runtime 装进环境时可以不设
    env["PYTHONPATH"] = os.pathsep.join(
        p for p in (
            os.path.join(project_root, "src"),
            os.environ.get("RUNTIME_ROOT", ""),
            os.environ.get("PYTHONPATH", ""),
        ) if p
    )

    proc = subprocess.Popen(
        [venv_python, "-m", "uvicorn", "interactive_agent.runtime.rest:app",
         "--host", "127.0.0.1", "--port", str(port), "--log-level", "warning"],
        cwd=project_root,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )

    base = f"http://127.0.0.1:{port}"
    url = base + path
    ok = True
    try:
        # 等待就绪
        deadline = time.time() + 20
        ready = False
        while time.time() < deadline:
            if proc.poll() is not None:
                print("uvicorn exited early:", proc.stderr.read().decode())
                return 1
            try:
                httpx.get(base + "/docs", timeout=0.5)
                ready = True
                break
            except Exception:
                time.sleep(0.2)
        if not ready:
            print("uvicorn did not become ready in time")
            return 1
        print(f"[OK] uvicorn ready on {base}")

        # ---- 第一轮：触发 interrupt ----
        with httpx.stream("POST", url, json={"input": {"query": "帮我查余额"}, "stream": True},
                          timeout=10) as resp:
            assert resp.status_code == 200, f"turn1 status={resp.status_code}"
            assert resp.headers["content-type"].startswith("text/event-stream"), \
                f"turn1 media={resp.headers.get('content-type')}"
            body1 = "".join(resp.iter_text())
        events1 = _parse_sse(body1)
        assert events1, "首轮空流"
        int_frames = [e for e in events1 if e["custom_rsp_data"]["event"] == "interrupt_start"]
        assert int_frames, f"首轮无 interrupt_start 帧: {events1}"
        assert int_frames[0]["success"] is True
        assert int_frames[0]["custom_rsp_data"]["content"] == INTERRUPT_CONTENT
        print(f"[PASS] 首轮 interrupt_start: content={int_frames[0]['custom_rsp_data']['content']!r}")

        # ---- 第二轮：同会话续接 ----
        with httpx.stream("POST", url, json={"input": {"query": RESUME_SUPPLEMENT}, "stream": True},
                          timeout=10) as resp:
            assert resp.status_code == 200, f"turn2 status={resp.status_code}"
            body2 = "".join(resp.iter_text())
        events2 = _parse_sse(body2)
        final = [e for e in events2 if e["custom_rsp_data"]["event"] == "final_answer_chunk"]
        assert final, f"续接轮无 final_answer_chunk 帧: {events2}"
        content = final[0]["custom_rsp_data"]["content"]
        assert RESUME_SUPPLEMENT in content, f"补充输入未交回: {content!r}"
        assert INTERACTION_ID in content, f"recovery_point_id 未交回: {content!r}"
        print(f"[PASS] 续接轮 final_answer_chunk: {content!r}")

        print("\n== LIVE HTTP RESULT ==")
        print(f"media-type      : {resp.headers['content-type']}")
        print(f"turn1 events    : {[e['custom_rsp_data']['event'] for e in events1]}")
        print(f"turn2 events    : {[e['custom_rsp_data']['event'] for e in events2]}")
        print(f"final answer    : {content!r}")
        print("RESULT: PASS")
        return 0
    except AssertionError as exc:
        ok = False
        print(f"\nRESULT: FAIL -> {exc}")
        return 1
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
        out, err = proc.communicate()
        if err:
            sys.stderr.write("---- uvicorn stderr ----\n" + err.decode() + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
