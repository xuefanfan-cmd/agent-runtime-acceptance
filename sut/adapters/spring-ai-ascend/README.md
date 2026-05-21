# Adapter — spring-ai-ascend

This adapter binds the abstract SUT contract to the `spring-ai-ascend`
agent runtime.

## What this adapter is

It is the only file in this repository (alongside its sibling
`adapter.yaml`) that contains the product name `spring-ai-ascend` in a
load-bearing way. It maps:

- Abstract endpoints → the SUT's `/v1/...` HTTP routes.
- Abstract state names → the SUT's concrete state vocabulary.
- Abstract response field paths → the SUT's JSON response shapes.
- Abstract hooks → SUT-specific injection mechanisms (where supported).

## How to boot the SUT

The acceptance runner will call `boot.sh` (not yet shipped in this initial
commit; will be added in a follow-up iteration). The script's contract is
defined in [../../README.md](../README.md):

- Stdin: ignored.
- Args: optional; `--profile <name>` may be used to vary the boot
  configuration.
- Stdout: the LAST line MUST be the base URL of the running SUT
  (e.g. `http://localhost:8080`).
- Stderr: free for diagnostic output.
- Exit code: 0 on healthy boot, non-zero on failure.
- Side effects: may pull a docker image; may start one or more containers;
  must clean up via the corresponding `teardown.sh`.

Until `boot.sh` lands, an operator wishing to run this adapter manually
boots the SUT according to the SUT's own quickstart (whatever that
quickstart may be) and then invokes the test runner with the resulting
base URL.

## Capabilities not yet exposed

| Hook                     | Status      | Effect on report                              |
|--------------------------|-------------|------------------------------------------------|
| `set_worker_delay_ms`    | unavailable | AT-001.C and AT-001.E report INCONCLUSIVE      |
| `force_worker_failure`   | unavailable | AT-005 (when authored) will report INCONCLUSIVE |
| `seed_tenant`            | unavailable | AT-002 (when authored) will report INCONCLUSIVE |
| `revoke_credentials`     | unavailable | AT-006 (when authored) will report INCONCLUSIVE |

The intent is to expand these hooks as the SUT grows the surface to
support them. Each hook addition is a PR in THIS adapter's directory —
never in the SUT's repository. The acceptance suite remains the authority
on which hooks exist; the SUT chooses whether to expose them.

## Versioning

The adapter declares which version of the SUT contract it was authored
against, via `adapter.yaml#contract_version`. Bumping the SUT contract
triggers a review pass over every adapter; this adapter's owners receive
a notification through the PR that changes the contract.

## Editorial separation

Changes to this adapter MUST NOT be bundled with changes to
[../../../specs/](../../../specs/) or
[../../../PHILOSOPHY.md](../../../PHILOSOPHY.md) in the same PR. The
reviewer will split such PRs on sight; the separation is a structural
defence against the spec corpus being shaped to fit any single SUT's
convenience.
