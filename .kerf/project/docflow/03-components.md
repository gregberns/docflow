# DocFlow — Components

Decomposition of the work defined in `01-problem-space.md` and mapped in `02-analysis.md` into seven implementable components with concrete, testable requirements.

Each requirement is verifiable — by a test, a grep, a run of the app, or a build-tool check. Requirements describe WHAT each component does, not HOW it does it.

## Component overview

| # | Component | One-line role |
|---|---|---|
| **C1** | Config Layer | Declarative definitions of orgs, document-type field schemas, and workflows; loaded + validated at startup. |
| **C2** | Document Ingestion & Storage | Ingestion + storage of file references. Owns `StoredDocument` only — immutable file metadata, no lifecycle state. |
| **C3** | Processing Pipeline & Eval | Processing pipeline (text extraction, classification, extraction) + LLM call audit + eval. Owns `ProcessingDocument` and `llm_call_audit`. |
| **C4** | Workflow Engine | Workflow engine + processed-document storage. Owns `Document` (the processed, workflow-ready entity) and `WorkflowInstance`. |
| **C5** | API & Real-Time | Backend HTTP API (orgs, processing-documents, documents, actions) plus SSE endpoint for live processing + workflow events. |
| **C6** | Frontend (React SPA) | UI covering every state from the mockups; dynamic field form per doc-type; SSE consumer. |
| **C7** | Platform & Quality | docker-compose, DB migrations, build tooling, quality gates, seed data, done-means-green hook, README. |

Dependency DAG (arrow = "depends on"):

```
C7 (platform) — provides DocumentEventBus, build/deploy scaffolding
      │
C1 (config) ───────────► C2, C3, C4
C2 (ingest+storage) ───► C1 (for org validation); owns `stored_documents`
C3 (processing+eval)───► C1, C2 (reads StoredDocument bytes/text); owns `processing_documents` + `llm_call_audit`
C4 (workflow+document)► C1, C2 (reads StoredDocument for file ref), C3 (subscribes to ProcessingCompleted; calls LlmExtractor.extract for retype re-extract);
                         owns `documents` (processed) + `workflow_instances`
C5 (api+sse)        ───► C1, C2, C3, C4 (read-only projections via ProcessingDocumentSummary + DocumentView read-models)
C6 (frontend)       ───► C5

(C3 and C4 communicate via events, not direct calls from C3 back to C4.)
```

No cycles. Key boundary properties:

- **Each bounded context owns its own tables and its own writers.** C2 writes only to `stored_documents`. C3 writes only to `processing_documents` and `llm_call_audit`. C4 writes only to `documents` and `workflow_instances`. No context's writer is ever called from outside its context.
- **3-entity lifecycle split.** `StoredDocument` (C2) is the immutable file reference; it lives forever and carries no lifecycle state. `ProcessingDocument` (C3) is a transient pipeline record (minutes; deleted on success); only documents currently being processed have a row. `Document` (C4) is the processed, workflow-ready entity that lives forever. Most documents in the system are stable (processed); only a few are transient (processing). Modeling them all as one entity made every read pay for the rare case — separating them lets each table stay narrow and each query stay cheap.
- **Reads cross contexts via two narrow projections.** `ProcessingDocumentSummary` (small, in-flight) and `DocumentView` (full, processed) live in a shared `read-model` package. The dashboard list endpoint returns both in one response: `{ processing: ProcessingDocumentSummary[], documents: DocumentView[] }`.
- **C4 → C3** is a synchronous call only for retype re-extraction (`LlmExtractor.extract`); **C3 → C4** is via events only (no direct call). Initial pipeline completion flows C3 → event → C4.
- **`organizationId` is denormalized onto every tenant-scoped table** (`stored_documents`, `processing_documents`, `documents`, `workflow_instances`, `llm_call_audit`) so multi-tenant queries don't need cross-context JOINs for the common filter. Writers keep the value consistent with `StoredDocument.organizationId`; tested by unit test.
- **C7** is a peer — it provides the event bus and build/deploy scaffolding but nothing at runtime calls into it.

---

## C1. Config Layer

**Role.** Owns the declarative definitions that make the system multi-tenant: organizations, document types (with their field schemas), and workflows (with their stages and conditional branches). All other backend components read from C1 instead of hardcoding client- or type-specific logic.

### Requirements

1. **C1-R1.** Organizations are defined in a committed config file. Each org record has at minimum: stable identifier (slug), display name, icon identifier, and an ordered list of document-type identifiers the org supports.
2. **C1-R2.** Document-type field schemas are defined in committed config. Each schema specifies for each field: field name, data type (`string`, `date`, `decimal`, `enum`, `array`), whether required, and for enum fields the allowed values. Schema structure supports nested arrays-of-objects (e.g., `lineItems`, `materials`, `items`) with a sub-schema.
3. **C1-R3.** Workflows are defined in committed config keyed by `(organizationId, documentTypeId)`. Each workflow is an ordered list of **Stages** starting with `Review` (workflows do **not** include `Upload`, `Classify`, or `Extract` — those are processing-pipeline steps owned by C3, not workflow stages) and ending at a terminal stage (`Filed` or `Rejected`). Each stage definition carries: `id`, `displayName`, `kind ∈ {review, approval, terminal}`, **`canonicalStatus ∈ {AWAITING_REVIEW, AWAITING_APPROVAL, FILED, REJECTED}`**, and an optional `role` slot (see C1-R10). `canonicalStatus` is the domain-level status every query, filter, and stat rolls up to — see C1-R12. Workflows also declare the set of valid **Transitions** connecting stages.
4. **C1-R4.** A **Transition** is a first-class config concept with the shape `(fromStage, action, toStage, guard?)`, where `action` is one of `AutoAdvance`, `Approve`, `Reject`, `Flag`, `Resolve` and `guard` is an optional **StageGuard** (a predicate over `Document.extractedFields` that must evaluate true for the transition to fire). The Ironworks Lien Waiver "unconditional skip" is expressed as a transition from `Review → Filed` with `guard: waiverType == "unconditional"`, plus a transition from `Review → Project Manager Approval` with the inverse guard. Service code never inspects stage names or client slugs to decide transitions — it iterates the configured transitions and evaluates their guards.
4a. **C1-R4a.** A **StageGuard** references field values by JSON-path or similar identifier and compares to a literal; complex boolean combinations are not required for the spec's single conditional case. Exact syntax is deferred to Pass 4 research, but the shape (predicate-over-field-values) is committed.
5. **C1-R5.** Config is validated at application startup. Startup fails with a clear error if: a referenced doc-type doesn't exist, a workflow references an unknown stage, required field metadata is missing, an enum field has duplicate values, or the workflow lacks a terminal stage.
6. **C1-R6.** Config exposes typed read APIs to other components: `getOrganization(orgId)`, `listOrganizations()`, `getDocumentTypeSchema(orgId, docTypeId)`, `listDocumentTypes(orgId)`, `getWorkflow(orgId, docTypeId)`, `getAllowedDocTypes(orgId)`. No other component constructs these objects directly.
7. **C1-R7.** Grep test: searching the backend source tree for literal stage strings like `"Manager Approval"`, `"Attorney Approval"`, or client slugs like `"pinnacle"`, `"riverside"`, `"ironworks"` returns zero hits outside config files and tests. Enforced by a build check.
8. **C1-R8.** Config seeds three organizations (Riverside Bistro, Pinnacle Legal Group, Ironworks Construction) with all nine `(org, docType)` field schemas and all nine workflows per the spec (`02-analysis.md` §1.1 and §1.2).
9. **C1-R9.** Adding a fourth organization with one new doc-type and one new workflow requires only new seed-fixture YAML files (or a new Flyway migration) plus restart; no changes to C2, C3, C4, C5, or C6 source code. **Verification:** a seeder unit test loads a fourth-org YAML fixture and asserts the catalog APIs (`getOrganization`, `getDocumentTypeSchema`, `getWorkflow`) return the new entries. The full end-to-end pipeline run against a synthetic fourth org is out of scope for the take-home — the unit test is sufficient proof-of-extensibility for the spec's three real clients.
10. **C1-R10.** Each approval stage definition carries an optional `role` descriptor — a human-readable identifier ("Attorney", "Manager", "Project Manager", etc.) distinct from the stage name. The exact shape (string tag vs. Role entity with FK) is decided in Pass 4 research; C1-R3's schema reserves the slot. When present, C5 surfaces the role in the document detail payload and C6 displays it alongside the stage label.
12. **C1-R12. Canonical `WorkflowStatus` enum is a first-class domain concept.** The enum values are `AWAITING_REVIEW`, `FLAGGED`, `AWAITING_APPROVAL`, `FILED`, `REJECTED` (5 values). Every stage in config declares a `canonicalStatus` mapping (except `FLAGGED`, which is never declared on a stage — it's a runtime override applied when a Review stage has a non-null `workflowOriginStage`). The domain vocabulary is: stages are org-specific and many; `WorkflowStatus` is canonical and few. All filtering, stats aggregation, and domain-level queries run in terms of `WorkflowStatus`. Per-org stage names exist only for write-time routing (whose workflow's stage id is this?) and for UI display (show "Attorney Approval" next to the row). This is enforced: there is no API endpoint or query that filters documents by per-org stage name.

    **Note:** `ProcessingDocument` (C3) has its own `currentStep` enum (`TEXT_EXTRACTING, CLASSIFYING, EXTRACTING, FAILED`) — that's a separate concept owned by C3 and never mixed with `WorkflowStatus`. The processing pipeline finishes before a `Document` (and its `WorkflowInstance`) is created; once a workflow exists, the processing step is by definition complete.

11. **C1-R11.** **Client data lives in the database; YAML is only a seed fixture.**
    - Flyway migrations create the client-data tables empty: `organizations`, `document_types` (with a `field_schema JSONB` column), `workflows`, `stages` (ordinal-preserving), `transitions` (with `guard JSONB` or `guard_field/op/value` columns).
    - On application startup, a seeder checks whether the reference tables are empty. If `SELECT count(*) FROM organizations = 0`, it parses the YAML seed fixtures under `src/main/resources/seed/` and INSERTs rows. Otherwise it is a no-op.
    - Once seeded, the database is authoritative. Subsequent startups do not read YAML — the catalogs (C1-R6) are populated from the DB.
    - Changing client data in production is done by writing a new Flyway migration (SQL `INSERT`/`UPDATE`/`DELETE`). Changing in dev can also be done by wiping the client-data tables and restarting with edited YAML.
    - `StoredDocument.organizationId` (C2), `ProcessingDocument.organizationId` (C3), `Document.organizationId` (C4), and `Document.detectedDocumentType` (C4) all FK to these tables and enforce referential integrity. `WorkflowInstance.currentStageId` is a composite FK to `stages`.

### Dependencies
- None (foundational).

### Interfaces provided
- `OrganizationCatalog` read API (R6).
- `DocumentTypeSchema` read API (R6).
- `WorkflowCatalog` read API (R6, R4).

### Open items (resolved in Pass 4 Research)
- Config file format: YAML vs. DOT/graph notation vs. another format.
- How conditional transitions (Lien Waiver unconditional skip) are expressed syntactically.
- Whether config is load-time only or hot-reloadable.
- Load-time validation strategy (hand-rolled validator vs. JSON-schema-driven).
- **Approval-role modeling.** Whether each approval stage in config carries a `role` tag (string), a FK to a first-class `Role` entity, or nothing (stages only). Affects C1's schema and C6's summary display; see `01-problem-space.md` Deferred.

---

## C2. Document Ingestion & Storage

**Role.** Accepts uploaded files, persists them, and exposes a stable file reference (`StoredDocument`). The "blob + immutable metadata" seam for the rest of the system. C2 does **not** extract text, classify, or track lifecycle state — that all lives in C3 (processing pipeline) and C4 (workflow).

### Requirements

1. **C2-R1.** Accepts PDF and image uploads (`application/pdf`, `image/png`, `image/jpeg`) via an unauthenticated HTTP endpoint (auth is out of scope per the problem space). Rejects other MIME types with 415 and a structured error body.
2. **C2-R2.** Stores the uploaded file on the local filesystem under a configurable path. The stored file's on-disk path is an implementation detail; the public interface is a stable `storedDocumentId` exposed via C5.
3. **C2-R3.** *(Reserved — text extraction has moved to C3 and now lives on `ProcessingDocument.rawText`. C2 no longer extracts text.)*
4. **C2-R4.** Persists the ingestion aggregate as one row. `StoredDocument` is **immutable** — once written, it is never updated.

    - **`StoredDocument`** (C2's sole aggregate root): `id`, `organizationId` (FK → `organizations`), `uploadedAt`, `sourceFilename`, `mimeType`, `storagePath`. **Nothing else** — no `rawText`, no `textExtractionStatus`, no `currentStage`, no flag fields, no classification, no extraction. Those concepts live in C3 (`ProcessingDocument`) and C4 (`Document`, `WorkflowInstance`).

    Upon successful upload, C2 transactionally INSERTs a `StoredDocument` row **and** an initial `ProcessingDocument` row (with `currentStep = TEXT_EXTRACTING`), then signals C3 to start the pipeline. See C3 for processing details.

4a. **C2-R4a.** Client-data reference tables (`organizations`, `document_types`, `workflows`, `stages`, `transitions`) are created by Flyway (C7-R3) and populated by the C1-R11 seeder on first install. `StoredDocument.organizationId` FKs to `organizations.id`. Other FKs (`WorkflowInstance.currentStageId` composite FK to `stages`; `Document.detectedDocumentType` FK to `document_types`) live on the tables in their respective contexts, not on `StoredDocument`.
5. **C2-R5.** `StoredDocument` rows are scoped to the organization they were uploaded for. Queries by org return only that org's stored documents. No global "all stored documents" endpoint exists.
6. **C2-R6.** Stored documents are never deleted through the runtime API. (Deletion is explicitly out of scope per the problem space.)
7. **C2-R7.** The storage seam is isolated behind a `StoredDocumentStorage` interface with a single filesystem implementation. Swapping to S3 later requires adding a new implementation and changing no consumer code.
8. **C2-R8.** *(Removed — stage advancement is no longer C2's concern. The processing pipeline (C3) drives `ProcessingDocument.currentStep` advancement; the workflow engine (C4) drives `WorkflowInstance.currentStageId` advancement after C3 completes.)*
9. **C2-R9.** Persisted schema managed by Flyway. C2 owns only `stored_documents`:
    - `stored_documents(id PK, organization_id FK, uploaded_at, source_filename, mime_type, storage_path)`.
    - Index: `stored_documents(organization_id, uploaded_at DESC)` — list-by-org queries.
    - FKs live on the other tables pointing to `stored_documents.id`: `processing_documents.stored_document_id`, `documents.stored_document_id` (unique), `llm_call_audit.stored_document_id` (nullable — audit may also reference a `Document` post-processing).
9a. **C2-R9a.** `storagePath` on `StoredDocument` is derived deterministically from `StoredDocument.id` (e.g., `{storageRoot}/{id}.bin` with mime-type inferred from `StoredDocument.mimeType`). Orphan detection (FS file with no DB row) and missing-file detection (DB row with no FS file) are straightforward to diagnose. `GET /api/documents/{id}/file` (C5-R5) returns 404 with a typed error if the file is missing; the upload flow writes the file before the DB row is committed so crash-recovery can detect and clean stray files, not stray rows.

### Dependencies
- **C1** (to validate `organizationId` against the catalog).

### Interfaces provided

C2 exposes only ingestion-scoped interfaces. Processing-pipeline state is C3's; workflow state is C4's.

- `StoredDocumentIngestionService.upload(orgId, file) → storedDocumentId` — orchestrates: sniff MIME, reject bad types, save bytes via `StoredDocumentStorage`, then in one DB transaction INSERT `StoredDocument` + INSERT initial `ProcessingDocument` (with `currentStep = TEXT_EXTRACTING`), then signal C3 to begin the pipeline. Used by C5.
- `StoredDocumentReader.get(storedDocumentId) → StoredDocument` — C2's raw aggregate. Used by C3 (to read bytes for text extraction / LLM calls) and by read-models.
- `StoredDocumentStorage.save(id, bytes) / load(id) / delete(id)` — filesystem-backed seam, swappable for S3.

C2 **does not** expose a `listDocuments` endpoint for the dashboard — that read crosses contexts and lives in the `DocumentView` / `ProcessingDocumentSummary` read-models (see C3 and C4 interfaces; C5 composes them).

---

## C3. Processing Pipeline & Eval

**Role.** The processing surface: orchestrates the text-extract → classify → extract pipeline against an in-flight `ProcessingDocument`, stores prompts as named resource files, records a lightweight audit row per LLM call, and exposes an eval harness that scores the pipeline against a single labeled sample set. C3 owns the in-flight pipeline state; once processing completes, it hands off to C4 (which creates the processed `Document` + `WorkflowInstance`). The `ProcessingDocument` row is left in place — cleanup is deferred to a hypothetical cron not implemented in the take-home.

### The `ProcessingDocument` entity

`ProcessingDocument` is C3's transient aggregate root — it exists only while a document is being processed (typically minutes), and is deleted when the pipeline completes successfully. Failed processing rows stay in the DB indefinitely so the user can retry.

Schema:
- `id` (PK)
- `stored_document_id` FK → `stored_documents`
- `organization_id` (denormalized for tenant queries)
- `created_at`
- `current_step ∈ {TEXT_EXTRACTING, CLASSIFYING, EXTRACTING, FAILED}`
- `raw_text` (nullable; populated when text-extract step completes)
- `last_error` (nullable; populated when `current_step = FAILED`)
- `attempt_count` (int; bumped on retry)

Index: `(organization_id, created_at DESC)` for the dashboard's "processing" section list.

### Requirements

1. **C3-R1.** `classifyDocument(processingDocumentId)` calls the Anthropic API with the document's content (text from `ProcessingDocument.rawText`, the original PDF, or both per the modality decided in research), a system prompt, and the set of allowed doc-types for the document's org (from C1). Returns a `detectedDocumentType` that is a member of that allowed set. On API failure, sets `ProcessingDocument.currentStep = FAILED` with `lastError` populated and emits `ProcessingStepChanged` with `currentStep: FAILED` and the `error` payload.
2. **C3-R2.** `extractFields(processingDocumentId | documentId, docTypeId)` calls the Anthropic API using tool use: the tool's input JSON schema is derived from the doc-type's field schema in C1. `tool_choice` forces the model to call that specific tool. The returned `tool_use.input` is validated against the schema. If the model returns an invalid structure, the call is retried once; a second failure surfaces a typed error.
    - During the **initial pipeline**, the result is staged on the `ProcessingDocument` and rolled into `Document.extractedFields` at completion time.
    - During **retype re-extraction** (invoked by C4 — see C4-R6), the result is written directly to `Document.extractedFields` via UPDATE (no `ProcessingDocument` involvement).
3. **C3-R3.** Pipeline steps are separate HTTP calls — a classification failure does not prevent a later retry of classification, and an extraction failure does not require re-running classification. Retry resumes from `ProcessingDocument.currentStep`.
4. **C3-R4.** Retype re-extraction (C4-driven, see C4-R6): replaces `Document.extractedFields` via UPDATE. The old field payload is not merged or preserved — prior values are not recoverable from the audit table either, since the take-home's `llm_call_audit` keeps metadata only (see C3-R5a). Accepted simplification for the take-home.
5. **C3-R5.** Prompts live as named resource files at `src/main/resources/prompts/<id>.txt`. There is no in-app versioning scheme — prompt history is tracked via git. The eval (C3-R8) reports against whatever prompts are committed at the run's commit.
5a. **C3-R5a.** LLM-call audit persists to a dedicated `llm_call_audit` table, one row per classify or extract invocation, with columns: `id`, `stored_document_id` (FK; always populated — every audited call has an underlying file), `processing_document_id` (FK, nullable — populated for initial-pipeline calls), `document_id` (FK, nullable — populated for retype re-extraction calls), `organization_id`, `call_type ∈ {classify, extract}`, `model_id`, `error` (nullable), `at`. Index on `(stored_document_id, at DESC)`.
6. **C3-R6.** The tool input schema sent to Anthropic is generated deterministically from C1's doc-type schema. A given schema always produces the same tool definition (stable field ordering, stable enum ordering).
7. **C3-R7.** The sample corpus is treated as a single labeled set. A committed manifest maps each sample filename to its known-correct `docType` and known-correct `extractedFields`. No tune/verify split (the take-home does not iterate on prompts at the bar where a holdout matters).
8. **C3-R8.** An eval command runs the classify + extract pipeline against the labeled samples and produces a markdown report with **aggregate classification accuracy** and **aggregate extract field accuracy** (mean exact-match across all fields and samples). Per-org/per-doc-type/per-field breakdowns are out of scope for the take-home — the aggregate numbers are sufficient to demonstrate the pipeline meets a defensible bar.
9. **C3-R9.** The eval hits the live Anthropic API. There is no recorded-replay mode and no committed responses directory. CI does not run the eval (it is run on demand, locally, before submission).
10. *(removed — eval has no per-run prompt-version metadata; prompt state is implicit in the git commit at run time.)*
11. *(removed — no recorded responses directory; see C3-R9.)*
12. **C3-R12.** A single happy-path integration smoke test exercises the classify + extract pipeline end-to-end against the live Anthropic API for one sample document. Comprehensive HTTP-seam coverage (retry-then-succeed, 429/5xx error paths, invalid response shapes) is out of scope for the take-home — unit tests on the orchestrator + manual exercise during development cover those branches.
13. **C3-R13.** **Retype re-extraction is an UPDATE on `Document`, not a new row.** When C4 invokes `LlmExtractor.extract(documentId, docTypeId)` for retype, the writer UPDATEs `Document.extractedFields` (and `Document.detectedDocumentType` if changed) in place. Prior extracted values are not preserved beyond the audit row's metadata (`call_type`, `model_id`, `at`) — this is an accepted simplification for the take-home; production would persist request/response payloads on `llm_call_audit` for full recoverability. `Document.reextractionStatus` enum tracks the in-flight state (`NONE` → `IN_PROGRESS` → back to `NONE` on success, or `FAILED` on error). A test verifies: invoking retype on a Document moves `reextractionStatus` through the expected sequence and replaces `extractedFields` atomically when the LLM call returns.
14. **C3-R14.** **Pipeline orchestration.** `ProcessingPipelineOrchestrator` runs the steps in order on a `ProcessingDocument`:
    1. `currentStep = TEXT_EXTRACTING` → run PDFBox (or equivalent), populate `rawText`.
    2. `currentStep = CLASSIFYING` → call Anthropic classify; record audit row; on success, capture `detectedDocumentType` (in-memory, not on the row — it's transient until terminal).
    3. `currentStep = EXTRACTING` → call Anthropic extract with tool schema for `detectedDocumentType`; record audit row; on success, capture `extractedFields`.
    4. **Terminal success:** emit `ProcessingCompleted { storedDocumentId, processingDocumentId, organizationId, detectedDocumentType, extractedFields, rawText, at }` on the `DocumentEventBus` — internal-only, not on the SSE stream. C4 subscribes; in one transaction C4 INSERTs `Document` (with `processedAt = now`, `reextractionStatus = NONE`) + INSERTs `WorkflowInstance` (with `currentStageId = Review`). The `ProcessingDocument` row is left in place; cleanup is deferred to a hypothetical cron-style cleanup not implemented in the take-home (see Production Considerations, C7-R9). C4 emits `DocumentStateChanged` for the new `Review` state — that is the SSE-visible event.
    5. **Failure at any step:** UPDATE `ProcessingDocument.currentStep = FAILED`, set `lastError`, emit `ProcessingStepChanged { storedDocumentId, processingDocumentId, organizationId, currentStep: FAILED, error: { code, message }, at }`. The `ProcessingDocument` row stays in the DB — the UI surfaces a "Processing failed — Retry" affordance (C6-R8). No `Document` row is created for failed processing.
15. **C3-R15.** *(Removed — `document_classifications` and `document_extractions` tables no longer exist. `Document.detectedDocumentType` and `Document.extractedFields` hold current values; `llm_call_audit` holds history.)*
16. **C3-R16.** Tenant-awareness: `organization_id` is denormalized onto `processing_documents`, `llm_call_audit`, `documents` (C4), and `workflow_instances` (C4-R11). Consistency with `StoredDocument.organization_id` is guaranteed by the writers that set them (they always read the parent `StoredDocument` first). A unit test asserts the writers produce consistent `organization_id` values.

### Dependencies
- **C1** (allowed doc-types, field schemas).
- **C2** (reads `StoredDocument` for file bytes + identity).

### Interfaces provided
- `ProcessingDocumentService.start(storedDocumentId)` — kicks off the pipeline. Called by C2 after upload writes the initial `ProcessingDocument` row.
- `ProcessingDocumentReader.get(id) → ProcessingDocument` and `.list(orgId) → ProcessingDocumentSummary[]` — read-side surface; the list form powers the dashboard's processing section (C5-R3).
- `ProcessingDocumentService.retry(processingDocumentId)` — resumes a `FAILED` processing run; resets `currentStep` to the failed step and re-runs forward.
- `LlmExtractor.extract(documentId, docTypeId)` — invoked by C4 for **retype re-extraction**; UPDATEs `Document.extractedFields` and `Document.detectedDocumentType`; emits `ExtractionCompleted { documentId }` (or `ExtractionFailed`). Used by C4-R6.
- `EvalRunner.run(options) → MarkdownReport` — dev-tool surface, not exposed over HTTP.

C3 never calls C4 directly. C4 learns of pipeline completion by subscribing to `ProcessingCompleted` on the event bus, and learns of retype-extraction completion by subscribing to `ExtractionCompleted`.

### Sub-package layout

- `pipeline/` — `ProcessingPipelineOrchestrator`, retry coordination, step implementations.
- `prompts/` — prompt storage / versioning.
- `audit/` — `LlmCallAudit` writer + reader.
- `eval/` — `EvalRunner` + recorded-response replay.
- `anthropic/` — SDK wrapper.

(Previously-proposed `classify/` and `extract/` sub-packages with their own writers are removed — neither classification nor extraction has a dedicated table; results live on `Document` and the audit row carries the call detail.)

### Open items (resolved in Pass 4 Research)
- Input modality: text vs. native PDF vs. hybrid.
- Prompt management storage + versioning scheme.

### Fixed decisions (no longer open)
- Model ID: `claude-sonnet-4-6` for both classify and extract. Sourced from `AppConfig.llm.modelId` per C7-R13. Swap is a single config change if the eval pushes us toward Haiku or Opus.

---

## C4. Workflow Engine

**Role.** The state machine **and** the home of the processed-document entity. Owns `Document` (the workflow-ready, post-processing entity) and `WorkflowInstance`. Reads workflow config from C1, processes actions against documents, enforces transitions including the Lien Waiver conditional, listens for `ProcessingCompleted` from C3 to materialize new `Document` + `WorkflowInstance` rows, and emits state-change events consumed by C5.

### The `Document` entity (C4-owned)

`Document` is C4's processed, workflow-ready entity. It is created when the C3 pipeline completes and lives forever after.

Schema:
- `id` (PK)
- `stored_document_id` FK → `stored_documents` (unique — 1:1 with the underlying file)
- `organization_id` (denormalized for tenant queries)
- `detected_document_type` FK → `document_types`
- `extracted_fields` JSONB
- `raw_text` (the text extracted by C3 during processing, transferred to `Document` on completion)
- `processed_at` (timestamp of pipeline completion)
- `reextraction_status ∈ {NONE, IN_PROGRESS, FAILED}` — tracks retype re-extraction state (no boolean flags)

Indexes: `(organization_id, processed_at DESC)`; `(stored_document_id)` unique.

### Requirements

1. **C4-R1.** Given a document's `WorkflowInstance.currentStageId` and an action, `applyAction(documentId, action, payload)` looks up the matching `Transition` in the configured workflow, evaluates its optional `StageGuard` against `Document.extractedFields`, and persists both the new `currentStageId` **and** the derived `currentStatus` through `WorkflowInstanceWriter.advanceStage`. Rejects invalid actions with a typed error (e.g., `Approve` on a `FILED` document returns `InvalidAction`). All stage-name knowledge comes from C1 at runtime — C4 source contains zero literal stage strings.
2. **C4-R2.** `Approve` from any approval stage advances to the next stage in the configured workflow.
3. **C4-R3.** `Approve` from `Review` selects the first `Transition` whose `fromStage == Review`, whose `action == Approve`, and whose `guard` evaluates true (or is absent). Guards read directly from `Document.extractedFields` (no separate extraction table). The target stage is that transition's `toStage`. Test case: Ironworks Lien Waiver with `extractedFields.waiverType == "unconditional"` advances Review → Filed (matching the guarded transition), while `== "conditional"` advances Review → Project Manager Approval (matching the inverse-guarded transition).
4. **C4-R4.** `Reject` from `Review` advances to `Rejected` (terminal). `Reject` from any other stage is invalid.
5. **C4-R5.** `Flag(comment)` from any approval stage: requires non-empty `comment` (rejects empty/whitespace), calls `WorkflowInstanceWriter.setFlag(documentId, originStage = <current stage>, comment = <comment>)`, and advances `currentStage` to `Review`.
6. **C4-R6.** `Resolve` from `Review` (when `WorkflowInstance.workflowOriginStage` is set): if the user did not change `detectedDocumentType` since the flag, calls `WorkflowInstanceWriter.clearFlag(documentId)` and advances back to the stored `workflowOriginStage`. If the user did change the type:
    - Set `Document.reextractionStatus = IN_PROGRESS` (UPDATE) and emit `DocumentStateChanged` with the new `reextractionStatus` (currentStage stays `Review`).
    - Invoke `C3.LlmExtractor.extract(documentId, newDocTypeId)` (asynchronous).
    - On `ExtractionCompleted` event (internal): UPDATE `Document.detectedDocumentType` and `Document.extractedFields`, set `reextractionStatus = NONE`, call `clearFlag` (which leaves `currentStage = Review` since workflow stays in Review post-retype), emit `DocumentStateChanged` with `reextractionStatus = NONE`.
    - On `ExtractionFailed` (internal): set `reextractionStatus = FAILED`, leave `Document` otherwise unchanged, emit `DocumentStateChanged` with `reextractionStatus = FAILED`.

    Re-extraction does **not** go through `ProcessingDocument` — that entity is only for the initial pipeline. The retype lifecycle is communicated to the UI exclusively via `reextractionStatus` field changes on `DocumentStateChanged`; there are no separate retype-specific events.
7. **C4-R7.** Terminal states (`Filed`, `Rejected`) have no outgoing transitions. All action calls on a terminal document return `InvalidAction`.
8. **C4-R8.** Every state change emits a `DocumentStateChanged { documentId, storedDocumentId, organizationId, currentStage, currentStatus, reextractionStatus, action?, comment?, at }` event consumed by C5's SSE publisher. No persistent stage-history audit table — `WorkflowInstance` is the source of truth for current state, and `llm_call_audit` retains LLM-call history. Stage-history beyond "current" is out of scope for the take-home (see Production Considerations, C7-R9). The event does not carry `previousStage`/`previousStatus`; the SSE consumer (C6-R5) refetches the dashboard list on any event rather than diffing.
9. **C4-R9.** Property-based tests for the engine verify: (a) every valid workflow reaches a terminal state from any non-terminal stage under the sequence of "always approve" actions; (b) flag→resolve with no type change returns to the originating approval stage; (c) flag→resolve with type change results in the document being in `Review` with no origin stage; (d) terminal states have zero outgoing transitions; (e) stage guards are honored — generated workflows with guarded transitions route correctly for each guard valuation.
9a. **C4-R9a.** `WorkflowEngine` accepts a `WorkflowCatalog` via constructor injection (not a static singleton). Property-based tests supply a generator-backed `WorkflowCatalog` that synthesizes random-shape workflows so C4-R9 is truly property-based, not limited to the nine seeded workflows.
9b. **C4-R9b.** **Flag-origin restoration matrix.** Example-based tests exercise every approval stage in every client as an origin: for each of the eight approval-stage names across the three clients, test (flag → resolve without type change → returns to that origin stage) and (flag → resolve with type change → stays in `Review`, origin cleared, re-extraction fires). The test is parameterized by the seeded workflow config.
10. **C4-R10.** The engine grep test: source code contains no literal stage strings (`"Manager Approval"`, `"Filed"`, etc.) and no client slugs. All stage names come from C1 at runtime. Enforced as a Gradle check task (see C7-R5).
11. **C4-R11.** `WorkflowInstance` schema: `id, document_id FK → documents (unique), organization_id, current_stage_id (composite FK to stages), current_status WorkflowStatus, workflow_origin_stage (nullable), flag_comment (nullable), updated_at`. `organization_id` denormalized per the multi-tenant discipline (C3-R16). `current_status` is computed at write time by this rule: if `currentStage.kind = 'review'` AND `workflow_origin_stage IS NOT NULL` then `FLAGGED`; otherwise `currentStage.canonicalStatus`. Indexes: `(organization_id, current_status, updated_at DESC)` — primary dashboard filter path; `(document_id)` unique. **`WorkflowInstance` always starts at `Review`** when created (no Upload/Classify/Extract pre-stages); the engine never sees a workflow in a "processing" status because processing finishes before C4 creates the row.
12. **C4-R12. Canonical status is C4's responsibility.** Every call to `WorkflowInstanceWriter.advanceStage`, `setFlag`, `clearFlag` sets both `currentStageId` and `currentStatus`. A unit test asserts that for every seeded workflow stage, the computed `currentStatus` matches the stage's `canonicalStatus` (or `FLAGGED` in the review+origin case). A property-based test generates random workflows and asserts the rule holds across all transitions.
13. **C4-R13. ProcessingCompleted handoff.** C4 subscribes to `ProcessingCompleted` on the `DocumentEventBus`. On receipt, in **one DB transaction**:
    1. INSERT `Document` (id, stored_document_id, organization_id, detected_document_type, extracted_fields, raw_text, processed_at = event.at, reextraction_status = NONE).
    2. INSERT `WorkflowInstance` (document_id = new id, organization_id, current_stage_id = the workflow's `Review` stage id for `(orgId, detectedDocumentType)` from C1, current_status = AWAITING_REVIEW, workflow_origin_stage = NULL, flag_comment = NULL, updated_at = now).
    3. Emit `DocumentStateChanged { documentId, storedDocumentId, organizationId, currentStage = Review, currentStatus = AWAITING_REVIEW, reextractionStatus = NONE, at }`.

    The `ProcessingDocument` row is left in place; deletion is deferred to a hypothetical cleanup cron not implemented in this take-home. The dashboard query (C5-R3) filters `ProcessingDocument` rows that already have a matching `Document` so completed-but-undeleted rows do not appear in the in-flight section.

    A test verifies the handoff is atomic (no partial state on transaction failure).

### Dependencies
- **C1** (workflow definitions).
- **C2** (reads `StoredDocument` for cross-references; never writes to it).
- **C3** (subscribes to `ProcessingCompleted` for initial materialization; synchronous invocation of `LlmExtractor.extract` for retype re-extraction; C4 does **not** wait for the extract call's HTTP completion — it subscribes to `ExtractionCompleted` / `ExtractionFailed` events for state transitions).

### Interfaces provided
- `WorkflowEngine.applyAction(documentId, action, payload) → DocumentView | error` (used by C5).
- `DocumentReader.get(documentId) → Document` and `.list(orgId, filter) → DocumentView[]` — read-side surface for processed documents.
- `WorkflowInstanceWriter.advanceStage / setFlag / clearFlag` — internal to C4; sets both `currentStageId` and `currentStatus` atomically per C4-R12. Operates on the C4-owned `Document` / `WorkflowInstance` pair.
- `DocumentEventBus` (provided by C7) — C4 publishes only `DocumentStateChanged` (retype lifecycle is communicated via the `reextractionStatus` field on this same event, not via separate retype events). C5 subscribes for SSE fan-out. C3 publishes `ProcessingStepChanged` (SSE-visible; carries `currentStep` including `FAILED` with `error`), `ProcessingCompleted` (internal-only), `ExtractionCompleted` and `ExtractionFailed` (internal-only, for retype). C4 subscribes to `ProcessingCompleted` (for initial materialization, C4-R13) and to `ExtractionCompleted` / `ExtractionFailed` (for retype completion, C4-R6).

---

## C5. API & Real-Time

**Role.** The HTTP + SSE boundary. Exposes REST endpoints for orgs, documents, and actions, and a Server-Sent Events endpoint for live state changes.

### Requirements

1. **C5-R1.** `GET /api/organizations` returns the list of organizations (id, name, icon, docTypes) from C1. `GET /api/organizations/{orgId}` returns a single org with its workflows and field schemas sufficient for the frontend to render filter dropdowns and the review form.
2. **C5-R2.** `POST /api/organizations/{orgId}/documents` accepts a multipart upload and returns a document id. Rejects unknown orgId with 404 and unsupported MIME types with 415.
3. **C5-R3.** `GET /api/organizations/{orgId}/documents` returns the dashboard list as a single response with two arrays:

    ```json
    {
      "processing": ProcessingDocumentSummary[],
      "documents": DocumentView[]
    }
    ```

    - `processing` — every in-flight `ProcessingDocument` for the org, projected to `{ processingDocumentId, storedDocumentId, sourceFilename, currentStep, lastError, createdAt }`. Filtered to exclude rows that already have a matching `Document` by `storedDocumentId` (since the take-home does not implement post-success cleanup of `ProcessingDocument` rows; see C4-R13). Always returned in `createdAt DESC` order. Frontend renders this as the small "in flight" section at the top of the dashboard (C6-R2).
    - `documents` — processed documents (joined `Document` + `WorkflowInstance` + stage display metadata) filtered by query params **`status`** (canonical `WorkflowStatus` value) and `docType`. **No per-org stage-name filter exists** — the API speaks only the canonical status vocabulary per C1-R12. Sorted by `updatedAt DESC` within the filtered set. Returns up to a soft cap (e.g., 200 rows). Pagination is out of scope for the take-home — the sample corpus stays well under the cap.

    Filter params apply only to `documents`; `processing` is always returned in full because the in-flight set is small.
4. **C5-R4.** `GET /api/documents/{documentId}` returns the `DocumentView` projection for a **processed** document: id, org, sourceFilename, mimeType, uploadedAt (from `StoredDocument`), processedAt, rawText (or excerpt), currentStageId, currentStageDisplayName (from stages config), currentStatus (canonical), workflowOriginStage, flagComment, detectedDocumentType, extractedFields, reextractionStatus. Read-only; cross-context read-model joining `Document` (C4) + `WorkflowInstance` (C4) + `StoredDocument` (C2) + stage display metadata (C1).

    There is no detail endpoint for in-flight `ProcessingDocument` — the dashboard list (C5-R3) carries everything the UI shows for processing rows, and processing rows are not navigable (C6-R3, C6-R7).
5. **C5-R5.** `GET /api/documents/{documentId}/file` streams the raw uploaded file bytes with the correct `Content-Type`. HTTP range requests (`Accept-Ranges: bytes`) are out of scope for the sample corpus (all samples are single-page, small PDFs); if a future browser-side viewer requires range support it is a drop-in addition, not a structural change.
6. **C5-R6.** `POST /api/documents/{documentId}/actions` accepts a **discriminated union** keyed on `action`:
    - `{ "action": "Approve" }` — no payload.
    - `{ "action": "Reject" }` — no payload.
    - `{ "action": "Flag", "comment": string }` — `comment` required, non-empty.
    - `{ "action": "Resolve" }` — no payload.

    Backend validates the shape before calling `WorkflowEngine.applyAction`. Returns the updated `DocumentView` or a structured error. `Flag` with missing/empty `comment` returns `VALIDATION_FAILED`.
7. **C5-R7.** Review-stage editing is split into two endpoints to avoid conflating save with reclassify:
    - `PATCH /api/documents/{documentId}/review/fields` — accepts edited `extractedFields` payload; validates shape against the current `detectedDocumentType`'s schema; persists via direct UPDATE on `Document.extractedFields`. Document stays in `Review`. No side effects besides the save.
    - `POST /api/documents/{documentId}/review/retype` — accepts `{ "newDocumentType": string }`; validates that the target type is in the current org's allowed list; triggers C4 (which orchestrates re-extraction per C4-R6 — sets `Document.reextractionStatus = IN_PROGRESS`, invokes `C3.LlmExtractor.extract`, listens for `ExtractionCompleted`); returns `202 Accepted` with `{ reextractionStatus: "IN_PROGRESS" }`. The document's `currentStage` stays `Review` throughout — the `reextractionStatus` field signals the in-flight retype to the UI; on completion `reextractionStatus` returns to `NONE`.
8. **C5-R8.** `GET /api/organizations/{orgId}/stream` is **one SSE endpoint per org** carrying exactly two event types. Internal-only `DocumentEventBus` events (`ProcessingCompleted`, `ExtractionCompleted`, `ExtractionFailed`) are not fanned out to clients — the UI reacts to the `DocumentStateChanged` events that C4 emits in response.
    - `ProcessingStepChanged` — `{ storedDocumentId, processingDocumentId, organizationId, currentStep, error?: { code, message }, at }`. `error` is populated when `currentStep == FAILED`.
    - `DocumentStateChanged` — `{ documentId, storedDocumentId, organizationId, currentStage, currentStatus, reextractionStatus, action?, comment?, at }`. The retype lifecycle is communicated by emitting this event with updated `reextractionStatus` (`IN_PROGRESS` → `NONE` on success, `IN_PROGRESS` → `FAILED` on failure); `currentStage` stays `Review` throughout. The event does not include `previousStage`/`previousStatus` — the consumer (C6-R5) refetches the dashboard list on any event rather than diffing.

    Each SSE frame carries an `id:` (monotonically increasing per connection), an `event:` line naming the event type, and the server sends an initial `retry: 5000` hint. `Last-Event-ID` resume is out of scope — clients reconnect and rely on initial-state rehydration from the list endpoint.
9. **C5-R9.** All endpoints return structured error bodies `{ code, message, details? }` for 4xx/5xx responses. Validation errors return 400 with field-level `details`.
9a. **C5-R9a.** Error codes are an enumerated set. Minimum vocabulary:
    - `UNKNOWN_ORGANIZATION` (404), `UNKNOWN_DOCUMENT` (404), `UNKNOWN_PROCESSING_DOCUMENT` (404), `UNKNOWN_DOC_TYPE` (404).
    - `UNSUPPORTED_MEDIA_TYPE` (415), `INVALID_FILE` (400).
    - `VALIDATION_FAILED` (400) — includes `details` with field paths.
    - `INVALID_ACTION` (409) — e.g., `Approve` on a terminal document, or any workflow action on a `ProcessingDocument` (which has no workflow yet).
    - `REEXTRACTION_IN_PROGRESS` (409) — workflow action attempted while `Document.reextractionStatus = IN_PROGRESS`.
    - `LLM_UNAVAILABLE` (502) — upstream Anthropic failure after retries.
    - `INTERNAL_ERROR` (500).

    A contract test verifies the `{ code, message, details? }` shape for each error code.
10. *(removed — OpenAPI generation is out of scope for the take-home.)*

### Dependencies
- **C1**, **C2**, **C3**, **C4** (all the backend business components).

### Interfaces provided
- REST API surface above (used by C6).
- SSE stream endpoint (used by C6).

---

## C6. Frontend (React SPA)

**Role.** The user-facing app. React + TypeScript + Vite; no auth; consumes C5's REST + SSE; renders the screens and states in `problem-statement/mockups/`.

### Requirements

1. **C6-R1.** Organization picker screen (landing page) lists all orgs from `/api/organizations`. Selecting an org stores the selection client-side and navigates to the dashboard.
2. **C6-R2.** Dashboard screen shows **two sections** for the selected org, sourced from the single `GET /api/organizations/{orgId}/documents` response (C5-R3):
    - **Processing section (top, small).** Renders the `processing` array from the API response — every in-flight `ProcessingDocument`. Each row shows filename, current step badge (`Text Extracting` / `Classifying` / `Extracting` / `Failed`), reduced opacity, and a spinner (or retry affordance for `Failed`).
    - **Documents section (below).** Renders the `documents` array — processed documents — with filter dropdowns for **`status` (canonical `WorkflowStatus` enum)** and `docType`. Status options are the fixed canonical vocabulary (Awaiting Review, Awaiting Approval, Flagged, Filed, Rejected) — the same for every org. Status options that can't apply to any of the org's workflows may be hidden (e.g., Riverside Receipt has no approval stage, so "Awaiting Approval" is omitted when viewing Riverside). `docType` options come from the selected org's configured doc-types.

    Verified by a component test that asserts: the `docType` dropdown is scoped (e.g., "Retainer Agreement" appears only on Pinnacle), the `status` dropdown uses canonical values (no per-org stage names, no processing-step names), and the processing section renders separately from the documents section.
3. **C6-R3.** Processing-section rows are **non-clickable** (still in flight; no detail view yet). Rows where `currentStep = FAILED` show an inline "Processing failed — Retry" affordance that POSTs to a retry endpoint; on success the row's `currentStep` cycles back to its original failed step and re-runs forward.
4. **C6-R4.** Document upload button on the dashboard opens a file picker, posts to `/api/organizations/{orgId}/documents`, and shows the uploaded document in the processing section with live updates from SSE.
5. **C6-R5.** The dashboard subscribes to `/api/organizations/{orgId}/stream` SSE. On **any** event (`ProcessingStepChanged` or `DocumentStateChanged`), the handler invalidates the dashboard query and refetches `GET /api/organizations/{orgId}/documents`. Surgical cache updates (remove-from-processing, add-to-documents, diff-on-stage-change) are not implemented — at the take-home's scale the refetch is cheap and the code stays simple. The active document detail view (when open) similarly refetches its own query on `DocumentStateChanged` for that `documentId`. Closing the SSE connection on unmount is tested.
6. **C6-R6.** Clicking a row in the documents section opens the detail view route for that processed `documentId`. Clicking a row in the processing section is a no-op (or shows the minimal processing detail per C6-R7) — there is no full review/approval UI for in-flight documents.
7. **C6-R7.** Only processed documents have a detail view: route `/documents/{id}`, sourced from `GET /api/documents/{id}`, two-panel layout with PDF preview on the left and the full Review/Approval form panel on the right (per C6-R8). In-flight processing rows are not navigable — the dashboard processing section (C6-R2) renders everything the user needs to see for a `ProcessingDocument`, including the inline retry affordance for failed processing (C6-R3). There is no `/processing-documents/{id}` route.
8. **C6-R8.** The processed-`Document` form panel content depends on the current workflow stage and reextraction status:
    - **`reextractionStatus = IN_PROGRESS`:** in-flight banner ("Re-extracting as <newType>…"); form disabled; no action buttons until completion.
    - **`reextractionStatus = FAILED`:** error banner with Retry affordance; form re-enabled with the previous values still visible.
    - **Review (not flagged):** editable form with fields generated from the doc-type schema (C1 via C5); document-type dropdown; Approve + Reject action buttons.
    - **Review (flagged):** same form with a flag banner (origin stage + comment) above the form; Resolve replaces Approve.
    - **Approval stages:** read-only summary of the reviewed data; Approve + Flag buttons. If C1 config supplies a `role` for the stage, it is displayed alongside the stage label ("Attorney Approval — role: Attorney").
    - **Filed / Rejected:** read-only summary; single Back to Documents button.
9. **C6-R9.** Stage progress indicator **synthesizes from `ProcessingDocument.currentStep` (when in flight) or `WorkflowInstance.currentStageId` (when processed)**, plus the workflow definition returned by C5:
    - **In-flight (`ProcessingDocument`):** shows the pre-workflow processing steps as their own progress segment (Text Extracting → Classifying → Extracting), with the current step highlighted and any `FAILED` step shown in red.
    - **Processed (`Document` with `WorkflowInstance`):** shows the full workflow stages (Review → … → Filed/Rejected) per the configured workflow.

    The pre-workflow processing steps and the workflow stages are rendered as a single visual sequence so users see the full lifecycle, but they come from two different data sources joined client-side.
10. **C6-R10.** Review form supports all field types in the C1 schema: string, date, decimal, enum (dropdown), and array-of-object (`lineItems`, `materials`, `items`) with add/remove row.
11. **C6-R11.** Changing the document type in the Review form opens a confirm modal ("Re-extract as …?"). Confirming POSTs to `/api/documents/{id}/review/retype` (C5-R7) with the new type. The form transitions to the `reextractionStatus = IN_PROGRESS` state per C6-R8 — the document does **not** leave Review (no `Extract` workflow stage exists anymore); the in-flight banner signals the retype.
12. **C6-R12.** Flag from an approval stage opens a modal with a required comment textarea; submit is disabled until non-empty. On submit, posts the `Flag` action.
13. **C6-R13.** Frontend component tests cover: stage progress component for each workflow variant, review form for at least one schema from each client (e.g., Pinnacle Invoice, Riverside Receipt, Ironworks Lien Waiver), flag modal validation, reclassify modal confirm/cancel.
14. **C6-R14.** E2E test suite (Playwright or equivalent), run against the dockerized stack:
    - **Happy path** for at least one `(client, doc-type)` combination: upload → classify → extract → review approve → approval approve → filed.
    - **Flag-and-resolve path:** upload → classify → extract → review approve → approval flag-with-comment → review (shows banner) → resolve → approval → approve → filed. Exercises the interaction most prone to regression (C4-R6 origin restoration).

### Dependencies
- **C5** (REST + SSE).

### Interfaces provided
- User-facing UI only. No interface consumed by other components.

---

## C7. Platform & Quality

**Role.** The project's connective tissue: docker-compose, migrations, build tooling, quality gates, seed data, agent-config hooks, and the README. Not a runtime component, but everything else depends on the scaffolding it provides.

### Requirements

1. **C7-R1.** `docker-compose.yml` at the repo root starts backend, frontend, and PostgreSQL with a single `docker-compose up` invocation. No host-side setup beyond Docker itself and populating `.env` from `.env.example`.
2. **C7-R2.** `.env.example` documents every required environment variable (minimum: `ANTHROPIC_API_KEY`). `.env` is in `.gitignore`.
3. **C7-R3.** PostgreSQL schema is managed by Flyway migrations under a versioned `db/migration` directory. Every migration is named `V{n}__{snake_case}.sql` and is never edited once applied. Migrations are split by table/feature rather than one giant baseline:
    - `V1__client_data_tables.sql` — empty `organizations`, `document_types`, `workflows`, `stages`, `transitions`. (Unchanged from prior plan.)
    - `V2__stored_documents.sql` — C2's immutable file-reference table.
    - `V3__processing_documents.sql` — C3's transient pipeline table.
    - `V4__documents.sql` — C4's processed-document entity (with `extracted_fields JSONB`, `raw_text`, `reextraction_status`).
    - `V5__workflow_instances.sql` — C4's workflow run-state.
    - `V6__llm_call_audit.sql` — C3's LLM call ledger (with FKs to `stored_documents`, `processing_documents`, `documents`).
    - Later migrations as features are added; never edit an applied one.

    Migrations for `document_classifications` and `document_extractions` are **not** present — those tables don't exist in the new model (current values live on `Document`; history lives in `llm_call_audit`).

    Application-level seeder (C1-R11) runs on startup if client-data tables are empty, loading from `src/main/resources/seed/`.
4. **C7-R4.** Seed data mechanism loads roughly half of the 23 sample PDFs under `problem-statement/samples/` into the running app on first startup (or on demand via a command). Which files are seeded is deterministic, pinned in a committed manifest `seed/manifest.yaml` listing the relative paths together with each sample's labeled ground-truth `documentType` and `extractedFields`. The seeder **bypasses `ProcessingDocument` entirely** — for each manifest entry, in one transaction it INSERTs a `StoredDocument` (file copied to storage), a `Document` (with `detectedDocumentType` and `extractedFields` from the manifest, `processedAt = now`, `reextractionStatus = NONE`), and a `WorkflowInstance` (with `currentStageId = Review`, `currentStatus = AWAITING_REVIEW`). No LLM call happens at seed time. A test compares the post-seed `documents` set to the manifest.
5. **C7-R5.** Backend Gradle build includes: Spotless (format), Checkstyle + PMD (lint), JaCoCo (coverage — fail below 70% line coverage), Error Prone (static analysis), and a `grepForbiddenStrings` task that scans `src/main/java` and fails on literal hits for any stage name or client slug (as required by C1-R7 and C4-R10). The `grepForbiddenStrings` task is wired into the default `check` task. `./gradlew build` fails on any violation. JaCoCo exclusions are enumerated in a committed file (e.g., `config/jacoco/exclusions.txt`) and limited to: generated sources (MapStruct/Jackson), Flyway migration classes, Spring configuration classes, SSE emitter transport-shell classes (which don't coverage-test well for long-lived connections), and records/value classes whose only methods are accessors. The exclusion list is reviewed in Pass 5 (change-spec).
6. **C7-R6.** Frontend build includes: ESLint (error on warnings), Prettier (`--check` in CI), TypeScript strict mode, Vitest for unit tests, Playwright for E2E. `npm run build` fails on any of the above.
6a. **C7-R6a.** Frontend test coverage is enforced by the build. The threshold lives in `frontend/vitest.config.ts` under `test.coverage.thresholds`; `npm run build` fails below the threshold. Until Pass 4 decides the bar, a placeholder of `0` is committed in that exact file with a `// TODO(research): set coverage threshold` comment so the deferred decision is machine-grep-findable and impossible to lose in prose.
7. **C7-R7.** A `.claude/settings.json` Stop hook runs the fast test suite + lint/format/type checks and blocks the agent's "done" signal if any fail. Long-running tests (full Playwright E2E, live-mode eval) are excluded from the hook.
8. **C7-R8.** `AGENTS.md` at the repo root documents conventions; `CLAUDE.md` is a symlink to `AGENTS.md` (already present).
9. **C7-R9.** `README.md` at the repo root documents: how to run (`docker-compose up`), the design decisions (no-auth, local FS vs. S3, Java/Spring versions, Anthropic choice, SSE), how the client data (orgs, document types, workflows) is seeded from YAML fixtures on first startup only for ease of setup — after which the database is authoritative, how to run the LLM eval, how to add labeled samples, and a **"Production considerations"** section that names what was intentionally simplified for the take-home and what would change in production. At minimum this section covers:
    - No auth; real deployment would add identity + role-based access; the `role` slot on approval stages (C1-R10) is the attach point.
    - The `role/stage/action` domain model is deliberately basic — production would likely promote `Role` to a first-class entity with a permissions matrix, support composite approvers, and let roles be shared across orgs where appropriate.
    - **`ProcessingDocument` cleanup is deferred.** On successful pipeline completion the take-home leaves the `ProcessingDocument` row in place; the dashboard query filters it out by joining against `documents`. Production would add a cron-style cleanup that deletes `ProcessingDocument` rows whose `storedDocumentId` already has a `Document`. This kept the take-home's transactional handoff from spanning two contexts.
    - **3-entity document lifecycle is a deliberate domain modeling choice.** `StoredDocument` (C2, immutable file ref), `ProcessingDocument` (C3, transient pipeline state, deleted on success in production), and `Document` (C4, processed and workflow-ready) are separate entities in separate tables — not a single document row with a `currentStage` discriminator. The reasoning: most documents in the system are stable (processed); only a few are transient (processing). Separating them keeps the hot-path read (the dashboard's `documents` query) narrow, lets the processing pipeline iterate on its own state shape without touching the workflow tables, and makes the bounded-context ownership unambiguous (each entity has exactly one writer). The cost is that the dashboard endpoint returns two arrays instead of one — a deliberate tradeoff.
    - Local-filesystem storage instead of S3 (C2-R7 isolates the seam).
    - Classpath seed fixtures instead of an externally-managed config store.
    - Single-tenant deployment — horizontal scaling concerns (distributed SSE fan-out, LLM call concurrency limits) not addressed.
10. **C7-R10.** A CI workflow file (or a single `make check` target usable by CI) runs the full gate: backend build (including Spotless, Checkstyle, PMD, JaCoCo 70% floor, Error Prone, `grepForbiddenStrings`, the single happy-path LLM smoke test from C3-R12, property-based workflow-engine tests, seed-manifest verification, the C1-R9 fourth-org seeder unit test); frontend build (ESLint, Prettier `--check`, TypeScript strict, Vitest with coverage threshold, Playwright happy-path E2E, Playwright flag-and-resolve E2E). The eval (C3-R8) is run on demand, not in CI.
11. **C7-R11.** A `DocumentEventBus` (in-process publish/subscribe) is provided at the platform layer. Contract: `publish(event)` is synchronous from the caller's perspective but dispatch to subscribers is non-blocking. Used by C3 (emit `ProcessingStepChanged` for SSE; emit `ProcessingCompleted`, `ExtractionCompleted`, `ExtractionFailed` internal-only), C4 (emit `DocumentStateChanged` for SSE; subscribe to `ProcessingCompleted` for initial materialization and to `ExtractionCompleted`/`ExtractionFailed` for retype completion), and C5 (subscribe for SSE fan-out per org — `ProcessingStepChanged` and `DocumentStateChanged` only).
12. **C7-R12.** Docker-compose includes a frontend container and backend container that run on the same Docker network; the frontend dev server (when run on the host via `npm run dev`) uses Vite's proxy config to forward `/api/**` and `/api/**/stream` to `http://localhost:8080`, avoiding CORS. Production docker-compose serves the frontend behind the same origin as the backend (either via a reverse proxy or by having the backend serve the built SPA). CORS policy for any cross-origin case: deny by default.
13. **C7-R13.** **Startup-only external-input loading.** All environment variables and external configuration (Anthropic API key, model ID, DB URL/credentials, file-storage path, seed-on-boot flag, recorded-eval mode toggle) are read exactly once at backend startup, validated, and bound to a single typed immutable `AppConfig` object (or a small nested set) that is injected into components. After binding, no runtime code reads `System.getenv()`, `@Value("${...}")`, or `.env` files. Missing/invalid config fails startup with a clear error citing the offending key and source — never a runtime 500 inside a user request. Model ID for the Anthropic SDK is pinned in `AppConfig.llm.modelId = "claude-sonnet-4-6"` (sourced from config, not hardcoded in C3). A startup-config test asserts that (a) removing a required env var causes startup to fail with the expected error code, and (b) no class in `src/main/java` other than the `AppConfig` binder references `System.getenv`, `@Value`, or similar — enforced by a grep check in the `grepForbiddenStrings` task (C7-R5).

### Dependencies
- None at the data layer. Consumed by every other component as build/deploy scaffolding.

### Interfaces provided
- `docker-compose up` (runtime harness).
- `./gradlew build`, `npm run build` (build harnesses).
- Flyway migration directory structure (consumed by C2).
- Stop hook + CI workflow (process harness).

---

## Cross-component interfaces summary

| Producer → Consumer | Interface | Form | Notes |
|---|---|---|---|
| C1 → C2, C3, C4 | Organization / DocType / Workflow / Transition / StageGuard / WorkflowStatus catalog | Typed Java read APIs | Loaded + validated at startup (C1-R5); seeded to DB on first install (C1-R11); subsequent startups read from DB |
| C2 → C3 | `StoredDocument` aggregate (id + bytes + immutable metadata) | `StoredDocumentReader.get(id)`, `StoredDocumentStorage.load(id)` | C3 reads bytes for text extraction and LLM calls; never mutates `StoredDocument` |
| C2 → C5 | Ingestion entry point | `StoredDocumentIngestionService.upload(orgId, file)` — atomically writes `StoredDocument` + initial `ProcessingDocument`, then signals C3 | Used by `POST /api/organizations/{orgId}/documents` |
| C3 → (own tables) | Pipeline state + LLM audit | `ProcessingDocumentService` writers; `LlmCallAudit` writer | Pipeline state is UPDATEd in place; audit is INSERT-only |
| C3 → DocumentEventBus | Pipeline lifecycle signals | `ProcessingStepChanged` (SSE-visible; carries `currentStep` incl. `FAILED` with `error`), `ProcessingCompleted`, `ExtractionCompleted`, `ExtractionFailed` (latter three internal-only) | C4 subscribes to `ProcessingCompleted` (initial materialization, C4-R13) and to `ExtractionCompleted` / `ExtractionFailed` (retype completion, C4-R6); C5 subscribes to `ProcessingStepChanged` for SSE |
| C4 → C3 | Synchronous invocation (retype only) | `LlmExtractor.extract(documentId, docTypeId)` | Used by C4-R6 for retype re-extraction; not used during initial pipeline (which C3 drives autonomously) |
| C4 → (own tables) | `Document`, `WorkflowInstance` state | `DocumentWriter` (UPDATE for retype results), `WorkflowInstanceWriter.advanceStage / setFlag / clearFlag` | Writer computes canonical status per C4-R12 |
| C4 → DocumentEventBus | Workflow state changes + retype lifecycle | `DocumentStateChanged` (retype lifecycle communicated via the `reextractionStatus` field on this event) | C5's SSE publisher subscribes |
| C4 → C5 | Action handler | `WorkflowEngine.applyAction(documentId, action, payload)` | Returns updated `DocumentView` or typed error |
| Read-models → C5 | `ProcessingDocumentSummary` (in-flight) and `DocumentView` (processed) projections | `ProcessingDocumentSummary`: minimal projection of `processing_documents` joined with `stored_documents`. `DocumentView`: JOIN across `documents` + `workflow_instances` + `stored_documents` + stage display metadata. | Live in a shared `read-model` package; no context owns them exclusively. Dashboard list endpoint (C5-R3) returns both arrays in one response. |
| C5 → C6 | REST API | HTTP JSON; dashboard list returns `{ processing, documents }` (C5-R3); status filter uses canonical `WorkflowStatus` only (5 values per C1-R12) | Error codes per C5-R9a |
| C5 → C6 | SSE stream | `text/event-stream` | **One stream per org** carries `ProcessingStepChanged` + `DocumentStateChanged` (only); event menu in C5-R8 |
| C7 → all | DocumentEventBus | Platform-level pub/sub (C7-R11) | Cross-context async signal |
| C7 → all | Build + deploy scaffolding | Files on disk | Not a runtime channel |

---

## Goal → Component traceability

Every goal in `01-problem-space.md` maps to at least one component.

| Goal | Components |
|---|---|
| G1 Meet every spec requirement cleanly | Covered implicitly by every component requirement that cites a spec item (fields in C1-R8, workflows in C1-R3/C4-R3, transitions in C4-R1–R7, UI behaviors in C6-R7–R12). No dedicated component — this goal is satisfied in aggregate. |
| G2 Multi-tenant from day one (data-driven) | C1 (primary), all backend consumers (via C1-R7, C4-R10) |
| G3 Real AI classify + extract, store text, tool use | C3 (entire processing pipeline: text extraction inside `ProcessingPipelineOrchestrator`, classification, tool-use extraction, audit) |
| G4 Stage-based workflow engine via declarative config | C1 (config), C4 (engine) |
| G5 Live progress via SSE | C4 (events), C5 (stream), C6 (consumer) |
| G6 One-command startup | C7 |
| G7 Seed demo data on boot | C7 |
| G8 Test at professional bar | C3 (eval), C4 (property tests), C6 (component + E2E), C7 (enforcement) |
| G9 Normalized relational schema (3NF) | C2 (`stored_documents`), C3 (`processing_documents`, `llm_call_audit`), C4 (`documents`, `workflow_instances`), C7 (Flyway) |
| G10 Enforced conventions | C7 |
| G11 Done means green | C7 (Stop hook) |
| G12 Agent-configuration versioned | C7 (AGENTS.md + `.claude/`) |
| G13 Classification/extraction measured (aggregate eval) | C3 |
