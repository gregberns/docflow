# C5 — API & Real-Time — Change Spec

Implementation plan for the HTTP + SSE boundary specified in `03-components.md` §C5. C5 owns no tables; it composes read-model projections (`DocumentView`, `ProcessingDocumentSummary`) and delegates writes to C2 (ingestion), C3 (retype re-extraction), and C4 (`WorkflowEngine`).

## 1. Requirements (carried forward)

| Req | Summary | Verified by |
|---|---|---|
| **C5-R1** | `GET /api/organizations` (list) and `GET /api/organizations/{orgId}` (detail). List includes `inProgressCount` (`ProcessingDocument` rows without matching `Document`) and `filedCount` (`WorkflowInstance` rows where `currentStatus = FILED`). | Smoke test |
| **C5-R2** | `POST /api/organizations/{orgId}/documents` multipart upload. 404 on unknown org, 415 on unsupported MIME. | Controller unit test + smoke test |
| **C5-R3** | `GET /api/organizations/{orgId}/documents` returns single `{ processing, documents, stats }`. `stats` is org-wide and unfiltered (`inProgress`, `awaitingReview`, `flagged`, `filedThisMonth`). Filter params (`status`, `docType`) apply only to `documents`. Soft cap 200, no pagination. | Smoke test + read-model unit tests |
| **C5-R4** | `GET /api/documents/{documentId}` returns `DocumentView`. Read-only, processed docs only. | Controller unit test |
| **C5-R5** | `GET /api/documents/{documentId}/file` streams raw bytes with correct `Content-Type`. | Controller unit test |
| **C5-R6** | `POST /api/documents/{documentId}/actions` accepts discriminated union `{ "action": "Approve" | "Reject" | "Flag" | "Resolve", ... }`. Returns updated `DocumentView`. | Controller unit test (per variant) |
| **C5-R7** | Review editing split into two endpoints: `PATCH /api/documents/{documentId}/review/fields` (save edited fields) and `POST /api/documents/{documentId}/review/retype` (returns 202 with `reextractionStatus: IN_PROGRESS`). | Controller unit tests |
| **C5-R8** | `GET /api/organizations/{orgId}/stream` SSE; carries exactly `ProcessingStepChanged` and `DocumentStateChanged`. Frames have `id:`, `event:`, initial `retry: 5000`. No `Last-Event-ID` resume. | SSE integration test |
| **C5-R9** | Structured error body `{ code, message, details? }` for all 4xx/5xx. | Contract test (per code) |
| **C5-R9a** | Eleven-code error taxonomy (`UNKNOWN_ORGANIZATION`, `UNKNOWN_DOCUMENT`, `UNKNOWN_PROCESSING_DOCUMENT`, `UNKNOWN_DOC_TYPE`, `UNSUPPORTED_MEDIA_TYPE`, `INVALID_FILE`, `VALIDATION_FAILED`, `INVALID_ACTION`, `REEXTRACTION_IN_PROGRESS`, `LLM_UNAVAILABLE`, `INTERNAL_ERROR`). Note: 11 codes total; the "8-code" label in `03-components.md` §C5-R9a is a misnomer carried over from earlier drafts — the canonical list has 11 entries. | Contract test |

## 2. Research summary (C5-relevant)

From `04-research/c4-c5-c7-backend-infra/findings.md`:

- **SSE transport.** `SseEmitter(0L)` (no timeout); per-org `ConcurrentHashMap<String, Set<SseEmitter>>` registry; cleanup on `onCompletion` / `onTimeout` / `onError`. Initial `retry: 5000` hint. Spring Boot 4 + Tomcat 11 + virtual threads; blocking writes park cheaply.
- **Event publication.** `@Async @EventListener` consumer (`SsePublisher`) reads from `ApplicationEventPublisher`; per-event monotonic `id:` from an `AtomicLong`; serialization via Jackson 3 (`tools.jackson.*`).
- **Error model.** RFC 7807 `ProblemDetail` with custom `code`, `message`, `details` properties at top level. `@RestControllerAdvice` maps a `DocflowException` enum into `ProblemDetail`.
- **Async executor.** `spring.threads.virtual.enabled=true`; default `SimpleAsyncTaskExecutor` runs `@Async` listeners on virtual threads. No named `TaskExecutor` bean.

## 3. Approach

### 3.1 Layering

```
Controller  (HTTP / SSE seam — Jackson 3 ↔ Java records)
   └── Service / Engine
          ├── OrganizationCatalog          (C1)
          ├── StoredDocumentIngestionService (C2 — upload entry point)
          ├── DocumentReadModel            (read-model package; queries Postgres)
          ├── WorkflowEngine               (C4 — applyAction)
          └── DocumentWriter / LlmExtractor (C4 → C3 — retype)
```

Controllers are thin: parse request, validate shape, delegate, render. Read-model queries live in `read-model` package (shared with C4); C5 does not own them. Action / retype writes go through C4's `WorkflowEngine`.

### 3.2 Endpoint contracts

All bodies are JSON unless noted. All times are ISO-8601 UTC.

#### `GET /api/organizations`

```json
[
  {
    "id": "pinnacle-legal",
    "name": "Pinnacle Legal Group",
    "icon": "...",
    "docTypes": ["invoice", "retainer-agreement", "expense-report"],
    "inProgressCount": 2,
    "filedCount": 17
  }
]
```

#### `GET /api/organizations/{orgId}`

Returns the single org plus its `workflows` (stage list with display names + canonical `WorkflowStatus`) and `fieldSchemas` (per doc-type field list) — sufficient for C6 to render filter dropdowns and the dynamic Review form.

#### `POST /api/organizations/{orgId}/documents`

Multipart, field name `file`. On success: `201 Created`, `{ "storedDocumentId": "<uuid>", "processingDocumentId": "<uuid>" }`. Delegates to `StoredDocumentIngestionService.upload(orgId, file)` which atomically writes `StoredDocument` + initial `ProcessingDocument` and signals C3.

#### `GET /api/organizations/{orgId}/documents?status={WorkflowStatus}&docType={docTypeId}`

```json
{
  "processing": [
    {
      "processingDocumentId": "...",
      "storedDocumentId": "...",
      "sourceFilename": "invoice-001.pdf",
      "currentStep": "CLASSIFYING",
      "lastError": null,
      "createdAt": "2026-04-27T12:00:00Z"
    }
  ],
  "documents": [
    { /* DocumentView */ }
  ],
  "stats": {
    "inProgress": 3,
    "awaitingReview": 5,
    "flagged": 1,
    "filedThisMonth": 12
  }
}
```

`stats` is computed against the full org dataset, ignoring `status` / `docType` query params. `processing` is unfiltered by query params and sorted `createdAt DESC`. `documents` honors filters and is sorted `updatedAt DESC`. Soft cap of 200 rows on `documents` (not enforced; sample corpus stays well under).

#### `GET /api/documents/{documentId}` → `DocumentView`

```json
{
  "documentId": "...",
  "organizationId": "pinnacle-legal",
  "sourceFilename": "invoice-001.pdf",
  "mimeType": "application/pdf",
  "uploadedAt": "...",
  "processedAt": "...",
  "rawText": "...",
  "currentStageId": "attorney-approval",
  "currentStageDisplayName": "Attorney Approval",
  "currentStatus": "AWAITING_APPROVAL",
  "workflowOriginStage": null,
  "flagComment": null,
  "detectedDocumentType": "invoice",
  "extractedFields": { /* dynamic per doc-type */ },
  "reextractionStatus": "NONE"
}
```

#### `GET /api/documents/{documentId}/file`

Streams raw bytes with `Content-Type` from `StoredDocument.mimeType`. No range-request support.

#### `POST /api/documents/{documentId}/actions` — discriminated union

Body shapes (Jackson 3 polymorphic deserialization keyed on `action`):

```json
{ "action": "Approve" }
{ "action": "Reject" }
{ "action": "Flag", "comment": "Totals don't match line items." }
{ "action": "Resolve" }
```

Response: `200 OK`, body is the updated `DocumentView`. Errors: `VALIDATION_FAILED` (400) on missing/empty `comment` for `Flag`; `INVALID_ACTION` (409) on terminal docs or processing-only docs; `REEXTRACTION_IN_PROGRESS` (409) when `reextractionStatus = IN_PROGRESS`.

Note: the `Resolve` body on this endpoint carries no fields — it just clears the flag and returns the document to its origin stage. C4's `WorkflowEngine.applyAction` signature uses `Resolve(UUID newDocTypeId)` internally, but the `newDocTypeId` payload is never sourced from this endpoint; type changes go through `POST /api/documents/{documentId}/review/retype` instead.

#### `PATCH /api/documents/{documentId}/review/fields`

Body: `{ "extractedFields": { ... } }`. Validates against `detectedDocumentType` schema (C1). Persists via `DocumentWriter` direct UPDATE on `Document.extractedFields`. Document stays in `Review`. Response: `200 OK`, updated `DocumentView`. Errors: `VALIDATION_FAILED` with field-level `details`; `INVALID_ACTION` if document is not in Review.

#### `POST /api/documents/{documentId}/review/retype`

Body: `{ "newDocumentType": "expense-report" }`. Validates target type is in the org's allowed list (else `UNKNOWN_DOC_TYPE`). Calls `WorkflowEngine.applyAction(documentId, Resolve, { newDocTypeId })` (the canonical engine surface from C4 §C4-R1) which sets `Document.reextractionStatus = IN_PROGRESS`, invokes `LlmExtractor.extract`, and listens for `ExtractionCompleted` / `ExtractionFailed` (C4-R6). Response: `202 Accepted`, `{ "reextractionStatus": "IN_PROGRESS" }`. The completion is communicated to the client via `DocumentStateChanged` SSE with `reextractionStatus` updated.

#### `GET /api/organizations/{orgId}/stream`

`Content-Type: text/event-stream`. Initial frame: comment line + `retry: 5000`. Subsequent frames per event:

```
id: 42
event: ProcessingStepChanged
data: {"storedDocumentId":"...","processingDocumentId":"...","organizationId":"pinnacle-legal","currentStep":"EXTRACTING","error":null,"at":"..."}

id: 43
event: DocumentStateChanged
data: {"documentId":"...","storedDocumentId":"...","organizationId":"pinnacle-legal","currentStage":"review","currentStatus":"AWAITING_REVIEW","reextractionStatus":"NONE","action":"Resolve","comment":null,"at":"..."}
```

Only these two event types appear on SSE. `ProcessingCompleted`, `ExtractionCompleted`, `ExtractionFailed` are internal `DocumentEventBus` events and are NOT fanned out to clients.

### 3.3 SSE protocol details

- One emitter per HTTP connection; emitters keyed by `orgId` in `SseRegistry`.
- `SseEmitter(0L)` — never times out; relies on client disconnect to clean up.
- `id:` is a server-side `AtomicLong`, monotonic per process (not per connection). Resume is out of scope so the value is informational.
- On send failure, `emitter.completeWithError(t)` removes it from the registry via the `onError` handler.
- `@Async @EventListener` on `SsePublisher.onEvent` runs on a virtual thread; per-emitter writes are sequential within that listener invocation.

### 3.4 Error taxonomy

| Code | HTTP | When |
|---|---|---|
| `UNKNOWN_ORGANIZATION` | 404 | `orgId` not in C1 catalog |
| `UNKNOWN_DOCUMENT` | 404 | `documentId` not in `documents` |
| `UNKNOWN_PROCESSING_DOCUMENT` | 404 | `processingDocumentId` not in `processing_documents` |
| `UNKNOWN_DOC_TYPE` | 404 | retype `newDocumentType` not in org's allowed list |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | upload MIME not in supported set (PDF + image MIMEs from C2) |
| `INVALID_FILE` | 400 | upload empty / corrupt / over size limit |
| `VALIDATION_FAILED` | 400 | request body fails schema validation; `details: [{path, message}]` |
| `INVALID_ACTION` | 409 | action not legal in current stage (e.g., Approve on Filed; any action on a `ProcessingDocument`) |
| `REEXTRACTION_IN_PROGRESS` | 409 | workflow action attempted while `reextractionStatus = IN_PROGRESS` |
| `LLM_UNAVAILABLE` | 502 | upstream Anthropic failure surfaced through C3/C4 |
| `INTERNAL_ERROR` | 500 | uncaught exception |

All error responses are RFC 7807 `ProblemDetail` with custom `code`, `message`, optional `details` top-level properties. `@RestControllerAdvice` does the mapping from a sealed `DocflowException` hierarchy.

## 4. Files & changes

All paths are absolute under `/Users/gb/github/basata/`.

### 4.1 New files (Java, `src/main/java/com/docflow/api/`)

- `OrganizationController.java` — `@GetMapping /api/organizations`, `/api/organizations/{orgId}`.
- `DocumentUploadController.java` — `@PostMapping /api/organizations/{orgId}/documents`.
- `DashboardController.java` — `@GetMapping /api/organizations/{orgId}/documents` (returns `{processing, documents, stats}`).
- `DocumentController.java` — `@GetMapping /api/documents/{documentId}`, `/api/documents/{documentId}/file`.
- `DocumentActionController.java` — `@PostMapping /api/documents/{documentId}/actions`.
- `ReviewController.java` — `@PatchMapping /api/documents/{documentId}/review/fields`, `@PostMapping /api/documents/{documentId}/review/retype`.
- `SseController.java` — `@GetMapping /api/organizations/{orgId}/stream`.

### 4.2 New files (Java, `src/main/java/com/docflow/api/sse/`)

- `SseRegistry.java` — `ConcurrentHashMap<String, Set<SseEmitter>>`; `register(orgId)`, `emittersFor(orgId)`.
- `SsePublisher.java` — `@Async @EventListener` on `DocumentEvent`; filters to `ProcessingStepChanged` / `DocumentStateChanged`; writes to all emitters for the org.

### 4.3 New files (Java, `src/main/java/com/docflow/api/error/`)

- `ErrorCode.java` — enum (11 entries per C5-R9a; HTTP status + URI).
- `DocflowException.java` — base class; subclasses `UnknownOrganizationException`, `UnknownDocumentException`, `ValidationException`, `InvalidActionException`, `ReextractionInProgressException`, `LlmUnavailableException`.
- `GlobalExceptionHandler.java` — `@RestControllerAdvice` mapping `DocflowException` → `ProblemDetail`; default mapping for `MethodArgumentNotValidException` / `HttpMediaTypeNotSupportedException` → matching codes.

### 4.4 New files (Java, `src/main/java/com/docflow/api/dto/`)

- `OrganizationListItem.java` — record.
- `OrganizationDetail.java` — record (includes workflows + field schemas).
- `DashboardResponse.java` — record (`processing`, `documents`, `stats`).
- `DashboardStats.java` — record.
- `ActionRequest.java` — sealed interface; `Approve`, `Reject`, `Flag(String comment)`, `Resolve` permitted; Jackson 3 `@JsonTypeInfo(use = NAME, property = "action")` polymorphic deserialization.
- `ReviewFieldsPatch.java` — record.
- `RetypeRequest.java` — record.
- `RetypeAccepted.java` — record (`reextractionStatus`).

### 4.5 Edits to existing config

- `src/main/resources/application.yml` — confirm `spring.threads.virtual.enabled=true`, `spring.mvc.async.request-timeout` (ignored by `SseEmitter(0L)` but set for rest of MVC).
- `App.java` (or main class) — `@EnableAsync`.

## 5. Acceptance criteria

Each is testable.

1. `GET /api/organizations` returns 200 with array shape per §3.2; `inProgressCount` matches `SELECT COUNT(*) FROM processing_documents pd LEFT JOIN documents d USING (stored_document_id) WHERE pd.organization_id = ? AND d.id IS NULL`; `filedCount` matches `WorkflowInstance.currentStatus = 'FILED'` count for the org.
2. `POST .../documents` with valid PDF returns 201 + `{storedDocumentId, processingDocumentId}`. With unknown `orgId`: 404 / `UNKNOWN_ORGANIZATION`. With `Content-Type: application/zip`: 415 / `UNSUPPORTED_MEDIA_TYPE`.
3. `GET .../documents` returns the three-key shape. Filtering by `?status=AWAITING_REVIEW` reduces only `documents`; `processing` and `stats` unchanged.
4. `GET /api/documents/{id}` returns `DocumentView` with all fields including `reextractionStatus`. Unknown id: 404 / `UNKNOWN_DOCUMENT`.
5. `GET /api/documents/{id}/file` returns 200 with `Content-Type` matching `StoredDocument.mimeType` and body matching the stored bytes byte-for-byte.
6. `POST .../actions` with each of the four union variants invokes `WorkflowEngine.applyAction` once and returns the updated `DocumentView`. `Flag` with empty `comment` returns 400 / `VALIDATION_FAILED` with `details` containing `comment`. `Approve` on a `FILED` document returns 409 / `INVALID_ACTION`.
7. `PATCH .../review/fields` with valid payload returns 200 with updated `extractedFields`; document stays in `Review`. Invalid field shape returns 400 / `VALIDATION_FAILED` with field-level `details`.
8. `POST .../review/retype` returns 202 / `{ "reextractionStatus": "IN_PROGRESS" }` and triggers `LlmExtractor.extract` exactly once. Unknown doc type for the org returns 404 / `UNKNOWN_DOC_TYPE`.
9. `GET /api/organizations/{orgId}/stream` returns `Content-Type: text/event-stream`. First frame contains `retry: 5000`. When a `ProcessingStepChanged` event is published for the org, the SSE response includes `event: ProcessingStepChanged` with the JSON payload from §3.2. Only `ProcessingStepChanged` and `DocumentStateChanged` are emitted; `ProcessingCompleted`, `ExtractionCompleted`, `ExtractionFailed` are NEVER emitted on SSE.
10. Each error code maps to its documented HTTP status. The error body is RFC 7807 `ProblemDetail` with `code`, `message`, and optional `details` as top-level properties. A contract test asserts the shape per code.

## 6. Verification

```
make test
```

This runs `./gradlew check` (which transitively runs the spec's HTTP-seam smoke test, the SSE integration test, and the contract tests) plus `npm --prefix frontend run check`.

### Smoke test (single happy path)

`src/test/java/com/docflow/api/HappyPathSmokeTest.java` — `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`:

1. `POST /api/organizations/pinnacle-legal/documents` with a sample PDF → 201, capture `storedDocumentId`.
2. Wait briefly (or `@RecordApplicationEvents` to await `ProcessingCompleted`).
3. `GET /api/organizations/pinnacle-legal/documents` → assert `documents` contains an entry with the matching `storedDocumentId` and `currentStatus = AWAITING_REVIEW`.
4. `POST /api/documents/{documentId}/actions` with `{ "action": "Approve" }` → 200 + updated `DocumentView` advanced one stage.

This is the only HTTP-seam integration test (per scope cut). Other layers — controllers, error handler, read-model, SSE fan-out — are tested independently in unit / contract tests.

### Manual smoke

```
make start
curl -s http://localhost:8080/api/organizations | jq
curl -N http://localhost:8080/api/organizations/pinnacle-legal/stream
```

## 7. Error handling and edge cases

- **SSE client disconnect.** `SseRegistry`'s `onCompletion` / `onTimeout` / `onError` callbacks remove the emitter. Subsequent publishes for that org skip the removed entry.
- **SSE write failure mid-event.** `emitter.completeWithError(t)` is called; cleanup removes the emitter. Other emitters for the same org are unaffected.
- **No `Last-Event-ID` resume.** Reconnecting clients re-fetch the dashboard list (C6-R5) for state rehydration. The server does not buffer events.
- **Action on processing-only document.** `Document` does not yet exist (only `ProcessingDocument`). `POST /api/documents/{id}/actions` returns 404 / `UNKNOWN_DOCUMENT` (the document id namespace is `Document.id`, distinct from `ProcessingDocument.id`). If a client sends a `processingDocumentId` here it correctly 404s.
- **Action while reextraction in progress.** `WorkflowEngine` checks `Document.reextractionStatus`; if `IN_PROGRESS`, controller surfaces `REEXTRACTION_IN_PROGRESS` (409).
- **Stage guard rejection.** `WorkflowEngine` returns a typed error which controller maps to `INVALID_ACTION` (409).
- **Validation failure on body.** Spring's `MethodArgumentNotValidException` is caught by `GlobalExceptionHandler` and emitted as `VALIDATION_FAILED` (400) with `details: [{path, message}]` per field error.
- **Polymorphic action body with unknown discriminator.** Jackson 3 throws on unknown `action` value; mapped to `VALIDATION_FAILED` with `details` pointing at `action`.
- **Empty multipart upload.** `INVALID_FILE` (400).
- **`LLM_UNAVAILABLE`.** Surfaces through C3 (initial pipeline) or C4 (retype). For retype, the failure is communicated via `DocumentStateChanged` with `reextractionStatus = FAILED` on the SSE stream; the original 202 response from `POST .../review/retype` still stands. The synchronous response path uses `LLM_UNAVAILABLE` only when an action's request thread itself talks to the LLM (none currently do — all LLM calls are async).
- **Soft cap 200 on documents.** Not enforced; the sample corpus is far smaller. Documented as a Production Considerations bullet.

## 8. Migration / backwards compatibility

Greenfield component; no migrations from prior API versions. Schema changes belong to C2/C3/C4 specs; C5 owns no tables.

---

### Production Considerations

- Dashboard `documents` array has a soft cap of 200; pagination is not implemented and the sample corpus stays well under the cap.
