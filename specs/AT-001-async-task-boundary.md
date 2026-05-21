---
id: AT-001
title: Asynchronous Long-Running Task Boundary
status: active
sut_capability: create-long-task
authority:
  - kind: first-principle
    statement: |
      A request that triggers long-horizon work must not hold the client
      transport thread for the duration of the work. Holding the connection
      conflates client liveness with work liveness, blocks cancellation,
      and exhausts server thread pools under burst.
  - kind: industry-pattern
    statement: |
      Async Request-Reply with a polled task handle is the canonical shape
      for long-running work over HTTP.
    references:
      - "RFC 9110 §15.3.3 — 202 Accepted semantics"
      - "RFC 7231 §4.3.3 — POST semantics for delayed-resource creation"
      - "Microsoft Cloud Design Patterns — Async Request-Reply"
      - "Mark Massé, REST API Design Rulebook (O'Reilly 2011) §5.2"
  - kind: human-factors
    statement: |
      An acknowledgement budget of 200 ms is the upper bound below which a
      human operator perceives the response as immediate.
    references:
      - "Nielsen, J. (1993). Usability Engineering. §5.5 Response Time"
      - "Card, S. K., Robertson, G. G., & Mackinlay, J. D. (1991). The
         Information Visualizer. CHI '91."
  - kind: security-pattern
    statement: |
      Tying request acceptance to worker progress amplifies resource
      exhaustion attack surfaces. The cursor pattern caps blast radius:
      hostile or slow workers cannot starve healthy endpoints.
    references:
      - "OWASP API Security Top 10 — API4:2023 Unrestricted Resource Consumption"
---

# AT-001 — Asynchronous Long-Running Task Boundary

> **In one line:** A POST that triggers long-running work MUST hand back a
> task handle within 200 ms — even if the worker is slow, stuck, or
> failing.

## The picture

```
client                            SUT                            worker
  │                                │                                │
  ├─ POST /create-long-task ──────►│                                │
  │                                ├─ enqueue work ────────────────►│
  │◄─ 202 {handle, pollUrl} ───────┤    (work proceeds              │
  │       (within 200 ms)          │     independently;             │
  │                                │     may take minutes or hours) │
  │                                │                                │
  ├─ POST /cancel/{handle} ───────►│                                │
  │◄─ 200 OK ──────────────────────┤    (cancel reaches the worker  │
  │                                │     even before it began)      │
  │                                │                                │
  ├─ GET  /poll/{handle} ─────────►│                                │
  │◄─ 200 {status: RUNNING|...} ───┤                                │
```

A SUT that holds the client connection until the worker completes —
even when "the worker is fast in dev" hides the symptom — fails this
test.

## Why we care

When a system accepts a request that triggers long-horizon work, it
must not hold the client transport thread for the duration of the
work. Holding the connection has three consequences, each of them
sufficient on its own to require the async pattern:

1. **Liveness conflation.** The worker cannot keep going if the
   client's TCP connection times out. Work that should be measured in
   minutes or hours is now bounded by the client's idle timeout.
2. **No cancellation verb.** A held connection cannot be cancelled
   cleanly; only the cursor pattern admits a separate cancel operation.
   The client's only option is "kill the TCP socket", which leaves the
   SUT in an ambiguous state.
3. **Thread-pool exhaustion under burst.** Each in-flight request pins
   one OS thread (or one reactive subscriber). Under burst, latency-
   sensitive endpoints (health, status, cancel) starve.

RFC 9110 §15.3.3 codifies HTTP 202 Accepted for exactly this case:
*"the request has been accepted for processing, but the processing
has not been completed."* Microsoft's Cloud Design Patterns documents
the **Async Request-Reply** pattern as the canonical shape. Mark
Massé's *REST API Design Rulebook* §5.2 makes it a rule for any HTTP
API hosting long-running work.

The **200 ms** budget comes from human-factors research. Nielsen 1993
§5.5 (building on Card et al. 1991) establishes that above 200 ms,
users perceive a system as "doing something now", not "responsive". A
system that exceeds the threshold has, by definition, performed
user-visible work inside what should be a free acknowledgement.

The security angle is **OWASP API Security Top 10 — API4:2023
(Unrestricted Resource Consumption)**. Coupling acknowledgement to
worker progress amplifies the blast radius of any slow or hostile
worker: such workers can starve healthy endpoints by pinning OS
threads.

## What PASSing looks like

A trace from a conformant SUT, with the worker deliberately delayed
to 30 seconds via the adapter hook:

```
T+0 ms       POST /v1/runs                   → leaves client
T+38 ms      response status                 ← HTTP 202
T+40 ms      response body                   ← {"runId":  "f3a1...",
                                                "pollUrl": "/v1/runs/f3a1..."}
T+45 ms      POST /v1/runs/f3a1.../cancel    → leaves client
T+71 ms      response status                 ← HTTP 200
T+72 ms      response body                   ← {"runId": "f3a1...",
                                                "status": "CANCELLED"}
```

| Check                | Result | Notes                                            |
|----------------------|--------|--------------------------------------------------|
| Acknowledgement < 200 ms | ✅ | 38 ms ≪ 200 ms                                  |
| Body shape valid     | ✅     | task handle + poll address present; no `result` |
| Cancel reachable     | ✅     | same handle returned 200 within 30 ms           |
| Worker independence  | ✅     | ack arrived 30 s before worker would have       |

## What FAILing looks like

The same stimulus against a non-conformant SUT (one that lacks an
async path and runs the worker inline):

```
T+0 ms        POST /v1/runs                  → leaves client
              (... client waits ...)
T+30 000 ms   response status                ← HTTP 200
T+30 001 ms   response body                  ← {"runId": "f3a1...",
                                                "result": "world-hello"}
                                                ^^^^^^^^
                                                worker output baked into
                                                the acknowledgement
```

| Check                | Result | Notes                                                         |
|----------------------|--------|---------------------------------------------------------------|
| Acknowledgement < 200 ms | ❌ | 30 s ≫ 200 ms — SUT held the connection until worker done    |
| Body shape valid     | ❌     | response carries `result` — SUT has no async path             |
| Cancel reachable     | ⛔     | un-tested — no handle returned in time to cancel              |
| Worker independence  | ❌     | ack tracks worker latency 1:1                                 |

## The five sub-clauses

Each sub-clause is one observable invariant in Given / When / Then form.
The SUT is conformant to AT-001 iff every sub-clause holds (or honestly
reports INCONCLUSIVE).

### AT-001.A — Acknowledgement latency

- **Given:** the SUT is booted in a steady state; the worker delay is
  set to ≥ 30 s via the adapter hook (if available).
- **When:** the client sends 30 sequential POSTs to `create-long-task`
  after 5 warm-up requests.
- **Then:** the **p99** latency from "last request byte sent" to "first
  response byte received" is ≤ **200 ms**.

### AT-001.B — Acknowledgement shape

- **Given:** any response collected during AT-001.A.
- **When:** the client parses the response body as JSON.
- **Then:**
  - status code is in `200..299` (HTTP 202 strongly preferred);
  - body contains a **task handle** string of length ≥ 16;
  - body contains a **poll address** non-empty string;
  - body contains **no** field named in the adapter's
    `terminal_output_jsonpaths_forbidden` list (e.g. `$.result`,
    `$.output`).

### AT-001.C — Acknowledgement independence from worker progress

- **Given:** the worker delay is forced to **≥ 30 000 ms** via the
  adapter hook `set_worker_delay_ms`.
- **When:** the client runs the AT-001.A stimulus.
- **Then:** the **p99** acknowledgement latency is still ≤ **200 ms** —
  i.e. the SUT did not couple acknowledgement to worker progress.
- **INCONCLUSIVE if:** the adapter declares `set_worker_delay_ms` as
  unavailable. The test cannot inject the stimulus, so it cannot
  pronounce PASS or FAIL.

### AT-001.D — Cancellability between ack and worker start

- **Given:** a fresh task handle returned by AT-001.A.
- **When:** the client sends `cancel-task` against that handle within
  100 ms of receiving the acknowledgement.
- **Then:** cancel returns either:
  - `2xx` — cancel accepted, or task already terminal; or
  - `4xx` — cancel refused with a stated reason in the body.
- **NEVER:**
  - `5xx` — SUT treats cancel as an internal-error path; or
  - `404` — handle is not addressable, contradicting AT-001.B.

### AT-001.E — Task durability across client disconnect

- **Given:** a fresh task handle returned by AT-001.A; worker delay
  still ≥ 30 s so the task is guaranteed to still be in flight.
- **When:** the client forcibly closes the TCP connection within
  50 ms of the ack, waits 5 000 ms, then polls the handle on a fresh
  connection.
- **Then:** the poll returns `2xx` with a state field that maps (via
  the adapter) to one of the abstract states defined in
  [000-state-vocabulary.md](000-state-vocabulary.md):
  `RUNNING` · `SUCCEEDED` · `FAILED` · `CANCELLED` · `EXPIRED` ·
  `SUSPENDED`.
- **FAIL if:** the poll returns `404` AND the adapter has NOT declared
  `disconnect_triggered_erasure: true`.
- **INCONCLUSIVE if:** the adapter cannot induce a worker delay, so
  the task may legitimately have terminated before the disconnect.

## What this test does NOT assert

- The exact format of the task handle (UUID, ULID, opaque base64).
  SUT choice.
- The transport of state updates (long-poll, SSE, WebSocket, webhook).
  Orthogonal.
- The exact HTTP status code, beyond the constraints in AT-001.B.
- The correctness of the eventual task output. Covered by AT-002 and
  later.
- The semantics of cancellation in detail (refund-on-cancel,
  partial-side-effect rollback). Covered by AT-003.
- Behaviour under sustained load. Covered by AT-007 (throughput).
- The poll mechanism's own latency or correctness. Covered by AT-004
  (polling).

## Procedure

1. Bring up the SUT per its adapter's `boot.sh`. Wait for the SUT's
   `health` endpoint to return a healthy verdict. Boot timeout: 120 s;
   on timeout, abort with verdict `INCONCLUSIVE`.
2. Configure the worker delay to 30 000 ms via the adapter's
   `set_worker_delay_ms` hook. If the adapter declares this hook is
   unavailable, sub-clauses AT-001.C and AT-001.E are reported as
   `INCONCLUSIVE`; sub-clauses A, B, D continue normally.
3. **Warm-up.** Send 5 sequential POSTs to `create-long-task`. Discard
   the responses' timings.
4. **Measure batch.** Send 30 sequential POSTs to `create-long-task`.
   For each, record:
   - `t_request_end` — wall-clock at last request byte.
   - `t_response_start` — wall-clock at first response byte.
   - `status` — HTTP status code.
   - `body` — full response body, parsed as JSON.
5. Compute `latency_ms[i] = t_response_start - t_request_end` for each
   request. Sort; report `p50`, `p95`, `p99`.
6. **Validate shape.** For each of the 30 responses:
   - Status code in `200..299`.
   - Body contains the field at
     `adapter.response_shape.task_handle_jsonpath`; value is a string
     of length ≥ 16.
   - Body contains the field at
     `adapter.response_shape.poll_address_jsonpath`; value is a
     non-empty string.
   - Body contains no field at the JSONPath listed in
     `adapter.response_shape.terminal_output_jsonpaths_forbidden`.
7. **AT-001.A verdict.** `PASS` iff `p99 ≤ 200`.
8. **AT-001.B verdict.** `PASS` iff every response satisfied the shape
   constraints in step 6.
9. **AT-001.C verdict.** `PASS` iff steps 4–7 passed under the
   30 000 ms worker delay configured in step 2. (The same data feeds
   A, B, and C; the distinction is in interpretation, not in
   measurements.)
10. **AT-001.D — cancel test.** Take the first task handle from step 4.
    Within 100 ms of receiving its acknowledgement, issue a
    `cancel-task` request. Record status. `PASS` iff status ∈
    `200..299` ∪ `400..499` (excluding `404`), within 100 ms of
    request transmission.
11. **AT-001.E — disconnect test.**
    1. Send one additional POST to `create-long-task`; capture the
       acknowledgement and task handle.
    2. Forcibly close the TCP connection within 50 ms of the
       acknowledgement.
    3. Wait 5 000 ms.
    4. Issue `poll-task` against the handle (on a new connection).
    5. `PASS` iff the response is `2xx` and the state field maps (per
       the adapter) to one of the abstract states listed in
       AT-001.E above.

## Reporting

A conformance report for AT-001 against a single SUT MUST contain:

```yaml
test: AT-001
sut: <sut-id from adapter>
sut_self_reported_version: <opaque string from SUT's health endpoint>
test_suite_version: <new repo's git SHA>
ran_at: <ISO-8601 UTC>
verdict: PASS | FAIL | INCONCLUSIVE
sub_clauses:
  AT-001.A: PASS | FAIL | INCONCLUSIVE
  AT-001.B: PASS | FAIL | INCONCLUSIVE
  AT-001.C: PASS | FAIL | INCONCLUSIVE
  AT-001.D: PASS | FAIL | INCONCLUSIVE
  AT-001.E: PASS | FAIL | INCONCLUSIVE
measurements:
  p50_ms: <int>
  p95_ms: <int>
  p99_ms: <int>
  status_codes_observed: [<int>, ...]
  shape_violations: <int>
  cancel_status: <int>
  cancel_latency_ms: <int>
  post_disconnect_state: <string>
environment:
  cpu_model: <string>
  vcpu_count: <int>
  memory_gb: <int>
  hostname: <string>          # for traceability; not for SUT identification
  network_mode: <string>      # e.g. "loopback", "lan", "container-network"
adapter_capabilities:
  set_worker_delay_ms_available: <bool>
  disconnect_triggered_erasure_declared: <bool>
notes: <free text from the test runner>
```

**Verdict aggregation:** the top-level `verdict` is `PASS` iff every
sub-clause is `PASS` or `INCONCLUSIVE` AND at least one is `PASS`. If
any sub-clause is `FAIL`, top-level is `FAIL`. If every sub-clause is
`INCONCLUSIVE`, the top-level is `INCONCLUSIVE` (the SUT did not
expose enough surface to be testable).

## SUT-agnosticism

This test does not require the SUT to be written in any specific
language or framework, nor to use any specific persistence or
scheduling technology. A SUT may realise the cursor pattern via:

- HTTP 202 + `Location` header + `GET <Location>`, or
- HTTP 200 + JSON body containing handle + `GET /<base>/tasks/{id}`, or
- HTTP 202 + body containing handle + WebSocket upgrade for live state,
  or
- gRPC `CreateTask` + separate `GetTask` (the adapter wraps gRPC
  behind the abstract HTTP capability), or
- any other realisation in which an acknowledgement is returned and a
  separate polled handle is the source of truth for task state.

All such realisations are valid as long as the five sub-clauses hold.

## Adapter requirements

An adapter targeting this test MUST declare (in its `adapter.yaml`):

| Field                                                             | Required | Notes                                                       |
|-------------------------------------------------------------------|----------|-------------------------------------------------------------|
| `endpoints.create_long_task` (method + URL template)              | yes      | the endpoint under test                                     |
| `endpoints.poll_task` (method + URL template with handle slot)    | yes      | for AT-001.E                                                |
| `endpoints.cancel_task` (method + URL template with handle slot)  | yes      | for AT-001.D                                                |
| `endpoints.health` (method + URL template)                        | yes      | for boot wait                                               |
| `hooks.set_worker_delay_ms` (mechanism + signature)               | no       | absence → AT-001.C and AT-001.E report `INCONCLUSIVE`       |
| `response_shape.task_handle_jsonpath`                             | yes      | e.g. `$.runId`                                              |
| `response_shape.poll_address_jsonpath`                            | yes      | e.g. `$.pollUrl`                                            |
| `response_shape.terminal_output_jsonpaths_forbidden`              | no       | list; default `[]`                                          |
| `state_mapping`                                                   | yes      | maps SUT-specific state names to AT-001.E's abstract states |
| `disconnect_triggered_erasure`                                    | no       | default `false`; if `true` adapter emits a warning in report|

If a required field is missing, the test framework refuses to run
AT-001 against that SUT and records `INCONCLUSIVE` with the adapter
validation error. This protects the report's integrity — silent
skipping is worse than honest INCONCLUSIVE.
