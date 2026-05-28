# spring-ai-ascend Integration Acceptance Test Plan

## 1. Method Positioning

This test repository adopts an **architecture-aware external integration acceptance** method: the specification layer remains SUT-agnostic and uses externally observable behavior as the primary PASS/FAIL criterion; the adapter layer records spring-ai-ascend-specific feature-to-module collaboration mappings and observable evidence, so that coverage analysis and result interpretation can be guided by the SUT architecture. Tests may understand internal module responsibilities, but private implementation details are not acceptance objects.

In other words, this plan is not saying that modules are not connected during testing. It says that **module call relationships themselves are not written as the acceptance object**. During test design, the test team should understand which modules are expected to collaborate behind a feature. During test judgment, the evidence should come from external behavior, public observation surfaces, traces, metrics, audits, state transitions, and similar observable artifacts.

## 2. Acceptance Goals

For enterprise-grade Agent Runtime systems such as spring-ai-ascend, integration acceptance focuses on:

1. Whether an external caller can trigger runtime capabilities through stable contracts.
2. Whether multiple modules behind a feature form verifiable collaboration.
3. Whether key architectural commitments appear as externally observable behavior.
4. Whether the system remains safe and recoverable under exceptions, retries, tenant-boundary violations, and data-plane pressure.
5. Whether acceptance specifications avoid overfitting to any specific SUT implementation.

## 3. Layered Boundaries

### 3.1 Specification Layer: SUT-Agnostic

AT specifications under `specs/` must remain SUT-agnostic:

- They must not reference spring-ai-ascend module names, class names, or package names.
- They must not reference spring-ai-ascend ADRs, Rules, `CLAUDE.md`, or `ARCHITECTURE.md`.
- They must not assert private methods, internal classes, database tables, or internal queues.
- They describe only externally observable behavior, abstract capabilities, verdict criteria, and reporting shapes.

### 3.2 Adapter Layer: SUT-Aware

`sut/adapters/spring-ai-ascend/` may record SUT-specific information, including:

- Mappings from abstract capabilities to concrete endpoints.
- Mappings from abstract states to SUT states.
- Feature-to-module collaboration coverage mappings.
- Mappings from observable evidence to traces, metrics, audits, and endpoints.

The adapter layer may know spring-ai-ascend module responsibilities, but it must still not require tests to import SUT code or depend on private implementation details.

### 3.3 Judgment Layer: External Behavior First, Public Evidence as Support

PASS / FAIL is primarily based on:

- HTTP status codes, response bodies, and response latency.
- Run state transitions.
- Public query endpoints.
- Public metrics.
- Public traces/spans.
- Public audit events / reports.

Two kinds of conclusions are separated during judgment:

1. **Conformance verdict**: whether the SUT satisfies the externally observable behavior defined by an AT specification. This is the primary basis for PASS / FAIL / INCONCLUSIVE.
2. **Coverage explanation**: whether there is sufficient public evidence to explain the collaboration among spring-ai-ascend modules behind the feature. This is used to describe coverage quality and locate evidence gaps.

If the external behavior required by an AT specification cannot be observed, report `INCONCLUSIVE`. If the external behavior satisfies the specification but there is not enough evidence to prove that an expected module participated, do not force the specification-level PASS into a FAIL. Instead, mark an evidence gap in coverage analysis, or make that public observation surface a required evidence item in the relevant AT before using it for verdict judgment.

## 4. Feature-Driven Module Collaboration Acceptance

Acceptance cases should not use an internal call chain such as `client -> service -> bus -> engine` as the test object. They should use features as the test object.

Example: canceling a run.

External feature:

1. Create a long-running task.
2. Request cancellation while the task is running.
3. Query state changes.
4. Re-verify that the cancellation interface remains available under data-plane pressure.

Expected collaboration:

- client/API receives the external request.
- middleware performs authentication, tenant scoping, policy enforcement, and trace propagation.
- service manages the run lifecycle.
- bus carries the control command.
- execution-engine reacts to cancellation and advances state.

Acceptable evidence:

- `cancel` returns 2xx or an explicit non-404 4xx.
- `poll` eventually observes `CANCELLED` or another explicit terminal state.
- `create` / `cancel` / `poll` are correlated by the same runId / traceId.
- Control-plane-related metrics or audit events are visible.

Unacceptable evidence:

- A private Java method was called.
- An internal class was instantiated.
- An internal table was written.
- An internal package path appeared in a stack trace.

## 5. Verdict Principles

| Verdict / Label | Meaning |
|---|---|
| PASS | The externally observable behavior defined by the AT specification satisfies the requirement; coverage analysis may further explain whether module-collaboration evidence is sufficient. |
| FAIL | The externally observable behavior defined by the AT specification violates the requirement, or public evidence shows that a security boundary / external contract is broken. |
| INCONCLUSIVE | The SUT does not expose enough external behavior surface to honestly determine the AT requirement. |
| EVIDENCE_GAP | Coverage explanation label: external behavior may have satisfied the requirement, but there is not enough public observable evidence to prove that an expected module collaborated. It is not a top-level verdict. |

`INCONCLUSIVE` is neither PASS nor FAIL. It records insufficient acceptance surface and prevents the test repository from invading SUT internals just to obtain a binary result. `EVIDENCE_GAP` is used for coverage explanation and does not change the specification-level verdict, unless the corresponding AT has already defined that observation surface as a required condition.
