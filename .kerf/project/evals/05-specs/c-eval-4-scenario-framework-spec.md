# Change Spec — C-EVAL-4 (Scenario-test framework)

A new test layer that boots the full Spring application, runs Postgres in Testcontainers with real Flyway, executes real PDFBox text extraction, and stubs only `LlmClassifier` and `LlmExtractor`. Each scenario is a YAML fixture with a real PDF input, canned classification + extraction values, and a set of end-state assertions. Runs inside `make test`. No API key required. Scope: 12 scenarios.

---

## Requirements (from `03-components.md`)

R-EVAL-4.1 through R-EVAL-4.13 (verbatim).

## Research summary (from `04-research/c-eval-4/findings.md`)

- `@Bean @Primary` overrides at the `LlmClassifier` / `LlmExtractor` seam are the right boundary. The lower seam (`LlmCallExecutor`) is rejected because it couples fixtures to SDK message shape.
- The stubs subclass the production class and override the public methods to consult a `ScenarioContext` bean for the active fixture's canned values. Subclassing avoids refactoring production code into interfaces.
- `RetypeFlowIT` already demonstrates `@Bean LlmExtractor` overrides; the pattern is proven.
- Real Flyway against a Testcontainers Postgres (no `withInitScripts` overlay).
- Awaitility with bounded waits; recorder beans for events.
- Audit-writer invariant preserved by the stubs.
- Concurrent-upload scenario (#5) uses an SSE GET in a separate thread; the stub matches by exact `rawText` content (different PDFs → different text → unambiguous).

## Approach

### Architecture

```
Test class extends AbstractScenarioIT
  └── @SpringBootTest(classes = Application.class, RANDOM_PORT)
  └── @ActiveProfiles("scenario")
  └── @Testcontainers (Postgres)
  └── @Import(ScenarioStubConfig.class)

Test method:
  1. Load fixture YAML  → ScenarioFixture
  2. ScenarioContext.setActive(fixture)
  3. Upload real PDF via TestRestTemplate
  4. Awaitility: poll dashboard for AWAITING_REVIEW (or expected end state)
  5. Assert Document row, WorkflowInstance row, recorded events match fixture.expectedEndState
```

The application boots normally. PDFBox extracts real text. `LlmClassifier` is replaced by `ScenarioLlmClassifierStub`, which reads the canned `classification` from the active fixture and returns the matching `ClassifyResult` (or throws the named exception). `LlmExtractor` is replaced by `ScenarioLlmExtractorStub`, which does the same for `extractFields(...)` and (for the retype path) `extract(...)`.

The audit-writer invariant is preserved: both stubs inject and call `LlmCallAuditWriter` exactly the way the production classes do.

### Profile and bean wiring

Profile name: `scenario`. Activated via `@ActiveProfiles("scenario")` on `AbstractScenarioIT`. `ScenarioStubConfig` is `@TestConfiguration` annotated `@Profile("scenario")`. It declares:

```java
@Bean @Primary LlmClassifier llmClassifier(...) { return new ScenarioLlmClassifierStub(...); }
@Bean @Primary LlmExtractor  llmExtractor(...)  { return new ScenarioLlmExtractorStub(...); }
@Bean ScenarioContext scenarioContext()         { return new ScenarioContext(); }
```

`ScenarioContext` is a singleton bean carrying the active fixture(s). Tests call `scenarioContext.setActive(fixture)` in `@BeforeEach`. For the concurrent-uploads scenario, `setActive(List<ScenarioFixture>)` is the multi-fixture variant — the stubs match the incoming `rawText` against each fixture's `inputPdf` extracted text (computed once per fixture, on first match attempt, and memoized).

### Fixture YAML schema

```yaml
scenarioId: happy-path-pinnacle-invoice
description: Happy path. Classify -> extract -> Review -> Approve -> Filed.
organizationId: pinnacle-legal
inputPdf: pinnacle-legal/invoices/dewey_cheatham_howe_feb_2024.pdf

# One of: classification.docType OR classification.error
classification:
  docType: invoice           # required iff no error
  # OR error: SCHEMA_VIOLATION   # one of SCHEMA_VIOLATION, UNAVAILABLE, TIMEOUT, PROTOCOL_ERROR

# One of: extraction.fields OR extraction.error
extraction:
  fields:
    vendor: "Dewey, Cheatham & Howe LLP"
    invoiceNumber: "DCH-2024-0231"
    invoiceDate: "2024-02-15"
    dueDate: "2024-03-16"
    subtotal: "12750.00"
    tax: "0.00"
    totalAmount: "12750.00"
    paymentTerms: "Net 30"
    lineItems:
      - description: "Legal services - January 2024"
        quantity: "1"
        unitPrice: "12750.00"
        total: "12750.00"
  # OR error: SCHEMA_VIOLATION

actions:
  # Optional: post-upload actions to apply, in order, before assertions
  - type: APPROVE
    expectedStatus: 200

expectedEndState:
  document:
    detectedDocumentType: invoice
    reextractionStatus: NONE
    extractedFieldsContains:
      vendor: "Dewey, Cheatham & Howe LLP"
      totalAmount: "12750.00"
  workflowInstance:
    currentStageId: stage-approval-attorney
    currentStatus: AWAITING_APPROVAL
    workflowOriginStage: null
    flagComment: null
  events:
    # Order matters; subset match (events MAY include other events not listed)
    - type: ProcessingStepChanged
      currentStep: TEXT_EXTRACTING
    - type: ProcessingStepChanged
      currentStep: CLASSIFYING
    - type: ProcessingStepChanged
      currentStep: EXTRACTING
    - type: DocumentStateChanged
      currentStage: stage-review
      currentStatus: AWAITING_REVIEW
    - type: DocumentStateChanged
      action: APPROVE
      currentStage: stage-approval-attorney
      currentStatus: AWAITING_APPROVAL
```

For multi-input scenarios (concurrent uploads):

```yaml
scenarioId: concurrent-uploads
inputs:
  - inputPdf: ...
    organizationId: ...
    classification: { docType: invoice }
    extraction: { fields: {...} }
  - inputPdf: ...
    organizationId: ...
    classification: { docType: receipt }
    extraction: { fields: {...} }
expectedEndState: { ... }
```

Loader rejects unknown properties (Jackson `FAIL_ON_UNKNOWN_PROPERTIES = true`). Loader rejects fixtures with both `inputPdf` and `inputs`. Loader rejects fixtures with neither `classification.docType` nor `classification.error`.

### Directory layout

```
backend/src/test/resources/scenarios/
  ├── 01-happy-path-pinnacle-invoice.yaml
  ├── 02-wrong-type-classification.yaml
  ├── 03-missing-required-field.yaml
  ├── 04-retype-extraction-completes.yaml
  ├── 05-concurrent-uploads.yaml
  ├── 06-corrupt-pdf.yaml
  ├── 07-lien-waiver-conditional-guard.yaml
  ├── 08-lien-waiver-unconditional-guard.yaml
  ├── 09-retype-no-op.yaml
  ├── 10-retype-extraction-fails.yaml
  ├── 11-flag-from-attorney-stage.yaml
  └── 12-action-on-filed-document.yaml
```

```
backend/src/test/java/com/docflow/scenario/
  ├── AbstractScenarioIT.java           — base class, Spring/Postgres wiring
  ├── ScenarioStubConfig.java           — @TestConfiguration, @Bean @Primary stubs
  ├── ScenarioContext.java              — request-/test-scoped fixture carrier
  ├── ScenarioFixture.java              — record matching the YAML schema
  ├── ScenarioFixtureLoader.java        — Jackson-based loader, strict mode
  ├── ScenarioLlmClassifierStub.java    — extends LlmClassifier; consults context
  ├── ScenarioLlmExtractorStub.java     — extends LlmExtractor; consults context
  ├── ScenarioAssertions.java           — fluent assertion helpers (Document, WorkflowInstance, events)
  ├── ScenarioRecorder.java             — captures DocumentStateChanged + ProcessingStepChanged
  ├── ScenarioSseClient.java            — SSE GET helper (only used in scenario 05)
  └── ScenarioRunnerIT.java             — parameterized test class iterating fixtures
```

`ScenarioRunnerIT` uses `@ParameterizedTest` with a `@MethodSource` returning the list of fixtures. One JVM context boot, twelve test invocations.

### The 12 scenarios

#### 1. Happy path — Pinnacle invoice classifies, extracts, advances to Filed

Upload `dewey_cheatham_howe_feb_2024.pdf`. Stub returns `docType: invoice` and a full set of canned fields. Pipeline materializes Document + WorkflowInstance (`AWAITING_REVIEW`). POST `Approve`; assert advance to `AWAITING_APPROVAL` (Attorney). POST `Approve` again; assert `FILED`. Verifies the green path through PDFBox + classify + extract + Review + two approvals.

#### 2. Wrong-type classification

Upload a Riverside invoice PDF, but the stub returns `docType: lien-waiver` (not in the org's allowed enum). Production code (`LlmClassifier.classify`) raises `LlmSchemaViolation` because `'lien-waiver'` is not in `getAllowedDocTypes("riverside-bistro")`. Pipeline catches → orchestrator marks `current_step = FAILED`, emits `ProcessingStepChanged { currentStep: FAILED }`. Assert: `ProcessingDocument.currentStep = FAILED`, `last_error` populated; no `Document` row materialized; no `WorkflowInstance` row materialized. The stub achieves this by returning the not-allowed string from `classify(...)`; the production validation in `LlmClassifier` does the throw. (The stub does not throw directly here — the production rejection logic is the thing being verified.)

#### 3. Missing required field

Upload a Pinnacle invoice. Stub returns `docType: invoice` and an `extraction.fields` map missing `paymentTerms` (a required field). Pipeline completes (the stub does not validate the extraction shape — the production extract step does the schema-validation). Production should mark this as a `LlmSchemaViolation` if the schema-validation runs in the extract step. Verify: depending on whether the production code validates the schema before or after marshalling (per `c3-pipeline-spec.md` §3.6 — `Validate tool_use.input against the schema`), this scenario is the regression test for that path. End state: orchestrator marks `current_step = FAILED`. (If the production behavior is to materialize the document with missing fields and let the user resolve, this scenario asserts that flow instead. The implementation agent reads the c3 pipeline spec and confirms the expected behavior; the fixture's `expectedEndState` reflects whichever is correct.)

#### 4. Retype `Resolve` with type change → re-extraction completes

Upload a Pinnacle invoice; pipeline reaches `AWAITING_REVIEW`. POST `Flag` from Review (yes — Review allows Flag in the c4 spec). POST `Resolve` with `newDocTypeId = expense-report`. Stub's `LlmExtractor.extract(documentId, "expense-report")` returns canned expense-report fields; emits `ExtractionCompleted`. Assert: `Document.detectedDocumentType = "expense-report"`, `extractedFields` updated, `reextractionStatus = NONE`, `WorkflowInstance.workflowOriginStage` cleared. (Per c4 spec, after retype with type change the workflow stays at Review.)

#### 5. Two concurrent uploads → both flow correctly

Open SSE GET for `riverside-bistro` in a thread. Upload `pdf-1` and `pdf-2` (both Riverside invoices) concurrently. Both pipelines run; stubs match each by `rawText` content. Both reach `AWAITING_REVIEW`. Decode buffered SSE frames; assert each document's events appear (in any order across the two streams of events, but each document's own events appear in the documented order). End state: two `Document` rows + two `WorkflowInstance` rows, both `AWAITING_REVIEW`.

#### 6. Corrupt PDF

Upload a fixture file at `backend/src/test/resources/scenarios/_fixtures/corrupt.pdf` — a deliberately broken PDF (e.g., truncated to 8 bytes, or random binary). PDFBox `Loader.loadPDF` throws an `IOException`. Orchestrator catches → `current_step = FAILED`, `last_error` populated, emits `ProcessingStepChanged`. Assert no Document materialized, error message contains a recognizable PDFBox error indicator. The fixture's `inputPdf` field references the corrupt file under `_fixtures/` (the loader allows paths under that subtree as well as under `problem-statement/samples/`).

#### 7. Lien-waiver guard — conditional → Project Manager Approval

Upload an Ironworks lien-waiver PDF. Stub returns `docType: lien-waiver` and `extraction.fields` with `waiverType: "conditional"`. Pipeline materializes; `WorkflowInstance` starts at Review. POST `Approve` from Review. The c4 guard logic (per `c4-workflow-spec.md` C4-R3) evaluates `waiverType == "conditional"` → routes to Project Manager Approval. Assert `currentStageId` is the Project Manager stage, `currentStatus = AWAITING_APPROVAL`.

#### 8. Lien-waiver guard — unconditional → Filed

Same as #7 but `waiverType: "unconditional"`. Guard routes straight to Filed. Assert `currentStageId` is the Filed terminal stage, `currentStatus = FILED`.

#### 9. Retype `Resolve` no type change → no-op resolve

Upload a Pinnacle invoice; reach `AWAITING_REVIEW`. POST `Flag` (with comment). POST `Resolve` with the same `newDocTypeId = invoice`. Per c4 spec C4-R6: no type change means `clearFlag` and return to `workflowOriginStage`. Assert: `LlmExtractor.extract` was **not** called (verified by recording stub invocations — the stub has a counter), `Document.detectedDocumentType` unchanged, `WorkflowInstance.workflowOriginStage = null` (cleared), `currentStageId` matches origin (or stays Review if origin was Review — the test sets origin to Review so the post state is also Review with no flag).

Variant: set origin to a non-Review stage by the flag-from-attorney path. Specifically: upload → reach Review → Approve → reach Attorney Approval → Flag (origin = Attorney Approval) → Resolve with no type change → assert returns to Attorney Approval.

#### 10. `ExtractionFailed` after retype

Upload a Pinnacle invoice; reach `AWAITING_REVIEW`. POST `Flag`. POST `Resolve` with `newDocTypeId = receipt`. Stub's `LlmExtractor.extract(...)` throws `LlmSchemaViolation` (canned via `extraction.error: SCHEMA_VIOLATION` in the fixture). Production code catches → `Document.reextractionStatus = FAILED`, emits `ExtractionFailed`. Assert: `reextractionStatus = FAILED`, `Document.extractedFields` unchanged from the original, `WorkflowInstance` still flagged (`workflow_origin_stage` retained, `currentStatus = FLAGGED`).

#### 11. Flag from Attorney Approval, resolve in Review without type change

Upload a Pinnacle invoice; reach `AWAITING_REVIEW`. POST `Approve` → reach `AWAITING_APPROVAL` (Attorney). POST `Flag` with comment "needs receipt". `WorkflowInstance.currentStage = stage-review`, `workflow_origin_stage = stage-approval-attorney`, `currentStatus = FLAGGED`. POST `Resolve` with same `newDocTypeId = invoice`. No type change → restore origin: `currentStage = stage-approval-attorney`, `currentStatus = AWAITING_APPROVAL`, `workflow_origin_stage = null`, `flag_comment = null`. This is the canonical origin-restoration test.

#### 12. Approve action on a Filed document

Drive a happy path to Filed. POST `Approve` on the filed document. Expect 409 / `INVALID_ACTION` per the c5 spec. Assert HTTP 409 and the RFC 7807 problem detail body shape. End state unchanged.

### Spec edits

`/Users/gb/github/basata/.kerf/project/docflow/05-specs/c3-pipeline-spec.md`:

| Edit | What |
|---|---|
| L22 (C3-R12 Verification cell) | Append `; comprehensive HTTP-seam coverage with stubbed LLM seams is delegated to the scenario suite (C3-R17).` to the existing verification text. |
| Insert after the C3-R16 row | Add new rows: `\| C3-R17 \| Scenario tests boot real Spring stack with stubbed \`LlmClassifier\`/\`LlmExtractor\`; run in \`make test\`; no API key required. \| Scenario test class executes inside \`./gradlew check\`; passes without \`ANTHROPIC_API_KEY\` set. \|` and `\| C3-R18 \| Fixture YAML schema defined; loader validates and fails fast on malformed fixtures. \| Loader unit test asserts rejection of unknown properties, missing required keys, and conflicting input shapes. \|` |

`/Users/gb/github/basata/.kerf/project/docflow/05-specs/c4-workflow-spec.md`:

| Edit | What |
|---|---|
| Insert after the C4-R13 row (or wherever R-tags currently end) | Add `\| C4-R14 \| Workflow scenarios cover happy path, wrong-type flag, missing-field flag, retype paths (no-op, success, failed), and origin restoration. \| Scenario suite (C-EVAL-4) includes the corresponding fixtures. \|` |

`/Users/gb/github/basata/.kerf/project/docflow/05-specs/c5-api-spec.md`:

| Edit | What |
|---|---|
| L264 | Replace `the only HTTP-seam integration test` with `the only **live-API** HTTP-seam integration test`. |
| Insert after the C5-R8 row (or wherever R-tags currently end) | Add `\| C5-R10 \| SSE scenario covers two concurrent uploads on a single subscriber stream. \| Scenario 05-concurrent-uploads asserts both documents' events appear on a single SSE GET. \|` |

`/Users/gb/github/basata/.kerf/project/docflow/05-specs/c7-platform-spec.md`:

| Edit | What |
|---|---|
| Insert after C7-R14 | Add `\| **C7-R15** \| Scenario tests are part of the \`make test\` fast gate; excluded from \`make e2e\` and \`make eval\`. \| \`./gradlew check\` runs them; \`make e2e\` and \`make eval\` do not invoke the scenario suite. \|` |

## Files & changes

### New

- `backend/src/test/java/com/docflow/scenario/AbstractScenarioIT.java`
- `backend/src/test/java/com/docflow/scenario/ScenarioStubConfig.java`
- `backend/src/test/java/com/docflow/scenario/ScenarioContext.java`
- `backend/src/test/java/com/docflow/scenario/ScenarioFixture.java`
- `backend/src/test/java/com/docflow/scenario/ScenarioFixtureLoader.java`
- `backend/src/test/java/com/docflow/scenario/ScenarioLlmClassifierStub.java`
- `backend/src/test/java/com/docflow/scenario/ScenarioLlmExtractorStub.java`
- `backend/src/test/java/com/docflow/scenario/ScenarioAssertions.java`
- `backend/src/test/java/com/docflow/scenario/ScenarioRecorder.java`
- `backend/src/test/java/com/docflow/scenario/ScenarioSseClient.java`
- `backend/src/test/java/com/docflow/scenario/ScenarioRunnerIT.java`
- `backend/src/test/java/com/docflow/scenario/ScenarioFixtureLoaderTest.java` — unit tests for the loader's strict-mode behavior.
- `backend/src/test/resources/scenarios/_fixtures/corrupt.pdf` — small deliberately corrupt PDF for scenario 06.
- `backend/src/test/resources/scenarios/01-happy-path-pinnacle-invoice.yaml` … `12-action-on-filed-document.yaml` — twelve fixture files.

### Edited

- `/Users/gb/github/basata/.kerf/project/docflow/05-specs/c3-pipeline-spec.md` — C3-R12 wording + new C3-R17, C3-R18.
- `/Users/gb/github/basata/.kerf/project/docflow/05-specs/c4-workflow-spec.md` — new C4-R14.
- `/Users/gb/github/basata/.kerf/project/docflow/05-specs/c5-api-spec.md` — §6 wording (L264) + new C5-R10.
- `/Users/gb/github/basata/.kerf/project/docflow/05-specs/c7-platform-spec.md` — new C7-R15.

### Build

No changes to `backend/build.gradle` are expected. The scenario tests use Testcontainers, Awaitility, AssertJ, JUnit 5, Jackson YAML, Spring Boot Test — all already on the test classpath (visible in `RetypeFlowIT` and `HappyPathSmokeTest`).

If the SSE client requires `spring-webflux`, a single test-scope dependency may be added. Default approach: implement SSE consumption with `java.net.http.HttpClient` and a streaming `BodyHandlers.ofLines()` — no new dependency.

## Acceptance criteria

1. `cd /Users/gb/github/basata/backend && ./gradlew check` passes without `ANTHROPIC_API_KEY` in the environment.
2. `ScenarioRunnerIT` runs all 12 scenarios; each produces a passing test result.
3. `ScenarioFixtureLoaderTest` exercises the strict-parse contract: unknown property → fail; missing `inputPdf` and `inputs` → fail; both `inputPdf` and `inputs` → fail; `classification` with neither `docType` nor `error` → fail; `classification.error` with an unknown error name → fail.
4. The `LlmCallAuditWriter` writes one row per stub invocation (verified by counting rows in `llm_call_audit` after each scenario, or by adding a recorder bean to capture writer calls). This holds the audit-table invariant.
5. Scenario 05 (concurrent uploads) consumes the SSE stream and asserts both documents' events appear; the test does not flake across 10 successive runs.
6. Scenario 06 (corrupt PDF) does not produce a `Document` row and produces a `ProcessingStepChanged { currentStep: FAILED }` event with a non-empty `error`.
7. The full scenario suite completes in under 5 minutes wall-clock on a clean build.
8. The four spec edits land: `c3-pipeline-spec.md` (C3-R12 + R17 + R18), `c4-workflow-spec.md` (R14), `c5-api-spec.md` (§6 + R10), `c7-platform-spec.md` (R15). Each new R-tag has both summary and verification columns filled.
9. `make e2e` does **not** run scenario tests (Playwright only). `make eval` does **not** run scenario tests (`evalRun` only).

## Verification

```
cd /Users/gb/github/basata/backend && ./gradlew check
```

Expected: passes; `ScenarioRunnerIT` reports 12 passed.

Per-scenario isolation:

```
cd /Users/gb/github/basata/backend && ./gradlew test --tests "com.docflow.scenario.ScenarioRunnerIT.scenario07_lienWaiverConditionalGuard"
```

Loader unit tests:

```
cd /Users/gb/github/basata/backend && ./gradlew test --tests "com.docflow.scenario.ScenarioFixtureLoaderTest"
```

CI confirmation: GitHub Actions run shows the scenario tests pass, no `ANTHROPIC_API_KEY` provided to the job.

## Error handling and edge cases

- **Fixture references a non-existent PDF.** `ScenarioFixtureLoader` resolves the path against `problem-statement/samples/` (or the local `_fixtures/` subtree) and fails the test with the resolved absolute path on miss.
- **Two scenarios run simultaneously by mistake.** Forbidden — `ScenarioContext` is a singleton, mutable. JUnit 5 sequential test execution within a class is the default. The base class enforces it (`@Execution(ExecutionMode.SAME_THREAD)`).
- **The stub is asked for a doc-type the fixture didn't anticipate.** The stub matches by `rawText`; if no fixture matches, the stub throws an explicit `IllegalStateException("no scenario fixture matched rawText (length: <n>) for org <orgId>")`. This surfaces test-author errors loudly.
- **Audit-writer is invoked twice for a stubbed retry path.** `LlmExtractor.extractFields(...)` retries once on `LlmSchemaViolation`. The stub honors this: if the fixture's `extraction.error` is `SCHEMA_VIOLATION`, the stub throws on the first call, succeeds on the second (or throws again, depending on a fixture flag — default: succeed on retry, mirroring the typical recovery path). Audit rows appear for both attempts.
- **Concurrent-uploads test interleaving.** Both stubs match by `rawText`. Even if the events arrive out of order on the bus, the recorder groups by `documentId` and the assertion is per-document.
- **SSE timeout.** Bounded read window (30 s). If the expected events do not arrive within the window, the test fails with the captured frames printed for diagnosis.

## Migration / backwards compatibility

None. New tests; no production code changes. The four spec-edit additions are append-only R-tags plus two prose clarifications.

## Notes on dependency on C-EVAL-3

Recommended order: C-EVAL-3 lands first. If C-EVAL-4 lands first, the seed copies in `backend/src/test/resources/seed-fourth-org/` and the loader fixtures still carry `inputModality:` lines — the scenario tests (which load the production seed) work fine because the field is silently parsed. After C-EVAL-3 lands, those YAML files lose the line as part of the C-EVAL-3 commit. No work in C-EVAL-4 is wasted by the ordering choice.
