# DocFlow

DocFlow is a multi-client document-processing platform built against the take-home spec in `problem-statement/`. It ingests PDFs for three fictional clients — **Riverside Bistro** (restaurant), **Pinnacle Legal Group** (law firm), and **Ironworks Construction** (general contractor) — runs them through an LLM-driven classify-and-extract pipeline, and routes each document through a per-org review/approval workflow until it is filed or rejected. Stack: Java 25 + Spring Boot 4 + PostgreSQL on the backend, React + TypeScript (Vite) on the frontend, Anthropic (Claude Sonnet 4.6) for both classification and field extraction, PDFBox 3 for text extraction.

## How to run

```
cp .env.example .env          # then fill in ANTHROPIC_API_KEY
make build
make start                    # alias: docker-compose up
```

- Dashboard: <http://localhost:5174>
- API base: <http://localhost:8551>
- Required env: `ANTHROPIC_API_KEY` (see `.env.example` for the full list — DB creds, storage root, host port overrides). The seeded dashboard works without a key — only live uploads and `make eval` require it. A reviewer who just wants to click around the UI against the labeled samples can leave it unset.

Common targets:

| Command | Action |
|---|---|
| `make build` | Build backend + frontend images. |
| `make start` (alias `up`) | Start backend, frontend, Postgres. |
| `make stop` (alias `down`) | Stop the stack. |
| `make test` | Fast gate: `./gradlew check` then `npm --prefix frontend run check`. |
| `make e2e` | Playwright scenarios against the running stack. |
| `make eval` | Live LLM eval; opt-in, requires a real `ANTHROPIC_API_KEY`. |

## Design tour

For a ~10 min architecture & design tour, read `.kerf/project/docflow/SPEC.md`. It walks the system top-down with pointers into the per-component specs under `.kerf/project/docflow/05-specs/`. A one-page architecture diagram lives in `docs/architecture.md`, and `docs/api-walkthrough.md` is a curl-based walkthrough of the realistic flow for reviewers without the SPA.

## Design decisions

- **YAML-seeded per-org config.** Org / doc-type / workflow definitions ship as classpath resources and are loaded once on first boot by `OrgConfigSeeder`. Adding a new doc type or org is a config-only change.
- **Workflow-as-data.** Per-org × per-doc-type workflows are encoded as YAML transitions evaluated by `TransitionResolver`, including the lien-waiver `unconditional`-skip guard. No code change is needed to add a new approval chain.
- **Anthropic-only LLM dependency.** Single provider, single model id (`claude-sonnet-4-6`) bound at startup. Multi-provider routing is out of scope.
- **SSE over WebSockets** for stage-progress fan-out — one-way feed, plain HTTP semantics, no client send-channel needed.
- **Local-filesystem document storage** behind the `StoredDocumentStorage` seam — S3 swap is a single-implementation change.
- **Postgres schema in 3NF, FKs everywhere.** JSON columns reserved for the genuinely-dynamic extracted-field payloads; everything else is relational. Migrations via Flyway.
- **Spring Boot 4 + Java 25** via the Gradle toolchain; no host JDK required when building through Docker.

## Document pipeline

A document is represented by three distinct domain entities as it flows through the system, each owning a different phase of its life:

- **`StoredDocument`** — the immutable byte content and upload metadata. Created once when the file lands, never mutated, persists for the document's full lifetime. Backed by the filesystem-storage seam (`StoredDocumentStorage`); the DB row holds only the path + content type.
- **`ProcessingDocument`** — transient pipeline state that lives only while text extraction, classification, and field extraction are in flight. Carries retry/failure status, current pipeline step, and any partial output. Retired the moment extraction completes; ProcessingDocument failures don't touch the filed state.
- **`Document`** — the human-reviewable, workflow-bearing entity that materializes when extraction succeeds. Holds the extracted fields, the resolved doc type, and (via the linked `WorkflowInstance`) the current review/approval state through to Filed or Rejected.

This split keeps each table narrow and write-path-specific: pipeline retries write only to `ProcessingDocument`, the workflow engine writes only to `Document`/`WorkflowInstance`, and the dashboard read is scoped to `Document` alone with a small in-flight join against `ProcessingDocument` for the "still processing" indicator.

```
Upload  ─►  Text Extract (PDFBox)  ─►  Classify (LLM)  ─►  Extract Fields (LLM)
                                                                   │
                                                                   ▼
                                                                Review  ──► Rejected (terminal)
                                                                   │
                                                                   ▼
                                                  Approval stage 1 ─► … ─► Approval stage N ─► Filed
                                                                   │
                                                                   ▼
                                                       Flag (with comment)
                                                       returns to Review,
                                                       remembers origin stage
```

`StoredDocument` is alive for the whole flow above. `ProcessingDocument` covers Upload through Extract Fields. `Document` is born at Review and persists through every subsequent state.

- The **approval chain is org × doc-type specific** — defined in YAML under `backend/src/main/resources/seed/workflows/<org>/<doc-type>.yaml`. Pinnacle invoices go Review → Attorney Approval → Billing Approval → Filed; Riverside invoices go Review → Manager Approval → Filed; Ironworks invoices go Review → Project Manager Approval → Accounting Approval → Filed.
- **Flag** is valid only from approval stages. It returns the document to Review, requires a comment, and stores the origin stage so Resolve sends the document back where it came from.
- **Reject** is a Review-stage action and is terminal.
- **Lien-waiver special case**: an unconditional lien waiver skips Project Manager Approval and is filed directly from Review. Conditional waivers walk the full chain. The guard lives in `TransitionResolver`.

## Key files

Backend — pipeline + workflow:
- `backend/src/main/java/com/docflow/workflow/WorkflowEngine.java` — workflow state machine; entry point for every action.
- `backend/src/main/java/com/docflow/workflow/TransitionResolver.java` — guarded transitions (incl. lien-waiver skip guard).
- `backend/src/main/java/com/docflow/c3/llm/LlmExtractor.java` — Anthropic SDK call boundary for classify + extract.
- `backend/src/main/java/com/docflow/api/document/ReviewController.java` — Review-stage HTTP API (approve / flag / resolve / reject / retype).
- `backend/src/main/java/com/docflow/api/dashboard/JdbcDashboardRepository.java` — dashboard list + counts SQL.

Backend — schema + seeded config:
- `backend/src/main/resources/db/migration/V1__init.sql` — Postgres schema.
- `backend/src/main/resources/seed/workflows/<org>/<doc-type>.yaml` — per-org × per-doc-type workflow definitions.
- `backend/src/main/resources/seed/doc-types/<org>/<doc-type>.yaml` — field schemas per doc type.

Frontend:
- `frontend/src/routes/DocumentDetailPage.tsx` — main detail screen; PDF viewer + form + stage progress + action bar.
- `frontend/src/components/ReviewForm.tsx` — review-stage UX (approve / flag / reject / retype branching).
- `frontend/src/components/FormPanel.tsx` — extracted-field form rendering, including dynamic schema swap on retype.

Eval:
- `eval/harness/run.py` — live-LLM eval harness (HTTP-driven via the running backend).
- `eval/harness/run_db_direct.py` — DB-direct variant; polls Postgres for completed extractions.
- `eval/pdfbox-check/REPORT.md` — text-extraction quality report (23/23 PDFs clean).
- `eval/reports/db_direct_*.md` — per-run scoring output.

## Tests

- **Unit + integration:** `make test` runs the full backend suite (`./gradlew check`) plus `npm --prefix frontend run check`. Last clean run: backend 381/381, frontend 105/105.
- **Scenario harness:** YAML-driven end-to-end scenarios under `backend/src/test/resources/scenarios/`, executed by `backend/src/test/java/com/docflow/scenario/ScenarioRunnerIT.java`. 3 of the planned 12 scenarios are merged today (`01-happy-path-pinnacle-invoice`, `02-wrong-type-classification`, `03-missing-required-field`); the rest are queued.
- **Live LLM eval:** `make eval` runs `EvalRunner` against `problem-statement/samples/`; opt-in (requires a real `ANTHROPIC_API_KEY`), not part of `make test`. The Python harness in `eval/harness/` is the day-to-day variant.
- **PDFBox text-quality check:** one-shot run, results in `eval/pdfbox-check/REPORT.md`.

## What works well

- Classify + extract is accurate on the sample corpus: **23/23 doc-type, 105/111 fields = 94.6%** on the latest live-LLM eval (`eval/reports/db_direct_20260429T230742Z.md`).
- PDFBox text extraction is clean across all 23 samples — no parse failures, all key fields preserved.
- Core happy-path workflow exercised end-to-end via API for all three orgs (upload → classify → extract → Review → approve chain → Filed).
- Flag-from-approval with origin restoration verified working: origin stage stored on flag, restored on resolve, comment cleared.
- Concurrent uploads handled independently — no field cross-contamination across in-flight pipelines.
- SSE feed drives real-time dashboard updates.

## Production considerations

- **No auth.** A real deployment would add identity + role-based access; the `role` slot on approval stages (C1-R10) is the attach point.
- **The `role` / `stage` / `action` model is deliberately basic.** Production would likely promote `Role` to a first-class entity with a permissions matrix, support composite approvers, and let roles be shared across orgs where appropriate.
- **`ProcessingDocument` cleanup is deferred.** On successful pipeline completion the take-home leaves the `ProcessingDocument` row in place; the dashboard query filters it out by joining against `documents`. Production would add a cron-style cleanup that deletes `ProcessingDocument` rows whose `storedDocumentId` already has a `Document`.
- **The 3-entity document lifecycle is a deliberate domain modeling choice.** `StoredDocument`, `ProcessingDocument`, and `Document` are separate tables — not a single document row with a `currentStage` discriminator. Most documents are stable (processed); only a few are transient. Separating them keeps the dashboard read narrow, lets the pipeline iterate on its own state shape, and gives each entity exactly one writer. The cost is a two-array dashboard payload — a deliberate tradeoff.
- **Local-filesystem storage instead of S3.** The `StoredDocumentStorage` seam (C2-R7) isolates the swap.
- **Classpath seed fixtures instead of an externally-managed config store.** Org / doc-type / workflow YAMLs ship in the backend jar.
- **Single-tenant deployment.** Horizontal scaling concerns — distributed SSE fan-out, LLM call concurrency limits — are not addressed.
- **No pagination on the dashboard documents list.** The endpoint returns up to a soft cap (~200 rows); the take-home corpus stays well below it. Production would add cursor-based pagination on the `documents` array (the `processing` array stays small in any deployment).

## Repo guide

What lives at the repo root and whether a reviewer should care:

| Path | Purpose | Reviewer-relevant? |
|---|---|---|
| `backend/`, `frontend/` | Implementation. | Yes |
| `problem-statement/` | Original take-home spec + sample PDFs. Read-only input. | Yes |
| `docs/` | Architecture diagram + curl-based API walkthrough. | Yes |
| `.kerf/project/docflow/` | Curated planning artifacts (problem space, component specs, integration plan, tasks). `SPEC.md` is the entry point. | Yes |
| `eval/` | Eval manifest + generated reports under `eval/reports/`. | Optional |
| `TESTING-PLAYBOOK.md` | Manual test scenarios run against the live stack. | Optional |
| `.beads/` | Issue tracker DB + JSONL export. See "Future work" below. | Optional |
| `HANDOFF.md`, `HANDOFF-tester.md` | Internal hand-off notes between the implementor and tester agent lanes used to build this. | No (internal) |
| `test-logs/` | Captured manual-test output from the tester lane. | No (internal) |

## Future work

The take-home is feature-complete against the spec, but a handful of follow-ups are tracked in `.beads/issues.jsonl`. Themed:

- **Persistence cleanup.** Several tables have overlapping JPA + JDBC writers; consolidate to a single writer per table and tighten visibility on shared SQL constants.
- **Performance hotspots.** Dashboard query needlessly returns `raw_text`, the four stat counts could collapse into one aggregate query, the processing list has no `LIMIT`, and `WorkflowEngine.applyAction` holds a Postgres connection across the 60s LLM call on retype. Hikari pool size and leak detection are unconfigured.
- **Scenario coverage.** The Playwright suite covers the happy path and flag-and-resolve; ~7 additional scenarios (corrupt PDFs, lien-waiver guards, retype origin restoration, terminal-state actions, concurrent uploads + SSE) are written up but not yet implemented.
- **Retype-flow correctness.** Re-extraction holds the workflow connection open; the retry policy and load-time PDF check deviate slightly from the kerf spec.
- **Error-path mapping.** PDF response is buffered into a full `byte[]` rather than streamed; not a correctness bug, but worth fixing under load.

See `.beads/issues.jsonl` for the full backlog.
