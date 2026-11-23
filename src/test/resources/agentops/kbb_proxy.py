#!/usr/bin/env python3
"""FEAT-105 故障注入代理（127.0.0.1:6399 → 127.0.0.1:6333），keep-alive 版。

模式（KBB_PROXY_MODE）：
  stall_first_write : 写请求（PUT/POST /points 且 body 含 "points"）挂起 45s 不转发
  tamper_owner      : 写请求转发但篡改 payload.agent_ops_owner
  stall_query       : 召回检索（POST /points/query）挂起 45s（A7 分母不变式）
  stall_probe       : GET /collections 挂起 45s（probe 超时 → 503）
  auth_required     : Authorization/api-key ≠ KBB_PROXY_EXPECTED_KEY → 401
其余请求透传。keep-alive：同一客户端连接循环处理多请求；上游连接按客户端连接复用。
"""
import asyncio
import json
import os

UP_HOST, UP_PORT = "127.0.0.1", 6333
LISTEN_HOST, LISTEN_PORT = "127.0.0.1", 6399
MODE = os.environ.get("KBB_PROXY_MODE", "stall_first_write")
STALL_SECONDS = 45.0
FOREIGN_OWNER = "foreign-process-mitm"


def log(*args):
    print("[kbb-proxy]", *args, flush=True)


async def read_request(reader):
    head = b""
    while b"\r\n\r\n" not in head:
        chunk = await asyncio.wait_for(reader.read(4096), timeout=30)
        if not chunk:
            return None
        head += chunk
    head_part, rest = head.split(b"\r\n\r\n", 1)
    lines = head_part.decode("latin1").split("\r\n")
    method, path, _ = lines[0].split(" ", 2)
    headers = {}
    for ln in lines[1:]:
        if ":" in ln:
            k, v = ln.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    clen = int(headers.get("content-length") or 0)
    body = rest
    while len(body) < clen:
        chunk = await asyncio.wait_for(reader.read(65536), timeout=30)
        if not chunk:
            break
        body += chunk
    return method.upper(), path, headers, body


async def relay_response(up_reader, client_writer):
    """读上游响应头，按 content-length/chunked 完整读取后一次性写给客户端。"""
    head = b""
    while b"\r\n\r\n" not in head:
        chunk = await asyncio.wait_for(up_reader.read(4096), timeout=60)
        if not chunk:
            return
        head += chunk
    head_part, rest = head.split(b"\r\n\r\n", 1)
    lines = head_part.decode("latin1").split("\r\n")
    headers = {}
    for ln in lines[1:]:
        if ":" in ln:
            k, v = ln.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    if "chunked" in headers.get("transfer-encoding", "").lower():
        # chunked → 完整解帧为原始字节，改写为 content-length 响应（消灭分帧问题）
        dechunked = b""
        buf = rest
        while True:
            while b"\r\n" not in buf:
                buf += await asyncio.wait_for(up_reader.read(4096), timeout=60)
            size_line, buf = buf.split(b"\r\n", 1)
            size = int(size_line.strip() or b"0", 16)
            if size == 0:
                break
            while len(buf) < size:
                buf += await asyncio.wait_for(up_reader.read(65536), timeout=60)
            dechunked += buf[:size]
            buf = buf[size:]
            while b"\r\n" not in buf[:2] and len(buf) < 2:
                buf += await asyncio.wait_for(up_reader.read(4096), timeout=60)
            buf = buf[2:]
        new_head = head_part.decode("latin1").split("\r\n")
        new_head = [l for l in new_head if "transfer-encoding" not in l.lower()]
        new_head.append(f"content-length: {len(dechunked)}")
        client_writer.write(("\r\n".join(new_head) + "\r\n\r\n").encode("latin1") + dechunked)
        await client_writer.drain()
        return
    clen = int(headers.get("content-length") or 0)
    body = rest
    while len(body) < clen:
        chunk = await asyncio.wait_for(up_reader.read(65536), timeout=60)
        if not chunk:
            break
        body += chunk
    client_writer.write(head_part + b"\r\n\r\n" + body)
    await client_writer.drain()


async def handle(reader, writer):
    up_reader = up_writer = None
    try:
        while True:  # keep-alive：同一客户端连接循环处理多请求
            req = await read_request(reader)
            if req is None:
                break
            method, path, headers, body = req
            log(MODE, method, path[:60])
            log("DBG: after log line")

            if MODE == "auth_required":
                expected = os.environ.get("KBB_PROXY_EXPECTED_KEY", "correct-token")
                auth = headers.get("authorization", "") or headers.get("api-key", "")
                if auth not in (expected, "Bearer " + expected):
                    log("auth rejected")
                    body401 = b'{"error": {"message": "Unauthorized"}}'
                    writer.write(b"HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\n"
                                 + b"Content-Length: " + str(len(body401)).encode() + b"\r\n\r\n" + body401)
                    await writer.drain()
                    break
                log("auth ok")

            is_write = (method in ("POST", "PUT") and "/points" in path
                        and "/points/query" not in path and "/points/delete" not in path
                        and b'"points"' in body)
            is_query = method == "POST" and "/points/query" in path

            if MODE == "stall_first_write" and is_write:
                log("stalling write (never forwarded)")
                await asyncio.sleep(STALL_SECONDS)
                break
            if MODE == "stall_query" and is_query:
                log("stalling recall query (recall error → denominator invariant)")
                await asyncio.sleep(STALL_SECONDS)
                break
            if MODE == "stall_probe" and method == "GET" and "/collections" in path:
                log("stalling probe (probe timeout → 503)")
                await asyncio.sleep(STALL_SECONDS)
                break

            out_body = body
            if MODE == "tamper_owner" and is_write:
                try:
                    d = json.loads(body)
                    for pt in d.get("points", []):
                        pt.setdefault("payload", {})["agent_ops_owner"] = FOREIGN_OWNER
                    out_body = json.dumps(d).encode()
                    log("owner tampered")
                except Exception as exc:
                    log("tamper failed, forwarding raw:", exc)

            log("DBG: before open_connection")
            if up_writer is None:
                up_reader, up_writer = await asyncio.open_connection(UP_HOST, UP_PORT)
            log("DBG: upstream opened")
            req_line = f"{method} {path} HTTP/1.1\r\n"
            hdr_lines = "".join(
                f"{k}: {v}\r\n" for k, v in headers.items() if k.lower() != "content-length")
            up_writer.write(req_line.encode() + hdr_lines.encode()
                            + f"content-length: {len(out_body)}\r\n\r\n".encode() + out_body)
            await up_writer.drain()
            log("DBG: request forwarded, relaying response")
            await relay_response(up_reader, writer)
            log("DBG: response relayed")
            if headers.get("connection", "").lower() == "close":
                break
    except Exception as exc:
        log("handler error:", type(exc).__name__, str(exc)[:120])
    finally:
        for w in (up_writer, writer):
            try:
                if w is not None:
                    w.close()
            except Exception:
                pass


async def main():
    srv = await asyncio.start_server(handle, LISTEN_HOST, LISTEN_PORT)
    log("listening on", LISTEN_PORT, "mode=", MODE)
    async with srv:
        await srv.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
