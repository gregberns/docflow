# DocFlow — Integration (Pass 6)

Documents the seams between the seven component specs in `05-specs/`. This file is **not** a re-write of those specs; it captures only the cross-component concerns: dependency edges, startup ordering, shared state, end-to-end data flow, cross-cutting policies, and the integration testing strategy.

Source-of-truth requirements: `03-components.md`. Spec reviews: `change-spec-review.md` (Round 1 fixes verified, Round 2 accept-with-fixes). Cross-cutting context: `01-problem-space.md`. Conventions: `CLAUDE.md`.

---

## 1. Component dependency graph

Edge label is the canonical contract object that crosses the seam.

```
                           C7 (platform)
                ┌─────────────────────────────────────────┐
                │ DocumentEventBus, AppConfig, Makefile,  │
                │ Flyway V1__init.sql, AsyncConfig        │
                └────────────┬────────────────────────────┘
                             │ (provides scaffolding to all)
                             ▼

   C1 (config) ─────────► C2, C3, C4, C5, C6 (catalogs)
        │
        │  OrganizationCatalog, DocumentTypeCatalog, WorkflowCatalog
        │  (typed read APIs; loaded once at startup)
        ▼
   C2 (ingest+storage) ──► writes stored_documents + initial processing_documents
        │       ▲
        │       │ StoredDocumentReader.get / StoredDocumentStorage.load
        │       │
        │       └──── consumed by C3, C5 read-models
        │
        │  publish StoredDocumentIngested (post-commit, on DocumentEventBus)
        ▼
   C3 (pipeline+eval) ──► writes processing_documents + llm_call_audit
        │       │
        │       │  publishes ProcessingStepChanged (SSE-visible),
        │       │             ProcessingCompleted, ExtractionCompleted,
        │       │             ExtractionFailed (internal-only)
        │       │
        │       │  exposes LlmExtractor.extract (called by C4 for retype)
        │       ▼
   C4 (workflow) ──────► writes documents + workflow_instances
        │       │
        │       │  subscribes to ProcessingCompleted (initial materialization)
        │       │  subscribes to ExtractionCompleted / ExtractionFailed (retype)
        │       │  publishes DocumentStateChanged (SSE-visible)
        │       │
        │       │  exposes WorkflowEngine.applyAction (called by C5)
        │       ▼
   C5 (api+sse)  ──────► no tables; composes read-models
        │       │
        │       │  REST API (HTTP JSON) + per-org SSE stream
        │       │  subscribes to ProcessingStepChanged + DocumentStateChanged
        │       │  delegates writes to C2 (upload), C3 (retype, via C4),
        │       │  C4 (actions)
        │       ▼
   C6 (frontend SPA) ──► consumes REST + SSE
                          (no inbound dependency from any backend component)
```

### Edge contracts

| From | To | Contract object | Direction |
|---|---|---|---|
| C1 | C2 | `OrganizationCatalog.getOrganization(orgId)` | sync read |
| C1 | C3 | `DocumentTypeCatalog.getDocumentTypeSchema`, `OrganizationCatalog.getAllowedDocTypes` | sync read |
| C1 | C4 | `WorkflowCatalog.getWorkflow(orgId, docTypeId)` (constructor-injected per C4-R9a) | sync read |
| C1 | C5 | `OrganizationCatalog.listOrganizations`, `DocumentTypeCatalog.listDocumentTypes` | sync read |
| C2 | C3 | `StoredDocumentReader.get(id)`, `StoredDocumentStorage.load(id)` | sync read |
| C2 | C5 | `StoredDocumentIngestionService.upload(orgId, file) → IngestionResult{storedDocumentId, processingDocumentId}` | sync call |
| C2 | C3 | `StoredDocumentIngested { storedDocumentId, organizationId, processingDocumentId, at }` | event (post-commit) |
| C3 | C4 | `ProcessingCompleted { storedDocumentId, processingDocumentId, organizationId, detectedDocumentType, extractedFields, rawText, at }` | event (internal) |
| C3 | C4 | `ExtractionCompleted { documentId, extractedFields, detectedDocumentType?, at }` / `ExtractionFailed { documentId, error, at }` | event (internal) |
| C3 | C5 | `ProcessingStepChanged { storedDocumentId, processingDocumentId, organizationId, currentStep, error?, at }` | event (SSE-visible) |
| C4 | C3 | `LlmExtractor.extract(documentId, newDocTypeId)` | sync call (returns quickly; C3 publishes events asynchronously) |
| C4 | C5 | `WorkflowEngine.applyAction(documentId, action, payload) → DocumentView \| WorkflowError` | sync call |
| C4 | C5 | `DocumentStateChanged { documentId, storedDocumentId, organizationId, currentStage, currentStatus, reextractionStatus, action?, comment?, at }` | event (SSE-visible) |
| C5 | C6 | REST endpoints + per-org SSE stream | HTTP / `text/event-stream` |
| C7 | all | `DocumentEventBus`, `AppConfig`, `AsyncConfig`, Flyway `V1__init.sql`, Makefile | scaffolding |

There are **no cycles**. The only direction-bidirectional pair (C3 ↔ C4) is split by mechanism: C3 → C4 is event-only; C4 → C3 is a single sync call (`LlmExtractor.extract`) for retype only.

---

## 2. Initialization order

The Spring `ApplicationContext` brings beans up in dependency order. The contracts below name what must finish before what.

1. **`AppConfig` binding** (C7-owned record under `com.docflow.config`). Reads env + `application.yml` exactly once, validates with Jakarta `@Validated`. Failure here fails startup with `BindValidationException` before any other bean exists.
2. **DataSource + Flyway migration** (C7-owned). `V1__init.sql` runs in the canonical table order: client-data tables (C1) → `stored_documents` (C2) → `processing_documents` (C3) → `documents` + `workflow_instances` (C4) → `llm_call_audit` (C3). Flyway checksum failure on a previously-applied migration fails startup.
3. **JPA repositories and entity managers** (per-component, internal). Standard Spring Data wiring; no cross-component ordering needed at this layer.
4. **`OrgConfigSeeder`** (C1) runs as `@EventListener(ApplicationReadyEvent.class)` — i.e., after the context is fully refreshed but inside the startup phase. It checks `organizations` row count; if zero and `AppConfig.OrgConfigBootstrap.seedOnBoot=true`, it parses `seed/*.yaml`, validates via `ConfigValidator`, and INSERTs in one transaction. Failure aborts startup.
5. **`OrganizationCatalog`, `DocumentTypeCatalog`, `WorkflowCatalog`** (C1) — declared `@DependsOn("orgConfigSeeder")` so they JPA-load into immutable in-memory views only after the seeder finishes.
6. **`PromptLibrary`** (C3) loads all `prompts/*.txt` resources at startup and validates that every `(orgId, allowedDocType)` pair from C1 has a matching `extract_<docType>.txt`. Missing prompt files fail startup (matches C1-R5's fail-fast posture). `@DependsOn` on the C1 catalogs.
7. **C7 seed data loader** (C7-R4) runs after both `OrgConfigSeeder` and the C1 catalogs are ready. For each manifest entry it inserts `StoredDocument` + `Document` + `WorkflowInstance` (Review / `AWAITING_REVIEW`) in one transaction. **No LLM call at seed time.** This is the application-data seeder; distinct from C1's reference-data seeder.
8. **`PipelineTriggerListener` (C3), `ProcessingCompletedListener` (C4), `ExtractionEventListener` (C4), `SsePublisher` (C5)** all become live as soon as `@EventListener` registration completes in step 3. They will not see events until step 9.
9. **HTTP listener** (Tomcat 11 / Spring MVC) opens once the context is refreshed. Uploads can begin and the SSE stream is reachable.

### Failure mode boundaries

- **Fails startup:** missing/blank `ANTHROPIC_API_KEY` (`AppConfig` validation), Flyway migration error, C1 config validation failure (CV-1..CV-8 in `c1-config-spec.md` §3.4), missing prompt resource (C3 §3.3), seed manifest references a missing file (C7-R4).
- **Fails at first request, not startup:** unknown `orgId` in upload URL (`UNKNOWN_ORGANIZATION` 404), upstream Anthropic 5xx (surfaced through C3 → `ProcessingDocument.currentStep = FAILED` + `ProcessingStepChanged`).

---

## 3. Shared state and resources

### 3.1 Database (PostgreSQL, single Flyway baseline `V1__init.sql`)

| Table | Owner | Writers | Readers |
|---|---|---|---|
| `organizations` | C1 | `OrgConfigSeedWriter` (seed-only) | C1 catalogs, C2 (FK validation), C5 |
| `organization_doc_types` | C1 | `OrgConfigSeedWriter` (seed-only) | C1 catalogs |
| `document_types` | C1 | `OrgConfigSeedWriter` (seed-only) | C1 catalogs, C3 (tool schema), C4 (FK), C5 |
| `workflows`, `stages`, `transitions` | C1 | `OrgConfigSeedWriter` (seed-only) | `WorkflowCatalog` (C1), C4 (engine reads via catalog) |
| `stored_documents` | C2 | `StoredDocumentIngestionServiceImpl` (INSERT-only; immutable) | C2 reader, C3, C4, C5 read-models |
| `processing_documents` | C3 | `StoredDocumentIngestionServiceImpl` (initial INSERT, in C2's transaction); `ProcessingDocumentWriter` (UPDATEs `current_step`, `raw_text`, `last_error`) | C3 orchestrator, C5 dashboard query (filters out rows that already have a matching `Document`) |
| `documents` | C4 | `DocumentWriter` (INSERT on `ProcessingCompleted`; UPDATE on retype completion + field PATCH) | C4 reader, C5 read-models |
| `workflow_instances` | C4 | `WorkflowInstanceWriter.advanceStage / setFlag / clearFlag` | C4 reader, C5 read-models |
| `llm_call_audit` | C3 | `LlmCallAuditWriter` (INSERT-only) | C3 reader (none external in take-home) |

**`llm_call_audit` FK shape (parked in 03-components.md, confirmed in C3 §3.7 and C7 §3.3):**
- `stored_document_id` is **always** populated (every audited call has an underlying file).
- `processing_document_id` is populated for initial-pipeline calls (classify and the initial extract); FK `ON DELETE SET NULL`.
- `document_id` is populated for retype-extract calls; FK `ON DELETE SET NULL`.
- `CHECK ((processing_document_id IS NOT NULL AND document_id IS NULL) OR (processing_document_id IS NULL AND document_id IS NOT NULL))` enforces mutual exclusivity.

### 3.2 `AppConfig` — single typed config tree

Owned by C7; lives at `com.docflow.config.AppConfig`. The **only** legitimate reader of `System.getenv`, `@Value`, or `.env` content. Nested record set:

```java
package com.docflow.config;

public record AppConfig(
    Llm llm,                       // C3-contributed: modelId, apiKey, requestTimeout, eval.reportPath
    Storage storage,               // C2-contributed: storageRoot
    Database database,             // C7-owned: url, user, password
    OrgConfigBootstrap config      // C1-contributed: seedOnBoot, seedResourcePath
) {
    public record Llm(String modelId, String apiKey, Duration requestTimeout, Eval eval) {
        public record Eval(String reportPath) {}
    }
    public record Storage(String storageRoot) {}
    public record Database(String url, String user, String password) {}
    public record OrgConfigBootstrap(boolean seedOnBoot, String seedResourcePath) {}
}
```

Other components inject the bound values via constructor parameters only. `grepForbiddenStrings` (C7 §3.6) scans `backend/src/main/java/**/*.java` and rejects any `System.getenv`, `@Value`, or literal `.env` reference outside `com.docflow.config/`.

This is the canonical assembly per Round-1 Issue 3 (RESOLVED). The Round-2 follow-up "C7 §3 / §4 should explicitly enumerate the nested-record set" is satisfied here.

### 3.3 `DocumentEventBus`

C7-provided (C7-R11) wrapper over Spring's `ApplicationEventPublisher`. `publish(event)` returns synchronously to the caller; dispatch to `@Async @EventListener` subscribers runs on virtual threads (`spring.threads.virtual.enabled=true`).

| Event | Publisher | Subscribers | Visibility |
|---|---|---|---|
| `StoredDocumentIngested` | C2 (post-commit) | C3 `PipelineTriggerListener` | internal |
| `ProcessingStepChanged` | C3 orchestrator (every step transition incl. `FAILED`) | C5 `SsePublisher` | **SSE-visible** |
| `ProcessingCompleted` | C3 orchestrator (terminal success) | C4 `ProcessingCompletedListener` | internal |
| `ExtractionCompleted` | C3 `LlmExtractor` (retype success) | C4 `ExtractionEventListener` | internal |
| `ExtractionFailed` | C3 `LlmExtractor` (retype failure) | C4 `ExtractionEventListener` | internal |
| `DocumentStateChanged` | C4 (every state transition + retype lifecycle via `reextractionStatus` field) | C5 `SsePublisher` | **SSE-visible** |

C5 fans out **only** `ProcessingStepChanged` and `DocumentStateChanged` on the per-org SSE stream. The internal-only events never leave the JVM.

### 3.4 File storage

`StoredDocumentStorage` interface owned by C2 (C2-R7); single filesystem implementation `FilesystemStoredDocumentStorage` resolves `{storageRoot}/{id}.bin` via `java.nio.file.Path`. `storageRoot` is bound from `AppConfig.Storage`. Atomic write via tmp file + `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`. C3 reads bytes through this seam for text extraction and LLM calls; C5's `GET /api/documents/{id}/file` endpoint also reads through it.

### 3.5 LLM client wrapper

`AnthropicClientFactory` (C3) wraps `com.anthropic.client.AnthropicClient`, configured with `AppConfig.Llm.apiKey` and `requestTimeout`. SDK built-in retry disabled — C3 owns retry semantics (one retry on `LlmSchemaViolation` for extract; no retry on classify or on transport errors). Consumed for retype by C4 via `LlmExtractor.extract(documentId, newDocTypeId)`.

### 3.6 `OrganizationCatalog` / `DocumentTypeCatalog` / `WorkflowCatalog`

Owned by C1; in-memory `*View` records cached at startup. Consumed by:
- **C2** — existence check on upload (`getOrganization(orgId).isPresent()`).
- **C3** — `getAllowedDocTypes(orgId)` for classify enum, `getDocumentTypeSchema(orgId, docTypeId)` for tool schema.
- **C4** — constructor-injected `WorkflowCatalog` per C4-R9a; engine reads transitions and stages.
- **C5** — listing orgs (`/api/organizations`), org detail with workflows + field schemas.
- **C6** — indirectly, through C5's responses.

---

## 4. Data-flow walkthroughs

### 4.1 Upload flow (C5 → C2 → C3 → C4 → C5 SSE → C6)

1. **C6** user clicks Upload; the optimistic UI (C6 `useUploadDocument.ts`) inserts a placeholder into the `['dashboard', orgId]` query's `processing[]` array.
2. **C5** `DocumentUploadController` receives `POST /api/organizations/{orgId}/documents` (multipart). Calls `StoredDocumentIngestionService.upload(orgId, file)`.
3. **C2** orchestrator:
   - Validates `orgId` via `OrganizationCatalog.getOrganization(orgId)` — empty → `UNKNOWN_ORGANIZATION` 404.
   - Sniffs MIME via Tika; rejects non-allowed → `UNSUPPORTED_MEDIA_TYPE` 415.
   - Mints `storedDocumentId` and `processingDocumentId` (UUIDv7).
   - Writes bytes to `{storageRoot}/{id}.bin` via atomic-move (FS-write **before** DB commit per C2-R9a).
   - Begins transaction: INSERT `stored_documents`, INSERT `processing_documents` with `current_step = TEXT_EXTRACTING`, denormalized `organization_id`. Commits.
   - Post-commit: publishes `StoredDocumentIngested { storedDocumentId, organizationId, processingDocumentId, at }` on the bus.
   - Returns `IngestionResult { storedDocumentId, processingDocumentId }` to C5.
4. **C5** controller returns `201 Created` with both ids in the response body.
5. **C3** `PipelineTriggerListener.onIngested` fires (async, virtual thread). Calls `ProcessingDocumentService.start(storedDocumentId, processingDocumentId)`.
6. **C3** `ProcessingPipelineOrchestrator` runs sequentially:
   - **TEXT_EXTRACTING:** PDFBox parses `StoredDocument` bytes → `rawText`. UPDATE `processing_documents.raw_text`. Emit `ProcessingStepChanged { currentStep: TEXT_EXTRACTING }` (already emitted on entry by orchestrator's step transition; described in C3 §3.11).
   - UPDATE `current_step = CLASSIFYING`. Emit `ProcessingStepChanged { currentStep: CLASSIFYING }`.
   - **CLASSIFYING:** call Anthropic with allowed-doc-types enum and forced `select_doc_type` tool. Validate response. INSERT `llm_call_audit` row (call_type=`classify`).
   - UPDATE `current_step = EXTRACTING`. Emit `ProcessingStepChanged { currentStep: EXTRACTING }`.
   - **EXTRACTING:** call Anthropic with `extract_<docType>` tool whose schema comes from `DocumentTypeCatalog`. Validate; one retry on `LlmSchemaViolation`. INSERT `llm_call_audit` row (call_type=`extract`).
   - **Terminal success:** publish `ProcessingCompleted { storedDocumentId, processingDocumentId, organizationId, detectedDocumentType, extractedFields, rawText, at }` (internal-only). The `processing_documents` row is left in place; cleanup is deferred to a hypothetical cron not implemented in the take-home.
7. **C4** `ProcessingCompletedListener` (async, `@Transactional`):
   - Looks up the workflow's `Review` stage id via `WorkflowCatalog.getWorkflow(orgId, detectedDocumentType)`.
   - INSERT `documents` (`processed_at = event.at`, `reextraction_status = NONE`, all fields from event payload).
   - INSERT `workflow_instances` (`current_stage_id = Review.id`, `current_status = AWAITING_REVIEW`, `workflow_origin_stage = NULL`, `flag_comment = NULL`, `updated_at = now`).
   - Publish `DocumentStateChanged { documentId, storedDocumentId, organizationId, currentStage = Review, currentStatus = AWAITING_REVIEW, reextractionStatus = NONE, at }` (SSE-visible).
8. **C5** `SsePublisher` (`@Async @EventListener` on `DocumentEvent`) writes the event frame to every emitter registered under `orgId` via `SseRegistry.emittersFor(orgId)`.
9. **C6** `useOrgEvents` hook receives `DocumentStateChanged`, invalidates `['dashboard', orgId]`. TanStack Query refetches; the dashboard now shows the document under `documents[]` with `currentStatus = AWAITING_REVIEW` and removes the corresponding `processing[]` entry (the dashboard query filters out `processing_documents` rows that already have a matching `Document`, per C5-R3 / C4-R13).

### 4.2 Review/Approve flow (C6 → C5 → C4 → C5 SSE → C6)

1. **C6** user clicks Approve in the form panel. `useDocumentActions.ts` POSTs `{ "action": "Approve" }` to `/api/documents/{documentId}/actions`.
2. **C5** `DocumentActionController` deserializes the discriminated union (Jackson 3 `@JsonTypeInfo` keyed on `action`), calls `WorkflowEngine.applyAction(documentId, Approve, null)`.
3. **C4** `WorkflowEngine`:
   - Reads `Document` + `WorkflowInstance`.
   - `TransitionResolver` iterates configured transitions for `currentStageId` and `Approve`; picks the first whose `StageGuard` evaluates true (or is `Always`).
   - For Ironworks Lien Waiver from `Review`: two guarded transitions exist — `FieldEquals("waiverType", "unconditional") → Filed` and `FieldEquals("waiverType", "conditional") → Project Manager Approval`; the engine picks whichever guard matches against `Document.extractedFields`.
   - Calls `WorkflowInstanceWriter.advanceStage(documentId, newStageId)` which sets both `current_stage_id` and the derived `current_status` (`canonicalStatus` from C1, with `FLAGGED` only when stage is `review` AND `workflow_origin_stage IS NOT NULL`).
   - Publishes `DocumentStateChanged { ..., currentStage, currentStatus, reextractionStatus = <unchanged>, action = Approve, comment = null, at }`.
   - Returns the updated `DocumentView` to C5.
4. **C5** controller responds `200 OK` with the `DocumentView` body.
5. **C5** `SsePublisher` (independently of the request thread) fans out `DocumentStateChanged` to every emitter for the org.
6. **C6** receives the event; `useOrgEvents` invalidates `['dashboard', orgId]` and (when the detail view is open for that `documentId`) `['document', documentId]`. The form panel re-renders against the new stage.

Reject, Flag, and no-type-change Resolve follow the same shape with different transition selections and writer methods (`advanceStage` for Reject, `setFlag` for Flag, `clearFlag` for plain Resolve).

### 4.3 Retype flow (C6 → C5 → C4 → C3 → C4 → C5 SSE → C6)

1. **C6** user changes the doc-type dropdown in Review; `ReclassifyModal` opens. On Confirm, `useDocumentActions.ts` POSTs `{ "newDocumentType": "<id>" }` to `/api/documents/{documentId}/review/retype`.
2. **C5** `ReviewController.retype`:
   - Validates `newDocumentType` is in the org's allowed list (else `UNKNOWN_DOC_TYPE` 404).
   - Calls `WorkflowEngine.applyAction(documentId, Resolve, { newDocTypeId })` — the canonical engine surface.
   - Returns `202 Accepted` with body `{ "reextractionStatus": "IN_PROGRESS" }`.
3. **C4** `WorkflowEngine.applyAction` (Resolve with type change):
   - Validates `Document.reextractionStatus != IN_PROGRESS` (else throws `ExtractionInProgress` → C5 surfaces `REEXTRACTION_IN_PROGRESS` 409).
   - `DocumentWriter.setReextractionStatus(documentId, IN_PROGRESS)`.
   - Publish `DocumentStateChanged { ..., reextractionStatus = IN_PROGRESS, currentStage = Review }` (SSE-visible).
   - Synchronously invokes `LlmExtractor.extract(documentId, newDocTypeId)`. The call returns quickly because C3's implementation publishes the result event asynchronously from inside the LLM client wrapper (the HTTP call runs on a virtual thread, not on C4's caller thread).
   - Returns the in-progress `DocumentView` to C5 controller (which has already responded `202`).
4. **C3** `LlmExtractor.extract` (retype mode):
   - Calls Anthropic with `extract_<newDocType>` tool. Validates; one retry on `LlmSchemaViolation`.
   - INSERT `llm_call_audit` row with `document_id = documentId`, `processing_document_id = NULL`.
   - On success: UPDATE `documents.detected_document_type` and `documents.extracted_fields`; publish `ExtractionCompleted { documentId, extractedFields, detectedDocumentType, at }` (internal).
   - On failure (after retry): publish `ExtractionFailed { documentId, error, at }` (internal).
5. **C4** `ExtractionEventListener`:
   - On `ExtractionCompleted`: `DocumentWriter.setReextractionStatus(documentId, NONE)`; `WorkflowInstanceWriter.clearFlag(documentId)` (sets `workflow_origin_stage = NULL`, `flag_comment = NULL`; stage stays `Review`); publish `DocumentStateChanged { ..., reextractionStatus = NONE, currentStage = Review, currentStatus = AWAITING_REVIEW }`.
   - On `ExtractionFailed`: `DocumentWriter.setReextractionStatus(documentId, FAILED)`; publish `DocumentStateChanged { ..., reextractionStatus = FAILED, currentStage = Review }`.
6. **C5** `SsePublisher` fans out the `DocumentStateChanged` event(s).
7. **C6** receives the event; `useOrgEvents` invalidates both `['dashboard', orgId]` and `['document', documentId]`. The form panel transitions out of the IN_PROGRESS banner — to the standard editable Review form on success, or to the FAILED banner with prior values intact on failure.

---

## 5. Cross-cutting concerns

### 5.1 Logging

Spring Boot defaults to logback with stdout output (matches the problem-space "structured logs to stdout and that's it" non-goal). No correlation-id middleware is introduced. Cross-component correlation through the upload → processing → workflow chain is provided by **the events themselves**: `organizationId` and `storedDocumentId` are present on every event, `processingDocumentId` on every event up to `ProcessingCompleted`, and `documentId` from `ProcessingCompleted` onward. Operators correlating logs use these ids as grep keys.

`AsyncUncaughtExceptionHandler` (C7 `AsyncConfig`) logs exceptions thrown by `@Async @EventListener` invocations at ERROR level so they don't get swallowed.

### 5.2 Configuration assembly

C7 owns the `AppConfig` host file under `com.docflow.config`. C1 contributes `OrgConfigBootstrap`, C2 contributes `Storage`, C3 contributes `Llm`. Binding happens once via `@ConfigurationProperties` + `@Validated` from `application.yml` (with env overrides). Validation is Jakarta Bean Validation (`@NotBlank`, `@NotNull`, `@Positive`); failure throws `BindValidationException` before `ApplicationContext` refresh, so a missing/blank env var fails startup, never a runtime 500.

`grepForbiddenStrings` (C7 §3.6) reads the literal pattern list from `config/forbidden-strings.txt` (owned by C1) and rejects any `System.getenv`, `@Value`, or literal `.env` reference outside `com.docflow.config/`. Stage names and client slugs are forbidden everywhere in `src/main/java`. The file is the single source of truth; the task fails fast if it is missing or empty.

### 5.3 Error propagation across boundaries

The path from a thrown exception to a structured JSON error body:

```
internal exception (e.g., IOException, AnthropicException)
  ↓ caught at component boundary
component-level typed error (LlmException, WorkflowError, IngestionError)
  ↓ rewrapped at C5 boundary
DocflowException subclass (sealed hierarchy in com.docflow.api.error)
  ↓ caught by GlobalExceptionHandler (@RestControllerAdvice)
ProblemDetail (RFC 7807) with custom code/message/details properties
  ↓ Jackson 3 serialization
JSON response { "code": "...", "message": "...", "details": [...]?, "status": ... }
```

Specific routings:

- **C3 LLM failure during retype** (`LlmUnavailable`, `LlmTimeout`, `LlmSchemaViolation` after retry): C3 publishes `ExtractionFailed`. C4 `ExtractionEventListener` sets `reextractionStatus = FAILED` and publishes `DocumentStateChanged`. C5 fans out via SSE. **C6** renders the FAILED banner per C6-R8 / AC6.3. The original `POST /review/retype` 202 response is unaffected.
- **C4 `StageGuard` rejection** (no transition matches): `WorkflowEngine` throws `InvalidAction`. C5 maps to `INVALID_ACTION` 409 with `{ "code": "INVALID_ACTION", "message": "..." }`. C6's mutation `onError` shows a generic error toast and refetches the document.
- **C4 `Flag` with empty comment**: `WorkflowEngine` throws `ValidationFailed { details: [{ path: "comment", message: "must be non-empty" }] }`. C5 maps to `VALIDATION_FAILED` 400 with field-level `details`. C6 displays inline field error.
- **C4 retype while already in progress**: `WorkflowEngine` throws `ExtractionInProgress` → C5 `REEXTRACTION_IN_PROGRESS` 409.
- **C2 unknown org / unsupported MIME**: thrown before any DB write; surfaces directly as `UNKNOWN_ORGANIZATION` 404 / `UNSUPPORTED_MEDIA_TYPE` 415.
- **Optimistic-lock overflow on `WorkflowInstance`** (after one retry): falls through to the catch-all `INTERNAL_ERROR` 500. C6 shows generic "Save failed; refresh and retry" toast. There is no dedicated `STALE_VERSION` code (Round-1 Issue 11 resolved via Option a).

### 5.4 Transaction boundaries

| Operation | Transaction scope |
|---|---|
| C2 upload | One transaction: INSERT `stored_documents` + INSERT `processing_documents`. FS write happens **before** the transaction (atomic-move). Event publish happens **after** commit. |
| C3 per-step persistence | One transaction per LLM call boundary: UPDATE `processing_documents.current_step` (and `raw_text` / `last_error` as applicable) + INSERT `llm_call_audit`. |
| C3 → C4 handoff (`ProcessingCompleted`) | C4 `ProcessingCompletedListener` is `@Transactional`: INSERT `documents` + INSERT `workflow_instances` + publish `DocumentStateChanged` happen in one transaction. Failure mid-listener rolls both INSERTs back; no `DocumentStateChanged` is emitted. Tested in `ProcessingCompletedListenerIT`. |
| C4 `applyAction` | One transaction: UPDATE `workflow_instances` (and `documents` if retype) + publish `DocumentStateChanged`. |
| C4 retype completion (`ExtractionCompleted` / `ExtractionFailed`) | One transaction per listener invocation: UPDATE `documents` + UPDATE `workflow_instances` (clearFlag) + publish event. |
| C7 seed (C7-R4) | One transaction per manifest entry: INSERT `stored_documents` + INSERT `documents` + INSERT `workflow_instances`. Whole-seeder failure rolls all entries back. |

### 5.5 Concurrency

- **`WorkflowInstance.updated_at` optimistic lock** (C4 §7). UPDATE checks the prior `updated_at`; on collision, the writer retries once. Second collision falls through to `INTERNAL_ERROR` 500.
- **Retype concurrency guard** (C4-R6 + C5 §3.4 `REEXTRACTION_IN_PROGRESS`). `WorkflowEngine.applyAction` checks `Document.reextractionStatus != IN_PROGRESS` before invoking the retype path; concurrent retype attempts return 409 with `REEXTRACTION_IN_PROGRESS`.
- **Pipeline parallelism** (C3 §3.11). Multiple `ProcessingDocument`s can run concurrently, bounded by the virtual-thread `TaskExecutor`. A single doc is processed sequentially (text-extract → classify → extract).
- **SSE fan-out** (C5 §3.3). Per-org emitters in a `ConcurrentHashMap<String, Set<SseEmitter>>`; `@Async @EventListener` writes to all emitters for the event's org. `emitter.completeWithError(t)` cleans up failed emitters via the `onError` callback; other emitters are unaffected.

### 5.6 Observability

None beyond stdout logging in this take-home. No Prometheus metrics, no distributed tracing, no centralized logging pipeline. Per `01-problem-space.md` non-goals.

---

## 6. Integration testing strategy

Testing layers:

| Layer | Owner | Trigger |
|---|---|---|
| Unit + property tests | per-component spec | `make test` (fast gate) |
| Integration tests (cross-spec seams) | this section | `make test` |
| E2E (Playwright) | C6 | `make e2e` (excluded from `make test`) |
| Live LLM eval | C3 | `make eval` (on-demand only) |

### 6.1 Unit + property tests (owned by individual specs)

- **C1:** `ConfigLoaderTest`, `ConfigValidatorTest` (CV-1..CV-8), `OrganizationCatalogIT` (Testcontainers), `FourthOrgSeederTest` (C1-R9), `GrepForbiddenStringsTest`.
- **C2:** `StoredDocumentIngestionServiceImplTest`, `FilesystemStoredDocumentStorageTest`.
- **C3:** `ProcessingPipelineOrchestratorTest`, `LlmExtractorTest`, `ToolSchemaBuilderTest` (determinism), `LlmCallAuditWriterTest` (CHECK-constraint enforcement), `EvalScorerTest`, `EvalReportWriterTest`.
- **C4:** `WorkflowEnginePropertyTest` (jqwik, no Spring context per C4-R9a), `WorkflowEngineExampleTest` (per workflow), `FlagOriginRestorationTest` (C4-R9b matrix), `WorkflowInstanceWriterTest` (C4-R12).
- **C5:** controller unit tests per endpoint, contract test asserting RFC 7807 shape per error code.
- **C6:** Vitest component tests per C6-R13 (StageProgress, ReviewForm per client schema, FlagModal, ReclassifyModal). Coverage gate 70 line / 60 branch.
- **C7:** `MissingApiKeyStartupTest`, `GrepForbiddenStringsFileTest`, `DocumentEventBusTest`.

### 6.2 Integration tests (cross-spec seams)

Three suites that explicitly exercise integration boundaries:

1. **`HappyPathSmokeTest` (C5 §6).** `@SpringBootTest(webEnvironment = RANDOM_PORT)` + Postgres Testcontainer. Drives the upload → pipeline → handoff path entirely through HTTP: `POST /api/organizations/pinnacle-legal/documents` → wait for `ProcessingCompleted` → `GET /api/organizations/pinnacle-legal/documents` asserts the document appears in `documents[]` with `currentStatus = AWAITING_REVIEW` → `POST /api/documents/{id}/actions { "action": "Approve" }` advances one stage. **This is the only HTTP-seam integration test for the take-home** (per the C5 spec scope cut). Live Anthropic API calls inside; gated on `ANTHROPIC_API_KEY`.

2. **`ProcessingCompletedListenerIT` (C4 §6) and `RetypeFlowIT` (C4 §6).** Drive the C3 ↔ C4 event boundary directly. `RetypeFlowIT` exercises the retype path through `LlmExtractor.extract`. **Decision (Round-1 review carried into Pass 6):** `RetypeFlowIT` uses a **stubbed `LlmExtractor`** that emits `ExtractionCompleted` / `ExtractionFailed` deterministically — the live API is exercised by `HappyPathSmokeTest` and the on-demand `make eval`, not by `RetypeFlowIT`. Rationale: `RetypeFlowIT` is testing C4's listener semantics and `reextractionStatus` transitions, not the LLM call shape; live API calls would make the test flaky and slow without adding cross-spec coverage.

3. **`SeedManifestTest` (C7).** Asserts that after first-boot seed, the `documents` table set matches `seed/manifest.yaml` exactly (org, type, fields, no `ProcessingDocument` rows for seeded data).

The HTTP-seam smoke is intentionally narrow per the C5 spec ("This is the only HTTP-seam integration test (per scope cut). Other layers — controllers, error handler, read-model, SSE fan-out — are tested independently in unit / contract tests.").

### 6.3 E2E tests (C6, `make e2e`)

Playwright against the running stack (`docker compose up`). Two scenarios per C6-R14:

- `happy-path.spec.ts` — Pinnacle Invoice end-to-end: upload → processing → Review → Approve through Attorney → Billing → Filed.
- `flag-and-resolve.spec.ts` — exercises C4-R6 origin restoration end-to-end.

Excluded from `make test` (long-running, requires running stack). Run on demand and in CI after `make test` passes.

### 6.4 Live LLM eval (C3, `make eval`)

Out of CI entirely. `EvalRunner` runs classify + extract over the labeled `seed/manifest.yaml` corpus against the live Anthropic API and writes `eval/reports/latest.md` with aggregate classification accuracy and aggregate extract field accuracy. On-demand only (typically run before submission).

---

## 7. Outstanding follow-ups for Pass 7 (Tasks)

Items deferred from earlier passes that Pass 7 should turn into concrete tasks. None of these block integration; they are documented gaps acknowledged across the specs.

1. **`ProcessingDocument` cleanup cron is not implemented.** On successful pipeline completion, C3 leaves the `ProcessingDocument` row in place. The dashboard query (C5-R3) filters out completed-but-undeleted rows by joining against `documents`. Production would add a cron-style cleanup deleting `ProcessingDocument` rows whose `storedDocumentId` already has a `Document`. Acknowledged in 03-components.md C7-R9 and C3 §3.8. **Pass 7 task:** none — acknowledged as out-of-scope for the take-home.

2. **jqwik-spring Spring Boot 4 support not yet confirmed upstream.** Mitigation in place per C4-R9a: `WorkflowEngine` is constructor-injected with `WorkflowCatalog`, so the property suite runs with no Spring context (research §7). **Pass 7 task:** confirm at implementation time that jqwik 1.9.x + JUnit Platform engine work cleanly without `jqwik-spring`; if not, remove `@SpringBootTest` from any property test that accidentally requires it.

3. **Stop hook cadence risk (full check creep on slow machines).** `make test` runs Spotless + Checkstyle + PMD + JaCoCo + Error Prone + grep + unit + property tests + frontend lint/typecheck/test. Research §8 noted full check can creep past 60–90s on slow machines. Mitigation deferred unless measured. **Pass 7 task:** during implementation, time `make test` on the dev machine; if it consistently exceeds the Stop hook's 600s timeout, split into `PreToolUse` (format + grep) + `Stop` (full check) per C7 §7.

4. **Round-1 Issue 15 (`SyntheticFourthOrgTest` overlap with C1's `FourthOrgSeederTest`).** Round-2 verified C7 spec did not add `SyntheticFourthOrgTest`; concern was hypothetical. **Pass 7 task:** none — already resolved.

5. **Round-2 cosmetic notes** (deferred from review):
   - C3 §3.5 prose lowercase `text`/`pdf` should match C1's `enum InputModality { TEXT, PDF }` casing. Documentation polish.
   - C4-R6 row's "(asynchronous)" parenthetical could be sharpened to "(synchronous call; C3 emits ExtractionCompleted/ExtractionFailed asynchronously)" to remove residual ambiguity.

   **Pass 7 task:** optional spec cleanup; does not affect implementation.

6. **Round-2 follow-up on `inputModality` in C3 prose vs. C1 enum.** Resolved per Round-2 (C1 §3.2 carries the canonical enum; C3 §3.5 references the field by name). **Pass 7 task:** ensure implementation passes `inputModality` from `DocTypeDefinition` directly to the orchestrator's modality choice, no string conversion.

7. **No-strict typo in 03-components.md C5-R9a.** Round-2 verified the 03-components.md C5-R9a row enumerates the 11 codes inline and never uses an "8-code" label. The C5 spec's reflective note about a misnomer is harmless. **Pass 7 task:** none.

8. **`AppConfig` nested-record enumeration in C7 §3 / §4** (Round-2 follow-up). This integration document (§3.2 above) is the natural venue for the canonical enumeration. **Pass 7 task:** when implementing C7's `AppConfig.java`, use the structure documented here.
