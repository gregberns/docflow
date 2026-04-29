# Research — C-EVAL-5 (Testing-strategy documentation)

Lightweight pass. Most decisions are downstream of C-EVAL-4 (the doc describes what that work introduces). Two open questions resolved here.

## Q1: Where does the testing-strategy doc live?

Two reasonable locations:

1. `backend/docs/testing-strategy.md` — package-local, lives with the code.
2. Inside the README at the repo root — the open `df-9c2.12 — C7.12 README` ticket will need this content anyway.

**Recommendation: write the doc in the kerf substrate at `05-specs/testing-strategy.md` for the implementation phase to copy verbatim into the README.** The README ticket is open; landing the doc as part of the C-EVAL-4 work would conflict with whatever shape `C7.12 README` takes. The kerf substrate version is the source of truth; `C7.12` references it.

If the README ticket has not landed by the time C-EVAL-4 ships, an alternative is `docs/testing.md` at the repo root as a placeholder that the README later links to. Either way, the kerf substrate version is reusable.

## Q2: What's the structure?

Sections:

1. **Overview.** One paragraph: the four layers, their purposes, when each runs.
2. **Layer 1: Unit tests.** Per-component tests; no Spring context. Run inside `./gradlew check` and therefore `make test`. JaCoCo 70% line-coverage threshold applies.
3. **Layer 2: Scenario tests (`make test`).** Full Spring stack via `Application.class`; real Postgres + Flyway via Testcontainers; real PDFBox; stubbed `LlmClassifier` / `LlmExtractor`; deterministic; no API key. Cover the 12 scenarios listed in the framework spec.
4. **Layer 3: Live HTTP smoke (`HappyPathSmokeTest`).** One sample, full stack, live API. Gated on `ANTHROPIC_API_KEY`. Inside `make test` but skipped when the key is absent.
5. **Layer 4: Eval rigs (on demand only).**
   - `make eval` → `./gradlew evalRun` — Java; isolates `LlmClassifier` + `LlmExtractor` on 12 samples; produces a markdown report; tests prompt quality.
   - `eval/harness/run.py` — Python; drives the running stack via HTTP on 23 samples; tests full-pipeline end-to-end.
6. **What is not tested.** Playwright covers the frontend (separate `make e2e` gate); load and stress are out of scope.
7. **When to add to which layer.** A short decision tree:
   - "I want to test a workflow guard": scenario tests.
   - "I want to test a new prompt on real samples": `evalRun` (`make eval`).
   - "I want to test a new HTTP error code": scenario tests (or a thin controller-level unit test if the case is purely controller logic).
   - "I want to test the full pipeline against the real LLM": `eval/harness/run.py`.
   - "I want to test a workflow property over many random inputs": jqwik unit tests (existing pattern in `WorkflowEnginePropertyTest`).
   - "I want to test the frontend": Playwright (`make e2e`).

## Constraints

- Markdown, no emoji.
- Match the style of existing kerf substrate (descriptive prose, dry tone).
- ~2 pages rendered. Not a treatise; a reference.

No external research. The doc is a synthesis of `02-analysis.md` §3 plus the C-EVAL-4 spec.
