# Testing strategy

DocFlow has four test layers. Each has a distinct scope, run cadence, and prerequisites. The fast gate (`make test`) runs the unit, scenario, and live-smoke layers; the eval rigs are on demand only.

## Layer 1: Unit tests

Per-component tests with no Spring context. They cover small units of logic — workflow engine transitions and properties, JSON-schema construction, fixture loaders, the eval scorer, individual controllers, parsers, and validators.

- Run: `make test` (transitively `./gradlew check`)
- Prerequisites: none
- Duration: under one minute

JaCoCo line coverage is checked here at a 70% threshold.

## Layer 2: Scenario tests

Full Spring-stack integration tests. Each scenario boots the application via `Application.class`, runs Postgres in Testcontainers with real Flyway migrations, executes real PDFBox text extraction, and stubs only `LlmClassifier` and `LlmExtractor`. Twelve scenarios cover the happy path, classification edge cases (wrong type, missing required fields, schema violations), retype paths (no-op, success, failure), workflow-guard branches (lien-waiver conditional vs. unconditional), origin restoration, concurrent uploads on a single SSE stream, corrupt PDFs, and action validation on terminal documents.

The stubs are deterministic: each scenario fixture YAML names a real PDF, the canned classification result, the canned extraction fields, and the expected end state.

- Run: `make test`
- Prerequisites: Docker (for Testcontainers Postgres). No API key.
- Duration: 2 to 5 minutes

## Layer 3: Live HTTP smoke (`HappyPathSmokeTest`)

A single Spring-Boot integration test that uploads one Riverside invoice PDF against the running stack and the live Anthropic API, polls for `AWAITING_REVIEW`, and approves the document. It is the only live-API HTTP-seam test in `make test`.

- Run: `make test` (skipped when `ANTHROPIC_API_KEY` is unset)
- Prerequisites: Docker, `ANTHROPIC_API_KEY`
- Duration: 1 to 2 minutes

## Layer 4: Eval rigs (on demand)

### `make eval` — `./gradlew evalRun`

Java-side harness that calls `LlmClassifier` and `LlmExtractor` directly against a 12-sample manifest, with rawText injected from labeled fixtures. It bypasses HTTP, PDFBox, the orchestrator, and the workflow. The signal is prompt quality.

- Run: `make eval` (or `cd backend && ./gradlew evalRun`)
- Prerequisites: `ANTHROPIC_API_KEY`
- Duration: 2 to 5 minutes
- Output: `eval/reports/latest.md`

### Python harness — `eval/harness/run.py`

Drives the running stack via the public HTTP API on the full 23-sample corpus. Uploads each PDF, polls until `AWAITING_REVIEW`, captures the `DocumentView`, and compares against the hand-authored label set. The signal is full-pipeline end-to-end behavior including PDFBox, classify, extract, workflow materialization, and persistence.

- Run: `make start`, then `python3 eval/harness/run.py`
- Prerequisites: `ANTHROPIC_API_KEY`, running stack
- Duration: 5 to 10 minutes
- Output: `eval/reports/<timestamp>.md`

## What is not tested

- Frontend interactions: covered by Playwright via `make e2e` (separate gate; not part of `make test`).
- Performance, load, stress: out of scope.
- LLM safety / content filtering: not exercised.
- OCR or non-PDFBox text extraction paths: not present.

## When to add to which layer

| You want to test... | Layer |
|---|---|
| A workflow guard branches correctly | Scenario tests |
| A new HTTP error code surfaces correctly end-to-end | Scenario tests |
| Pure controller logic in isolation | Unit tests |
| A new prompt against real samples (live API) | `make eval` |
| The full pipeline against the live LLM (live API) | Python harness |
| A workflow property over many random inputs | jqwik unit tests (existing pattern in `WorkflowEnginePropertyTest`) |
| A new validation rule on the YAML loader | Unit tests for the rule; scenario test for end-to-end behavior |
| A new piece of frontend UI | Playwright (`make e2e`) |
