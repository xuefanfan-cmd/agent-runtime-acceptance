---
id: AT-002
title: Multi-Tenant Read / Write Isolation
status: active
sut_capability: poll-task, cancel-task, list-tasks
authority:
  - kind: first-principle
    statement: |
      In a multi-tenant system, every operation must be scoped to the
      tenant identity that authorised the operation. A request issued by
      tenant A must never observe or affect data created by tenant B,
      even if both requests reach the same backing store and the same
      process.
  - kind: industry-pattern
    statement: |
      Defense in depth: tenant isolation is enforced at every layer that
      sees tenant identity (application code, persistence, storage
      engine) so that a single layer's compromise does not breach the
      isolation boundary.
    references:
      - "PostgreSQL Row-Level Security (RLS) — official documentation"
      - "AWS Multi-Tenant SaaS Architecture whitepaper (2021)"
      - "Salesforce multi-tenant architecture — Force.com platform"
  - kind: security-pattern
    statement: |
      Broken Object-Level Authorization is the highest-frequency API
      vulnerability: a caller-supplied identifier (task handle, account
      ID) must be cross-checked against the caller's authorised scope on
      every access, not only at creation.
    references:
      - "OWASP API Security Top 10 — API1:2023 Broken Object Level Authorization"
      - "OWASP API Security Top 10 — API5:2023 Broken Function Level Authorization"
      - "CWE-639 — Authorization Bypass Through User-Controlled Key"
  - kind: historic-incident
    statement: |
      Cross-tenant data exposure has caused public incidents at multiple
      large multi-tenant providers. The pattern is consistent: an object
      identifier becomes addressable from any tenant's credentials
      because the access path skipped the tenant scope check.
---

# AT-002 — Multi-Tenant Read / Write Isolation

> **In one line:** Tenant A's tasks MUST be invisible and unreachable
> to tenant B — on read, list, AND cancel — even when B holds a valid
> token and supplies A's task handle directly.

## The picture

```
tenant_A                            SUT                            tenant_B
   │                                 │                                 │
   ├─ POST /create-long-task ───────►│                                 │
   │   (auth: A_token)               │                                 │
   │◄─ {handle: alpha-1, ...} ───────┤                                 │
   │                                 │                                 │
   │                                 │ ◄─ POST /create-long-task ──────┤
   │                                 │     (auth: B_token)             │
   │                                 ├─► {handle: beta-1, ...} ───────►│
   │                                 │                                 │
   │                                 │ ◄─ GET /poll/alpha-1 ───────────┤
   │                                 │     (auth: B_token)             │
   │                                 ├─► 404  OR  403 ────────────────►│
   │                                 │   (NEVER 200 with A's body)     │
   │                                 │                                 │
   │                                 │ ◄─ GET /list-tasks ─────────────┤
   │                                 │     (auth: B_token)             │
   │                                 ├─► [beta-1] ────────────────────►│
   │                                 │   (alpha-1 MUST be absent)      │
   │                                 │                                 │
   │                                 │ ◄─ POST /cancel/alpha-1 ────────┤
   │                                 │     (auth: B_token)             │
   │                                 ├─► 404 OR 403 ──────────────────►│
   │                                 │   (alpha-1 MUST stay RUNNING)   │
```

The invariant is mechanical: anything a foreign tenant submits referencing
another tenant's handle MUST return as if the handle does not exist for
that caller. The choice between 404 and 403 is SUT discretion (see
AT-002.A below); the prohibition is on 200 with the wrong tenant's data.

## Why we care

In a multi-tenant runtime, every persisted object — task records, logs,
state checkpoints, audit rows — carries a tenant identity. The isolation
boundary is the entire contract the platform offers to tenants. A single
cross-tenant leak voids the contract for every tenant on the platform.

Application-layer ACLs alone are insufficient for two reasons:

1. **Brittleness.** Every code path that reads an object must remember
   to check `obj.tenant == caller.tenant`. Missed checks accumulate as
   the codebase grows. Static analysis cannot reliably find them all.
2. **Privilege escalation amplification.** Any SSRF, RCE, or admin
   panel compromise that gives an attacker valid SUT credentials
   immediately becomes a cross-tenant breach unless the storage tier
   enforces scope independently.

**OWASP API Security Top 10 (2023)** ranks Broken Object-Level
Authorization (API1) as the most common API vulnerability. CWE-639
catalogues the pattern: an object identifier supplied by the caller is
trusted without re-validating the caller's scope. The defense is
defense-in-depth: enforce scope at the controller, the service, the
repository, AND the storage engine (e.g. PostgreSQL Row-Level Security
policies enforced inside the database itself).

The historic record across the SaaS industry is consistent: every major
multi-tenant provider has shipped at least one CVE in this class. The
acceptance bar is therefore high — five sub-clauses, each independently
verifiable.

## What PASSing looks like

Stimulus: tenant A creates a task, then tenant B tries every cross-tenant
access path. A conformant SUT:

```
T+0 ms     [A] POST /v1/runs                          → leaves client A
T+38 ms    [A] response                               ← 202 {runId: alpha-1, ...}
T+90 ms    [B] POST /v1/runs                          → leaves client B
T+128 ms   [B] response                               ← 202 {runId: beta-1, ...}
T+200 ms   [B] GET /v1/runs/alpha-1                   → leaves client B
T+230 ms   [B] response                               ← 404 Not Found
T+300 ms   [B] GET /v1/runs                           → leaves client B (list)
T+340 ms   [B] response                               ← [beta-1]   ← alpha-1 absent
T+400 ms   [B] POST /v1/runs/alpha-1/cancel           → leaves client B
T+430 ms   [B] response                               ← 404 Not Found
T+500 ms   [A] GET /v1/runs/alpha-1                   → leaves client A
T+530 ms   [A] response                               ← 200 {status: RUNNING}   ← still running
```

| Check                                | Result | Notes                                         |
|--------------------------------------|--------|-----------------------------------------------|
| B's read of A's handle               | ✅     | 404 (could also be 403)                       |
| B's list excludes A's tasks          | ✅     | only beta-1 returned                          |
| B's cancel of A's handle             | ✅     | 404; alpha-1 still RUNNING                    |
| A's own read of own handle works     | ✅     | 200 with correct body — proves SUT not broken |

## What FAILing looks like

Same stimulus against a non-conformant SUT (one that checks tenant on
write but not on read):

```
T+200 ms   [B] GET /v1/runs/alpha-1                   → leaves client B
T+232 ms   [B] response                               ← 200 {runId: alpha-1,
                                                            status: RUNNING,
                                                            tenantId: A}
                                                            ^^^^^^^^^^^
                                                            cross-tenant leak
T+300 ms   [B] GET /v1/runs                           → leaves client B (list)
T+340 ms   [B] response                               ← [alpha-1, beta-1]
                                                              ^^^^^^^
                                                              A's task listed
                                                              under B's identity
T+400 ms   [B] POST /v1/runs/alpha-1/cancel           → leaves client B
T+450 ms   [B] response                               ← 200 {status: CANCELLED}
                                                            ^^^^^^^^^
                                                            B cancelled A's task
```

| Check                            | Result | Notes                                                 |
|----------------------------------|--------|-------------------------------------------------------|
| B's read of A's handle           | ❌     | 200 with A's data — broken object-level authorization |
| B's list excludes A's tasks      | ❌     | A's task visible to B                                 |
| B's cancel of A's handle         | ❌     | B was able to cancel A's task — broken write scope    |

## The five sub-clauses

### AT-002.A — Cross-tenant read denied

- **Given:** tenant A has created task `alpha-1` (handle observable to A
  via `poll-task`).
- **When:** tenant B (different identity, valid credentials of its own)
  invokes `poll-task` with handle `alpha-1`.
- **Then:** SUT returns `404 Not Found` OR `403 Forbidden`. The choice
  between 404 and 403 is SUT discretion; some implementations prefer 404
  to avoid leaking existence.
- **NEVER:** `200` with any field from `alpha-1`'s persisted state.

### AT-002.B — Cross-tenant list isolation

- **Given:** tenant A has created task `alpha-1`; tenant B has created
  task `beta-1`.
- **When:** tenant B invokes `list-tasks`.
- **Then:** the returned list contains `beta-1` AND does not contain
  `alpha-1` (or any other task created by tenant A).
- **INCONCLUSIVE if:** the adapter declares `list_tasks` as unavailable.

### AT-002.C — Cross-tenant cancel denied

- **Given:** tenant A has a task `alpha-1` in `RUNNING` state.
- **When:** tenant B invokes `cancel-task` with handle `alpha-1`.
- **Then:** SUT returns `404` OR `403`; subsequently, tenant A polling
  `alpha-1` MUST observe its state unchanged (still `RUNNING`, not
  `CANCELLED`).
- **NEVER:** `2xx` on the cancel call AND tenant A observes `CANCELLED`.

### AT-002.D — Token / header tenant mismatch rejected

- **Given:** tenant B's auth credential is bound to tenant identity B.
- **When:** tenant B issues a `create-long-task` request whose body or
  headers explicitly declare a different tenant id (`X-Tenant-Id: A`)
  while the credential still authorises B.
- **Then:** SUT returns `4xx` (typically 400 or 403); the request is
  not processed as either tenant.
- **INCONCLUSIVE if:** the SUT's auth scheme does not carry tenant
  identity in the credential (e.g. shared API key); declare via
  adapter's `auth.tenant_claim_present: false`.

### AT-002.E — Storage-layer enforcement (defense in depth)

- **Given:** the SUT's persistence is configured with tenant-scoping
  enforcement at the storage engine (e.g. PostgreSQL RLS, partition-
  by-tenant, separate schemas).
- **When:** the adapter exposes a hook `verify_storage_layer_scope`
  that, for example, runs a known cross-tenant query against the
  storage engine with tenant A's session context and asserts zero rows
  of tenant B's data are visible.
- **Then:** the hook reports `enforced: true` AND `cross_tenant_rows
  _visible: 0`.
- **INCONCLUSIVE if:** the adapter declares `verify_storage_layer
  _scope` as unavailable. Storage-layer enforcement is the gold
  standard but not all SUTs offer black-box visibility into it.

## What this test does NOT assert

- The SUT's choice of tenant identity carrier (JWT claim, header, mTLS
  certificate subject, API key) — adapter declares the scheme.
- The exact wording of the rejection bodies; only the status code and
  the absence of foreign-tenant data matter.
- Rate limits, quotas, or fairness across tenants — separate test
  (AT-009, future).
- Tenant onboarding / provisioning workflows — out of scope.
- Encryption-at-rest tenant key isolation — out of scope (separate
  category, future AT-DATA-* series).

## Procedure

1. Bring up the SUT per its adapter's `boot.sh`. Wait for `health`
   healthy.
2. **Seed tenants.** Via the adapter's `seed_tenant` hook, create two
   tenant identities — call them `tenant_A` and `tenant_B` — and
   obtain a valid credential for each. If the hook is unavailable,
   report all five sub-clauses as `INCONCLUSIVE` and abort the test.
3. **Create A's task.** As tenant A, POST `create-long-task` with
   worker delay set to ≥ 30 s if `set_worker_delay_ms` is available
   (so the task stays in flight throughout the test). Record handle
   `alpha-1`.
4. **Create B's task.** Same, as tenant B. Record handle `beta-1`.
5. **AT-002.A.** As tenant B, GET `poll-task` with `alpha-1`. Record
   status code. PASS iff 404 or 403; FAIL on 2xx.
6. **AT-002.B.** As tenant B, GET `list-tasks`. Parse the list. PASS
   iff the list contains `beta-1` AND does not contain `alpha-1`.
7. **AT-002.C.** As tenant B, POST `cancel-task` with `alpha-1`. Record
   status. Then as tenant A, GET `poll-task` with `alpha-1`. PASS iff
   the cancel attempt was 4xx AND A's poll shows the state is not
   `CANCELLED`.
8. **AT-002.D.** As tenant B, POST `create-long-task` with a request
   header / body field explicitly setting tenant id to A. PASS iff the
   response is 4xx and no new task appears in either tenant's list.
9. **AT-002.E.** If the adapter exposes `verify_storage_layer_scope`,
   invoke it; PASS iff the hook reports enforcement. Else
   `INCONCLUSIVE`.
10. **Cleanup.** Cancel both tasks; teardown via `teardown.sh`.

## Reporting

```yaml
test: AT-002
sut: <sut-id from adapter>
sut_self_reported_version: <opaque string from SUT's health endpoint>
test_suite_version: <repo git SHA>
ran_at: <ISO-8601 UTC>
verdict: PASS | FAIL | INCONCLUSIVE
sub_clauses:
  AT-002.A: PASS | FAIL | INCONCLUSIVE
  AT-002.B: PASS | FAIL | INCONCLUSIVE
  AT-002.C: PASS | FAIL | INCONCLUSIVE
  AT-002.D: PASS | FAIL | INCONCLUSIVE
  AT-002.E: PASS | FAIL | INCONCLUSIVE
observations:
  cross_tenant_read_status: <int>
  cross_tenant_list_contained_foreign: <bool>
  cross_tenant_cancel_status: <int>
  victim_state_after_cancel_attempt: <string>
  mismatched_claim_status: <int>
  storage_layer_enforcement_reported: <bool|null>
adapter_capabilities:
  seed_tenant_available: <bool>
  list_tasks_available: <bool>
  verify_storage_layer_scope_available: <bool>
  auth_tenant_claim_present: <bool>
notes: <free text>
```

Top-level `verdict` follows the AT-001 aggregation rule.

## SUT-agnosticism

This test does not assume the SUT enforces isolation via any specific
mechanism. A SUT may achieve conformance via:

- Application-layer tenant scope checks at every read site;
- Persistence-layer scope (every query joined to a tenant column);
- Storage-engine scope (Postgres RLS, per-tenant schemas, per-tenant
  databases);
- Network-layer scope (per-tenant service instances behind a router);
- any combination of the above.

The test only observes the externally-visible outcome.

## Adapter requirements

| Field                                              | Required | Notes                                                       |
|----------------------------------------------------|----------|-------------------------------------------------------------|
| `endpoints.list_tasks` (method + URL template)     | yes      | For AT-002.B                                                |
| `endpoints.poll_task`                              | yes      | For AT-002.A                                                |
| `endpoints.cancel_task`                            | yes      | For AT-002.C                                                |
| `hooks.seed_tenant`                                | no       | Absence → all five sub-clauses INCONCLUSIVE                 |
| `hooks.verify_storage_layer_scope`                 | no       | Absence → only AT-002.E INCONCLUSIVE                        |
| `auth.tenant_claim_present` (bool)                 | yes      | If false → AT-002.D INCONCLUSIVE                            |
| `state_mapping`                                    | yes      | Needed to compare poll responses                            |

If `seed_tenant` is unavailable the whole test cannot run; the report
records `INCONCLUSIVE` against every sub-clause with a single reason
("adapter cannot provision two distinct tenant identities"). This is
honest reporting, not a free pass.
