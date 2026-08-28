# coding: utf-8
"""本地 fake Versatile 远端 HTTP 服务（南向 wire 双端探针的远端一侧）。

这个服务是**真实 TCP HTTP 服务**（stdlib ``http.server`` + 线程），
它不是进程内替身——VersatileClient 通过 httpx 经真实 socket 打到它，
因此往返覆盖了：出站请求体 / 头 / 查询串、响应分帧（``data:`` 前缀剥离、
逐行完整 JSON 解析）、状态码路径、连接建立与关闭。

## wire 形态（与 versatile/client.py 的约束严格一致）

    HTTP POST + JSON 请求体 → 响应按行分帧（一行一个完整 JSON）+ 可选 ``data:`` 前缀

- 不聚合 SSE 事件块：空行不作事件边界。
- 每行必须是一个完整 JSON；非 JSON 起始行按 SSE 字段行处理（可透传）。

## 路由

模式由 **conversation_id 前缀**决定（同时验证 build_url 把会话标识渲染进地址）：

| conversation_id 前缀 | 服务行为 | 预期终态 |
|---|---|---|
| ``conv-success-*`` | 1 条文本内容帧 + 1 条 End 业务终态帧 | 完成（终答） |
| ``conv-http-error-*`` | 返回 HTTP 500 | VERSATILE_HTTP_STATUS_ERROR（传输失败） |
| ``conv-remote-error-*`` | 返回 error 事件帧（带远端原生错误码） | VersatileRemoteError（业务失败） |
| ``conv-no-terminal-*`` | 1 条文本内容帧后直接关闭，无 End 无结束信号 | VERSATILE_STREAM_CLOSED_WITHOUT_TERMINAL（失败） |
| ``conv-interrupt-*`` | end 结束信号但无业务终态（远端在等输入） | 中断（of_interrupt） |
| ``conv-raw-*`` | 先发一条非 JSON 行再发 End | 完成（含 raw 帧透传） |
"""
from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 远端帧形态全部取自真实存量判据（.legacy-oracle/.../test_versatile_proxy.py、
# test_runner_stream.py 逐字形态），不是编造的。
FRAME_TEXT = '{"event":"message","type":"text","data":{"content":"hello from remote"}}\n'
FRAME_END = '{"event":"message","data":{"node_type":"End","is_finished":true}}\n'
FRAME_ERROR = (
    '{"event":"error","data":{"code":"E_NO_MATCH","message":"\\u672a\\u627e\\u5230\\u5339\\u914d\\u7684\\u610f\\u56fe"}}\n'
)
FRAME_RAW = 'event: message\n'  # 非 JSON 起始行 → SSE 字段行
FRAME_END_SIGNAL = '{"event":"end"}\n'  # 流结束信号，但无业务终态


def mode_for_conversation(conversation_id: str) -> str:
    """按会话标识前缀决定服务行为；未知前缀回退到 success（便于手工探）。"""
    for prefix, mode in (
        ("conv-http-error-", "http_error"),
        ("conv-remote-error-", "remote_error"),
        ("conv-no-terminal-", "no_terminal"),
        ("conv-interrupt-", "interrupt"),
        ("conv-raw-", "raw"),
        ("conv-success-", "success"),
    ):
        if conversation_id.startswith(prefix):
            return mode
    return "success"


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # 静默，避免刷屏
        pass

    def _read_body(self) -> str:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return ""
        return self.rfile.read(length).decode("utf-8", "replace")

    def _send_headers(self, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Content-Length", "0")  # 会被 _stream 覆盖
        self.end_headers()

    def _stream(self, lines: list[str]) -> None:
        """以流式行分帧写响应体；逐行 flush，模拟真实远端流。"""
        body = "".join(lines).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()

    def do_POST(self):  # noqa: N802 - http.server 命名约定
        body = self._read_body()
        # 路径形如 /versatile/{conversation_id}；从最后一段取会话标识
        conversation_id = self.path.rstrip("/").rsplit("/", 1)[-1]
        mode = mode_for_conversation(conversation_id)

        if mode == "http_error":
            self.send_response(500)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            msg = b"internal error"
            self.send_header("Content-Length", str(len(msg)))
            self.end_headers()
            self.wfile.write(msg)
            self.wfile.flush()
            return

        if mode == "success":
            self._stream([FRAME_TEXT, FRAME_END])
            return
        if mode == "remote_error":
            self._stream([FRAME_ERROR])
            return
        if mode == "no_terminal":
            # 有内容帧、无 End 节点、无结束信号 → 直接关闭（连接异常断开的等价形态）
            self._stream([FRAME_TEXT])
            return
        if mode == "interrupt":
            # 结束信号但无业务终态 → 远端在等输入
            self._stream([FRAME_END_SIGNAL])
            return
        if mode == "raw":
            self._stream([FRAME_RAW, FRAME_END])
            return
        self._stream([FRAME_TEXT, FRAME_END])


class FakeVersatileService:
    """在独立线程里跑一个真实 HTTP 服务。"""

    def __init__(self, host: str = "127.0.0.1", port: int = 0) -> None:
        self._server = ThreadingHTTPServer((host, port), _Handler)
        self.host, self.port = self._server.server_address[:2]
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    def url_template(self) -> str:
        """VersatileConfig.url_template —— 会话标识被渲染进地址路径。"""
        return f"{self.base_url}/versatile/{{conversation_id}}"

    def start(self) -> "FakeVersatileService":
        self._thread.start()
        return self

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)


if __name__ == "__main__":
    svc = FakeVersatileService().start()
    print(f"fake versatile remote listening on {svc.base_url}")
    print(f"url_template = {svc.url_template()}")
    try:
        import time

        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        svc.stop()
