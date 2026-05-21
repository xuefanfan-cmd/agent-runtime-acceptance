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

## What this test asserts

The SUT exposes an endpoint that the adapter has bound to the abstract
capability `create-long-task` (see [../sut/sut-contract.md](../sut/sut-contract.md)).
The SUT is conformant to AT-001 if and only if **all five sub-clauses**
below hold simultaneously.

### AT-001.A — Acknowledgement latency

A `POST` to the `create-long-task` endpoint MUST receive a complete
response status line within **200 ms at p99** over a 30-request warm-JVM
sequence, measured from the client transmitting the last byte of the
request to the client receiving the first byte of the response.

### AT-001.B — Acknowledgement shape

The response status code MUST be in the range `200..299` with semantics
"work accepted, not yet complete" (HTTP 202 strongly preferred). The
response body MUST be a JSON object containing at minimum:

| Field name (per adapter map) | Type   | Constraint                                          |
|------------------------------|--------|-----------------------------------------------------|
| task handle                  | string | length ≥ 16; unique to this task                    |
| poll address                 | string | non-empty; resolves under the SUT's base authority  |

The body MUST NOT contain any field whose semantics is "terminal output of
the task" (e.g. `result`, `output`, `terminal_payload`). Terminal output
is observable only after polling, not at acknowledgement.

### AT-001.C — Acknowledgement independence from worker progress

The latency budget in AT-001.A MUST hold even when the SUT's worker
subsystem is artificially delayed by **≥ 30 seconds**. The test sets the
delay via the adapter-declared hook `set_worker_delay_ms`. An SUT whose
acknowledgement latency tracks worker latency has coupled them and FAILS
this sub-clause, even if AT-001.A passes in isolation.

### AT-001.D — Cancellability between acknowledgement and worker start

There MUST exist a non-zero time window after acknowledgement during which
a `cancel-task` operation on the returned handle is honoured. The operation
MUST return:

- `2xx` (cancel accepted, or task already terminal), OR
- `4xx` with an explanatory body (cancel refused for a stated reason).

It MUST NOT return:

- `5xx` (the SUT considers cancellation an internal-error path), OR
- `404` (the handle is not addressable — meaning AT-001.B was a lie).

### AT-001.E — Task durability across client disconnect

After acknowledgement, the task's existence and progress MUST NOT depend
on the client TCP connection. The test forces a client disconnect within
50 ms of the acknowledgement; after 5 seconds, a poll of the handle MUST
return a documented state. Acceptable states:

`RUNNING` · `SUCCEEDED` · `FAILED` · `CANCELLED` · `EXPIRED` · `SUSPENDED`

The state names listed above are the abstract states this acceptance
suite recognises. The SUT may use synonyms; the adapter declares the
mapping. A `404` on the polled handle FAILS this sub-clause unless the
SUT's adapter has declared `disconnect_triggered_erasure: true` AND that
declaration is itself flagged for warning in the report.

## What this test does NOT assert

- The exact format of the task handle (UUID, ULID, opaque base64). SUT choice.
- The transport of state updates (long-poll, SSE, WebSocket, webhook). Orthogonal.
- The exact HTTP status code, beyond the constraints in AT-001.B.
- The correctness of the eventual task output. Covered by AT-002+.
- The semantics of cancellation in detail (refund-on-cancel, partial-side-effect rollback). Covered by AT-003.
- Behaviour under sustained load. Covered by AT-007 (throughput).
- The poll mechanism's own latency or correctness. Covered by AT-004 (polling).

## Procedure

1. Bring up the SUT per its adapter's `boot.sh`. Wait for the SUT's
   `health` endpoint to return a healthy verdict. Boot timeout: 120 s; on
   timeout, abort with verdict `INCONCLUSIVE`.
2. Configure the worker delay to 30 000 ms via the adapter's
   `set_worker_delay_ms` hook. If the adapter declares this hook is
   unavailable, sub-clauses AT-001.C and AT-001.E are reported as
   `INCONCLUSIVE`; sub-clauses A, B, D continue normally.
3. **Warm-up.** Send 5 sequential POSTs to `create-long-task`. Discard
   the responses' timings.
4. **Measure batch.** Send 30 sequential POSTs to `create-long-task`. For
   each, record:
   - `t_request_end` — wall-clock at last request byte.
   - `t_response_start` — wall-clock at first response byte.
   - `status` — HTTP status code.
   - `body` — full response body, parsed as JSON.
5. Compute `latency_ms[i] = t_response_start - t_request_end` for each
   request. Sort; report `p50`, `p95`, `p99`.
6. **Validate shape.** For each of the 30 responses:
   - Status code in `200..299`.
   - Body contains the field at `adapter.response_shape.task_handle_jsonpath`;
     value is a string of length ≥ 16.
   - Body contains the field at `adapter.response_shape.poll_address_jsonpath`;
     value is a non-empty string.
   - Body contains no field at the JSONPath listed in
     `adapter.response_shape.terminal_output_jsonpaths_forbidden`.
7. **AT-001.A verdict.** `PASS` iff `p99 ≤ 200`.
8. **AT-001.B verdict.** `PASS` iff every response satisfied the shape
   constraints in step 6.
9. **AT-001.C verdict.** `PASS` iff steps 4–7 passed under the 30 000 ms
   worker delay configured in step 2. (The same data feeds A, B, and C;
   the distinction is in interpretation, not in measurements.)
10. **AT-001.D — cancel test.** Take the first task handle from step 4.
    Within 100 ms of receiving its acknowledgement, issue a `cancel-task`
    request. Record status. `PASS` iff status ∈ `200..299` ∪ `400..499`
    (excluding `404`), within 100 ms of request transmission.
11. **AT-001.E — disconnect test.**
    1. Send one additional POST to `create-long-task`; capture the
       acknowledgement and task handle.
    2. Forcibly close the TCP connection within 50 ms of the
       acknowledgement.
    3. Wait 5 000 ms.
    4. Issue `poll-task` against the handle (on a new connection).
    5. `PASS` iff the response is `2xx` and the state field maps (per
       adapter) to one of the abstract states listed in AT-001.E above.

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

Verdict aggregation: the top-level `verdict` is `PASS` iff every sub-clause
is `PASS` or `INCONCLUSIVE` AND at least one is `PASS`. If any sub-clause
is `FAIL`, top-level is `FAIL`. If every sub-clause is `INCONCLUSIVE`, the
top-level is `INCONCLUSIVE` (the SUT did not expose enough surface to be
testable).

## SUT-agnosticism

This test does not require the SUT to be written in any specific language
or framework, nor to use any specific persistence or scheduling
technology. A SUT may realise the cursor pattern via:

- HTTP 202 + `Location` header + `GET <Location>`, or
- HTTP 200 + JSON body containing handle + `GET /<base>/tasks/{id}`, or
- HTTP 202 + body containing handle + WebSocket upgrade for live state, or
- gRPC `CreateTask` + separate `GetTask` (the adapter wraps gRPC behind
  the abstract HTTP capability), or
- any other realisation in which an acknowledgement is returned and a
  separate polled handle is the source of truth for task state.

All such realisations are valid as long as the five sub-clauses hold.

## Adapter requirements

An adapter targeting this test MUST declare (in its `adapter.yaml`):

| Field                                                           | Required | Notes                                                       |
|-----------------------------------------------------------------|----------|-------------------------------------------------------------|
| `endpoints.create_long_task` (method + URL template)            | yes      | the endpoint under test                                     |
| `endpoints.poll_task` (method + URL template with handle slot)  | yes      | for AT-001.E                                                |
| `endpoints.cancel_task` (method + URL template with handle slot)| yes      | for AT-001.D                                                |
| `endpoints.health` (method + URL template)                      | yes      | for boot wait                                               |
| `hooks.set_worker_delay_ms` (mechanism + signature)             | no       | absence → AT-001.C and AT-001.E report `INCONCLUSIVE`       |
| `response_shape.task_handle_jsonpath`                           | yes      | e.g. `$.runId`                                              |
| `response_shape.poll_address_jsonpath`                          | yes      | e.g. `$.pollUrl`                                            |
| `response_shape.terminal_output_jsonpaths_forbidden`            | no       | list; default `[]`                                          |
| `state_mapping`                                                 | yes      | maps SUT-specific state names to AT-001.E's abstract states |
| `disconnect_triggered_erasure`                                  | no       | default `false`; if `true` adapter emits a warning in report|

If a required field is missing, the test framework refuses to run AT-001
against that SUT and records `INCONCLUSIVE` with the adapter validation
error. This protects the report's integrity — silent skipping is worse
than honest INCONCLUSIVE.
