<!-- PP-TRIAL:v1 -->
# Session Handoff

> Generated 2026-04-27 by /session-handoff. Read by /session-resume.

## Intent (forward-stated)

**Purpose.** Continue the kerf-driven planning of DocFlow (a multi-client document processing take-home), so that Pass 5 (Change Spec) can begin from a fully user-aligned component decomposition.

**Key Tasks.**
1. Walk the user through **C4 — Workflow Engine**, focusing on DB interaction and interfaces (the user's stated review priorities). Surface assumptions made.
2. Walk through **C5 — API & Real-Time** with the same focus areas.
3. Walk through **C6 — Frontend (React SPA)**.
4. Walk through **C7 — Platform & Quality**.
5. Advance kerf status from `research` to `change-spec` and start Pass 5.

**End State.** All seven components walked with the user, any further corrections folded into `03-components.md` and the research findings, and Pass 5 (Change Spec) under way with per-component spec drafts being authored.

## Autonomy Scope (for next session)

**Decide autonomously:**
- Walkthrough format, ordering of sub-points within a component, length of explanations
- Spawning subagents to apply edits across files
- Wording/phrasing of requirements as long as semantics match what's already agreed
- Test naming, package layout suggestions, internal class-name choices
- Adding non-blocking refinements that the user clearly already wants
- Capturing user decisions back into `03-components.md` and research findings files

**Ask first:**
- Any change that contradicts a Decision Made This Session
- Domain-model changes (entity splits, new bounded contexts, schema reshapes)
- Adding scope (new features, new clients, new components)
- Architectural reversals (going back from event-driven to direct calls, etc.)
- Anything that touches the user-confirmed CTO-facing DDD/micro-lith framing
- Advancing kerf status (`kerf status docflow change-spec` and onward) — the user has been the gate on each pass advance so far
- Picking implementation choices that the research findings explicitly left open without recommendation

If unsure, default to ask.

## Decisions Made This Session

1. Document lifecycle uses a 3-entity split: `StoredDocument` (C2, file reference only) → `ProcessingDocument` (C3, transient pipeline state, deleted on success) → `Document` (C4, processed/workflow-ready). `WorkflowInstance` is 1:1 with `Document`.
2. Workflow stages list drops Upload/Classify/Extract. Workflows start at Review. Stage `kind` enum is `{review, approval, terminal}`.
3. Canonical `WorkflowStatus` enum is the domain vocabulary for filtering/stats: `{AWAITING_REVIEW, FLAGGED, AWAITING_APPROVAL, FILED, REJECTED}`. `FLAGGED` is a runtime override (review-kind stage with origin set), never declared on a stage.
4. `ProcessingDocument.currentStep` is a separate enum: `{TEXT_EXTRACTING, CLASSIFYING, EXTRACTING, FAILED}`. Distinct concept from WorkflowStatus.
5. Entity columns use enums, never booleans. `Document.reextractionStatus ∈ {NONE, IN_PROGRESS, FAILED}` replaced an earlier `isReextracting: boolean`.
6. `document_classifications` and `document_extractions` tables do not exist. Current values live on `Document` (C4-owned); history lives in `llm_call_audit`.
7. One SSE stream per org carries typed events: `ProcessingStepChanged`, `ProcessingFailed`, `ProcessingCompleted`, `DocumentStateChanged`, `DocumentReextractionStarted/Completed/Failed`.
8. Pipeline lifecycle is event-driven cross-context: C3 emits `ProcessingCompleted`; C4 creates Document + WorkflowInstance transactionally.
9. Re-extraction (user retype in Review) does NOT go through `ProcessingDocument`. C4 invokes `LlmExtractor.extract(documentId, newDocType)` directly; updates `Document` on `ExtractionCompleted`.
10. Seeded samples skip `ProcessingDocument` — inserted as `StoredDocument` + `Document` + `WorkflowInstance` directly.
11. Client/user data is seeded from YAML fixtures in `src/main/resources/seed/` only when reference tables are empty. Subsequent startups read from DB. README documents this for ease of setup.
12. Service config (env vars, API keys) loaded ONCE at startup into a typed `AppConfig` (Spring `@ConfigurationProperties` + Jakarta Validation). No runtime `System.getenv`/`@Value` outside the binder.
13. Model: `claude-sonnet-4-6`, sourced from `AppConfig.llm.modelId`.
14. Stack: Java 25, Spring Boot 4 (Jackson 3, Jakarta EE 11), React 19, Vite 8, PostgreSQL 17/18, Flyway, Anthropic Java SDK.
15. LLM input modality: hybrid, per-doc-type. Text for classify; PDF for extract on doc-types with nested arrays; eval confirms per-type.
16. Prompt management: resource files at `src/main/resources/prompts/<id>/v<N>.txt`. `llm_call_audit` records `prompt_identifier + version+sha256-short`.
17. `organizationId` denormalized onto every tenant-scoped table. Writers keep it consistent with `Document.organizationId`. Tested by unit test, no triggers.
18. Frontend filter dropdown uses canonical `WorkflowStatus` only. No per-org-stage filter parameter on the API.
19. CTO reviewer cares about DDD/micro-lith — bounded-context separation must be visible at the package and table-ownership level.
20. `AGENTS.md` is canonical; `CLAUDE.md` is a symlink. Stop hook in `.claude/settings.json` runs `./gradlew check && npm --prefix frontend run check` before allowing "done."

## Decisions Parked

- **[watch]** `llm_call_audit` FK shape — subagent chose three FKs (always-populated `stored_document_id`; mutually-exclusive `processing_document_id` and `document_id`). Pass 5 should confirm this is right when writing the migration.
- **[watch]** `ProcessingDocument` deletion timing — flagged as implementation-time call (in C4's `ProcessingCompleted`-handler transaction, vs. C3 deletes on observing `DocumentStateChanged`). Both work.
- **[routine]** Frontend coverage threshold currently `0` placeholder in `vitest.config.ts`. Research recommends 70% line / 60% branch; Pass 5 change-spec should land that value.
- **[routine]** `claude-opus-4-7` model availability noted but the user pinned `claude-sonnet-4-6`. Re-pin live before project start.
- **[routine]** Sub-package layout for C3 (was `classify/` + `extract/`; now `pipeline/`). Pass 5 confirms the package structure when writing change-spec.
- **[watch]** Property-based testing library `jqwik-spring` may not yet target Spring Boot 4. Mitigation in place (C4 engine is DI-free in tests). Confirm at implementation time.

## Open Questions

1. Does the user agree with the C4 walkthrough framing as written below in "What the Next Session Should Start With"? The C3 walkthrough was the trigger for the big architectural refactor — does C4 require a similar deep-dive, or has the refactor already absorbed C4's main concerns?
2. Should the next session run `kerf shelve docflow` after C7 walkthrough completes, or wait until Pass 5 (Change Spec) is also under way before any kerf-side state preservation?
3. The two parked items tagged **[watch]** under "Decisions Parked" — does the user want them resolved during the C4-C7 walkthroughs, or deferred to Pass 5 (Change Spec) where they have more concrete context?

## Load-Bearing Tokens

- `kerf` (the spec-writing CLI; commands: `kerf new`, `kerf status`, `kerf shelve`, `kerf resume`, `kerf square`, `kerf finalize`)
- `StoredDocument` (C2 entity — file reference only)
- `ProcessingDocument` (C3 entity — transient pipeline state)
- `Document` (C4 entity — processed, workflow-ready)
- `WorkflowInstance` (C4 entity, 1:1 with Document)
- `WorkflowStatus` (canonical enum)
- `canonicalStatus` (per-stage config field)
- `AWAITING_REVIEW`, `FLAGGED`, `AWAITING_APPROVAL`, `FILED`, `REJECTED`
- `currentStep` (ProcessingDocument's separate enum)
- `TEXT_EXTRACTING`, `CLASSIFYING`, `EXTRACTING`, `FAILED`
- `reextractionStatus` (`NONE` / `IN_PROGRESS` / `FAILED`)
- `llm_call_audit`
- `DocumentEventBus`
- `ProcessingCompleted` (the cross-context event that triggers Document materialization)
- `StageGuard` (the predicate-over-extractedFields concept)
- `micro-lith` (the CTO's stated architectural style)
- `AppConfig` (typed startup config object)
- `claude-sonnet-4-6`
- Pass 1 Problem Space / Pass 2 Analyze / Pass 3 Decompose / Pass 4 Research / Pass 5 Change Spec / Pass 6 Integration / Pass 7 Tasks / Pass 8 Ready
- `03-components.md`
- `problem-statement/` (read-only source of truth — the original spec PDF + samples + mockups)
- `DocumentView` (the cross-context read-model projection)

## Out of Scope

- Authentication / users / role-based access (deliberate, documented in README Production Considerations).
- S3 / object storage — using local filesystem; seam isolated via `StoredDocumentStorage`.
- Pixel-perfect mockup parity.
- A 4th client beyond Riverside Bistro / Pinnacle Legal / Ironworks Construction.
- Hot-reload of client config (restart-only).
- Reopening Rejected documents / Filed reversal.
- Document deletion via API (no DELETE endpoint).
- Concurrent-edit semantics.
- Encrypted / password-protected PDF support.
- Document full-text search.
- Frontend test coverage at the backend's 95% bar (lower bar; specifics TBD in Pass 5 from research recommendation 70% line / 60% branch).
- Range-request streaming on `GET /api/documents/{id}/file`.
- `Last-Event-ID` SSE resume.
- Generic OpenAPI documentation (marked optional in C5-R10).

## What the Next Session Should Start With

Read `/Users/gb/github/basata/.kerf/project/docflow/03-components.md` end-to-end (it's been heavily refactored this session — the previous mental model of a single `Document` entity with workflow state attached is gone). Then read the C4 portion (search for "## C4. Workflow Engine") plus `/Users/gb/github/basata/.kerf/project/docflow/04-research/c4-c5-c7-backend-infra/findings.md`. Then present **C4 — Workflow Engine** to the user using the same walkthrough format used for C1 and C2: (1) one-paragraph role recap, (2) key entities + tables, (3) research decisions applied, (4) DB interaction (the user's focus area #1), (5) interfaces (the user's focus area #2), (6) assumptions to review, (7) questions for the user. Be especially explicit about which of the 3 entities C4 owns (`Document` + `WorkflowInstance` + `document_state_transitions`), how `currentStatus` is computed at write time from `currentStage.canonicalStatus` plus the flag override, how the `ProcessingCompleted` event triggers Document materialization, and how the retype flow uses `reextractionStatus`. Do not advance kerf status without explicit user approval. Suggest the user run `/session-resume` if they haven't already.
