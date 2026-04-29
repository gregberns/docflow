# Problem Space — Test Coverage and Eval Infrastructure (`evals`)

## Summary

DocFlow's pipeline runs end-to-end (upload → PDFBox text-extract → classify → extract → workflow), but cross-component test coverage in CI is thin. The only integration test that exercises the full path requires a live `ANTHROPIC_API_KEY`, so CI never validates the seam. There is also a known incomplete spec — `inputModality = PDF` is set in seed YAML for four doc-types but ignored by the LLM-calling code — and PDFBox extraction has never been validated against the real sample PDFs. This work plans (a) verification of the current system's behavior on real samples, (b) removal of the dead `inputModality` PDF branch, and (c) a deterministic scenario-test layer that drives the full pipeline with a stubbed LLM in CI. Implementation of (b) and (c) is handed to a separate agent via beads tickets after this kerf work completes.

## Goals

1. Validate PDFBox extraction quality on all 23 real sample PDFs and produce a written verdict.
2. Capture a live-LLM end-to-end baseline (full pipeline via HTTP, all 23 samples) — complementing the just-landed `./gradlew evalRun` which only tests `LlmClassifier` + `LlmExtractor` in isolation on 12 samples.
3. Plan the removal of `inputModality = PDF` from spec, schema, YAML, entity, view, and the message-builder code.
4. Plan the scenario-test framework: stub seam at `LlmClassifier` / `LlmExtractor`, fixture YAML format, Spring `scenario` profile, list of 12 scenarios.
5. Produce a complete set of beads-ready ticket descriptions (title + what + why + acceptance criteria) so a downstream implementation agent can pick the work up cleanly.
6. Produce documentation of the testing strategy (eval rig + scenario tests + their respective scopes) that can later inform README work (C7.12 is open).

## Non-goals

- Implementing the scenario tests themselves (handed off via beads).
- Implementing the `inputModality` removal (handed off via beads).
- Wiring up `inputModality = PDF` — explicitly removed, not built.
- Replacing, modifying, or duplicating `./gradlew evalRun` (the just-landed C3.11). The Python harness complements it; it does not subsume it.
- New eval scoring metrics beyond what `EvalScorer` already provides.
- OCR or non-PDFBox text extraction.
- Frontend testing changes (Playwright stays as-is).
- Performance, load, or stress testing.

## Constraints

- `problem-statement/` is read-only. All validation artifacts (extracted text, eval reports) live outside it under `eval/...`.
- Scenario tests must run in `make test` (the fast gate) without an API key, with no flakiness.
- Spec edits land in `.kerf/project/docflow/05-specs/` and `06-integration.md`, tasks in `07-tasks.md`. Beads tickets reflect the same work.
- The parent `docflow` kerf work remains the source of truth for component contracts. This work amends it rather than replacing.
- Greenfield — no backwards-compatibility burden.

## Success criteria

1. `eval/pdfbox-check/REPORT.md` exists, covers all 23 samples, has per-file verdicts and an aggregate "text-only path is viable / marginal / needs intervention." **Status: done — verdict viable, all 23 clean.**
2. `eval/reports/<timestamp>.md` exists from a full-batch live-API run (or a clear status note if the stack/key prerequisites weren't met). Contains per-sample doc-type accuracy and per-field hit/miss against a hand-authored label set.
3. A written removal plan for `inputModality` enumerates every file that changes (spec sections, migration delta, YAML keys, Java classes, tests).
4. A written scenario-framework spec defines the stub seam (`LlmClassifier` / `LlmExtractor`), fixture YAML schema, Spring profile setup, fixture loader, and the full list of 12 scenarios with one-paragraph descriptions each.
5. Beads-ready ticket descriptions exist for everything in (3) and (4), shaped at 1–2 hours per ticket where possible. Tickets are not yet created in beads — that is a deliberate next step the user triggers.
6. Spec edit list is precise: each spec file gets a numbered list of "section X gets new subsection Y" or "line N changes from A to B" entries — concrete enough that the implementation agent doesn't have to re-derive intent.
7. Testing-strategy documentation (in this kerf work's outputs) clearly delineates: what `./gradlew evalRun` covers, what the Python harness covers, what scenario tests cover, and how they relate. Content is reusable for the C7.12 README task.
