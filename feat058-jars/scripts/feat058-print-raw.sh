#!/usr/bin/env bash
# FEAT-058 ｜ HTTP response 完整打印（S1 connect-timeout 场景）
# 作用：绕开 fixture 的 parse 直接打 raw body，印每一条 SSE 事件的 type + 全 payload + 流外字段
#
# 用法：
#   cd /home/agent-runtime-acceptance
#   bash feat058-print-raw.sh
set -euo pipefail

PROBE_JAR=/home/openjiuwen/timeout-probe-agent-0.1.0.jar
STUB_JAR=/home/openjiuwen/llm-stub-server-0.1.0.jar
OUTDIR=/tmp/feat058-raw-$(date +%s)
mkdir -p "$OUTDIR"

# 1. 启 stub（任意模式，S1 不走到对端）
nohup java -jar "$STUB_JAR" > "$OUTDIR/stub.log" 2>&1 &
STUB_PID=$!

# 2. 启 probe-agent 用 s1 profile（connect-timeout=5s + api-base 指 TEST-NET-1 黑洞）
nohup java -jar "$PROBE_JAR" \
    --spring.profiles.active=s1 \
    > "$OUTDIR/probe.log" 2>&1 &
PROBE_PID=$!

trap 'kill -9 $PROBE_PID $STUB_PID 2>/dev/null || true' EXIT

# 3. 等 probe 就绪
for i in $(seq 1 60); do
    if (echo > /dev/tcp/127.0.0.1/18092) 2>/dev/null; then break; fi
    sleep 1
done
echo "=== probe ready after ${i}s ==="

# 4. 发请求，-i 带 header、-N 禁 buffer、原样打
CONV=sit-probe-$(date +%s)
echo "=== conversation_id: $CONV ==="
echo
echo "===== HTTP RESPONSE (headers + full raw body) ====="
curl -sS -i -N -X POST "http://127.0.0.1:18092/v1/query" \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream, application/json" \
    -d "{\"conversation_id\":\"$CONV\",\"message\":\"ping\"}" \
    | tee "$OUTDIR/response.raw"
echo
echo "===== /HTTP RESPONSE ====="
echo

# 5. 按 SSE event 拆开逐条 pretty-print（找 numericCode / metadata 等被 fixture 漏掉的字段）
echo "===== EVENT-BY-EVENT PRETTY ====="
awk '/^data: /{sub(/^data: /,""); print}' "$OUTDIR/response.raw" | awk 'NF' | while IFS= read -r line; do
    echo "--- event ---"
    echo "$line" | python3 -m json.tool || echo "$line"
done
echo "===== /EVENT-BY-EVENT ====="
echo

# 6. 顺手把日志里的 181008 行也印出来（和开发对账）
echo "===== PROBE LOG [181008] lines ====="
grep -E "\[181008\]|numericCode|metadata" "$OUTDIR/probe.log" || echo "(no match)"
echo
echo "artifacts under: $OUTDIR"
