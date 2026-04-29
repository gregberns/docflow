# Change Spec — C-EVAL-5 (Testing-strategy documentation)

A short prose document describing the four test layers, their scopes, and how to choose between them. Reusable verbatim by the open `df-9c2.12 — C7.12 README` ticket. The doc itself lives in this kerf substrate at `05-specs/testing-strategy.md`; the implementation phase copies it into the README (or a linked `docs/testing.md`) as part of the README ticket.

---

## Requirements (from `03-components.md`)

R-EVAL-5.1 through R-EVAL-5.5 (verbatim).

## Research summary (from `04-research/c-eval-5/findings.md`)

- The testing-strategy doc is descriptive, not prescriptive. It documents the four layers as built; the implementation phase does not "implement" the strategy, it copies the doc.
- Location decided: kerf substrate is the source of truth (`05-specs/testing-strategy.md`); `df-9c2.12` consumes it.
- Layer ordering: unit → scenario → live smoke → eval rigs. This puts the deterministic, fast layers first and the on-demand layers last.

## Approach

Write the file once, in this kerf substrate. The C7.12 README ticket then includes a step "copy substrate testing-strategy doc into README (or `docs/testing.md` if README gets long)".

The doc is one file, ~2 pages rendered, markdown, no emoji, dry tone matching the existing kerf substrate.

## Files & changes

### New

- `/Users/gb/.kerf/projects/basata/evals/05-specs/testing-strategy.md`

### Edited

- None during implementation. The README ticket (`df-9c2.12`) consumes the doc later.

## Document outline

```
# Testing strategy

DocFlow has four test layers. Each has a distinct scope, run cadence, and
prerequisites. The fast gate (`make test`) runs the first three; the eval
rigs are on demand.

## Layer 1: Unit tests
- Scope: ...
- Run: `make test` (transitively `./gradlew check`)
- Prerequisites: none
- Duration: <1 minute

## Layer 2: Scenario tests
- Scope: full Spring stack, real Postgres + Flyway + PDFBox; LLM seams stubbed.
  12 scenarios cover happy path, classification edge cases, retype paths,
  workflow guards, concurrent uploads, error handling.
- Run: `make test`
- Prerequisites: Docker (Testcontainers Postgres). No API key.
- Duration: 2-5 minutes

## Layer 3: Live HTTP smoke (HappyPathSmokeTest)
- Scope: one sample, full stack, live Anthropic API.
- Run: `make test` (skipped when ANTHROPIC_API_KEY is unset)
- Prerequisites: Docker, ANTHROPIC_API_KEY
- Duration: 1-2 minutes

## Layer 4: Eval rigs (on demand)

### `make eval` -> ./gradlew evalRun
- Scope: LlmClassifier and LlmExtractor in isolation against a 12-sample
  manifest. Live Anthropic API. Tests prompt quality.
- Run: `make eval` (or `cd backend && ./gradlew evalRun`)
- Prerequisites: ANTHROPIC_API_KEY
- Duration: 2-5 minutes
- Output: eval/reports/latest.md

### Python harness (eval/harness/run.py)
- Scope: full HTTP API, 23-sample corpus, live Anthropic API. End-to-end
  signal across PDFBox + classify + extract + workflow + persistence.
- Run: stack up (`make start`), then `python3 eval/harness/run.py`
- Prerequisites: ANTHROPIC_API_KEY, running stack
- Duration: 5-10 minutes
- Output: eval/reports/<timestamp>.md

## What is not tested

- Frontend interactions: covered by Playwright via `make e2e`.
- Performance, load, stress: out of scope for this take-home.
- LLM safety / content filtering: not exercised.

## When to add to which layer

| You want to test... | Layer |
|---|---|
| A workflow guard branches correctly | Scenario tests |
| A new HTTP error code | Scenario tests (or controller-level unit if pure controller logic) |
| A new prompt against real samples | `evalRun` |
| Full pipeline against the live LLM | Python harness |
| A workflow property over many random inputs | jqwik unit (existing pattern in WorkflowEnginePropertyTest) |
| A new validation rule on YAML loader | Unit + scenario tests for load failures |
| Frontend interaction | Playwright (`make e2e`) |
```

(The actual prose is written by the implementation agent into `testing-strategy.md`; this outline captures the headings, intent, and table.)

## Acceptance criteria

1. The file `05-specs/testing-strategy.md` exists in the kerf substrate at `/Users/gb/.kerf/projects/basata/evals/05-specs/testing-strategy.md`.
2. The doc covers all seven section headings listed above.
3. The "When to add to which layer" table contains at least the seven rows shown.
4. The doc is readable in under 10 minutes by someone who has read `CLAUDE.md` once.
5. No emoji.

## Verification

Manual read by the user (or by the implementation agent at write-time). No automated check beyond "file exists, has the seven section headings". A brief `grep` confirms the headings:

```
grep -E '^## ' /Users/gb/.kerf/projects/basata/evals/05-specs/testing-strategy.md
```

Expected output: seven `## ...` lines matching the outline.

## Error handling and edge cases

- **The doc goes stale.** Mitigation: when the C7.12 README ticket runs, the implementation agent re-reads `05-specs/testing-strategy.md` and the four layer artifacts (`evalRun`, scenario suite, `HappyPathSmokeTest`, Python harness) and reconciles. If any layer's name or location changed, the doc is updated in the kerf substrate first, then copied.
- **The README ticket prefers a different structure.** Acceptable. The kerf-substrate doc is a source of content; the README ticket reshapes as needed.

## Migration / backwards compatibility

None.
