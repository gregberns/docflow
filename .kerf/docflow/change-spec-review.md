# Change Spec Review — Round 1

## Summary
- Specs reviewed: 7 (`c1-config`, `c2-ingestion`, `c3-pipeline`, `c4-workflow`, `c5-api`, `c6-frontend`, `c7-platform`)
- Verdict: **accept-with-fixes**
- Blocking issues: 7
- Non-blocking issues: 8

The seven specs each cover their requirements with concrete acceptance criteria, runnable verification commands, and explicit error-handling tables. The workflow engine, processing pipeline, and platform specs in particular are tight. Cross-spec drift is concentrated in three areas: migration ordering (a real bug — half the specs missed the `V1__init.sql` baseline directive), the Java package root (`com.basata.docflow.*` vs. `com.docflow.*`), and the C5 error taxonomy (C2 and C6 invent codes that aren't in the canonical list). None of these is design-deep — they're all fixable by an integration-pass edit.

---

## Blocking issues

### Issue 1: Migration ordering violates the single-baseline `V1__init.sql` decision
- **Specs affected:** `c1-config-spec.md` (§4.3), `c2-ingestion-spec.md` (§4.2 + AC-R9), `c4-workflow-spec.md` (§4.2)
- **Description:** `03-components.md` C7-R3 and HANDOFF.md mandate a **single baseline** `V1__init.sql` containing every table. C7's spec follows this correctly. C3's spec follows it (annotates SQL fragments as contributions to V1). However:
  - C1 spec proposes `V2__org_config_tables.sql` ("V1 is reserved for the C2/C3/C4/C5 transactional schema; ordering is finalized in cross-spec review").
  - C2 spec proposes `V2__stored_documents.sql` ("V1 reserved for C7's organizations + reference tables").
  - C4 spec proposes `V4__c4_documents_and_workflow_instances.sql` and even prescribes "V1=stored_documents, V2=processing_documents+llm_call_audit, V3=organizations".

  These three are mutually inconsistent (C1 and C2 both claim V2 for different tables; C4 imagines a different overall ordering) **and** they all violate the canonical decision regardless of which one would have "won."
- **Recommended fix:** Edit C1, C2, and C4 to drop the per-context `V{n}__*.sql` files and reframe their migration sections as **SQL fragments contributed to the single `V1__init.sql` migration owned by C7**, mirroring the language already used in C3 ("C3 contributes the SQL fragments; C7 stitches the migration"). Update the C2 acceptance criterion AC-R9 (`./gradlew flywayInfo` shows V2 applied with the column set + index) and the C4 verification step similarly. C7's V1 already names `stored_documents`, `processing_documents`, `documents`, `workflow_instances`, `llm_call_audit`, plus the C1 client-data tables, in the right order.
- **Owner:** C1, C2, C4 spec authors.

### Issue 2: Java root-package inconsistency (`com.basata.docflow.*` vs. `com.docflow.*`)
- **Specs affected:** `c1-config-spec.md` (uses `com.basata.docflow.*`), `c2-ingestion-spec.md` (uses `com.basata.docflow.*`), `c3-pipeline-spec.md` (uses `com.docflow.c3.*`), `c4-workflow-spec.md` (uses `com.docflow.workflow.*`), `c5-api-spec.md` (uses `com.docflow.api.*`), `c7-platform-spec.md` (allow-lists `com.docflow.config/`)
- **Description:** Two root-package conventions are in flight. C1 and C2 use `com.basata.docflow.*`; C3, C4, C5 use `com.docflow.*`; C7's `grepForbiddenStrings` allow-list (which gates env-var reads) hard-codes `backend/src/main/java/com/docflow/config/` as the only legitimate location for `System.getenv` / `@Value` / `.env`. As written, C1's `AppConfig.OrgConfigBootstrap` lives at `com.basata.docflow.config.org.AppConfig` and C2's `IngestionConfig` lives at `com.basata.docflow.ingestion.internal` — neither matches C7's allow-list, so the build will fail on the first env read. Even apart from the C7-R13 angle, mixed roots will not compile cleanly together.
- **Recommended fix:** Pick one root and apply globally. Recommend `com.docflow.*` (matches C3/C4/C5/C7 — the larger set, and C7's allow-list is already pinned to it). Edit C1 to move `com.basata.docflow.config.**` → `com.docflow.config.**` (and matching test paths). Edit C2 to move `com.basata.docflow.ingestion.**` → `com.docflow.ingestion.**`.
- **Owner:** C1 and C2 spec authors.

### Issue 3: C2's `IngestionConfig` and C1's `OrgConfigBootstrap` violate "AppConfig is the only config reader"
- **Specs affected:** `c2-ingestion-spec.md` (§4.1 lists `IngestionConfig.java` with `@ConfigurationProperties("docflow.storage")`), `c1-config-spec.md` (§3.8 contributes `OrgConfigBootstrap` but says "C7 owns the AppConfig record. C1 contributes one nested config block" — narratively right, but §4.1 then puts the binder inside C1's package), `c7-platform-spec.md` (R13 + grep allow-list permits config reads only inside one allow-listed config package).
- **Description:** C7-R13 says all env / config reads go through one `AppConfig` (or a small nested set) inside one allow-listed package. C2 spec adds its own `@ConfigurationProperties` class outside that package; C1's spec *says* `AppConfig` lives in C7 but writes the binder source location into C1's own package tree. Either reading is consistent with C7's grep regex (`@Value\(`) which targets only literal `@Value`; neither directly trips the regex (the regex doesn't catch `@ConfigurationProperties`). But the *intent* of C7-R13 — "one place reads externals, everything else gets values injected" — is violated, and the path-based allow-list is too narrow to admit either C1's or C2's binders.
- **Recommended fix:** Reconcile the storyline. Two options:
  - **(a, recommended)** C7's `AppConfig` is a tree of nested records (`Llm`, `Storage`, `Database`, `OrgConfigBootstrap`); C1 contributes the *type definition* of `OrgConfigBootstrap` and C2 contributes the *type definition* of `Storage`; both type definitions live in `com.docflow.config` (C7's allow-listed package). No `@ConfigurationProperties` annotation appears anywhere outside that package. Other components consume the typed records by injection.
  - **(b)** Extend the C7 grep allow-list to additional config packages and accept multiple binders. Pricier in conceptual surface; rejected by C7-R13's "single typed object" wording.
  Pick (a). Edit C1 §3.8 / §4.1 and C2 §4.1 / §4.3 so the property-bound types live under `com.docflow.config` and other components only see them via injection.
- **Owner:** C1 and C2 spec authors; C7 spec author confirms the consolidated record tree.

### Issue 4: Error taxonomy drift — C2 and C6 use codes not in C5's canonical set
- **Specs affected:** `c2-ingestion-spec.md` (uses `INVALID_ORG`, `STORAGE_FAILURE`, `INGESTION_FAILURE`), `c6-frontend-spec.md` (handles `STALE_VERSION`, `INVALID_TRANSITION`, `422 VALIDATION_FAILED`), `c5-api-spec.md` (the canonical taxonomy).
- **Description:** C5's R9a names exactly 11 codes (the spec also acknowledges the "8-code" name is shorthand). C2 spec invents `INVALID_ORG` (should be `UNKNOWN_ORGANIZATION`), `STORAGE_FAILURE`, `INGESTION_FAILURE` — these aren't in the catalog. C6 spec handles `STALE_VERSION` and `INVALID_TRANSITION` errors that C5 never produces (and 422 instead of 400 for VALIDATION_FAILED). Neither divergence is intentional; both look like authors writing from memory.
- **Recommended fix:**
  - C2: replace `INVALID_ORG` with `UNKNOWN_ORGANIZATION`; replace `STORAGE_FAILURE` / `INGESTION_FAILURE` with `INTERNAL_ERROR` (C5's catch-all) — or, if the team thinks these deserve dedicated codes, add them to C5-R9a in the same edit pass.
  - C6: drop `STALE_VERSION` and `INVALID_TRANSITION` handling (no producer); fix the validation-failed status to 400. The optimistic-concurrency story is genuinely missing from the canonical taxonomy — note as Issue 11 below.
  - C5: per the brief, surface in the spec body that the "8-code" label is a misnomer (already done — C5-R9a row says "ten codes total"; bump to 11 to match the actual list).
- **Owner:** C2 and C6 spec authors edit; C5 spec author confirms the canonical list is exactly 11.

### Issue 5: Ingestion return shape mismatch between C2 and C5
- **Specs affected:** `c2-ingestion-spec.md` (interface declared as `upload(orgId, file) → storedDocumentId`), `c5-api-spec.md` (response body `{ "storedDocumentId": "<uuid>", "processingDocumentId": "<uuid>" }`)
- **Description:** C5 needs **both** ids in the upload response (so the frontend can correlate the optimistic processing row to the server's `processingDocumentId`). C2 declares the interface to return only `storedDocumentId`. Since both rows are inserted in the same C2 transaction, returning both is essentially free.
- **Recommended fix:** Edit C2 §1 ("Cross-component contracts") and §3.1 step 8 to declare the return shape as `{ storedDocumentId, processingDocumentId }` (e.g., a typed record `IngestionResult`). Update C2's AC-R1 to match and add an assertion that the response carries both ids.
- **Owner:** C2 spec author.

### Issue 6: `forbidden-strings.txt` ownership: C1 ships it, C7 ignores it
- **Specs affected:** `c1-config-spec.md` (§3.6 + §4.5: "Literal list (committed at `config/forbidden-strings.txt`)"), `c7-platform-spec.md` (§3.6: hard-codes the same list as Kotlin regexes inside `build.gradle.kts` and never reads `config/forbidden-strings.txt`).
- **Description:** C1 says the list lives in a text file consumed by the Gradle task. C7 inlines the same list as `Regex(...)` literals in the task definition. Two sources of truth that will inevitably drift. Per the brief: "C1 ships `config/forbidden-strings.txt`; C7 wires the Gradle task to consume it."
- **Recommended fix:** Edit C7 §3.6 so the task **reads `config/forbidden-strings.txt`** at execution time and builds the regex set from it (or treats each line as a literal string match). Drop the inline regex list. Keep C1's `config/forbidden-strings.txt` as the single source of truth. C1's grep-fixture test (AC-G1, AC-G2) gives end-to-end coverage; C7 adds a test that the task fails fast if the file is missing.
- **Owner:** C7 spec author.

### Issue 7: C5's retype endpoint refers to a `WorkflowEngine.beginRetype` method that C4 doesn't expose
- **Specs affected:** `c5-api-spec.md` (§3.2 retype: "Calls `WorkflowEngine.beginRetype(documentId, newDocumentType)`"), `c4-workflow-spec.md` (`WorkflowEngine` exposes only `applyAction`; retype is triggered through the `Resolve` action with a `newDocTypeId` payload, per §3.5).
- **Description:** C4 spec's surface is `WorkflowEngine.applyAction(documentId, action, payload)` and the retype path is "`Resolve` with a `newDocTypeId` payload that differs from the current `detectedDocumentType`." C5 spec invents a separate `beginRetype` method on the engine. The two can't both be true.
- **Recommended fix:** Edit C5 §3.2 retype description to say it calls `WorkflowEngine.applyAction(documentId, Resolve, { newDocumentType })`. (Note: the C5 endpoint contract — `POST /api/documents/{documentId}/review/retype` returning 202 with `{ reextractionStatus: "IN_PROGRESS" }` — still stands; only the internal call wording changes.) Alternatively, C4 spec adds `beginRetype` as a sugar method that delegates to `applyAction(Resolve, …)`. The first option is closer to C4's "all writes go through one engine method" discipline.
- **Owner:** C5 spec author edits; C4 spec author confirms.

---

## Non-blocking issues

### Issue 8: C7 hard-codes the seed-manifest filenames; this aligns with the brief but should be acknowledged
- **Specs affected:** `c7-platform-spec.md` §3.7
- **Description:** Per the brief: "Confirm C7 actually pins the filenames (rather than deferring)." It does — twelve specific paths under `problem-statement/samples/...` are listed in §3.7 with selection rationale. This is correct and on-spec; no change needed. Flagged here only because the brief asked for explicit confirmation.
- **Recommended fix:** None — the spec is correct.

### Issue 9: `ProcessingDocument` event-name placeholder ("StoredDocumentIngested") never resolved by C3
- **Specs affected:** `c2-ingestion-spec.md` (§3.1 step 7, §10 item 1), `c3-pipeline-spec.md` (§3.1, §3.9: enumerates `ProcessingStepChanged`, `ProcessingCompleted`, `ExtractionCompleted`, `ExtractionFailed` — no upload-trigger event named).
- **Description:** C2 spec publishes a `StoredDocumentIngested`-or-equivalent event after upload. C3 spec lists only the four lifecycle events; no upload-trigger event is named. C3 triggers the pipeline somehow — either via the named event (then C3 should declare it) or via a direct call (then C2's "post-commit event publish" decision is moot). This is the actual cross-spec cross-cut C2 §10 item 1 flagged.
- **Recommended fix:** Reconcile in integration. Cleanest landing: C3 declares a `StoredDocumentIngested` event (or names whatever the trigger should be) in §3.9 and adds the listener; C2 keeps the post-commit publish.
- **Owner:** C3 spec author adds the event; C2 spec author confirms wording.

### Issue 10: C4-R6 retype "asynchronous" wording vs. C4 §3.5's "C3's `@Async` method returns a `CompletableFuture`"
- **Specs affected:** `c4-workflow-spec.md` (§3.5: "C3's `@Async` method returns a `CompletableFuture<Void>` that C4 ignores"), `c3-pipeline-spec.md` (`LlmExtractor.extract` not declared as `@Async`; emits events itself).
- **Description:** C4 spec describes `LlmExtractor.extract` as a Spring `@Async` method returning a future. C3 spec doesn't annotate it `@Async`; it lives in `c3.llm` and is invoked by both the orchestrator (which itself runs on a worker thread) and C4 (synchronously per §3.5 retype rationale). Whether the method itself is `@Async` is unclear; both specs work if it isn't (C4 already says it doesn't await). But the wording disagrees and the test stubs in C3 (`LlmExtractorTest`) and C4 (`RetypeFlowIT`) need the same answer.
- **Recommended fix:** Edit C4 §3.5 to say: "C4 invokes `LlmExtractor.extract(...)` synchronously; the call returns quickly because C3's implementation publishes events asynchronously (the LLM HTTP call runs on a virtual thread inside the LLM client wrapper, not on the caller's thread)." Drop the "returns `CompletableFuture<Void>`" line. C3's existing description is fine.
- **Owner:** C4 spec author.

### Issue 11: Optimistic concurrency story is missing from C5-R9a
- **Specs affected:** `c5-api-spec.md` (R9a), `c4-workflow-spec.md` (§7 mentions "optimistic locking via `updated_at` + retry-once" with overflow surfaced as `INTERNAL_ERROR`), `c6-frontend-spec.md` (handles `STALE_VERSION` 409).
- **Description:** C4 plans optimistic locking but maps overflow to `INTERNAL_ERROR`; C6 expects a `STALE_VERSION` 409 with a typed code. The specs disagree about whether stale-edit collisions get their own code or fall under the catch-all.
- **Recommended fix:** Decide and align. Either (a) keep `INTERNAL_ERROR` for stale-edit (simplest; C6 adjusts its handler), or (b) add `STALE_VERSION` (409) to C5-R9a as a 12th code (C6's handler is right; C5 and C4 update). For a take-home with no concurrent editors, (a) is sufficient.
- **Owner:** C5 + C6 spec authors.

### Issue 12: C5's `OrganizationCatalog.exists(orgId)` API is named differently from C1
- **Specs affected:** `c2-ingestion-spec.md` (§3.8: `OrganizationCatalog.exists(orgId)`, hedged as "or whatever C1 names it"), `c1-config-spec.md` (`OrganizationCatalog` declares `getOrganization(orgId) → Optional<OrganizationView>`, `listOrganizations()`, `getAllowedDocTypes(orgId)` — no `exists`).
- **Description:** Minor — easy to fix. C2 should call `getOrganization(orgId).isPresent()`.
- **Recommended fix:** Edit C2 §3.8 and §3.1 step 1 to say "calls `OrganizationCatalog.getOrganization(orgId)` and rejects on empty." Or extend C1's interface with `exists(orgId)` if the team prefers.
- **Owner:** C2 spec author.

### Issue 13: C6 frontend coverage threshold and C7's placeholder
- **Specs affected:** `c6-frontend-spec.md` §6.5 (sets 70% line / 60% branch), `c7-platform-spec.md` C7-R6a (commits `0` placeholder + TODO comment).
- **Description:** Per the brief: "C6 sets the value (research-recommended 70% line / 60% branch); C7 references but does not set." Both specs do this correctly — C7 commits the placeholder, C6 sets the real number when the frontend module is built. No change.
- **Recommended fix:** None.

### Issue 14: C3 audit row's `model_id` index missing
- **Specs affected:** `c3-pipeline-spec.md` §4 schema fragment.
- **Description:** Schema indexes `(stored_document_id, at DESC)`, `(processing_document_id)`, `(document_id)`. No index on `model_id` — but no spec query filters by `model_id` either, so this is fine. Flagged only to confirm there's no missing index.
- **Recommended fix:** None.

### Issue 15: C7 `SyntheticFourthOrgTest` overlaps C1's `FourthOrgSeederTest`
- **Specs affected:** `c1-config-spec.md` (lists `FourthOrgSeederTest` as the C1-R9 verification), `c7-platform-spec.md` (lists `SyntheticFourthOrgTest` as a thinner C7 test).
- **Description:** Two tests, same purpose. Keep one.
- **Recommended fix:** Drop C7's `SyntheticFourthOrgTest`; the C1 test already covers C1-R9. C7's CI invocation runs the C1 suite anyway.
- **Owner:** C7 spec author.

---

## Requirement coverage check

For each component, the spec's coverage map (or equivalent traceability table) was checked against the C{N}-R{n} list in `03-components.md`. A requirement is "covered" if it has a section number, an acceptance criterion, or a test path tied to it.

### C1 (Config Layer) — full coverage
All of C1-R1, R2, R3, R4, R4a, R5, R6, R7, R8, R9, R10, R11, R12 appear in the §1 traceability table and have backing acceptance criteria.

### C2 (Ingestion & Storage) — full coverage
C2-R1, R2, R4, R4a, R5, R6, R7, R9, R9a all in the §1 table. C2-R3 and C2-R8 are explicitly reserved/removed in 03-components.md and the spec acknowledges that.

### C3 (Pipeline & Eval) — full coverage
C3-R1, R2, R4, R5, R5a, R6, R7, R8, R9, R12, R13, R14, R16 all covered. C3-R3, R10, R11, R15 are explicitly removed in 03-components.md and the spec notes that. **Open item:** C3 spec adds a hybrid-modality `inputModality` flag on the C1 doc-type record (§3.5). C1 spec's `DocTypeDefinition` does **not** include this flag. Coverage gap: either C1 adds the field or C3 stops modeling it as a config attribute. (Filed as Issue 16 below.)

### C4 (Workflow Engine) — full coverage
C4-R1 through C4-R13, R9a, R9b all in the §1 traceability table.

### C5 (API & Real-Time) — full coverage
C5-R1 through C5-R9, R9a all in the §1 traceability table. C5-R10 explicitly removed in 03-components.md.

### C6 (Frontend) — full coverage
C6-R1 through C6-R14 all in the §1 traceability table.

### C7 (Platform & Quality) — full coverage
C7-R1 through C7-R14 all in the §1 traceability table.

### Issue 16 (newly surfaced): `inputModality` flag missing from C1 schema
- **Specs affected:** `c1-config-spec.md` (§3.2 `DocTypeDefinition` does not have `inputModality`), `c3-pipeline-spec.md` (§3.5 hybrid decision references it as a per-doc-type config attribute).
- **Recommended fix:** Add `InputModality` enum (`TEXT`, `PDF`) and an `inputModality` field on `DocTypeDefinition` with a default of `TEXT`. Update C1 seed YAML for the four nested-array doc-types to set `PDF`. Or, alternatively, drop the per-doc-type knob and pick one modality globally in C3's `AppConfig.llm` block.
- Severity: **non-blocking** (configuration tunable, not behaviorally correct/incorrect at the spec bar).

---

## Cross-spec consistency check

| Dimension | Verdict |
|---|---|
| **Migration ordering — single `V1__init.sql`** | **Disagree.** C1, C2, C4 violate the canonical decision; C3, C7 follow it. Issue 1. |
| **`llm_call_audit` FK shape** | **Agree.** C3 (§3.7) and C7 (§3.3) both specify `stored_document_id` always populated, `processing_document_id` and `document_id` mutually exclusive via CHECK. C4 doesn't touch the table. |
| **Dashboard read-model shape** | **Agree.** Both C5 (§3.2) and C6 (§3.3 query keys, §5.2 stats bar) name the response `{ processing, documents, stats }` with stats keys `inProgress`, `awaitingReview`, `flagged`, `filedThisMonth`. C6's §9.2 "contradiction surfaced" was authored before the C5 spec landed; the C5 spec confirms it. |
| **SSE event vocabulary** | **Agree.** C4 (§3.3), C5 (§3.2 stream block + §5.9 AC), and C6 (§3.4 hook listeners) all carry exactly `ProcessingStepChanged` and `DocumentStateChanged`; `ProcessingCompleted`, `ExtractionCompleted`, `ExtractionFailed` are internal-only. |
| **`DocumentStateChanged` payload** | **Agree.** C4 §3.3 / §5.AC3, C5 §3.2 / §5.9, C6 §3.4 all carry `reextractionStatus` (NONE / IN_PROGRESS / FAILED) and explicitly do not carry `previousStage`/`previousStatus`. C4-R8 has a contract test that fails if the absent fields reappear. |
| **Error taxonomy size** | **Mixed.** C5 acknowledges "ten codes total; '8-code' is a shorthand" (correct count for the listed entries). C2 and C6 introduce non-canonical codes (Issue 4). The 03-components.md C5-R9a list is 11 entries — including `INTERNAL_ERROR` — so C5's "ten" is itself one short. Recommend canonicalizing on **11 codes** in 03-components and C5. |
| **Retype call boundary (Option 1)** | **Agree on direction; disagree on signature.** All three (C3, C4, C5) agree that C4 calls C3 directly. C4 and C5 disagree on the method name (`applyAction(Resolve, ...)` vs. `beginRetype(...)`) — Issue 7. C3 surfaces `REEXTRACTION_IN_PROGRESS` 409 via C4's `ExtractionInProgress` exception → C5's `@RestControllerAdvice` mapping; this part matches. |
| **Ingestion return shape** | **Disagree.** C2 returns only `storedDocumentId`; C5 returns both ids. Issue 5. |
| **`grepForbiddenStrings` ownership** | **Disagree.** C1 commits the file `config/forbidden-strings.txt`; C7 inlines the same list as Kotlin regexes and never reads the file. Issue 6. |
| **`AppConfig` as the only config reader** | **Disagree.** C1 and C2 both write `@ConfigurationProperties` binders outside C7's allow-listed `com.docflow.config` package. Issue 3. |
| **Frontend coverage threshold** | **Agree.** C6 sets 70% line / 60% branch; C7 commits `0` placeholder with TODO comment. |
| **Seed manifest filename split** | **Agree.** C7 §3.7 names all 12 files with paths and rationale. No deferral. |
| **Canonical vocabulary** | **Mostly agree.** All seven specs use `StoredDocument`, `ProcessingDocument`, `Document`, `WorkflowInstance`, `WorkflowStatus`, `canonicalStatus`, `currentStep`, `reextractionStatus`, `StageGuard`, `DocumentEventBus`, `AppConfig`, `claude-sonnet-4-6` consistently. See §Vocabulary check below for minor casing issues. |

---

## Vocabulary check

Tokens are used consistently across specs with the following exceptions:

- **`com.basata.docflow.*` vs. `com.docflow.*`** — see Issue 2. This is the most disruptive vocabulary drift because grep-task allow-lists, test selectors (`./gradlew test --tests "com.basata.docflow.config.*"` vs. `./gradlew test --tests "com.docflow.workflow.*"`) and import paths all key off the root.
- **`UNKNOWN_ORGANIZATION` (canonical) vs. `INVALID_ORG` (C2)** — see Issue 4.
- **`workflowOriginStage` vs. `workflow_origin_stage` vs. `originStage`** — these are arguably distinct (the first is the JSON field, the second the SQL column, the third a method-parameter shorthand); usage is consistent with that distinction across specs. Not a real issue but worth noting that two-word casing has appeared as `workflowOriginStage` (most specs), `workflow_origin_stage` (SQL only), and `originStage` (C4 §3.5 sequence prose, where it's a local variable). All three are intelligible.
- **`canonicalStatus` (camelCase, on stage definitions) vs. `currentStatus` (camelCase, on `WorkflowInstance`)** — these are different concepts, used correctly throughout.
- **`Resolve(UUID newDocTypeId)` (C4 §4) vs. `Resolve` no payload (C5 §3.2 action body)** — minor: the action body in C5 is `{ "action": "Resolve" }` with no `newDocumentType`, but the retype path is the separate `POST .../review/retype` endpoint. C4's `Resolve` in `WorkflowAction` carries `newDocTypeId` because that's how the engine receives it (the C5 retype controller calls `applyAction` after stamping `newDocTypeId` from the URL body). Both specs are internally consistent; flagged here only because a reader could misread C4 to think the action POST takes `newDocTypeId` directly. Adding a one-line note in C5 §3.2 ("the `Resolve` action body carries no fields; retype with type change goes through `POST /review/retype` instead") would close the gap.
- **`DocumentEventBus` (PascalCase)** — used consistently as the type name; lowercase `documentEventBus` appears only as a parameter / field name (which is correct Java convention).

---

## What's working well

For balance, three things the seven authors got right that are easy to miss:

1. **No "Production Considerations" sections snuck in.** Only C5 has one (the soft-cap bullet, explicitly authorized). C7's Production-Considerations content lives inside the README spec for C7-R9, where 03-components.md placed it. The HANDOFF.md "no unprompted prod caveats" guidance was respected.
2. **The retype lifecycle is described identically in C3, C4, C5, and C6** — `reextractionStatus` cycles `NONE → IN_PROGRESS → NONE`/`FAILED`, communicated to the client via `DocumentStateChanged` with no separate retype-specific events. This is the trickiest cross-spec contract in the system and all four specs are aligned.
3. **C4's property-test design respects C4-R9a** — engine takes `WorkflowCatalog` by constructor, no Spring context in the property suite. This is the right answer to the jqwik-spring boot-4-mismatch risk that research §7 surfaced.

---

# Change Spec Review — Round 2

## Summary
- Specs reviewed: 7 (`c1-config`, `c2-ingestion`, `c3-pipeline`, `c4-workflow`, `c5-api`, `c6-frontend`, `c7-platform`)
- Verdict: **accept-with-fixes** (one small documentation fix; ready to advance to Pass 6 immediately after)
- Blocking issues remaining: 0
- New issues introduced: 0

The Round 1 fix subagents resolved every blocking issue. No new drift was introduced by the fixes. One small documentation polish (a non-blocking C7 §3 enumeration) and a minor cosmetic case-style note in C3's prose are the only outstanding items, neither of which gates Pass 6.

## Round 1 blocking issue verification

1. **Migration ordering — RESOLVED.** No standalone `V2__*.sql`, `V3__*.sql`, or `V4__*.sql` files referenced anywhere. C1 §4.3, C2 §4.2, C3 §4 (modifications), and C4 §4.2 each explicitly state they "contribute SQL fragments to V1__init.sql" owned by C7. C7 §3.3 lists table creation order: client-data → `stored_documents` → `processing_documents` → `documents` / `workflow_instances` → `llm_call_audit`. C2's AC-R9 was rewritten to assert `flywayInfo` shows the single V1 baseline.
2. **Java root package — RESOLVED.** Zero `com.basata.docflow.*` references across all 7 specs (verified by grep). All packages now under `com.docflow.*`. C1 moved to `com.docflow.config.org.*`; C2 moved to `com.docflow.ingestion.*`.
3. **AppConfig single config reader — RESOLVED.** C1 §3.8 and §4.1 explicitly state `OrgConfigBootstrap` lives "inside `com.docflow.config` alongside `AppConfig` itself, not inside C1's `com.docflow.config.org.*` package tree" and shows the nested-record code block. C2 §3.4 and §4.1 state `AppConfig.Storage` is "a nested record on the C7-owned AppConfig" and "C2 does not declare its own `@ConfigurationProperties` binder". No `@ConfigurationProperties` annotation appears outside `com.docflow.config`. **Caveat:** C7's own spec text does not explicitly enumerate the nested-record set (`Llm`, `Storage`, `Database`, `OrgConfigBootstrap`); it only mentions `AppConfig.llm.modelId` in passing. The contributing specs (C1 §3.8 code block, C2 §3.4) carry the canonical structure. This is a documentation gap, not a contradiction — the contracts agree on placement.
4. **Error taxonomy — RESOLVED.** C5-R9a explicitly enumerates 11 codes (`UNKNOWN_ORGANIZATION`, `UNKNOWN_DOCUMENT`, `UNKNOWN_PROCESSING_DOCUMENT`, `UNKNOWN_DOC_TYPE`, `UNSUPPORTED_MEDIA_TYPE`, `INVALID_FILE`, `VALIDATION_FAILED`, `INVALID_ACTION`, `REEXTRACTION_IN_PROGRESS`, `LLM_UNAVAILABLE`, `INTERNAL_ERROR`) and notes the "8-code" label is a misnomer. C2 uses only `UNKNOWN_ORGANIZATION`, `UNSUPPORTED_MEDIA_TYPE`, `INVALID_FILE`, `INTERNAL_ERROR` (canonical). C6 dropped `STALE_VERSION` / `INVALID_TRANSITION`; uses `500 INTERNAL_ERROR` for stale-edit collisions and `400 VALIDATION_FAILED` for body-validation. Zero non-canonical codes anywhere across the 7 specs (verified by grep).
5. **Ingestion return shape — RESOLVED.** C2 §1 declares `IngestionResult { storedDocumentId: UUID, processingDocumentId: UUID }`; §3.1 step 8 returns it; AC-R1 asserts both UUIDs in the response. C5 §3.2 upload response body and §4 acceptance criteria both consume `{storedDocumentId, processingDocumentId}`.
6. **forbidden-strings.txt ownership — RESOLVED.** C7 §3.6 explicitly says "The literal pattern list is **not** inlined in `build.gradle.kts`. It lives in `config/forbidden-strings.txt`, a plain-text file owned by C1." The Gradle task reads the file at execution time, with comment + blank-line stripping, and fails fast on missing file. C7 adds `GrepForbiddenStringsFileTest` to verify the fail-fast behavior. C1 §3.6 / §4.5 commit `config/forbidden-strings.txt` as canonical.
7. **`WorkflowEngine.beginRetype` removed — RESOLVED.** Zero references to `beginRetype` anywhere in the 7 specs. C5 §3.2 retype description: "Calls `WorkflowEngine.applyAction(documentId, Resolve, { newDocTypeId })` (the canonical engine surface from C4 §C4-R1)". C4's `WorkflowAction.Resolve(UUID newDocTypeId)` matches.

## New issues found

None blocking. Two cosmetic notes:

- **C3 §3.5 prose uses lowercase `text` / `pdf`** in `inputModality ∈ {text, pdf}`, while C1 §3.2 declares `enum InputModality { TEXT, PDF }` (uppercase, canonical). The C1 enum is authoritative; C3's lowercase is descriptive prose, not a contract claim. Cosmetic only.
- **C4-R6 row in §1 requirements table parenthetically labels `LlmExtractor.extract(...)` "(asynchronous)"** while C4 §2 / §3.5 / §3.6 explicitly describe it as a synchronous Java call (with C3 publishing events asynchronously). The parenthetical refers to the overall retype flow, not the method semantics. Slightly imprecise wording; not a contradiction.

## Non-blocking item status

- **Issue 9 (`StoredDocumentIngested` event name resolution).** RESOLVED. C3 §3.9 declares `StoredDocumentIngested { storedDocumentId, organizationId, processingDocumentId, at }` as canonical and confirms C2's publisher line. C2 §3.1 step 7 publishes the same name.
- **Issue 10 (`@Async` wording on `LlmExtractor.extract`).** RESOLVED. C4 §2, §3.5 step 4, and §3.6 (DI surface) all state `LlmExtractor.extract` is invoked synchronously and returns quickly because C3 publishes events from inside a virtual-thread LLM-client wrapper. C3 spec does not annotate the method `@Async`. The C4-R6 row's "(asynchronous)" parenthetical is the only residual fuzziness.
- **Issue 11 (Optimistic concurrency / `STALE_VERSION`).** RESOLVED via Option (a). C6 §3.5 / §7 explicitly map stale-edit collisions to `500 INTERNAL_ERROR`: "Stale-edit collisions surface as C5's generic 500 INTERNAL_ERROR (no dedicated code; C4 maps optimistic-lock overflow to the catch-all per the cross-spec resolution)." No `STALE_VERSION` code anywhere.
- **Issue 12 (`OrganizationCatalog.exists` API name).** RESOLVED. C2 §3.1 step 1 and §3.8 now call `OrganizationCatalog.getOrganization(orgId)` and reject on empty `Optional`; no `exists` method invented.
- **Issue 15 (`SyntheticFourthOrgTest` overlap).** NOT RESOLVED — but trivial. C7 spec did not change its test list; C7 still has no `SyntheticFourthOrgTest` (it was never added) and C1 spec has `FourthOrgSeederTest` for C1-R9. The overlap concern was hypothetical; no action needed.
- **Issue 16 (`inputModality` flag on C1 schema).** RESOLVED. C1 §3.2 adds `enum InputModality { TEXT, PDF }` and `@NotNull InputModality inputModality` field on `DocTypeDefinition`. C1 §4.4 sets `inputModality: PDF` for the four nested-array doc-types and `TEXT` (default) for the other five. AC-L6 verifies the values. C3 §3.5 references the field by name.

## Outstanding follow-ups

- **(Skip) 03-components.md C5-R9a "8-code" → "11-code" wording.** Verified — the 03-components.md C5-R9a row enumerates the 11 codes inline and never uses an "8-code" label. The "8-code" misnomer existed only in older drafts and is acknowledged only in the C5 spec's reflective note. No edit to 03-components.md is required.
- **(Optional, non-blocking) C7 §3 / §4 should explicitly enumerate the `AppConfig` nested-record set.** Currently only C1 §3.8 carries the canonical structure (`Llm`, `Storage`, `Database`, `OrgConfigBootstrap`). The integration pass (Pass 6) is the natural place to lift this into C7 since C7 owns the host file `AppConfig.java`. Not a blocker.
- **(Optional, cosmetic) Lowercase `text` / `pdf` prose in C3 §3.5.** Should match C1's uppercase enum values when discussed as enum members. Trivial.
- **(Optional, cosmetic) C4-R6 row's "(asynchronous)" parenthetical.** Could be sharpened to "(synchronous call; C3 emits ExtractionCompleted/ExtractionFailed asynchronously)" to remove all residual ambiguity. Trivial.

Recommendation: **advance to Pass 6 (Integration)** without another fix round. The remaining items are trivial or out-of-scope; Pass 6 is the right venue for the C7 nested-record enumeration polish.
