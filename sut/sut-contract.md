# SUT Contract — Capabilities every System Under Test must expose

This document defines the abstract surface against which acceptance test
cases are written. Each test case names one or more capabilities; an SUT's
adapter binds these capabilities to the SUT's concrete endpoints, hooks,
and vocabularies.

## Capability categories

Capabilities are grouped into three categories:

1. **Endpoint capabilities** — abstract HTTP endpoints (method + path
   template) the SUT exposes.
2. **Hook capabilities** — mechanisms by which the test runner can induce
   specific SUT behaviour for testing (e.g. inject worker delay).
3. **Shape capabilities** — JSONPath expressions that map abstract field
   names to the SUT's concrete response bodies.

A test case is runnable against a SUT iff all capabilities the test
declares as required are present in the SUT's adapter.

## Endpoint capabilities

| Capability id        | Purpose                                                  | Required for       |
|----------------------|----------------------------------------------------------|--------------------|
| `health`             | Liveness probe; returns the SUT's self-reported version. | Always             |
| `create_long_task`   | Accept work; return a task handle without blocking.      | AT-001 and later   |
| `poll_task`          | Retrieve current state and result of a task by handle.   | AT-001 and later   |
| `cancel_task`        | Request cancellation of an in-flight task.               | AT-001 and later   |
| `list_tasks`         | Enumerate tasks visible to the calling identity.         | AT-008 and later   |

Each endpoint capability's adapter binding declares:

- `method` — HTTP method (`GET`, `POST`, etc.).
- `url_template` — URL with slot placeholders. The placeholder `{base}`
  is reserved for the SUT's base URL. Endpoint-specific placeholders
  (e.g. `{handle}`) are documented per capability.

## Hook capabilities

Hook capabilities are OPTIONAL by default. A SUT not exposing a hook
causes dependent sub-clauses to report INCONCLUSIVE, not FAIL.

| Capability id                  | Purpose                                                       | First consumed by  |
|--------------------------------|---------------------------------------------------------------|--------------------|
| `set_worker_delay_ms`          | Inject artificial worker delay for boundary tests.            | AT-001.C, AT-001.E |
| `force_worker_failure`         | Cause the next worker invocation to throw / exit non-zero.    | (future)           |
| `seed_tenant`                  | Provision a tenant identity for multi-tenant test cases.      | AT-002             |
| `revoke_credentials`           | Invalidate a previously-issued credential mid-flight.         | (future)           |
| `verify_storage_layer_scope`   | Confirm tenant scope is enforced at the storage engine.       | AT-002.E           |

Each hook binding declares:

- `available` — boolean. If `false`, dependent sub-clauses report
  INCONCLUSIVE for this SUT.
- `mechanism` — how the test runner activates the hook (e.g.
  `http_endpoint`, `env_var`, `config_file_mutation`, `bean_override`).
- `signature` — the parameters the hook accepts (if applicable).
- `reason` — required when `available: false`; a brief sentence the
  report surfaces explaining the gap.

## Shape capabilities

For each endpoint that returns JSON, the adapter declares the JSONPath to
abstract fields.

### `create_long_task` response

| Abstract field name                       | Required | Example value             |
|-------------------------------------------|----------|---------------------------|
| `task_handle_jsonpath`                    | yes      | `$.runId`                 |
| `poll_address_jsonpath`                   | yes      | `$.pollUrl`               |
| `terminal_output_jsonpaths_forbidden`     | no       | `["$.result", "$.output"]`|

### `poll_task` response

| Abstract field name      | Required | Example value     |
|--------------------------|----------|-------------------|
| `state_jsonpath`         | yes      | `$.status`        |
| `created_at_jsonpath`    | no       | `$.createdAt`     |
| `finished_at_jsonpath`   | no       | `$.finishedAt`    |

The shape capability set grows as new test cases require new fields.
Adapters that do not yet declare a newly-required field cause the
corresponding sub-clause to report INCONCLUSIVE.

### Submission validation exemplars

For tests that exercise schema-strict matching (AT-003), the adapter
declares two exemplar values from the SUT's task-type vocabulary, and
the JSONPaths the test runner uses to set and inspect them:

| Field name                       | Required | Example                                                       |
|----------------------------------|----------|---------------------------------------------------------------|
| `known_task_type`                | yes      | a string the SUT WILL accept as a `type` field                |
| `unknown_task_type`              | yes      | a string the adapter author GUARANTEES is NOT registered      |
| `submission_type_jsonpath`       | yes      | where `type` is placed in the submission body                 |
| `rejection_field_jsonpath`       | yes      | where the offending field name appears in the rejection body  |
| `rejection_value_jsonpath`       | yes      | where the offending value appears in the rejection body       |

If either of `known_task_type` / `unknown_task_type` is omitted, AT-003
reports INCONCLUSIVE for every sub-clause.

## Boot profile declarations

For lifecycle tests (e.g. AT-004), the adapter's `boot.sh` accepts a
`--profile=<name>` argument. The adapter declares which profiles it
supports and the substring tokens the test should look for in stderr
on aborting profiles:

| Profile name        | Purpose                                                    | First consumed by  |
|---------------------|------------------------------------------------------------|--------------------|
| `valid_prod`        | All required config present; SUT should boot cleanly       | AT-004.A           |
| `missing_required`  | One required config item deliberately missing              | AT-004.B, AT-004.C |
| `inmem_in_prod`     | Production posture wired to a dev-only in-memory component | AT-004.D           |

For the aborting profiles, the adapter optionally declares the literal
substring the test runner expects in stderr:

| Field                       | Used by    | Notes                                                   |
|-----------------------------|------------|---------------------------------------------------------|
| `missing_config_key`        | AT-004.C   | substring-matched against stderr of `missing_required`  |
| `dev_only_component_name`   | AT-004.D   | substring-matched against stderr of `inmem_in_prod`     |

Absent profiles flip the dependent sub-clauses to INCONCLUSIVE — they
do NOT count as FAIL. A SUT that genuinely has no in-memory dev impls,
for example, legitimately omits `inmem_in_prod`.

## Capacity declarations

For tests that exercise load characteristics (e.g. AT-005), the adapter
declares the SUT's stated capacity envelope:

| Field name                          | Used by  | Notes                                                       |
|-------------------------------------|----------|-------------------------------------------------------------|
| `max_payload_size_bytes`            | AT-005   | Largest payload the SUT accepts on `create_long_task`       |
| `declared_concurrent_task_ceiling`  | AT-005   | Optional; if present the test caps saturation at this value |

## State vocabulary mapping

Every adapter MUST include a `state_mapping` block projecting the SUT's
concrete state names onto the abstract states defined in
[../specs/000-state-vocabulary.md](../specs/000-state-vocabulary.md).

```yaml
state_mapping:
  RUNNING:   [<concrete name>, ...]
  SUCCEEDED: [<concrete name>, ...]
  FAILED:    [<concrete name>, ...]
  CANCELLED: [<concrete name>, ...]
  EXPIRED:   [<concrete name>, ...]
  SUSPENDED: [<concrete name>, ...]
```

A SUT whose internal name set does not include a given abstract state
leaves it unmapped; dependent sub-clauses report INCONCLUSIVE.

## Auth declaration

If the SUT requires authentication to reach its endpoints, the adapter
declares the SHAPE of the auth (not actual secrets). Secrets MUST be
sourced from environment variables at test-run time and MUST NOT appear
in any file in this repository.

```yaml
auth:
  scheme: bearer | basic | api_key | none
  token_env_var: <name of env var the runner consults>
  additional_headers:
    <header_name>:
      env_var: <env var name>
      required: <bool>
  tenant_claim_present: <bool>   # does the auth credential carry tenant identity?
```

`tenant_claim_present` is consumed by AT-002.D. If `false`, AT-002.D
reports INCONCLUSIVE (the SUT's auth scheme does not give the test a
way to construct a "credential authorises tenant B but request claims
tenant A" mismatch).

## Versioning

The SUT contract is versioned by this file's git history. An adapter
declares the contract version it was authored against:

```yaml
contract_version: <git sha of sut-contract.md at adapter creation>
```

When the contract evolves in a way that affects an adapter, the adapter
opens a PR to bump its `contract_version` and to add any new fields the
update introduces. Old adapters remain runnable against old test cases;
test cases requiring the new contract version skip old adapters with
INCONCLUSIVE.

## What the contract intentionally does NOT specify

- The SUT's process model (monolith vs services vs serverless).
- The SUT's persistence (Postgres vs DynamoDB vs in-memory).
- The SUT's worker model (threads vs reactive vs virtual threads).
- The SUT's authentication implementation (Keycloak vs Auth0 vs custom).
- The SUT's deployment topology (Kubernetes vs VM vs container vs PaaS).

All of these are SUT choices. The acceptance suite is silent on them.
