# C3 — Processing Pipeline & Eval — Change Spec

Implements the C3 component from `03-components.md`: the `ProcessingDocument`-driven processing pipeline (text extract → classify → extract via tool use), the `llm_call_audit` audit trail, the `LlmExtractor.extract` retype entry point, and the eval harness.

Research basis: `04-research/c3-llm/findings.md`. Where research left a choice open, this spec picks one and records the rationale.

---

## 1. Requirements (carried from `03-components.md` §C3)

| ID | Summary | Verification |
|---|---|---|
| C3-R1 | `classifyDocument(processingDocumentId)` calls Anthropic with allowed-doc-types from C1; returns a member of that set; on failure sets `current_step = FAILED` and emits `ProcessingStepChanged`. | Unit test on the orchestrator step; mocked SDK. |
| C3-R2 | `extractFields` calls Anthropic with tool use; tool input JSON schema derived from C1 doc-type schema; `tool_choice` forced; output validated; one retry on invalid; second failure surfaces typed error. Initial pipeline stages result on `ProcessingDocument`; retype writes directly to `Document.extractedFields`. | Unit tests on the extract step (both retry-then-succeed and second-failure branches). |
| C3-R4 | Retype re-extraction (C4-driven) replaces `Document.extractedFields` via UPDATE; old payload not preserved. | Unit test asserts UPDATE-not-INSERT and that prior values are gone. |
| C3-R5 | Prompts live as named resource files at `src/main/resources/prompts/<id>.txt`; no in-app versioning; eval reports against whatever prompts are committed at run time. | Grep + startup load test. |
| C3-R5a | `llm_call_audit` table: one row per classify/extract; columns `id`, `stored_document_id` (always populated), `processing_document_id` (nullable), `document_id` (nullable), `organization_id`, `call_type ∈ {classify, extract}`, `model_id`, `error` (nullable), `at`. Index on `(stored_document_id, at DESC)`. | Migration exists; integration test inserts both call shapes. |
| C3-R6 | Tool input schema generated deterministically from C1 doc-type schema; same schema → same tool definition (stable field + enum order). | Property test: serialize twice, assert byte-equal. |
| C3-R7 | Sample corpus is a single labeled set; committed manifest maps filename → known `docType` + `extractedFields`; no tune/holdout split. | Manifest file exists and parses. |
| C3-R8 | Eval command runs classify + extract over labeled samples; produces a markdown report with **aggregate classification accuracy** and **aggregate extract field accuracy** (mean exact-match across all fields and samples). No per-org/per-doc-type/per-field breakdowns. | Run `./gradlew evalRun`; markdown report appears at the configured path. |
| C3-R9 | Eval hits the live Anthropic API; no recorded-replay mode; CI does not run the eval (run on demand locally). | CI config has no eval step; eval task requires `ANTHROPIC_API_KEY`. |
| C3-R12 | A single happy-path integration smoke test exercises classify + extract end-to-end against the live API for one sample document. Comprehensive HTTP-seam coverage out of scope. | Tagged JUnit test, gated on `ANTHROPIC_API_KEY`. |
| C3-R13 | Retype re-extraction is an UPDATE on `Document` (not new row); `Document.reextractionStatus` cycles `NONE → IN_PROGRESS → NONE`/`FAILED`. | Unit test on the retype path. |
| C3-R14 | `ProcessingPipelineOrchestrator` runs `TEXT_EXTRACTING → CLASSIFYING → EXTRACTING`; on success emits `ProcessingCompleted` (internal) and the `ProcessingDocument` row is left in place; on any-step failure UPDATE `current_step = FAILED`, set `last_error`, emit `ProcessingStepChanged { current_step: FAILED, error }`. No retry endpoint. | Orchestrator unit test exercises both terminal paths. |
| C3-R16 | Tenant-aware: `organization_id` denormalized on `processing_documents` and `llm_call_audit`; writers read parent `StoredDocument` first to keep values consistent. Unit test asserts consistency. | Unit test on writers. |

(C3-R3, C3-R10, C3-R11, C3-R15 were removed in `03-components.md`; not carried forward.)

Locked decisions (from `03-components.md`): model `claude-sonnet-4-6` for both calls, sourced from `AppConfig.llm.modelId` (C7-R13).

---

## 2. Research summary

From `04-research/c3-llm/findings.md` — load-bearing facts that shape this spec:

1. **Input modality.** Claude PDFs are converted page-by-page to image+text and provided to the model. Hybrid (text for classify, PDF for extract on doc-types with nested arrays) is the recommended default; text-only is the cheap fallback if the eval shows hybrid offers no measurable lift on the corpus. Base64 inline beats Files-API for take-home (Files-API still beta, single extra call, marginal cache benefit at our document sizes).
2. **Prompt management.** Resource files keyed by identifier; prompt change = edit the file; reviewer sees a plain-text diff. Per `03-components.md` §C3-R5, this take-home explicitly does **not** introduce in-app prompt versioning — git is the version store. Research's `<identifier>/<version>.txt` layout is therefore simplified to `<identifier>.txt`.
3. **Tool-use SDK shape.** Use `Tool.builder().inputSchema(InputSchema.builder()…)` with `JsonValue.from(Map.of(…))` because schemas come from C1 config (not Jackson POJOs). Force a tool with `ToolChoice.ofTool(ToolChoiceTool.builder().name(toolName).build())`. Read via `response.content().stream().flatMap(cb -> cb.toolUse().stream())._input()`.
4. **Eval scoring.** Per `03-components.md` §C3-R8 the take-home reports two aggregate numbers only — classification accuracy and mean extract field-level exact-match. Research's richer per-field-type metric palette (parsed-equal dates, numeric-equal amounts, Levenshtein-ratio narratives, row-level P/R/F1 nested arrays) is *not* in scope for this spec; field comparison is normalized exact match across all field types. Recorded in §3 as a deliberate scope cut.
5. **Recording format.** N/A. The components doc removed recorded-mode replay. Research's recording shape is not used.
6. **Risks worth carrying forward.** PDFBox quality on whimsical samples (mitigation: dry-run on all 23 samples during implementation), classifier returning a non-enum value (forced tool choice + schema validation should make this loud, not silent — fail with typed error), accumulating FAILED `ProcessingDocument` rows (cleanup deferred to C7-R9 hypothetical cron, not implemented).

---

## 3. Approach

### 3.1 Architecture

Three layered seams inside `c3.pipeline`:

```
StoredDocumentIngested (on DocumentEventBus, published by C2 post-commit)
    └─► PipelineTriggerListener.onIngested(event)
           └─► ProcessingDocumentService.start(storedDocumentId, processingDocumentId)
                  └─► ProcessingPipelineOrchestrator.run(processingDocumentId)
                         ├─ TextExtractStep            (PDFBox → rawText)
                         ├─ ClassifyStep               (LlmClassifier)
                         └─ ExtractStep                (LlmExtractor — initial-pipeline mode)
                      └─ on success: emit ProcessingCompleted on DocumentEventBus
                      └─ on failure: UPDATE current_step = FAILED + emit ProcessingStepChanged

LlmExtractor.extract(documentId, docTypeId)        ← C4 retype entry point
    └─ writes Document.extractedFields directly
    └─ emits ExtractionCompleted / ExtractionFailed
```

The pipeline is triggered by a `StoredDocumentIngested` event on `DocumentEventBus` (published by C2 post-commit per its §3.7 ordering; the precise event name is owned here in C3 — see §3.9). `PipelineTriggerListener` is the bus subscriber that consumes the event and calls `ProcessingDocumentService.start`. C2 never calls C3 directly — the boundary is unidirectional via the bus.

`ProcessingPipelineOrchestrator` runs synchronously inside a worker thread spawned by `ProcessingDocumentService.start` (Spring `@Async` on a bounded `TaskExecutor` in C7). The orchestrator never blocks the HTTP thread that triggered the upload (C2 → C3 boundary). Each step is an independent class implementing a `PipelineStep` interface — the orchestrator is the only thing that knows about `current_step` transitions.

### 3.2 Class layout

```
backend/src/main/java/com/docflow/c3/
  pipeline/
    PipelineStep.java                      — sealed interface; one of {TextExtract, Classify, Extract}
    ProcessingPipelineOrchestrator.java
    PipelineTriggerListener.java           — subscribes to StoredDocumentIngested on DocumentEventBus; calls ProcessingDocumentService.start
    TextExtractStep.java                   — PDFBox-backed
    ClassifyStep.java                      — calls LlmClassifier
    ExtractStep.java                       — calls LlmExtractor (initial-pipeline mode)
    ProcessingDocumentService.java         — public entry: start(storedDocumentId, processingDocumentId)
    ProcessingDocumentWriter.java          — package-private; only writer for processing_documents
    ProcessingDocumentReader.java          — public read-side
  llm/
    LlmClassifier.java                     — classify call
    LlmExtractor.java                      — extract call; public for C4 retype
    AnthropicClientFactory.java            — wraps com.anthropic.client.AnthropicClient
    PromptLibrary.java                     — loads resources at startup
    ToolSchemaBuilder.java                 — deterministic schema → InputSchema
    LlmException.java                      — sealed: LlmTimeout, LlmProtocolError, LlmSchemaViolation, LlmUnavailable
  audit/
    LlmCallAudit.java                      — record (immutable)
    LlmCallAuditWriter.java                — INSERT only
    LlmCallAuditReader.java                — read-side
  events/
    StoredDocumentIngested.java            — internal trigger (C3 consumes; C2 publishes)
    ProcessingStepChanged.java             — SSE-visible
    ProcessingCompleted.java               — internal
    ExtractionCompleted.java               — internal (retype)
    ExtractionFailed.java                  — internal (retype)
  eval/
    EvalRunner.java                        — public CLI entry; runs classify+extract over manifest
    EvalManifest.java                      — record; loaded from yaml
    EvalScorer.java                        — pure: aggregates accuracy
    EvalReportWriter.java                  — pure: result → markdown
backend/src/main/resources/
  prompts/
    classify.txt
    extract_riverside_invoice.txt
    extract_riverside_receipt.txt
    extract_riverside_expense_report.txt
    extract_pinnacle_invoice.txt
    extract_pinnacle_retainer_agreement.txt
    extract_pinnacle_expense_report.txt
    extract_ironworks_invoice.txt
    extract_ironworks_change_order.txt
    extract_ironworks_lien_waiver.txt
  eval/
    manifest.yaml                          — sample filename → {docType, extractedFields}
backend/src/main/resources/db/migration/
  V1__init.sql                             — see §3.7 for FK shape
```

### 3.3 Prompt structure

Two prompt families:

- `classify.txt` — system prompt template with `{{ALLOWED_DOC_TYPES}}` substitution; instructs the model to call `select_doc_type` with one of the allowed values. Substitution is `String.replace`-based (no Mustache dependency).
- `extract_<docType>.txt` — system prompt template per (org × doc-type) combination; doc-type-specific guidance (e.g., narrative-field hints for `scope`, table-row guidance for `lineItems`).

`PromptLibrary` loads all files at startup and validates that every (org, allowed-doc-type) pair from C1 has a matching `extract_<docType>.txt`. **Missing prompts fail startup** (matches the C1-R5 fail-fast pattern). No in-app version metadata; the prompt content is whatever git has at HEAD.

### 3.4 Tool-use shapes

**Classify:**
- Tool name: `select_doc_type`.
- Input schema: `{ "type": "object", "properties": { "docType": { "type": "string", "enum": <allowed for org> } }, "required": ["docType"] }`.
- `tool_choice = ofTool(select_doc_type)`. `max_tokens = 512`.
- Read `tool_use.input.docType` and validate against the enum; non-member is a `LlmSchemaViolation`.

**Extract:**
- Tool name: `extract_<docType>` (e.g., `extract_pinnacle_invoice`).
- Input schema: derived deterministically from C1's doc-type field schema by `ToolSchemaBuilder` — fields and enums emitted in declaration order from C1 (which itself loads them in source order). Same C1 schema → byte-identical tool definition.
- `tool_choice = ofTool(extract_<docType>)`. `max_tokens = 2048`.
- Read `tool_use.input` as a `JsonValue` and validate against the schema. Validation happens via the same JSON-schema library used in C1 to load the doc-type schema, so the same rules govern both ingestion and runtime.

### 3.5 Input modality

**Decision: hybrid (text for classify, PDF for extract on doc-types with nested arrays; text for extract on flat doc-types).** Per-doc-type modality is a metadata flag on the C1 doc-type record (`inputModality ∈ {text, pdf}`, default `text`). Doc-types with a nested-array field (`lineItems`, `materials`, `items`) set `pdf`. Base64 inline; no Files-API.

**Rationale.** Research §1 documented this as the recommended default; the table-heavy doc-types (Riverside Invoice, Pinnacle Invoice, Ironworks Invoice, both Expense Reports) benefit from preserved column alignment, while flat doc-types (Riverside Receipt, Pinnacle Retainer, Ironworks Change Order, Ironworks Lien Waiver) get nothing from the image channel and don't pay the token cost.

If the eval reports lower-than-expected aggregate field accuracy after first run, the next mitigation is to flip all doc-types to `pdf` and re-run — a config change, not code.

### 3.6 Retry & error handling

LLM call lifecycle (per call):

1. Build `MessageCreateParams`.
2. Call `client.messages().create(params)`.
3. On SDK exception (`AnthropicException`):
   - 429 / 5xx → throw `LlmUnavailable`.
   - I/O timeout → throw `LlmTimeout`.
4. On success: extract `tool_use` from response. If absent → `LlmProtocolError`.
5. Validate `tool_use.input` against the schema. If invalid → `LlmSchemaViolation`.

Retry policy:
- **Classify:** no retry. Failure surfaces as `current_step = FAILED`. (Classify is cheap and a second roll of the same dice rarely helps.)
- **Extract:** one retry on `LlmSchemaViolation` only (per C3-R2). No retry on `LlmUnavailable` or `LlmTimeout` (a transient API failure is a transient API failure; the user can re-upload).
- **Initial pipeline (orchestrator):** any thrown `LlmException` after the policy above is caught at the orchestrator boundary and turned into `current_step = FAILED` + `ProcessingStepChanged`.
- **Retype (`LlmExtractor.extract` invoked by C4):** any thrown `LlmException` becomes `ExtractionFailed { documentId, error }` on the bus; C4 sets `Document.reextractionStatus = FAILED`.

Anthropic SDK timeouts: `requestTimeout = 60s` per call (configurable via `AppConfig.llm.requestTimeout`). The SDK's built-in retry policy is disabled (we own retry semantics).

### 3.7 `llm_call_audit` table — FK shape decision

Per `03-components.md` §C3-R5a the parked question was whether `processing_document_id` and `document_id` should be modeled as two columns or as a single polymorphic column.

**Decision: two nullable FK columns.** `stored_document_id` always populated; `processing_document_id` populated for initial-pipeline calls (classify and the initial extract); `document_id` populated for retype-extract calls. A CHECK constraint enforces mutual exclusivity:

```sql
CHECK (
  (processing_document_id IS NOT NULL AND document_id IS NULL) OR
  (processing_document_id IS NULL AND document_id IS NOT NULL)
)
```

Rationale: standard relational FKs cost nothing extra; the alternative (a single `subject_id` column with a `subject_type` discriminator) loses referential integrity to no benefit. Both columns get FKs and indexes per the schema convention in `CLAUDE.md`.

This resolves the parked routine item.

### 3.8 Audit row lifecycle

Every classify and every extract call writes one `LlmCallAudit` row, INSERT-only:
- Inserted *after* the SDK call returns (success or failure). On failure, `error` carries a short message; on success, `error IS NULL`.
- `processing_document_id` set for initial-pipeline calls; `document_id` set for retype calls. `stored_document_id` always set.
- `model_id` is `claude-sonnet-4-6` (from `AppConfig.llm.modelId`).
- `at` is `now()` at INSERT time.

The audit writer is the only mutator on `llm_call_audit`. The orchestrator and `LlmExtractor` both call it via injected interface. Audit rows are never deleted, even when the parent `ProcessingDocument` is deleted on success — the FK is `ON DELETE SET NULL` so historical audit rows survive `ProcessingDocument` deletion.

Wait: `03-components.md` §C3-R14 says **"The `ProcessingDocument` row is left in place; cleanup is deferred"** — there is no actual deletion at completion. That means `ON DELETE SET NULL` is not exercised in the implemented codepath but remains the right schema-level guard for the future cleanup cron (C7-R9).

### 3.9 Events

All events flow through `DocumentEventBus`. C3 declares one **trigger** event (consumed by C3 itself, published by C2) and four **lifecycle** events (published by C3, consumed by C4 / C5).

**Trigger (C3 consumes; C2 publishes):**

- `StoredDocumentIngested { storedDocumentId, organizationId, processingDocumentId, at }` — published by C2 post-commit after the upload transaction succeeds (C2 §3.7). C3's `PipelineTriggerListener` subscribes and invokes `ProcessingDocumentService.start(storedDocumentId, processingDocumentId)`. **Internal-only — not SSE-visible.** C5 does not subscribe; the event never leaves the backend. Cross-spec dependency: C2's spec currently uses the same name (C2 §3.1 step 7 / §10 item 1 references "the C3 trigger event" with `StoredDocumentIngested` as the placeholder); this spec confirms `StoredDocumentIngested` as the canonical name and C2's spec's publisher line is unchanged.

**Lifecycle (C3 publishes):**

- `ProcessingStepChanged { storedDocumentId, processingDocumentId, organizationId, currentStep, error?, at }` — emitted on every step transition AND on FAILED. SSE-visible (C5 subscribes for the org's stream).
- `ProcessingCompleted { storedDocumentId, processingDocumentId, organizationId, detectedDocumentType, extractedFields, rawText, at }` — emitted at terminal success. Internal-only; C4 subscribes to materialize `Document` + `WorkflowInstance`.
- `ExtractionCompleted { documentId, extractedFields, detectedDocumentType?, at }` and `ExtractionFailed { documentId, error, at }` — emitted by `LlmExtractor` for retype only. Internal-only; C4 subscribes to UPDATE `Document.extractedFields` and `reextractionStatus`.

`ProcessingFailed` is not a separate event — it is folded into `ProcessingStepChanged` with `currentStep: FAILED` per `03-components.md`.

### 3.10 Eval harness

`EvalRunner` is invoked by a Gradle task (`./gradlew evalRun`), not via HTTP:

1. Load `eval/manifest.yaml` (sample filename → `{ orgId, docType, extractedFields }`).
2. For each sample: load PDF bytes from `problem-statement/samples/...`, run `LlmClassifier` then `LlmExtractor` (live API, hits `AppConfig.llm.modelId`).
3. `EvalScorer` compares predicted vs. labeled:
   - Classification: `predicted == expected` (exact equality on enum value).
   - Extraction: walk every field in the expected payload (including nested rows), normalize-and-exact-match each scalar; aggregate as `correct / total`.
4. `EvalReportWriter` produces a markdown file with two top-line numbers:
   - `Classification accuracy: <x>/<n> (<pct>%)`
   - `Extraction field accuracy: <x>/<n> (<pct>%)`
   plus a per-sample row in a table for debugging (sample name, predicted docType, expected docType, classify ✓/✗, fields-matched/total).
5. Output written to `eval/reports/latest.md`.

The eval manifest is the single labeled set per C3-R7. No tune/holdout split.

### 3.11 Concurrency & ordering

A single `ProcessingDocument` is processed sequentially: text-extract, then classify, then extract. The orchestrator does not parallelize within a doc. Multiple docs can run concurrently; the `TaskExecutor` from C7 bounds the parallelism.

`current_step` UPDATEs are committed before the next step starts, so SSE consumers see ordered transitions. A failed step UPDATE + event emit happens in the same transaction as the audit INSERT (one transaction per LLM call boundary, not per pipeline run).

---

## 4. Files & changes

### New files

```
backend/src/main/java/com/docflow/c3/pipeline/PipelineStep.java
backend/src/main/java/com/docflow/c3/pipeline/ProcessingPipelineOrchestrator.java
backend/src/main/java/com/docflow/c3/pipeline/PipelineTriggerListener.java
backend/src/main/java/com/docflow/c3/pipeline/TextExtractStep.java
backend/src/main/java/com/docflow/c3/pipeline/ClassifyStep.java
backend/src/main/java/com/docflow/c3/pipeline/ExtractStep.java
backend/src/main/java/com/docflow/c3/pipeline/ProcessingDocumentService.java
backend/src/main/java/com/docflow/c3/pipeline/ProcessingDocumentWriter.java
backend/src/main/java/com/docflow/c3/pipeline/ProcessingDocumentReader.java
backend/src/main/java/com/docflow/c3/pipeline/ProcessingDocument.java
backend/src/main/java/com/docflow/c3/pipeline/ProcessingDocumentSummary.java
backend/src/main/java/com/docflow/c3/llm/LlmClassifier.java
backend/src/main/java/com/docflow/c3/llm/LlmExtractor.java
backend/src/main/java/com/docflow/c3/llm/AnthropicClientFactory.java
backend/src/main/java/com/docflow/c3/llm/PromptLibrary.java
backend/src/main/java/com/docflow/c3/llm/PromptTemplate.java
backend/src/main/java/com/docflow/c3/llm/ToolSchemaBuilder.java
backend/src/main/java/com/docflow/c3/llm/LlmException.java
backend/src/main/java/com/docflow/c3/audit/LlmCallAudit.java
backend/src/main/java/com/docflow/c3/audit/LlmCallAuditWriter.java
backend/src/main/java/com/docflow/c3/audit/LlmCallAuditReader.java
backend/src/main/java/com/docflow/c3/events/StoredDocumentIngested.java
backend/src/main/java/com/docflow/c3/events/ProcessingStepChanged.java
backend/src/main/java/com/docflow/c3/events/ProcessingCompleted.java
backend/src/main/java/com/docflow/c3/events/ExtractionCompleted.java
backend/src/main/java/com/docflow/c3/events/ExtractionFailed.java
backend/src/main/java/com/docflow/c3/eval/EvalRunner.java
backend/src/main/java/com/docflow/c3/eval/EvalManifest.java
backend/src/main/java/com/docflow/c3/eval/EvalScorer.java
backend/src/main/java/com/docflow/c3/eval/EvalReportWriter.java

backend/src/main/resources/prompts/classify.txt
backend/src/main/resources/prompts/extract_riverside_invoice.txt
backend/src/main/resources/prompts/extract_riverside_receipt.txt
backend/src/main/resources/prompts/extract_riverside_expense_report.txt
backend/src/main/resources/prompts/extract_pinnacle_invoice.txt
backend/src/main/resources/prompts/extract_pinnacle_retainer_agreement.txt
backend/src/main/resources/prompts/extract_pinnacle_expense_report.txt
backend/src/main/resources/prompts/extract_ironworks_invoice.txt
backend/src/main/resources/prompts/extract_ironworks_change_order.txt
backend/src/main/resources/prompts/extract_ironworks_lien_waiver.txt

backend/src/main/resources/eval/manifest.yaml

backend/src/test/java/com/docflow/c3/pipeline/ProcessingPipelineOrchestratorTest.java
backend/src/test/java/com/docflow/c3/pipeline/PipelineTriggerListenerTest.java
backend/src/test/java/com/docflow/c3/pipeline/ProcessingDocumentWriterTest.java
backend/src/test/java/com/docflow/c3/llm/ToolSchemaBuilderTest.java
backend/src/test/java/com/docflow/c3/llm/LlmExtractorTest.java
backend/src/test/java/com/docflow/c3/llm/PromptLibraryTest.java
backend/src/test/java/com/docflow/c3/audit/LlmCallAuditWriterTest.java
backend/src/test/java/com/docflow/c3/eval/EvalScorerTest.java
backend/src/test/java/com/docflow/c3/eval/EvalReportWriterTest.java
backend/src/test/java/com/docflow/c3/integration/PipelineSmokeIT.java
```

### Modifications to other components

- `backend/src/main/resources/db/migration/V1__init.sql` (owned by C7 per analysis): add `processing_documents` and `llm_call_audit` table definitions per §3.7. C3 contributes the SQL fragments; C7 stitches the migration.
- `backend/src/main/resources/application.yaml` (C7-owned): add `llm.modelId: claude-sonnet-4-6`, `llm.requestTimeout: 60s`, and `llm.eval.reportPath: eval/reports/latest.md`.
- `build.gradle.kts` (C7): add `evalRun` task that invokes `EvalRunner`; add `org.apache.pdfbox:pdfbox` and `com.anthropic:anthropic-java` deps.

### `processing_documents` schema (SQL fragment)

```sql
CREATE TABLE processing_documents (
  id                UUID PRIMARY KEY,
  stored_document_id UUID NOT NULL REFERENCES stored_documents(id),
  organization_id    TEXT NOT NULL,
  current_step       TEXT NOT NULL CHECK (current_step IN ('TEXT_EXTRACTING','CLASSIFYING','EXTRACTING','FAILED')),
  raw_text           TEXT,
  last_error         TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_processing_documents_org_created ON processing_documents (organization_id, created_at DESC);
CREATE INDEX idx_processing_documents_stored      ON processing_documents (stored_document_id);
```

### `llm_call_audit` schema (SQL fragment)

```sql
CREATE TABLE llm_call_audit (
  id                    UUID PRIMARY KEY,
  stored_document_id    UUID NOT NULL REFERENCES stored_documents(id),
  processing_document_id UUID REFERENCES processing_documents(id) ON DELETE SET NULL,
  document_id            UUID REFERENCES documents(id)             ON DELETE SET NULL,
  organization_id       TEXT NOT NULL,
  call_type             TEXT NOT NULL CHECK (call_type IN ('classify','extract')),
  model_id              TEXT NOT NULL,
  error                 TEXT,
  at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (
    (processing_document_id IS NOT NULL AND document_id IS NULL) OR
    (processing_document_id IS NULL AND document_id IS NOT NULL)
  )
);
CREATE INDEX idx_llm_call_audit_stored_at ON llm_call_audit (stored_document_id, at DESC);
CREATE INDEX idx_llm_call_audit_proc      ON llm_call_audit (processing_document_id);
CREATE INDEX idx_llm_call_audit_doc       ON llm_call_audit (document_id);
```

(`documents` is created by C4's portion of `V1__init.sql`; ordering of `CREATE TABLE` statements within the migration must put `documents` before `llm_call_audit`.)

---

## 5. Acceptance criteria

1. `./gradlew build` passes with `0` Spotless / Checkstyle / PMD violations on all C3 sources.
2. `./gradlew test` passes; the suite covers:
   - `PipelineTriggerListener` consumes a `StoredDocumentIngested` event from the bus and invokes `ProcessingDocumentService.start(storedDocumentId, processingDocumentId)` exactly once with the event's payload.
   - Orchestrator runs all three steps in order on the happy path; `ProcessingCompleted` fires.
   - Orchestrator on text-extract failure: `current_step = FAILED`, `last_error` populated, `ProcessingStepChanged` with `currentStep: FAILED` fires.
   - Orchestrator on classify failure: same FAILED behavior.
   - Orchestrator on extract failure: same FAILED behavior.
   - `LlmExtractor.extract` retype path: `Document.reextractionStatus` cycles `NONE → IN_PROGRESS → NONE` on success and `NONE → IN_PROGRESS → FAILED` on error; `Document.extractedFields` UPDATE-replaces (not merge).
   - `ToolSchemaBuilder` is deterministic: serialize twice, byte-equal.
   - `LlmCallAuditWriter` writes a row for every call shape (classify / extract / initial / retype) with the correct FK populated and the other null; the CHECK constraint rejects the inverse.
   - `PromptLibrary.validate()` fails startup when a referenced prompt file is missing.
   - `EvalScorer` correctly aggregates classification accuracy and field accuracy on a synthetic 3-sample fixture.
   - Writers consistently denormalize `organization_id` from `StoredDocument.organizationId` (C3-R16).
3. `PipelineSmokeIT` passes when run with a valid `ANTHROPIC_API_KEY` (gated; not in CI).
4. `./gradlew evalRun` produces `eval/reports/latest.md` with the two aggregate-accuracy numbers and a per-sample table.
5. `processing_documents` and `llm_call_audit` tables exist in the migrated schema with all columns, FKs, indexes, and CHECK constraints in §4.
6. SSE consumers see `ProcessingStepChanged` events for every step transition including FAILED; `ProcessingCompleted` does not appear on the SSE stream.

---

## 6. Verification

```bash
./gradlew :backend:spotlessCheck :backend:checkstyleMain :backend:pmdMain
./gradlew :backend:test
ANTHROPIC_API_KEY=... ./gradlew :backend:integrationTest -Pgroup=smoke
ANTHROPIC_API_KEY=... ./gradlew :backend:evalRun
cat eval/reports/latest.md
```

Verify schema:

```bash
docker compose up -d postgres
./gradlew :backend:flywayMigrate
psql -h localhost -U docflow -c "\d processing_documents"
psql -h localhost -U docflow -c "\d llm_call_audit"
```

Verify event flow with a manual upload (end-to-end with `docker compose up`):
1. POST `/api/organizations/riverside/documents` with a sample PDF.
2. Open `/api/organizations/riverside/events` SSE stream in another terminal.
3. Observe `ProcessingStepChanged` events: `TEXT_EXTRACTING → CLASSIFYING → EXTRACTING`.
4. C4's `DocumentStateChanged` follows for the new `Review` state.

---

## 7. Error handling and edge cases

| Case | Behavior |
|---|---|
| PDFBox throws on `TEXT_EXTRACTING` (corrupt or password-protected PDF) | UPDATE `current_step = FAILED`, `last_error = "text-extract failed: <message>"`, emit `ProcessingStepChanged { currentStep: FAILED, error }`. No audit row (no LLM call was made). |
| Anthropic 429 / 5xx during classify | `LlmUnavailable`. Audit row inserted with `error` populated. UPDATE `current_step = FAILED`. No retry. |
| Anthropic 429 / 5xx during extract | `LlmUnavailable`. Audit row inserted. UPDATE `current_step = FAILED`. No retry (transient API failure surfaces; user re-uploads). |
| Anthropic timeout during classify or extract | `LlmTimeout`. Same handling as 429/5xx. |
| Classify response missing `tool_use` block | `LlmProtocolError`. Audit row inserted. No retry on classify (per §3.6). FAILED. |
| Classify returns `docType` not in the org's allowed enum | `LlmSchemaViolation`. Audit row inserted with `error`. No retry (forced tool choice + enum schema means this should be rare; if it happens we surface it loudly rather than silently re-prompting). FAILED. |
| Extract response missing `tool_use` block OR `input` fails JSON-schema validation | `LlmSchemaViolation`. Audit row inserted. **One retry** with the same params. If retry fails: second audit row + `LlmSchemaViolation` propagates → FAILED on initial pipeline; `ExtractionFailed` event on retype. |
| Retype: `Document` row already has `reextractionStatus = IN_PROGRESS` when a new retype request arrives | `LlmExtractor.extract` rejects with a typed error; C4 surfaces 409. (Concurrency guard.) |
| Retype: classify-step is skipped (C4 already supplied the new `docTypeId`); only extract runs. If extract fails, `Document.detectedDocumentType` is **not** changed and `extractedFields` is left untouched; `reextractionStatus = FAILED` is the only state change. | Test verifies. |
| Eval: a sample file in the manifest is missing on disk | `EvalRunner` fails fast with a clear error message naming the missing file. |
| Eval: live API rate-limits during the run | `EvalRunner` retries the single failed call once after a 5s backoff, then aborts the run with a partial report dumped to `eval/reports/latest.md` marked "INCOMPLETE". |
| Multiple uploads land for the same `StoredDocument` | Not possible: ingestion (C2) creates `StoredDocument` and `ProcessingDocument` atomically (C2 §C2-R-atomic). C3 trusts that invariant. |
| FAILED `ProcessingDocument` accumulation | Acknowledged. Cleanup is C7-R9's hypothetical cron, not implemented in the take-home. |

---

## 8. Migration / backwards compatibility

Greenfield. The `V1__init.sql` migration creates `processing_documents` and `llm_call_audit` from empty. There is no prior schema to migrate from and no live data to preserve.

If the schema needs to change post-V1 (e.g., adding a `latency_ms` column once we want it), it goes in a new `V<n>__<name>.sql` migration per `CLAUDE.md` § "Database schema". `V1__init.sql` is never edited after it is applied.

---
