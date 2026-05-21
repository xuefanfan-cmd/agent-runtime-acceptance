# specs/ — Acceptance test specifications

Each file under this directory is either a single acceptance test case
(`AT-NNN`) or a shared vocabulary / methodology helper (`0NN-...`).
Specifications are markdown, not code. They describe what an SUT must
demonstrate; they do not implement the test runner.

## File naming

```
AT-<NNN>-<kebab-slug>.md      # Test case
0<NN>-<kebab-slug>.md          # Vocabulary or methodology helper
```

Numbers are assigned in order of merge into `main`. Gaps are allowed
(retired tests keep their number to preserve cross-references).

## Required sections in every test case

1. **Front matter** (yaml) — `id`, `title`, `status`, `sut_capability`,
   `authority`.
2. **What this test asserts** — the sub-clauses (`AT-NNN.A`, `.B`, ...).
   Each sub-clause is a single observable invariant with its own
   PASS / FAIL / INCONCLUSIVE criterion.
3. **What this test does NOT assert** — explicit scoping out.
4. **Procedure** — step-by-step. A competent engineer should be able to
   realise the test from the procedure alone, without consulting any SUT.
5. **Reporting** — the yaml shape the test runner emits when this test
   completes.
6. **SUT-agnosticism** — a paragraph confirming the test does not assume
   any specific SUT language, framework, or architecture.
7. **Adapter requirements** — the table of fields the SUT's adapter must
   supply for this test to run.

## Author checklist

Before opening a PR with a new test case, confirm each item:

- [ ] No `authority:` entry cites any specific SUT's repository.
- [ ] No procedure step assumes a specific SUT language or framework.
- [ ] All capability names match those declared in
      [../sut/sut-contract.md](../sut/sut-contract.md).
- [ ] Each sub-clause has its own PASS / FAIL / INCONCLUSIVE criterion.
- [ ] At least one INCONCLUSIVE path exists for SUTs that cannot expose
      a required hook.
- [ ] The reporting yaml shape is complete and machine-parseable.
- [ ] The four reviewer heuristics in [../PHILOSOPHY.md](../PHILOSOPHY.md)
      all answer "yes" for this spec.

## Authority kinds permitted in front matter

See [../PHILOSOPHY.md](../PHILOSOPHY.md). Current accepted kinds:

`first-principle` · `industry-pattern` · `standard` · `human-factors` ·
`security-pattern` · `academic` · `historic-incident`

Adding a new kind requires a PR that updates `PHILOSOPHY.md`.

## Vocabulary documents

Specs reference shared vocabulary defined in `0NN-...md` files (currently
just [000-state-vocabulary.md](000-state-vocabulary.md)). Vocabulary
documents are not test cases — they define terms the test cases use.

## Status enum

| status               | meaning                                                                    |
|----------------------|----------------------------------------------------------------------------|
| `active`             | The spec is complete; an SUT can be tested against it today.               |
| `draft`              | Sub-clauses are still being refined; SUTs may report INCONCLUSIVE for all. |
| `retired`            | Superseded; kept for traceability. Number is not reused.                   |

A spec must NEVER carry a status that references a particular SUT's wave,
release cycle, or roadmap. Wave-coupled vocabulary belongs to one specific
SUT and is therefore forbidden here.
