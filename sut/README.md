# sut/ — System Under Test (SUT) abstraction

This directory defines what it means to be an SUT, and how a specific
implementation registers itself as one.

## Files

- [sut-contract.md](sut-contract.md) — the abstract surface every SUT must
  expose to be testable by this acceptance suite.
- `adapters/<sut-id>/` — one subdirectory per SUT, each containing an
  `adapter.yaml` that maps the SUT's concrete endpoints, hooks, and state
  names to the abstract capabilities declared in `sut-contract.md`.

## Adding a new SUT

1. Choose a kebab-case identifier for your SUT (no version suffix; the
   SUT self-reports its version at runtime via the `health` endpoint).
2. Create `adapters/<your-sut-id>/`.
3. Add an `adapter.yaml` covering at minimum the required fields in
   [sut-contract.md](sut-contract.md).
4. Add a `README.md` describing how to boot the SUT for testing.
5. Add a `boot.sh` (POSIX shell) that the test runner invokes to bring the
   SUT to a healthy state. The script's contract:
   - Stdout: the LAST line MUST be the SUT's base URL.
   - Exit code: 0 on healthy boot, non-zero on failure.
   - Idempotent: re-running must either bring the SUT up cleanly or
     detect an existing healthy instance.
6. Add a `teardown.sh` that cleans up resources `boot.sh` allocated.
7. Open a PR. The adapter is reviewed independently of the test specs.

The first iteration of this repository ships only `adapter.yaml` and
`README.md` for the first SUT; `boot.sh` / `teardown.sh` are added in a
follow-up.

## What an adapter MUST NOT do

- Modify any file under [../specs/](../specs/).
- Disable or skip any sub-clause. INCONCLUSIVE is the only acceptable way
  to indicate "this SUT cannot satisfy this sub-clause"; FAIL is the only
  acceptable way to indicate "this SUT does not satisfy".
- Reach into the SUT's source code to assert behaviour. The adapter is a
  binding from abstract to concrete observable surfaces — nothing else.

## What an adapter MAY do

- Translate between the abstract and concrete vocabularies (state names,
  JSONPath expressions, URL templates).
- Bring up SUT-specific infrastructure (databases, containers, sidecars)
  inside `boot.sh`.
- Inject SUT-specific hooks (e.g. "set worker delay") via mechanisms
  appropriate to that SUT, as long as those hooks are documented in the
  adapter.yaml.
- Declare itself unable to expose a particular hook; dependent sub-clauses
  then report INCONCLUSIVE rather than FAIL.

## Reviewer rule for adapter PRs

The adapter review checks one thing first: does the adapter add ANY
content under [../specs/](../specs/) or [../PHILOSOPHY.md](../PHILOSOPHY.md)
in the same PR? If yes, the PR is split. Spec authorship and SUT binding
must remain editorially separate; bundling them is the start of the
overfitting path this repo exists to prevent.
