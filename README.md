# DocFlow

Multi-client document processing platform: ingest PDFs, run an LLM-driven classify-and-extract pipeline, and route the result through a per-org review workflow.

## How to run

Prerequisites: Docker (with Compose v2). No host-side JDK / Node install needed for the default flow.

```
cp .env.example .env
# fill in ANTHROPIC_API_KEY in .env
make build
make start
```

The seeded dashboard works without a key — only live uploads and `make eval` require `ANTHROPIC_API_KEY`. A reviewer who just wants to click around the UI against the labeled samples can leave it unset.

Common targets:

| Command | Action |
|---|---|
| `make build` | Build backend + frontend images. |
| `make start` (alias `up`) | Start backend, frontend, Postgres. |
| `make stop` (alias `down`) | Stop the stack. |
| `make test` | Fast gate: `./gradlew check` then `npm --prefix frontend run check`. |
| `make e2e` | Playwright scenarios against the running stack. |
| `make eval` | Live LLM eval; opt-in, requires a real `ANTHROPIC_API_KEY`. |

Host-side ports are controlled by `BACKEND_HOST_PORT` and `FRONTEND_HOST_PORT` in `.env.example`; consult that file for the full set of variables.

## Design tour

For a ~10 min architecture & design tour, read `.kerf/project/docflow/SPEC.md`. It walks the system top-down with pointers into the per-component specs under `.kerf/project/docflow/05-specs/`. A one-page architecture diagram lives in `docs/architecture.md`, and `docs/api-walkthrough.md` is a curl-based walkthrough of the realistic flow for reviewers without the SPA.

## Design decisions

- **No auth.** Development scope only. Endpoints are namespaced by `organizationId` (path-scoped) but unauthenticated. Identity / RBAC is the production attach point (see Production considerations).
- **Local filesystem storage.** `StoredDocument` bytes are written under `STORAGE_ROOT` on the backend container's volume. The storage seam is isolated behind `StoredDocumentStorage`, so swapping in S3 is a single-implementation change.
- **Java 25 + Spring Boot 4.** Backend `build.gradle.kts` pins `JavaLanguageVersion.of(25)` and `org.springframework.boot` `4.0.0`. The toolchain is provisioned by Gradle; no host JDK is required when building via Docker.
- **Anthropic as the LLM provider.** Single provider, single model id (`claude-sonnet-4-6`) bound at startup via `AppConfig.llm.modelId`. The Anthropic Java SDK is the only LLM dependency; routing across providers is out of scope.
- **Server-Sent Events for stage progress.** `GET /api/organizations/{orgId}/documents/stream` fans out `ProcessingStepChanged` and `DocumentStateChanged` events from the in-process `DocumentEventBus`. SSE was chosen over WebSockets for a one-way feed under HTTP semantics with no client send-channel.
- **Three Docker services.** `docker-compose.yml` runs `postgres` (Postgres 16), `backend` (Spring Boot, port 8080 in-container), and `frontend` (Vite-built SPA served on 5173) on a shared bridge network. The frontend container reaches the backend via the in-network DNS name `backend`; host-side dev (`npm run dev`) uses Vite proxy to `http://localhost:8080`.
- **Node 22.** Frontend image is `node:22-alpine`; `frontend/package.json` targets React 19 + Vite 8.

## Seeded data

Two layers of seed data populate the database on first boot only. After the first successful run, the database is authoritative; subsequent boots skip already-seeded rows.

1. **Client configuration** (`OrgConfigSeeder`). Reads `backend/src/main/resources/seed/organizations.yaml` plus the per-org doc-type and workflow YAMLs under `seed/doc-types/<org>/` and `seed/workflows/<org>/`, and inserts `organizations`, `document_types`, `workflows`, `stages`, and `transitions`. Gated by `docflow.config.seed-on-boot` in `application.yml` (true under the `dev` profile, false under `prod`).
2. **Labeled samples** (`SeedDataLoader`). Reads `backend/src/main/resources/seed/manifest.yaml` and inserts `StoredDocument` + `Document` (with `processedAt = now`) + `WorkflowInstance` (`currentStageId = Review`) for each entry — bypassing the LLM pipeline entirely. PDFs live under `backend/src/main/resources/seed/files/...` mirroring the manifest's relative paths. Idempotent on `(organizationId, sourcePath)`.

To add a labeled sample: pick a PDF from `problem-statement/samples/`, copy it under `backend/src/main/resources/seed/files/<org>/<type>/<file>.pdf`, and append an entry to `backend/src/main/resources/seed/manifest.yaml` with `path`, `organizationId`, `documentType`, and `extractedFields`. `SeedDataLoader` will pick it up on the next clean boot.

## `make eval`

`make eval` is a separate, opt-in target that runs the C3 live-LLM eval harness (`com.docflow.c3.eval.EvalRunner`). It is **not** invoked by `make test` or CI. Requirements:

- A real `ANTHROPIC_API_KEY` in the environment. The Gradle task short-circuits with a "skipping eval" message if the key is unset or blank.
- The sample PDFs available at `problem-statement/samples/` (the default `docflow.eval.samplesRoot`).

The harness boots a non-web Spring context, runs classify + extract against each entry in `eval/manifest.yaml`, scores results with `EvalScorer`, and writes a markdown report to the path configured at `docflow.llm.eval.report-path` (default `eval/reports/latest.md`). The report contains aggregate doc-type and per-field accuracy plus a per-sample breakdown; an aggregate accuracy below the configured threshold causes the task to exit non-zero.

## Production considerations

The take-home was scoped narrowly. The items below were intentionally simplified.

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
