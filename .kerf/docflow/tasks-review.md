# Pass 7 Tasks Review — Round 1

## Summary

The task list is comprehensive, coherent, and well-aligned with the assembled SPEC, the integration plan, and the seven Pass-5 component specs. Coverage tables are accurate where I spot-checked; the dependency DAG is acyclic; integration tasks live in their natural owning components and are correctly ordered. Sizing is generally on point. Verdict: **accept-with-fixes** — 0 blocking, 5 should-fix, 4 nits. The should-fix items are mostly missing/inaccurate AC mappings in the §5.2 coverage tables and a contract mismatch between C6.10's spec source and C5's actual flag endpoint.

## Findings

### 1. C6.10 deliverable points at a flag endpoint that doesn't exist [Should-fix]
**Where:** `c6-frontend-spec.md` AC9.3 (and by transitivity `07-tasks.md` C6.10 referencing it) vs. `c5-api-spec.md` §3.2.
**Issue:** C6 AC9.3 says "POST `/api/documents/{id}/{stageId}/flag` is fired with the comment in the body". C5's spec defines no such endpoint — Flag is a variant on `POST /api/documents/{id}/actions` with `{ "action": "Flag", "comment": ... }`. The C5 controller (C5.6) only routes `/actions`, `/review/fields`, and `/review/retype`. The C6.10 task carries the C6 AC verbatim and will fail integration if implemented literally. (This is a cross-spec drift inherited from Pass 5; Pass 7's job here is to surface it so the implementer doesn't burn a day building a non-existent endpoint.)
**Suggested fix:** In C6.10 (or C6.9 where `useDocumentActions` is wired) add a one-line note: "Flag is implemented via `POST /api/documents/{id}/actions` with `{ "action": "Flag", "comment": ... }` per C5 §3.2 — AC9.3's URL pattern is wrong; treat the `action` body endpoint as canonical." Add a cross-comp dep on C5.6 (already implicit, currently spelled out in C6.9). Consider also leaving a TODO in `tasks-review.md` to fix the C6 spec text in a Pass-5 cleanup.

### 2. C2 AC-OVERSIZE is missing from §5.2 coverage table [Should-fix]
**Where:** `07-tasks.md` §5.2 C2 table; `c2-ingestion-spec.md` AC-OVERSIZE.
**Issue:** The C2 spec lists 13 ACs (AC-R1..AC-R9a, AC-IMMUTABILITY, AC-EVENT, AC-ORG-VALIDATION, AC-CORRUPT-PDF, AC-OVERSIZE). The 07-tasks coverage table maps `AC-R1..AC-R9a` and the four named ACs but never names AC-OVERSIZE. The spec says it's "Spring's standard 413; we do not need a custom guard" — so no implementation, but it should still be acknowledged in the coverage table (or explicitly noted as "no task — Spring default") to keep the coverage check honest.
**Suggested fix:** Add a row `AC-OVERSIZE | (Spring default, no task)` to the C2 coverage table, or fold it into C2.4's AC list with a one-line note that the upper bound is asserted by Spring's `spring.servlet.multipart.max-file-size`.

### 3. C7.9 cross-comp dep list misses C7.4 V1 schema [Should-fix]
**Where:** `07-tasks.md` C7.9 (Seed loader).
**Issue:** C7.9 Within-component deps lists `C7.4, C7.7, C7.8`. Cross-comp lists `C1.7, C1.8, C2.1, C2.2, C4.2`. Within is correct, but for consistency with the §3 dep graph note ("every task that reads or writes a covered table thus carries C7.4 as a cross-comp dep"), C7.4 properly belongs in *cross-component* deps (it's owned by C7 too, but it's referenced as the V1 assembly). The §3 graph likewise shows `C7.4 + C7.8 → C7.9`, conflating with-in and cross. Cosmetic only.
**Suggested fix:** Move C7.4 from "Within-component deps" to be also referenced cross-comp, or just call it out once as "scaffold dep" since both are C7-owned. Same minor inconsistency exists for C7.10 (lists C7.5/C7.6 within-comp; correct since all C7).

### 4. C5 §5.2 coverage table conflates AC1..AC10 to a single row [Should-fix]
**Where:** `07-tasks.md` §5.2 C5 table.
**Issue:** The whole C5 table compresses `AC1..AC10 | C5.2..C5.7`. C5 spec ACs are sharply distinct (AC1 = orgs list, AC9 = SSE, AC10 = error contract). For implementer trace-back, mapping each AC to its task helps. Specifically AC1 → C5.3, AC2 → C5.4, AC3 → C5.5, AC4 → C5.4, AC5 → C5.4, AC6 → C5.6, AC7 → C5.6, AC8 → C5.6, AC9 → C5.7, AC10 → C5.2 (and C5.8 covers AC1+AC2+AC6 end-to-end). The condensed shape is technically true but not auditable.
**Suggested fix:** Expand the C5 §5.2 table to one row per AC, mapping each to the owning task (and the C5.8 smoke as an end-to-end touchpoint).

### 5. C3 §5.2 coverage table dual-tracks AC2 sub-items but skips one [Should-fix]
**Where:** `07-tasks.md` §5.2 C3 table.
**Issue:** The C3 spec's AC2 enumerates ten sub-bullets (PipelineTriggerListener, orchestrator happy/text/classify/extract failure, retype reextraction-status cycle, ToolSchemaBuilder, LlmCallAuditWriter, PromptLibrary, EvalScorer, organization_id denormalization). The 07-tasks table breaks AC2 into seven sub-rows (good idea) but misses **"Orchestrator on text-extract failure"**, **"Orchestrator on classify failure"**, **"Orchestrator on extract failure"** — these are the three FAILED-path bullets in C3 spec §5 item 2. The implicit "AC2 trigger-listener / happy path / failures" row in the table folds these together but the wording leaves the three distinct FAILED-step assertions ambiguous.
**Suggested fix:** Replace `AC2 trigger-listener / happy path / failures | C3.9` with three explicit rows: `AC2 PipelineTriggerListener invocation | C3.9`, `AC2 orchestrator happy path | C3.9`, `AC2 orchestrator FAILED paths (text-extract / classify / extract) | C3.9`. Same task, but the AC isn't lost in a slash.

### 6. C4.10 within-comp dep is informal [Nit]
**Where:** `07-tasks.md` C4.10.
**Issue:** Lists "Within-component deps: all C4 production tasks." For a DAG it's better to enumerate the specific predecessor task IDs (C4.2, C4.4, C4.5, C4.6, C4.7) so the graph is closed. Cosmetic; the meaning is clear.
**Suggested fix:** Replace with `Within-component deps: {C4.2, C4.4, C4.5, C4.6, C4.7}` or note "all C4 production tasks (C4.2..C4.7)".

### 7. C3.1 fragment ordering description is ambiguous [Nit]
**Where:** `07-tasks.md` C3.1 (Cross-component deps).
**Issue:** C3.1 contributes both `processing_documents` AND `llm_call_audit`. The description says C2.3 and C4.3 must be ordered earlier in V1. That's only fully true for `llm_call_audit` (which FKs to `documents` from C4.3). `processing_documents` only needs C2.3 before it. The current note is correct in aggregate but conflates the two fragments. C7.4 ultimately stitches the order; this is just a descriptive nit.
**Suggested fix:** Clarify: "`processing_documents` orders after C2.3 (`stored_documents`); `llm_call_audit` orders after C4.3 (`documents`); both stitched by C7.4."

### 8. C6.12 cross-comp deps under-specifies seed dependency [Nit]
**Where:** `07-tasks.md` C6.12 (Playwright E2E).
**Issue:** Says "Full backend stack (C2/C3/C4/C5) running under `docker compose`; live or stubbed Anthropic per C7's compose config." The two scenarios assume seeded orgs (Pinnacle Invoice). C7.9 (seed loader) is a hard dep. Phase P9 ordering covers it but not explicitly named in the cross-comp list.
**Suggested fix:** Add `C7.9` (application-data seed loader) and `C1.7` (org config seeded) explicitly to C6.12's cross-comp deps.

### 9. P3 phase claim about parallelism is slightly overstated [Nit]
**Where:** `07-tasks.md` §2 phases table, P3 row.
**Issue:** P3 row says "Mostly yes (fragments parallel; assembly serial)." The four fragment tasks (C1.5, C2.3, C3.1, C4.3) are listed as parallelizable, but C2.3's repository class needs the entity created and `StoredDocumentEntity` hides under `internal/`; in practice C2.3 needs C2.1 (P2) done. That's already encoded as C2.3 within-comp dep on C2.1. Same for C1.5 → C1.1, etc. The phase note is correct in spirit but a reader might think P3 starts before P2 finishes; it doesn't (P3 fragments depend on P2 types).
**Suggested fix:** Tweak the P3 row to "Yes within P3 once P2 types land — fragments parallel after C1.1/C2.1/C3 entities/C4.1; assembly serial." Or just leave it; the per-task within-comp deps are explicit.

## Coverage spot-checks

End-to-end verified:

- **C1 ACs (AC-L1..L6, AC-V1..V9, AC-S1..S3, AC-C1..C6, AC-E1, AC-G1/G2, AC-AC1):** all enumerated in §5.2 C1 table; mapped to specific tasks. Verified against `c1-config-spec.md` §5.
- **C4 ACs (5 numbered ACs in c4-workflow-spec.md §5):** verified each maps to the right task in §5.2 C4 table. Coverage of AC1 (state transitions) split across C4.5/C4.7/C4.9 is correct given retype is in C4.7 and parameterized matrix in C4.9.
- **C7 ACs (1..10):** verified each is owned by the named task. AC4 (Flyway) → C7.4 ✓, AC5 (seed) → C7.9 ✓, AC7 (missing api key) → C7.3 ✓, AC8 (event bus) → C7.7 ✓, AC9 (CI) → C7.11 ✓, AC10 (e2e/eval) → C7.11/C7.12 ✓.
- **C6 ACs (AC1.1..AC9.3):** verified the §5.2 C6 row touches C6.1/C6.3/C6.4/C6.5/C6.7/C6.8/C6.9/C6.10 — every AC has at least one task entry.
- **SPEC.md §3 events row:** verified that all six concrete records have a producer task and consumers — C7.7 skeletons, C2.4 publishes `StoredDocumentIngested`, C3.9 publishes `ProcessingStepChanged`/`ProcessingCompleted`, C3.8 publishes `Extraction*`, C4.5/C4.6/C4.7 publish `DocumentStateChanged`. Matrix matches integration §3.3.

Trusted (not re-verified line-by-line):

- **C2 AC mapping** — only spot-checked AC-R4/R5/R6/R7 against tasks. Found AC-OVERSIZE gap noted above.
- **C3 AC mapping** — verified the AC2 sub-bullets except as noted in finding #5.
- **C5 AC mapping** — folded together; flagged in finding #4.

## DAG analysis

**No cycles.** Walked the cross-component edges:

- C2 → C3: C2.4 publishes `StoredDocumentIngested`, consumed by C3.9. C3.9 deps on C2.4. ✓ no return edge.
- C3 ↔ C4 split: C3.9 publishes `ProcessingCompleted`, C3.8 publishes `Extraction*`; C4.6/C4.7 listen. C4.5 sync-calls C3.8's `LlmExtractor.extract`. C3.8 depends on C4.2 (Document entity) — that's a *type* dep, not a behavior dep, and C4.5 → C3.8 is the behavior dep, so the cycle C3.8 → C4.2 → ... → C4.5 → C3.8 is broken because C4.5 uses C3.8 only at runtime via constructor injection; and C3.8's compile dep on C4.2 is the entity record + writer. The integration plan §6.2 #2 (stubbed `LlmExtractor` in `RetypeFlowIT`) is the operational manifestation. ✓ acyclic.
- C5 → C2/C3/C4: C5 controllers consume C2.4 (upload), C4.5 (engine), C4.2 (DocumentReader), no return edges to C5. ✓
- C7 ↔ all: C7.4 stitches fragments from C1.5/C2.3/C3.1/C4.3 — those four tasks ship fragments; C7.4 assembles. The fragments don't depend on C7.4 *for their own deliverable*, but every DB-bound consumer depends on C7.4. The 07-tasks.md description of this co-ownership pattern is coherent. ✓
- C7.7 (event bus + record skeletons) → all event-publishing/consuming tasks. The "skeleton records compile against a stable contract" decision in C7.7 lets producers/listeners ship in any order after C7.7. ✓

**Suspicious deps verified OK:**

- C5.7 SSE listens to events from C3.9 (ProcessingStepChanged) and C4.5/C4.6/C4.7 (DocumentStateChanged). C5.7's cross-comp deps name them all. Phase P7 sits after P6 (C3/C4 done). ✓
- C5.8 HappyPathSmokeTest depends on the full backend stack including seeded org. Cross-comp lists C1.7 explicitly (good). ✓
- C6.12 Playwright E2E — see finding #8; C7.9/C1.7 implicit not explicit.

**Integration tasks verified present and correctly ordered:**

- C3 → C4 handoff: `ProcessingCompletedListenerIT` in C4.6 — present, P6.
- C3 → C4 retype: `RetypeFlowIT` with stubbed `LlmExtractor` in C4.7 — present, P6, integration §6.2 #2 explicitly cited.
- HTTP-seam smoke: `HappyPathSmokeTest` in C5.8 — present, P9, ordered after C5.3..C5.7.
- App-data seed: `SeedManifestTest` in C7.9 — present, P6, ordered after C7.4/C7.7/C7.8/C1.7/C1.8.
- Frontend E2E: `make e2e` Playwright in C6.12 — present, P9.
- Live LLM eval: `make eval` `EvalRunner` in C3.11 — present, P9.
- Live API smoke: `PipelineSmokeIT` in C3.10 — present, P9.

All integration tasks accounted for, scoped correctly (smoke tests gated on `ANTHROPIC_API_KEY`; `RetypeFlowIT` uses stubbed extractor per integration plan), and ordered after their dependencies.

---

## Round 1 fixes applied (main thread)

All five should-fix items addressed, plus two nits. Remaining two nits left as cosmetic.

| # | Severity | Status | Resolution |
|---|---|---|---|
| 1 | Should-fix | **Fixed** | C6.10 cross-component deps now spell out that Flag is `POST /actions { action: "Flag", comment }` per C5 §3.2; `useDocumentActions` should route accordingly. C6 spec drift (AC9.3 wording) called out explicitly so the implementer doesn't build a non-existent endpoint. |
| 2 | Should-fix | **Fixed** | C2 §5.2 coverage table now includes `AC-OVERSIZE | (Spring default `spring.servlet.multipart.max-file-size` — no task)`. |
| 3 | Should-fix | Not applied | Cosmetic — both C7.4 and C7.9 are C7-owned; the within-/cross- distinction is internal-vs-external by component, not by ownership. The §3 dep graph already shows `C7.4 + C7.8 → C7.9` correctly. Leaving as-is to keep the convention consistent. |
| 4 | Should-fix | **Fixed** | C5 §5.2 coverage table expanded to one row per AC (AC1–AC10), each mapped to its owning task; C5.8 noted as the end-to-end touchpoint for AC1/AC2/AC6. |
| 5 | Should-fix | **Fixed** | C3 §5.2 coverage table now splits AC2's three FAILED-step assertions into separate rows (text-extract / classify / extract). |
| 6 | Nit | **Fixed** | C4.10 within-component deps enumerated explicitly: C4.2, C4.4, C4.5, C4.6, C4.7. |
| 7 | Nit | Not applied | Cosmetic — C3.1's cross-comp dep wording is true in aggregate; C7.4 stitches the actual order. |
| 8 | Nit | **Fixed** | C6.12 cross-comp deps now name C1.7 (org config seeded) and C7.9 (application-data seed loader) explicitly. |
| 9 | Nit | Not applied | Cosmetic — the per-task within-comp deps are explicit; phase-table is a guide, not a contract. |

No blocking issues remain. Task list ready to advance to `ready`.

**Sizing scrutiny on the 12 listed 2-day tasks:**

- C2.4 (Tika MIME + upload orchestrator + 5 ACs incl. fault injection) — genuine 2-day.
- C3.8 (LlmExtractor: two surfaces, retry, audit, concurrency guard) — genuine 2-day.
- C3.11 (EvalRunner + manifest + scorer + report writer + Gradle task + tests) — genuine 2-day; could push to ~2.5d if manifest ground-truth filling takes long.
- C4.5 (`WorkflowEngine.applyAction` covering 4 actions + retype sync call + tx + event) — genuine 2-day.
- C4.8 (jqwik property suite covering five properties + status-rule property) — genuine 2-day; first jqwik wiring can chew time.
- C5.6 (DocumentActionController + ReviewController, 3 endpoints, 4 union variants, validation) — genuine 2-day.
- C5.7 (SSE Registry + Publisher + Controller + integration test) — genuine 2-day.
- C6.4 (Dashboard skeleton + 11 components + tests) — genuine 2-day, near upper bound.
- C6.8 (FormPanel render-state machine across 5 branches + zod schema builder + readonly tables) — genuine 2-day, near upper bound.
- C6.9 (ReviewForm + FieldArrayTable + useDocumentActions covering 5 mutations + 3 client-schema tests) — genuine 2-day, near upper bound.
- C7.4 (V1 assembly only, with FlywayBaselineTest) — actually fits comfortably in 1 day per the task's own size; the "2-day" comment in §7 lists it but the task itself is sized 1d. Possibly a §7 wording error; either is defensible.
- C7.9 (seed loader + idempotency + integration test, runs against C1/C2/C4 tables) — genuine 2-day.

Two-day count is honest. No task struck me as oversized that should be split, and no half-day task struck me as trivial enough to fold.
