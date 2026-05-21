# Philosophy

## Three rules of thumb

1. Test the SUT's externally observable behaviour, not its internal
   structure.
2. Cite authority that exists independently of any single SUT.
3. Prefer abstract capability names; let adapters do the mapping.

## What "black box" means here

A black-box test sees only what an unprivileged external client could see:

- HTTP request / response pairs (status, headers, body, timing).
- Side effects observable through other public endpoints (polling, lists,
  notifications).
- Behaviour under client-induced conditions (disconnect, retry, parallelism,
  malformed inputs).
- Logs and metrics IF the SUT exposes them on a public surface (Prometheus
  scrape endpoint, structured log shipper, etc.).

A black-box test does NOT see:

- Class names, method signatures, package layouts.
- Internal queue depths, thread pool sizes, lock contention.
- Database table names, column definitions, transaction boundaries.
- Configuration files internal to the SUT process.

If a test specification mentions any of the latter, it has crossed the
boundary and must be rewritten.

## Why we forbid citing the SUT's authority documents

Every mature codebase publishes some internal authority: architecture
documents, ADRs, RFCs, design briefs. These are valuable artifacts for the
team that owns the code. They are NOT valid authority for an acceptance
test, for three reasons:

1. **Co-evolution.** When the SUT's authority doc and the test case evolve
   together, the test reflects "what the team agreed to do today", not
   "what the system should do under first principles". The test stops
   detecting genuine architectural mistakes.
2. **Overfitting.** Developers reading both the source and the test
   collapse to "the spec says X because the test wants X" — circular
   authority. The test becomes a developer convenience, not a check.
3. **Multi-implementation.** If a second implementation appears (a fork, a
   competing project, a vendor variant), the original SUT's authority docs
   do not apply to it. An acceptance suite citing them cannot validate the
   second SUT honestly.

## What we cite instead

Acceptable authority kinds (used in spec front matter as `kind:` tags):

| Kind                | Examples                                                                          |
|---------------------|-----------------------------------------------------------------------------------|
| `first-principle`   | "A long-running task must not hold the client transport thread."                  |
| `industry-pattern`  | Microsoft Cloud Design Patterns; Enterprise Integration Patterns                  |
| `standard`          | RFC, ISO/IEC, IEEE, W3C, OpenTelemetry semantic conventions                       |
| `human-factors`     | Nielsen 1993; Card et al. 1991; Norman 1988                                       |
| `security-pattern`  | OWASP, NIST, CWE                                                                  |
| `academic`          | Peer-reviewed publications                                                        |
| `historic-incident` | Public post-mortems (e.g. AWS, GitLab, Cloudflare) that motivate the invariant    |

The list is open; new kinds may be added when a new authority source arises
that does not fit existing buckets. Adding a kind requires a PR that
updates this file.

## What we never cite

- Any source under any specific SUT's repository tree.
- Section numbers in any SUT's architecture documents.
- Rule IDs, ADR IDs, governance IDs from any specific SUT.
- Source code paths, package names, class names from any specific SUT.

## INCONCLUSIVE is a feature, not a flaw

Conformance suites often try to coerce binary PASS / FAIL outcomes. We
deliberately introduce a third verdict:

- **PASS** — the SUT demonstrably satisfied the sub-clause.
- **FAIL** — the SUT demonstrably violated the sub-clause.
- **INCONCLUSIVE** — the SUT did not expose enough surface for the test to
  determine PASS or FAIL.

INCONCLUSIVE is honest. An SUT may legitimately choose not to expose a
particular hook (e.g. "configure worker delay") for security or simplicity
reasons; an INCONCLUSIVE verdict reports the gap without falsely declaring
conformance. A suite that hides INCONCLUSIVE as "skipped" or counts it as
PASS is a suite that has been gamed.

## How the boundary holds in practice

The test suite never imports any SUT's code. The test suite never reads any
SUT's source tree. The test suite never references any SUT's internal
documentation in a spec. The test suite communicates with each SUT through
exactly one file in this repo: `sut/adapters/<sut-id>/adapter.yaml`,
authored by that SUT's integrator.

When you find yourself wanting to import an SUT type, read an SUT's
architecture document, or cite an SUT's rule — stop. Whatever you are
trying to assert can be expressed in observable behaviour. If it cannot,
it is not a property of the acceptance contract; it is an implementation
detail of one SUT.

## A reviewer's heuristic

When reviewing a new spec or change to an existing spec, ask:

1. Could this exact text be applied verbatim to a second, unrelated SUT?
2. If the SUT's source code were deleted tomorrow, would the spec still
   make sense?
3. Does each `authority:` entry name a source that exists independently of
   any SUT?
4. Is every observable named here something an external HTTP client (or
   equivalent) could measure without privileged access?

If any answer is "no", the change is not yet ready.
