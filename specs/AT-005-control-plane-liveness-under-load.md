---
id: AT-005
title: Control-Plane Liveness Under Data-Plane Saturation
status: active
sut_capability: health, cancel-task (under concurrent create-long-task load)
authority:
  - kind: first-principle
    statement: |
      A system whose control plane shares resources with its data plane
      has no escape hatch: when the data plane saturates (intentionally
      or under attack), operators lose the ability to observe, cancel,
      or intervene. Control and data MUST run on logically (and ideally
      physically) independent paths.
  - kind: industry-pattern
    statement: |
      Traffic-class separation, in-band vs out-of-band signalling, and
      quality-of-service tiers all express the same invariant: the
      operational liveness path is not subject to backpressure from the
      data path.
    references:
      - "RFC 2474 — Differentiated Services field in IP headers"
      - "Kubernetes API priority and fairness (APF) feature"
      - "AWS Lambda reserved concurrency"
      - "Erlang/OTP supervisor trees — supervisor lives outside worker pool"
  - kind: security-pattern
    statement: |
      Resource exhaustion attacks are explicitly catalogued. A SUT
      whose data-plane saturation also takes down its control plane
      multiplies the blast radius of every burst — accidental or
      hostile — by removing the operator's ability to intervene.
    references:
      - "OWASP API Security Top 10 — API4:2023 Unrestricted Resource Consumption"
      - "CWE-770 — Allocation of Resources Without Limits or Throttling"
  - kind: historic-incident
    statement: |
      Numerous public production outages have followed the same shape:
      a workload spike saturates the data path; control endpoints
      become slow or unresponsive; the operator's first attempt to
      mitigate (cancel jobs, fetch status, scale up) cannot complete;
      the incident extends until external action (kill the process,
      reroute the LB) is taken.
---

# AT-005 — Control-Plane Liveness Under Data-Plane Saturation

> **In one line:** When many heavy tasks are in flight, `health` and
> `cancel-task` MUST stay responsive — operators need an escape hatch
> exactly when the system is under stress.

## The picture

```
                         ┌── client lane A (data plane) ──┐
                         │  many parallel POSTs of        │
                         │  create-long-task with large   │
                         │  payloads (saturating data)    │
                         └────────────┬───────────────────┘
                                      │
                                      ▼
                              ┌───────────────┐
                              │      SUT      │
                              │  ┌─────────┐  │
                              │  │ control │  │  control-plane endpoints
                              │  │  plane  │  │  (health, cancel, status)
                              │  └─────────┘  │  MUST stay responsive
                              │  ┌─────────┐  │  independent of data plane
                              │  │  data   │  │
                              │  │  plane  │  │
                              │  └─────────┘  │
                              └───────┬───────┘
                                      ▲
                         ┌────────────┴───────────────────┐
                         │ client lane B (control plane)  │
                         │ probes GET /health every 1s    │
                         │ AND issues cancel-task         │
                         │ during the saturation window   │
                         └────────────────────────────────┘
```

The test issues concurrent load on the data path from lane A, and uses
lane B to verify that control responses still meet their latency
budgets. A SUT that fails to isolate the two will see lane B's latency
track lane A's queue depth.

## Why we care

Long-running systems have an asymmetric relationship between data and
control:

- **Data plane** is throughput-shaped. Bursts, retries, large payloads,
  slow workers are normal. Data-plane latency degrading under load is
  expected and acceptable within reason.
- **Control plane** is liveness-shaped. Operators reach for it precisely
  during incidents — to read status, to cancel jobs, to make decisions
  under time pressure. Control-plane latency degrading under load is
  catastrophic: the operator's intervention is itself blocked.

Every mature distributed system codifies this asymmetry. The Internet
itself does — RFC 2474 (DSCP) gives routers a way to prioritise control
traffic over bulk. Kubernetes' API Priority and Fairness (APF) feature
guarantees that LIST and WATCH calls from the controller manager do not
starve under user request load. AWS Lambda's reserved concurrency
guarantees that operational functions (e.g. cleanup) get a slice
independent of user-driven load. Erlang/OTP supervisors are processes
distinct from the worker pool by construction.

**OWASP API4:2023 (Unrestricted Resource Consumption)** and
**CWE-770** catalogue the failure mode. The defense pattern is
physical or logical traffic-class separation: control endpoints get
their own thread pool, their own scheduler queue, their own scaling
unit. The acceptance bar in AT-005 is the externally observable
outcome of that pattern: under saturation, `health` keeps responding
within budget, `cancel-task` keeps responding within budget.

The historic incident pattern is consistent — described in the
authority section above — and the lesson is consistent: an
unresponsive control plane during a data-plane incident extends mean
time to recovery by every minute operators cannot reach the system.

## What PASSing looks like

The test runs two lanes concurrently for 60 seconds. Lane A submits
many heavy create-long-task requests with maximum-size payloads. Lane
B continuously samples `health` and `cancel-task`. A conformant SUT:

```
[Lane A: data-plane saturation]
  T+0s        first create-long-task POST
  T+0..60s    1000+ concurrent submissions, each carrying max-size payload
  T+60s       stop submitting; data-plane queues at ~1000 in-flight

[Lane B: control-plane probes — sampled every 1 second]
  T+5s   GET /health    →  200 in 18 ms   ✓
  T+10s  GET /health    →  200 in 22 ms   ✓
  T+15s  GET /health    →  200 in 19 ms   ✓
  ...
  T+30s  GET /health    →  200 in 41 ms   ✓   (slight pressure visible)
  ...
  T+60s  GET /health    →  200 in 35 ms   ✓
  T+61s  POST /cancel/<handle from lane A>  →  200 in 48 ms   ✓
  T+62s  POST /cancel/<handle from lane A>  →  200 in 51 ms   ✓
```

| Check                                                          | Result | Notes                               |
|----------------------------------------------------------------|--------|-------------------------------------|
| Health p99 ≤ 200 ms across the 60s saturation window           | ✅     | observed p99 = 41 ms                |
| Cancel p99 ≤ 500 ms during saturation                          | ✅     | observed p99 = 51 ms                |
| Health success rate ≥ 99 %                                     | ✅     | 60/60 succeeded                     |
| Cancel success rate ≥ 95 % during saturation                   | ✅     | 10/10 succeeded                     |
| No 5xx on the control plane during saturation                  | ✅     |                                     |
| Data-plane latency degraded (expected, not a failure)          | ✅     | data plane p99 = 8 s — fine        |

## What FAILing looks like

A non-conformant SUT where control endpoints share the same thread
pool as task dispatch:

```
[Lane A: data-plane saturation]
  T+0..60s    1000+ concurrent submissions, thread pool exhausted

[Lane B: control-plane probes]
  T+5s   GET /health    →  200 in 28 ms   ✓
  T+10s  GET /health    →  200 in 4 200 ms ✗  ← latency budget blown
  T+15s  GET /health    →  503 Service Unavailable ✗
  T+20s  GET /health    →  (timeout after 10 s) ✗
  ...
  T+61s  POST /cancel/...  →  (timeout after 10 s) ✗
                              ^^^^^^^^^^
                              operator cannot cancel; incident extends
                              until external action
```

| Check                                                          | Result | Notes                                                            |
|----------------------------------------------------------------|--------|------------------------------------------------------------------|
| Health p99 ≤ 200 ms across the 60s saturation window           | ❌     | observed p99 > 10 s                                              |
| Cancel p99 ≤ 500 ms during saturation                          | ❌     | cancel timed out                                                 |
| Health success rate ≥ 99 %                                     | ❌     | 12 / 60 timed out or returned 5xx                                |
| No 5xx on the control plane                                    | ❌     | 503 observed                                                     |

Under this SUT, an operator hit by a real incident has no ability to
intervene from the API surface. Recovery requires killing the process,
restarting from outside the SUT's control — an outage instead of a
degradation.

## The four sub-clauses

### AT-005.A — Health latency holds under saturation

- **Given:** the data plane is being saturated by ≥ 1000 concurrent
  in-flight `create-long-task` submissions (or the maximum the SUT
  accepts before backpressuring, whichever comes first).
- **When:** the test issues GET `health` once per second for 60
  seconds during the saturation window.
- **Then:** the **p99** latency across those 60 samples is ≤ **200 ms**;
  the success rate (status 2xx) is ≥ **99 %**.

### AT-005.B — Cancel latency holds under saturation

- **Given:** the same saturation as AT-005.A, plus at least 10 known
  in-flight task handles obtained from lane A's submissions.
- **When:** the test issues `cancel-task` against those 10 handles
  during the saturation window.
- **Then:** the **p99** latency across those 10 cancellations is ≤
  **500 ms**; the success rate (status 2xx OR 4xx-non-404) is ≥
  **95 %**.

### AT-005.C — No control-plane 5xx during saturation

- **Given:** the same saturation.
- **When:** every control-plane request issued during the window.
- **Then:** zero `5xx` responses. (A backpressured SUT MAY return
  `503 Service Unavailable` for `create-long-task` on the data plane;
  control endpoints MUST NOT 5xx.)

### AT-005.D — Recovery is bounded

- **Given:** saturation has stopped (lane A no longer submits).
- **When:** the test continues to sample `health` once per second.
- **Then:** within **30 seconds** of saturation ending, `health` p99
  over the most recent 10 samples is back to ≤ **100 ms** (i.e. the
  SUT recovers; long-tail damage from the burst does not persist).

## What this test does NOT assert

- Data-plane throughput or latency — the data plane is permitted to
  degrade arbitrarily under saturation; AT-005 only constrains the
  control plane.
- The mechanism of separation (separate thread pools, separate
  schedulers, separate processes, separate hosts) — only the
  externally-observable outcome.
- Behaviour beyond 1000 concurrent submissions — separate stress test
  (AT-010, future).
- Tenant fairness during saturation — separate test (AT-009, future).
- Cancellation correctness (does the worker actually stop?) — that
  is AT-003 / AT-011 territory; AT-005 only checks the cancel
  endpoint stayed responsive.

## Procedure

1. Bring up the SUT per `boot.sh`. Wait for `health` healthy with a
   small (≤ 50 ms) baseline latency confirmed via 5 warm-up probes.
2. **Pre-test data-plane probe.** Submit one `create-long-task` to
   confirm the data plane is functional; record the handle for later
   cancellation.
3. **Saturation.** In parallel (a separate goroutine / executor),
   begin submitting `create-long-task` requests at the maximum rate
   the test runner can sustain, with the largest payload size the
   adapter declares as acceptable. Continue for 60 seconds. Collect
   all returned handles.
4. **Control-plane probing — health.** Concurrent with step 3, once
   per second for 60 seconds, GET `health`. Record latency and
   status per probe.
5. **Control-plane probing — cancel.** During the second half of the
   saturation window (T+30s to T+60s), pick 10 handles from the
   pool collected in step 3 and POST `cancel-task` against each.
   Record latency and status per cancel.
6. **Stop saturation.** Cease lane-A submissions at T+60s.
7. **Recovery probing.** From T+60s to T+90s, continue GET `health`
   once per second. Compute p99 over the last 10 samples.
8. **Compute verdicts.**
   - AT-005.A — health p99 over step 4 ≤ 200 ms AND success rate
     ≥ 99 %.
   - AT-005.B — cancel p99 over step 5 ≤ 500 ms AND success rate
     ≥ 95 %.
   - AT-005.C — zero 5xx across steps 4 and 5.
   - AT-005.D — health p99 over the recovery window (step 7) ≤
     100 ms.
9. **Cleanup.** Cancel any remaining in-flight tasks; teardown.

## Reporting

```yaml
test: AT-005
sut: <sut-id from adapter>
sut_self_reported_version: <opaque string>
test_suite_version: <repo git SHA>
ran_at: <ISO-8601 UTC>
verdict: PASS | FAIL | INCONCLUSIVE
sub_clauses:
  AT-005.A: PASS | FAIL | INCONCLUSIVE
  AT-005.B: PASS | FAIL | INCONCLUSIVE
  AT-005.C: PASS | FAIL | INCONCLUSIVE
  AT-005.D: PASS | FAIL | INCONCLUSIVE
measurements:
  saturation_concurrent_tasks: <int>
  health_p50_ms: <int>
  health_p95_ms: <int>
  health_p99_ms: <int>
  health_success_rate: <float>
  cancel_p50_ms: <int>
  cancel_p95_ms: <int>
  cancel_p99_ms: <int>
  cancel_success_rate: <float>
  control_plane_5xx_count: <int>
  recovery_health_p99_ms: <int>
  recovery_time_to_baseline_seconds: <int>
environment:
  cpu_model: <string>
  vcpu_count: <int>
  memory_gb: <int>
  network_mode: <string>
adapter_capabilities:
  max_payload_size_bytes: <int>
  declared_concurrent_task_ceiling: <int|null>
notes: <free text>
```

## SUT-agnosticism

The test does not assume any particular separation mechanism. Possible
realisations of the underlying invariant include:

- Separate thread pools for control vs data;
- Separate reactive schedulers;
- Separate process / container / pod per plane;
- Async event-loop where control is preemptively prioritised;
- Hardware-level (separate hosts behind separate load balancers).

The test only sees latency and status codes from outside.

## Adapter requirements

| Field                                           | Required | Notes                                                       |
|-------------------------------------------------|----------|-------------------------------------------------------------|
| `endpoints.health`                              | yes      | Sampled during saturation                                   |
| `endpoints.cancel_task`                         | yes      | Sampled during saturation                                   |
| `endpoints.create_long_task`                    | yes      | Used to generate saturation                                 |
| `max_payload_size_bytes`                        | yes      | The largest payload the SUT accepts on create_long_task     |
| `declared_concurrent_task_ceiling`              | no       | If declared, the test caps saturation at this value; if absent the test pushes to first observed backpressure |
| `hooks.set_worker_delay_ms`                     | no       | If available, set high delay so tasks stay in-flight; if absent the test relies on natural worker latency |

If `max_payload_size_bytes` is not declared, the test cannot generate
predictable saturation; all sub-clauses report INCONCLUSIVE with that
adapter-declaration gap as the reason.
