#!/usr/bin/env bash
# =============================================================================
# Scenario-driver script for the three-layer model-call timeout probe demo.
#
# Launches the probe agent + stub LLM server (per-scenario mode), posts a tiny
# prompt to the agent's /v1/query endpoint, redirects server stdout to a log
# file under logs/, then runs trace-asserter.sh against that log.
#
# No real LLM is used: every scenario reaches the stub, which deterministically
# produces exactly the stall behaviour that triggers one timeout layer.
#
# Usage:
#   ./run-scenarios.sh                  # run all seven scenarios in sequence
#   ./run-scenarios.sh s2 s5            # only the named scenarios
# =============================================================================
set -euo pipefail

# Scrub inherited HTTP proxy env so the probe-agent's outbound model call
# tries the configured api-base directly. Without this, a corporate intranet
# proxy will MITM every request and return its own 5xx body (observed as
# [181001] HTTP 504 ... HIS Proxy Notification) before the three-layer
# timeout machinery can fire. Also disable JVM system-proxy auto-discovery
# via the java-opts injected into start_stub / start_probe below.
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy
NO_PROXY="127.0.0.1,localhost,10.255.255.1,192.0.2.1"
no_proxy="${NO_PROXY}"
export NO_PROXY no_proxy
JAVA_PROXY_OFF_OPTS="-Djava.net.useSystemProxies=false -Dhttp.proxyHost= -Dhttps.proxyHost="

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${HERE}/.." && pwd)"
LOG_DIR="${PROJECT_ROOT}/logs"
PROBE_JAR_GLOB="${PROJECT_ROOT}/timeout-probe-agent/target/timeout-probe-agent-*.jar"
STUB_JAR_GLOB="${PROJECT_ROOT}/llm-stub-server/target/llm-stub-server-*.jar"
PROBE_PORT="${TIMEOUT_PROBE_PORT:-18092}"
STUB_PORT="${LLM_STUB_PORT:-18094}"
STUB_URL_DEFAULT="http://127.0.0.1:${STUB_PORT}/v1"
# Unroutable blackhole target used by S1 to force a TCP connect timeout.
# TEST-NET-1 (192.0.2.0/24) is reserved for documentation but may route locally;
# the 10.255.255.1 address is a conventional non-routable sink that reliably
# syn-drops in common lab setups. Override with LLM_API_BASE_UNREACHABLE if the
# host network behaves differently.
BLACKHOLE_URL="${LLM_API_BASE_UNREACHABLE:-http://10.255.255.1:81}"

ALL_SCENARIOS=(s0 s1 s2 s3 s4 s5 s6)
REQUESTED=("$@")
if [[ ${#REQUESTED[@]} -eq 0 ]]; then
    REQUESTED=("${ALL_SCENARIOS[@]}")
fi

mkdir -p "${LOG_DIR}"

stub_mode_for_scenario() {
    case "$1" in
        s0) echo "ok" ;;
        s1) echo "ok" ;;  # Stub still runs but is unreachable from the agent's angle.
        s2) echo "header-wait-silent" ;;
        s3) echo "after-first-segment-idle" ;;
        s4) echo "long-answer-heartbeat" ;;
        s5) echo "idle-silent-blocking" ;;
        s6) echo "long-hold-for-cancel" ;;
        *)  echo "FATAL: unknown scenario $1" >&2; exit 1 ;;
    esac
}

api_base_for_scenario() {
    case "$1" in
        s1) echo "${BLACKHOLE_URL}" ;;
        *)  echo "${STUB_URL_DEFAULT}" ;;
    esac
}

# Per-scenario stub overrides that keep the smoke run fast while still proving
# the timeout layer under test. The stub always runs even when the agent cannot
# reach it (S1) — it is cheap and keeps the control flow identical.
# S3 explicitly stalls 310s to exceed the SDK's 300s minimum streamIdleSeconds.
stub_env_overrides_for_scenario() {
    case "$1" in
        s2) echo "LLM_STUB_HEADER_SILENCE_MS=12000" ;;
        s3) echo "LLM_STUB_FIRST_SEGMENT_MS=310000" ;;
        s4) echo "LLM_STUB_LONG_ANSWER_MS=15000 LLM_STUB_LONG_ANSWER_HB_MS=1500" ;;
        s5) echo "LLM_STUB_BLOCKING_IDLE_MS=12000" ;;
        s6) echo "LLM_STUB_LONG_HOLD_MS=30000" ;;
        *)  echo "" ;;
    esac
}

# Curl timeout cap. S6 drives caller-side cancellation via --max-time 3.
curl_max_time_for_scenario() {
    case "$1" in
        s6) echo "3" ;;
        *)  echo "600" ;;
    esac
}

# Per-scenario `stream` field for the /v1/query POST payload. S3/S4/S6 require
# the SSE path on the handler (JiuwenCoreAgentHandler.executeAgentStreaming →
# Runner.runAgentStreaming) because their stub behaviours (after-first-segment
# idle / heartbeat / long-hold-for-cancel) only make sense over a real SSE
# response body. S0/S1/S2/S5 drive the non-stream path (handler.query →
# executeAgent → Runner.runAgent) — S2/S5 header-wait fires regardless of body
# shape; S1 never gets a byte back because TCP connect times out; S0 is pure
# happy-path. QueryMvcController.handleQuery branches on request.isStream(),
# so this flag alone decides which handler method the probe-agent executes.
stream_flag_for_scenario() {
    case "$1" in
        s3|s4|s6) echo "true" ;;
        *)        echo "false" ;;
    esac
}

# Grace period after posting the request before killing the agent and running
# the asserter. Must exceed the configured timeout for the scenario under test.
# S3's 330s is the SDK consequence: streamIdleSeconds is validated in [300,900]s
# so AFTER_FIRST_SEGMENT cannot be exercised faster than ~5 minutes.
post_wait_for_scenario() {
    case "$1" in
        s0) echo "6" ;;
        s1) echo "12" ;;
        s2) echo "15" ;;
        s3) echo "330" ;;
        s4) echo "25" ;;
        s5) echo "15" ;;
        s6) echo "10" ;;
    esac
}

wait_for_port() {
    local port="$1"
    local attempts=0
    while ! (echo > "/dev/tcp/127.0.0.1/${port}") >/dev/null 2>&1; do
        attempts=$((attempts + 1))
        if [[ ${attempts} -gt 120 ]]; then
            echo "FATAL: port ${port} never came up" >&2
            return 1
        fi
        sleep 1
    done
}

jar_path() {
    local glob="$1"
    local matches=(${glob})
    if [[ ! -e "${matches[0]}" ]]; then
        echo "FATAL: no jar matched ${glob}; run mvn package first" >&2
        exit 1
    fi
    echo "${matches[0]}"
}

start_stub() {
    local scenario="$1"
    local log="$2"
    local jar
    jar="$(jar_path "${STUB_JAR_GLOB}")"
    local mode
    mode="$(stub_mode_for_scenario "${scenario}")"
    local extras
    extras="$(stub_env_overrides_for_scenario "${scenario}")"

    env ${extras} \
        LLM_STUB_PORT="${STUB_PORT}" \
        LLM_STUB_DEFAULT_MODE="${mode}" \
        nohup java ${JAVA_PROXY_OFF_OPTS} -jar "${jar}" > "${log}" 2>&1 &
    echo $!
}

start_probe() {
    local scenario="$1"
    local log="$2"
    local jar
    jar="$(jar_path "${PROBE_JAR_GLOB}")"
    local api_base
    api_base="$(api_base_for_scenario "${scenario}")"

    env \
        TIMEOUT_PROBE_PORT="${PROBE_PORT}" \
        LLM_API_BASE="${api_base}" \
        nohup java ${JAVA_PROXY_OFF_OPTS} -jar "${jar}" \
            --spring.profiles.active="${scenario}" > "${log}" 2>&1 &
    echo $!
}

post_task() {
    local scenario="$1"
    local max_time
    max_time="$(curl_max_time_for_scenario "${scenario}")"
    local stream_flag
    stream_flag="$(stream_flag_for_scenario "${scenario}")"
    local conv_id="timeout-tiering-${scenario}-$(date +%s)"
    # --noproxy keeps the loopback call from being hijacked by a global
    # HTTP_PROXY (e.g., corporate intranet gateway on dev machines); without
    # it the request never reaches probe-agent and every asserter that pins
    # on 181008 silently fails.
    # -N disables curl's output buffer so SSE chunks drain as they arrive;
    # harmless for non-stream scenarios. We discard the body because the
    # asserter inspects the probe-agent log, not the HTTP response.
    curl -sS -N --noproxy '127.0.0.1,localhost' --max-time "${max_time}" -o /dev/null -X POST "http://127.0.0.1:${PROBE_PORT}/v1/query" \
        -H "Content-Type: application/json" \
        -d "{\"conversation_id\":\"${conv_id}\",\"stream\":${stream_flag},\"messages\":[{\"role\":\"user\",\"content\":\"Say one word.\"}]}" || true
}

run_one_scenario() {
    local scenario="$1"
    local timestamp
    timestamp="$(date +%Y%m%d-%H%M%S)"
    local probe_log="${LOG_DIR}/${timestamp}-${scenario}-probe.log"
    local stub_log="${LOG_DIR}/${timestamp}-${scenario}-stub.log"
    local request_log="${LOG_DIR}/${timestamp}-${scenario}-request.log"

    echo "===== scenario ${scenario} ====="
    echo "  probe log: ${probe_log}"
    echo "  stub log:  ${stub_log}"

    local stub_pid
    stub_pid="$(start_stub "${scenario}" "${stub_log}")"
    trap 'kill '"${stub_pid}"' 2>/dev/null || true' EXIT
    wait_for_port "${STUB_PORT}"

    local probe_pid
    probe_pid="$(start_probe "${scenario}" "${probe_log}")"
    trap 'kill '"${probe_pid}"' '"${stub_pid}"' 2>/dev/null || true' EXIT
    wait_for_port "${PROBE_PORT}"

    post_task "${scenario}" > "${request_log}" 2>&1 || true

    local wait_seconds
    wait_seconds="$(post_wait_for_scenario "${scenario}")"
    sleep "${wait_seconds}"
    kill "${probe_pid}" 2>/dev/null || true
    kill "${stub_pid}"  2>/dev/null || true
    wait 2>/dev/null || true
    trap - EXIT

    "${HERE}/trace-asserter.sh" "${scenario}" "${probe_log}"
}

for scenario in "${REQUESTED[@]}"; do
    run_one_scenario "${scenario}"
done
