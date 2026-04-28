# DocFlow — Tasks (Pass 7)

> Implementation task list assembled from the seven Pass-5 component change specs in `05-specs/` and the Pass-6 integration plan in `06-integration.md`. Read this with `SPEC.md` and `06-integration.md` open. **68 tasks across 7 components**, grouped into 10 phases by dependency. Within a phase, tasks can be parallelized; across phases they are gated.

Phase ordering is the recommended implementation sequence — but each task carries explicit `Within-component deps` and `Cross-component deps`, so the order can be relaxed where they're independent.

---

## 1. How to read this list

Each task entry has the same shape:

```
### CN.M — {short imperative title}                                     [Phase X]
**What:** 1–3 sentences describing the work.
**Spec refs:** CN-Rx; cN-*-spec.md §x.y; SPEC.md §x.
**Deliverables:** files / classes / tests.
**Acceptance criteria:** ACx from spec or testable conditions.
**Within-component deps:** {CN.X, CN.Y} or {none}.
**Cross-component deps:** {task IDs} or {none}.
**Size:** half-day | 1 day | 2 days.
```

- **Phase** tags drive ordering across components. Implementer tackles one phase at a time, parallelizing within a phase.
- **Within-component deps** are local prerequisites; the per-component sections below are listed in dep order.
- **Cross-component deps** point at specific task IDs in other components. Where a task contributes a fragment to a shared artifact (e.g., the `V1__init.sql` migration), the cross-comp dep names the assembling task (`C7.4`).

### A note on event records

Six concrete `DocumentEvent` records cross component boundaries: `StoredDocumentIngested`, `ProcessingStepChanged`, `ProcessingCompleted`, `ExtractionCompleted`, `ExtractionFailed`, `DocumentStateChanged`. Their package owners are documented in the publisher's spec (mostly `com.docflow.c3.events.*` for ingestion + pipeline events; `com.docflow.workflow.events.*` for `DocumentStateChanged`). At implementation time, the record skeletons should be created early (alongside `C7.7 DocumentEventBus`) so producers and consumers can compile against a stable contract; the corresponding tasks are pinned via cross-comp deps below.

### A note on the V1 migration

`V1__init.sql` is a single Flyway baseline (C7-R3) but its content is contributed by C1, C2, C3, and C4. Each owner ships an SQL fragment via its own task (`C1.5`, `C2.3`, `C3.1`, `C4.3`); `C7.4` is the assembly task that stitches the fragments in canonical order and ships the file. Every task that reads or writes a covered table thus carries `C7.4` as a cross-comp dep.

### A note on cross-component vs orchestration tasks

There is **no separate "integration" component** in this list. Integration tasks (the C3↔C4 listener seam, the SSE fan-out, the HappyPathSmokeTest) live in the natural owning component:

- `C4.6` (ProcessingCompletedListener) and `C4.7` (ExtractionEventListener) are C4-owned.
- `C5.7` (SSE fan-out) and `C5.8` (HappyPathSmokeTest) are C5-owned.
- `C6.12` (Playwright E2E) is C6-owned.
- `C7.9` (application-data seed loader) is C7-owned and runs across the C1/C2/C4 tables.

Each of these tasks names its cross-comp deps explicitly, so the integration topology surfaces from the per-component lists rather than a separate section.

---

## 2. Phases

| Phase | Name | What unlocks next phase | Parallelizable across components? |
|---|---|---|---|
| **P0** | Repo & build skeleton | Other tasks can compile / run a test | Yes (frontend skel parallel to backend skel) |
| **P1** | `AppConfig` & shared platform | Component code can read config | No — single linear chain |
| **P2** | Pure domain types | Properties, validation, generators ready | Yes (C1.1 + C4.1 in parallel) |
| **P3** | DB schema fragments + V1 assembly | DB-bound code can run against Postgres | Mostly yes (fragments parallel; assembly serial) |
| **P4** | Quality gates + event bus | `make test` is a real gate; events flow | Yes (C7.5 / C7.6 / C7.7 / C1.9 parallel) |
| **P5** | Config layer build-out | Catalogs available to all consumers | Mostly yes (C1.2/C1.3/C1.4 parallel; C1.7/C1.8 serial) |
| **P6** | Component implementation | Backend domain logic + storage | **Highly parallel** — C2 / C3 / C4 fan out |
| **P7** | API surface | REST + SSE reachable by the frontend | Mostly serial within C5 |
| **P8** | Frontend implementation | UI consumes the API surface | **Highly parallel** within C6 |
| **P9** | Integration, E2E, hooks, CI, README | "Done" bar reachable | Mostly parallel |

Detailed phase membership is named in each task's `[Phase X]` tag.

---

## 3. Dependency graph (key cross-component edges)

```
P0:  C7.1 ──► C7.2
              └─► (independent C1.1, C2.1, C4.1 can begin)

P1:  C7.2 ──► C7.3 (AppConfig — hosts C1.6, C2.2-config, C3.6-config nested records)

P2:  C1.1, C4.1 (pure types — parallel)

P3:  C1.5 ┐
     C2.3 ├──► C7.4 (V1 assembly) ──► every DB-bound task
     C3.1 │
     C4.3 ┘

P4:  C7.4 ──► C7.5 (quality gates), C7.7 (DocumentEventBus + DocumentEvent seal)
     C1.9 (forbidden-strings.txt) ──► C7.6 (grepForbiddenStrings)

P5:  C7.4 ──► C1.2 ──► C1.3 ──► C1.4 ──► C1.7 ──► C1.8 (catalogs)
                                                  └──► consumed by C2.4, C3.{4,5,6,7,8}, C4.{2,4,5,6,7}, C5.3

P6:  C1.8 + C7.4 + C7.7 ──► C2 + C3 + C4 (parallel fan-out)

      C2.1 ──► C2.2 ──► C2.4 ──► C2.5
                ▲
                └─ C7.3, C2.3
                   C2.4 also depends on: C1.8, C7.7

      C3.2 ──► C3.9 ──► (C2.4 publishes ingested events; C4.6 consumes ProcessingCompleted)
      C3.3 ──┘    ▲
      C3.4   ────┘
      C3.5 ──► C3.6 ──► C3.7 ──┐
                              ├──► C3.9
      C3.5, C3.6 ──► C3.8 ──► (C4.5 sync-call; C4.7 listens)
      C3.7 + C3.8 ──► C3.11 (eval)
      C3.9 ──► C3.10 (smoke IT)

      C4.1 ──► C4.2 ──► C4.4 ──► C4.5 ──► C4.6, C4.7
                                       ▲
              C3.8 ───────────────────┘  (sync call from C4.5; listener in C4.7)
              C3.9 events ──► C4.6
              C4.5, C4.7 ──► C4.8 (jqwik) ──► C4.9 (origin-restoration matrix)

      C7.8 (manifest YAML + 12 PDFs) ──► C7.9 (seed loader, after C1.7 catalogs)

P7:  C5.1 ──► C5.2 ──► C5.3, C5.4, C5.5, C5.6
     C7.7 + C3 events + C4.5 events ──► C5.7 (SSE)
     C5.3..C5.7 ──► C5.8 (HappyPathSmokeTest)

P8:  C7.2 ──► C6.1 ──► C6.2 ──► C6.3, C6.4
     C6.4 ──► C6.5
     C6.2 ──► C6.6 ──► C6.7, C6.8 ──► C6.9 ──► C6.10
     C6.{1..10} ──► C6.11 (coverage gate)

P9:  C5.5 + C5.8 + C6.11 + C7.4 + C7.5 + C7.6 ──► C7.10 (Stop hook)
     C7.10 ──► C7.11 (CI)
     C6.11 + C7.9 ──► C6.12 (Playwright)
     Everything ──► C7.12 (README)
```

### What can run in parallel

**P6 fan-out (the longest parallelism opportunity).** Once `C1.8` (catalogs) + `C7.4` (V1) + `C7.7` (event bus) are merged, three streams open:
- C2 stream: C2.1 → C2.2 → C2.4 → C2.5 (5 tasks).
- C3 stream: C3.2/C3.3/C3.4/C3.5 → C3.6/C3.7 → C3.8 → C3.9 → C3.10/C3.11 (~11 tasks).
- C4 stream: C4.2 → C4.4 → C4.5 → C4.6/C4.7/C4.8 → C4.9/C4.10 (~10 tasks).

The streams share two synchronisation points: C3.8 needs `C4.2`'s `Document` entity and `DocumentWriter`; `C4.5` calls `C3.8`'s `LlmExtractor.extract`. Sequence: **C4.2 first → C3.8 → C4.5**. C4.6 and C4.7 also depend on C3 event records (C3.8/C3.9) being defined first. Otherwise the streams are independent.

**P8 fan-out.** Within C6, the sub-trees rooted at C6.4 (Dashboard) and C6.6 (Detail) are independent down to C6.10 — two implementers (or two passes by one) can fan out.

---

## 4. Tasks

Per-component sections below. Cross-component dep IDs are resolved to specific tasks; integration plan §3.3 is the source for event payload shapes.

---

### C7 — Platform & Quality

> Foundation: every other component depends on C7's scaffolding. Tasks land in P0..P4 and P9. Sequence them first because the build, the migration, and the event bus are inputs to everything else.

#### C7.1 — Repo scaffolding: Makefile, docker-compose, .env, .gitignore                  [P0]
**What:** Create `Makefile` at repo root with targets `build`, `start`(=`up`), `stop`(=`down`), `test`, `e2e`, `eval` per spec §3.1; `docker-compose.yml` with backend + frontend + Postgres on a shared network and Vite proxy contract; `.env.example` documenting `ANTHROPIC_API_KEY`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `STORAGE_ROOT`; `.gitignore` excluding `.env`.
**Spec refs:** C7-R1, C7-R2, C7-R12, C7-R14; c7-platform-spec.md §3.1, §4.
**Deliverables:**
- `Makefile`, `docker-compose.yml`, `.env.example`, `.gitignore`.
**Acceptance criteria:**
- AC1: `cp .env.example .env && make build && make start` brings the stack up with no host-side setup beyond Docker.
- `docker compose ps` shows three services after `make start`.
- `git check-ignore .env` returns the file; `.PHONY` declared on every target; aliases `up`/`down` resolve to canonical targets.
**Within-component deps:** none.
**Cross-component deps:** none.
**Size:** 1 day.

#### C7.2 — Backend Gradle skeleton + frontend npm skeleton                              [P0]
**What:** `backend/build.gradle.kts` + `backend/settings.gradle.kts` as a single-module Spring Boot project with `java`, `org.springframework.boot`, `io.spring.dependency-management` plugins (quality-gate plugins deferred to C7.5). `frontend/package.json` + minimal Vite + TS skeleton. `frontend/vitest.config.ts` placeholder coverage threshold (`0` + `// TODO(research): set coverage threshold`).
**Spec refs:** C7-R6, C7-R6a; c7-platform-spec.md §3.2.
**Deliverables:**
- `backend/build.gradle.kts`, `backend/settings.gradle.kts`, `frontend/package.json`, `frontend/vitest.config.ts`.
**Acceptance criteria:**
- `./gradlew help` and `npm --prefix frontend run --silent` succeed on a clean checkout.
- `frontend/vitest.config.ts` contains `// TODO(research): set coverage threshold`; `test.coverage.thresholds` key present.
- `npm run build` wired to fail on ESLint warnings, Prettier `--check`, TypeScript strict, Vitest threshold (gates filled in by C6.11).
**Within-component deps:** C7.1.
**Cross-component deps:** C6.11 sets the actual frontend coverage numbers.
**Size:** 1 day.

#### C7.3 — `AppConfig` typed config tree + `application.yml`                            [P1]
**What:** Implement `com.docflow.config.AppConfig` as a record with nested `Llm`, `Storage`, `Database`, `OrgConfigBootstrap` records per integration §3.2, bound via `@ConfigurationProperties("docflow")` + `@Validated` with Jakarta constraints. `application.yml` enables `spring.threads.virtual.enabled=true`, Flyway config, `docflow.*` tree. Pin `docflow.llm.modelId = "claude-sonnet-4-6"`. Test `MissingApiKeyStartupTest`.
**Spec refs:** C7-R13; c7-platform-spec.md §3, §4; integration §3.2.
**Deliverables:**
- `backend/src/main/java/com/docflow/config/AppConfig.java`.
- `backend/src/main/resources/application.yml`.
- `backend/src/test/java/com/docflow/config/MissingApiKeyStartupTest.java`.
**Acceptance criteria:**
- AC7: blanking `ANTHROPIC_API_KEY` fails startup with an error citing `docflow.llm.apiKey` (verified by `MissingApiKeyStartupTest`) — never a runtime 500.
- `BindValidationException` fires before `ApplicationContext` refresh.
**Within-component deps:** C7.2.
**Cross-component deps:** Hosts nested records contributed by C1.6 (`OrgConfigBootstrap`), C2.2 (`Storage` field shape), C3.6 (`Llm` field shape). Field shapes confirmed by integration §3.2.
**Size:** 1 day.

#### C7.4 — Flyway baseline `V1__init.sql` (assembly)                                    [P3]
**What:** Single baseline migration assembled from C1.5 + C2.3 + C3.1 + C4.3 fragments in the canonical order: client-data tables (C1) → `stored_documents` (C2) → `processing_documents` (C3) → `documents` + `workflow_instances` (C4) → `llm_call_audit` (C3) including the CHECK constraint on `(processing_document_id IS NULL) <> (document_id IS NULL)`. FKs and indexes everywhere. `FlywayBaselineTest` asserts only `V1__init.sql` is present at scaffold time.
**Spec refs:** C7-R3; c7-platform-spec.md §3.3, §4; integration §3.1.
**Deliverables:**
- `backend/src/main/resources/db/migration/V1__init.sql`.
- `backend/src/test/java/com/docflow/platform/FlywayBaselineTest.java`.
**Acceptance criteria:**
- AC4: empty DB → migration applies cleanly; previously-migrated DB is no-op; editing `V1__init.sql` after apply causes Flyway checksum failure on next start.
- `find db/migration -name 'V*.sql'` lists only `V1__init.sql`.
- `processing_documents` and `llm_call_audit` tables exist with all columns, FKs, indexes, and CHECK constraints.
- `documents` has `(organization_id, processed_at DESC)` and UNIQUE `(stored_document_id)` indexes; `workflow_instances` has `(organization_id, current_status, updated_at DESC)` and UNIQUE `(document_id)` indexes.
- `current_status` CHECK enumerates `AWAITING_REVIEW, FLAGGED, AWAITING_APPROVAL, FILED, REJECTED`; `reextraction_status` CHECK enumerates `NONE, IN_PROGRESS, FAILED`.
**Within-component deps:** C7.3.
**Cross-component deps:** Stitches fragments from **C1.5, C2.3, C3.1, C4.3**.
**Size:** 1 day (assembly only — fragments are owned by other tasks).

#### C7.5 — Quality gate plugins: Spotless, Checkstyle, PMD, JaCoCo, Error Prone        [P4]
**What:** Wire backend quality plugins per spec §3.2 / §3.6: Spotless `googleJavaFormat()`, Checkstyle 10.x with `backend/config/checkstyle/checkstyle.xml`, PMD 7.22.0 with `backend/config/pmd/pmd-ruleset.xml`, JaCoCo `minimum = 0.70` line coverage and exclusions in `backend/config/jacoco/exclusions.txt`, Error Prone (replacing SpotBugs per analysis §4.3). Wire `jacocoTestCoverageVerification` into `check`.
**Spec refs:** C7-R5; c7-platform-spec.md §3.2, §3.6.
**Deliverables:**
- `backend/build.gradle.kts` plugin block + config.
- `backend/config/checkstyle/checkstyle.xml`, `backend/config/pmd/pmd-ruleset.xml`, `backend/config/jacoco/exclusions.txt`.
**Acceptance criteria:**
- AC2 (partial): `./gradlew check` returns 0 on a clean tree; injecting Spotless / Checkstyle / PMD / JaCoCo<70% violations each independently fail the build.
- Error Prone is the static-analysis backbone (no SpotBugs).
**Within-component deps:** C7.2.
**Cross-component deps:** none.
**Size:** 1 day.

#### C7.6 — `grepForbiddenStrings` Gradle task + tests                                   [P4]
**What:** Kotlin DSL task per spec §3.6: reads `config/forbidden-strings.txt` at execution time, fails fast if file missing/unreadable/has zero usable patterns. Scans `backend/src/main/java/**/*.java`. Allow-lists `com.docflow.config` for env-read patterns; stage names + client slugs forbidden everywhere. Wired into `check`. Tests `GrepForbiddenStringsFileTest` (fail-fast on missing/empty file).
**Spec refs:** C7-R5, C7-R13; c7-platform-spec.md §3.6.
**Deliverables:**
- `backend/build.gradle.kts` (`grepForbiddenStrings` task).
- `backend/src/test/java/com/docflow/config/GrepForbiddenStringsFileTest.java`.
**Acceptance criteria:**
- AC6: rejects literal stage name in service, client slug in switch, `System.getenv` outside config, `@Value` outside `com.docflow.config`; accepts `System.getenv` inside config.
- Task fails fast (with clear error) when `config/forbidden-strings.txt` is missing or has only comments/blanks.
**Within-component deps:** C7.5.
**Cross-component deps:** **C1.9** owns `config/forbidden-strings.txt` (the literal pattern list) and the positive/negative regex fixture test.
**Size:** 1 day.

#### C7.7 — `DocumentEventBus` + `AsyncConfig` + event record skeletons                  [P4]
**What:** Implement `com.docflow.platform.DocumentEventBus` wrapping Spring's `ApplicationEventPublisher` (synchronous publish; async dispatch on virtual threads via `@EnableAsync`). `AsyncConfig` registers an `AsyncUncaughtExceptionHandler` logging at ERROR. Define the `DocumentEvent` interface (non-sealed for incremental contribution) and skeleton record bodies for the six concrete events with payloads from integration §3.3, so producers and consumers can compile against a stable contract before downstream tasks begin. Test `DocumentEventBusTest`.
**Spec refs:** C7-R11; c7-platform-spec.md §3, §4; integration §3.3.
**Deliverables:**
- `backend/src/main/java/com/docflow/platform/DocumentEventBus.java`.
- `backend/src/main/java/com/docflow/platform/AsyncConfig.java`.
- `backend/src/main/java/com/docflow/platform/DocumentEvent.java`.
- Skeleton records under `com.docflow.c3.events` (`StoredDocumentIngested`, `ProcessingStepChanged`, `ProcessingCompleted`, `ExtractionCompleted`, `ExtractionFailed`) and `com.docflow.workflow.events.DocumentStateChanged` — record bodies only; producers and listeners arrive in their owning tasks.
- `backend/src/test/java/com/docflow/platform/DocumentEventBusTest.java`.
**Acceptance criteria:**
- AC8: `publish(event)` returns synchronously; `@Async @EventListener` runs on a virtual thread and observes the event.
- Async exception thrown in a listener is logged at ERROR (not swallowed).
- All six concrete record types are defined and implement `DocumentEvent`.
**Within-component deps:** C7.3.
**Cross-component deps:** Producers / consumers are completed by C2.4 (`StoredDocumentIngested`), C3.9 (`ProcessingStepChanged` + `ProcessingCompleted`), C3.8 (`ExtractionCompleted` + `ExtractionFailed`), C4.5/C4.6/C4.7 (`DocumentStateChanged`).
**Size:** 1 day.

#### C7.8 — Seed manifest YAML + 12 PDF resources                                        [P6]
**What:** Author `backend/src/main/resources/seed/manifest.yaml` with the 12 entries enumerated in spec §3.7 (paths, `organizationId`, `documentType`, ground-truth `extractedFields` filled by reading the PDFs). Copy 12 source PDFs from `problem-statement/samples/` into `backend/src/main/resources/seed/files/` mirroring relative paths (`problem-statement/` stays read-only).
**Spec refs:** C7-R4; c7-platform-spec.md §3.7.
**Deliverables:**
- `backend/src/main/resources/seed/manifest.yaml`.
- `backend/src/main/resources/seed/files/**/*.pdf` (12 files).
**Acceptance criteria:**
- Manifest covers all 9 (org × doc-type) buckets ≥ once and both Lien Waiver `waiverType` values.
- 12 entries; selection lexicographically deterministic per bucket.
- All referenced PDF paths resolve.
**Within-component deps:** none.
**Cross-component deps:** none.
**Size:** 1 day.

#### C7.9 — Seed loader + `SeedManifestTest`                                              [P6]
**What:** Application-data seeder running as `@EventListener(ApplicationReadyEvent)` after `C1.7 OrgConfigSeeder` (per integration §2 step 7). For each manifest entry, in **one transaction**, INSERT `StoredDocument` + `Document` (`processedAt = now`, `reextractionStatus = NONE`) + `WorkflowInstance` (`currentStageId = Review`, `currentStatus = AWAITING_REVIEW`). Idempotent on `(organizationId, sourcePath)`. No LLM call. `SeedManifestTest` compares post-seed `documents` rows to manifest.
**Spec refs:** C7-R4; c7-platform-spec.md §3.7; integration §2 (step 7), §5.4.
**Deliverables:**
- `backend/src/main/java/com/docflow/platform/SeedDataLoader.java`.
- `backend/src/test/java/com/docflow/platform/SeedManifestTest.java`.
**Acceptance criteria:**
- AC5: `documents` rows match exactly the 12 manifest entries (org, type, fields). No `ProcessingDocument` rows for seeded data.
- Re-running on a populated DB is a no-op (idempotent).
- Missing PDF → seeder fails at startup with clear "seed file not found" before any partial inserts.
- Whole-seeder failure rolls all entries back.
**Within-component deps:** C7.4, C7.7, C7.8.
**Cross-component deps:** **C1.7** (org config seeded first), **C1.8** (catalogs ready), **C2.1** (`StoredDocumentStorage` for file payloads, **C2.2** filesystem impl), **C4.2** (`Document` + `WorkflowInstance` entities).
**Size:** 2 days.

#### C7.10 — Stop hook configuration                                                     [P9]
**What:** `.claude/settings.json` Stop hook running `cd "$CLAUDE_PROJECT_DIR" && make test` (timeout 600s) per spec §3.4. Verify hook fires and that injecting any quality-gate violation blocks the agent's "done" signal.
**Spec refs:** C7-R7; c7-platform-spec.md §3.4.
**Deliverables:**
- `.claude/settings.json`.
**Acceptance criteria:**
- AC3: hook fires; injecting Spotless / `grepForbiddenStrings` / JaCoCo violations each independently block the "done" signal.
**Within-component deps:** C7.1, C7.5, C7.6.
**Cross-component deps:** every backend / frontend task whose tests are run by `make test`.
**Size:** half-day.

#### C7.11 — CI workflow                                                                 [P9]
**What:** `.github/workflows/ci.yml` per spec §3.5: trigger on push/PR; single `verify` job on `ubuntu-latest`; `setup-java@v4` Temurin 25, `setup-node@v4` LTS; steps run `make test`, `make build`, `make start` (background) + healthcheck wait, `make e2e`, `make stop` (always-run). `make eval` is **not** invoked.
**Spec refs:** C7-R10; c7-platform-spec.md §3.5.
**Deliverables:**
- `.github/workflows/ci.yml`.
**Acceptance criteria:**
- AC9: green PR → green CI; PR injecting an AC2 failure → red CI.
- AC10: `make e2e` runs in CI; `make eval` is reachable but never invoked from `make test` or CI.
**Within-component deps:** C7.1, C7.10.
**Cross-component deps:** **C6.12** (`make e2e` Playwright suite must exist for the CI step to run).
**Size:** half-day.

#### C7.12 — README                                                                       [P9]
**What:** Write `README.md` per spec §4 / C7-R9: how to run, design decisions (no-auth, local FS vs S3, Java/Spring versions, Anthropic, SSE), how seeded data is loaded on first boot only, how to run `make eval`, how to add labeled samples, "Production considerations" enumerated in 03-components.md C7-R9. Production-considerations content is scoped per the project's memory note (no unprompted operational color).
**Spec refs:** C7-R9; c7-platform-spec.md §4.
**Deliverables:**
- `README.md`.
**Acceptance criteria:**
- Instructions match the actual run sequence verified in C7.1.
- All required sections present.
- `AGENTS.md` + `CLAUDE.md` symlink already in place — no work for C7-R8.
**Within-component deps:** C7.1, C7.9, C7.11.
**Cross-component deps:** none.
**Size:** half-day.

---

### C1 — Config Layer

> Owns the declarative org / doc-type / workflow definitions and the read-side catalogs every other component consumes. Tasks land in P2..P5.

#### C1.1 — Define `OrgConfig` records, enums, and guard evaluator                       [P2]
**What:** Create the `com.docflow.config.org` record/enum set: `OrgConfig`, `OrganizationDefinition`, `DocTypeDefinition`, `InputModality`, `FieldDefinition`, `FieldType`, `ArrayItemSchema`, `WorkflowDefinition`, `StageDefinition`, `StageKind`, `WorkflowStatus`, `TransitionDefinition`, `TransitionAction`, `StageGuardConfig`, `GuardOp`. Implement `StageGuardConfig.evaluate(Map<String,Object>)` with EQ/NEQ semantics including null-handling per spec §7.
**Spec refs:** C1-R1, C1-R2, C1-R3, C1-R4, C1-R4a, C1-R10, C1-R12; c1-config-spec.md §3.2, §3.3.
**Deliverables:**
- All record/enum files under `backend/src/main/java/com/docflow/config/org/`.
- `backend/src/test/java/com/docflow/config/org/StageGuardConfigTest.java`.
**Acceptance criteria:**
- AC-L4 (role nullability matrix), AC-L5 (`WorkflowStatus` order), AC-L6 (`InputModality` values), guard-eval table from §7 covered by test.
**Within-component deps:** none.
**Cross-component deps:** none.
**Size:** 1 day.

#### C1.2 — Implement `ConfigLoader` (Jackson 3 YAML → records)                          [P5]
**What:** Build `ConfigLoader` using `tools.jackson.dataformat.yaml.YAMLMapper` with Jakarta `@Valid` propagation. Resolve `classpath:seed/` to read `organizations.yaml`, `doc-types/{org}/{type}.yaml`, `workflows/{org}/{type}.yaml`, assemble into a single `OrgConfig`. Wrap Jackson failures in `ConfigLoadException` with file+line context.
**Spec refs:** C1-R1, C1-R2, C1-R3, C1-R4; c1-config-spec.md §3.1, §4.1, §4.5, §7.
**Deliverables:**
- `backend/src/main/java/com/docflow/config/org/loader/ConfigLoader.java`, `ConfigLoadException.java`.
- Jackson YAML + spring-boot-starter-validation deps in `build.gradle.kts`.
- `backend/src/test/java/com/docflow/config/org/loader/ConfigLoaderTest.java`.
**Acceptance criteria:**
- AC-L1 (3 orgs / 9 doc-types / 9 workflows), AC-L2 (Riverside Invoice line-items), AC-L3 (Lien Waiver guard pair), invalid YAML rewraps as `ConfigLoadException`.
**Within-component deps:** C1.1.
**Cross-component deps:** none (depends on seed YAML from C1.4 for happy-path integration tests, but loader can be unit-tested earlier with synthetic strings).
**Size:** 1 day.

#### C1.3 — Implement `ConfigValidator` (CV-1..CV-8)                                     [P5]
**What:** Hand-rolled cross-reference validator that accumulates all failures and throws once via `ConfigValidationException`. Implements all eight checks from §3.4 plus the kind→canonicalStatus compatibility table.
**Spec refs:** C1-R5, C1-R12; c1-config-spec.md §3.4.
**Deliverables:**
- `backend/src/main/java/com/docflow/config/org/validation/ConfigValidator.java`, `ConfigValidationException.java`.
- `backend/src/test/java/com/docflow/config/org/validation/ConfigValidatorTest.java`.
- Tampered fixtures `backend/src/test/resources/validator-fixtures/cv-1..cv-8/`.
**Acceptance criteria:**
- AC-V1..AC-V8 each fixture triggers exactly its check; AC-V9 multi-error message accumulation.
**Within-component deps:** C1.1, C1.2.
**Cross-component deps:** none.
**Size:** 1 day.

#### C1.4 — Author seed YAML fixtures (3 orgs × 3 doc-types)                              [P5]
**What:** Author the nine doc-type schemas, three org definitions, and nine workflows in YAML, mirroring `02-analysis.md` §1.1/§1.2. Set `inputModality: PDF` on the four nested-array doc-types and inherit `TEXT` on the rest. Ironworks Lien Waiver workflow uses two inverse `waiverType`-eq guards out of `Review`.
**Spec refs:** C1-R2, C1-R8, C1-R10; c1-config-spec.md §4.4.
**Deliverables:**
- `backend/src/main/resources/seed/organizations.yaml`.
- `backend/src/main/resources/seed/doc-types/{riverside-bistro,pinnacle-legal,ironworks-construction}/*.yaml` (9 files).
- `backend/src/main/resources/seed/workflows/{riverside-bistro,pinnacle-legal,ironworks-construction}/*.yaml` (9 files).
**Acceptance criteria:**
- `ConfigLoader.load("classpath:seed/")` parses without error and `ConfigValidator.validate` returns silently. AC-L1, AC-L2, AC-L3, AC-L6 green.
**Within-component deps:** C1.1, C1.2, C1.3.
**Cross-component deps:** none.
**Size:** 1 day.

#### C1.5 — JPA entities, repositories, and migration fragment                            [P3]
**What:** Define `OrganizationEntity`, `OrganizationDocTypeEntity`, `DocumentTypeEntity` (with `field_schema JSONB`), `WorkflowEntity`, `StageEntity`, `TransitionEntity` with composite PKs, FK indexes, and Spring Data repositories. Author the SQL fragment `c1-org-config.sql` containing CREATE TABLE / FK / index / CHECK statements that C7.4 stitches into `V1__init.sql`.
**Spec refs:** C1-R11; c1-config-spec.md §4.2, §4.3.
**Deliverables:**
- Entities + repositories under `backend/src/main/java/com/docflow/config/persistence/`.
- `backend/src/main/resources/db/migration/fragments/c1-org-config.sql`.
**Acceptance criteria:**
- Every FK has an index; `idx_stages_workflow` present; CHECK constraints on `kind`, `canonical_status`, `action`, `guard_op`, `input_modality`.
- Repositories load against Postgres Testcontainer without schema errors (gated on **C7.4** assembly).
**Within-component deps:** C1.1.
**Cross-component deps:** Fragment stitched into `V1__init.sql` by **C7.4**.
**Size:** 1 day.

#### C1.6 — Contribute `OrgConfigBootstrap` to `AppConfig` + `application.yml` keys      [P1]
**What:** Author the nested `OrgConfigBootstrap(boolean seedOnBoot, String seedResourcePath)` record contributed to C7's `AppConfig`. Add `app.config.seedOnBoot: true` (dev) and `app.config.seedResourcePath: classpath:seed/` to `application.yml`. No `@Value` / `System.getenv` reads anywhere in `com.docflow.config.org.*`.
**Spec refs:** C1-R11; c1-config-spec.md §3.8, §4.5; SPEC.md C7-R13.
**Deliverables:**
- Record-shape contribution merged into `backend/src/main/java/com/docflow/config/AppConfig.java`.
- Keys added to `backend/src/main/resources/application.yml`.
**Acceptance criteria:**
- AC-AC1 — no `System.getenv` / `@Value` / `.env` references inside `com.docflow.config.org.*`. `seedOnBoot` defaults to `true` in dev profile, `false` in prod profile.
**Within-component deps:** none.
**Cross-component deps:** **C7.3** owns `AppConfig.java`; this task contributes the nested record type.
**Size:** half-day.

#### C1.7 — `OrgConfigSeeder` + `OrgConfigSeedWriter`                                    [P5]
**What:** Spring `@Component` `OrgConfigSeeder` listening on `ApplicationReadyEvent`: short-circuit when `seedOnBoot=false` or `organizations` non-empty; otherwise call `ConfigLoader`, `ConfigValidator`, then `OrgConfigSeedWriter.persist(parsed)` in a single `@Transactional` insert in dependency order (orgs → doc-types → workflows → stages → transitions, with ordinal columns).
**Spec refs:** C1-R8, C1-R9, C1-R11; c1-config-spec.md §3.7.
**Deliverables:**
- `backend/src/main/java/com/docflow/config/org/seeder/OrgConfigSeeder.java`, `OrgConfigSeedWriter.java`.
- `backend/src/test/java/com/docflow/config/org/seeder/OrgConfigSeederTest.java`.
**Acceptance criteria:**
- AC-S1 (3/9/9/≥27/≥36 row counts), AC-S2 (idempotent on second boot), AC-S3 (`seedOnBoot=false` issues no INSERT). Logs `OrgConfigSeeder: seeded ...` on first run, `seed skipped` on subsequent.
**Within-component deps:** C1.2, C1.3, C1.4, C1.5, C1.6.
**Cross-component deps:** **C7.4** for assembled `V1__init.sql`. Consumed by **C7.9** (application-data seeder runs after this via `@DependsOn`).
**Size:** 1 day.

#### C1.8 — Catalog beans (Organization / DocumentType / Workflow)                        [P5]
**What:** Implement the three catalog interfaces, their `*Impl` beans, `*View` projection records. Each bean `@DependsOn("orgConfigSeeder")`, JPA-loads once into immutable in-memory views, exposes the read API. `OrganizationCatalog.listOrganizations` preserves ordinal order from `organizations.yaml`.
**Spec refs:** C1-R6, C1-R11; c1-config-spec.md §3.5.
**Deliverables:**
- Interfaces, impls, view records under `backend/src/main/java/com/docflow/config/catalog/`.
- `backend/src/test/java/com/docflow/config/catalog/OrganizationCatalogIT.java` (Testcontainers).
**Acceptance criteria:**
- AC-C1..AC-C6 all green. No JPA entity types leak across the package boundary; consumers depend on `*View` records only.
**Within-component deps:** C1.5, C1.7.
**Cross-component deps:** Consumed downstream by C2.4 (`OrganizationCatalog.getOrganization`), C3.4 / C3.5 / C3.7 / C3.8 (`DocumentTypeCatalog`, `OrganizationCatalog.getAllowedDocTypes`), C4.2 / C4.4 / C4.5 / C4.6 / C4.7 (`WorkflowCatalog` constructor-injected per C4-R9a), C5.3 (org listing), C5.6 (allowed-doc-type validation).
**Size:** 1 day.

#### C1.9 — Author `forbidden-strings.txt` + grep proof test                              [P4]
**What:** Commit `config/forbidden-strings.txt` enumerating the eleven stage-name literals and three client slugs (with display variants) from §3.6. Write `GrepForbiddenStringsTest` that plants a `Bad.java` fixture containing `"Manager Approval"` and asserts the grep task fails, plus a control fixture using `WorkflowStatus.FILED` that does not.
**Spec refs:** C1-R7; c1-config-spec.md §3.6.
**Deliverables:**
- `config/forbidden-strings.txt`.
- `backend/src/test/java/com/docflow/config/org/GrepForbiddenStringsTest.java`.
- Fixtures under `backend/src/test/resources/grep-fixtures/`.
**Acceptance criteria:**
- AC-G1 (literal violation detected with file/line), AC-G2 (enum reference passes).
**Within-component deps:** none.
**Cross-component deps:** **C7.6** owns the Gradle task that consumes this file.
**Size:** half-day.

#### C1.10 — Fourth-org extensibility test                                                [P5]
**What:** Stage a synthetic fourth-org YAML bundle (`seed-fourth-org/organizations.yaml` + one doc-type + one workflow) under `backend/src/test/resources/`, run the seeder against an empty Postgres Testcontainer pointed at this resource path, assert catalog APIs return four orgs, ten doc-types, ten workflows. No production-source change required outside `com.docflow.config.org.*`.
**Spec refs:** C1-R9; c1-config-spec.md §4.6.
**Deliverables:**
- `backend/src/test/java/com/docflow/config/extensibility/FourthOrgSeederTest.java`.
- `backend/src/test/resources/seed-fourth-org/**`.
**Acceptance criteria:**
- AC-E1 — seeder loads the fourth org and catalog reads return it; no production-source change.
**Within-component deps:** C1.7, C1.8.
**Cross-component deps:** **C7.4** for assembled `V1__init.sql` schema.
**Size:** half-day.

---

### C2 — Document Ingestion & Storage

> Accepts uploads, persists bytes via the `StoredDocumentStorage` seam, transactionally writes the immutable `StoredDocument` + initial `ProcessingDocument`, and signals C3. Tasks land in P2..P6.

#### C2.1 — Define `StoredDocument` aggregate, IDs, and public seam interfaces            [P2]
**What:** Create the immutable `StoredDocument` record, `StoredDocumentId` wrapper, and the three public-facing interfaces (`StoredDocumentReader`, `StoredDocumentIngestionService`, `StoredDocumentStorage`) under `com.docflow.ingestion`. Wire the UUIDv7 generator (`com.github.f4b6a3:uuid-creator`) and `IngestionResult { storedDocumentId, processingDocumentId }`.
**Spec refs:** C2-R4, C2-R7; c2-ingestion-spec.md §3.2, §3.3, §3.4, §3.6.
**Deliverables:**
- `StoredDocument.java`, `StoredDocumentId.java`, `StoredDocumentReader.java`, `StoredDocumentIngestionService.java` under `com.docflow.ingestion`.
- `StoredDocumentStorage.java` under `com.docflow.ingestion.storage`.
- Gradle dep on `com.github.f4b6a3:uuid-creator`.
**Acceptance criteria:**
- AC-IMMUTABILITY: record has no mutators; interfaces have no `update*` methods.
- Public types in `ingestion` root match §3.2 boundary list exactly; everything else lives under `internal/` or `storage/`.
**Within-component deps:** none.
**Cross-component deps:** none.
**Size:** half-day.

#### C2.2 — Implement `FilesystemStoredDocumentStorage` with atomic-move                  [P6]
**What:** Implement the single filesystem `StoredDocumentStorage` impl resolving `{storageRoot}/{id}.bin` via NIO. `save` writes a tmp file in the same dir then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`. `load` throws `StoredFileNotFoundException` on missing file. `storageRoot` from `AppConfig.Storage` constructor injection.
**Spec refs:** C2-R2, C2-R7, C2-R9a; c2-ingestion-spec.md §3.4.
**Deliverables:**
- `FilesystemStoredDocumentStorage.java`, `StoredFileNotFoundException.java` under `com.docflow.ingestion.storage`.
- `FilesystemStoredDocumentStorageTest.java`.
**Acceptance criteria:**
- AC-R2: round-trip save/load returns identical bytes; final file at `{storageRoot}/{id}.bin`.
- `load(id)` of missing file → `StoredFileNotFoundException`.
- AC-R7: in-memory `StoredDocumentStorage` substitutes with no production-code change.
**Within-component deps:** C2.1.
**Cross-component deps:** **C7.3** contributes `AppConfig.Storage(storageRoot)`; the `Storage` field shape is contributed here per integration §3.2.
**Size:** half-day.

#### C2.3 — `stored_documents` migration fragment + JPA entity                            [P3]
**What:** Contribute the `stored_documents` table DDL and `(organization_id, uploaded_at DESC)` index fragment to C7.4. Add the package-private `StoredDocumentEntity`, `StoredDocumentEntityRepository`, and `StoredDocumentJpaReader` mapping entity → `StoredDocument` record.
**Spec refs:** C2-R4a, C2-R5, C2-R9; c2-ingestion-spec.md §3.2, §4.2.
**Deliverables:**
- DDL fragment merged into `backend/src/main/resources/db/migration/V1__init.sql` (assembled by C7.4).
- `StoredDocumentEntity.java`, `StoredDocumentEntityRepository.java`, `StoredDocumentJpaReader.java` under `com.docflow.ingestion.internal`.
**Acceptance criteria:**
- AC-R9: `flywayInfo` shows V1 applied; `information_schema` confirms columns + index.
- AC-IMMUTABILITY: entity has no setters; repository has no `update*`/`save*` beyond single insert path.
- AC-R5: `StoredDocumentReader.get(id)` returns rows regardless of org.
**Within-component deps:** C2.1.
**Cross-component deps:** **C7.4** assembles `V1__init.sql`; depends on C1.5 (organizations table for FK target) being ordered before this fragment in V1.
**Size:** 1 day.

#### C2.4 — Tika MIME sniffing + upload orchestrator                                      [P6]
**What:** Implement `StoredDocumentIngestionServiceImpl`. Wire Tika as singleton bean, sniff bytes against allowed set (`application/pdf`, `image/png`, `image/jpeg`) with `application/octet-stream` → claimed-content-type fallback. Orchestrate full upload per §3.7: org-validation → MIME sniff → FS write → DB transaction → post-commit event publish. **Atomic-move FS write must complete before DB transaction begins** (C2-R9a). The `StoredDocumentIngested` event publishes only after commit. Transaction inserts both `stored_documents` AND initial `processing_documents` row (`current_step = TEXT_EXTRACTING`, denormalized `organization_id`).
**Spec refs:** C2-R1, C2-R4, C2-R9a; c2-ingestion-spec.md §3.1, §3.5, §3.7, §3.8.
**Deliverables:**
- `StoredDocumentIngestionServiceImpl.java` under `com.docflow.ingestion.internal`.
- Tika `Detector` bean configuration.
- `StoredDocumentIngestionServiceImplTest.java`.
- Fixtures: `sample-invoice.pdf`, `not-a-pdf.txt`, `zero-bytes.bin` under `backend/src/test/resources/fixtures/`.
**Acceptance criteria:**
- AC-R1: PDF → 200 with both UUIDs; `text/csv` → 415; PDF body with `Content-Type: text/plain` accepted.
- AC-R4: both rows present after success; fault-injection between FS write and commit leaves orphan file but no DB rows; fault-injection between two INSERTs rolls both back.
- AC-R9a: file at `{storageRoot}/{id}.bin` exists *before* the row commits.
- AC-ORG-VALIDATION: unknown `orgId` → no FS file, no DB row, 404.
- AC-CORRUPT-PDF: zero-byte body → 415.
**Within-component deps:** C2.1, C2.2, C2.3.
**Cross-component deps:** **C1.8** (`OrganizationCatalog.getOrganization`); **C3.1** (`processing_documents` schema fragment, assembled into V1 by C7.4); **C7.4** (V1 must apply); **C7.7** (`DocumentEventBus` + `StoredDocumentIngested` record skeleton).
**Size:** 2 days.

#### C2.5 — Integration test: full upload round-trip                                      [P6]
**What:** `@SpringBootTest` with Postgres Testcontainer and tmpfs storage root. Drives `StoredDocumentIngestionService.upload` end-to-end: row persisted with all six columns, file present, `processing_documents` row with `current_step = TEXT_EXTRACTING`, exactly one `StoredDocumentIngested` event observed *after* commit. Boundary-grep verifies no `DELETE` mapping anywhere in `com.docflow.ingestion`.
**Spec refs:** C2-R5, C2-R6; c2-ingestion-spec.md §6.
**Deliverables:**
- `StoredDocumentIngestionIntegrationTest.java`.
- Boundary-grep verification step (Spotless/Checkstyle config or shell test).
**Acceptance criteria:**
- AC-R6: no route matches `DELETE /api/.../documents/{id}`.
- AC-EVENT: exactly one `StoredDocumentIngested` post-commit per upload.
- AC-R7: in-memory `StoredDocumentStorage` substitutes cleanly in a sibling test.
- Boundary check: nothing outside `com.docflow.ingestion` references `ingestion.internal`.
**Within-component deps:** C2.4.
**Cross-component deps:** **C7.7** (`DocumentEventBus`); **C7.4** (V1 schema); **C1.7** (seeded organizations for FK validation).
**Size:** 1 day.

---

### C3 — Processing Pipeline & Eval

> Orchestrates `ProcessingDocument` through `TEXT_EXTRACTING → CLASSIFYING → EXTRACTING`, audits every LLM call, exposes `LlmExtractor.extract` for retype, ships the on-demand eval harness. Largest backend component. Tasks land in P3..P6.

#### C3.1 — `processing_documents` + `llm_call_audit` migration fragments                 [P3]
**What:** Contribute the `processing_documents` and `llm_call_audit` `CREATE TABLE` SQL (columns, FKs, indexes, CHECK constraints — including the mutual-exclusivity CHECK on the audit table) to C7.4. Order ensures `documents` precedes `llm_call_audit` so the `document_id` FK resolves.
**Spec refs:** C3-R5a, C3-R14; c3-pipeline-spec.md §3.7, §4.
**Deliverables:**
- SQL fragments merged into `V1__init.sql` (assembled by C7.4).
- `backend/src/test/java/com/docflow/c3/audit/LlmCallAuditCheckConstraintIT.java`.
**Acceptance criteria:**
- AC5: `processing_documents` and `llm_call_audit` tables exist after `flywayMigrate` with all columns, FKs, indexes, CHECK constraints.
- CHECK rejects rows with neither or both of `processing_document_id` / `document_id`.
**Within-component deps:** none.
**Cross-component deps:** **C7.4** assembles V1; **C2.3** (`stored_documents`) and **C4.3** (`documents`) must be ordered earlier in V1.
**Size:** half-day.

#### C3.2 — `ProcessingDocument` entity, writer, reader                                   [P6]
**What:** Implement `ProcessingDocument` JPA entity, `ProcessingDocumentWriter` (UPDATEs only — INSERT lives in C2.4's transaction), and `ProcessingDocumentReader`. Writer denormalizes `organization_id` from `StoredDocument.organizationId` to keep values consistent.
**Spec refs:** C3-R14, C3-R16; c3-pipeline-spec.md §3.2.
**Deliverables:**
- `ProcessingDocument.java`, `ProcessingDocumentWriter.java`, `ProcessingDocumentReader.java` under `com.docflow.c3.pipeline`.
- `ProcessingDocumentWriterTest.java`.
**Acceptance criteria:**
- Writer reads parent `StoredDocument` first and asserts `organization_id` matches before UPDATE.
- UPDATE of `current_step`, `raw_text`, `last_error` persists with no INSERT path exposed.
**Within-component deps:** C3.1.
**Cross-component deps:** **C2.1** (`StoredDocumentReader`); **C7.4** (V1).
**Size:** 1 day.

#### C3.3 — `LlmCallAudit` record + `LlmCallAuditWriter` (INSERT-only)                   [P6]
**What:** Build the immutable `LlmCallAudit` record and the INSERT-only writer. Writer accepts both call shapes (initial = `processing_document_id` set / `document_id` null; retype = inverse) and rejects malformed shapes pre-INSERT.
**Spec refs:** C3-R5a, C3-R16; c3-pipeline-spec.md §3.7, §3.8.
**Deliverables:**
- `LlmCallAudit.java`, `LlmCallAuditWriter.java`, `LlmCallAuditReader.java` under `com.docflow.c3.audit`.
- `LlmCallAuditWriterTest.java`.
**Acceptance criteria:**
- Writes one row per `(stored_document_id, call_type)` with correct nullable FK populated and the other null.
- Rejects mutually-inclusive shapes without round-tripping to the DB.
**Within-component deps:** C3.1.
**Cross-component deps:** **C7.4** (V1).
**Size:** half-day.

#### C3.4 — `PromptLibrary` with startup validation                                       [P6]
**What:** Implement `PromptLibrary` and `PromptTemplate`. Load `prompts/*.txt` resources at startup; validate every `(orgId, allowedDocType)` pair from C1 has `extract_<docType>.txt`. Missing prompt → startup failure. `String.replace`-based `{{ALLOWED_DOC_TYPES}}` substitution. Author 1 classify + 9 extract prompt files.
**Spec refs:** C3-R5; c3-pipeline-spec.md §3.3.
**Deliverables:**
- `PromptLibrary.java`, `PromptTemplate.java` under `com.docflow.c3.llm`.
- `classify.txt` + 9 `extract_<docType>.txt` under `backend/src/main/resources/prompts/`.
- `PromptLibraryTest.java`.
**Acceptance criteria:**
- `PromptLibrary.validate()` throws on missing prompt; deleting one file fails startup.
- Grep + load test confirms ten prompts resolve at startup.
**Within-component deps:** none.
**Cross-component deps:** **C1.8** (`OrganizationCatalog.getAllowedDocTypes`, `DocumentTypeCatalog.listDocumentTypes`). Order via `@DependsOn` per integration §2 step 6.
**Size:** 1 day.

#### C3.5 — `ToolSchemaBuilder` (deterministic) + `LlmException` hierarchy                [P6]
**What:** Implement `ToolSchemaBuilder` converting C1 doc-type field schema → Anthropic SDK `InputSchema` deterministically (declaration-order fields and enums). Build sealed `LlmException` hierarchy (`LlmTimeout`, `LlmProtocolError`, `LlmSchemaViolation`, `LlmUnavailable`).
**Spec refs:** C3-R6; c3-pipeline-spec.md §3.4, §3.6.
**Deliverables:**
- `ToolSchemaBuilder.java`, `LlmException.java` under `com.docflow.c3.llm`.
- `ToolSchemaBuilderTest.java`.
**Acceptance criteria:**
- Property test serializes the same C1 schema twice and asserts byte-equal output.
- Tool name `select_doc_type` for classify, `extract_<docType>` for extract.
**Within-component deps:** none.
**Cross-component deps:** **C1.8** (`DocumentTypeCatalog.getDocumentTypeSchema`).
**Size:** 1 day.

#### C3.6 — `AnthropicClientFactory` + LLM call lifecycle wrapper                          [P6]
**What:** Wire `AnthropicClientFactory` over `com.anthropic.client.AnthropicClient` using `AppConfig.Llm` (apiKey, requestTimeout). SDK built-in retry **disabled**. Per-call lifecycle: build params → SDK call → translate `AnthropicException` → extract `tool_use` or `LlmProtocolError`. Hybrid input modality (text-only vs PDF base64 inline) keyed by `inputModality` on `DocTypeDefinition`.
**Spec refs:** c3-pipeline-spec.md §3.5, §3.6.
**Deliverables:**
- `AnthropicClientFactory.java` under `com.docflow.c3.llm`.
- Helper for building `MessageCreateParams` with text-or-PDF content blocks.
**Acceptance criteria:**
- SDK built-in retry off (verifiable via factory config).
- 429/5xx → `LlmUnavailable`; I/O timeout → `LlmTimeout`; missing tool_use → `LlmProtocolError`.
**Within-component deps:** C3.5.
**Cross-component deps:** **C7.3** (`AppConfig.Llm` — the `Llm` field shape is contributed here per integration §3.2); **C2.2** (`StoredDocumentStorage.load` for PDF bytes).
**Size:** 1 day.

#### C3.7 — `LlmClassifier` (no retry)                                                    [P6]
**What:** Implement `LlmClassifier` using `AnthropicClientFactory`, `PromptLibrary`, `ToolSchemaBuilder`. Forced `tool_choice = ofTool(select_doc_type)`, `max_tokens = 512`, text input. Validate result against the org's allowed-doc-type enum; non-member → `LlmSchemaViolation`. **No retry.** Always writes one `llm_call_audit` row (success or failure).
**Spec refs:** C3-R1; c3-pipeline-spec.md §3.4, §3.6, §3.8.
**Deliverables:**
- `LlmClassifier.java`.
- `LlmClassifierTest.java`.
**Acceptance criteria:**
- Mocked SDK: success returns enum; non-enum result → `LlmSchemaViolation`; both paths INSERT one audit row.
**Within-component deps:** C3.3, C3.4, C3.5, C3.6.
**Cross-component deps:** **C1.8** (`OrganizationCatalog.getAllowedDocTypes`).
**Size:** half-day.

#### C3.8 — `LlmExtractor` (initial-pipeline + retype + retry)                            [P6]
**What:** Implement `LlmExtractor` covering both call sites. Forced `extract_<docType>` tool with deterministic schema; `max_tokens = 2048`. **One retry on `LlmSchemaViolation` only** (no retry on transport). Two surfaces: an internal call used by `ExtractStep` (returns extracted fields; does NOT touch `Document`) and `extract(documentId, newDocTypeId)` for retype that UPDATEs `Document.extractedFields` + `detected_document_type`, then publishes `ExtractionCompleted`/`ExtractionFailed`. Retype audit rows carry `document_id`.
**Spec refs:** C3-R2, C3-R4, C3-R13; c3-pipeline-spec.md §3.6, §3.8, §3.9.
**Deliverables:**
- `LlmExtractor.java` under `com.docflow.c3.llm`.
- Concrete record bodies for `ExtractionCompleted` / `ExtractionFailed` (skeletons created in C7.7).
- `LlmExtractorTest.java`.
**Acceptance criteria:**
- Retry-then-succeed and second-failure branches each verified.
- Retype path UPDATEs `Document.extractedFields` (asserts no INSERT, prior values gone).
- Retype audit row has `document_id` set, `processing_document_id` null.
- Concurrency guard rejects retype when `reextractionStatus = IN_PROGRESS` with typed error.
**Within-component deps:** C3.3, C3.4, C3.5, C3.6.
**Cross-component deps:** **C4.2** (`Document` entity / `DocumentWriter`); **C7.7** (`DocumentEventBus` + event record skeletons).
**Size:** 2 days.

#### C3.9 — `ProcessingPipelineOrchestrator` + step classes + `PipelineTriggerListener`   [P6]
**What:** Build the orchestrator (TEXT_EXTRACTING → CLASSIFYING → EXTRACTING), the three `PipelineStep` classes (`TextExtractStep` via PDFBox, `ClassifyStep`, `ExtractStep`), `ProcessingDocumentService.start`, and `PipelineTriggerListener` (subscribes to `StoredDocumentIngested`). Orchestrator emits `ProcessingStepChanged` on every transition incl. FAILED, and `ProcessingCompleted` on terminal success. Per-LLM-call transaction boundary covers UPDATE `current_step` + INSERT `llm_call_audit`.
**Spec refs:** C3-R1, C3-R2, C3-R14, C3-R16; c3-pipeline-spec.md §3.1, §3.2, §3.6, §3.9, §3.11.
**Deliverables:**
- `PipelineStep.java`, `TextExtractStep.java`, `ClassifyStep.java`, `ExtractStep.java`, `ProcessingPipelineOrchestrator.java`, `ProcessingDocumentService.java`, `PipelineTriggerListener.java` under `com.docflow.c3.pipeline`.
- Concrete record bodies for `StoredDocumentIngested`, `ProcessingStepChanged`, `ProcessingCompleted`.
- `ProcessingPipelineOrchestratorTest.java`, `PipelineTriggerListenerTest.java`.
**Acceptance criteria:**
- Listener consumes `StoredDocumentIngested` and calls `ProcessingDocumentService.start` exactly once.
- Three steps run in order on happy path; `ProcessingCompleted` published.
- Text-extract / classify / extract failure each set `current_step = FAILED`, populate `last_error`, emit `ProcessingStepChanged{FAILED}`.
- PDFBox failure produces no audit row (only LLM failures audit).
**Within-component deps:** C3.2, C3.3, C3.7, C3.8.
**Cross-component deps:** **C2.4** publishes `StoredDocumentIngested` post-commit; **C7.7** (`DocumentEventBus`).
**Size:** 2 days.

#### C3.10 — Live-API smoke test (`PipelineSmokeIT`)                                      [P9]
**What:** Single happy-path JUnit integration test exercising classify + extract end-to-end against the live Anthropic API for one sample document. Tagged and gated on `ANTHROPIC_API_KEY`; excluded from `make test`.
**Spec refs:** C3-R12; c3-pipeline-spec.md §6.
**Deliverables:**
- `PipelineSmokeIT.java` under `com.docflow.c3.integration`.
**Acceptance criteria:**
- AC3: passes with valid `ANTHROPIC_API_KEY`.
- Skipped (not failed) when key absent.
**Within-component deps:** C3.9.
**Cross-component deps:** **C2.4** (ingestion service); **C7.5** (Gradle test task definition for the tagged group).
**Size:** half-day.

#### C3.11 — Eval harness + `evalRun` Gradle task                                         [P9]
**What:** Implement `EvalManifest`, `EvalScorer`, `EvalReportWriter`, `EvalRunner`. Load `eval/manifest.yaml`, run `LlmClassifier` + `LlmExtractor` over each labeled sample (live API), score (classification exact-match + per-field normalized exact-match aggregated), write `eval/reports/latest.md`. Gradle task `evalRun` invokes `EvalRunner`; requires `ANTHROPIC_API_KEY`. Eval is on-demand only — not in CI.
**Spec refs:** C3-R7, C3-R8, C3-R9; c3-pipeline-spec.md §3.10.
**Deliverables:**
- `EvalManifest.java`, `EvalScorer.java`, `EvalReportWriter.java`, `EvalRunner.java` under `com.docflow.c3.eval`.
- `backend/src/main/resources/eval/manifest.yaml` (filename → `{ orgId, docType, extractedFields }` for the 23 samples).
- `evalRun` Gradle task in `build.gradle.kts`.
- `EvalScorerTest.java`, `EvalReportWriterTest.java`.
**Acceptance criteria:**
- `EvalScorer` aggregates classification + field accuracy on a synthetic 3-sample fixture.
- `./gradlew evalRun` produces `eval/reports/latest.md` with both aggregate numbers and per-sample table.
- Manifest parses; missing sample fails fast with named-file error.
- CI config has no `evalRun` step; task fails clearly if `ANTHROPIC_API_KEY` unset.
**Within-component deps:** C3.7, C3.8.
**Cross-component deps:** **C7.5** owns `build.gradle.kts` task wiring; samples live under read-only `problem-statement/samples/`.
**Size:** 2 days.

---

### C4 — Workflow Engine

> Owns `Document` (post-processing) and `WorkflowInstance`. Materializes them on `ProcessingCompleted`; runs `WorkflowEngine.applyAction`; emits `DocumentStateChanged`. Tasks land in P2..P6.

#### C4.1 — Define core workflow domain types                                             [P2]
**What:** Sealed action/error types: `WorkflowAction` (`Approve`, `Reject`, `Flag(comment)`, `Resolve(newDocTypeId)`), `WorkflowError` (`InvalidAction`, `ValidationFailed`, `UnknownDocument`, `ExtractionInProgress`), `WorkflowStatus` enum, `StageGuard` sealed interface (`FieldEquals`, `FieldNotEquals`, `Always`) with `evaluate(Map<String,Object>)`. Pure types, no I/O.
**Spec refs:** C4-R1, C4-R5, C4-R6; c4-workflow-spec.md §3.4, §4.1.
**Deliverables:**
- `WorkflowAction.java`, `WorkflowError.java`, `WorkflowStatus.java`, `StageGuard.java` under `com.docflow.workflow`.
- `StageGuardTest.java`.
**Acceptance criteria:**
- `FieldEquals.evaluate` returns true iff `Objects.equals(fields.get(path), value)`; supports top-level scalar paths.
- `Always.evaluate` returns true unconditionally.
- All types sealed; exhaustive switches compile.
**Within-component deps:** none.
**Cross-component deps:** none.
**Size:** half-day.

#### C4.2 — `Document` and `WorkflowInstance` entities + writers                           [P6]
**What:** `Document` record (id, storedDocumentId, organizationId, detectedDocumentType, extractedFields, rawText, processedAt, reextractionStatus), `ReextractionStatus` enum, `WorkflowInstance` record, `DocumentReader`, `DocumentWriter` (`insert`, `updateExtraction`, `setReextractionStatus`), `WorkflowInstanceWriter` (`advanceStage`, `setFlag`, `clearFlag`). Each writer method computes derived `currentStatus` and `currentStageId` together.
**Spec refs:** C4-R11, C4-R12; c4-workflow-spec.md §3.2, §4.1.
**Deliverables:**
- `Document.java`, `ReextractionStatus.java`, `DocumentReader.java`, `DocumentWriter.java` under `com.docflow.document`.
- `WorkflowInstance.java`, `WorkflowInstanceWriter.java` under `com.docflow.workflow`.
- `WorkflowInstanceWriterTest.java`.
**Acceptance criteria:**
- `advanceStage`, `setFlag`, `clearFlag` each set `currentStageId` AND `currentStatus` in same UPDATE; never split.
- Status rule: `review + originStage != null → FLAGGED`; else `currentStage.canonicalStatus`. Property-tested.
- Optimistic-lock check on `updated_at` with single retry; second collision throws.
**Within-component deps:** C4.1.
**Cross-component deps:** **C1.8** (`WorkflowCatalog` for stage `kind` + `canonicalStatus`); **C7.4** (V1).
**Size:** 1 day.

#### C4.3 — `documents` + `workflow_instances` migration fragment                         [P3]
**What:** Author the `documents` and `workflow_instances` `CREATE TABLE` blocks per C4-R11 with FKs, JSONB `extracted_fields`, `reextraction_status` CHECK, `current_status` CHECK, required indexes. Hand to C7.4 for inclusion in the single baseline migration in correct dependency order (after `stored_documents`, `document_types`, `stages`).
**Spec refs:** C4-R11, C4-R13; c4-workflow-spec.md §4.2.
**Deliverables:**
- SQL fragment merged into `V1__init.sql` (assembled by C7.4).
- `SchemaIndexExistenceTest.java` under `com.docflow.workflow` (Testcontainers — asserts `(organization_id, current_status, updated_at DESC)` and `(document_id)` UNIQUE indexes exist).
**Acceptance criteria:**
- `documents` has `(organization_id, processed_at DESC)` and UNIQUE `(stored_document_id)` indexes.
- `workflow_instances` has `(organization_id, current_status, updated_at DESC)` and UNIQUE `(document_id)` indexes.
- `current_status` CHECK enumerates exactly the 5 canonical values; `reextraction_status` CHECK enumerates `NONE, IN_PROGRESS, FAILED` with default `NONE`.
- Flyway migrate succeeds against empty Postgres.
**Within-component deps:** none.
**Cross-component deps:** **C7.4** assembles V1; **C2.3** (`stored_documents`) and **C1.5** (`document_types`, `stages`) ordered earlier.
**Size:** half-day.

#### C4.4 — `TransitionResolver`                                                          [P6]
**What:** Pure function: given `(currentStageId, action, extractedFields, WorkflowCatalog)`, iterate configured transitions and return first whose `(fromStage, action)` matches and `StageGuard` evaluates true (or `Always`); otherwise `InvalidAction`. No I/O, no Spring.
**Spec refs:** C4-R1, C4-R3, C4-R7; c4-workflow-spec.md §3.4.
**Deliverables:**
- `TransitionResolver.java`.
- `TransitionResolverTest.java`.
**Acceptance criteria:**
- Lien Waiver Review/Approve with `waiverType=unconditional` → Filed; `conditional` → Project Manager Approval.
- Terminal stages have zero outgoing transitions; any action → `InvalidAction`.
- Resolver contains zero literal stage strings.
**Within-component deps:** C4.1.
**Cross-component deps:** **C1.8** (`WorkflowCatalog`) — read-only.
**Size:** half-day.

#### C4.5 — `WorkflowEngine.applyAction`                                                  [P6]
**What:** Wire `WorkflowEngine` with constructor injection (catalog, readers, writers, `LlmExtractor`, `DocumentEventBus`). Dispatch `Approve`/`Reject`/`Flag`/`Resolve` to writers via `TransitionResolver`. Validate `Flag.comment` non-empty trimmed. Resolve no-type-change → `clearFlag` to `workflowOriginStage`. Resolve with type change → guard against `IN_PROGRESS`, set `IN_PROGRESS`, publish event, sync-call `LlmExtractor.extract`. Each successful path publishes exactly one `DocumentStateChanged` inside the same `@Transactional` boundary.
**Spec refs:** C4-R1, C4-R2, C4-R4, C4-R5, C4-R6, C4-R8, C4-R9a; c4-workflow-spec.md §3.5, §3.6, §5.1, §5.2, §5.3.
**Deliverables:**
- `WorkflowEngine.java`.
- `WorkflowEngineExampleTest.java`.
**Acceptance criteria:**
- Constructor takes `WorkflowCatalog`, `DocumentReader/Writer`, `WorkflowInstanceWriter`, `LlmExtractor`, `DocumentEventBus`; no static singletons.
- `Reject` from non-Review → `InvalidAction`; from Review → Rejected.
- `Flag("")`, `Flag("   ")` → `ValidationFailed { details: [{ path: "comment" }] }`.
- `Resolve` with `reextractionStatus == IN_PROGRESS` → `ExtractionInProgress`.
- Every successful `applyAction` publishes exactly one `DocumentStateChanged` carrying `documentId, storedDocumentId, organizationId, currentStage, currentStatus, reextractionStatus, action?, comment?, at` and no `previousStage`/`previousStatus`.
- Manager Approval / Approve → Filed; Manager Approval / Flag → Review with origin set.
**Within-component deps:** C4.1, C4.2, C4.4.
**Cross-component deps:** **C1.8** (`WorkflowCatalog` constructor-injected per C4-R9a); **C3.8** (`LlmExtractor.extract`); **C7.7** (`DocumentEventBus`).
**Size:** 2 days.

#### C4.6 — `ProcessingCompletedListener` (C3 → C4 handoff)                                [P6]
**What:** `@Async @EventListener` on `ProcessingCompleted`. In one `@Transactional` boundary: look up Review stage via `WorkflowCatalog.getWorkflow(orgId, detectedDocumentType)`; INSERT `Document` with payload fields, `reextractionStatus = NONE`; INSERT `WorkflowInstance` at Review with `AWAITING_REVIEW`, no origin, no flag; publish `DocumentStateChanged`. Handle duplicate (UNIQUE collision) by rolling back and logging WARN — no second event.
**Spec refs:** C4-R13; c4-workflow-spec.md §3.3, §5.4, §7.
**Deliverables:**
- `ProcessingCompletedListener.java`.
- `ProcessingCompletedListenerIT.java`.
**Acceptance criteria:**
- Mid-transaction failure rolls back both rows; no `DocumentStateChanged` emitted.
- Successful handoff yields one `DocumentStateChanged` with `currentStage = Review`, `currentStatus = AWAITING_REVIEW`, `reextractionStatus = NONE`.
- All field values come from event payload — no extra DB read.
- Duplicate `ProcessingCompleted` for same `storedDocumentId` rolls back on UNIQUE collision; WARN logged; no second event.
**Within-component deps:** C4.2, C4.3, C4.5.
**Cross-component deps:** **C3.9** publishes `ProcessingCompleted`; **C1.8** (`WorkflowCatalog.getWorkflow`); **C7.7** (`DocumentEventBus`).
**Size:** 1 day.

#### C4.7 — `ExtractionEventListener` (retype completion)                                 [P6]
**What:** `@Async @EventListener` on `ExtractionCompleted` and `ExtractionFailed`. On success: UPDATE `Document.detectedDocumentType` + `extractedFields`, `setReextractionStatus(NONE)`, `clearFlag` (stage stays Review), publish `DocumentStateChanged`. On failure: `setReextractionStatus(FAILED)`, publish `DocumentStateChanged`. Tolerate races (document not `IN_PROGRESS` → log INFO and skip; unknown documentId → log WARN and skip; never throw out of the listener).
**Spec refs:** C4-R6; c4-workflow-spec.md §3.5, §5.4, §7.
**Deliverables:**
- `ExtractionEventListener.java`.
- `RetypeFlowIT.java` (uses **stubbed** `LlmExtractor` per integration §6.2 #2).
**Acceptance criteria:**
- Drive `Resolve` with type change → assert sequence: `DocumentStateChanged{IN_PROGRESS}`, then `LlmExtractor.extract` invoked, then on stub `ExtractionCompleted`: `DocumentStateChanged{NONE, currentStage=Review, currentStatus=AWAITING_REVIEW}`, origin/flag cleared, `detectedDocumentType` and `extractedFields` updated.
- On stub `ExtractionFailed`: `DocumentStateChanged{FAILED, currentStage=Review}`; no other state change.
- Listener exceptions never propagate.
- Late `ExtractionCompleted` arriving when status != IN_PROGRESS → INFO and dropped (no DB write, no event).
**Within-component deps:** C4.2, C4.5.
**Cross-component deps:** **C3.8** publishes `ExtractionCompleted` / `ExtractionFailed`; **C7.7** (`DocumentEventBus`).
**Size:** 1 day.

#### C4.8 — jqwik property-test suite (no Spring context)                                  [P6]
**What:** Property suite using jqwik 1.9.x with generator-backed `WorkflowCatalog` (no Spring, no DB). Drive `WorkflowEngine` over randomly-generated topologies and instances. Cover C4-R9 (a–e) properties.
**Spec refs:** C4-R9, C4-R9a, C4-R12; c4-workflow-spec.md §6.
**Deliverables:**
- `WorkflowEnginePropertyTest.java`.
- jqwik test config (Gradle `useJUnitPlatform { includeEngines("jqwik", "junit-jupiter") }`).
**Acceptance criteria:**
- (a) Always-Approve from any non-terminal stage reaches a terminal in finite steps.
- (b) Flag → Resolve no-type-change returns to origin stage with origin/comment cleared.
- (c) Flag → Resolve with type change leaves stage at Review and clears origin.
- (d) Terminal stages → every action returns `InvalidAction`.
- (e) Guards route correctly across generated valuations.
- Status-rule property: every persisted `WorkflowInstance` row satisfies the C4-R12 derivation.
- Test runs without `@SpringBootTest`.
**Within-component deps:** C4.4, C4.5.
**Cross-component deps:** none (`WorkflowCatalog` interface from C1 stubbed in-test).
**Size:** 2 days.

#### C4.9 — Flag-origin restoration matrix + event-shape contract test                    [P6]
**What:** Parameterized example test: for every approval stage across the three seed clients, drive Flag from origin → Resolve no-type (returns to origin) and Resolve with type change (stays Review, origin cleared after `ExtractionCompleted`). Plus a contract test asserting `DocumentStateChanged` JSON has no `previousStage` / `previousStatus` fields.
**Spec refs:** C4-R8, C4-R9b; c4-workflow-spec.md §6.
**Deliverables:**
- `FlagOriginRestorationTest.java`.
- `DocumentStateChangedShapeTest.java`.
**Acceptance criteria:**
- Each of the eight approval stages tested as flag origin under both type-change and no-type-change paths.
- Serialized `DocumentStateChanged` JSON contains only the documented keys; absence of `previousStage` and `previousStatus` asserted.
- Retype path emits exactly two events (IN_PROGRESS then NONE/FAILED).
**Within-component deps:** C4.5, C4.7, C4.8.
**Cross-component deps:** **C1.4 / C1.7** (seeded catalog data).
**Size:** 1 day.

#### C4.10 — `grepForbiddenStrings` sweep over C4 source                                  [P6]
**What:** Fast test-time sweep asserting `com.docflow.workflow` and `com.docflow.document` source contains zero literal stage strings and zero client slugs. The Gradle task `grepForbiddenStrings` is the source of truth; this is a faster-feedback duplicate.
**Spec refs:** C4-R10; c4-workflow-spec.md §6.
**Deliverables:**
- `EngineForbiddenStringsTest.java`.
**Acceptance criteria:**
- Zero hits across `com.docflow.workflow` and `com.docflow.document` `*.java` files.
- Test runs in under one second; uses `Files.walk` + regex.
- `./gradlew :backend:check` includes both this test and the `grepForbiddenStrings` Gradle task.
**Within-component deps:** C4.2, C4.4, C4.5, C4.6, C4.7 (i.e. all C4 production tasks).
**Cross-component deps:** **C1.9** (`config/forbidden-strings.txt`); **C7.6** (`grepForbiddenStrings` Gradle task).
**Size:** half-day.

---

### C5 — API & Real-Time

> REST controllers and the per-org SSE stream. Composes read-models from C2/C3/C4; writes go through C2.4, C3.8 (via C4), and C4.5. Tasks land in P7 + P9.

#### C5.1 — Scaffold DTOs and error taxonomy                                              [P7]
**What:** DTO records (`OrganizationListItem`, `OrganizationDetail`, `DashboardResponse`, `DashboardStats`, `ReviewFieldsPatch`, `RetypeRequest`, `RetypeAccepted`) and the `ActionRequest` sealed interface with Jackson 3 `@JsonTypeInfo(use=NAME, property="action")` polymorphic deserialization for `Approve`/`Reject`/`Flag(comment)`/`Resolve`. `ErrorCode` enum (11 entries) and sealed `DocflowException` hierarchy.
**Spec refs:** C5-R6, C5-R9, C5-R9a; c5-api-spec.md §3.2, §3.4, §4.3, §4.4.
**Deliverables:**
- `backend/src/main/java/com/docflow/api/dto/*.java`.
- `backend/src/main/java/com/docflow/api/error/ErrorCode.java`, `DocflowException.java` + subclasses.
- `ActionRequestDeserializationTest.java`.
**Acceptance criteria:**
- Jackson 3 round-trips all four `ActionRequest` variants; unknown discriminator throws (handled in C5.2).
- `ErrorCode` enumerates exactly 11 entries with HTTP status mapping.
**Within-component deps:** none.
**Cross-component deps:** none.
**Size:** 1 day.

#### C5.2 — `GlobalExceptionHandler` with RFC 7807 `ProblemDetail`                        [P7]
**What:** `@RestControllerAdvice` mapping `DocflowException` subclasses to `ProblemDetail` with custom `code`, `message`, optional `details`. Cover `MethodArgumentNotValidException` → `VALIDATION_FAILED`, `HttpMediaTypeNotSupportedException` → `UNSUPPORTED_MEDIA_TYPE`, Jackson polymorphism failure on `action` → `VALIDATION_FAILED` with `details: [{path: "action"}]`, catch-all → `INTERNAL_ERROR`.
**Spec refs:** C5-R9, C5-R9a; c5-api-spec.md §3.4, §4.3, §7; integration §5.3.
**Deliverables:**
- `GlobalExceptionHandler.java`.
- `GlobalExceptionHandlerContractTest.java`.
**Acceptance criteria:**
- AC10: each of 11 codes maps to documented HTTP status; body is RFC 7807 with `code`, `message`, optional `details`.
- Empty `Flag.comment` → `VALIDATION_FAILED` 400 with `details: [{path:"comment"}]`.
**Within-component deps:** C5.1.
**Cross-component deps:** none.
**Size:** 1 day.

#### C5.3 — `OrganizationController` (list + detail)                                      [P7]
**What:** `GET /api/organizations` returns the array shape (with `inProgressCount` + `filedCount`). `GET /api/organizations/{orgId}` returns the org plus workflows (stages with display + canonical `WorkflowStatus`) and field schemas. Compose from C1 catalogs and a small read-model query for the two counts.
**Spec refs:** C5-R1; c5-api-spec.md §3.2, §4.1, AC1.
**Deliverables:**
- `OrganizationController.java`.
- `OrganizationControllerTest.java`.
**Acceptance criteria:**
- AC1: `inProgressCount` matches `processing_documents LEFT JOIN documents` count; `filedCount` matches `WorkflowInstance.currentStatus = FILED` count.
- Unknown `orgId` on detail → 404 / `UNKNOWN_ORGANIZATION`.
**Within-component deps:** C5.1, C5.2.
**Cross-component deps:** **C1.8** (`OrganizationCatalog`, `WorkflowCatalog`, `DocumentTypeCatalog`); read-model query reading **C3.1** `processing_documents` + **C4.3** `documents` + `workflow_instances`.
**Size:** 1 day.

#### C5.4 — `DocumentUploadController` + `DocumentController` (view + file stream)        [P7]
**What:** `POST /api/organizations/{orgId}/documents` (multipart `file`) delegates to C2.4, returns 201 + `{storedDocumentId, processingDocumentId}`. `GET /api/documents/{documentId}` returns `DocumentView`. `GET /api/documents/{documentId}/file` streams bytes with `Content-Type` from `StoredDocument.mimeType`.
**Spec refs:** C5-R2, C5-R4, C5-R5; c5-api-spec.md §3.2, §4.1; integration §4.1.
**Deliverables:**
- `DocumentUploadController.java`, `DocumentController.java`.
- Controller unit tests.
**Acceptance criteria:**
- AC2: valid PDF → 201 + body; unknown `orgId` → 404; `application/zip` → 415.
- AC4: `DocumentView` includes `reextractionStatus`; unknown id → 404.
- AC5: file bytes match stored bytes byte-for-byte; `Content-Type` matches `StoredDocument.mimeType`.
**Within-component deps:** C5.1, C5.2.
**Cross-component deps:** **C2.4** (`StoredDocumentIngestionService.upload`); **C2.1** (`StoredDocumentReader`); **C2.2** (`StoredDocumentStorage.load`); **C4.2** (`DocumentReader` for `DocumentView`).
**Size:** 1 day.

#### C5.5 — `DashboardController` (read-model composition)                                [P7]
**What:** `GET /api/organizations/{orgId}/documents?status&docType` returns `{processing, documents, stats}`. `processing` ignores filters (sorted `createdAt DESC`); `documents` honors filters (canonical `WorkflowStatus` only) and sorts `updatedAt DESC` with soft cap 200; `stats` is org-wide and unfiltered. Filters out `processing_documents` rows that already have a matching `Document`.
**Spec refs:** C5-R3; c5-api-spec.md §3.2, AC3.
**Deliverables:**
- `DashboardController.java`.
- `DashboardControllerTest.java`.
**Acceptance criteria:**
- AC3: three-key shape; `?status=AWAITING_REVIEW` reduces only `documents`; `processing` and `stats` unchanged.
- Filter accepts canonical `WorkflowStatus` enum values only; invalid → `VALIDATION_FAILED`.
**Within-component deps:** C5.1, C5.2.
**Cross-component deps:** Read-model package (shared with C4) — `DocumentReadModel` over **C4.2** rows; `ProcessingDocumentSummary` over **C3.2** rows; stats query over **C4.2** `workflow_instances`.
**Size:** 1 day.

#### C5.6 — `DocumentActionController` + `ReviewController`                                [P7]
**What:** `POST /api/documents/{documentId}/actions` deserializes the union and calls `WorkflowEngine.applyAction`, returns updated `DocumentView`. `PATCH /api/documents/{documentId}/review/fields` validates against C1 schema, persists via `DocumentWriter`, returns updated view. `POST /api/documents/{documentId}/review/retype` validates `newDocumentType` against the org's allowed list, calls `WorkflowEngine.applyAction(documentId, Resolve, {newDocTypeId})`, returns 202 + `{reextractionStatus: IN_PROGRESS}`.
**Spec refs:** C5-R6, C5-R7; c5-api-spec.md §3.2, §4.1, AC6/AC7/AC8; integration §4.2, §4.3.
**Deliverables:**
- `DocumentActionController.java`, `ReviewController.java`.
- Per-variant + per-endpoint unit tests.
**Acceptance criteria:**
- AC6: each of four variants invokes `WorkflowEngine.applyAction` exactly once; empty `Flag.comment` → 400; `Approve` on `FILED` → 409 `INVALID_ACTION`; reextraction-in-progress → 409 `REEXTRACTION_IN_PROGRESS`.
- AC7: valid PATCH → 200 with updated `extractedFields`; invalid shape → 400 with field-level `details`.
- AC8: retype → 202 + `{reextractionStatus: IN_PROGRESS}` and triggers `LlmExtractor.extract` exactly once via the engine; unknown type → 404 `UNKNOWN_DOC_TYPE`.
**Within-component deps:** C5.1, C5.2, C5.4.
**Cross-component deps:** **C4.5** (`WorkflowEngine.applyAction`); **C4.2** (`DocumentWriter` for field PATCH); **C1.8** (`DocumentTypeCatalog`, `OrganizationCatalog.getAllowedDocTypes`).
**Size:** 2 days.

#### C5.7 — SSE: `SseRegistry` + `SsePublisher` + `SseController`                          [P7]
**What:** `SseRegistry` keyed by `orgId` (`ConcurrentHashMap<String, Set<SseEmitter>>`) with `SseEmitter(0L)` and `onCompletion`/`onTimeout`/`onError` cleanup. `SseController` registers and emits the initial `retry: 5000` frame. `SsePublisher` is `@Async @EventListener` filtering the bus to `ProcessingStepChanged` and `DocumentStateChanged` only, writing frames with monotonic `id:` (process-wide `AtomicLong`), `event:`, JSON `data:`. `@EnableAsync` and `spring.threads.virtual.enabled=true` confirmed.
**Spec refs:** C5-R8; c5-api-spec.md §3.2, §3.3, §4.2, §4.5, AC9, §7; integration §3.3.
**Deliverables:**
- `SseRegistry.java`, `SsePublisher.java` under `com.docflow.api.sse`.
- `SseController.java` under `com.docflow.api`.
- `SseFanoutIntegrationTest.java`.
- Edits: `application.yml`, `App.java` (`@EnableAsync`).
**Acceptance criteria:**
- AC9: `Content-Type: text/event-stream`; first frame contains `retry: 5000`; publishing `ProcessingStepChanged` for the org produces `event: ProcessingStepChanged`; `ProcessingCompleted`/`ExtractionCompleted`/`ExtractionFailed` are NEVER emitted on SSE.
- Emitters for the same org are isolated.
- No `Last-Event-ID` resume logic.
**Within-component deps:** C5.1.
**Cross-component deps:** **C7.7** (`DocumentEventBus`); **C3.9** publishes `ProcessingStepChanged`; **C4.5/C4.6/C4.7** publish `DocumentStateChanged`.
**Size:** 2 days.

#### C5.8 — `HappyPathSmokeTest` (the only HTTP-seam integration test)                    [P9]
**What:** `@SpringBootTest(webEnvironment=RANDOM_PORT)` + Postgres Testcontainer driving upload → wait for `ProcessingCompleted` → dashboard GET → Approve action through `TestRestTemplate`. Gated on `ANTHROPIC_API_KEY` (live LLM calls inside).
**Spec refs:** all C5-R*; c5-api-spec.md §6; integration §6.2 #1.
**Deliverables:**
- `HappyPathSmokeTest.java`.
**Acceptance criteria:**
- POST → 201 with both ids; after `ProcessingCompleted`, dashboard `documents[]` contains entry with `currentStatus = AWAITING_REVIEW`; Approve POST → 200 with `DocumentView` advanced one stage.
- The only HTTP-seam integration test in the suite.
**Within-component deps:** C5.3, C5.4, C5.5, C5.6, C5.7.
**Cross-component deps:** **C1.7** (seeded org `pinnacle-legal`); **C2.4** (ingestion); **C3.9** (pipeline, live Anthropic); **C4.5/C4.6** (workflow listeners); **C7.7** (bus); **C7.4** (V1); **C7.3** (AppConfig).
**Size:** 1 day.

---

### C6 — Frontend (React SPA)

> Three routes consuming C5 REST + per-org SSE. Tasks land in P8 + P9.

#### C6.1 — Application shell, routing, and providers                                     [P8]
**What:** Wire up `main.tsx`, `App.tsx`, `QueryClientProvider`, `RouterProvider` with the four routes (`/`, `/org/:orgId/dashboard`, `/documents/:documentId`, `*` → `NotFoundPage`), and the PDF.js worker via Vite `?url` import. No `/processing-documents/:id` route registered.
**Spec refs:** C6-R7; c6-frontend-spec.md §3.1, §3.2, §4.1.
**Deliverables:**
- `frontend/src/main.tsx`, `App.tsx`, `pdf-worker.ts`, `routes/NotFoundPage.tsx`.
- `frontend/tests/unit/App.test.tsx`.
**Acceptance criteria:**
- AC6.1: navigating to `/processing-documents/{anyId}` resolves to `NotFoundPage`.
- App mounts without errors; React Router exposes the four declared routes only.
**Within-component deps:** none.
**Cross-component deps:** **C7.2** owns the Vite scaffold; this task populates `src/`.
**Size:** half-day.

#### C6.2 — API client, type models, and MSW handlers                                     [P8]
**What:** `api/client.ts` (typed-error fetch wrapper per C5 RFC 7807), per-endpoint API modules (`organizations.ts`, `dashboard.ts`, `documents.ts`, `workflows.ts`), TypeScript types mirroring C5, and the MSW handler set used by all unit tests.
**Spec refs:** c6-frontend-spec.md §3.3, §4.1, §7.
**Deliverables:**
- `frontend/src/api/{client,organizations,dashboard,documents,workflows}.ts`.
- `frontend/src/types/{readModels,workflow,events,schema}.ts`.
- `frontend/tests/msw/{handlers,server}.ts`.
**Acceptance criteria:**
- `client.ts` rejects with a typed `DocflowApiError` carrying `code`, `message`, `details`, `status`.
- MSW handlers serve happy-path fixtures for `GET /api/organizations`, `GET /api/organizations/{orgId}/documents`, `GET /api/documents/{id}`, `GET /api/organizations/{orgId}/doctypes/{docTypeId}/workflow`, plus action endpoints.
**Within-component deps:** C6.1.
**Cross-component deps:** **C5.3 / C5.4 / C5.5 / C5.6** endpoints + payload shapes.
**Size:** 1 day.

#### C6.3 — `OrgPickerPage` with `OrgPickerCard`                                          [P8]
**What:** Org picker route: `OrgPickerPage` queries `['organizations']` and renders one `OrgPickerCard` per result with icon, name, supported doc-types `<li>`s, and `In Progress` / `Filed` badges. Card click → `/org/{orgId}/dashboard`.
**Spec refs:** C6-R1; c6-frontend-spec.md §5.1.
**Deliverables:**
- `routes/OrgPickerPage.tsx`, `components/OrgPickerCard.tsx`.
- `frontend/tests/unit/OrgPickerPage.test.tsx`.
**Acceptance criteria:** AC1.1, AC1.2, AC1.3.
**Within-component deps:** C6.1, C6.2.
**Cross-component deps:** **C5.3** payload (`inProgressCount`, `filedCount`, `supportedDocTypes`, `iconUrl`).
**Size:** half-day.

#### C6.4 — Dashboard skeleton, stats bar, filters, and sections                          [P8]
**What:** `DashboardPage` (route `/org/:orgId/dashboard`) runs `['dashboard', orgId]` once and feeds `DashboardStatsBar`, `DashboardFilterBar` (canonical `WorkflowStatus` + per-org docType options), `ProcessingSection` (non-clickable rows, opacity 0.55, spinner, FAILED inline), and `DocumentsSection` (clickable rows → `/documents/{id}`). Empty-filter fallback row.
**Spec refs:** C6-R2, C6-R3, C6-R6; c6-frontend-spec.md §5.2, §5.3.
**Deliverables:**
- `routes/DashboardPage.tsx`.
- `components/{AppTopbar,DashboardStatsBar,DashboardFilterBar,ProcessingSection,ProcessingRow,DocumentsSection,DocumentRow,StepBadge,StatusBadge,DocTypeBadge}.tsx`.
- `frontend/tests/unit/DashboardPage.test.tsx`, `ProcessingRow.test.tsx`.
**Acceptance criteria:** AC2.1, AC2.2, AC3.1..AC3.6.
**Within-component deps:** C6.1, C6.2.
**Cross-component deps:** **C5.5** (`{stats, processing, documents}` shape).
**Size:** 2 days.

#### C6.5 — `useOrgEvents` SSE hook + upload mutation                                     [P8]
**What:** `useOrgEvents(orgId)` opens a single `EventSource` to `/api/organizations/{orgId}/stream`, listens for `ProcessingStepChanged` and `DocumentStateChanged`, refetches `['dashboard', orgId]` on any event and `['document', id]` on `DocumentStateChanged` for the relevant id. `useUploadDocument` with optimistic insert into `processing[]`, `onError` rollback, `onSettled` invalidate. Wire upload button + hidden file input on dashboard.
**Spec refs:** C6-R4, C6-R5; c6-frontend-spec.md §3.4, §3.5, §5.4, §5.5.
**Deliverables:**
- `hooks/useOrgEvents.ts`, `hooks/useUploadDocument.ts`.
- `frontend/tests/unit/useOrgEvents.test.tsx`, `useUploadDocument.test.tsx`.
**Acceptance criteria:** AC4.1, AC4.2, AC5.1..AC5.4.
**Within-component deps:** C6.2, C6.4.
**Cross-component deps:** **C5.7** (SSE stream); **C5.4** (upload endpoint).
**Size:** 1 day.

#### C6.6 — `DocumentDetailPage` layout + `PdfViewer`                                     [P8]
**What:** `/documents/:documentId` route: queries `['document', documentId]`, lays out two-panel `DetailLayout` (left `PdfViewer` using `react-pdf`, right form panel), renders `DocumentHeader`, calls `useOrgEvents(orgId)`, surfaces `react-pdf` `onLoadError` fallback.
**Spec refs:** C6-R7; c6-frontend-spec.md §3.2, §7.
**Deliverables:**
- `routes/DocumentDetailPage.tsx`.
- `components/{DetailLayout,PdfViewer,DocumentHeader}.tsx`.
- `frontend/tests/unit/DocumentDetailPage.test.tsx`.
**Acceptance criteria:**
- Mounting issues exactly one `GET /api/documents/{id}` and one `EventSource` open.
- PDF load failure renders fallback; form panel remains usable.
**Within-component deps:** C6.2, C6.5.
**Cross-component deps:** **C5.4** (`GET /api/documents/{id}` and `/file`).
**Size:** 1 day.

#### C6.7 — `StageProgress` (pre-workflow + workflow synthesis)                            [P8]
**What:** Implement `StageProgress` joining `['workflow', orgId, docTypeId]` against the document's `currentStep` (in-flight) or `currentStageId` + `currentStatus` + `originStage` (processed). Render the seven state matrices (in-flight running, in-flight FAILED, Review, Review-flagged regressed-amber, Approval, Filed, Rejected with rejected-edge + rejected-current + muted never-reached approvals).
**Spec refs:** C6-R9; c6-frontend-spec.md §3.7, §5.7, §6.3.
**Deliverables:**
- `components/StageProgress.tsx`.
- `frontend/tests/unit/StageProgress.test.tsx`.
**Acceptance criteria:** AC7.1, AC7.2, AC7.3, AC7.4.
**Within-component deps:** C6.2, C6.6.
**Cross-component deps:** **C5.3** (`GET /api/organizations/{orgId}/doctypes/{docTypeId}/workflow`).
**Size:** 1 day.

#### C6.8 — `FormPanel` render-state machine + zod schema builder + readonly modes        [P8]
**What:** 5-branch dispatch over `currentStage` + `reextractionStatus`: `ReextractionInProgressBanner`, `ReextractionFailedBanner`, Review (editable, dropdown, Approve/Reject), Review-flagged (`FlagBanner` + Resolve), Approval (`ApprovalSummary` read-only + Approve/Flag + role-suffix label), Terminal (`TerminalSummary` + Back to Documents). `schemas/buildZodFromFieldSchema.ts` covers string/date/decimal/enum/array (decimal `.preprocess` for `1,234.56` and `1234.56`). `ReadOnlyArrayTable` for non-Review modes.
**Spec refs:** C6-R8, C6-R10 (read-only branch); c6-frontend-spec.md §3.6, §5.6.
**Deliverables:**
- `components/{FormPanel,ApprovalSummary,TerminalSummary,FlagBanner,ReextractionInProgressBanner,ReextractionFailedBanner,ReadOnlyArrayTable}.tsx`.
- `schemas/buildZodFromFieldSchema.ts`, `util/formatters.ts`.
- `frontend/tests/unit/FormPanel.test.tsx`, `buildZodFromFieldSchema.test.ts`.
**Acceptance criteria:** AC6.2..AC6.7, AC8.3 (no inputs/controls in non-Review).
**Within-component deps:** C6.2, C6.6.
**Cross-component deps:** **C5.4** (`DocumentView` payload incl. `currentStage`, `currentStatus`, `reextractionStatus`, `originStage`, `flagComment`, `extractedFields`, `fieldSchema`, optional `role`).
**Size:** 2 days.

#### C6.9 — `ReviewForm` editable mode, `FieldArrayTable`, action mutations                [P8]
**What:** Editable Review form with `react-hook-form` + zod resolver, scalar inputs, `FieldArrayTable` (inline-editable rows + Add/Remove via `useFieldArray`), and `useDocumentActions` covering Approve/Reject/Flag/Resolve (no-type)/Retype. Mutations have no optimistic update; `onSuccess` invalidates `['document', id]` + `['dashboard', orgId]`; `onError` shows generic toast and invalidates `['document', id]`. 400 `VALIDATION_FAILED` details → inline field errors.
**Spec refs:** C6-R10, C6-R8 (Review/Resolve buttons); c6-frontend-spec.md §3.5, §5.8 (AC8.1, AC8.2), §7.
**Deliverables:**
- `components/{ReviewForm,FieldArrayTable}.tsx`.
- `hooks/useDocumentActions.ts`.
- `frontend/tests/unit/ReviewForm.test.tsx` (Pinnacle Invoice `lineItems`, Riverside Receipt `category` enum, Ironworks Lien Waiver `waiverType` enum).
- `useDocumentActions.test.tsx`.
**Acceptance criteria:** AC8.1, AC8.2.
**Within-component deps:** C6.8.
**Cross-component deps:** **C5.6** (`POST /api/documents/{id}/actions` discriminated union; flag endpoint); **C5.2** error envelope mapping.
**Size:** 2 days.

#### C6.10 — `ReclassifyModal` + `FlagModal`                                              [P8]
**What:** `ReclassifyModal` (opens on doc-type dropdown change in Review; Cancel reverts; Confirm POSTs `/api/documents/{id}/review/retype` and transitions to `reextractionStatus = IN_PROGRESS`). `FlagModal` (required textarea, submit disabled while empty/whitespace, on submit POST flag and invalidate document query).
**Spec refs:** C6-R11, C6-R12; c6-frontend-spec.md §5.8 (AC8.4), §5.9.
**Deliverables:**
- `components/{ReclassifyModal,FlagModal}.tsx`.
- `frontend/tests/unit/ReclassifyModal.test.tsx`, `FlagModal.test.tsx`.
**Acceptance criteria:** AC8.4, AC9.1, AC9.2, AC9.3.
**Within-component deps:** C6.9.
**Cross-component deps:** **C5.6**: retype is `POST /api/documents/{id}/review/retype`; **Flag is implemented via `POST /api/documents/{id}/actions` with body `{ "action": "Flag", "comment": ... }` per c5-api-spec.md §3.2** — the C6 AC9.3 URL pattern (`/{id}/{stageId}/flag`) is a Pass-5 spec drift; treat the `/actions` endpoint as canonical and route the flag mutation in `useDocumentActions` accordingly.
**Size:** half-day.

#### C6.11 — Vitest coverage gate, lint, format, typecheck, build                          [P9]
**What:** Set `coverage: { thresholds: { lines: 70, branches: 60, functions: 70, statements: 70 } }` in `vitest.config.ts` (replacing C7.2's placeholder `0`). Ensure `npm run lint`, `format:check`, `typecheck`, `build`, `test -- --coverage` all green. Verify `make test` runs the frontend portion correctly.
**Spec refs:** C6-R13; c6-frontend-spec.md §6.1, §6.2, §6.5.
**Deliverables:**
- `frontend/vitest.config.ts` (coverage thresholds).
- Any small refactors needed to clear the coverage gate.
**Acceptance criteria:**
- `npm run test -- --coverage` reports >=70 lines / >=60 branches / >=70 functions / >=70 statements and exits 0.
- `make test` exits 0 with the frontend portion enabled.
**Within-component deps:** C6.1..C6.10.
**Cross-component deps:** **C7.1 / C7.2** (Make + Vitest placeholder).
**Size:** half-day.

#### C6.12 — Playwright E2E (`make e2e`)                                                  [P9]
**What:** Author `happy-path.spec.ts` (Pinnacle Invoice upload → Review → Attorney Approval → Billing Approval → Filed) and `flag-and-resolve.spec.ts` (upload → Review approve → Attorney flag with comment → assert flag banner with origin `ATTORNEY_APPROVAL` → Resolve → assert return to `AWAITING_APPROVAL` at Attorney → approve through to Filed). Wire `playwright.config.ts`.
**Spec refs:** C6-R14; c6-frontend-spec.md §4.2, §6.4.
**Deliverables:**
- `frontend/e2e/{happy-path,flag-and-resolve}.spec.ts`.
- `frontend/playwright.config.ts`.
**Acceptance criteria:**
- `make e2e` runs both specs against `docker compose up` and exits 0.
- `make test` does **not** invoke Playwright.
**Within-component deps:** C6.1..C6.11.
**Cross-component deps:** Full backend stack (**C2/C3/C4/C5**) running under `docker compose`; **C1.7** (org config seeded) and **C7.9** (application-data seed loader) so Pinnacle Invoice fixtures exist; live or stubbed Anthropic per C7's compose config.
**Size:** 1 day.

---

## 5. Coverage check

### 5.1 SPEC.md coverage

| SPEC.md section | Tasks |
|---|---|
| §1 Problem in one page | (orientation; no tasks) |
| §2 System at a glance | All component sections in §4 |
| §3 Canonical vocabulary — entities | C1.1, C2.1, C3.2, C4.1, C4.2 |
| §3 Canonical vocabulary — status enums | C1.1, C4.1, C4.2 |
| §3 Canonical vocabulary — workflow pieces | C1.1, C4.4, C4.5 |
| §3 Canonical vocabulary — platform pieces | C7.3, C7.6, C7.7, C1.8 |
| §3 Canonical vocabulary — events | C7.7, C2.4, C3.8, C3.9, C4.5, C4.6, C4.7 |
| §4 Tech stack and platform | C7.1, C7.2, C7.3 |
| §5 C1 Config Layer | C1.1..C1.10 |
| §5 C2 Document Ingestion & Storage | C2.1..C2.5 |
| §5 C3 Processing Pipeline & Eval | C3.1..C3.11 |
| §5 C4 Workflow Engine | C4.1..C4.10 |
| §5 C5 API & Real-Time | C5.1..C5.8 |
| §5 C6 Frontend (React SPA) | C6.1..C6.12 |
| §5 C7 Platform & Quality | C7.1..C7.12 |
| §6 Integration & data flows — upload | C2.4, C3.9, C4.6, C5.7, C6.5 |
| §6 Integration & data flows — review/approve | C5.6, C4.5, C5.7, C6.9 |
| §6 Integration & data flows — retype | C5.6, C4.5, C3.8, C4.7, C5.7, C6.10 |
| §6 Initialization order | C7.3, C7.4, C1.7, C1.8, C3.4, C7.9 |
| §6 Failure-mode boundaries | C7.3, C2.4, C3.9 |
| §7 Definition of done — `make build` | C7.1 |
| §7 Definition of done — `make test` | C7.5, C7.6, C7.10, C5.8, C6.11 |
| §7 Definition of done — Stop hook | C7.10 |
| §7 Definition of done — `make e2e` | C6.12 |
| §7 Test-suite map | C7.5, C5.8, C4.7, C7.9, C6.12, C3.10, C3.11 |
| §8 Out of scope | (acknowledged; no tasks) |
| §9 Reading order for an implementer | (no tasks) |
| §10 Open follow-ups | See §6 below |

### 5.2 Per-component requirement coverage

#### C1
| Requirement | Tasks |
|---|---|
| C1-R1 | C1.1, C1.2, C1.4 |
| C1-R2 | C1.1, C1.2, C1.4 |
| C1-R3 | C1.1, C1.2, C1.4 |
| C1-R4 | C1.1, C1.2 |
| C1-R4a | C1.1 |
| C1-R5 | C1.3 |
| C1-R6 | C1.8 |
| C1-R7 | C1.9 |
| C1-R8 | C1.4, C1.7 |
| C1-R9 | C1.10 |
| C1-R10 | C1.1, C1.4 |
| C1-R11 | C1.5, C1.6, C1.7, C1.8 |
| C1-R12 | C1.1, C1.3 |
| AC-L1..AC-L6 | C1.1, C1.2, C1.4 |
| AC-V1..AC-V9 | C1.3 |
| AC-S1..AC-S3 | C1.7 |
| AC-C1..AC-C6 | C1.8 |
| AC-E1 | C1.10 |
| AC-G1, AC-G2 | C1.9 |
| AC-AC1 | C1.6 |

#### C2
| Requirement | Tasks |
|---|---|
| C2-R1 | C2.4 |
| C2-R2 | C2.2 |
| C2-R4 | C2.1, C2.4 |
| C2-R4a | C2.3 |
| C2-R5 | C2.3, C2.5 |
| C2-R6 | C2.5 |
| C2-R7 | C2.1, C2.2 |
| C2-R9 | C2.3 |
| C2-R9a | C2.2, C2.4 |
| AC-R1..AC-R9a | C2.2, C2.3, C2.4, C2.5 |
| AC-IMMUTABILITY | C2.1, C2.3 |
| AC-EVENT | C2.5 |
| AC-ORG-VALIDATION | C2.4 |
| AC-CORRUPT-PDF | C2.4 |
| AC-OVERSIZE | (Spring default `spring.servlet.multipart.max-file-size` — no task) |

#### C3
| Requirement | Tasks |
|---|---|
| C3-R1 | C3.7, C3.9 |
| C3-R2 | C3.8, C3.9 |
| C3-R4 | C3.8 |
| C3-R5 | C3.4 |
| C3-R5a | C3.1, C3.3 |
| C3-R6 | C3.5 |
| C3-R7 | C3.11 |
| C3-R8 | C3.11 |
| C3-R9 | C3.11 |
| C3-R12 | C3.10 |
| C3-R13 | C3.8 |
| C3-R14 | C3.1, C3.2, C3.9 |
| C3-R16 | C3.2, C3.3, C3.9 |
| AC1 (build clean) | C7.5 + all C3 tasks |
| AC2 PipelineTriggerListener invocation | C3.9 |
| AC2 orchestrator happy path | C3.9 |
| AC2 orchestrator FAILED — text-extract step | C3.9 |
| AC2 orchestrator FAILED — classify step | C3.9 |
| AC2 orchestrator FAILED — extract step | C3.9 |
| AC2 retype reextraction-status cycle | C3.8 |
| AC2 ToolSchemaBuilder determinism | C3.5 |
| AC2 LlmCallAuditWriter shapes | C3.3 |
| AC2 PromptLibrary missing-file | C3.4 |
| AC2 EvalScorer | C3.11 |
| AC2 organization_id denormalization | C3.2 |
| AC3 PipelineSmokeIT | C3.10 |
| AC4 evalRun report | C3.11 |
| AC5 schema migrated | C3.1 |
| AC6 SSE visibility | C3.9 |

#### C4
| Requirement | Tasks |
|---|---|
| C4-R1 | C4.4, C4.5 |
| C4-R2 | C4.5 |
| C4-R3 | C4.4, C4.5 |
| C4-R4 | C4.5 |
| C4-R5 | C4.5 |
| C4-R6 | C4.5, C4.7 |
| C4-R7 | C4.4, C4.8 |
| C4-R8 | C4.5, C4.9 |
| C4-R9 | C4.8 |
| C4-R9a | C4.5, C4.8 |
| C4-R9b | C4.9 |
| C4-R10 | C4.10 |
| C4-R11 | C4.2, C4.3 |
| C4-R12 | C4.2, C4.8 |
| C4-R13 | C4.6 |
| AC1 state transitions | C4.5, C4.7, C4.9 |
| AC2 guard rejections | C4.4, C4.5 |
| AC3 event emissions | C4.5, C4.6, C4.7, C4.9 |
| AC4 persistence invariants | C4.2, C4.3, C4.6 |
| AC5 source discipline | C4.10 |

#### C5
| Requirement | Tasks |
|---|---|
| C5-R1 | C5.3 |
| C5-R2 | C5.4 |
| C5-R3 | C5.5 |
| C5-R4 | C5.4 |
| C5-R5 | C5.4 |
| C5-R6 | C5.1, C5.6 |
| C5-R7 | C5.6 |
| C5-R8 | C5.7 |
| C5-R9 | C5.1, C5.2 |
| C5-R9a | C5.1, C5.2 |
| AC1 (orgs list) | C5.3 (+ C5.8 end-to-end) |
| AC2 (upload) | C5.4 (+ C5.8 end-to-end) |
| AC3 (dashboard 3-key) | C5.5 |
| AC4 (`DocumentView` includes `reextractionStatus`) | C5.4 |
| AC5 (file stream) | C5.4 |
| AC6 (action union) | C5.6 (+ C5.8 end-to-end) |
| AC7 (review/fields PATCH) | C5.6 |
| AC8 (review/retype 202) | C5.6 |
| AC9 (SSE) | C5.7 |
| AC10 (RFC 7807 contract) | C5.2 |

#### C6
| Requirement | Tasks |
|---|---|
| C6-R1 | C6.3 |
| C6-R2 | C6.4 |
| C6-R3 | C6.4 |
| C6-R4 | C6.5 |
| C6-R5 | C6.5, C6.6 |
| C6-R6 | C6.4 |
| C6-R7 | C6.1, C6.6 |
| C6-R8 | C6.8, C6.9 |
| C6-R9 | C6.7 |
| C6-R10 | C6.8, C6.9 |
| C6-R11 | C6.10 |
| C6-R12 | C6.10 |
| C6-R13 | C6.4, C6.7, C6.9, C6.10, C6.11 |
| C6-R14 | C6.12 |
| AC1.1..AC9.3 | C6.1, C6.3, C6.4, C6.5, C6.7, C6.8, C6.9, C6.10 |

#### C7
| Requirement | Tasks |
|---|---|
| C7-R1 | C7.1 |
| C7-R2 | C7.1 |
| C7-R3 | C7.4 |
| C7-R4 | C7.8, C7.9 |
| C7-R5 | C7.5, C7.6 |
| C7-R6 | C7.2 |
| C7-R6a | C7.2, C6.11 |
| C7-R7 | C7.10 |
| C7-R8 | (already in place — no work) |
| C7-R9 | C7.12 |
| C7-R10 | C7.11 |
| C7-R11 | C7.7 |
| C7-R12 | C7.1 |
| C7-R13 | C7.3, C7.6 |
| C7-R14 | C7.1 |
| AC1..AC10 | C7.1, C7.3, C7.4, C7.5, C7.6, C7.7, C7.9, C7.10, C7.11, C7.12 |

---

## 6. Open follow-ups carried from earlier passes

Items deferred from earlier passes. None are blocking; each is either turned into a task here or acknowledged as out-of-scope.

1. **`ProcessingDocument` cleanup cron.** Acknowledged as out-of-scope for the take-home; documented in C7.12 (README's Production considerations). The dashboard query (C5.5) handles undeleted rows by JOIN. **No task.**
2. **jqwik-spring Spring Boot 4 support.** Mitigation in place: C4.5 / C4.8 are constructor-injected so the property suite runs without `@SpringBootTest`. C4.8 implementation should confirm jqwik 1.9.x + JUnit Platform engine work without `jqwik-spring`. **Covered by C4.8.**
3. **Stop hook cadence risk.** During C7.10 implementation, time `make test` on the dev machine. If consistently > 600s, split into `PreToolUse` (format + grep) + `Stop` (full check) per c7-platform-spec.md §7. **Covered by C7.10's verification step.**
4. **Round-2 cosmetic notes** (C3 §3.5 prose modality casing; C4-R6 row "asynchronous" parenthetical). Documentation polish; **no task** required.
5. **`inputModality` from `DocTypeDefinition` directly.** C3.6 passes `inputModality` from `DocTypeDefinition` directly to the orchestrator's modality choice — no string conversion. **Covered by C3.6.**
6. **`AppConfig` nested-record enumeration.** Integration plan §3.2 carries the canonical enumeration; C7.3 implements that exact shape. **Covered by C7.3.**

---

## 7. Notes for the reviewer

- Every task carries `Within-component deps` and `Cross-component deps`; the §3 dep graph is the union of the cross-component edges resolved to specific task IDs.
- The integration tasks live in their owning component (C4.6/C4.7 for the C3↔C4 seam; C5.7 for SSE; C5.8 for the cross-stack smoke; C7.9 for the application-data seeder; C6.12 for E2E).
- The `V1__init.sql` baseline is co-owned: each component-table task (C1.5 / C2.3 / C3.1 / C4.3) ships its DDL fragment; C7.4 stitches and ships the file.
- The six concrete `DocumentEvent` records are skeleton-defined in C7.7 so consumers can compile against a stable contract; producers / listeners arrive in their owning tasks (C2.4, C3.8, C3.9, C4.5, C4.6, C4.7).
- Sizing target was half-day to ~2 days per task. C2.4, C3.8, C3.11, C4.5, C4.8, C5.6, C5.7, C6.4, C6.8, C6.9, C7.4, C7.9 are the 2-day tasks — all genuine multi-component or multi-file work.
