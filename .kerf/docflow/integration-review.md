# Integration Review — Round 1

## Summary
- Artifacts reviewed: `SPEC.md`, `06-integration.md` (+ all 7 specs in `05-specs/`, `01-problem-space.md`, `03-components.md`, `change-spec-review.md`, `CLAUDE.md`).
- Verdict: **accept** (clean — advance to Pass 7).
- Blocking issues: 0
- Non-blocking issues: 2

The integration writing is faithful to the upstream artifacts. No requirements added, no decisions changed. The two flagged "potential inconsistencies" are both non-issues on close reading — see §"Two flagged inconsistencies" below. All Round-2 cosmetic carry-overs are resolved or properly deferred. No net-new contradictions surfaced.

---

## Success-criterion traceability

The 26 success criteria in `01-problem-space.md` §"Success criteria" all trace to a component and a spec section. SPEC.md §1 condenses them, and `03-components.md` C{N}-R{n} requirements pick them up:

| Success criterion (1-page numbering) | Owner(s) | Spec anchor |
|---|---|---|
| 1. `docker-compose up` (now `make build && make start`) brings up full stack | C7 | `c7-platform-spec.md` §3.2 / C7-R1 |
| 2. Seeded sample documents present on first launch | C7 | `c7-platform-spec.md` §3.7 / C7-R4 |
| 3. Pick any of three clients → dashboard | C5 + C6 | `c5-api-spec.md` C5-R1 / `c6-frontend-spec.md` C6-R1 |
| 4. Upload PDF → Classify → Extract → Review via SSE | C2, C3, C5, C6 | All four specs; SPEC.md §6.1 walkthrough |
| 5. Real Anthropic API calls; offline = observable failure | C3 | `c3-pipeline-spec.md` §3.5 / C3-R1, R9 |
| 6. From Review: edit fields, change type, approve, reject | C4, C5, C6 | C4-R3 / C5-R7 / C6-R8 |
| 7. From approval: approve or flag-with-comment | C4, C5, C6 | C4-R5 / C5-R6 / C6-R12 |
| 8. Flagged → Resolve returns to origin (or re-extracts on type change) | C4 | C4-R6 + C4-R9b |
| 9. Filed/Rejected: read-only detail view | C6 | C6-R8 |
| 10. Lien Waiver `unconditional` skips PM Approval | C1, C4 | C1-R4 + C4-R3 (test case) |
| 11. Each client's doc-type renders correct fields | C1, C6 | C1-R2 / C6-R10, C6-R13 |
| 12. Each (client, doc-type) follows correct workflow | C1, C4 | C1-R3 / C4-R9b |
| 13. Filter dropdowns reflect selected org | C5, C6 | C5-R3 / C6-R2 |
| 13a. Workflows declarative; grep-no-stage-strings | C1, C7 | C1-R7 / C7-R5 (`grepForbiddenStrings`) |
| 14. Backend coverage (≥70% line per JaCoCo, scope-cut from 95%) | C7 | C7-R6 / SPEC.md §7 |
| 15. Property-based tests for engine | C4 | C4-R9 / C4-R9a |
| 16. Integration tests at LLM HTTP seam | C3, C5 | `HappyPathSmokeTest` (06-integration §6.2) |
| 17. Minimal E2E happy path | C6 | C6-R14 / `make e2e` |
| 17a. Frontend component tests | C6 | C6-R13 |
| 18. README explains run + design decisions | C7 | C7-R9 |
| 19. AGENTS.md / CLAUDE.md symlink | C7 (project-level) | `CLAUDE.md` (already in place) |
| 20. Build fails on lint/format/static | C7 | C7-R5, C7-R6 |
| 21. Stop hook enforces fast suite | C7 | C7-R7 / C7-R14 |
| 22. 3NF schema, FKs, indexes, Flyway | C7 + per-context owners | C7-R3; per-spec schema fragments |
| 23–26. LLM eval harness | C3 | C3-R7..R9; on-demand `make eval` |

**No criteria fail to trace.** Two are scope-cut from the original wording (95% coverage → 70% / single labeled set instead of tune-holdout split); both are documented as scope cuts in `SPEC.md` §1, `06-integration.md`, and the relevant specs, so the trace is to a deliberate-deviation decision, not a gap.

---

## Interface consistency check

| Dimension | Verdict |
|---|---|
| Dependency graph matches per-spec contracts | **agree** (with minor convention note below) |
| Initialization order respects `@DependsOn` | **agree** |
| Canonical event names | **agree** (all 5/6 events match across C2/C3/C4/C5/06-integration/SPEC) |
| `llm_call_audit` FK shape | **agree** (`stored_document_id` always; `processing_document_id` ⊕ `document_id`; CHECK enforced — identical in C3 §3.7, C7 §3.3, 06-integration §3.1) |
| Retype call boundary | **agree** (C4 → C3 sync call; C3 emits `ExtractionCompleted`/`ExtractionFailed` async — phrased identically in C3, C4, C5, C6, 06-integration §3.3 / §4.3) |
| Error taxonomy (11 codes) | **agree** (zero `STALE_VERSION` / `INVALID_TRANSITION` / `INVALID_ORG` references anywhere; verified by grep) |
| Java root package | **agree** (zero `com.basata.docflow.*` references; all `com.docflow.*`) |
| Single `V1__init.sql` migration | **agree** (no `V2__org_config_tables.sql`, `V2__stored_documents.sql`, `V4__c4_*.sql` references; `V2__*` mentioned only as "future additive" guidance) |

**Convention note on the dependency graph (non-blocking).** The 06-integration §1 edge-contracts table uses "From → To" to mean "interface provider → consumer" (e.g., `C2 → C5` for `StoredDocumentIngestionService.upload`), which inverts the usual "depends on" arrow direction (C5 actually calls into C2, so C5 → C2 in dependency terms). The ASCII graph at the top of §1 is consistent with the table's reading. This is internally consistent but a reader expecting "depends on" arrows could mis-read the table. Not worth a fix — both readings reach the same wiring.

---

## Round 2 cosmetic carry-overs (verification)

- **C3 §3.5 lowercase `text`/`pdf` → `TEXT`/`PDF`:** **RESOLVED.** `c3-pipeline-spec.md` §3.5 (line 151) now reads `inputModality ∈ {TEXT, PDF}` with the canonical uppercase enum values matching C1's `enum InputModality { TEXT, PDF }`.
- **C4-R6 row "(asynchronous)" parenthetical:** **RESOLVED.** `c4-workflow-spec.md` line 20 now reads "(synchronous call; C3 emits `ExtractionCompleted`/`ExtractionFailed` asynchronously)".
- **C7 `AppConfig` nested-record enumeration:** **RESOLVED.** `c7-platform-spec.md` line 222 (Files-and-changes table) now enumerates all four nested records — `AppConfig.Llm` (modelId, apiKey, requestTimeout, eval.reportPath), `AppConfig.Storage` (storageRoot), `AppConfig.OrgConfigBootstrap` (seedOnBoot, seedResourcePath). 06-integration §3.2 carries the canonical Java code block. (The fourth nested record `Database` appears in the 06-integration code block but not in the C7 §"Files and changes" line; the latter mentions Llm/Storage/OrgConfigBootstrap explicitly. Trivial omission — `Database` is listed elsewhere in C7 spec; no contradiction.)
- **`03-components.md` C5-R9a "8-code" wording:** **STATUS — never existed in 03-components.md.** Round-2 already verified the 03-components.md C5-R9a row enumerates 11 codes inline and never used an "8-code" label. The "8-code" misnomer lives only in `c5-api-spec.md` §C5-R9a row (line 18) which itself acknowledges it as a misnomer carried over from earlier drafts. SPEC.md §10 #1 perpetuates the misnomer in describing the issue ("8-code prose typo") rather than just calling it a documentation-cleanup item — minor wording but harmless. No action.

---

## SPEC.md assembly fidelity

SPEC.md does **not** add requirements or change decisions. Spot checks:

- §1 condensed success criteria — every bullet maps cleanly to a Pass-1 success criterion (with two scope cuts annotated).
- §3 canonical vocabulary — entity / status / engine / event tables match `03-components.md` and per-spec definitions exactly. Tokens (`StoredDocument`, `ProcessingDocument`, `Document`, `WorkflowInstance`, `WorkflowStatus`, `canonicalStatus`, `currentStep`, `reextractionStatus`, `StageGuard`, `WorkflowEngine`, `DocumentEventBus`, `AppConfig`, `grepForbiddenStrings`, the three catalogs, the two read projections) are used identically.
- §4 tech stack hedges Spring Boot version as "(latest GA on the analysis branch)" — see flagged inconsistency below; the hedge is correct, not a change of decision.
- §5 component summaries are all "two-sentence summary + pointer + 1–3 key decisions" — pointers are accurate, no new decisions introduced.
- §6 integration & data flows is a 30-second redirect; full version remains in `06-integration.md`.
- §8 out-of-scope list matches `HANDOFF.md` and the per-spec scope cuts.
- §10 follow-ups for Pass 7 is a faithful collation of the deferred items from `06-integration.md` §7 and `change-spec-review.md` Round 2.

**No fidelity failures.**

---

## Two flagged inconsistencies (resolution)

### Spring Boot version naming — **hedge is correct, not real drift**

- `01-problem-space.md` line 39 says "Spring Boot 4 (match the reference stack exactly — these are recent GA releases)" — written when Spring Boot 4 was the planned target.
- `06-integration.md` §2 step 9 says "HTTP listener (Tomcat 11 / Spring MVC) opens" — Tomcat 11 is the embedded servlet container in Spring Boot 4.x; specifying Tomcat 11 is consistent with Spring Boot 4.
- `SPEC.md` §4 hedges with "Spring Boot (latest GA on the analysis branch)".
- `SPEC.md` §10 #3 says "**`jqwik-spring` Spring Boot 4 support not yet confirmed upstream**" — explicitly Spring Boot 4.
- `06-integration.md` §7 #2 also explicitly says Spring Boot 4.

Each artifact is internally consistent. The intended target is Spring Boot 4; SPEC.md's hedge is appropriate because this is take-home documentation written against a moving target ("latest GA"). Tomcat 11 in 06-integration is the embedded container that ships with Spring Boot 4 — that's a concrete pin, not drift. **No action; SPEC.md's hedge is the right level of precision for §4 (tech stack overview).**

### Stop hook timeout — **600s is a generous outer bound, not drift**

- `c7-platform-spec.md` §3.4 sets the Stop hook timeout to **600s** (10 minutes).
- `c7-platform-spec.md` line 39 notes "full check can creep past **60–90s** on slow machines" (research §8).
- `06-integration.md` §7 #3 references the 60–90s research observation and says to split the gate "if `make test` consistently exceeds the **600s** Stop hook timeout".

These are **not** in conflict. 60–90s is the *expected* runtime on a fresh machine; 600s is the *timeout ceiling* before the hook reports failure. The research caveat is "if it creeps past 60–90s" (i.e., gets noticeably slower than baseline); the 600s ceiling exists so a one-off slow run isn't reported as failure. The C7 spec is clear: 600s is the timeout, splitting is the mitigation **only if** runtime regularly approaches the ceiling. **No action.**

---

## Blocking issues

None.

---

## Non-blocking issues

1. **SPEC.md §10 #1 perpetuates "8-code" wording.** The bullet describes the issue as "C5-R9a '8-code' prose typo" but the actual phrasing in `03-components.md` never uses "8-code" — only `c5-api-spec.md` §C5-R9a's reflective note does, and that note already acknowledges the misnomer. Pass 7 should either drop SPEC.md §10 #1 entirely or restate it as "C5 spec's reflective note about an '8-code' label is harmless given the row enumerates 11 codes inline." Pure documentation cleanup; no implementation impact.

2. **C7 §"Files and changes" line for `AppConfig.java` omits the `Database` nested record.** `c7-platform-spec.md` line 222 names `Llm`, `Storage`, `OrgConfigBootstrap` but skips `Database`. The full canonical structure including `Database` is in `06-integration.md` §3.2's Java code block, which is the canonical enumeration for Pass 6. Implementation should follow the 06-integration §3.2 structure. Trivial.

---

## Outstanding follow-ups for Pass 7

The four items SPEC.md §10 carries forward (verified consistent with `06-integration.md` §7):

1. **`03-components.md` C5-R9a "8-code" wording (revisit per non-blocking item 1 above).** Either drop or rephrase. No action required.
2. **Stop hook cadence on slow machines.** Measure `make test` during implementation; split gate if it approaches 600s.
3. **`jqwik-spring` Spring Boot 4 support.** Confirm at implementation time; mitigation already in place via `WorkflowEngine` constructor injection.
4. **`ProcessingDocument` cleanup cron.** Acknowledged out-of-scope; documented in README per C7-R9.
5. **Cosmetic spec polish** (lowercase `text`/`pdf`, "(asynchronous)" parenthetical). Both **already RESOLVED** in the spec text — SPEC.md §10 #5's "carried from Round-2 review" framing is a touch stale; the items are now historical, not outstanding. Pass 7 can drop them from the active list.

**Recommendation:** advance to Pass 7 (Tasks). The two non-blocking items above are documentation polish and can be cleaned up during Pass 7 if the author wishes, but they do not block decomposition into implementation tasks.
