# C2 — Document Ingestion & Storage — Change Spec

Pass 5 detailed change spec for **C2 Document Ingestion & Storage** of DocFlow. C2 owns the immutable file-reference aggregate `StoredDocument` and the filesystem-backed `StoredDocumentStorage` seam. Text extraction, classification, extraction, lifecycle state, and dashboard reads are explicitly **not** C2's concern.

Source documents:

- Requirements: `.kerf/project/docflow/03-components.md` §C2 (lines 91–131) and cross-component summary (lines 442–481).
- Research: `.kerf/project/docflow/04-research/c2-ingestion/findings.md`.
- Codebase paths reference: `.kerf/project/docflow/02-analysis.md`.

---

## 1. Requirements (carried forward, traceability preserved)

| ID | Requirement | Source |
|---|---|---|
| **C2-R1** | Accepts `application/pdf`, `image/png`, `image/jpeg` via an unauthenticated HTTP endpoint. Other MIME types rejected with 415 + structured error. | 03-components.md §C2 |
| **C2-R2** | Stores the uploaded file on the local filesystem under a configurable path. The on-disk path is an implementation detail; public interface is `storedDocumentId`. | 03-components.md §C2 |
| **C2-R3** | *(Reserved — text extraction moved to C3.)* | 03-components.md §C2 |
| **C2-R4** | Persists the ingestion aggregate as one immutable row. `StoredDocument` fields: `id`, `organizationId`, `uploadedAt`, `sourceFilename`, `mimeType`, `storagePath`. Upload transactionally INSERTs `StoredDocument` **and** the initial `ProcessingDocument` (with `currentStep = TEXT_EXTRACTING`), then signals C3. | 03-components.md §C2 |
| **C2-R4a** | Client-data reference tables (incl. `organizations`) are created by Flyway (C7-R3) and seeded by C1-R11. `StoredDocument.organizationId` FKs to `organizations.id`. | 03-components.md §C2 |
| **C2-R5** | `StoredDocument` rows are tenant-scoped to `organizationId`. No global "all stored documents" endpoint. | 03-components.md §C2 |
| **C2-R6** | No deletion endpoint. (Out of scope per problem space.) | 03-components.md §C2 |
| **C2-R7** | Storage is isolated behind `StoredDocumentStorage` interface; single filesystem implementation. S3 swap requires only a new implementation. | 03-components.md §C2 |
| **C2-R8** | *(Removed — stage advancement is C3/C4.)* | 03-components.md §C2 |
| **C2-R9** | Flyway-managed schema. C2 owns only `stored_documents(id PK, organization_id FK, uploaded_at, source_filename, mime_type, storage_path)`. Index `(organization_id, uploaded_at DESC)`. Other tables FK into `stored_documents.id`; those FK declarations live with the dependent tables, not here. | 03-components.md §C2 |
| **C2-R9a** | `storagePath` is derived deterministically from `id` as `{storageRoot}/{id}.bin`. File is written to disk **before** the DB row is committed so crash-recovery sees stray files (orphans), not stray rows. C5-R5 (`GET /api/documents/{id}/file`) returns 404 for missing file. | 03-components.md §C2 |

Cross-component contracts owned by C2 (per §"Cross-component interfaces summary"):

- `StoredDocumentReader.get(storedDocumentId) → StoredDocument` — consumed by C3 and read-models.
- `StoredDocumentStorage.save(id, bytes) / load(id) / delete(id)` — filesystem seam.
- `StoredDocumentIngestionService.upload(orgId, file) → IngestionResult` — consumed by C5's `POST /api/organizations/{orgId}/documents`. `IngestionResult` is a typed record `{ storedDocumentId: UUID, processingDocumentId: UUID }`. Both UUIDs are minted and inserted in the same C2 transaction (per C2-R4), so returning both is essentially free; C5 needs the `processingDocumentId` so the frontend can correlate the optimistic processing row to the server.

---

## 2. Research summary

From `04-research/c2-ingestion/findings.md`:

- **Storage path scheme.** `{storageRoot}/{id}.bin` — deterministic, no filename sanitization, S3 migration is one-for-one (`{bucket}/{id}.bin`). `Content-Type` served from `StoredDocument.mimeType`.
- **MIME handling.** Sniff bytes on ingestion via Apache Tika `tika-core` (~5 MB, pure-Java magic-byte detection — no Tesseract / POI bloat). The persisted `mimeType` is the **sniffed** type; the client-claimed `Content-Type` header is ignored after gating. Allowed set: `application/pdf`, `image/png`, `image/jpeg`.
- **Tika fallback.** If Tika returns `application/octet-stream`, retry against the claimed header as a second opinion; if neither is in the allowed set, reject with `INVALID_FILE`.
- **Bounded-context reaffirmation.** PDFBox lives in C3, not C2. C2 never opens the PDF beyond byte-level magic detection.

---

## 3. Approach

### 3.1 Architecture

C2 is a thin ingestion seam. The upload control flow:

1. C5 controller (`POST /api/organizations/{orgId}/documents`) hands the multipart part to `StoredDocumentIngestionService.upload(orgId, file)`.
2. `StoredDocumentIngestionService` (the orchestrator):
   1. Validates `orgId` against C1's org catalog: calls `OrganizationCatalog.getOrganization(orgId)` and rejects an empty `Optional` with `UNKNOWN_ORGANIZATION`.
   2. Reads bytes once, in full, into memory. (Sample corpus is small digital PDFs; streaming is a speculative abstraction for this take-home.)
   3. Sniffs MIME via Tika; rejects unsupported types with `UNSUPPORTED_MEDIA_TYPE` (HTTP 415).
   4. Mints `StoredDocument.id` and `ProcessingDocument.id` (both UUIDv7 — time-ordered, pairs naturally with the `(org, uploaded_at DESC)` index).
   5. Writes bytes to disk via `StoredDocumentStorage.save(id, bytes)` (file written **before** transaction).
   6. In one DB transaction: INSERT `stored_documents` row + INSERT initial `processing_documents` row with `currentStep = TEXT_EXTRACTING`, `organizationId` denormalized to match.
   7. Publishes a `StoredDocumentIngested` (or equivalent C3 trigger — exact name owned by C3's spec) signal so C3 begins the pipeline. **Decision:** C2 does **not** call C3 synchronously. The signal is published via the platform `DocumentEventBus` (C7-R11). Rationale: keeps the upload response fast and the C2/C3 boundary unidirectional via events. (Cross-spec note: the precise event type name is C3's to define — surfaced in §10 below.)
   8. Returns `IngestionResult { storedDocumentId, processingDocumentId }` to the caller. Both UUIDs were minted in step 4 and committed together in step 6.

### 3.2 Class layout (Java, Spring Boot 4 / Jakarta)

Package root: `com.docflow.ingestion`.

```
com.docflow.ingestion
├── StoredDocument                       (record — immutable aggregate)
├── StoredDocumentId                     (record wrapping UUID)
├── StoredDocumentReader                 (interface — read seam consumed by C3 + read-models)
├── StoredDocumentIngestionService       (interface — entry point consumed by C5)
├── internal/
│   ├── StoredDocumentIngestionServiceImpl
│   ├── StoredDocumentJpaReader          (StoredDocumentReader impl, JPA-backed)
│   ├── StoredDocumentEntity             (JPA @Entity — internal; never escapes the package)
│   └── StoredDocumentEntityRepository   (Spring Data JPA)
└── storage/
    ├── StoredDocumentStorage            (interface — save/load/delete)
    └── FilesystemStoredDocumentStorage  (single impl)
```

Boundary rules:

- Public types in the package root are: `StoredDocument`, `StoredDocumentId`, `StoredDocumentReader`, `StoredDocumentIngestionService`, `StoredDocumentStorage`. Everything under `internal/` is package-private and not exported.
- `StoredDocumentEntity` is a JPA entity solely to satisfy Hibernate; mapping into the immutable `StoredDocument` record happens inside the package. No JPA type leaks to consumers.
- The C5 controller imports `StoredDocumentIngestionService`. C3 imports `StoredDocumentReader` and `StoredDocumentStorage`. Read-models import `StoredDocumentReader` only.

### 3.3 Aggregate shape

```java
public record StoredDocument(
    StoredDocumentId id,
    UUID organizationId,
    Instant uploadedAt,
    String sourceFilename,
    String mimeType,
    String storagePath
) {}
```

Immutability is enforced by the record + the absence of any update path on the writer (the entity has no setters; only one INSERT path).

### 3.4 Storage seam

```java
public interface StoredDocumentStorage {
    void save(StoredDocumentId id, byte[] bytes);
    byte[] load(StoredDocumentId id);
    void delete(StoredDocumentId id);
}
```

`FilesystemStoredDocumentStorage` resolves `{storageRoot}/{id}.bin` via `java.nio.file.Path`. `storageRoot` is read from `AppConfig.Storage` (a nested record on the C7-owned `AppConfig` — see §4.1) and injected into the storage bean. C2 does **not** declare its own `@ConfigurationProperties` binder; per C7-R13 the only legitimate config reader is `AppConfig` under `com.docflow.config`. On `save`: write bytes to a temp file in the same directory, then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` to the final path — guarantees no half-written file is observable. On `load`: throw a typed `StoredFileNotFoundException` if the path does not exist. `delete` is implemented but never called by the runtime API (kept for tests + the eventual S3 swap symmetry).

**Decision (research left open):** the storage root default is `./storage/documents` for local dev (resolved relative to the working directory of the Spring Boot app), overridable via `DOCFLOW_STORAGE_ROOT` env var per `.env.example`. Rationale: docker-compose mounts `./storage` into the backend container; absolute path inside the container is documented in `docker-compose.yml`. No multi-tenant subdir partitioning (`{org}/{yyyy-mm}/...`) — research flagged this as over-engineered for the take-home; orphan detection at the flat layout is a single directory listing.

### 3.5 MIME sniffing

```java
private static final Set<String> ALLOWED = Set.of(
    "application/pdf", "image/png", "image/jpeg");

String sniff(byte[] bytes, String claimedFilename, String claimedContentType) {
    String sniffed = tika.detect(bytes, claimedFilename);
    if (ALLOWED.contains(sniffed)) return sniffed;
    if ("application/octet-stream".equals(sniffed)
        && claimedContentType != null
        && ALLOWED.contains(claimedContentType)) {
        return claimedContentType;
    }
    throw new DocflowException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, sniffed);
}
```

`Tika` is constructed once and held as a singleton bean (thread-safe per Tika docs).

### 3.6 ID strategy

`StoredDocument.id` = UUIDv7. **Decision (research left open implicitly):** chosen over UUIDv4 because the primary access pattern is `(organizationId, uploadedAt DESC)` and the time-ordered prefix gives correlated index locality; chosen over a `bigserial` because the id is also the storage filename and exposing a sequential integer in URLs would reveal upload volume. JDK 25 has no built-in UUIDv7; use the `com.github.f4b6a3:uuid-creator` library (single ~200 KB dep) — already implied by the time-ordered choice in 02-analysis.md.

### 3.7 Transactional ordering

```
1. (no-tx) tika.detect → reject if bad
2. (no-tx) storage.save(id, bytes)             ← FS write happens first
3. (tx)   INSERT stored_documents
          INSERT processing_documents (currentStep=TEXT_EXTRACTING, organizationId=…)
          [commit]
4. (post-commit) eventBus.publish(StoredDocumentIngested)
```

Failure of step 3 leaves an orphan FS file (recoverable: a startup janitor or an ops query — not in scope for this spec). Failure of step 2 means no DB row exists; client gets 5xx and may retry. Per C2-R9a, this ordering is the explicit contract.

### 3.8 Org validation

Before any FS or DB write, `StoredDocumentIngestionServiceImpl` calls C1's `OrganizationCatalog.getOrganization(orgId)` and rejects on empty `Optional`. On miss → `UNKNOWN_ORGANIZATION` (HTTP 404 from the C5 controller). This avoids creating an orphan file for a bad orgId.

---

## 4. Files & changes

Greenfield repo. All paths are new. Validated against the project layout in 02-analysis.md (no pre-existing source code to conflict with).

### 4.1 Source files

| Path | Purpose |
|---|---|
| `backend/src/main/java/com/docflow/ingestion/StoredDocument.java` | Immutable record. |
| `backend/src/main/java/com/docflow/ingestion/StoredDocumentId.java` | Typed id wrapper. |
| `backend/src/main/java/com/docflow/ingestion/StoredDocumentReader.java` | Read-side interface. |
| `backend/src/main/java/com/docflow/ingestion/StoredDocumentIngestionService.java` | Write-side interface. |
| `backend/src/main/java/com/docflow/ingestion/internal/StoredDocumentIngestionServiceImpl.java` | Orchestrator. |
| `backend/src/main/java/com/docflow/ingestion/internal/StoredDocumentJpaReader.java` | `StoredDocumentReader` impl. |
| `backend/src/main/java/com/docflow/ingestion/internal/StoredDocumentEntity.java` | JPA entity (package-private). |
| `backend/src/main/java/com/docflow/ingestion/internal/StoredDocumentEntityRepository.java` | Spring Data JPA repo. |
| `backend/src/main/java/com/docflow/ingestion/storage/StoredDocumentStorage.java` | Storage seam. |
| `backend/src/main/java/com/docflow/ingestion/storage/FilesystemStoredDocumentStorage.java` | Single impl. |

C2 does **not** ship a standalone config-binder file. The typed `AppConfig.Storage` record (`storageRoot: String`) is contributed to C7's `AppConfig` (under `com.docflow.config`, the only allow-listed config-reader package per C7-R13). C2's `FilesystemStoredDocumentStorage` consumes it via constructor injection only.

### 4.2 Migration

C2 contributes SQL fragments to the **single baseline migration** `V1__init.sql` owned by C7 (per C7-R3). C2 does **not** ship a standalone `V{n}__*.sql` file; the fragments below are stitched into C7's V1 in the canonical table order documented in C7's spec (`organizations` + reference tables first, then `stored_documents`, then `processing_documents`, etc.).

C2's fragment:

```sql
CREATE TABLE stored_documents (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    uploaded_at     TIMESTAMPTZ  NOT NULL,
    source_filename TEXT         NOT NULL,
    mime_type       TEXT         NOT NULL,
    storage_path    TEXT         NOT NULL
);

CREATE INDEX stored_documents_org_uploaded_idx
    ON stored_documents (organization_id, uploaded_at DESC);
```

(`processing_documents` is contributed by C3 to the same `V1__init.sql`. Its FK to `stored_documents.id` lives in C3's fragment per C2-R9.)

### 4.3 Configuration

| Path | Change |
|---|---|
| `.env.example` | Add `DOCFLOW_STORAGE_ROOT=./storage/documents` (existing file gets one new entry — does not currently exist; created here if not present per repo scaffolding). |
| `backend/src/main/resources/application.yml` | Add `docflow.storage.root: ${DOCFLOW_STORAGE_ROOT:./storage/documents}`. |

### 4.4 Tests

| Path | Purpose |
|---|---|
| `backend/src/test/java/com/docflow/ingestion/StoredDocumentIngestionServiceImplTest.java` | Unit tests for orchestration, MIME gating, org validation, FS-before-DB ordering. |
| `backend/src/test/java/com/docflow/ingestion/storage/FilesystemStoredDocumentStorageTest.java` | Round-trip save/load/delete; atomic-move semantics; `StoredFileNotFoundException` on missing. |
| `backend/src/test/java/com/docflow/ingestion/StoredDocumentIngestionIntegrationTest.java` | `@SpringBootTest` with Postgres testcontainer + tmpfs storage root: full upload → row persisted + file present + event published. |
| `backend/src/test/resources/fixtures/sample-invoice.pdf` | Copied from `problem-statement/samples/pinnacle-legal/invoices/` — small, valid PDF for happy-path tests. |
| `backend/src/test/resources/fixtures/not-a-pdf.txt` | 415 rejection fixture. |
| `backend/src/test/resources/fixtures/zero-bytes.bin` | Corrupt / empty file fixture. |

---

## 5. Acceptance criteria

Each is concrete and testable.

1. **AC-R1 (C2-R1).** Posting `application/pdf` to the upload endpoint returns 200 with `{ "storedDocumentId": "<uuid>", "processingDocumentId": "<uuid>" }` (both fields present, both valid UUIDs, distinct from each other). Posting `text/csv` returns 415 with body `{ "code": "UNSUPPORTED_MEDIA_TYPE", "detected": "text/csv" }`. Posting a `.pdf` file with `Content-Type: text/plain` is **accepted** (Tika sniff wins).
2. **AC-R2 (C2-R2).** After upload, `{storageRoot}/{id}.bin` exists on disk with bytes equal to the uploaded body.
3. **AC-R4 (C2-R4).** A `stored_documents` row exists with all six columns populated, and a `processing_documents` row exists with `stored_document_id = id` and `current_step = 'TEXT_EXTRACTING'`. Both inserts are in one transaction (verified by killing the JVM between them in a fault-injection test → neither row exists).
4. **AC-R5 (C2-R5).** `StoredDocumentReader.get(id)` returns the row regardless of org, but no API path returns rows across orgs (the read endpoint at C5 filters by `{orgId}` path variable; verified in C5's spec, surfaced here as a boundary).
5. **AC-R6.** No HTTP route exists matching `DELETE /api/.../documents/{id}` (grep test on the route table).
6. **AC-R7.** `StoredDocumentStorage` has exactly one production implementation. A test substitutes an in-memory implementation with no other code change.
7. **AC-R9.** `./gradlew flywayInfo` shows the single `V1__init.sql` baseline applied; an information_schema query confirms `stored_documents` has the column set + index per §4.2.
8. **AC-R9a.** `storagePath` in the persisted row equals `{storageRoot}/{id}.bin`. Deleting the file on disk and calling `StoredDocumentStorage.load(id)` raises `StoredFileNotFoundException` (the C5 layer translates to 404 — covered in C5's spec).
9. **AC-IMMUTABILITY.** `StoredDocumentEntity` has no `@Setter`, no public mutators, no `update*` methods on the repository (compile-time / static analysis check).
10. **AC-EVENT.** On successful upload, exactly one `StoredDocumentIngested` (or C3-named equivalent) event is observed on the bus, after commit, with `id`, `organizationId`, `mimeType`.
11. **AC-ORG-VALIDATION.** Posting with an unknown `orgId` returns 404 `UNKNOWN_ORGANIZATION` and produces no FS file and no DB row.
12. **AC-CORRUPT-PDF.** A zero-byte body uploaded as `application/pdf` is rejected (Tika sniffs as `application/octet-stream` or `text/plain`; falls through allowed-set; 415).
13. **AC-OVERSIZE.** A request body exceeding the configured limit (Spring `spring.servlet.multipart.max-file-size`) returns Spring's standard 413; we do not need a custom guard.

---

## 6. Verification

Exact commands. All are part of "done means green" (CLAUDE.md §"Done means green").

```
./gradlew :backend:spotlessCheck
./gradlew :backend:checkstyleMain :backend:checkstyleTest
./gradlew :backend:pmdMain :backend:pmdTest
./gradlew :backend:test --tests "com.docflow.ingestion.*"
./gradlew :backend:integrationTest --tests "com.docflow.ingestion.*"
./gradlew :backend:build
```

Targeted checks:

- **Boundary check.** `! grep -R "ingestion.internal" backend/src/main/java | grep -v "/ingestion/"` — the `internal` package must not be referenced from outside its parent package.
- **No-deletion check.** `! grep -R "@DeleteMapping" backend/src/main/java/com/docflow/ingestion` — no deletion endpoints anywhere in C2.
- **Migration check.** `./gradlew :backend:flywayValidate` after starting Postgres via docker-compose.

Test classes called out:

- `StoredDocumentIngestionServiceImplTest` — covers ACs 1, 3, 11, 12.
- `FilesystemStoredDocumentStorageTest` — covers ACs 2, 8 (storage half).
- `StoredDocumentIngestionIntegrationTest` — covers ACs 3, 7, 10 end-to-end.

---

## 7. Error handling and edge cases

| Case | Behavior | Reported as |
|---|---|---|
| Unknown `orgId` | Reject before FS write. No row, no file. | 404 `UNKNOWN_ORGANIZATION` |
| MIME not in allowed set (sniffed) | Reject before FS write. | 415 `UNSUPPORTED_MEDIA_TYPE` with `{ detected }` |
| Tika sniffs `application/octet-stream` and claimed type is allowed | Accept, persist claimed type as `mimeType`. | 200 |
| Tika sniffs `application/octet-stream` and claimed type is also disallowed/missing | Reject. | 415 `UNSUPPORTED_MEDIA_TYPE` |
| Zero-byte body | Tika sniff returns non-allowed type → reject. | 415 |
| Corrupt PDF (truncated / wrong header bytes) | Tika sniffs based on what's there; if not `application/pdf`, reject. If somehow sniffed as PDF but unparseable, **C2 does not care** — that's C3's `textExtractionStatus = failed` path. C2's invariant is "bytes were accepted under a recognized MIME"; semantic validity is downstream. | 200 from C2; later surfaced via C3 event |
| Oversize file | Spring multipart limit triggers before we read the body. | 413 |
| FS write fails (disk full, permission, atomic-move failure) | Wrap as `StorageException`. No DB row written. | 500 `INTERNAL_ERROR` |
| DB INSERT fails after FS write | Transaction rollback. FS file remains on disk as an orphan. Client gets 500. (Recovery is an ops concern; out of scope for this spec, per problem space — flagged in research note 92.) | 500 `INTERNAL_ERROR` |
| Event publish fails after commit | Logged. Row + file are durable; pipeline does not start. (Self-healing requires a reconciliation pass — out of scope; flagged in §10.) | 200 returned to client; logged warning |
| Concurrent uploads of the same bytes | Two distinct `id`s, two distinct files, two distinct rows. Idempotency is **not** a C2 requirement (no `Idempotency-Key` in the spec). | 200 each |
| Filename with path separators / null bytes | `sourceFilename` is stored verbatim as text (not used for FS path — `id.bin` is used). No sanitization; SQL parameter binding handles the rest. | 200 |

---

## 8. Migration / backwards compatibility

Greenfield. No backwards compatibility constraints. Migration plan: C2 contributes the `stored_documents` fragment to the single `V1__init.sql` baseline owned by C7; no data to backfill.

If the storage seam is later swapped to S3 (out of scope), the change is:

- Add `S3StoredDocumentStorage implements StoredDocumentStorage`.
- Toggle bean wiring via a Spring profile / property.
- Re-point `storagePath` derivation. Existing rows already store `{storageRoot}/{id}.bin`; the migrator copies files to `s3://{bucket}/{id}.bin` and a one-shot SQL UPDATE rewrites `storage_path`. Consumers do not change.

This is a future concern; called out only because C2-R7 demands the seam exist now.

---

## 9. Decisions recorded (where research left options open)

| Question | Decision | Rationale |
|---|---|---|
| ID type | UUIDv7 (`uuid-creator` lib) | Time-ordered locality with the `(org, uploaded_at DESC)` index; doesn't leak upload volume; same surface as UUID. |
| Storage root default | `./storage/documents`, overridable via `DOCFLOW_STORAGE_ROOT` | Plays cleanly with docker-compose volume mount; no per-org subdirs (research called partitioning over-engineered). |
| FS write ordering | File **before** DB transaction; atomic-move via tmp file | Matches C2-R9a's stated invariant (orphans, not stray rows) and avoids half-written files. |
| C2 → C3 trigger | Event on `DocumentEventBus`, post-commit | Keeps direction unidirectional; matches the C3 event-subscription pattern in the cross-component summary. |
| In-memory vs streamed read | In-memory `byte[]` | Sample corpus is small, and Tika needs the bytes anyway. Streaming is a speculative abstraction. |
| Tika fallback when sniff is `octet-stream` | Trust claimed `Content-Type` only if it is in the allowed set | Research recommendation (note 92, Risk 2). |

---

## 10. Cross-spec items to flag

These belong in adjacent specs but the C2 spec assumes them. Surfaced for review:

1. **Event type name.** C2 publishes "the C3 trigger event"; the canonical name lives in C3's spec. C2-R4 just says "signals C3 to start the pipeline." If C3 names this `StoredDocumentIngested`, this spec's references resolve cleanly; if C3 names it differently, only the publisher line in `StoredDocumentIngestionServiceImpl` changes.
2. **Initial `ProcessingDocument` row.** C2-R4 mandates that the upload transaction also inserts the `processing_documents` row with `currentStep = TEXT_EXTRACTING`. The column set + constraints on that table are owned by C3's spec — C2 assumes they exist and that `organizationId` is denormalized there. This is a hard cross-spec dependency: C3's `processing_documents` fragment in `V1__init.sql` **must** be ordered after C2's `stored_documents` fragment and **must** define `processing_documents.stored_document_id` as `NOT NULL REFERENCES stored_documents(id)`.
3. **`OrganizationCatalog` API shape.** C2 calls `OrganizationCatalog.getOrganization(orgId): Optional<OrganizationView>` (per C1's spec) and rejects on empty. No bespoke `exists` method required.
4. **Orphan-file reconciliation.** Outside scope per problem-space "deletion is out of scope"; flagged as a known operational gap on the FS-write-before-commit ordering. No work in this spec.
5. **Event publish failure recovery.** Same flavor: spec'd as logged-and-dropped; a reconciliation pass would be needed for true at-least-once delivery. Not in scope.

---

## 11. Open contradictions surfaced

None blocking. Two items worth noting:

- **C2-R4 wording.** The requirement says "signals C3 to start the pipeline" without naming the mechanism. The cross-component summary (line 448) says "atomically writes `StoredDocument` + initial `ProcessingDocument`, then signals C3." Neither says synchronous or async. This spec commits to **post-commit event publish** (rationale in §3.1). If a future C3 spec instead expects a synchronous in-process call, this is a one-line change but should be agreed before implementation.
- **C2-R9 vs C2-R4a on FK declarations.** C2-R9 says "FKs live on the other tables pointing to `stored_documents.id`." C2-R4a says reference-data FKs (e.g., `Document.detectedDocumentType` → `document_types`) also live on the dependent tables. Consistent rule: **every FK lives with the dependent table's migration**, never on `stored_documents`. Recorded explicitly here so adjacent specs follow suit.
