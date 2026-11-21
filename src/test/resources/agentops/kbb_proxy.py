#!/usr/bin/env python3
"""FEAT-105 B08/B09 故障注入代理（127.0.0.1:6399 → 127.0.0.1:6333）。

模式（环境变量 KBB_PROXY_MODE）：
  stall_first_write : knowledge 写请求（POST /points 且 body 含 "points"）挂起 45s 后
                      不转发直接断开——产品 adapter 超时 30s，触发 mark_write_unknown；
                      写请求从未到达真实 Qdrant（可断言真实库零残留）。
  tamper_owner      : 写请求转发，但把每个 point 的 payload.agent_ops_owner 篡改为
                      "foreign-process-mitm"——触发清理期 authorize_delete 失败 →
                      ownership_conflict（异主记录保留）。
其余请求（探针 GET、exact-get POST /points body=ids、delete）全部透传。
每连接单请求处理（响应后关闭两侧），httpx 会为后续请求新建连接。
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
    """读上游响应头，按 content-length / chunked 透传至客户端。"""
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
    client_writer.write(head)
    if headers.get("transfer-encoding", "").lower() == "chunked":
        client_writer.write(rest)
        await client_writer.drain()
        while True:
            line = await asyncio.wait_for(up_reader.readline(), timeout=60)
            client_writer.write(line)
            if line in (b"\r\n", b""):
                if line == b"\r\n":
                    size_line = await asyncio.wait_for(up_reader.readline(), timeout=60)
                    client_writer.write(size_line)
                    if size_line.strip() in (b"0", b""):
                        break
                    continue
                break
        await client_writer.drain()
        return
    clen = int(headers.get("content-length") or 0)
    body = rest
    while len(body) < clen:
        chunk = await asyncio.wait_for(up_reader.read(65536), timeout=60)
        if not chunk:
            break
        body += chunk
    client_writer.write(body)
    await client_writer.drain()


async def handle(reader, writer):
    try:
        req = await read_request(reader)
        if req is None:
            return
        method, path, headers, body = req
        is_upsert = (method in ("POST", "PUT") and "/points" in path
                     and "/points/query" not in path and "/points/delete" not in path
                     and b'"points"' in body)
        log(MODE, method, path[:60], "upsert" if is_upsert else "passthrough")

        if MODE == "stall_first_write" and is_upsert:
            log("stalling write for", STALL_SECONDS, "s (never forwarded)")
            await asyncio.sleep(STALL_SECONDS)
            return  # 不回响应直接断开：产品侧 30s 超时先到，触发 unknown

        out_body = body
        if MODE == "tamper_owner" and is_upsert:
            try:
                d = json.loads(body)
                for p in d.get("points", []):
                    p.setdefault("payload", {})["agent_ops_owner"] = FOREIGN_OWNER
                out_body = json.dumps(d).encode()
                log("owner tampered for", len(d.get("points", [])), "point(s)")
            except Exception as exc:
                log("tamper failed, forwarding raw:", exc)

        up_reader, up_writer = await asyncio.open_connection(UP_HOST, UP_PORT)
        req_line = f"{method} {path} HTTP/1.1\r\n"
        hdr_lines = "".join(
            f"{k}: {v}\r\n" for k, v in headers.items() if k.lower() != "content-length")
        up_writer.write(req_line.encode() + hdr_lines.encode()
                        + f"content-length: {len(out_body)}\r\n\r\n".encode() + out_body)
        await up_writer.drain()
        await relay_response(up_reader, writer)
        up_writer.close()
    except Exception as exc:
        log("handler error:", type(exc).__name__, str(exc)[:120])
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def main():
    srv = await asyncio.start_server(handle, LISTEN_HOST, LISTEN_PORT)
    log("listening on", LISTEN_PORT, "mode=", MODE)
    async with srv:
        await srv.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
