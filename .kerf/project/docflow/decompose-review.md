# Decompose Review

Round 1 was a single general-purpose review. Round 2 fanned out to four specialist reviewers (domain model, data/schema, API/SSE, testability). This file consolidates both rounds and records what was applied.

---

## Round 1 — general review

All Round 1 findings were applied in the first pass:

- **B1 (DAG).** Added explicit `DocumentWriter` interface on C2; C3 writes through it; DAG and interface table updated.
- **B2 (C3↔C4 cycle).** Rerouted completion signal through `DocumentEventBus` — C3 emits, C4 subscribes.
- **B3 (frontend coverage bar).** Added C7-R6a with Vitest threshold enforcement + TODO marker.
- **B4 (filter scoping).** Tightened C6-R2 with disjoint-option test requirement across orgs.
- **N1** C2-R1 rephrased (unauthenticated).
- **N2** C3-R5 loosened — audit-metadata requirement is binding, storage scheme is deferred.
- **N4** C1 gained an Open Items block (config format, etc.).
- **N5** C5-R10 marked optional polish.
- **N6** C7-R5 JaCoCo exclusion list made explicit (generated code, Flyway, configs, DTOs).
- **N7** G1 traceability row tightened.

---

## Round 2 — four specialist reviewers

### Domain-model lens

**Blocking:**
- **DM-B1. `Document` fused with `WorkflowInstance`.** `currentStage`, `workflowOriginStage`, `flagComment` live on `Document`. Split into a `WorkflowInstance` entity with FK to `Document`. **Applied.**
- **DM-B2. `Transition` is unnamed.** `(fromStage, action, toStage, guard?)` should be a first-class domain concept. **Applied** — named in C1 and C4.
- **DM-B3. `StageGuard` / `TransitionCondition` not named.** The shape of the condition (predicate over `Document.extractedFields`) should be committed even if syntax is deferred. **Applied.**

**Non-blocking applied:**
- **DM-NB3.** Reserved `role` slot on stage definitions (shape TBD in research). Applied.
- **DM-NB5.** Added `StageHistory` / state-transition persistence. Applied (see DB-B2).
- **DM-NB7.** Named `DocumentEventBus` in C7 (platform) as cross-cutting infrastructure. Applied.

### Data/schema lens

**Blocking:**
- **DB-B1. No `llm_call_audit` table named** for C3-R5 metadata. **Applied** — C3 now names the table.
- **DB-B2. No state-transition history table** for C4-R8 events. **Applied** — `document_state_transitions` added.
- **DB-B3. `organizations` / `document_types` reference-table seeding under-specified.** **Applied** — C1/C7 now name the seeding Flyway migration + startup-validation behavior.
- **DB-B4. Composite index `(organization_id, current_stage, uploaded_at DESC)` missing.** **Applied** — C2-R9 updated.

**Non-blocking applied:**
- JSONB usage made consistent in the text.
- Eval recording storage pinned to `eval/recordings/{sample-id}/{prompt-version}/{classify|extract}.json`.
- Migration strategy clarified (per-table migrations, not one giant baseline).

### API/SSE lens

**Blocking:**
- **API-B1. Action payload shape.** Pinned as discriminated union keyed on `action`, with per-action payload schemas.
- **API-B2. PATCH review conflates save-fields and change-type.** Split into `PATCH /review/fields` and `POST /review/retype`.
- **API-B3. SSE event menu incomplete.** Enumerated full vocabulary: `DocumentStateChanged`, `ClassificationCompleted`, `ClassificationFailed`, `ExtractionCompleted`, `ExtractionFailed`.
- **API-B4. Error codes undefined.** Added enumeration of error codes with per-endpoint HTTP status mapping.

**Non-blocking applied:**
- SSE reconnection contract: `id:` per event, `retry:` hint on server.
- Range requests on file endpoint explicitly scoped out for the take-home.
- CORS / dev-proxy policy added to C7.

### Testability lens

**Blocking:**
- **T-B1. Grep tests for hardcoded stage/client names are unenforced.** **Applied** — C7 now wires grep check as a Gradle `check`-task dependency.
- **T-B2. Synthetic-fourth-org test is unassigned.** **Applied** — assigned to C1 as an integration test.
- **T-B3. `WorkflowCatalog` injection for property tests.** **Applied** — C4 now requires constructor-injectable catalog.
- **T-B4. Integration tests at Anthropic HTTP seam.** **Applied** — added to C3.
- **T-B5. Flag-origin restoration example tests.** **Applied** — matrix tests required in C4.

**Non-blocking applied:**
- Hedge words ("clear error", "acceptable") tightened where fixes were low-cost.
- Seed-manifest verification pinned.
- TODO placeholder file path pinned to `frontend/vitest.config.ts`.
- Flag-and-resolve E2E added to CI set in C7.
- Config-validation failure tests, API error-shape contract tests, re-extraction idempotency test, PDF-extraction-failure tests — all pinned.

---

## Not applied (deliberate)

- **DM-NB2 / NB6.** Field-schema expressiveness, multi-tenancy asymmetry — reviewer flagged as correct as-is. No change.
- **DB-5 JSONB for nested arrays tradeoff.** Kept in JSONB per problem-space permission. Added a one-line tradeoff note to C2 rather than re-opening the decision.
- **API CORS promotion to required config** — C7 now mentions dev-proxy; full CORS policy is implementation detail.
- **Testability hedge language on `C5-R3 "acceptable page size ≥ 50"`** — pinned to exactly 50 for the test but kept "≥" in prose as minimum.
