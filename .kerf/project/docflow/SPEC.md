# DocFlow — Implementation Spec

> DocFlow is a multi-client document processing platform for an outsourced bookkeeping firm: pick a client, upload a PDF/image, the system uses an LLM to classify the document type and extract structured fields, then the document flows through a per-client (Review → Approval(s) → Filed/Rejected) workflow with flag-and-resolve. **This spec is the implementing agent's starting point** — it is curatorial: a guided tour over the seven component specs in `05-specs/`, the integration plan in `06-integration.md`, and the requirements catalog in `03-components.md`. Read this top to bottom (≈10 minutes), then dive into the linked detail files when you need specifics.

---

## 1. Problem in one page

**What we're building.** A multi-tenant document-processing app: org picker → per-org dashboard → upload → live classify+extract pipeline → Review form → workflow stages → Filed/Rejected. Three real clients (Riverside Bistro, Pinnacle Legal Group, Ironworks Construction) with nine `(client, doc-type)` combinations, all driven by declarative config so adding a fourth client is a data change, not a code change.

**Why the four (really three) clients differ.** Each org has its own document types, its own field schemas (Pinnacle Invoice has `lineItems`, Ironworks has `materials`, Riverside Receipt has neither), and its own workflow chain (Riverside Receipt is just `Review → Filed`; Pinnacle Invoice routes through Attorney → Billing; Ironworks Lien Waiver has a conditional `unconditional → Filed` branch). Any backend or UI logic that hardcodes a stage name or client slug is a bug — those strings live in C1 config.

**Success criteria** (condensed from `01-problem-space.md` §Success criteria):
- `make build && make start` brings up the full stack from a fresh clone with only `ANTHROPIC_API_KEY` set.
- A user can pick any of the three clients, upload a file, watch it progress live via SSE through Classify → Extract → Review, edit fields, change type (re-extract), approve through the configured stages, or reject.
- Lien Waiver `waiverType == "unconditional"` skips Project Manager Approval (Review → Filed).
- All nine `(org, doc-type)` workflows work end-to-end with their correct field schemas.
- Backend ≥70% line coverage (JaCoCo) with property-based tests on the workflow engine; integration tests cover the cross-spec seams; one E2E happy path + one flag-and-resolve scenario.
- Schema is 3NF or better, FKs everywhere, indexes on every FK and common query column, single-baseline Flyway migration `V1__init.sql`.
- Lint + format + static analysis + grep-forbidden-strings wired into the build; the Stop hook enforces `make test` exit 0.
- LLM eval (`make eval`) reports aggregate classification accuracy and aggregate extraction-field accuracy against the labeled seed manifest.

**Out of scope.** Auth, S3, hot-reload of config, document deletion, concurrent edits, encrypted PDFs, full-text search, dashboard pagination, OpenAPI docs, recorded-eval replay, tune/holdout split, per-org stage-name filter on the API, `previousStage`/`previousStatus` on events, surgical SSE cache updates, `Last-Event-ID` SSE resume, `ProcessingDocument` cleanup cron, `ProcessingDocument` detail route, user-initiated pipeline retry, stage-history audit table. Curated list lives in `HANDOFF.md` §"Out of Scope" — see also §8 below.

**Pointer.** Full problem space (goals, constraints, deferred items) in `01-problem-space.md`.

---

## 2. System at a glance

**Architecture style.** Sealed-package boundaries inside one Spring Boot backend application + a React SPA frontend. Each backend bounded context (C1–C5) owns its own tables and its own writers; cross-context reads go through two shared read-model projections (`ProcessingDocumentSummary`, `DocumentView`). C7 is a peer that provides scaffolding (event bus, AppConfig, build/CI/Makefile) but nothing at runtime calls into it. No microservices, no separate workflow service — micro-lith with strict module discipline.

**Components in one sentence each:**

- **C1 — Config Layer.** Owns the declarative definitions (organizations, document-type field schemas, workflows with stages and transitions); seeds them from YAML to DB on first install, then DB is authoritative; exposes typed read catalogs to the rest of the backend.
- **C2 — Document Ingestion & Storage.** Accepts uploads, stores file bytes via a pluggable filesystem-backed `StoredDocumentStorage`, transactionally inserts an immutable `StoredDocument` plus the initial `ProcessingDocument`, signals C3 to start the pipeline.
- **C3 — Processing Pipeline & Eval.** Runs text-extract (PDFBox) → classify → extract (Anthropic tool use) on each `ProcessingDocument`, audits every LLM call to `llm_call_audit`, exposes `LlmExtractor.extract` for retype, and ships an on-demand eval harness scoring the labeled seed manifest.
- **C4 — Workflow Engine.** Owns `Document` (post-processing) and `WorkflowInstance`; subscribes to `ProcessingCompleted` to materialize them; runs `applyAction` (Approve/Reject/Flag/Resolve) by iterating configured transitions and evaluating `StageGuard` predicates; emits `DocumentStateChanged`.
- **C5 — API & Real-Time.** REST controllers for orgs/documents/actions plus one `text/event-stream` endpoint per org carrying `ProcessingStepChanged` and `DocumentStateChanged`. Has no tables of its own — composes read-models.
- **C6 — Frontend (React SPA).** React 19 + Vite + TypeScript strict. Routes: org picker → dashboard (stats bar + processing list + documents list) → document detail (PDF preview + dynamic form per stage). Subscribes to per-org SSE; refetches on any event.
- **C7 — Platform & Quality.** docker-compose, single-baseline Flyway migration, Gradle build with Spotless/Checkstyle/PMD/JaCoCo/Error Prone/`grepForbiddenStrings`, npm build with ESLint/Prettier/tsc-strict/Vitest, Makefile (`make test` is the fast gate), Stop hook, CI workflow, `DocumentEventBus` Spring wrapper, `AppConfig` typed startup binding.

**Pointer.** Component overview table, dependency DAG, and full requirements in `03-components.md`. Per-component change specs in `05-specs/c{N}-*-spec.md`.

---

## 3. Canonical vocabulary (load-bearing tokens)

These are the names an implementer hits on day 1. Use them exactly.

### Entities (the 3-entity document lifecycle)

| Token | Owner | Definition |
|---|---|---|
| **`StoredDocument`** | C2 | Immutable file reference: `id`, `organizationId`, `uploadedAt`, `sourceFilename`, `mimeType`, `storagePath`. Once written, never updated. Lives forever. |
| **`ProcessingDocument`** | C3 | Transient pipeline record. Exists only while a doc is being classified/extracted. Carries `currentStep`, `rawText`, `lastError`. Deleted on successful pipeline completion **in production**; left in place in the take-home (dashboard query filters undeleted rows by JOIN). |
| **`Document`** | C4 | Processed, workflow-ready entity. Created when C3's pipeline completes. Carries `detectedDocumentType`, `extractedFields`, `rawText`, `processedAt`, `reextractionStatus`. Lives forever. |
| **`WorkflowInstance`** | C4 | Per-`Document` workflow state: `currentStageId`, `currentStatus`, `workflowOriginStage` (nullable; set when flagged), `flagComment` (nullable), `updatedAt`. Always starts at `Review`. |

### Status / step enums

| Token | Owner | Values |
|---|---|---|
| **`WorkflowStatus`** (canonical) | C1 / C4 | `AWAITING_REVIEW`, `FLAGGED`, `AWAITING_APPROVAL`, `FILED`, `REJECTED` (5 values). The domain-level vocabulary every filter, stat, and query rolls up to. Each stage in config declares a `canonicalStatus` mapping (except `FLAGGED`, which is a runtime override applied when a Review stage has a non-null `workflowOriginStage`). |
| **`canonicalStatus`** | C1 | The `WorkflowStatus` value that a stage rolls up to. Property of stage config. |
| **`currentStep`** (processing) | C3 | `TEXT_EXTRACTING`, `CLASSIFYING`, `EXTRACTING`, `FAILED` (4 values). Owned by `ProcessingDocument` only — never mixed with `WorkflowStatus`. Pipeline finishes before any `Document`/`WorkflowInstance` exists. |
| **`reextractionStatus`** | C4 | `NONE`, `IN_PROGRESS`, `FAILED` on `Document`. Communicates retype lifecycle to the UI; no separate retype events. |

### Workflow-engine pieces

| Token | Owner | Definition |
|---|---|---|
| **Stage** | C1 | `id`, `displayName`, `kind ∈ {review, approval, terminal}`, `canonicalStatus`, optional `role` slot. |
| **Transition** | C1 | `(fromStage, action, toStage, guard?)` where `action ∈ {AutoAdvance, Approve, Reject, Flag, Resolve}`. |
| **`StageGuard`** | C1 | Optional predicate over `Document.extractedFields` (field path + op + literal). Used to express the Lien Waiver `waiverType == "unconditional"` branch. |
| **`WorkflowEngine`** | C4 | `applyAction(documentId, action, payload)`; iterates configured transitions for the current stage, evaluates the first matching guard, calls `WorkflowInstanceWriter.advanceStage / setFlag / clearFlag`. |

### Cross-cutting platform pieces

| Token | Owner | Definition |
|---|---|---|
| **`DocumentEventBus`** | C7 | Wrapper over Spring's `ApplicationEventPublisher`; `@Async @EventListener` subscribers run on virtual threads. The single seam between C2 → C3, C3 → C4, C3/C4 → C5. |
| **`AppConfig`** | C7 | The single typed immutable record tree (`Llm`, `Storage`, `Database`, `OrgConfigBootstrap`) bound from env + `application.yml` via `@ConfigurationProperties` + `@Validated`. The **only** code that reads `System.getenv` / `@Value` lives under `com.docflow.config`. |
| **`grepForbiddenStrings`** | C7 | Custom Gradle task scanning `backend/src/main/java`. Forbidden patterns (stage names, client slugs, `System.getenv`, `@Value`, `.env` references) live in `config/forbidden-strings.txt` (C1-owned). Wired into `check`. |
| **`OrganizationCatalog` / `DocumentTypeCatalog` / `WorkflowCatalog`** | C1 | Typed read APIs over the (DB-backed) client-data tables, loaded into immutable in-memory `*View` records at startup. Constructor-injected into consumers (notably `WorkflowEngine` per C4-R9a). |
| **`ProcessingDocumentSummary` / `DocumentView`** | shared `read-model` package | The two cross-context read projections returned together by the dashboard list endpoint. `ProcessingDocumentSummary` is the in-flight projection; `DocumentView` joins `Document` + `WorkflowInstance` + `StoredDocument` + stage display metadata. |

### Canonical events (5 distinct events; 6 if counting `ExtractionCompleted`/`ExtractionFailed` separately)

| Event | Publisher | Visibility |
|---|---|---|
| **`StoredDocumentIngested`** | C2 (post-commit) | internal |
| **`ProcessingStepChanged`** | C3 (every step transition incl. `FAILED` with `error`) | **SSE-visible** |
| **`ProcessingCompleted`** | C3 (terminal pipeline success) | internal |
| **`ExtractionCompleted` / `ExtractionFailed`** | C3 (retype only) | internal |
| **`DocumentStateChanged`** | C4 (every state transition + retype lifecycle via `reextractionStatus` field) | **SSE-visible** |

C5's SSE stream carries only the two **SSE-visible** events. The internal-only events never leave the JVM.

---

## 4. Tech stack and platform

**Backend.** Java 25 (Temurin), Spring Boot (latest GA on the analysis branch), Gradle (Kotlin DSL). Persistence: Spring Data JPA + PostgreSQL 16. Migrations: Flyway with a single baseline `V1__init.sql`. PDF text extraction: Apache PDFBox. Async: virtual threads (`spring.threads.virtual.enabled=true`) + `@Async @EventListener`. Validation: Jakarta Bean Validation. Tests: JUnit 5 + jqwik (property-based) + Testcontainers for the cross-spec integration suites.

**Frontend.** React 19 + Vite 8 + TypeScript strict. Routing: React Router v7. Server state: TanStack Query. Forms: `react-hook-form` v7 + `zod` + `useFieldArray` (for line-items / materials / items). PDF viewer: `react-pdf` v10. Tests: Vitest 3 + React Testing Library v16 + `@testing-library/user-event` v14 + jsdom + MSW 2. E2E: Playwright 1.55+. Coverage gate: 70 line / 60 branch.

**LLM.** `claude-sonnet-4-6` via the Anthropic Java SDK for both classify and extract, sourced from `AppConfig.Llm.modelId` (no hardcode). Tool use is forced via `tool_choice` with a tool schema generated deterministically from the C1 doc-type schema. **No streaming, no Files API** in this take-home — content blocks only. C3 owns retry semantics (one retry on `LlmSchemaViolation` for extract; no retry on transport errors); SDK built-in retry is disabled.

**Build/run.** A `Makefile` at the repo root is the unified developer surface. Targets: `make build` (docker images), `make start` / `make up` (full stack), `make stop` / `make down`, `make test` (the fast gate — backend `./gradlew check` then frontend `npm run check`), `make e2e` (Playwright against the running stack), `make eval` (live LLM eval, on-demand only). The Stop hook in `.claude/settings.json` and the CI workflow both invoke `make test` — single source of truth.

**Pointer.** Full Makefile + Gradle + docker-compose + Flyway baseline detail in `05-specs/c7-platform-spec.md`. Anthropic SDK wrapper and prompt resource layout in `05-specs/c3-pipeline-spec.md`.

---

## 5. Components

Each subsection: two-sentence summary, pointer to spec, and the 1–3 most load-bearing canonical decisions.

### C1 — Config Layer

Owns the declarative definitions that make DocFlow multi-tenant: organizations, document-type field schemas (with nested arrays-of-objects), workflows with stages and transitions and optional `StageGuard`s. Loaded from YAML seed fixtures into client-data DB tables on first install via the `OrgConfigSeeder`; subsequent startups read from DB.

**Pointer.** `05-specs/c1-config-spec.md`.

**Key decisions.**
- **Client data lives in DB; YAML is only the seed fixture (C1-R11).** Once seeded, the DB is authoritative; production data changes are new Flyway migrations.
- **`WorkflowStatus` is canonical and few; stage names are per-org and many (C1-R12).** Every stage carries a `canonicalStatus` mapping. `FLAGGED` is the only status never declared on a stage — it's a runtime override applied when a Review stage has a non-null `workflowOriginStage`.
- **`grepForbiddenStrings` is enforced (C1-R7 + C7-R5).** Forbidden patterns live in `config/forbidden-strings.txt` (C1-owned); the Gradle task fails the build if any literal stage name or client slug appears in `backend/src/main/java/**/*.java` outside config and tests.

### C2 — Document Ingestion & Storage

Accepts PDF/PNG/JPEG uploads, sniffs MIME, persists bytes via a pluggable `StoredDocumentStorage` interface (single filesystem implementation), and **in one DB transaction** writes the immutable `StoredDocument` plus the initial `ProcessingDocument` (`currentStep = TEXT_EXTRACTING`). After commit, publishes `StoredDocumentIngested` so C3 starts the pipeline.

**Pointer.** `05-specs/c2-ingestion-spec.md`.

**Key decisions.**
- **`StoredDocument` is immutable (C2-R4).** No `rawText`, no `currentStage`, no flag fields — those live on `ProcessingDocument` (C3) and `Document` (C4). One row, write-once.
- **FS write happens before DB commit (C2-R9a).** Atomic-move via tmp file + `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`. On crash recovery, stray files (no DB row) are easier to clean than stray rows (no file). `storagePath` is derived deterministically from `StoredDocument.id` (`{storageRoot}/{id}.bin`).
- **Storage seam isolated behind `StoredDocumentStorage` (C2-R7).** Swap to S3 = new implementation, no consumer change.

### C3 — Processing Pipeline & Eval

Orchestrates `ProcessingDocument` through `TEXT_EXTRACTING → CLASSIFYING → EXTRACTING`; classify and extract both call Anthropic via the SDK with forced tool use; every LLM call writes a row to `llm_call_audit`. Exposes `LlmExtractor.extract(documentId, docTypeId)` for C4 to invoke during retype, and ships an `EvalRunner` that runs the labeled seed manifest against the live API on demand and prints a markdown report.

**Pointer.** `05-specs/c3-pipeline-spec.md`.

**Key decisions.**
- **Every LLM call is audited; FK shape is always-populated `stored_document_id` plus mutually-exclusive `processing_document_id` and `document_id` (C3-R5a + C7 §3.3).** Enforced by `CHECK ((processing_document_id IS NOT NULL AND document_id IS NULL) OR (processing_document_id IS NULL AND document_id IS NOT NULL))`. Initial-pipeline calls reference `processing_document_id`; retype calls reference `document_id`.
- **Retype re-extraction is an UPDATE on `Document`, not a new row (C3-R13).** Prior `extractedFields` not preserved beyond the audit row's metadata — accepted simplification for the take-home.
- **C3 → C4 is event-only; C4 → C3 is a single sync call.** `ProcessingCompleted`, `ExtractionCompleted`, `ExtractionFailed` are internal-only bus events; C4 subscribes. `LlmExtractor.extract` is the only sync call from C4 into C3 (retype path).
- **Aggregate-only eval, live API only (C3-R7..R9).** Single labeled set in `seed/manifest.yaml`; no tune/holdout split; no recorded replay; CI does not run the eval.

### C4 — Workflow Engine

Owns `Document` (created on `ProcessingCompleted`) and `WorkflowInstance`. `WorkflowEngine.applyAction` looks up the matching configured `Transition`, evaluates its optional `StageGuard` against `Document.extractedFields`, and persists both `currentStageId` and `currentStatus` atomically through `WorkflowInstanceWriter`. Emits `DocumentStateChanged` on every transition.

**Pointer.** `05-specs/c4-workflow-spec.md`.

**Key decisions.**
- **`currentStatus` is computed at write time (C4-R12).** Rule: if `currentStage.kind = 'review'` AND `workflow_origin_stage IS NOT NULL` → `FLAGGED`; otherwise `currentStage.canonicalStatus`. Every writer (`advanceStage`, `setFlag`, `clearFlag`) sets both fields atomically.
- **Workflow engine is config-driven and contains zero literal stage strings (C4-R10).** Property-based tests (`jqwik`) generate random-shape workflows via a constructor-injected `WorkflowCatalog` (C4-R9a) so the suite isn't limited to the nine seeded workflows.
- **`ProcessingCompleted` handoff is one transaction (C4-R13).** INSERT `Document` + INSERT `WorkflowInstance` (with `currentStageId = Review`, `currentStatus = AWAITING_REVIEW`) + publish `DocumentStateChanged`. Atomic; tested.
- **Retype lifecycle communicated via `reextractionStatus` field on `DocumentStateChanged` (C4-R6 + C5-R8).** No separate retype events. `Document.currentStage` stays `Review` throughout retype; `reextractionStatus` cycles `NONE → IN_PROGRESS → NONE` (or `FAILED`).

### C5 — API & Real-Time

REST controllers for orgs, documents, and actions, plus one `text/event-stream` endpoint per org. `GET /api/organizations/{orgId}/documents` returns the dashboard payload as `{ processing[], documents[], stats }` in one response. The action endpoint accepts a discriminated union keyed on `action` and delegates to `WorkflowEngine.applyAction`. Has no tables of its own — composes read-models.

**Pointer.** `05-specs/c5-api-spec.md`.

**Key decisions.**
- **Two endpoints, not one, for Review-stage editing (C5-R7).** `PATCH .../review/fields` saves edited `extractedFields`; `POST .../review/retype` triggers re-extraction. Splits "save" from "reclassify."
- **Filter API speaks canonical `WorkflowStatus` only (C5-R3).** No per-org stage-name filter exists. Status options are the fixed five; `docType` filter values come from C1 per-org config.
- **Enumerated error-code vocabulary (C5-R9a).** RFC 7807 ProblemDetail body shape `{ code, message, details? }`; codes include `UNKNOWN_ORGANIZATION`, `UNSUPPORTED_MEDIA_TYPE`, `VALIDATION_FAILED`, `INVALID_ACTION`, `REEXTRACTION_IN_PROGRESS`, `LLM_UNAVAILABLE`, `INTERNAL_ERROR`. Contract test asserts the shape per code.
- **One SSE stream per org carrying exactly two event types (C5-R8).** `ProcessingStepChanged` + `DocumentStateChanged`. Frames carry `id:` (monotonic per connection) and `event:` lines; `retry: 5000` hint sent on connect. `Last-Event-ID` resume is out of scope — clients reconnect and rehydrate from the list endpoint.

### C6 — Frontend (React SPA)

Three routes (`/`, `/org/:orgId/dashboard`, `/documents/:documentId`); selected org is in the URL path, not client storage. Dashboard renders stats bar + Processing section + Documents section from one fetch + one SSE stream per org; on **any** event, refetches the dashboard query (no surgical cache mutations).

**Pointer.** `05-specs/c6-frontend-spec.md`.

**Key decisions.**
- **Form panel content is keyed off `currentStage` + `reextractionStatus` (C6-R8).** Five distinct states: re-extract IN_PROGRESS (banner, disabled), re-extract FAILED (banner, prior values), Review (editable + dropdown + Approve/Reject), Review-flagged (banner + Resolve replaces Approve), Approval (read-only summary + Approve/Flag, role label if configured), Filed/Rejected (read-only + Back to Documents).
- **Array-of-object fields (`lineItems`, `materials`, `items`) are inline-editable in Review only (C6-R10).** Read-only tables in Approval / Filed / Rejected.
- **Stage progress indicator synthesizes pre-workflow processing steps with workflow stages (C6-R9)** into one visual sequence, joined client-side from `ProcessingDocument.currentStep` (in flight) or `WorkflowInstance.currentStageId` (processed). Rejected terminal: incoming segment + `Rejected` rendered red; never-reached approval stages muted.
- **No `/processing-documents/{id}` route (C6-R7).** Processing rows are non-clickable; the dashboard already shows everything the user needs (filename, step badge, inline failure). Recovery from `FAILED` is re-upload.

### C7 — Platform & Quality

Project connective tissue: docker-compose, single-baseline Flyway migration, Gradle backend build (Spotless/Checkstyle/PMD/JaCoCo 70%/Error Prone/`grepForbiddenStrings`), npm frontend build (ESLint/Prettier/tsc-strict/Vitest), Makefile, Stop hook, CI workflow, `DocumentEventBus`, typed `AppConfig`. Not a runtime component but everything else depends on its scaffolding.

**Pointer.** `05-specs/c7-platform-spec.md`.

**Key decisions.**
- **One source of truth for the fast gate: `make test` (C7-R14).** Both the Stop hook (C7-R7) and the CI workflow (C7-R10) invoke `make test`. Excludes Playwright E2E (`make e2e`) and live LLM eval (`make eval`).
- **`AppConfig` is the only code that reads env (C7-R13).** Records + `@ConfigurationProperties` + `@Validated`. Missing/blank `ANTHROPIC_API_KEY` fails startup with `BindValidationException`, never a runtime 500. `grepForbiddenStrings` rejects any `System.getenv` / `@Value` / `.env` reference outside `com.docflow.config`.
- **Single Flyway baseline `V1__init.sql` (C7-R3).** Creates all tables in one file: client-data tables (C1) → `stored_documents` (C2) → `processing_documents` (C3) → `documents` + `workflow_instances` (C4) → `llm_call_audit` (C3). Future schema changes are additive `V2__*`, never editing V1.
- **Seed data bypasses `ProcessingDocument` entirely (C7-R4).** `seed/manifest.yaml` lists 12 of 23 sample PDFs with ground-truth `documentType` and `extractedFields`; one transaction per entry inserts `StoredDocument` + `Document` + `WorkflowInstance` (`currentStageId = Review`, `currentStatus = AWAITING_REVIEW`). No LLM call at seed time.

---

## 6. Integration & data flows

**Pointer.** Full integration plan in `06-integration.md`: edge contracts table, initialization order with failure-mode boundaries, shared state (DB, `AppConfig`, `DocumentEventBus`, file storage, LLM client, catalogs), end-to-end flows, cross-cutting concerns (logging, error propagation, transaction boundaries, concurrency), and the integration testing strategy. The summaries below are the 30-second version.

### Three flows (high level)

**4.1 Upload flow.** C6 POSTs multipart to C5 → C5 calls `StoredDocumentIngestionService.upload` (C2) → C2 validates org via `OrganizationCatalog` (C1), sniffs MIME, writes bytes to FS atomically, then in one DB transaction INSERTs `stored_documents` + `processing_documents (currentStep = TEXT_EXTRACTING)` and post-commit publishes `StoredDocumentIngested` → C3 `PipelineTriggerListener` runs the orchestrator (TEXT_EXTRACTING → CLASSIFYING → EXTRACTING), emits a `ProcessingStepChanged` per transition (SSE-visible) and writes an `llm_call_audit` per LLM call → on terminal success C3 publishes `ProcessingCompleted` (internal) → C4 `ProcessingCompletedListener` in one transaction INSERTs `Document` + `WorkflowInstance (currentStageId = Review, currentStatus = AWAITING_REVIEW)` and publishes `DocumentStateChanged` → C5's `SsePublisher` fans out the two SSE-visible events to per-org emitters → C6 invalidates the dashboard query and refetches.

**4.2 Review/Approve flow.** C6 POSTs `{ action: "Approve" }` to `/api/documents/{id}/actions` → C5 deserializes the discriminated union and calls `WorkflowEngine.applyAction` (C4) → C4 picks the first matching configured `Transition` (evaluating `StageGuard` against `Document.extractedFields` — this is where the Lien Waiver `waiverType` branch is decided), calls `WorkflowInstanceWriter.advanceStage` (which sets `currentStageId` and the derived `currentStatus` atomically), publishes `DocumentStateChanged`, returns the updated `DocumentView` → C5 responds 200 and (independently, on the async listener) fans out the SSE event → C6 refetches. Reject / Flag / no-type-change Resolve follow the same shape with different transition selections and writer methods (`advanceStage` for Reject, `setFlag` for Flag, `clearFlag` for plain Resolve).

**4.3 Retype flow.** C6 POSTs `{ newDocumentType }` to `/api/documents/{id}/review/retype` → C5 calls `WorkflowEngine.applyAction(documentId, Resolve, { newDocTypeId })` → C4 validates `reextractionStatus != IN_PROGRESS` (else 409 `REEXTRACTION_IN_PROGRESS`), sets `Document.reextractionStatus = IN_PROGRESS`, publishes `DocumentStateChanged` (SSE-visible), then synchronously invokes `LlmExtractor.extract` (C3) — the call returns quickly because C3 publishes the result event asynchronously → C5 has already responded `202 Accepted { reextractionStatus: "IN_PROGRESS" }` → C3 calls Anthropic with `extract_<newDocType>` tool, writes `llm_call_audit` with `document_id` populated and `processing_document_id` NULL, publishes `ExtractionCompleted` (success) or `ExtractionFailed` (after one retry on schema violation) — both internal-only → C4 `ExtractionEventListener` updates `Document` + clears flag (success) or sets `reextractionStatus = FAILED`, publishes `DocumentStateChanged` → SSE fan-out → C6 refetches.

### Initialization order (one short list)

1. `AppConfig` binds env + `application.yml`; validation failure aborts before context refresh.
2. DataSource + Flyway `V1__init.sql` runs; checksum failure on a previously-applied migration aborts startup.
3. JPA repositories wire up.
4. `OrgConfigSeeder` runs `@EventListener(ApplicationReadyEvent)`; if `organizations` is empty and `seedOnBoot=true`, parses `seed/*.yaml`, validates, INSERTs in one transaction.
5. C1 catalogs (`OrganizationCatalog`, `DocumentTypeCatalog`, `WorkflowCatalog`) JPA-load into immutable in-memory views (`@DependsOn("orgConfigSeeder")`).
6. `PromptLibrary` (C3) loads `prompts/*.txt` and validates one extract prompt exists per `(orgId, docType)`; missing files fail startup.
7. C7 application-data seeder (C7-R4) inserts the seed manifest entries (`StoredDocument` + `Document` + `WorkflowInstance` per entry, no LLM call).
8. Async event listeners (`PipelineTriggerListener`, `ProcessingCompletedListener`, `ExtractionEventListener`, `SsePublisher`) become live as `@EventListener` registration completes.
9. HTTP listener opens; uploads can begin.

### Failure-mode boundaries

- **Fails startup** (loud, before serving traffic): missing/blank `ANTHROPIC_API_KEY` (`AppConfig` validation), Flyway migration error, C1 config validation failure (CV-1..CV-8 in `c1-config-spec.md` §3.4), missing prompt resource for any seeded `(orgId, docType)`, seed manifest references a missing PDF.
- **Fails at first request, not startup**: unknown `orgId` in upload URL (`UNKNOWN_ORGANIZATION` 404), upstream Anthropic 5xx (surfaced through C3 → `ProcessingDocument.currentStep = FAILED` + `ProcessingStepChanged`).

---

## 7. Definition of done

"Done" means **all** of:

- `make build` succeeds.
- `make test` exits 0 — backend `./gradlew check` (Spotless, Checkstyle, PMD, JaCoCo ≥70% line, Error Prone, `grepForbiddenStrings`, unit + property tests, integration tests except E2E and live eval) followed by frontend `npm run check` (ESLint with warnings-as-errors, Prettier `--check`, TypeScript strict, Vitest with the 70/60 coverage threshold).
- The Stop hook (`.claude/settings.json`) passes — it invokes `make test`, so this is mechanical given the above.
- `make e2e` passes against the running stack for the two Playwright scenarios (happy-path, flag-and-resolve).

If any of those fail, the task is **not** done — fix the failure, do not hand off broken work. `make eval` is on-demand (run before submission); not part of the done bar.

### Test-suite map (what runs where)

| Suite | Trigger | Notes |
|---|---|---|
| Unit + property tests (per component) | `make test` | jqwik for `WorkflowEnginePropertyTest` (no Spring context per C4-R9a). |
| Cross-spec integration tests (`HappyPathSmokeTest`, `ProcessingCompletedListenerIT`, `RetypeFlowIT`, `SeedManifestTest`) | `make test` | Testcontainers Postgres. `HappyPathSmokeTest` is the **only** HTTP-seam integration test (per the C5 scope cut) and hits the live API; `RetypeFlowIT` uses a stubbed `LlmExtractor` that emits events deterministically. |
| E2E (Playwright) | `make e2e` | `happy-path.spec.ts` + `flag-and-resolve.spec.ts`. Excluded from `make test` because it requires a running stack. |
| Live LLM eval (`EvalRunner`) | `make eval` | On-demand only. Not in CI. Aggregate classification accuracy + aggregate extract field accuracy over `seed/manifest.yaml`. |

**Pointer.** `CLAUDE.md` § "'Done' means green" carries the canonical statement; `06-integration.md` §6 is the full integration testing strategy.

---

## 8. What's out of scope

The curated list — these are intentional simplifications, not gaps to fill. Each is one bullet; no production-considerations color beyond what the source artifacts already authorize.

- **Authentication / users / roles.** The `role` slot on approval stages (C1-R10) is the future attach point.
- **Object storage (S3).** Local FS via Docker volume; the `StoredDocumentStorage` interface (C2-R7) is the swap point.
- **Hot-reload of client config.** Restart-only.
- **A 4th client beyond the three.** The data model accepts one (proven by `FourthOrgSeederTest`), but no full pipeline is built.
- **Document deletion via API.**
- **Reopening Rejected documents / Filed reversal.** Both are terminal.
- **Concurrent-edit semantics.** Single optimistic lock on `WorkflowInstance.updated_at` with one retry; second collision returns 500.
- **Encrypted / password-protected PDF support.**
- **Document full-text search.**
- **Pixel-perfect mockup parity.** Pagination is shown in the mockups but not implemented.
- **Per-page pagination on dashboard.** Soft cap of ~200 rows on `documents`; the take-home corpus stays well under it. `processing` always returned in full.
- **HTTP range-request streaming on `GET /api/documents/{id}/file`.** Sample PDFs are small.
- **`Last-Event-ID` SSE resume.** Clients reconnect and rehydrate.
- **OpenAPI documentation.** Cut.
- **Tune/verify eval split.** Single labeled set in `seed/manifest.yaml`.
- **Recorded-eval replay.** Live API only; CI does not run the eval.
- **Per-org / per-doc-type / per-field eval breakdowns.** Aggregate classification accuracy + aggregate extract field accuracy only.
- **Comprehensive HTTP-seam integration suite.** One happy-path smoke (`HappyPathSmokeTest`) covers the cross-spec HTTP path; everything else is unit / contract / per-component integration.
- **Per-org stage-name filter on the API.** Canonical `WorkflowStatus` only.
- **`previousStage` / `previousStatus` on `DocumentStateChanged`.** Consumers refetch on any event.
- **Surgical SSE cache updates.** Refetch-on-any-event.
- **`ProcessingDocument` cleanup cron.** Rows left in place; dashboard JOIN filters them out.
- **`ProcessingDocument` detail view route.**
- **User-initiated pipeline retry endpoint.** Recovery is re-upload.
- **Stage-history audit table (`document_state_transitions`).** `WorkflowInstance` is the source of truth for current state; `llm_call_audit` retains LLM-call history.
- **Prompt versioning scheme in audit.** Git is history.
- **Per-table Flyway migration split.** Single `V1__init.sql` baseline.
- **Frontend test coverage at the backend's bar.** Frontend gate is 70 line / 60 branch.

**Pointer.** `HANDOFF.md` § "Out of Scope" is the canonical curated list; `01-problem-space.md` § Non-goals carries the original framing.

---

## 9. Reading order for an implementer

1. **`SPEC.md`** (this doc) — orientation.
2. **`03-components.md`** — canonical requirements for all seven components, including the cross-component interfaces summary at the bottom.
3. **`06-integration.md`** — seams, data flows, init order, transaction boundaries, integration test strategy.
4. **`05-specs/c{N}-*-spec.md`** — the per-component change spec for whichever component you're implementing. Each one carries: requirements table, research summary, approach, files-and-changes, acceptance criteria, test plan.
5. **As needed:**
   - `01-problem-space.md` — origin goals, non-goals, constraints, deferred items.
   - `02-analysis.md` — pre-decomposition analysis (mockup walkthroughs, sample reconnaissance, schema sketches).
   - `04-research/` — Pass 4 research findings on workflow config format, LLM input modality, prompt management, frontend testing, etc.
   - `problem-statement/DocFlow_Take_Home_Exercise_greg_berns.md` — the original take-home spec; reference only, the planning artifacts are derived from this.
   - `CLAUDE.md` — non-negotiable conventions (done-discipline, lint/format/type rules, DB rules, kerf workflow).

---

## 10. Open follow-ups carrying into Pass 7 (Tasks)

These are documented gaps, not blocking issues. Pass 7 either turns them into tasks or acknowledges them as out-of-scope.

1. **Stop hook cadence on slow machines.** `make test` runs Spotless + Checkstyle + PMD + JaCoCo + Error Prone + grep + unit + property + frontend lint/typecheck/test. Research §8 noted the full check can creep past 60–90s. Mitigation deferred unless measured: if `make test` consistently exceeds the 600s Stop hook timeout in practice, split into `PreToolUse` (format + grep) + `Stop` (full check) per `c7-platform-spec.md` §7.
2. **`jqwik-spring` Spring Boot 4 support not yet confirmed upstream.** Mitigation in place per C4-R9a — `WorkflowEngine` is constructor-injected with `WorkflowCatalog` so the property suite runs with no Spring context. At implementation time, confirm jqwik 1.9.x + JUnit Platform engine work cleanly without `jqwik-spring`.
3. **`ProcessingDocument` cleanup cron not implemented (intentional).** On successful pipeline completion, C3 leaves the `ProcessingDocument` row in place; the dashboard query filters via JOIN against `documents`. Acknowledged as out-of-scope; called out in `README.md` § "Production considerations" per C7-R9.
