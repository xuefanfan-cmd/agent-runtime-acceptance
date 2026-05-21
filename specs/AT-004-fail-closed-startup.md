---
id: AT-004
title: Fail-Closed Startup Under Production Posture
status: active
sut_capability: boot lifecycle (via adapter boot.sh profiles)
authority:
  - kind: first-principle
    statement: |
      A system that boots successfully with an unsafe or incomplete
      configuration cannot be trusted to enforce its safety properties
      at runtime. The boot path is the last opportunity to refuse —
      after boot, the system is committed.
  - kind: industry-pattern
    statement: |
      Twelve-Factor App principle III (Config): configuration is read
      from the environment and validated at startup; missing or invalid
      configuration is a fatal startup error, not a runtime degradation.
    references:
      - "12factor.net §III Config"
      - "12factor.net §IX Disposability"
  - kind: security-pattern
    statement: |
      Security Misconfiguration is the fifth most common vulnerability
      class on the OWASP Top 10. The countermeasure is fail-closed by
      default: the absence of explicit security configuration MUST cause
      the system to refuse to start in production posture, not to start
      with permissive defaults.
    references:
      - "OWASP Top 10 (2021) — A05:2021 Security Misconfiguration"
      - "NIST SP 800-53 — CM-7 Least Functionality"
  - kind: historic-incident
    statement: |
      Knight Capital (2012) lost USD 440M in 45 minutes because a
      deployment activated a dormant fail-open code path on production
      servers. The fundamental cause was a startup-time configuration
      mismatch that the system tolerated rather than rejected. Similar
      patterns are documented in numerous post-mortems where a system
      booted with dev defaults in production.
---

# AT-004 — Fail-Closed Startup Under Production Posture

> **In one line:** In production posture, the SUT MUST refuse to boot
> when any required configuration is missing or any dev-only
> implementation is wired — exit non-zero, do not open the HTTP port.

## The picture

```
test runner                            SUT process

# ── Profile 1: valid production config ───────────────────────────
   ├─ boot.sh --profile=valid_prod ──►│
   │                                  ├─ read config — all required present
   │                                  ├─ wire beans — all durable
   │                                  ├─ open port 8080
   │                                  ├─ stdout last line: "http://localhost:8080"
   │◄─ exit 0, stdout: URL ───────────┤
   │
   ├─ GET <URL>/health ──────────────►│
   │◄─ 200 { status: "UP" } ──────────┤
   │
   ├─ teardown.sh ───────────────────►│ (graceful shutdown)


# ── Profile 2: production posture with missing required config ──
   ├─ boot.sh --profile=missing_auth ►│
   │                                  ├─ read config — JWT issuer absent
   │                                  ├─ throw IllegalStateException
   │                                  ├─ stderr: "missing required config:
   │                                  │           app.auth.issuer"
   │                                  ├─ DO NOT open port 8080
   │◄─ exit ≠ 0, no URL on stdout ────┤
   │
   │   (test runner verifies no port responds)


# ── Profile 3: production posture with in-memory dev impl wired ──
   ├─ boot.sh --profile=inmem_in_prod ►│
   │                                   ├─ read config — APP_POSTURE=prod
   │                                   ├─ wire InMemoryStore
   │                                   ├─ throw IllegalStateException
   │                                   ├─ stderr: "dev-only impl rejected
   │                                   │           under posture=prod"
   │◄─ exit ≠ 0 ───────────────────────┤
```

The test treats the SUT process as an oracle: each boot profile is a
configuration scenario; the SUT either starts cleanly or refuses
loudly. Permissive starting under bad config is the failure mode.

## Why we care

The boot path is the only place a system can refuse to participate. Once
the application has opened its port and started serving traffic, every
subsequent safety guarantee is conditional on the configuration that
was loaded at boot. If boot tolerates missing or wrong configuration,
the system is committed to running with whatever defaults filled the
gap — and dev defaults in production are how breaches happen.

**Twelve-Factor App** §III (*Config*) prescribes that "all of the
config" lives in environment variables read at boot, and that the
application validates them at startup. §IX (*Disposability*) prescribes
that processes start up quickly OR fail fast — not slow boot with
ambiguous outcome.

**OWASP Top 10 A05:2021 — Security Misconfiguration** is consistently
in the top five of the OWASP rankings because the failure pattern is
universal: defaults that are safe in development are unsafe in
production, and a system that does not distinguish posture at boot
ships dev defaults to production.

The historic record is grim. **Knight Capital (2012)** is the most
quoted: a deployment left a dormant code path enabled on production
servers because the boot path tolerated the configuration mismatch
rather than refusing it. The result was USD 440 million in losses in
45 minutes and the end of the firm. Every post-mortem of an incident
caused by a "leftover dev setting in prod" is a variation on the same
theme: the boot path was too permissive.

AT-004 ratchets that lesson into the acceptance contract. A SUT that
satisfies it can be operated safely; a SUT that fails it cannot.

## What PASSing looks like

The adapter has declared three boot profiles. The test runs each:

```
# Profile valid_prod
$ boot.sh --profile=valid_prod
[stderr] starting under posture=prod, all required config present
[stderr] opening port 8080
[stdout] http://localhost:8080
$ echo $?
0
$ curl -fs http://localhost:8080/v1/health
{"status":"UP","sha":"abc1234","db_ping_ns":234567}

# Profile missing_auth
$ boot.sh --profile=missing_auth
[stderr] starting under posture=prod
[stderr] FATAL: missing required config: app.auth.issuer
[stderr] (see ADR-XXXX or equivalent runtime docs)
[stderr] refusing to start
$ echo $?
1
$ curl -fs http://localhost:8080/v1/health
curl: (7) Failed to connect to localhost port 8080: Connection refused

# Profile inmem_in_prod
$ boot.sh --profile=inmem_in_prod
[stderr] starting under posture=prod
[stderr] FATAL: in-memory implementation 'InMemoryStore' is dev-only;
[stderr]        APP_POSTURE=prod forbids this binding
[stderr] refusing to start
$ echo $?
1
```

| Check                                                       | Result | Notes                              |
|-------------------------------------------------------------|--------|------------------------------------|
| valid_prod boots & exposes healthy port                     | ✅     |                                    |
| missing_auth exits non-zero                                 | ✅     | exit 1                             |
| missing_auth does NOT open the port                         | ✅     | curl fails with Connection refused |
| missing_auth stderr names the missing config item           | ✅     | "app.auth.issuer"                  |
| inmem_in_prod exits non-zero                                | ✅     | exit 1                             |
| inmem_in_prod stderr names the offending dev-only component | ✅     | "InMemoryStore"                    |

## What FAILing looks like

A non-conformant SUT that warns about missing config and starts anyway:

```
# Profile missing_auth (against non-conformant SUT)
$ boot.sh --profile=missing_auth
[stderr] starting under posture=prod
[stderr] WARN: app.auth.issuer not set; using default
                                         ^^^^^^^^^^^^
                                         silently fell back
                                         to a permissive default
[stderr] opening port 8080
[stdout] http://localhost:8080
$ echo $?
0                  ← booted successfully
$ curl -fs http://localhost:8080/v1/health
{"status":"UP",...}   ← serving traffic with no auth
```

| Check                                                       | Result | Notes                                                       |
|-------------------------------------------------------------|--------|-------------------------------------------------------------|
| missing_auth exits non-zero                                 | ❌     | exit 0; SUT booted with permissive default                  |
| missing_auth does NOT open the port                         | ❌     | port 8080 open and serving                                  |

This SUT is now running production traffic with a missing authentication
config. The control plane has failed to refuse, and every downstream
guarantee about authorized access is void.

## The five sub-clauses

### AT-004.A — valid_prod boots cleanly

- **Given:** the adapter exposes a boot profile `valid_prod` that
  provides all required configuration for production posture.
- **When:** the test invokes `boot.sh --profile=valid_prod`.
- **Then:** the script exits 0 within the boot timeout (120 s), the
  last line of stdout is a base URL, and `GET <base>/health` returns
  `2xx` within 10 seconds of boot completion.

### AT-004.B — Missing required config aborts boot

- **Given:** the adapter exposes a boot profile (e.g.
  `missing_auth`) that omits a configuration item the SUT declares
  required in production posture.
- **When:** the test invokes `boot.sh --profile=<missing_required>`.
- **Then:** the script exits non-zero within 60 s, and no HTTP port
  declared in any prior profile responds.
- **INCONCLUSIVE if:** the adapter does not declare any
  `missing_required` profile.

### AT-004.C — Boot failure message names the missing item

- **Given:** the abort observed in AT-004.B.
- **When:** the test reads the script's stderr.
- **Then:** stderr contains the literal name of the missing
  configuration item (e.g. `app.auth.issuer`, `JWT_ISSUER_URI`),
  ideally with a reference to documentation.
- **SHOULD:** include a reference identifier (ADR id, doc URL, config
  key path) so an operator can self-diagnose without spelunking source.

### AT-004.D — Dev-only implementation rejected in production posture

- **Given:** the adapter exposes a profile (e.g. `inmem_in_prod`)
  that wires an implementation the SUT classifies as dev-only while
  posture is production.
- **When:** the test invokes `boot.sh --profile=inmem_in_prod`.
- **Then:** boot aborts non-zero AND the stderr names the offending
  component AND the port does not open.
- **INCONCLUSIVE if:** the adapter does not declare any dev-only
  components (e.g. SUTs that ship no in-memory variants).

### AT-004.E — No partial-success boot

- **Given:** any aborting profile (B or D).
- **When:** the test waits 30 s after the boot script exits and then
  probes every endpoint listed in the adapter.
- **Then:** every endpoint refuses connection (TCP RST or timeout) —
  the SUT MUST NOT be partially up. A process that exited non-zero
  but left a child process listening on the port is a FAIL.

## What this test does NOT assert

- The full enumeration of required configuration items — that is
  SUT-specific; the adapter declares one representative
  `missing_required` profile.
- The SUT's recovery posture (auto-restart, supervisor escalation) —
  separate operational test.
- The performance characteristics of boot (cold-start latency) — see
  AT-006 (boot performance, future).
- Posture transitions at runtime (changing APP_POSTURE without restart)
  — out of scope; the contract is "posture is read once at boot".

## Procedure

1. Verify adapter declares the required profiles. At minimum
   `valid_prod` MUST be present; `missing_required` and
   `inmem_in_prod` are optional but their absence flips dependent
   sub-clauses to INCONCLUSIVE.
2. **AT-004.A.** Invoke `boot.sh --profile=valid_prod`. Wait for exit
   or 120 s timeout. Parse last line of stdout as base URL. Poll
   `health` endpoint for up to 10 s. PASS iff exit 0 AND `health`
   returns 2xx. Teardown.
3. **AT-004.B.** Invoke `boot.sh --profile=<missing_required>`. Wait
   for exit or 60 s timeout. Capture exit code, stderr, stdout. PASS
   iff exit non-zero AND no prior `health` URL responds.
4. **AT-004.C.** Parse the stderr captured in step 3. PASS iff stderr
   contains a literal token the adapter declares as the
   `missing_config_key`.
5. **AT-004.D.** Invoke `boot.sh --profile=inmem_in_prod`. Same shape
   as step 3 with the additional check that stderr contains a literal
   token the adapter declares as the `dev_only_component_name`.
6. **AT-004.E.** Wait 30 s after each aborting profile's exit. Probe
   the base URL from `valid_prod`'s prior boot (different test
   iteration) plus any port listed in adapter.yaml. PASS iff every
   probe fails to connect.
7. Always teardown via `teardown.sh` even on FAIL paths.

## Reporting

```yaml
test: AT-004
sut: <sut-id from adapter>
sut_self_reported_version: <opaque string>
test_suite_version: <repo git SHA>
ran_at: <ISO-8601 UTC>
verdict: PASS | FAIL | INCONCLUSIVE
sub_clauses:
  AT-004.A: PASS | FAIL | INCONCLUSIVE
  AT-004.B: PASS | FAIL | INCONCLUSIVE
  AT-004.C: PASS | FAIL | INCONCLUSIVE
  AT-004.D: PASS | FAIL | INCONCLUSIVE
  AT-004.E: PASS | FAIL | INCONCLUSIVE
observations:
  valid_prod_exit: <int>
  valid_prod_health_status: <int>
  missing_required_exit: <int>
  missing_required_stderr_contains_key: <bool>
  inmem_in_prod_exit: <int>
  inmem_in_prod_stderr_contains_component: <bool>
  ports_responsive_after_abort: <list of int>
adapter_capabilities:
  profiles_declared: [valid_prod, missing_required?, inmem_in_prod?]
  missing_config_key: <string|null>
  dev_only_component_name: <string|null>
notes: <free text>
```

## SUT-agnosticism

The test treats the SUT as a process with a boot script. It makes no
assumption about how the SUT internally enforces fail-closed: validation
annotations (`@RequiredConfig`), constructor checks, separate boot
guards, init container probes — any mechanism is valid as long as the
external observable holds (exit non-zero; no port).

The "production posture" concept is abstract: any flag the SUT uses to
distinguish production from development (env var, config file, command
line arg) is acceptable. The adapter encapsulates the mechanism inside
its boot profiles.

## Adapter requirements

| Field                                            | Required | Notes                                                  |
|--------------------------------------------------|----------|--------------------------------------------------------|
| `profiles.valid_prod` (boot.sh argument)         | yes      | Without it the entire test is INCONCLUSIVE             |
| `profiles.missing_required` (boot.sh argument)   | no       | Absence → AT-004.B, AT-004.C INCONCLUSIVE              |
| `profiles.inmem_in_prod` (boot.sh argument)      | no       | Absence → AT-004.D INCONCLUSIVE                        |
| `missing_config_key` (string)                    | no       | Needed for AT-004.C; substring-matched against stderr  |
| `dev_only_component_name` (string)               | no       | Needed for AT-004.D; substring-matched against stderr  |
| `endpoints.health`                               | yes      | For AT-004.A success check                             |

The boot.sh script's contract (from
[../README.md](../README.md)) is unchanged: last stdout line is the
base URL on success; exit code is 0 on success, non-zero on failure.
AT-004 simply exercises it with multiple profiles.
