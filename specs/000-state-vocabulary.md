# 000 — Abstract State Vocabulary

This document defines the abstract task states used across acceptance test
specifications. Specifications refer to these state names; each SUT's
adapter maps the SUT's concrete state names to this vocabulary.

## The six states

| Abstract state | Meaning                                                                                  |
|----------------|------------------------------------------------------------------------------------------|
| `RUNNING`      | The task has been accepted and work is in progress.                                      |
| `SUCCEEDED`    | The task completed without error and produced its intended output.                       |
| `FAILED`       | The task terminated due to an error attributable to the SUT or the task definition.      |
| `CANCELLED`    | The task terminated because cancellation was requested and honoured.                     |
| `EXPIRED`      | The task terminated because a declared deadline elapsed without completion.              |
| `SUSPENDED`    | The task is paused awaiting an external event (timer, callback, approval, dependency).   |

A SUT need not use these names internally. The adapter's `state_mapping`
section declares how the SUT's vocabulary projects onto the six.

## Constraints

- The six states are exhaustive for the purposes of acceptance reporting.
  A poll response whose state does not map to one of these six causes an
  INCONCLUSIVE verdict on the relevant sub-clause.
- `RUNNING` and `SUSPENDED` are non-terminal; the remaining four
  (`SUCCEEDED`, `FAILED`, `CANCELLED`, `EXPIRED`) are terminal. An adapter
  that maps a single SUT state to both terminal and non-terminal abstract
  states is invalid; the adapter validator rejects it.
- `PENDING` (queued but not yet started) is intentionally absent. From the
  acceptance perspective, an acknowledged task is `RUNNING`; the SUT may
  distinguish internally but the suite does not.
- Transition legality between states is asserted by specific test cases
  (e.g. the SUT must not transition `SUCCEEDED → RUNNING`). This vocabulary
  document defines the alphabet; the test cases assert the grammar.

## Mapping examples

A SUT whose internal state machine has `PENDING`, `RUNNING`, `WAITING`,
`DONE`, `ERR`, `KILLED`, `TIMEOUT` might declare:

```yaml
state_mapping:
  RUNNING:   [PENDING, RUNNING]
  SUSPENDED: [WAITING]
  SUCCEEDED: [DONE]
  FAILED:    [ERR]
  CANCELLED: [KILLED]
  EXPIRED:   [TIMEOUT]
```

A SUT that has fewer states than six may leave some abstract states
unmapped. Test cases that depend on an unmapped state report INCONCLUSIVE
against that SUT for the corresponding sub-clause.

A SUT cannot declare a state synonym across the terminal / non-terminal
boundary. For example, mapping `RUNNING: [PENDING, DONE]` is invalid
because `DONE` is terminal while `RUNNING` is non-terminal; the adapter
validator rejects this.

## Why exactly these six

The choice is informed by:

- Standard process-lifecycle vocabulary across compute systems (POSIX
  process states, Kubernetes Pod phases, AWS Step Functions execution
  states).
- The minimum set required to express the transitions tested by AT-001
  through AT-010.
- The minimum set required to talk about long-horizon agent runtimes
  specifically — `SUSPENDED` (vs `RUNNING`) is the load-bearing distinction
  that justifies the "agent runtime" category as separate from generic
  job schedulers.

The set is closed at six. A future test that needs a seventh state
proposes both the state and at least one test case that consumes it, in
the same PR.
