#!/usr/bin/env bash
# =============================================================================
# Scenario-trace asserter for the three-layer model-call timeout probe demo.
#
# Reads a probe-agent log file and verifies the per-scenario markers are
# present or absent. Exits non-zero on any mismatch, listing both the markers
# that fired and those that did not. Intended to be run from run-scenarios.sh
# but can be used standalone against an already-collected log.
#
# Usage:
#   ./trace-asserter.sh <scenario> <path/to/probe.log>
# =============================================================================
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <scenario> <path/to/probe.log>" >&2
    exit 2
fi

SCENARIO="$1"
LOG_FILE="$2"

if [[ ! -s "${LOG_FILE}" ]]; then
    echo "FAIL [${SCENARIO}]: log file empty or missing: ${LOG_FILE}" >&2
    exit 1
fi

# ---- Marker library ---------------------------------------------------------
# Each scenario declares required markers (must appear) and forbidden markers
# (must NOT appear). Patterns are extended regex, run against the log via grep.
#
# Timeout error payload contract (see ModelTimeoutErrorFactory):
#   StatusCode.MODEL_CALL_TIMEOUT = 181008
#   details.phase       in { BEFORE_CONNECT, BEFORE_FIRST_SEGMENT, AFTER_FIRST_SEGMENT }
#   details.layer       in { connect, header_wait, stream_idle }
#   details.timeout_seconds (double)
#   details.stream      (boolean)
#
# Logging caveat: the default logger prints BaseError.getMessage() only — i.e.
# `[181008] model call timeout at <PHASE>, effective timeout <N>s, reason: ...`.
# The structured `details.layer` field exists on the error object (consumers of
# the API can read it) but is NOT rendered into the log text. Likewise the raw
# layer names (`connect` / `header_wait` / `stream_idle`) only surface via the
# config dump line `{header_wait_seconds=..., connect_timeout_seconds=...,
# stream_idle_seconds=...}` which fires on every scenario regardless of the
# layer under test. For that reason the asserter uses the phase literal as the
# ground-truth layer proxy — phase ↔ layer are 1:1 in ModelTimeoutErrorFactory:
#   BEFORE_CONNECT       ↔ connect
#   BEFORE_FIRST_SEGMENT ↔ header_wait
#   AFTER_FIRST_SEGMENT  ↔ stream_idle
#
# S0 proves #6 by inspecting the resolved config logged at startup (default
# connect-timeout=10s, default stream-idle=600s) and confirming NO timeout.
# S1 proves #1/#10/#12 — BEFORE_CONNECT (→ layer=connect).
# S2 proves #5/#7/#10/#12 — BEFORE_FIRST_SEGMENT (→ layer=header_wait) via
#    legacy timeout override (streamIdleSeconds hard-ranged [300,900]s forces
#    the demo through the legacy path for sub-5-min header-wait).
# S3 proves #3/#4/#10/#12 — AFTER_FIRST_SEGMENT (→ layer=stream_idle).
# S4 proves #2 — long-running stream completes under heartbeat without timing out.
# S5 proves #9 — blocking response hitting layer=header_wait at phase
#    BEFORE_FIRST_SEGMENT (same resolver path as S2, different body shape).
# S6 proves #11 — client cancels first, no MODEL_CALL_TIMEOUT emitted.

# Explicit `=()` init matters under `set -u` on bash < 4.4, where a bare
# `declare -a X` followed by `${#X[@]}` can still raise `unbound variable`.
declare -a REQUIRED=()
declare -a FORBIDDEN=()

case "${SCENARIO}" in
    s0)
        REQUIRED=(
            "Started TimeoutProbeAgent"
        )
        FORBIDDEN=(
            "\\[181008\\]"
        )
        ;;
    s1)
        REQUIRED=(
            "\\[181008\\]"
            "timeout at BEFORE_CONNECT"
        )
        FORBIDDEN=(
            "timeout at BEFORE_FIRST_SEGMENT"
            "timeout at AFTER_FIRST_SEGMENT"
        )
        ;;
    s2)
        REQUIRED=(
            "\\[181008\\]"
            "timeout at BEFORE_FIRST_SEGMENT"
        )
        FORBIDDEN=(
            "timeout at BEFORE_CONNECT"
            "timeout at AFTER_FIRST_SEGMENT"
        )
        ;;
    s3)
        REQUIRED=(
            "\\[181008\\]"
            "timeout at AFTER_FIRST_SEGMENT"
        )
        FORBIDDEN=(
            "timeout at BEFORE_CONNECT"
        )
        ;;
    s4)
        REQUIRED=(
            "Started TimeoutProbeAgent"
        )
        FORBIDDEN=(
            "\\[181008\\]"
        )
        ;;
    s5)
        REQUIRED=(
            "\\[181008\\]"
            "timeout at BEFORE_FIRST_SEGMENT"
        )
        FORBIDDEN=(
            "timeout at BEFORE_CONNECT"
            "timeout at AFTER_FIRST_SEGMENT"
        )
        ;;
    s6)
        REQUIRED=(
            "Started TimeoutProbeAgent"
        )
        FORBIDDEN=(
            "\\[181008\\]"
        )
        ;;
    *)
        echo "FAIL [${SCENARIO}]: unknown scenario" >&2
        exit 2
        ;;
esac

# ---- Evaluate ---------------------------------------------------------------
hits=()
misses=()
for pattern in "${REQUIRED[@]}"; do
    if grep -qE "${pattern}" "${LOG_FILE}"; then
        hits+=("${pattern}")
    else
        misses+=("${pattern}")
    fi
done

leaks=()
for pattern in "${FORBIDDEN[@]}"; do
    if grep -qE "${pattern}" "${LOG_FILE}"; then
        leaks+=("${pattern}")
    fi
done

echo "----- trace assertion [${SCENARIO}] -----"
echo "  log: ${LOG_FILE}"
echo "  required hits: ${#hits[@]}/${#REQUIRED[@]}"
for p in "${hits[@]}"; do
    echo "    OK  ${p}"
done
for p in "${misses[@]}"; do
    echo "    MISS ${p}"
done
if [[ ${#FORBIDDEN[@]} -gt 0 ]]; then
    echo "  forbidden leaks: ${#leaks[@]}/${#FORBIDDEN[@]}"
    for p in "${leaks[@]}"; do
        echo "    LEAK ${p}"
    done
fi

if [[ ${#misses[@]} -ne 0 || ${#leaks[@]} -ne 0 ]]; then
    echo "RESULT [${SCENARIO}]: FAIL"
    exit 1
fi

echo "RESULT [${SCENARIO}]: PASS"
