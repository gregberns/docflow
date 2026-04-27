# DocFlow — Problem Space

## Summary

Build DocFlow, a multi-client document processing platform for an outsourced bookkeeping firm. Users pick a client (organization), upload PDFs/images, and the system uses an LLM to classify the document type and extract structured fields. Documents then flow through a client-specific, stage-based workflow (Review → one or more Approval stages → Filed), with support for rejection at Review and flagging back from any approval stage. Three concrete clients (Riverside Bistro, Pinnacle Legal Group, Ironworks Construction) must be fully supported, each with their own document types, field schemas, and workflows — the architecture must admit additional clients without structural changes. Source of truth for requirements is the spec at `problem-statement/DocFlow_Take_Home_Exercise_greg_berns.md`.

## Goals

1. **Meet every spec requirement cleanly.** Check every box in the spec without over-delivering; optimize for a clear, correct implementation over scope expansion.
2. **Multi-tenant from day one.** Each of the three clients has different document types, fields, and workflows; the system must be data-driven enough that adding a fourth client is a data change, not a code rewrite.
3. **Real AI classification and extraction — two-call pipeline with structured output.** Use the Anthropic API for both the classification step and the field-extraction step. No simulated responses. Each upload runs classification first (scoped to the selected org's allowed doc-types), then extraction using the schema for the classified type. On reclassification in Review, only the extract call re-runs. The raw text from the uploaded PDF is extracted on the backend (e.g., via PDFBox) and stored alongside the document record for display/debugging/future search, regardless of what is sent to the LLM. Structured extraction uses Anthropic **tool use**: a tool per `(client, docType)` whose JSON input schema matches the spec's field list; the model is forced to call that tool and `tool_use.input` is the structured output.
4. **Stage-based workflow engine, driven by declarative config.** Support the documented workflows for each (client, document-type) pair, including the flag-and-resolve loop (return to originating stage) and the Lien Waiver "unconditional" shortcut. Workflows (stages, transitions, conditional branches) are defined in a declarative configuration format (YAML or DOT/graph notation — decided in research) and loaded at startup. No client- or type-specific workflow logic is hardcoded in backend services or in the UI; adding a new workflow is a config change, not a code change. The engine stays intentionally simple — just enough to express the spec's workflows and the unconditional-lien-waiver branch.
5. **Live progress visibility.** Documents appear in the dashboard while classification/extraction are still running. The UI learns about state transitions through SSE (server-sent events) rather than poll-on-interval.
6. **One-command startup.** `docker-compose up` must bring the whole stack (backend, frontend, database, anything else) to a working state.
7. **Seed demo data on boot.** Pre-seed ~half of the provided sample documents so the reviewer sees a populated app on first launch; the rest are available to upload manually.
8. **Test at a professional bar.** 95%+ backend unit-test coverage, property-based tests for the workflow engine, integration tests for the LLM-touching paths (with recorded / mocked responses at that seam), unit/component-level tests for the frontend, and a minimal E2E suite for the critical user journey. Specific frontend testing approach (component-test framework, visual/contract-test strategy, optional agent-driven exploratory click-through) is a research item for Pass 4.
9. **Normalized relational schema.** The PostgreSQL schema is 3NF or better for transactional data, with foreign keys on every reference, indexes on every foreign key and common query column, and migrations version-controlled via Flyway or Liquibase. JSON is reserved for the genuinely-dynamic field payloads per document type; everything else is relational.
10. **Enforced conventions, not aspirational ones.** Lint, format, static analysis, and type checks are wired into the build so violations fail `./gradlew build` (backend) and the frontend build. An agent making changes conforms to the rules; it does not suppress them.
11. **"Done" means green.** Before the agent claims any task is complete, it runs the full build and the fast test suite (unit + integration + property-based) and verifies lint/format/type checks pass. Long-running tests (E2E browser, full seed-data loads) are exempt and run on demand. A Stop hook in `.claude/settings.json` enforces this automatically once the build is in place.
12. **Agent-configuration is versioned.** `AGENTS.md` is the canonical agent guide, `CLAUDE.md` is a symlink to it, and any Claude Code skills, hooks, or settings used by this project live under `.claude/` and are committed.
13. **Classification/extraction quality is measured, not assumed.** A simple eval harness scores the LLM pipeline against labeled samples. The sample corpus is split into an **eval/verify holdout** that is never used for prompt tuning, and a **tune/dev set** that can be. Running the eval produces a reproducible report (per-doc-type accuracy for classification; per-field precision/recall for extraction). The eval is runnable via a single command (e.g., `./gradlew evalLlm` or equivalent) and documented in the README. Target: classification accuracy on the holdout set ≥ 95%, with the measurement visible and reproducible — not a boast in the README.
14. **Startup-only external-input loading.** All environment variables and external configuration values (API keys, DB connection strings, file-storage paths, model IDs, feature flags) are read **once at application startup**, validated, and bound into a typed, immutable config object that is dependency-injected into components that need them. No code path in the running service calls `System.getenv()`, `@Value("${...}")` outside of the config-binding layer, or reads a config file mid-request. Missing or invalid config fails startup with a clear error — not a runtime failure inside a user request. This applies to both backend and any frontend build-time config.

## Non-goals

The following are **deliberately out of scope** for this take-home:

- **Authentication and user identity.** The spec does not specify users or roles. The app runs with no login; reviewer/approver actions are anonymous. This is a deliberate simplification that will be documented in the README. (If this were a real product, a real auth model would be first-class.)
- **Object storage.** Uploaded files are written to the local filesystem via a Docker volume. In production we would use S3 (or equivalent) — this will be called out in the README and in the design. No abstraction gymnastics to pretend otherwise in code.
- **Horizontal scaling / prod ops.** No Kubernetes manifests, no Prometheus/Grafana wiring, no centralized logging stack. Structured logs to stdout and that's it.
- **Exhaustive frontend test coverage.** Backend carries the 95%+ coverage bar; the frontend has tests but not at that percentage. Exact framework and coverage target to be decided in Pass 4 (Research).
- **Pixel-perfect mockups.** Layout, information architecture, and behavior must match the provided mockups; exact styling and polish are not targets.
- **Clients beyond the three in the spec.** The data model must accept a fourth client, but we are not building one.
- **Features not in the spec.** No audit log UI, no reporting, no bulk operations, no document search, no export, no notifications. If the spec doesn't ask for it, we don't build it.

## Constraints

### Technical
- **Backend:** Java 25, Spring Boot 4 (match the reference stack exactly — these are recent GA releases; the README will note the tooling prerequisites).
- **Frontend:** React + TypeScript.
- **Database:** PostgreSQL.
- **LLM:** Anthropic API (Claude). API key supplied via `ANTHROPIC_API_KEY` env var; `.env.example` documents it.
- **File storage:** Local filesystem backed by a Docker volume. Code will isolate the storage seam so it can be swapped for S3 later.
- **Runtime:** Must start end-to-end via `docker-compose up` with no additional setup beyond setting the Anthropic API key.

### Product (from spec)
- Documents are always scoped to a selected organization — the dashboard, filters, and detail view must all respect the current org.
- Dashboard filter options (stages, types) are derived from the selected org's workflow definitions, not a global list.
- In-flight documents (Classifying / Extracting) appear at the top of the list with a non-actionable indicator.
- Review stage: editable form, document-type dropdown; changing the type triggers re-extraction with an alert.
- Flag from approval: requires a comment; returns the document to Review with a banner showing origin stage and comment; Resolve returns it to that originating stage unless the type was changed (in which case re-extraction runs).
- Approval stages: read-only view, Approve or Flag-with-comment only.
- Rejected and Filed are terminal.
- **Lien Waiver special case:** if `waiverType == unconditional`, the workflow skips Project Manager Approval and goes Review → Filed.

### Scope discipline
- Three clients, nine (client × document-type) combinations, fully supported.
- Must handle spec-specified fields exactly (including nested `lineItems` / `items` / `materials` arrays).

### Quality / engineering discipline
- **Database:** PostgreSQL, normalized to at least 3NF for transactional tables, foreign keys and indexes on every reference, migrations under version control (Flyway or Liquibase).
- **Backend quality gates:** Spotless (format), Checkstyle or PMD (lint), SpotBugs (static analysis) wired into Gradle/Maven so `./gradlew build` fails on violations.
- **Frontend quality gates:** ESLint, Prettier, TypeScript strict mode wired into the build and a pre-commit hook.
- **Done discipline:** before declaring any implementation task complete, the agent runs the full build + fast test suite + lint/format/type checks. Failing any = not done. Enforced by a Stop hook in `.claude/settings.json`.
- **Agent configuration:** `AGENTS.md` is the canonical guide, `CLAUDE.md` symlinks to it, and Claude Code settings/skills/hooks under `.claude/` are committed.

### Deferred — to be specified in later passes
- **System architecture.** Module boundaries, layering (controllers / services / repositories vs. hexagonal / feature-sliced), whether the workflow engine is a separate bounded context, async processing mechanism (in-process `@Async` + SSE push, vs. queue + worker) — decided in the Analyze / Decompose / Research passes.
- **Frontend testing strategy.** Unit/component test framework (Vitest + React Testing Library vs. Jest), what qualifies as a "component test" vs. an E2E test, the coverage bar, and whether to include agent-driven exploratory testing (an agent clicks through the app and checks that pages render without JS errors and key elements are present) — to be scoped in Pass 4 (Research) with findings in `04-research/frontend-testing/findings.md`.
- **Workflow configuration format.** Whether workflows are defined in YAML, DOT/graph notation, or another format; how conditional transitions (e.g., Lien Waiver unconditional) are expressed; whether the config is loaded at startup only or hot-reloadable; the validation strategy for workflow configs at load time. Decided in Pass 4 (Research) with findings in `04-research/workflow-config/findings.md`. Principle: simple enough to read and edit by hand, expressive enough for the spec's workflows plus conditional branches.
- **LLM eval strategy.** How classification and extraction are scored; how the sample corpus is split into tune/dev vs. verify/holdout sets (and how the split is recorded so it's reproducible); what metrics are reported (accuracy for classification; field-level precision/recall/exact-match for extraction; handling of nested arrays like `lineItems` where partial correctness is possible); whether the eval runs against recorded LLM responses or live API calls; how results are reported (stdout + markdown report committed, vs. just stdout); how to surface eval deltas when prompts change. Decided in Pass 4 (Research) with findings in `04-research/llm-eval/findings.md`. Sample recon already notes that the hardest cases are narrative fields (retainer scope, change-order description), variable rate types (legal invoices), and category inference (receipts) — the holdout set should span these.
- **LLM input modality.** Whether classification and extraction calls send extracted text, native PDF content blocks (via the Anthropic SDK's `document` block — base64 or Files API `file_id`), or a hybrid (e.g., text for classify, PDF for extract). Tradeoffs: text is cheaper and faster; native PDF preserves spatial structure (helpful for tables such as `lineItems` and `materials`). The decision is driven by running the eval both ways on the tune set. Findings in `04-research/llm-input-modality/findings.md`.
- **Prompt management / versioning.** How prompts (system prompts, tool schemas, few-shot exemplars) are stored, versioned, and referenced. Candidate approaches: prompts in code as constants (simple, but every tweak is a code change), prompts in resource files with semantic-version filenames, prompts in the DB with a `prompt_version` column on each LLM-call record, or a thin wrapper that tags each call with a content-hash of the prompt used. The eval should be able to score a named prompt version against the holdout. Whether to track per-prompt performance over time, or just "which prompt was used for this specific document" as an audit trail, is part of the research. Findings in `04-research/prompt-management/findings.md`.
- **Approval-role domain modeling.** The spec's eight approval-stage names (Manager, Finance, Attorney, Billing, Partner, Project Manager, Accounting, Client Approval) conflate two distinct concepts: the *step* in the workflow and the *role* of the person who approves it. With no auth in scope, we can't enforce role-based access, but the domain is clearer if "role" is explicit. Options: (a) a string `role` tag on each approval stage in config — minimum viable; (b) a first-class `Role` entity with a FK from stage definition to role — fuller domain model, adds a table; (c) leave implicit (what the current draft does) — less clear. The UI should display role alongside stage name regardless of which option we pick. Research will also consider whether to keep a placeholder `assignedTo` / `approvedBy` column on the document state-change history for a future auth layer, even though it's always null today. Findings in `04-research/approval-roles/findings.md`.

### Timeline
- Target 3–4 days of real working time (per spec's own estimate).

## Success criteria

Each of the following must be true for the work to be considered done:

### Runtime / deployment
1. Running `docker-compose up` from a fresh clone, with `ANTHROPIC_API_KEY` set in `.env`, brings up backend, frontend, and database without additional commands.
2. The seeded sample documents (~half of the files under `problem-statement/samples/`) are present in the DB and accessible via the dashboard on first launch.

### Core flows
3. A user can open the app, pick any of the three clients, and land on that client's dashboard.
4. Uploading a PDF or image results in a new document that progresses through Classify → Extract → Review without manual intervention, with the UI observing each transition via SSE.
5. Classification and extraction both call the real Anthropic API; disabling network connectivity causes those steps to fail in an observable way (not silently fall back to mocks).
6. From the Review stage, the user can: edit fields, change the document type (triggering re-extraction with a visible alert), approve (advance to the next workflow stage for this client × type), or reject (terminal).
7. From any approval stage, the user can approve (advance) or flag with a required comment (return to Review).
8. A flagged document shows the comment banner in Review, and the Resolve button returns it to the stage it was flagged from — unless the reviewer changed the type, in which case re-extraction runs and the document returns to Review.
9. Filed and Rejected documents show a read-only detail view with no action buttons.
10. A Lien Waiver with `waiverType == unconditional` goes directly from Review to Filed, skipping Project Manager Approval.

### Multi-tenancy
11. Each of the three clients' document types render the correct fields in the Review form (verified against the spec's field lists).
12. Each (client, document-type) pair follows the correct workflow stages end-to-end.
13. Dashboard filter dropdowns for stage and type reflect only the selected org's workflows and types.
13a. Workflows are defined in a declarative config file(s) loaded at startup. Grepping the backend source for stage names (e.g., `"Manager Approval"`) or client names (e.g., `"Pinnacle"`) returns zero business-logic hits — those strings live in config and in tests only. Adding a new (client, document-type) workflow requires only a config edit and a DB/seed update, no code change in services or UI.

### Quality
14. Backend unit-test coverage is ≥ 95% (measured by JaCoCo or equivalent).
15. The workflow engine has property-based tests that assert invariants (e.g., every workflow eventually reaches Filed or Rejected; flag→resolve returns to the originating stage modulo type change; terminal states have no outgoing transitions).
16. Integration tests cover the classify and extract paths against a mocked or recorded Anthropic response at the HTTP seam.
17. A minimal E2E suite verifies the happy path: upload → classify → extract → review → approve → filed, for at least one (client, document-type) combination.
17a. The frontend has unit/component tests for the non-trivial logic (stage progress, workflow-aware action buttons, form for dynamic field schemas). The coverage bar and framework are set in Pass 4 research; whatever bar is set, the build enforces it.

### Documentation
18. `README.md` at the repo root explains how to run the app, the design decisions made (no-auth, filesystem vs S3, Java/Spring versions, LLM choice, SSE for progress), and any assumptions taken.
19. `AGENTS.md` exists at the repo root and documents the non-negotiable conventions (done-discipline, lint/format/type rules, DB rules). `CLAUDE.md` is a symlink to `AGENTS.md`.

### Engineering quality
20. Backend build fails on lint, format, or static-analysis violations. Frontend build fails on ESLint errors, Prettier drift, or TypeScript errors.
21. A Stop hook in `.claude/settings.json` runs the fast test suite + build checks and is committed to the repo so the rule is enforced, not aspirational.
22. Database schema is 3NF or better for transactional tables, foreign keys on every reference, indexes on every FK and common query column, migrations managed by Flyway or Liquibase.

### LLM quality
23. The sample corpus is split into a documented **tune/dev** set and an **verify/holdout** set. The split is recorded in a committed file (e.g., `eval/split.yaml`) and is deterministic — the same samples are in each bucket on every run. The holdout set spans the hardest cases identified during recon (narrative fields, variable rate types, category inference).
24. An eval harness runs via a single command and produces:
    - Classification accuracy per (client, doc-type), aggregate, and on the holdout set alone.
    - Extraction metrics per field (exact-match rate, or field-level precision/recall for list-valued fields).
    - A markdown report that is committable (so prompt changes + eval deltas can be diffed in git).
25. The eval harness is repeatable without network calls by default (using recorded responses or a cached golden file); a `--live` flag runs it against the real Anthropic API. CI runs only the recorded-mode eval.
26. The README documents how to run the eval, how to add new labeled samples, and how to move a sample from tune to holdout.
