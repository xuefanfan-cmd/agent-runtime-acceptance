---
id: AT-003
title: Submission Schema Strict Matching
status: active
sut_capability: create-long-task
authority:
  - kind: first-principle
    statement: |
      A system that silently routes unknown inputs to a default handler
      hides defects until they reach production. Strict validation at the
      boundary surfaces type drift early; silent fallback amplifies it.
  - kind: industry-pattern
    statement: |
      Schema-first APIs validate every submission against an explicit
      schema and reject malformed or unknown values with a structured
      error that identifies the offending field. The client can then
      either fix the request or fall back gracefully.
    references:
      - "OpenAPI Specification 3.1 — request validation"
      - "JSON Schema 2020-12 — additionalProperties / enum / required"
      - "Google API Improvement Proposals (AIP-193) — error model"
  - kind: standard
    statement: |
      Structured machine-readable error bodies for HTTP API failures are
      standardised. A rejection MUST carry enough information for the
      client to recover programmatically.
    references:
      - "RFC 7807 — Problem Details for HTTP APIs"
      - "RFC 9457 — Problem Details for HTTP APIs (revised)"
  - kind: historic-incident
    statement: |
      Silent type coercion and fallback routing are recurring causes of
      production outages. Examples: implicit numeric coercion masking
      schema drift; default-to-noop handlers swallowing misconfigured
      events; "best-effort" routing delivering work to the wrong worker
      and producing inscrutable downstream failures.
---

# AT-003 — Submission Schema Strict Matching

> **In one line:** A submission carrying an unknown task type MUST be
> rejected with a structured 4xx error that names the offending field —
> the SUT MUST NOT silently route it to a default handler.

## The picture

```
client                                  SUT
   │                                     │
   ├─ POST /create-long-task             │
   │   { type: "<KNOWN-type>",           │
   │     payload: { ... } }              │
   │◄─ 202 { handle, pollUrl } ─────────┤   known type → accepted
   │                                     │
   │                                     │
   ├─ POST /create-long-task             │
   │   { type: "<UNKNOWN-type>",         │
   │     payload: { ... } }              │
   │◄─ 4xx                              │   unknown type → rejected
   │   { error: "unknown_task_type",     │   with structured body that
   │     offending_field: "type",        │   names the field, the value,
   │     offending_value: "<UNKNOWN>",   │   and (ideally) the known set
   │     known_values: ["<KNOWN>", ...] }│
   │                                     │
   ├─ GET /list-tasks ──────────────────►│
   │◄─ [ handle-from-first-call ] ──────┤   only the accepted task; the
   │                                     │   rejected one MUST NOT have
   │                                     │   created a phantom record
```

The invariant is operational: there are exactly N supported task types,
the SUT knows the list, and a submission carrying a type outside the
list is rejected at the boundary — never quietly routed to a default.

## Why we care

Three failure modes that this test rules out:

1. **Silent demotion.** The SUT receives `type: "graph_v99"` but has
   only `graph_v1` registered. Instead of failing, it routes the
   payload to `graph_v1` ("close enough"). The client believes its
   `graph_v99` semantics are honoured; nothing surfaces the mismatch
   until the produced output diverges from expectation, often weeks
   later.
2. **Phantom records.** The SUT accepts the submission, creates a task
   record, returns a 202, but then fails to dispatch because no
   handler matches. The task lingers in `RUNNING` or `PENDING`
   forever; the client polls and times out. The acceptance was a lie.
3. **Inscrutable rejection.** The SUT rejects the submission with
   `400 Bad Request` and an empty body. The client cannot tell which
   field was wrong, what valid values are, or whether the failure is
   permanent or transient. Retry storms ensue.

The schema-first discipline — pioneered by Protocol Buffers, Avro, and
OpenAPI; standardised in JSON Schema; codified in Google's API
Improvement Proposals — demands that every boundary input is validated
against an explicit schema and that rejections are machine-readable
(RFC 7807 / RFC 9457 Problem Details).

A SUT that satisfies AT-003 is one that can be extended safely: a
client building against version N can submit a `type` introduced in
version N+1, receive a clear "unknown_task_type" rejection, and degrade
gracefully. A SUT that fails AT-003 cannot be extended at all without
breaking every existing client in subtle ways.

## What PASSing looks like

The adapter has declared `known_task_type: "graph_v1"` and
`unknown_task_type: "definitely_not_a_real_type_v999"`. The test runs:

```
T+0 ms     POST /v1/runs   { type: "graph_v1",     payload: ... }
T+38 ms    ← 202           { runId: "alpha-1", pollUrl: "/v1/runs/alpha-1" }

T+100 ms   POST /v1/runs   { type: "definitely_not_a_real_type_v999",
                              payload: ... }
T+135 ms   ← 422           { error: "unknown_task_type",
                              offending_field: "type",
                              offending_value: "definitely_not_a_real_type_v999",
                              known_values: ["graph_v1", "agent_loop_v1"] }

T+200 ms   GET /v1/runs    (list)
T+225 ms   ← [ alpha-1 ]   ← only one task, the accepted one
```

| Check                                | Result | Notes                                                       |
|--------------------------------------|--------|-------------------------------------------------------------|
| Known type accepted                  | ✅     | 202 with handle                                             |
| Unknown type rejected                | ✅     | 422 (could also be 400)                                     |
| Rejection identifies the field       | ✅     | `offending_field: "type"`                                   |
| Rejection identifies the value       | ✅     | `offending_value: "<UNKNOWN>"`                              |
| Rejection lists known values         | ✅     | helps client recover (this one is SHOULD, not MUST)         |
| No phantom task                      | ✅     | list shows only the accepted task                           |

## What FAILing looks like

A non-conformant SUT that silently routes unknown types to a default:

```
T+100 ms   POST /v1/runs   { type: "definitely_not_a_real_type_v999",
                              payload: ... }
T+138 ms   ← 202           { runId: "phantom-1", pollUrl: "/v1/runs/phantom-1" }
                              ^^^^^^^^^^^^^^^^^^
                              SUT accepted the unknown type;
                              client cannot tell anything is wrong

T+200 ms   GET /v1/runs    (list)
T+225 ms   ← [ alpha-1, phantom-1 ]
                              ^^^^^^^^^
                              phantom task exists; will probably
                              never produce a sensible result
```

| Check                                | Result | Notes                                                          |
|--------------------------------------|--------|----------------------------------------------------------------|
| Unknown type rejected                | ❌     | 202 returned instead of 4xx                                    |
| No phantom task                      | ❌     | unknown-type submission produced an addressable but doomed task |

## The five sub-clauses

### AT-003.A — Known type accepted (smoke)

- **Given:** the adapter declares `known_task_type: "<X>"`.
- **When:** the client POSTs `create-long-task` with `type: "<X>"`.
- **Then:** SUT returns `2xx` and a parseable task handle. (Smoke
  baseline; without this the rest of the test means nothing.)

### AT-003.B — Unknown type rejected with 4xx

- **Given:** the adapter declares `unknown_task_type: "<Y>"`, where
  `<Y>` is guaranteed by the adapter author NOT to be registered in
  this SUT.
- **When:** the client POSTs `create-long-task` with `type: "<Y>"`.
- **Then:** SUT returns a status code in `400..499` (typically 400 or
  422).
- **NEVER:**
  - `2xx` (silent acceptance);
  - `5xx` (internal error — strict rejection is a normal control
    flow, not an error);
  - hanging without response.

### AT-003.C — Rejection body identifies offending field and value

- **Given:** the rejection response from AT-003.B.
- **When:** the client parses the response body as JSON.
- **Then:** the body contains:
  - a field naming what was wrong (e.g. `error`, `code`, `type`); and
  - a field identifying the offending submission field (the adapter
    declares the JSONPath); and
  - a field carrying the offending value.
- **SHOULD:** also list known acceptable values (helps clients
  self-recover); a SUT that omits this is conformant but not ideal.

### AT-003.D — No phantom task

- **Given:** AT-003.B's rejected submission.
- **When:** the client invokes `list-tasks` (and, separately,
  `poll-task` with any handle the rejection body might have leaked).
- **Then:**
  - the list MUST NOT contain a task corresponding to the rejected
    submission;
  - any poll attempt for a leaked handle returns 404.
- **INCONCLUSIVE if:** the adapter declares `list_tasks` as
  unavailable.

### AT-003.E — Consistent rejection across batches

- **Given:** ten sequential submissions, each with the same unknown
  type but different payloads.
- **When:** the test issues all ten.
- **Then:** every one MUST be rejected with the same status code and
  the same error shape. A SUT that sometimes accepts and sometimes
  rejects has a race condition in its dispatcher and FAILS.

## What this test does NOT assert

- The exact rejection status code beyond `4xx` (400 vs 422 vs 409 is
  SUT discretion; the Problem Details standard is preferred but not
  required).
- The exact field name(s) in the rejection body; the adapter declares
  the JSONPath.
- Validation of fields other than `type` (payload-shape validation is
  a separate test, AT-012 future).
- Schema evolution rules (deprecation, sunset headers — separate test).
- Backward compatibility across SUT versions — separate test.

## Procedure

1. Bring up the SUT per its adapter's `boot.sh`. Wait for `health`.
2. **Read adapter declarations.** Confirm the adapter declares
   `known_task_type` and `unknown_task_type`. If either is missing,
   abort the test with `INCONCLUSIVE` for all sub-clauses.
3. **AT-003.A — smoke.** POST `create-long-task` with `type:
   <known_task_type>`. PASS iff status is `2xx` AND the response body
   contains a task handle.
4. **AT-003.B — rejection.** POST `create-long-task` with `type:
   <unknown_task_type>`. Record status. PASS iff status ∈ `400..499`.
5. **AT-003.C — error shape.** Parse the rejection body. PASS iff the
   adapter-declared `rejection_field_jsonpath` and
   `rejection_value_jsonpath` both resolve to non-null values.
6. **AT-003.D — no phantom.** Invoke `list-tasks`. Inspect the list
   for any task with a creation timestamp ≥ the rejected submission's
   timestamp that the test did not deliberately create. PASS iff none
   appear.
7. **AT-003.E — repeatability.** Repeat step 4 ten times. PASS iff
   every iteration returns the same status code (within `400..499`)
   and a body matching the same shape.
8. **Cleanup.** Cancel the task created in step 3; teardown.

## Reporting

```yaml
test: AT-003
sut: <sut-id from adapter>
sut_self_reported_version: <opaque string>
test_suite_version: <repo git SHA>
ran_at: <ISO-8601 UTC>
verdict: PASS | FAIL | INCONCLUSIVE
sub_clauses:
  AT-003.A: PASS | FAIL | INCONCLUSIVE
  AT-003.B: PASS | FAIL | INCONCLUSIVE
  AT-003.C: PASS | FAIL | INCONCLUSIVE
  AT-003.D: PASS | FAIL | INCONCLUSIVE
  AT-003.E: PASS | FAIL | INCONCLUSIVE
observations:
  known_type_status: <int>
  unknown_type_status: <int>
  rejection_field_jsonpath_resolved: <bool>
  rejection_value_jsonpath_resolved: <bool>
  phantom_tasks_observed: <int>
  repeat_run_status_codes: [<int>, ...]   # length 10
adapter_capabilities:
  known_task_type_declared: <bool>
  unknown_task_type_declared: <bool>
  list_tasks_available: <bool>
notes: <free text>
```

## SUT-agnosticism

The test makes no assumption about how the SUT internally represents
task types — strings, integers, URIs, enums, multi-part identifiers
are all valid. The adapter is responsible for choosing one known value
and one guaranteed-unknown value of the SUT's native type system.

The test also makes no assumption about the SUT's rejection error
format. It MAY follow RFC 7807 Problem Details, Google AIP-193, GraphQL
errors, or any bespoke shape — as long as the adapter can declare
where the offending field and value appear via JSONPath.

## Adapter requirements

| Field                                             | Required | Notes                                                        |
|---------------------------------------------------|----------|--------------------------------------------------------------|
| `known_task_type`                                 | yes      | A value the test runner can submit and expect acceptance     |
| `unknown_task_type`                               | yes      | Guaranteed by the adapter author to NOT be registered        |
| `submission_type_jsonpath`                        | yes      | Where in the submission body the `type` field goes           |
| `rejection_field_jsonpath`                        | yes      | Where the offending field name appears in the rejection body |
| `rejection_value_jsonpath`                        | yes      | Where the offending value appears in the rejection body      |
| `endpoints.list_tasks`                            | no       | Absence → AT-003.D INCONCLUSIVE                              |

If `known_task_type` and `unknown_task_type` are not declared, the test
runner cannot proceed; all sub-clauses report INCONCLUSIVE with a
single reason ("adapter does not declare exemplar task types").
