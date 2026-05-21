# agent-runtime-acceptance

A black-box acceptance test suite for agent runtime systems.

This repository defines the externally-observable behaviour that any system
calling itself an "agent runtime" must exhibit. It is implementation-
agnostic: the same test suite can validate any conforming SUT (System Under
Test), regardless of language, framework, or deployment topology.

The repository is intentionally independent of any specific agent runtime
project. Tests cite first principles, industry standards, and human-factors
literature — never the internal authority documents of a particular
product. This independence is structural, not aspirational: it prevents the
test corpus from being shaped to fit the convenience of any single
implementation.

## Status

Bootstrap. Repository ships:

- [PHILOSOPHY.md](PHILOSOPHY.md) — the methodology this repo enforces.
- [sut/sut-contract.md](sut/sut-contract.md) — the abstract surface every SUT
  must expose to be testable, plus boot-profile and capacity declarations
  introduced by lifecycle / load tests.
- [specs/000-state-vocabulary.md](specs/000-state-vocabulary.md) — shared
  vocabulary used across test cases.
- Five test specifications under [specs/](specs/):
  - [AT-001](specs/AT-001-async-task-boundary.md) — async long-running task boundary
  - [AT-002](specs/AT-002-multi-tenant-isolation.md) — multi-tenant read/write isolation
  - [AT-003](specs/AT-003-submission-schema-strict-matching.md) — submission schema strict matching
  - [AT-004](specs/AT-004-fail-closed-startup.md) — fail-closed startup under production posture
  - [AT-005](specs/AT-005-control-plane-liveness-under-load.md) — control-plane liveness under data-plane saturation
- [sut/adapters/spring-ai-ascend/](sut/adapters/spring-ai-ascend/) — one
  sample SUT adapter showing how an implementation binds to the suite.

Test implementations (Java), boot/teardown scripts, CI, tooling, and
certification reports follow in subsequent iterations.

## Layout

```
specs/                       # Test case specifications (markdown, not code)
  README.md                  # How to author a spec; author checklist
  000-state-vocabulary.md    # Shared abstract state names
  AT-001-...md               # Sample test case

sut/                         # System Under Test abstraction
  README.md                  # What an SUT is; how to add one
  sut-contract.md            # What capabilities an SUT must expose
  adapters/                  # One subdirectory per SUT
    spring-ai-ascend/        # First SUT adapter (sample)
      README.md
      adapter.yaml
```

## How to read this repo

1. [PHILOSOPHY.md](PHILOSOPHY.md) — why this repo exists and why it stays
   independent.
2. [sut/sut-contract.md](sut/sut-contract.md) — the abstract surface.
3. [specs/AT-001-async-task-boundary.md](specs/AT-001-async-task-boundary.md) —
   the test case format.
4. [sut/adapters/spring-ai-ascend/](sut/adapters/spring-ai-ascend/) — what an
   SUT-specific binding looks like.

## How to add a new SUT

Create a new subdirectory under `sut/adapters/<your-sut-id>/` and supply an
`adapter.yaml` declaring how your SUT realises the abstract capabilities in
[sut/sut-contract.md](sut/sut-contract.md). No change to `specs/` is
required.

## How to add a new test case

Add a file under `specs/` following the format of
[AT-001](specs/AT-001-async-task-boundary.md). Cite first principles,
standards, and literature — never another repo's rules, ADRs, or section
numbers. See [specs/README.md](specs/README.md) for the author checklist.

## Reciprocal independence

This repository does not import code from any SUT. SUTs do not import code
from this repository. The only coupling is observational: the test suite
talks to each SUT through the SUT's `adapter.yaml`, and through that file
alone.
