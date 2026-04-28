# C4 — Workflow Engine — Change Spec

Pass 5 detailed spec for the Workflow Engine and the C4-owned `Document` / `WorkflowInstance` entities. Carries forward the C4 requirements from `03-components.md` (lines 209–272), the C4-relevant findings in `04-research/c4-c5-c7-backend-infra/findings.md`, and the codebase paths referenced in `02-analysis.md`.

C4 owns two tables: `documents` (the processed, workflow-ready entity, materialized when C3 finishes its pipeline) and `workflow_instances` (the state machine, 1:1 with `Document`). It subscribes to one C3 event (`ProcessingCompleted`) for initial materialization, calls C3 directly (`LlmExtractor.extract`) for retype re-extraction, and emits one event (`DocumentStateChanged`) consumed by C5's SSE fan-out. The retype lifecycle uses two additional internal-only events (`ExtractionCompleted` / `ExtractionFailed`) to update `Document.reextractionStatus`.

---

## 1. Requirements

Carried forward from `03-components.md` §C4. Each is verifiable.

| ID | Requirement | Verification |
|---|---|---|
| C4-R1 | `applyAction(documentId, action, payload)` looks up the matching `Transition` for `WorkflowInstance.currentStageId` and the action, evaluates the optional `StageGuard` against `Document.extractedFields`, and persists `currentStageId` + derived `currentStatus` via `WorkflowInstanceWriter.advanceStage`. Invalid actions return a typed `InvalidAction` error. Stage names come from C1 only. | Unit + property tests; grep test (C4-R10) |
| C4-R2 | `Approve` from any approval stage advances to the next configured stage. | Example-based unit test per workflow |
| C4-R3 | `Approve` from `Review` selects the first transition with `fromStage == Review`, `action == Approve`, and a guard that evaluates true (or absent). Guards read `Document.extractedFields`. Ironworks Lien Waiver: `waiverType == "unconditional"` → Filed; `== "conditional"` → Project Manager Approval. | Example-based test for both waiver values |
| C4-R4 | `Reject` from `Review` → `Rejected` (terminal). `Reject` from any other stage → `InvalidAction`. | Unit test per non-Review stage |
| C4-R5 | `Flag(comment)` from any approval stage requires non-empty trimmed `comment`, calls `setFlag(documentId, originStage = currentStage, comment)`, advances to `Review`. Empty/whitespace comment → `ValidationFailed`. | Unit + property test (every approval stage) |
| C4-R6 | `Resolve` from `Review` (with `workflowOriginStage` set): no type change → `clearFlag` and return to `workflowOriginStage`; with type change → set `reextractionStatus = IN_PROGRESS`, emit `DocumentStateChanged`, invoke `C3.LlmExtractor.extract(documentId, newDocTypeId)` (synchronous call; C3 emits `ExtractionCompleted`/`ExtractionFailed` asynchronously). On `ExtractionCompleted` → UPDATE `detectedDocumentType` + `extractedFields`, set `reextractionStatus = NONE`, `clearFlag` (currentStage stays `Review`), emit `DocumentStateChanged`. On `ExtractionFailed` → set `reextractionStatus = FAILED`, emit `DocumentStateChanged`. | Integration test driving both paths |
| C4-R7 | `FILED` and `REJECTED` are terminal — every action returns `InvalidAction`. | Property test |
| C4-R8 | Every state change emits `DocumentStateChanged { documentId, storedDocumentId, organizationId, currentStage, currentStatus, reextractionStatus, action?, comment?, at }`. No persistent stage-history table. No `previousStage` / `previousStatus`. | `@RecordApplicationEvents` test asserting payload shape |
| C4-R9 | Property tests verify: (a) every workflow reaches a terminal state under always-approve from any non-terminal stage; (b) flag→resolve no-type-change returns to origin; (c) flag→resolve with type change → `Review`, origin cleared; (d) terminal stages have zero outgoing transitions; (e) guards route correctly for each valuation. | jqwik suite |
| C4-R9a | `WorkflowEngine` accepts `WorkflowCatalog` via constructor injection. Property tests pass a generator-backed catalog. | Compile-time signature; no Spring context in C4 property tests |
| C4-R9b | Flag-origin restoration matrix: each of the eight approval stages across the three clients tested as origin for both no-type-change and type-change paths. | Parameterized example-based test |
| C4-R10 | C4 source contains zero literal stage strings or client slugs. | `grepForbiddenStrings` Gradle task (C7-R5) |
| C4-R11 | `WorkflowInstance` schema: `id, document_id FK unique, organization_id, current_stage_id, current_status, workflow_origin_stage (nullable), flag_comment (nullable), updated_at`. Status rule: review + origin → `FLAGGED`; else `currentStage.canonicalStatus`. Indexes: `(organization_id, current_status, updated_at DESC)`, `(document_id)` unique. Always starts at `Review`. | Migration SQL + Flyway test + index-existence assertion |
| C4-R12 | `advanceStage` / `setFlag` / `clearFlag` set `currentStageId` and `currentStatus` together. Unit test asserts canonical-status mapping for every seeded stage; property test asserts the rule across random workflows. | Unit + property test |
| C4-R13 | On `ProcessingCompleted`, in one DB transaction: INSERT `Document` (carrying `reextractionStatus = NONE` and the event's `extractedFields` / `rawText` / `detectedDocumentType`), INSERT `WorkflowInstance` (Review stage, `AWAITING_REVIEW`, no origin, no flag), emit `DocumentStateChanged`. `ProcessingDocument` row is left in place; C5 dashboard query filters out completed-but-undeleted rows by joining against `documents`. Atomicity tested. | Integration test with simulated mid-transaction failure |

---

## 2. Research summary

C4-relevant findings from `04-research/c4-c5-c7-backend-infra/findings.md`:

- **Event bus.** `ApplicationEventPublisher` + `@EventListener`, with `@Async` on listeners that must not block the publisher (recommendation §1). C4 publishes via the `DocumentEventBus` wrapper provided by C7.
- **Async execution.** Java 25 virtual threads, globally enabled via `spring.threads.virtual.enabled=true`. C4's retype `Resolve` path invokes `LlmExtractor.extract` synchronously and returns quickly because C3's implementation publishes events asynchronously (the LLM HTTP call runs on a virtual thread inside C3's LLM client wrapper, not on C4's caller thread); C4 reacts to `ExtractionCompleted` / `ExtractionFailed` later (§2). C4 itself does no `@Async` work — its writes are synchronous from `applyAction` and from the `@EventListener` for `ProcessingCompleted`.
- **Property-based testing.** jqwik 1.9.x as a JUnit Platform engine. `jqwik-spring` does not yet target Boot 4 (§7). Mitigation already encoded in C4-R9a: `WorkflowEngine` is constructor-injected with `WorkflowCatalog`, so engine property tests run with no Spring context.
- **Error handling.** Workflow errors (`InvalidAction`, `ValidationFailed`, `UnknownDocument`, `ExtractionInProgress`) surface as `DocflowException` and are converted to `ProblemDetail` by C5's `@RestControllerAdvice` (§4). C4 does not handle HTTP — it only throws.
- **Stage-history audit.** Research §1 retains an event-only model; the previously-considered `document_state_transitions` audit table is dropped per the component-walkthrough revision. `WorkflowInstance` is the single source of truth for current state; LLM-call history lives in `llm_call_audit` (C3).
- **`previousStage` / `previousStatus`.** Dropped from `DocumentStateChanged`. The SSE consumer (C6-R5) refetches the dashboard list on any event rather than diffing locally.

Where research left options open:

- **Lien Waiver guard form (Option A vs. Option B).** Adopt **Option A**: two guarded transitions out of `Review` for the Ironworks Lien Waiver workflow (`waiverType == "unconditional"` → Filed; `waiverType == "conditional"` → Project Manager Approval). Rationale: keeps the engine's transition-evaluation rule uniform — pick the first transition whose guard matches — and avoids special-casing "skip-stage" semantics.
- **Retype call boundary (Option 1 vs. Option 2).** Adopt **Option 1**: C4 calls `C3.LlmExtractor.extract(documentId, docTypeId)` directly (synchronous Java call; C3's implementation returns quickly and publishes `ExtractionCompleted` / `ExtractionFailed` asynchronously from inside its LLM client wrapper). The `Extraction*` events are consumed by C4 to drive `reextractionStatus` transitions. Rationale: retype is a control-flow operation initiated by C4, not a pipeline triggered by an external signal. Event-mediating it would obscure causality and add a kafkaesque hop with no decoupling benefit at this scale.
- **`ProcessingDocument` cleanup.** Deferred to a hypothetical cleanup cron (not implemented). The dashboard query (C5-R3) filters out `ProcessingDocument` rows that already have a matching `Document` so completed-but-undeleted rows do not appear in the in-flight section. C4 does **not** delete the `ProcessingDocument`; that boundary stays with C3.

---

## 3. Approach

### 3.1 Architecture

C4 is a pure-Java state machine plus two writers and one read-side reader. No web layer, no HTTP. All inputs come through three channels:

1. `WorkflowEngine.applyAction(documentId, action, payload)` — synchronous Java call from C5.
2. `@EventListener` on `ProcessingCompleted` — C3 → C4 initial-materialization handoff.
3. `@EventListener` on `ExtractionCompleted` / `ExtractionFailed` — C3 → C4 retype completion.

All outputs go to:

1. `documents` and `workflow_instances` tables (via `DocumentWriter` and `WorkflowInstanceWriter`).
2. `DocumentEventBus.publish(DocumentStateChanged)`.
3. `C3.LlmExtractor.extract(documentId, newDocTypeId)` (retype path only).

```
┌────────────────────────────────────────────────────────────────────┐
│ C5 (HTTP)                                                          │
│   POST /actions ─► WorkflowEngine.applyAction(...)                 │
└─────────────────────────────────┬──────────────────────────────────┘
                                  │
                                  ▼
┌────────────────────────────────────────────────────────────────────┐
│ C4 — WorkflowEngine                                                │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ TransitionResolver (from WorkflowCatalog) + StageGuard eval  │  │
│  └──────────────┬────────────────────┬──────────────────────────┘  │
│                 │                    │                             │
│        advance/setFlag/      LlmExtractor.extract                  │
│        clearFlag (sync)        (Option 1 — direct call)            │
│                 │                    │                             │
│                 ▼                    ▼                             │
│  ┌─────────────────────┐    ┌─────────────────────┐                │
│  │ documents           │    │ C3                  │                │
│  │ workflow_instances  │    │ (returns via events)│                │
│  └─────────────────────┘    └──────────┬──────────┘                │
│                                        │                           │
│                                        ▼                           │
│              ProcessingCompleted / ExtractionCompleted /           │
│              ExtractionFailed (DocumentEventBus)                   │
│                                        │                           │
│                                        ▼                           │
│  @EventListener  ─► materialize Document or update reextraction    │
│                                                                    │
│              DocumentStateChanged ─► DocumentEventBus              │
└────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
                         C5 SSE fan-out (per-org)
```

### 3.2 State machine layout

**`WorkflowInstance`** holds the live state. There is no separate state-machine library — the engine is a small dispatch over `Transition` records pulled from `WorkflowCatalog` (C1). This is justified because:

- The state space is fully described by C1 config; the engine reads, it does not own the topology.
- Property-based tests need to drive the engine over generator-produced topologies. A library that wires state via annotations would defeat that.
- The total transition logic is on the order of 80 lines of straight Java.

**Stage kinds** (carried from C1): `system`, `review`, `approval`, `terminal`. Only `review` and `approval` are reachable here — `system` stages belong to C3's processing pipeline. C4 never sees a workflow in a system stage.

**Status derivation** (C4-R12) lives in a single private method on `WorkflowInstanceWriter`:

```
if currentStage.kind == 'review' && workflow_origin_stage != null:
    return FLAGGED
return currentStage.canonicalStatus
```

`canonicalStatus` is a per-stage property defined in C1 config and ranges over `AWAITING_REVIEW`, `AWAITING_APPROVAL`, `FILED`, `REJECTED`. `FLAGGED` is computed at write time only — it is never stored on a stage definition.

### 3.3 Event flow

Three event subscriptions, one publication.

**Subscriptions (all `@EventListener`, all running on a virtual thread via `@Async`):**

1. **`ProcessingCompleted`** → C4-R13 handoff. Event payload carries `documentId` (assigned by C3 ahead of publication), `storedDocumentId`, `organizationId`, `detectedDocumentType`, `extractedFields`, `rawText`, `at`. **C4 performs no extra DB read** — every value needed to materialize `Document` is on the event. The Review stage id is looked up from `WorkflowCatalog` by `(orgId, detectedDocumentType)`. Both INSERTs and the publish run in one `@Transactional` boundary.
2. **`ExtractionCompleted`** → retype success path (C4-R6). Event payload carries `documentId`, `newDocTypeId`, `extractedFields`. C4 UPDATEs `Document.detectedDocumentType` + `extractedFields`, sets `reextractionStatus = NONE`, calls `clearFlag` (currentStage stays `Review` — `clearFlag` semantics on a Review-with-no-origin instance is a no-op stage-wise but writes `workflow_origin_stage = NULL` and `flag_comment = NULL`).
3. **`ExtractionFailed`** → retype failure path (C4-R6). Event payload carries `documentId`, `error`. C4 sets `reextractionStatus = FAILED`. No other state changes.

All three publish a `DocumentStateChanged` after their write. The retype events emit only `reextractionStatus` field changes — no `currentStage` change, no `action`, no `comment`.

**Publication:**

`DocumentStateChanged { documentId, storedDocumentId, organizationId, currentStage, currentStatus, reextractionStatus, action?, comment?, at }`.

`storedDocumentId` is included so SSE consumers can correlate to the file reference without a follow-up read.

### 3.4 StageGuard pattern

`StageGuard` is a typed predicate from C1 over `Document.extractedFields`. The engine evaluates it directly — no string-templated expressions, no scripting.

```java
public sealed interface StageGuard
    permits FieldEquals, FieldNotEquals, Always {
  boolean evaluate(Map<String, Object> extractedFields);
}

public record FieldEquals(String fieldPath, Object value) implements StageGuard {
  @Override public boolean evaluate(Map<String, Object> fields) {
    return Objects.equals(extract(fields, fieldPath), value);
  }
}
```

`fieldPath` is a dotted path (`"waiverType"`, or future-proofing nested e.g. `"items.0.category"` is **not** introduced — current spec needs only top-level scalars; the dotted form is reserved but unused). The Lien Waiver workflow uses two transitions out of `Review`:

| from | action | guard | to |
|---|---|---|---|
| Review | Approve | `FieldEquals("waiverType", "unconditional")` | Filed |
| Review | Approve | `FieldEquals("waiverType", "conditional")` | Project Manager Approval |

Transition resolution iterates configured transitions in order and picks the first whose `(fromStage, action)` matches and whose guard evaluates true (or is `Always`). Two guards on the same `(from, action)` pair must be **disjoint** for the configured input domain — C1 startup validation (C1-R5) is responsible for that check; C4 trusts it.

### 3.5 Retype mechanism

Triggered by `Resolve` from `Review` when the user has changed `detectedDocumentType` since the flag was raised.

The engine detects "did the user change the type" by comparing the incoming `payload.newDocTypeId` to the current `Document.detectedDocumentType`. The Review-form payload always carries `newDocTypeId`; if the field equals the stored value, no retype path is taken.

**Sequence (Resolve with type change):**

1. Validate `Document.reextractionStatus != IN_PROGRESS` — otherwise throw `ExtractionInProgress` (HTTP 409 from C5 via `ProblemDetail`).
2. UPDATE `Document.reextractionStatus = IN_PROGRESS`. (No stage change.)
3. Publish `DocumentStateChanged { ..., reextractionStatus: IN_PROGRESS }`.
4. Invoke `llmExtractor.extract(documentId, newDocTypeId)` synchronously; the call returns quickly because C3's implementation publishes events asynchronously (the LLM HTTP call runs on a virtual thread inside the LLM client wrapper, not on the caller's thread). C4 does **not** await an extraction result here — it reacts to the subsequent `ExtractionCompleted` / `ExtractionFailed` event.
5. Return synchronous result to caller — `DocumentView` reflecting the IN_PROGRESS reextraction status, currentStage `Review`.

**Sequence (`ExtractionCompleted`):**

1. UPDATE `Document.detectedDocumentType` + `Document.extractedFields` from event payload.
2. UPDATE `Document.reextractionStatus = NONE`.
3. Call `WorkflowInstanceWriter.clearFlag(documentId)` — sets `workflow_origin_stage = NULL`, `flag_comment = NULL`. Stage remains `Review` because `clearFlag` does not move the stage when called from this path; semantics are encoded as: "if we are in Review and origin is null, leave stage; else move to origin."
4. Publish `DocumentStateChanged { currentStage: Review, currentStatus: AWAITING_REVIEW, reextractionStatus: NONE }`.

**Sequence (`ExtractionFailed`):**

1. UPDATE `Document.reextractionStatus = FAILED`.
2. Publish `DocumentStateChanged { currentStage: Review, reextractionStatus: FAILED }`.

The user can re-submit `Resolve` with a different (or same) `newDocTypeId` to retry; retry semantics are caller-driven, not engine-driven.

### 3.6 DI surface

`WorkflowEngine` constructor:

```java
WorkflowEngine(
    WorkflowCatalog catalog,           // C1 — pure read
    DocumentReader documentReader,     // own read
    DocumentWriter documentWriter,     // own write
    WorkflowInstanceWriter wfWriter,   // own write
    LlmExtractor llmExtractor,         // C3 — direct synchronous call; C3 publishes Extraction* events asynchronously
    DocumentEventBus eventBus          // C7
)
```

No static singletons. Every collaborator is mockable and the catalog is replaceable per-test (C4-R9a).

---

## 4. Files & changes

All paths are absolute. Backend module root is `/Users/gb/github/basata/backend/` (greenfield; created during C7).

### 4.1 Production code

| Path | Purpose |
|---|---|
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/workflow/WorkflowEngine.java` | Public entry point: `applyAction`. Constructor-injected per C4-R9a. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/workflow/TransitionResolver.java` | Pure function: `(currentStage, action, fields, catalog) → Transition | InvalidAction`. No I/O. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/workflow/StageGuard.java` | Sealed interface: `FieldEquals`, `FieldNotEquals`, `Always`. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/workflow/WorkflowAction.java` | Sealed interface: `Approve`, `Reject`, `Flag(String comment)`, `Resolve(UUID newDocTypeId)`. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/workflow/WorkflowError.java` | Sealed: `InvalidAction`, `ValidationFailed`, `UnknownDocument`, `ExtractionInProgress`. Mapped to `DocflowException` at the C5 boundary. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/document/Document.java` | Record: id, storedDocumentId, organizationId, detectedDocumentType, extractedFields, rawText, processedAt, reextractionStatus. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/document/ReextractionStatus.java` | Enum: `NONE`, `IN_PROGRESS`, `FAILED`. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/document/DocumentReader.java` | `get(documentId)`, `list(orgId, filter) → DocumentView[]`. JPA or JDBC; reads the `documents` × `workflow_instances` × `stored_documents` projection. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/document/DocumentWriter.java` | `insert(Document)`, `updateExtraction(documentId, docTypeId, fields)`, `setReextractionStatus(documentId, status)`. UPDATE-in-place. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/workflow/WorkflowInstance.java` | Record: id, documentId, organizationId, currentStageId, currentStatus, workflowOriginStage, flagComment, updatedAt. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/workflow/WorkflowStatus.java` | Enum: `AWAITING_REVIEW`, `FLAGGED`, `AWAITING_APPROVAL`, `FILED`, `REJECTED`. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/workflow/WorkflowInstanceWriter.java` | `advanceStage(documentId, newStageId)`, `setFlag(documentId, originStage, comment)`, `clearFlag(documentId)`. Each computes `currentStatus` per C4-R12. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/workflow/ProcessingCompletedListener.java` | `@EventListener` for `ProcessingCompleted`. Implements C4-R13 inside `@Transactional`. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/workflow/ExtractionEventListener.java` | `@EventListener` for `ExtractionCompleted` and `ExtractionFailed`. Implements C4-R6 retype completion. |
| `/Users/gb/github/basata/backend/src/main/java/com/docflow/readmodel/DocumentView.java` | Read-projection record consumed by C5. Lives in shared `read-model` package per `03-components.md`. |

### 4.2 Migrations

Per `03-components.md` C7-R3 there is a **single baseline migration** `V1__init.sql` owned by C7. C4 contributes the SQL fragments for `documents` and `workflow_instances`; C7 stitches them into V1 in dependency order alongside the other component contributions (`stored_documents`, `processing_documents`, `llm_call_audit`, the C1 client-data tables, etc.).

C4's contributed fragments (per the schemas in C4-R11 + the `Document` schema block, lines 217–227):

- `documents` table with FK `stored_document_id → stored_documents.id` (UNIQUE), FK `detected_document_type → document_types.id`, JSONB `extracted_fields`, `raw_text TEXT`, `processed_at TIMESTAMPTZ`, `reextraction_status TEXT NOT NULL CHECK (reextraction_status IN ('NONE','IN_PROGRESS','FAILED')) DEFAULT 'NONE'`, `organization_id` denormalized.
  - Indexes: `(organization_id, processed_at DESC)`, `(stored_document_id)` UNIQUE.
- `workflow_instances` table with FK `document_id → documents.id` UNIQUE, composite FK `current_stage_id → stages.id`, `current_status TEXT NOT NULL CHECK (current_status IN ('AWAITING_REVIEW','FLAGGED','AWAITING_APPROVAL','FILED','REJECTED'))`, nullable `workflow_origin_stage` and `flag_comment`, `updated_at TIMESTAMPTZ NOT NULL`.
  - Indexes: `(organization_id, current_status, updated_at DESC)`, `(document_id)` UNIQUE.

C4 ships no standalone `V{n}__*.sql` file. The fragments land inside `V1__init.sql` after `stored_documents`, `document_types`, and `stages` (the tables they reference) and before any C5 read-model views that join through them.

### 4.3 Tests

| Path | Coverage |
|---|---|
| `/Users/gb/github/basata/backend/src/test/java/com/docflow/workflow/WorkflowEnginePropertyTest.java` | jqwik suite for C4-R9 (a–e) and C4-R9a. Generator-backed `WorkflowCatalog`. |
| `/Users/gb/github/basata/backend/src/test/java/com/docflow/workflow/WorkflowEngineExampleTest.java` | C4-R2, C4-R3 (both Lien Waiver values), C4-R4, C4-R5 (per approval stage), C4-R7. Parameterized. |
| `/Users/gb/github/basata/backend/src/test/java/com/docflow/workflow/FlagOriginRestorationTest.java` | C4-R9b — every approval stage in every client, both type-change paths. |
| `/Users/gb/github/basata/backend/src/test/java/com/docflow/workflow/WorkflowInstanceWriterTest.java` | C4-R12 — canonical-status mapping over seeded stages + property test over generated stages. |
| `/Users/gb/github/basata/backend/src/test/java/com/docflow/workflow/ProcessingCompletedListenerIT.java` | C4-R13 atomicity, end-to-end with a real Postgres (Testcontainers). Uses `@RecordApplicationEvents`. |
| `/Users/gb/github/basata/backend/src/test/java/com/docflow/workflow/RetypeFlowIT.java` | C4-R6 — drives `Resolve` with type change, asserts `reextractionStatus` transitions and emitted events. Uses a stub `LlmExtractor`. |
| `/Users/gb/github/basata/backend/src/test/java/com/docflow/workflow/EngineForbiddenStringsTest.java` | Sanity check for C4-R10 (the Gradle task is the source of truth, but a test-time sweep gives faster feedback). |

---

## 5. Acceptance criteria

Concrete, testable. Every criterion maps to one or more requirements.

1. **State transitions.**
   - From `Review`, `Approve` on a Riverside Receipt advances to `Filed` and `currentStatus = FILED`. (C4-R3)
   - From `Review`, `Approve` on an Ironworks Lien Waiver with `extractedFields.waiverType == "unconditional"` advances to `Filed`. (C4-R3)
   - From `Review`, `Approve` on the same waiver with `waiverType == "conditional"` advances to `Project Manager Approval` and `currentStatus = AWAITING_APPROVAL`. (C4-R3)
   - From `Review`, `Reject` advances to `Rejected` and `currentStatus = REJECTED`. (C4-R4)
   - From `Manager Approval`, `Approve` on a Riverside Invoice advances to `Filed`. (C4-R2)
   - From `Manager Approval`, `Flag("needs receipt")` advances to `Review`, `workflowOriginStage = ManagerApproval`, `flagComment = "needs receipt"`, `currentStatus = FLAGGED`. (C4-R5, C4-R12)
   - From a `Review` with `workflowOriginStage = ManagerApproval` and no type change, `Resolve` returns to `Manager Approval` and clears origin + comment. (C4-R6)
   - From a `Review` with `workflowOriginStage = ManagerApproval` and a type change, `Resolve` keeps stage at `Review` and sets `reextractionStatus = IN_PROGRESS`. After `ExtractionCompleted`, `reextractionStatus = NONE`, origin + comment cleared, stage still `Review`. (C4-R6)

2. **Guard rejections.**
   - `Approve` on a `Filed` document throws `InvalidAction`. (C4-R7)
   - `Approve` on a `Rejected` document throws `InvalidAction`. (C4-R7)
   - `Reject` from `Manager Approval` throws `InvalidAction`. (C4-R4)
   - `Flag("")` from any approval stage throws `ValidationFailed`. (C4-R5)
   - `Flag("   ")` from any approval stage throws `ValidationFailed`. (C4-R5)
   - `Resolve` while `reextractionStatus = IN_PROGRESS` throws `ExtractionInProgress`. (C4-R6)

3. **Event emissions.**
   - Every successful `applyAction` publishes exactly one `DocumentStateChanged`. (C4-R8)
   - Every `ProcessingCompleted` consumed by C4 yields one `DocumentStateChanged` with `currentStage = Review`, `currentStatus = AWAITING_REVIEW`, `reextractionStatus = NONE`. (C4-R13)
   - The retype path emits two `DocumentStateChanged` events: one with `reextractionStatus = IN_PROGRESS` (on `Resolve`), one with `reextractionStatus = NONE` (on `ExtractionCompleted`) — or `FAILED` (on `ExtractionFailed`). (C4-R6)
   - No `DocumentStateChanged` event carries `previousStage` or `previousStatus`. (C4-R8)

4. **Persistence invariants.**
   - For every `WorkflowInstance` row, `currentStatus` matches the C4-R12 derivation rule. Asserted by a property test over inserted rows. (C4-R12)
   - Every `Document` row has a corresponding `WorkflowInstance` row. (C4-R13)
   - No `WorkflowInstance` row has `currentStage.kind == 'system'`. (C4-R11)

5. **Source discipline.**
   - `grepForbiddenStrings` Gradle task passes — no literal stage strings or client slugs in `com.docflow.workflow` or `com.docflow.document`. (C4-R10)

---

## 6. Verification

### Commands

```bash
# Full backend build (Spotless, Checkstyle, PMD, grepForbiddenStrings, all tests)
cd /Users/gb/github/basata && ./gradlew :backend:check

# Workflow tests only
./gradlew :backend:test --tests 'com.docflow.workflow.*'

# Property tests
./gradlew :backend:test --tests 'com.docflow.workflow.WorkflowEnginePropertyTest'

# Integration tests (Testcontainers — slower)
./gradlew :backend:integrationTest --tests 'com.docflow.workflow.*IT'

# Migration replay (validates the single V1__init.sql baseline including C4's contributed fragments)
./gradlew :backend:flywayMigrate flywayValidate
```

### Test specifics

- **C4-R8 event-shape contract test.** `@RecordApplicationEvents` + Jackson 3 round-trip. Asserts `previousStage` and `previousStatus` are absent from the serialized JSON (failing the test if a future change re-introduces them).
- **C4-R9a property tests.** Run with `useJUnitPlatform { includeEngines("jqwik", "junit-jupiter") }` and **without** `@SpringBootTest` — the engine is constructed in-test with a generator catalog, no Spring context.
- **C4-R13 atomicity.** Integration test that injects a `DocumentWriter` proxy throwing during INSERT; asserts neither row is present after the failure and no `DocumentStateChanged` was emitted.
- **C4-R10 grep task.** Gradle's `check` includes `grepForbiddenStrings` (per C7-R5). C4 source must contribute zero hits.

---

## 7. Error handling and edge cases

| Case | Behavior |
|---|---|
| Invalid action for current stage (e.g., `Approve` on `Filed`) | Throw `InvalidAction`; no DB write; no event. C5 maps to `INVALID_ACTION` 409. |
| `Reject` from non-Review stage | Throw `InvalidAction` (C4-R4). |
| `Flag` with empty/whitespace `comment` | Throw `ValidationFailed { details: [{ path: "comment", message: "must be non-empty" }] }`. C5 maps to `VALIDATION_FAILED` 400. |
| `Resolve` while `reextractionStatus == IN_PROGRESS` | Throw `ExtractionInProgress` 409. The user must wait for the extract to complete (or fail) before resolving again. |
| `Resolve` while `reextractionStatus == FAILED`, no type change | Treated as standard no-type-change resolve; advance to `workflowOriginStage`, set `reextractionStatus = NONE`. The failed status is cleared by the next successful resolve. |
| `Resolve` while `reextractionStatus == FAILED`, with type change | Standard retype: set `reextractionStatus = IN_PROGRESS`, invoke extract again. |
| Lien Waiver `Approve` from `Review` with `waiverType` missing or unrecognized | No transition matches; throw `InvalidAction`. C1 startup validation should catch malformed config; here the engine surfaces it as a runtime invalid action. |
| Two guards on the same `(fromStage, action)` both evaluate true | First match wins (transition list order). C1 validates disjointness at startup; this case is therefore a config bug — engine remains deterministic. |
| `ProcessingCompleted` arrives twice for the same `documentId` | Second INSERT fails on `(stored_document_id)` UNIQUE; the listener's `@Transactional` rolls back, no second `DocumentStateChanged` is emitted. The duplicate is logged at WARN. |
| `ExtractionCompleted` arrives but document is no longer `IN_PROGRESS` | Race; ignore the event (log at INFO). State is the source of truth. |
| `ExtractionFailed` arrives after a successful `ExtractionCompleted` | Same as above — ignore. |
| Document not found (event with unknown id) | Log at WARN, skip. Do not throw; that would crash the listener. |
| Concurrent `applyAction` on the same document | Optimistic locking via `updated_at` + retry-once. (C5 surfaces `INTERNAL_ERROR` if retry also fails — concurrent UI use is not a take-home scenario but the integrity invariant is preserved.) |

---

## 8. Migration / backwards compatibility

Greenfield. The single `V1__init.sql` baseline (owned by C7, with C4 contributing fragments — see §4.2) is the first to introduce `documents` and `workflow_instances`. No data migration required; no prior schema to preserve.

The earlier-considered `document_state_transitions` audit table is **dropped entirely** from the model. If a future revision reintroduces stage-history audit, it would land as a new migration; `WorkflowInstance` remains the source of truth for current state in either case.

`previousStage` / `previousStatus` are absent from the published `DocumentStateChanged` shape. SSE consumers (C6) do not depend on them; the documented refetch-on-event pattern (C6-R5) replaces local diffing.

