# Analysis — `evals`

Factual map of the territory before proposing changes. Three areas affected: the `inputModality` config-data lineage (component 3), the LLM-call seam plus existing integration-test scaffolding (component 4), and the four extant test layers (component 5).

---

## 1. `inputModality` lineage — current state

`inputModality` is a per-doc-type config field threaded from YAML through validation, persistence, catalog views, and finally into `MessageContentBuilder`. The branch that consumes the `PDF` value is dead — it exists, it is reachable from a public API, but no production caller ever passes it.

### 1.1 Spec substrate (read-only context)

- `c1-config-spec.md`
  - L18 (C1-R2): doc-type field schemas list `inputModality ∈ {TEXT, PDF}` with default `TEXT`, "consumed by C3 §3.5".
  - L88: `DocTypeDefinition` record declares `@NotNull InputModality inputModality`.
  - L92: `enum InputModality { TEXT, PDF }`.
  - L326: file inventory lists `InputModality.java`.
  - L362: `DocumentTypeEntity` schema description includes `input_modality varchar ∈ {TEXT, PDF}`.
  - L396: grep-forbidden allowed-strings list includes `input_modality`.
  - L421–L427: seed prose calls out which four doc-types set `PDF` and which five set `TEXT`.
  - L468 (AC-L6): acceptance test requires the four named doc-types to load as `PDF` and the rest as `TEXT`.
- `c3-pipeline-spec.md`
  - L37 (research summary §1): hybrid modality recommended; text-only fallback noted.
  - L151 (§3.5): "Decision: hybrid (text for classify, PDF for extract on doc-types with nested arrays …)". This is the spec authority for the PDF code path.

### 1.2 Production code (`backend/src/main/java`)

| File | Role |
|---|---|
| `com/docflow/config/org/InputModality.java` | Enum `{TEXT, PDF}`. Public, used by `DocTypeDefinition` and `MessageContentBuilder`'s nested enum. |
| `com/docflow/config/org/DocTypeDefinition.java` | YAML-backed record; `@NotNull InputModality inputModality` is a required field on every loaded doc-type. |
| `com/docflow/config/org/seeder/OrgConfigSeedWriter.java` (L81) | Writes `dt.inputModality().name()` into `document_types.input_modality` column. |
| `com/docflow/config/persistence/DocumentTypeEntity.java` (L28–L46, L62–L64) | JPA entity with `@Column(name = "input_modality", nullable = false) String inputModality`, getter, ctor parameter. |
| `com/docflow/config/catalog/DocumentTypeSchemaView.java` (L9) | Record exposes `String inputModality` to consumers. |
| `com/docflow/config/catalog/DocumentTypeCatalogImpl.java` (L95) | Maps entity → view, passing `entity.getInputModality()`. |
| `com/docflow/c3/llm/MessageContentBuilder.java` (L25–L28, L39, L75, L76, L80–L82) | Nested enum `InputModality { TEXT, PDF }` (separate from the config enum); `buildContentBlocks` branches on `modality == PDF` to emit a `DocumentBlockParam.base64Source(...)`. The PDF branch is the one verified-dead consumer. |
| `com/docflow/c3/llm/LlmClassifier.java` (L80–L83) | Calls `messageContentBuilder.build(... InputModality.TEXT, CLASSIFY_MAX_TOKENS, null, rawText)`. Hardcoded `TEXT`; passes `null` for `pdfBytes`. |
| `com/docflow/c3/llm/ExtractRequestBuilder.java` (L35–L38) | Same pattern: hardcoded `InputModality.TEXT`, `null` for pdfBytes. |

Two `InputModality` enums live in the codebase: `com.docflow.config.org.InputModality` (config-side, drives YAML) and the nested `MessageContentBuilder.InputModality` (LLM-side, drives the SDK call). They are not unified. The config-side value never reaches the LLM-side enum at runtime because the call sites in `LlmClassifier` and `ExtractRequestBuilder` ignore the `DocumentTypeSchemaView.inputModality()` field entirely.

### 1.3 Database schema

- `backend/src/main/resources/db/migration/V1__init.sql` (L19, L24–L25): `input_modality VARCHAR(16) NOT NULL DEFAULT 'TEXT'` plus `CHECK (input_modality IN ('TEXT', 'PDF'))`.
- `backend/src/main/resources/db/migration/fragments/c1-org-config.sql` (L15, L20–L21): same column + check; this fragment is the source assembled into `V1__init.sql`.

Greenfield: Flyway has only `V1__init.sql`. The cleanest removal is to edit `V1__init.sql` plus the fragment in place, since no environment has applied the migration in any state of record. (Confirmation gate: this is the project convention — every prior `V1__init.sql` baseline change has been done in place. No additive `V2__` migration has ever been authored.)

### 1.4 Seed YAML

Production seed (`backend/src/main/resources/seed/doc-types/`):
- `inputModality: PDF` (4): `riverside-bistro/invoice.yaml`, `riverside-bistro/expense-report.yaml`, `pinnacle-legal/expense-report.yaml`, `ironworks-construction/invoice.yaml`.
- `inputModality: TEXT` (5): `riverside-bistro/receipt.yaml`, `pinnacle-legal/invoice.yaml`, `pinnacle-legal/retainer-agreement.yaml`, `ironworks-construction/lien-waiver.yaml`, `ironworks-construction/change-order.yaml`.

Test seed (`backend/src/test/resources/seed-fourth-org/doc-types/`): mirrors production plus a `municipal-clerk/permit-application.yaml` with `PDF`.

Loader fixtures (`backend/src/test/resources/loader-fixtures/seed/doc-types/`): six doc-types with mixed values, exercising the loader's parsing.

SQL test fixtures (`backend/src/test/resources/fixtures/`): two files insert into `document_types(... input_modality, field_schema)` directly:
- `processing-completed-listener-seed.sql` (L4)
- `retype-flow-listener-seed.sql` (L4)

### 1.5 Tests that lock the field in place

- `StageGuardConfigTest.inputModalityEnumExposesExactlyTextAndPdf` (L95–L96): asserts enum has both values.
- `OrgConfigSeederIT.acS1_documentTypeWritesInputModalityAndFieldSchema` (L96, L103): asserts the column round-trips `"PDF"`.
- `OrgConfigPersistenceFragmentIT` (L123): asserts loaded value is `"PDF"`.
- `SeedFixturesTest.inputModalityIsPdfForNestedArrayDocTypesAndTextForTheRest` (L84–L97): asserts the four-PDF / five-TEXT split.
- `ConfigValidatorTest` (L10, L113, L234, L260, L286, L314): uses `InputModality.TEXT` as a literal in fixture builders.
- `LlmClassifierTest`, `LlmExtractorTest`, `MessageContentBuilderTest`, `LlmCallExecutorTest`: import `MessageContentBuilder.InputModality` for unit-test setup.

These all need to be removed or adjusted in lockstep with the production-code removal.

---

## 2. LLM seam and integration-test scaffolding

### 2.1 The two seams

`LlmClassifier` (`backend/src/main/java/com/docflow/c3/llm/LlmClassifier.java`):
- Public constructor takes `OrganizationCatalog`, `PromptLibrary`, `ToolSchemaBuilder`, `MessageContentBuilder`, `LlmCallExecutor`, `LlmCallAuditWriter`, `AppConfig`, `Clock`.
- One method: `classify(storedDocumentId, processingDocumentId, organizationId, rawText) → ClassifyResult`.
- `ClassifyResult` is a record with `String detectedDocumentType`.
- Throws `LlmSchemaViolation` when the model returns a value outside the org's allowed enum. Audit-writes on every call (success or failure).

`LlmExtractor` (`backend/src/main/java/com/docflow/c3/llm/LlmExtractor.java`):
- Two methods: `extractFields(storedDocumentId, processingDocumentId, organizationId, docTypeId, rawText) → Map<String, Object>` (initial-pipeline path) and `extract(documentId, newDocTypeId)` (retype path; reads document state, writes back, publishes `ExtractionCompleted` / `ExtractionFailed`).
- Constructor takes `DocumentTypeCatalog`, `ExtractRequestBuilder`, `RetypeDocumentSink`, `LlmCallExecutor`, `LlmCallAuditWriter`, `AppConfig`, `Clock`.
- One retry on `LlmSchemaViolation`.

Stubbing at this level ignores `MessageContentBuilder`, `LlmCallExecutor`, the Anthropic SDK, prompt rendering, tool-schema generation, and audit writes. The pipeline orchestrator's behavior on the `ClassifyResult` and the extracted `Map<String, Object>` is exercised end-to-end. This matches the spec's intent: the seam is the conceptual boundary, not the message shape.

The lower seam (`LlmCallExecutor.execute(MessageCreateParams, ToolSchema) → JsonValue`) was rejected: stubbing it forces fixtures to know SDK message shape, tool-call wire format, and exercises code paths (`MessageContentBuilder`, `ToolSchemaBuilder`) whose unit tests already cover.

### 2.2 Existing integration-test prior art

`backend/src/test/java/com/docflow/api/HappyPathSmokeTest.java`:
- `@SpringBootTest(classes = Application.class, webEnvironment = RANDOM_PORT)`. Boots the full app.
- `@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")` — gates on real API key.
- `@Testcontainers` + a single Postgres container; Flyway runs.
- Properties override `docflow.config.seed-on-boot=true`, `docflow.config.seed-resource-path=classpath:seed/`, `docflow.llm.model-id=claude-sonnet-4-6`, request timeout 120s, storage root to a temp dir.
- One test: upload a real Riverside invoice PDF → poll dashboard for `AWAITING_REVIEW` → POST `Approve` → assert `AWAITING_APPROVAL`.
- Skipped when no API key.

This is the live-API smoke. The scenario suite is its **stubbed** sibling at the same wiring level.

`backend/src/test/java/com/docflow/workflow/internal/RetypeFlowIT.java`:
- `@SpringBootTest(classes = RetypeFlowIT.RetypeFlowIntegrationApp.class)` — uses a hand-assembled `@SpringBootApplication` inner class, not `Application.class`. Imports a curated bean set (`AsyncConfig`, `DocumentEventBus`, catalogs, JDBC reader/writer, listener).
- Provides a Mockito-mocked `LlmExtractor` via `@Bean` override (lines 502–505). This is the canonical pattern: Spring sees the `@Bean LlmExtractor` declaration and uses it instead of the production component.
- `@Testcontainers` + `withInitScripts("db/migration/V1__init.sql", "fixtures/retype-flow-listener-seed.sql")` — test fixtures injected at container init time. No Flyway.
- `@DynamicPropertySource` wires Postgres URL.
- Disables web (`spring.main.web-application-type=none`), JPA DDL, Flyway. Fakes the LLM config (`docflow.llm.api-key=sk-ant-test`, request-timeout 60s).
- Uses Awaitility for asynchronous event assertions; provides a `DocumentStateChangedRecorder` bean to capture published events; uses logback `ListAppender` to assert on log output.

Two patterns to inherit:
- `@Bean` override for `LlmExtractor` / `LlmClassifier` is enough to redirect the seam without `@MockBean` magic.
- Awaitility + ApplicationEventPublisher + recorder bean lets a test wait for an asynchronous, multi-listener pipeline to settle.

Two patterns **not** to inherit:
- The hand-assembled `@SpringBootApplication` inner class is a bespoke wiring slice. The scenario tests need the **real** `Application.class` (full HTTP stack, full processing pipeline, full workflow listeners) — that is the point.
- `withInitScripts(...)` is incompatible with full Flyway boot. The scenario tests need Flyway (so the same migrations run as in production); fixtures load through service calls (e.g., the seed loader on first boot, then JdbcTemplate in `@BeforeEach`).

### 2.3 Spring profile usage today

No existing `application-*.yml` profile-keyed files. Profile selection is implicit (`default`). `@ActiveProfiles` is not used in production code. A new profile `scenario` activated via `@ActiveProfiles("scenario")` on the test class is greenfield — no collision.

### 2.4 Pipeline trigger and event bus

The pipeline starts when `StoredDocumentIngested` is published on `DocumentEventBus`. C2's upload controller publishes it post-commit. C3's `PipelineTriggerListener` consumes it and runs `TextExtractStep → ClassifyStep → ExtractStep`. Async listeners use `@Async` with a virtual-thread executor (per `AsyncConfig`).

For scenario tests this means: a scenario uploads via HTTP → C2 commits the row + publishes the event → C3 picks up async → real PDFBox runs → stub classifier/extractor return canned values → C4's `ProcessingCompleted` listener materializes `Document` + `WorkflowInstance` → assertions follow.

---

## 3. The four test layers — current state

### 3.1 Unit tests
Per-component, fast, no Spring context. Examples: `WorkflowEngine` property tests, `LlmClassifierTest`, `MessageContentBuilderTest`, `EvalScorerTest`. Run inside `./gradlew check`.

### 3.2 `./gradlew evalRun` (just-landed C3.11, beads `df-2zl.11`)
Java-side; invokes `EvalRunner` directly. Loads `eval/manifest.yaml` (12 samples), runs `LlmClassifier` then `LlmExtractor` against the live Anthropic API on each, scores with `EvalScorer`, writes `eval/reports/latest.md`. Skips cleanly without `ANTHROPIC_API_KEY`. **Tests the prompts in isolation.** Does not exercise PDFBox, the orchestrator, the workflow, or HTTP. Run on demand. Excluded from `make test`. Source: `backend/src/main/java/com/docflow/c3/eval/`.

### 3.3 Python harness (`eval/harness/run.py`)
Drives the running stack via the public HTTP API: upload → poll → assert. Reads `eval/harness/labels.yaml` (23 samples). Verdict-quality output. **Tests the full system end-to-end with the live API.** Requires the stack running and a key in env. Run on demand; not in `make test`. Larger, slower than `evalRun`; will fail loudly on schema/API regressions; less prompt-quality signal than `evalRun` because errors compound.

### 3.4 `HappyPathSmokeTest`
Single Spring-Boot integration test, gated on the API key, 2-minute timeout. One sample, one path. Currently the sole HTTP-seam test. Per `c5-api-spec.md` §6 it is described as "the only HTTP-seam integration test (per scope cut)" — that line is now stale because the scenario suite will be a HTTP-seam test layer.

### 3.5 What is missing
No deterministic CI test today exercises:
- Wrong-type classification flow.
- Missing-required-field flag.
- The retype `Resolve` paths (other than `RetypeFlowIT`, which uses a bespoke wiring slice and a mocked extractor — not the full HTTP stack).
- The lien-waiver guard branches.
- Two concurrent uploads on a single SSE stream.
- Action validation on terminal documents.
- Origin-restoration after attorney-stage flag.

These are the gaps the scenario suite fills.

---

## 4. Conventions to follow

- **Test placement:** `@SpringBootTest`s live under `backend/src/test/java/com/docflow/<area>/` (e.g., `com.docflow.api.HappyPathSmokeTest`, `com.docflow.workflow.internal.RetypeFlowIT`). Scenario-test class location follows: `com.docflow.scenario.*`.
- **Test resource layout:** fixtures under `backend/src/test/resources/<purpose>/`. Scenario fixtures: `backend/src/test/resources/scenarios/`.
- **YAML loader:** Jackson is the project standard (used by `ConfigLoader` for seed YAML). The scenario fixture loader should follow the same pattern (`ObjectMapper` with the YAML factory, record-based bindings, fail-fast on unknown fields).
- **Event-driven assertion:** Awaitility with explicit timeouts (5 s for fast paths, 30 s for full upload-to-review). Recorder beans for published events.
- **No literal stage strings or doc-type ids in production code** (C4-R10, enforced by `grepForbiddenStrings`). Test code is exempt; the scenario fixtures naturally hold these literals in YAML.
- **No comments unless the why is non-obvious** (project convention).
- **Coverage threshold:** JaCoCo line coverage 70% (from C7-R13). The new test layer increases coverage of the orchestrator + workflow listeners; nothing requires explicit configuration.

---

## 5. Constraints carried forward

- **Greenfield:** no migration history to honor; in-place edits to `V1__init.sql` and its fragments are how prior schema work has been done. Confirmed by recent commits (e.g., `C7.4: Flyway V1__init.sql baseline + manual @Bean wiring`).
- **`./gradlew check` must stay green:** scenario tests run under it.
- **API key not available in CI:** scenario tests must not import `ANTHROPIC_API_KEY` or instantiate `LlmCallExecutor` against the real SDK.
- **Stop hook runs `make test`:** every scenario must complete reliably under the existing 600s hook timeout and not leave dangling Postgres containers.
- **Spec edits land in two places only:** `c3-pipeline-spec.md` C3-R12 verification clause and `c5-api-spec.md` §6 prose. Both edits clarify the existing scope cut; no R-tag is renumbered.
- **`problem-statement/samples/*.pdf` are read-only inputs.** Scenario fixtures reference them by path; never copy or mutate.

---

## 6. Recent git activity in affected areas

- `3fb331b C7.4: Flyway V1__init.sql baseline + manual @Bean wiring` — the migration baseline that includes `input_modality`. In-place edits to V1 are the established pattern.
- `64b92a6 C2.3: stored_documents fragment + JPA entity + reader` — same in-place fragment-edit pattern.
- `6cb2313 C3.1: processing_documents + llm_call_audit migration fragments` — confirms fragments under `db/migration/fragments/` get assembled into `V1__init.sql`.
- C3.11 (eval harness) closed today (per problem-space). `EvalRunner`, `EvalScorer`, `EvalReportWriter`, `EvalManifestEntry` all exist under `c3/eval/`. Untouched by this work.

No active feature branches in the LLM-call or workflow areas as of this analysis.
