# Components — `evals`

Five components. Two already done (1, 2); three to plan (3, 4, 5).

The goal-to-component map (from `01-problem-space.md`):
- Goal 1 (PDFBox quality verdict) → C-EVAL-1.
- Goal 2 (live-LLM end-to-end baseline) → C-EVAL-2.
- Goal 3 (`inputModality` removal plan) → C-EVAL-3.
- Goal 4 (scenario-test framework) → C-EVAL-4.
- Goal 5 (beads-ready ticket descriptions) → ticket pass; not a component.
- Goal 6 (testing-strategy doc) → C-EVAL-5.

---

## C-EVAL-1 — PDFBox text-extraction validation (DONE)

**Description.** Validate PDFBox 3.0.3 quality on the 23 sample PDFs and produce a written verdict the implementation agent can rely on.

**Status.** Complete. Artifact: `eval/pdfbox-check/REPORT.md`. Verdict: text-only path is viable, all 23 samples extracted cleanly.

**Requirements.**
- R-EVAL-1.1: A markdown report at `eval/pdfbox-check/REPORT.md` covers all 23 samples and gives an aggregate verdict ("viable" / "marginal" / "needs intervention"). **Met.**
- R-EVAL-1.2: Per-file row in the report names the source PDF, the extracted text path, the verdict, and a one-line note on any caveat. **Met.**
- R-EVAL-1.3: The harness uses the same PDFBox API surface as production (`Loader.loadPDF` + `PDFTextStripper`). **Met.**

**Dependencies.** None (foundation).

---

## C-EVAL-2 — Live-LLM end-to-end harness (DONE — distinct from C3.11)

**Description.** A Python-driven harness that exercises the full HTTP API against the running stack with the live Anthropic API on all 23 samples. Complements `./gradlew evalRun` (Java-side, two-call isolation, 12 samples) by adding an end-to-end signal.

**Status.** Code complete (`eval/harness/run.py`, `eval/harness/labels.yaml`, `eval/harness/README.md`). Awaiting first run from the user.

**Requirements.**
- R-EVAL-2.1: `eval/harness/run.py` uploads each labeled PDF via `POST /api/organizations/{orgId}/documents`, polls for `AWAITING_REVIEW`, captures the resulting `DocumentView`. **Met.**
- R-EVAL-2.2: Compares observed `detectedDocumentType` and `extractedFields` against `eval/harness/labels.yaml`; emits a per-run timestamped report. **Met.**
- R-EVAL-2.3: Skips cleanly when the stack is unreachable or the API key is absent. **Met.**
- R-EVAL-2.4: Read-only with respect to `problem-statement/`. **Met.**

**Dependencies.** None at planning time. At run time: stack running, API key in env.

**Distinction from `evalRun` (C3.11):**
- `evalRun` — Java; calls `LlmClassifier` and `LlmExtractor` directly; bypasses HTTP, PDFBox, orchestrator, workflow; 12 samples; isolates prompts.
- Python harness — drives the stack via HTTP; exercises every layer including PDFBox, orchestrator, workflow, persistence; 23 samples; end-to-end signal.

---

## C-EVAL-3 — Remove `inputModality = PDF` (PLAN ONLY; hands off to implementation)

**Description.** The `inputModality` field exists across spec, schema, YAML, entity, view, and the LLM message-builder code, but the production call sites hardcode `TEXT` and pass `null` for PDF bytes. The PDF branch is dead. This component plans the surgical removal.

**Status.** Planning artifact only. Implementation by a downstream agent through beads.

**Requirements.**
- R-EVAL-3.1: After implementation, `grep -r "inputModality\|input_modality\|InputModality" backend/ src/main/resources/` returns zero results in `backend/src/main/`, `backend/src/test/` (excluding deliberately-deleted-file references in commit messages), `backend/src/main/resources/`, and `backend/src/test/resources/`.
- R-EVAL-3.2: `V1__init.sql` and the `c1-org-config.sql` fragment no longer declare `input_modality` column or the related CHECK constraint. The greenfield in-place edit is consistent with the project's prior baseline edits.
- R-EVAL-3.3: All nine production seed YAML files and all matching test seed YAML files have the `inputModality:` line removed. The loader continues to parse them without errors.
- R-EVAL-3.4: `DocTypeDefinition` no longer carries `inputModality`. `DocumentTypeEntity`, `DocumentTypeSchemaView`, `DocumentTypeCatalogImpl`, and `OrgConfigSeedWriter` no longer reference the field.
- R-EVAL-3.5: `MessageContentBuilder` no longer carries the nested `InputModality` enum, and `buildContentBlocks` no longer branches on modality. The PDF-only `DocumentBlockParam` import and Base64 helper are removed. The `pdfBytes` parameter is removed from the `build(...)` signature.
- R-EVAL-3.6: `LlmClassifier` and `ExtractRequestBuilder` call sites are updated to match the new builder signature — no `InputModality.TEXT` argument, no `null` `pdfBytes` argument.
- R-EVAL-3.7: All affected unit and integration tests build and pass after the field's removal. Tests that assert on the field's existence (`StageGuardConfigTest.inputModalityEnumExposesExactlyTextAndPdf`, `OrgConfigSeederIT.acS1_documentTypeWritesInputModalityAndFieldSchema`, `OrgConfigPersistenceFragmentIT` field-round-trip assertion, `SeedFixturesTest.inputModalityIsPdfForNestedArrayDocTypesAndTextForTheRest`) are deleted along with the field.
- R-EVAL-3.8: `c1-config-spec.md` and `c3-pipeline-spec.md` are updated to drop every reference to `InputModality` / `inputModality` / hybrid modality. C1-R2 wording, §3.2 record definition, §3.5 of c3-pipeline, AC-L6, and the file inventory are updated together.
- R-EVAL-3.9: `make test` passes; no test, lint, or format-check violation introduced; build is green.

**Dependencies.** None (precedes C-EVAL-4 only loosely — see §Integration). Fully independent of C-EVAL-1 and C-EVAL-2.

---

## C-EVAL-4 — Scenario-test framework (PLAN ONLY; hands off to implementation)

**Description.** A new test layer that boots the full Spring application via `Application.class`, runs Postgres in Testcontainers with real Flyway, executes real PDFBox text extraction, and stubs only `LlmClassifier` and `LlmExtractor`. Each scenario is a YAML fixture: real PDF input, canned classification result, canned extraction result, and a set of end-state assertions on `Document` + `WorkflowInstance` + emitted SSE events. Runs inside `make test` (the fast gate). Requires no API key.

**Status.** Planning artifact only. Implementation by a downstream agent through beads.

**Requirements.**

Wiring & determinism:
- R-EVAL-4.1: A Spring profile `scenario` activates a `@TestConfiguration` (`ScenarioStubConfig`) that supplies `@Bean @Primary` overrides for `LlmClassifier` and `LlmExtractor`. The overrides read the active scenario fixture and return canned results — no calls to `LlmCallExecutor` or the Anthropic SDK are made.
- R-EVAL-4.2: The base test class (`AbstractScenarioIT`) sets `@SpringBootTest(classes = Application.class, webEnvironment = RANDOM_PORT)` plus `@ActiveProfiles("scenario")` and `@Testcontainers`. Postgres runs as `postgres:16-alpine`; Flyway runs `V1__init.sql` (no `withInitScripts(...)` overlay).
- R-EVAL-4.3: Each test class extending `AbstractScenarioIT` works with a `@DynamicPropertySource` block that wires Postgres URL, sets `docflow.config.seed-on-boot=true`, sets `docflow.config.seed-resource-path=classpath:seed/`, and points `docflow.storage.storage-root` at a `@TempDir`.
- R-EVAL-4.4: When the harness runs, no environment variable named `ANTHROPIC_API_KEY` is read by the stubbed seams. The `LlmCallAuditWriter` continues to receive audit rows — the stubs call it on every invocation so existing audit invariants are preserved.

Fixture format & loader:
- R-EVAL-4.5: Each scenario fixture lives at `backend/src/test/resources/scenarios/<scenario-id>.yaml` with the schema specified in `05-specs/scenario-framework.md`. Required keys: `scenarioId`, `inputPdf` (path under `problem-statement/samples/`), `organizationId`, `classification` (`docType` or `error`), `extraction` (`fields` map or `error`), `expectedEndState` (assertions block).
- R-EVAL-4.6: A `ScenarioFixtureLoader` parses fixtures using Jackson YAML with strict unknown-property rejection (`FAIL_ON_UNKNOWN_PROPERTIES = true`). A malformed fixture fails the test at load time with a clear message naming the file and the offending key.
- R-EVAL-4.7: The loader resolves `inputPdf` against `problem-statement/samples/`. Missing files fail the test with the resolved absolute path.

Coverage (12 scenarios):
- R-EVAL-4.8: All 12 scenarios listed in `05-specs/scenario-framework.md` §3 have fixtures and matching test methods. Each scenario asserts the event sequence, the persisted `Document` row state, and the persisted `WorkflowInstance` row state. Origin-restoration scenarios additionally assert the `workflow_origin_stage` column transitions.

Build integration:
- R-EVAL-4.9: The scenario tests run as part of `./gradlew check` (and therefore `make test`). They do not require `ANTHROPIC_API_KEY`. They are excluded from `make e2e` (Playwright stays as-is) and `make eval` (`evalRun` stays as-is).
- R-EVAL-4.10: A scenario test completes deterministically under the Stop-hook timeout. No flaky waits — every Awaitility timeout is bounded (30 s upload-to-review, 5 s for synchronous transitions); event recorders are ordered.

Spec edits:
- R-EVAL-4.11: `c3-pipeline-spec.md` C3-R12 verification clause is updated: append "live-API integration coverage is limited to a single happy-path smoke; comprehensive HTTP-seam coverage with stubbed LLM seams is delegated to the scenario suite (C3-R17)."
- R-EVAL-4.12: `c5-api-spec.md` §6 prose update: "the only HTTP-seam integration test" becomes "the only **live-API** HTTP-seam integration test."
- R-EVAL-4.13: Five new R-tags are added to the existing specs: C3-R17, C3-R18, C4-R14, C5-R10, C7-R15. (Detail in `05-specs/scenario-framework.md` §5.)

**Dependencies.**
- C-EVAL-3 (`inputModality` removal): preferred to land first so the scenario harness does not have to declare the dead field anywhere. Not a hard blocker — could land in either order; if scenario tests land first, they include `inputModality` in their seed copies and that wash out when C-EVAL-3 lands. **Recommended order: C-EVAL-3 first, then C-EVAL-4.**
- C-EVAL-1: PDFBox quality verdict justifies the design choice (real PDFBox + stubbed LLM means scenarios drive real text extraction). Not a build dependency, only a justification.

---

## C-EVAL-5 — Testing-strategy documentation (PLAN ONLY; reusable for C7.12 README)

**Description.** A short prose document describing the four test layers: unit, `evalRun`, Python harness, scenario suite. For each layer: what it tests, what it does not test, when it runs (CI / on-demand / Stop hook), what it costs (API key needed? wall-clock time? infra needed?). Reusable verbatim or near-verbatim by the open `df-9c2.12 — C7.12 README` ticket.

**Status.** Planning artifact only. The doc itself is part of the change-spec output (`05-specs/testing-strategy.md`).

**Requirements.**
- R-EVAL-5.1: The doc lists the four layers in the order: unit → scenario → live smoke → eval rigs.
- R-EVAL-5.2: Each layer entry covers: scope (one paragraph), how to run, when it runs, requirements (API key? Postgres? running stack?), expected duration.
- R-EVAL-5.3: A "When to add to which layer" guide answers the questions: "I want to test a workflow guard / a new error code / a new prompt / a new validation rule — where does that test go?"
- R-EVAL-5.4: The doc identifies what is **not** tested by any layer (Playwright covers the frontend; load and stress are out of scope), so a reader knows the boundary.
- R-EVAL-5.5: Format and tone match the existing kerf substrate — markdown, descriptive prose, no emoji.

**Dependencies.** Logically depends on C-EVAL-3 (so the doc isn't out of date about a removed field) and C-EVAL-4 (so the scenario layer is real before being described). Authoring can happen in either order; the README slot at `C7.12` cannot land until both are implemented.

---

## Component dependency DAG

```
C-EVAL-1 (done) ──┐
                   │
C-EVAL-2 (done) ──┤
                   ├──► (informs design)
                   │
C-EVAL-3 ─────────┴──► C-EVAL-4 ──► C-EVAL-5
```

C-EVAL-3 and C-EVAL-4 are independent at the technical level (no shared interface, no shared data); the ordering is a hygiene preference. C-EVAL-5 is documentation that summarizes the others.

No cycles. All five fit cleanly into the seven-component cap; none require splitting.

---

## Interface summary

| Boundary | Producer | Consumer | Contract |
|---|---|---|---|
| `eval/pdfbox-check/REPORT.md` | C-EVAL-1 | C-EVAL-4 | Verdict that PDFBox is viable on real samples — justifies "scenario tests run real PDFBox" choice. Read-only doc. |
| `eval/harness/run.py` | C-EVAL-2 | C-EVAL-5 | A worked example of HTTP-driven end-to-end testing — referenced by the strategy doc. |
| `inputModality` field | (removed by C-EVAL-3) | (no consumer after removal) | Pre-removal: `DocTypeDefinition.inputModality()` reaches `DocumentTypeSchemaView.inputModality()` via `DocumentTypeCatalogImpl`. Post-removal: deleted everywhere. |
| `LlmClassifier` / `LlmExtractor` interfaces | (existing production code) | C-EVAL-4 | Stable Java method signatures (`classify(...)`, `extractFields(...)`, `extract(...)`); the scenario stubs implement the same interfaces and return canned results from the active fixture. |
| `ScenarioFixture` YAML schema | C-EVAL-4 fixtures | `ScenarioFixtureLoader` | Strict YAML; rejected on unknown properties; documented in `05-specs/scenario-framework.md`. |
| `make test` exit code | `./gradlew check` (incl. scenario tests) | CI, Stop hook | Non-zero on any failure. The four-layer separation is preserved: scenario tests are inside `make test`; `evalRun` and the Python harness are not. |
| `c3-pipeline-spec.md` and `c5-api-spec.md` | C-EVAL-4 spec edits | parent kerf substrate | Two precise prose edits + five new R-tags. |
