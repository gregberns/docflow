# Tasks — `evals`

Implementation task list for the consolidated `evals` work. Tasks are derived from `SPEC.md`, `06-integration.md`, and the component specs in `05-specs/`. Each task becomes a beads ticket.

Ordering follows `06-integration.md` §2: C-EVAL-3 first (cleans the substrate), then C-EVAL-4 (additive scenario suite), then C-EVAL-5 (doc). Within C-EVAL-4 the harness wiring lands first; scenarios accrue.

The bug bead `df-rar` (Backend fails to boot — AppConfig.Storage bean missing after C7.4) is referenced as a hard dependency for any task that boots the full Spring application via `Application.class` (i.e., every scenario task). Until `df-rar` is resolved, scenario tests cannot run green.

---

## Coverage map

| Spec section | Task(s) |
|---|---|
| C-EVAL-3 §Schema, §Production code, §Seed YAML, §SQL fixtures, §Tests, §Spec substrate | T1 |
| C-EVAL-4 §Architecture, §Profile/bean wiring, §Fixture YAML schema, §Directory layout, harness files | T2 |
| C-EVAL-4 §The 12 scenarios — #1, #2, #3 | T3 |
| C-EVAL-4 §The 12 scenarios — #4, #9, #10, #11 | T4 |
| C-EVAL-4 §The 12 scenarios — #6, #7, #8, #12 | T5 |
| C-EVAL-4 §The 12 scenarios — #5 (concurrent SSE) | T6 |
| C-EVAL-4 §Spec edits (c3 R12+R17+R18, c4 R14, c5 §6+R10, c7 R15) | T7 |
| C-EVAL-5 §Document outline, §Acceptance criteria | T8 |

---

## T1 — Remove `inputModality` from spec, schema, code, YAML, tests

**Component:** c-eval-3

**What.** Remove every reference to `inputModality` / `input_modality` / `InputModality` from the Java code, JPA entity, JSON view, V1 baseline migration + fragment, all production and test seed YAMLs, the two SQL listener fixtures, and the affected unit/IT tests. Land schema, code, YAML, SQL, tests, and `c1-config-spec.md` / `c3-pipeline-spec.md` substrate edits in one tightly-coupled change so the build is green at every commit.

**Why.** The PDF code branch in `MessageContentBuilder` is dead — `LlmClassifier` and `ExtractRequestBuilder` already hardcode text-only and pass `null` for PDF bytes. The field is dead config that misleads readers and pads the schema. Removing it unblocks a clean substrate for the scenario fixtures (T3–T6) so they don't ship with a dead key.

**Deliverables.**
- Edit `backend/src/main/resources/db/migration/V1__init.sql` and `backend/src/main/resources/db/migration/fragments/c1-org-config.sql` — drop `input_modality` column + `ck_document_types_input_modality` CHECK.
- Delete `backend/src/main/java/com/docflow/config/org/InputModality.java`.
- Edit `DocTypeDefinition.java`, `DocumentTypeEntity.java`, `DocumentTypeSchemaView.java`, `DocumentTypeCatalogImpl.java`, `OrgConfigSeedWriter.java`, `MessageContentBuilder.java`, `LlmClassifier.java`, `ExtractRequestBuilder.java` per `05-specs/c-eval-3-input-modality-removal-spec.md`.
- Drop `inputModality:` line from 9 production seed YAMLs + 10 test seed YAMLs + 1 missing-required loader fixture.
- Drop `input_modality` column + value from `processing-completed-listener-seed.sql` and `retype-flow-listener-seed.sql`.
- Delete tests `inputModalityEnumExposesExactlyTextAndPdf`, `acS1_documentTypeWritesInputModalityAndFieldSchema`, `inputModalityIsPdfForNestedArrayDocTypesAndTextForTheRest`, and the `loaded.getInputModality()` assertion in `OrgConfigPersistenceFragmentIT`.
- Edit `ConfigValidatorTest`, `MessageContentBuilderTest`, `LlmClassifierTest`, `LlmExtractorTest`, `LlmCallExecutorTest` to drop the `InputModality.TEXT` argument.
- Edit `.kerf/project/docflow/05-specs/c1-config-spec.md` (seven line-level edits per spec) and `.kerf/project/docflow/05-specs/c3-pipeline-spec.md` (research summary L37 + §3.5 L149–L155).

**Acceptance criteria.**
1. `cd /Users/gb/github/basata/backend && ./gradlew check` exits 0.
2. `grep -rn "inputModality\|input_modality\|InputModality" /Users/gb/github/basata/backend/src/main/ /Users/gb/github/basata/backend/src/test/ /Users/gb/github/basata/backend/src/main/resources/` returns no matches.
3. `grep -rn "inputModality\|input_modality\|InputModality" /Users/gb/github/basata/.kerf/project/docflow/05-specs/c1-config-spec.md /Users/gb/github/basata/.kerf/project/docflow/05-specs/c3-pipeline-spec.md` returns no matches.
4. `OrgConfigPersistenceFragmentIT` and `OrgConfigSeederIT` both pass after the change.
5. `MessageContentBuilder.build(...)` has the 5-parameter signature `(modelId, systemPrompt, toolSchema, maxTokens, text)`.

**Estimated hours.** 2h.

**Dependencies.** None.

---

## T2 — Scenario harness base wiring (AbstractScenarioIT, ScenarioStubConfig, ScenarioContext, fixture loader + tests)

**Component:** c-eval-4

**What.** Build the scenario-test framework substrate: the abstract base class with Spring + Testcontainers + profile wiring, the `@TestConfiguration` that provides `@Primary` stubs for `LlmClassifier` and `LlmExtractor`, the singleton `ScenarioContext`, the `ScenarioFixture` record + Jackson YAML loader with strict-mode parsing, the recorder bean, and an SSE client helper. Ship the loader unit-test class exercising the strict-mode contract. No fixtures land in this ticket — `ScenarioRunnerIT` is empty until T3.

**Why.** The whole scenario suite (T3–T6) rests on this scaffolding. Landing it independently keeps the harness commits reviewable and lets fixtures accrue without re-touching framework code. It also de-risks the core architectural decisions (stub seam at `LlmClassifier` / `LlmExtractor`, profile name `scenario`, audit-writer invariant) before any scenario asserts on behavior.

**Deliverables.**
- `backend/src/test/java/com/docflow/scenario/AbstractScenarioIT.java` — `@SpringBootTest(classes = Application.class, RANDOM_PORT)`, `@ActiveProfiles("scenario")`, `@Testcontainers`, `@Import(ScenarioStubConfig.class)`, `@Execution(SAME_THREAD)`.
- `backend/src/test/java/com/docflow/scenario/ScenarioStubConfig.java` — `@TestConfiguration @Profile("scenario")`; `@Bean @Primary` overrides for `LlmClassifier` and `LlmExtractor`; `@Bean ScenarioContext`.
- `backend/src/test/java/com/docflow/scenario/ScenarioContext.java` — singleton mutable carrier; `setActive(ScenarioFixture)` and `setActive(List<ScenarioFixture>)`; rawText-based fixture matching for the multi-input case.
- `backend/src/test/java/com/docflow/scenario/ScenarioFixture.java` — record matching the YAML schema (scenarioId, description, inputPdf or inputs[], organizationId, classification.{docType|error}, extraction.{fields|error}, actions[], expectedEndState).
- `backend/src/test/java/com/docflow/scenario/ScenarioFixtureLoader.java` — Jackson YAML loader, `FAIL_ON_UNKNOWN_PROPERTIES = true`, validates: missing `inputPdf` and `inputs` → fail; both → fail; `classification` with neither `docType` nor `error` → fail; unknown error name → fail.
- `backend/src/test/java/com/docflow/scenario/ScenarioLlmClassifierStub.java` — extends `LlmClassifier`; consults `ScenarioContext`; calls `LlmCallAuditWriter.insert(...)`.
- `backend/src/test/java/com/docflow/scenario/ScenarioLlmExtractorStub.java` — extends `LlmExtractor`; consults `ScenarioContext`; calls `LlmCallAuditWriter.insert(...)`; honors retry-on-`LlmSchemaViolation` semantics.
- `backend/src/test/java/com/docflow/scenario/ScenarioRecorder.java` — `@EventListener` capturing `DocumentStateChanged` + `ProcessingStepChanged`, grouped by `documentId`.
- `backend/src/test/java/com/docflow/scenario/ScenarioAssertions.java` — fluent helpers for Document, WorkflowInstance, and event subset-match assertions.
- `backend/src/test/java/com/docflow/scenario/ScenarioSseClient.java` — `java.net.http.HttpClient` + `BodyHandlers.ofLines()` SSE helper.
- `backend/src/test/java/com/docflow/scenario/ScenarioRunnerIT.java` — parameterized test class skeleton with `@MethodSource` returning fixture stream (empty in this ticket; T3+ add fixtures).
- `backend/src/test/java/com/docflow/scenario/ScenarioFixtureLoaderTest.java` — unit tests asserting strict-mode rejections (unknown property; missing input; both inputs; missing classification; unknown error name).

**Acceptance criteria.**
1. `cd /Users/gb/github/basata/backend && ./gradlew check` passes; new files compile; `ScenarioFixtureLoaderTest` passes its 5+ rejection cases.
2. The application context boots under the `scenario` profile (verified by an empty `ScenarioRunnerIT` lifecycle test that just spins up the context).
3. `LlmCallAuditWriter` is wired into both stubs; a unit-level invocation of the stub writes one row to `llm_call_audit` (verified via a small smoke test or assertion in `ScenarioRunnerIT`).
4. Loader rejects fixtures with unknown YAML keys, conflicting inputs, or missing required fields with a clear error message.

**Estimated hours.** 3h.

**Dependencies.** T1 (clean substrate so fixtures don't carry dead `inputModality:` lines), `df-rar` (backend boot bug — scenario context cannot start until `AppConfig.Storage` bean wiring is fixed).

---

## T3 — Scenarios 01, 02, 03 (happy path, wrong-type, missing-required-field)

**Component:** c-eval-4

**What.** Author the first three scenario YAML fixtures and turn on the corresponding `ScenarioRunnerIT` parameterized cases. Scenario 01 is the green path (Pinnacle invoice → classify → extract → Review → two approvals → Filed). Scenario 02 returns a disallowed `docType` from the classifier stub and asserts production `LlmSchemaViolation` → orchestrator marks `FAILED`. Scenario 03 returns an extraction map missing a required field and asserts the schema-validation outcome.

> **Note on scenario 03.** Verify behavior against existing pipeline implementation. C3 spec §3.6 documents `LlmSchemaViolation` as the typed outcome → FAILED state. Default expectation in this scenario is FAILED (not flag-and-continue). If pipeline behavior diverges from the spec, surface that as a separate bug ticket and adjust the fixture's expected end state accordingly.

**Why.** These three are the highest-value scenarios — they validate the harness end-to-end on the green path, the production rejection-on-bad-classification path, and the schema-violation path. Landing them after the harness gives the harness its first real exercise and surfaces any wiring defects early.

**Deliverables.**
- `backend/src/test/resources/scenarios/01-happy-path-pinnacle-invoice.yaml`
- `backend/src/test/resources/scenarios/02-wrong-type-classification.yaml`
- `backend/src/test/resources/scenarios/03-missing-required-field.yaml`
- `ScenarioRunnerIT` `@MethodSource` updated to include these three fixtures.

**Acceptance criteria.**
1. `cd /Users/gb/github/basata/backend && ./gradlew test --tests "com.docflow.scenario.ScenarioRunnerIT"` passes 3 cases.
2. Scenario 02 verifies that `LlmClassifier`'s production validation throws (the stub returns a string; production code rejects).
3. Scenario 03's `expectedEndState` matches actual production behavior verified against `c3-pipeline-spec.md` §3.6 — default is `current_step = FAILED`. If a divergence is observed, a separate bug ticket is opened and the fixture is adjusted.
4. `LlmCallAudit` row count assertion holds for each scenario (one classify row + one extract row for green path; one classify row only for failed-classify scenario 02).

**Estimated hours.** 2h.

**Dependencies.** T2.

---

## T4 — Scenarios 04, 09, 10, 11 (retype paths + origin restoration)

**Component:** c-eval-4

**What.** Author four retype-flow scenarios. Scenario 04: retype with type change → re-extraction completes, `Document` updated. Scenario 09: retype with no type change → no-op (`LlmExtractor.extract` not called, origin restored). Scenario 10: retype `Resolve` triggers extractor, but stub throws `LlmSchemaViolation` → `reextractionStatus = FAILED`, original fields untouched, instance still flagged. Scenario 11: flag from Attorney Approval, resolve in Review without type change → returns to Attorney Approval (canonical origin-restoration test).

**Why.** Retype flows are the most error-prone branch in the workflow (dual paths: type-change vs. no-op; success vs. failure). These four scenarios fully exercise C4 spec retype semantics — the no-op path, the success path, the failure path, and the cross-stage origin-restoration path.

**Deliverables.**
- `backend/src/test/resources/scenarios/04-retype-extraction-completes.yaml`
- `backend/src/test/resources/scenarios/09-retype-no-op.yaml` — including the variant from Attorney Approval origin.
- `backend/src/test/resources/scenarios/10-retype-extraction-fails.yaml`
- `backend/src/test/resources/scenarios/11-flag-from-attorney-stage.yaml`
- `ScenarioRunnerIT` `@MethodSource` updated.

**Acceptance criteria.**
1. All four scenarios pass via `./gradlew test --tests "com.docflow.scenario.ScenarioRunnerIT"`.
2. Scenario 09 asserts via the stub's invocation counter that `LlmExtractor.extract` was not called.
3. Scenario 10 asserts `reextractionStatus = FAILED`, `Document.extractedFields` byte-for-byte equal to pre-retype state, `WorkflowInstance.workflow_origin_stage` retained, `currentStatus = FLAGGED`.
4. Scenario 11 asserts post-resolve state: `currentStage = stage-approval-attorney`, `currentStatus = AWAITING_APPROVAL`, `workflow_origin_stage = null`, `flag_comment = null`.

**Estimated hours.** 2h.

**Dependencies.** T2 (and T3 for harness validation).

---

## T5 — Scenarios 06, 07, 08, 12 (corrupt PDF, lien-waiver guards, terminal-state action)

**Component:** c-eval-4

**What.** Author four error-and-guard scenarios. Scenario 06: corrupt PDF → PDFBox throws → orchestrator FAILED, no Document materialized. Scenario 07: lien-waiver `waiverType: "conditional"` → routes to Project Manager Approval per C4 guard logic. Scenario 08: lien-waiver `waiverType: "unconditional"` → routes straight to Filed. Scenario 12: Approve action on a Filed document → 409 + `INVALID_ACTION` problem detail.

**Why.** These scenarios cover the system's defensive paths — PDF parser failure, business-rule guards, and HTTP error envelopes. All four are short-tail, single-shot validations that exercise distinct production-code branches.

**Deliverables.**
- `backend/src/test/resources/scenarios/_fixtures/corrupt.pdf` — a deliberately broken PDF (truncated bytes or random binary) with no possible PDFBox parse.
- `backend/src/test/resources/scenarios/06-corrupt-pdf.yaml`
- `backend/src/test/resources/scenarios/07-lien-waiver-conditional-guard.yaml`
- `backend/src/test/resources/scenarios/08-lien-waiver-unconditional-guard.yaml`
- `backend/src/test/resources/scenarios/12-action-on-filed-document.yaml`
- `ScenarioRunnerIT` `@MethodSource` updated.
- `ScenarioFixtureLoader` updated (if not done in T2) to allow paths under `_fixtures/`.

**Acceptance criteria.**
1. All four scenarios pass.
2. Scenario 06: no `Document` row, `ProcessingStepChanged { currentStep: FAILED }` event with non-empty `error`.
3. Scenarios 07 and 08: `currentStageId` and `currentStatus` match the routing per C4-R3 guard logic.
4. Scenario 12: HTTP response is 409, body matches RFC 7807 problem-detail shape with `INVALID_ACTION` code; document end-state unchanged.

**Estimated hours.** 2h.

**Dependencies.** T2 (and T3 for harness validation).

---

## T6 — Scenario 05 (concurrent uploads + SSE assertion)

**Component:** c-eval-4

**What.** Author the concurrent-uploads scenario. Open an SSE GET subscription for `riverside-bistro` in a background thread. Upload two distinct Riverside invoice PDFs in parallel. Both pipelines run; the stubs match by exact `rawText` content. Decode buffered SSE frames; assert each document's events appear (any inter-document order, but within-document order preserved). End state: two `Document` rows + two `WorkflowInstance` rows, both `AWAITING_REVIEW`. Bounded SSE read window (30 s) with frame-print on timeout.

**Why.** Concurrency is the single hardest correctness property to assert in a harness. Isolating it as its own ticket lets the test be written carefully and re-run repeatedly for flake (acceptance criterion: 10 successive passes). Bundling it with other scenarios risks masking flakes inside a 12-test sweep.

**Deliverables.**
- `backend/src/test/resources/scenarios/05-concurrent-uploads.yaml` — multi-input fixture with `inputs:` array.
- `ScenarioRunnerIT` `@MethodSource` updated.
- `ScenarioSseClient` extended (if needed beyond what T2 ships) to support buffered frame capture.

**Acceptance criteria.**
1. Scenario 05 passes 10 successive runs without flake (`./gradlew test --tests "com.docflow.scenario.ScenarioRunnerIT.scenario05_concurrentUploads"` invoked 10 times).
2. Both documents reach `AWAITING_REVIEW`.
3. SSE frames captured for each document; events asserted in subset-match per `documentId`.
4. SSE read timeout produces a clear failure with captured frames printed.

**Estimated hours.** 2h.

**Dependencies.** T2 (and T3 to confirm harness baseline before adding concurrency).

---

## T7 — Spec edits: c3-pipeline, c4-workflow, c5-api, c7-platform R-tags and prose

**Component:** c-eval-4 (docs)

**What.** Apply the four spec-substrate edits in `.kerf/project/docflow/05-specs/`:
- `c3-pipeline-spec.md`: append clause to C3-R12 verification cell; add C3-R17 (scenario tests in `make test`, no API key) and C3-R18 (fixture loader strict-mode).
- `c4-workflow-spec.md`: add C4-R14 (workflow scenarios cover the documented set).
- `c5-api-spec.md`: replace `the only HTTP-seam integration test` with `the only **live-API** HTTP-seam integration test` at L264; add C5-R10 (concurrent SSE).
- `c7-platform-spec.md`: add C7-R15 (scenario tests in fast gate; excluded from e2e and eval).

**Why.** The spec substrate is the agent-readable architecture record. Without these edits, the new scenario suite has no R-tag coverage, and future agents reading the substrate will not know that scenario tests are part of the contract. Doing it as a separate ticket avoids bundling a docs-only change with code commits.

**Deliverables.**
- Four edited files under `/Users/gb/github/basata/.kerf/project/docflow/05-specs/`.

**Acceptance criteria.**
1. Each new R-tag (C3-R17, C3-R18, C4-R14, C5-R10, C7-R15) has both a summary and a verification column populated.
2. The C3-R12 verification cell ends with `; comprehensive HTTP-seam coverage with stubbed LLM seams is delegated to the scenario suite (C3-R17).`
3. `c5-api-spec.md` L264 reads `the only **live-API** HTTP-seam integration test`.
4. `grep -n "C3-R17\|C3-R18\|C4-R14\|C5-R10\|C7-R15" /Users/gb/github/basata/.kerf/project/docflow/05-specs/*.md` returns the new rows.

**Estimated hours.** 1h.

**Dependencies.** None (can land any time after T2; no code coupling).

---

## T8 — Testing-strategy doc

**Component:** c-eval-5

**What.** The testing-strategy doc already exists in this kerf substrate at `/Users/gb/github/basata/.kerf/project/evals/05-specs/testing-strategy.md`. Verify it satisfies the C-EVAL-5 acceptance criteria (seven section headings, when-to-add-to-which-layer table, no emoji, readable in <10 minutes), and tag it as ready-to-consume by the `df-9c2.12` README ticket.

**Why.** The doc is descriptive — it documents the four layers as built. It is consumed verbatim or near-verbatim by the C7.12 README ticket. Confirming its readiness here closes out the C-EVAL-5 component cleanly.

**Deliverables.**
- Verify `/Users/gb/github/basata/.kerf/project/evals/05-specs/testing-strategy.md` exists and has the seven `## ` headings: Layer 1, Layer 2, Layer 3, Layer 4, What is not tested, When to add to which layer (plus the title `# Testing strategy` is a single `#`).
- If the doc is missing any of the seven sections, fill in the gap.
- No code or other artifact changes.

**Acceptance criteria.**
1. `grep -E '^## ' /Users/gb/github/basata/.kerf/project/evals/05-specs/testing-strategy.md` returns at least 6 lines (one `## ` per layer + What-is-not-tested + When-to-add table = 6 sections under the H1).
2. The "When to add to which layer" table contains at least the seven rows shown in the C-EVAL-5 spec outline.
3. No emoji.
4. The doc is referenced from the C7.12 README ticket (`df-9c2.12`) as the source for the README's testing section.

**Estimated hours.** 0.5h.

**Dependencies.** None.

---

## Dependency graph

```
T1 ──► T2 ──► T3 ──► T4
                │      
                ├───► T5
                │
                └───► T6

T7 (independent of T2..T6 code; depends on T1 only for substrate consistency)

T8 (independent — testing-strategy.md already drafted)

df-rar (existing bug) ──► T2 (and transitively T3..T6)
```

T1 is the sole serial gate. T2 is the harness gate for all scenario-fixture tasks (T3, T4, T5, T6). T3 lands first within the scenario family because it gives the harness its first real exercise — T4, T5, and T6 should not start until T3 is green. T7 (spec edits) and T8 (testing-strategy doc) are independent of the code path and can land any time.

`df-rar` blocks T2 because the scenario suite boots `Application.class`; it cannot start while `AppConfig.Storage` bean wiring is broken.

## Parallelization opportunities

- T7 and T8 can land in parallel with any other task.
- T4, T5, and T6 can land in parallel once T3 is green (each adds independent fixtures; the only shared file is `ScenarioRunnerIT.java`'s `@MethodSource` registration, which is mechanical merge-resolution).

## Total estimate

T1 (2h) + T2 (3h) + T3 (2h) + T4 (2h) + T5 (2h) + T6 (2h) + T7 (1h) + T8 (0.5h) = ~14.5 hours.
