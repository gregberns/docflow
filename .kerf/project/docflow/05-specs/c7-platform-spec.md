# C7 — Platform & Quality (Change Spec)

Pass 5 implementation spec for the connective tissue: Makefile, docker-compose, Flyway, build tooling, quality gates, seed data, the Stop hook, CI workflow, and the README. C7 is not a runtime component — every other component depends on what is built here.

---

## 1. Requirements (carry-forward)

| ID | Requirement | Verification |
|---|---|---|
| **C7-R1** | `docker-compose.yml` at repo root brings up backend, frontend, and Postgres with one `docker compose up`. | `make build && make start` ; `docker compose ps` shows three services. |
| **C7-R2** | `.env.example` documents every required variable (minimum `ANTHROPIC_API_KEY`); `.env` is in `.gitignore`. | Grep `.env.example` ; `git check-ignore .env`. |
| **C7-R3** | Postgres schema is managed by Flyway under `backend/src/main/resources/db/migration/`. Initial schema is a single baseline `V1__init.sql`. Migrations are never edited once applied. | Boot brings up a fresh DB cleanly ; `FlywayBaselineTest` asserts only `V1__init.sql` is present. |
| **C7-R4** | A deterministic seed manifest at `backend/src/main/resources/seed/manifest.yaml` lists ~half of the 23 sample PDFs with ground-truth `documentType` and `extractedFields`. The seeder bypasses `ProcessingDocument` entirely — one transaction inserts `StoredDocument` + `Document` (`processedAt = now`, `reextractionStatus = NONE`) + `WorkflowInstance` (`currentStageId = Review`, `currentStatus = AWAITING_REVIEW`). No LLM call at seed time. | `SeedManifestTest` compares post-seed `documents` rows to the manifest. |
| **C7-R5** | Backend Gradle build includes Spotless, Checkstyle, PMD, JaCoCo (≥70% line coverage), Error Prone (preferred over SpotBugs per analysis §4.3), and `grepForbiddenStrings`. The grep task is wired into `check`. `./gradlew build` fails on any violation. JaCoCo exclusions live in `backend/config/jacoco/exclusions.txt`. | `./gradlew check` exit 0 on a clean tree ; injected violation fails the task. |
| **C7-R6** | Frontend build includes ESLint (warnings = errors), Prettier `--check`, TypeScript strict, Vitest, Playwright. `npm run build` fails on any of the above. | `npm --prefix frontend run check` exit 0. |
| **C7-R6a** | Frontend coverage threshold lives in `frontend/vitest.config.ts` under `test.coverage.thresholds`. C7 reserves the slot only — the actual numeric value is set in C6's spec. The placeholder commit is `0` with a `// TODO(research): set coverage threshold` comment. | Grep `frontend/vitest.config.ts` for the TODO comment ; threshold key present. |
| **C7-R7** | A `.claude/settings.json` Stop hook runs `make test` and blocks the agent's "done" signal on non-zero. | Hook fires; injected lint violation blocks Stop. |
| **C7-R8** | `AGENTS.md` at repo root documents conventions; `CLAUDE.md` is a symlink to `AGENTS.md`. (Already in place.) | `readlink CLAUDE.md` returns `AGENTS.md`. |
| **C7-R9** | `README.md` at repo root documents: how to run (`cp .env.example .env`, fill `ANTHROPIC_API_KEY`, then `make build && make start`), the design decisions (no-auth, local FS vs S3, Java/Spring versions, Anthropic choice, SSE), how seeded data is loaded on first boot only, how to run `make eval`, how to add labeled samples, and a "Production considerations" section covering the items enumerated in 03-components.md C7-R9. | Manual: README sections present. |
| **C7-R10** | A CI workflow at `.github/workflows/ci.yml` invokes `make test` and `make e2e`. `make eval` is on-demand only. | GitHub Actions run is green on PR. |
| **C7-R11** | A `DocumentEventBus` is provided at the platform layer — Spring's `ApplicationEventPublisher` wrapper plus `@Async @EventListener` subscribers. C3 publishes processing events; C4 publishes workflow events; C5 subscribes for SSE fan-out. `@EnableAsync` + `spring.threads.virtual.enabled=true`. | `DocumentEventBusTest` asserts publish→listener delivery. |
| **C7-R12** | docker-compose runs frontend + backend on a shared network; the host-side dev server (`npm run dev`) uses Vite proxy to forward `/api/**` and `/api/**/stream` to `http://localhost:8080`. CORS denies by default. | Manual smoke ; CORS contract test. |
| **C7-R13** | Startup-only external-input loading. All env vars / external configuration are read once into a typed immutable `AppConfig` (records + `@ConfigurationProperties` + `@Validated`) and injected. Missing/invalid config fails startup, never a runtime 500. Model ID is `AppConfig.llm.modelId = "claude-sonnet-4-6"`. No code outside the `AppConfig` package may reference `System.getenv`, `@Value`, or read `.env` directly — enforced by `grepForbiddenStrings`. | `MissingApiKeyStartupTest` asserts startup fails on empty key ; `grepForbiddenStrings` rejects an injected `@Value` outside `AppConfig`. |
| **C7-R14** | `Makefile` at repo root provides the unified developer surface: `make build`, `make start` (alias `up`), `make stop` (alias `down`), `make test`, `make e2e`, `make eval`. The Stop hook (C7-R7) and CI (C7-R10) both invoke `make test` — one source of truth for the fast gate. | Each target's exit code matches the underlying tool ; aliases resolve. |

---

## 2. Research summary (C7-relevant)

From `04-research/c4-c5-c7-backend-infra/findings.md`:

- **DocumentEventBus**: Spring `ApplicationEventPublisher` + `@EventListener` with `@Async` on listeners that must not block the publisher. Pair with `spring.threads.virtual.enabled=true`. Sealed `DocumentEvent` interface; every event carries `organizationId`. (§1)
- **AppConfig**: `@ConfigurationProperties` on records + `@Validated` + Jakarta Bean Validation. `BindValidationException` fires before `ApplicationContext` refresh. Test: `@SpringBootTest(properties = "docflow.llm.api-key=")` asserts startup failure. (§5)
- **`grepForbiddenStrings`**: Hand-rolled Kotlin DSL Gradle task. Forbidden patterns enumerated as regexes; the config package is allow-listed for env reads. Wired into `check`. (§6)
- **Stop hook**: `command` runs `cd "$CLAUDE_PROJECT_DIR" && make test` (research draft used `./gradlew check && npm run check` directly; this spec promotes `make test` to satisfy C7-R14's single-source-of-truth requirement). Timeout 600s. Excludes Playwright E2E and live LLM eval. (§8)
- **JaCoCo threshold**: research drafted 95%; this spec adopts **70%** per the C7-focus reminder (correction relative to the research draft). The research-draft 95% is contradicted by 03-components.md C7-R5; the components doc wins.
- **Error Prone over SpotBugs**: per analysis §4.3, SpotBugs has unverified Java 25 support; Error Prone is the safe alternative. Adopted here.
- **Stop hook cadence risk**: full check can creep past 60–90s on slow machines. Mitigation deferred unless measured; not an upfront concern.
- **`@Async` exception swallowing**: register an `AsyncUncaughtExceptionHandler` that logs at ERROR.

---

## 3. Approach

### 3.1 Makefile — unified developer surface

`Makefile` at the repo root is the canonical entry point for build/run/test. It's a thin wrapper around `docker compose`, `./gradlew`, and `npm`. The wrapper exists so that the Stop hook (C7-R7) and the CI workflow (C7-R10) invoke exactly the same fast gate as a developer typing one command.

Targets:

| Target | Action | Notes |
|---|---|---|
| `build` | `docker compose build` | Builds backend + frontend images. |
| `start` (alias `up`) | `docker compose up -d` | Starts the full stack. |
| `stop` (alias `down`) | `docker compose down` | Stops the stack. |
| `test` | `cd backend && ./gradlew check` followed by `npm --prefix frontend run check` | The fast gate. Lint, format, type-check, unit + property tests, coverage threshold. Returns non-zero on first failure. |
| `e2e` | `npm --prefix frontend run test:e2e` | Playwright happy-path + flag-and-resolve, against the running stack. |
| `eval` | `cd backend && ./gradlew evalLive` | Live LLM eval; on-demand only. |

`.PHONY` declared for every target. Aliases (`up`, `down`) are real targets that depend on the canonical name.

### 3.2 Gradle config (backend)

Plugins: `java`, `org.springframework.boot`, `io.spring.dependency-management`, `com.diffplug.spotless`, `checkstyle`, `pmd`, `jacoco`, `net.ltgt.errorprone`.

- **Spotless**: `googleJavaFormat()`; `target("src/**/*.java")`.
- **Checkstyle**: `checkstyle.toolVersion = "10.x"` (pin at scaffold time once Java 25 grammar is verified per analysis §4.3); ruleset `backend/config/checkstyle/checkstyle.xml`. Fallback path: if Checkstyle's Java 25 grammar still trails, swap to PMD-only and document it.
- **PMD**: `pmd.toolVersion = "7.22.0"`; ruleset `backend/config/pmd/pmd-ruleset.xml`.
- **JaCoCo**: `jacocoTestCoverageVerification` with `minimum = 0.70` line coverage. Exclusions enumerated in `backend/config/jacoco/exclusions.txt` and limited per C7-R5 to: generated sources, Flyway migration classes, Spring `@Configuration`, SSE emitter transport-shell classes, and records/value classes whose only methods are accessors.
- **Error Prone**: `errorprone("com.google.errorprone:error_prone_core:<latest>")`; `BugPattern` severity defaults left intact; project-specific suppressions documented.
- **`grepForbiddenStrings`**: hand-rolled Gradle task in Kotlin DSL. See §3.6 for the exact pattern set.

`tasks.check { dependsOn("grepForbiddenStrings") }`. JaCoCo verification is wired into `check` via `tasks.check { dependsOn(jacocoTestCoverageVerification) }`. `tasks.test { useJUnitPlatform { includeEngines("jqwik", "junit-jupiter") } }`.

### 3.3 Flyway baseline

Single baseline file: `backend/src/main/resources/db/migration/V1__init.sql`. Future schema changes are additive (`V2__*`, `V3__*`, never editing V1). The file creates, in order:

1. **Client-data tables** (C1): `organizations`, `document_types`, `workflows`, `stages`, `transitions`.
2. **`stored_documents`** (C2).
3. **`processing_documents`** (C3).
4. **`documents`**, **`workflow_instances`** (C4).
5. **`llm_call_audit`** (C3).

Bounded-context ownership is documented in component sections, not encoded in migration filenames. FKs on every reference; indexes on every FK and on columns used in WHERE / ORDER BY.

The `llm_call_audit` table has three FK slots: `stored_document_id` (always populated), `processing_document_id` (nullable), `document_id` (nullable). The latter two are mutually exclusive — a `CHECK` constraint enforces `(processing_document_id IS NULL) <> (document_id IS NULL)` so every audit row is anchored to exactly one of them. This shape is parked in 03-components.md and confirmed here.

### 3.4 Stop hook structure

`.claude/settings.json`:

```json
{
  "hooks": {
    "Stop": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "cd \"$CLAUDE_PROJECT_DIR\" && make test",
            "timeout": 600
          }
        ]
      }
    ]
  }
}
```

Non-zero exit blocks the agent's "done" signal; output surfaces in the transcript.

### 3.5 CI workflow shape

`.github/workflows/ci.yml`:

- Trigger: `push` to `main` and `pull_request`.
- Single job `verify` on `ubuntu-latest`.
- Steps:
  1. `actions/checkout@v4`
  2. `actions/setup-java@v4` with `temurin` 25
  3. `actions/setup-node@v4` with current LTS
  4. `make test`
  5. `make build`
  6. `make start` (background) + healthcheck wait
  7. `make e2e`
  8. `make stop` (always-run)

`make eval` is **not** in CI — live LLM calls are on-demand only.

### 3.6 Lint / format / static-analysis stack

- **Backend**: Spotless (format), Checkstyle + PMD (lint), JaCoCo (coverage ≥70%), Error Prone (static analysis), `grepForbiddenStrings` (custom guard). Wired into `./gradlew check`.
- **Frontend**: ESLint (warnings = errors), Prettier `--check`, TypeScript strict, Vitest, Playwright. Wired into `npm run check` and `npm run build`.
- **Pre-commit**: `husky` + `lint-staged` configured in C6's spec; this spec only ensures the build itself fails closed.

#### `grepForbiddenStrings` — pattern source and scanning

The task scans `backend/src/main/java/**/*.java`. The config-package allow-list path is `backend/src/main/java/com/docflow/config` — env-reading patterns matched inside that subtree are exempt; everything else fails the build.

**Pattern source.** The literal pattern list is **not** inlined in `build.gradle.kts`. It lives in `config/forbidden-strings.txt`, a plain-text file owned by C1 (see C1 spec §3.6, §4.5). The Gradle task reads this file at execution time and builds its regex set from the file contents:

- One pattern per line.
- Lines beginning with `#` are comments and ignored.
- Blank lines are ignored.
- Each non-comment, non-blank line is compiled as a `Regex`.

The task fails fast (with a clear error message) if `config/forbidden-strings.txt` is missing, unreadable, or contains zero usable patterns after comment/blank stripping. This guarantees the file is wired up and prevents a silent green build if the file is accidentally deleted.

The env-read patterns (`System.getenv`, `@Value`, literal `.env` references — required by C7-R13) are also lines in the same `config/forbidden-strings.txt` file. For those patterns the task suppresses matches whose file path is under `backend/src/main/java/com/docflow/config/`; stage-name and client-slug patterns are forbidden everywhere in `src/main/java`. The allow-list path and the per-pattern scope policy are encoded in the Gradle task itself (not in the text file). Test resources and generated sources are not scanned.

C1 owns the literal list and its grep-fixture coverage (C1 AC-G1, AC-G2). C7 owns the task wiring and adds `GrepForbiddenStringsFileTest` — a small unit test that asserts the task fails fast when `config/forbidden-strings.txt` is absent or contains only comments/whitespace.

A future refinement (noted but not done in this spec): generate the stage-name list from C1 config at build time so adding a new approval stage to a workflow YAML automatically forbids literal use in Java.

### 3.7 Seed data manifest

`backend/src/main/resources/seed/manifest.yaml` lists 12 of the 23 sample PDFs (52%, satisfying "roughly half"). Selection rules:

1. Cover all 9 (org × doc-type) buckets at least once — no bucket left unrepresented.
2. Cover both Lien Waiver `waiverType` values (the only conditional branch in any workflow).
3. Each bucket contributes its lexicographically first sample file(s); deterministic and easy to diff.

Resulting file list (paths relative to `problem-statement/samples/`):

| # | Org × type | Path |
|---|---|---|
| 1 | Riverside / Invoice | `riverside-bistro/invoices/artisanal_ice_cube_march_2024.pdf` |
| 2 | Riverside / Invoice | `riverside-bistro/invoices/senor_tacos_wholesale_march_2024.pdf` |
| 3 | Riverside / Receipt | `riverside-bistro/receipts/comically_large_spoon_receipt_0318.pdf` |
| 4 | Riverside / Receipt | `riverside-bistro/receipts/margarita_machine_parts_receipt_0315.pdf` |
| 5 | Riverside / Expense Report | `riverside-bistro/expense-reports/chen_lisa_front_of_house_march.pdf` |
| 6 | Pinnacle / Invoice | `pinnacle-legal/invoices/absolutely_legitimate_court_reporting_feb2024.pdf` |
| 7 | Pinnacle / Retainer Agreement | `pinnacle-legal/retainer-agreements/bigglesworth_estate_retainer_2024.pdf` |
| 8 | Pinnacle / Expense Report | `pinnacle-legal/expense-reports/chen_david_wellington_trust_march.pdf` |
| 9 | Ironworks / Invoice | `ironworks-construction/invoices/concrete_jungle_phase2_foundation_inv.pdf` |
| 10 | Ironworks / Change Order | `ironworks-construction/change-orders/infinity_pool_to_moat_change_order.pdf` |
| 11 | Ironworks / Lien Waiver (conditional) | `ironworks-construction/lien-waivers/exotic_aquatic_conditional_waiver.pdf` |
| 12 | Ironworks / Lien Waiver (unconditional) | `ironworks-construction/lien-waivers/concrete_jungle_unconditional_waiver.pdf` |

Each entry in `manifest.yaml` carries:

```yaml
- path: ironworks-construction/lien-waivers/concrete_jungle_unconditional_waiver.pdf
  organizationId: ironworks
  documentType: lien-waiver
  extractedFields:
    subcontractor: "..."
    projectCode: "..."
    projectName: "..."
    amount: "..."
    throughDate: "..."
    waiverType: unconditional
```

`extractedFields` ground-truth values are filled in during implementation by reading each PDF; the manifest *file* is committed with the path/org/type rows; the field values are the implementation task's output and verified by `SeedManifestTest`.

The 11 unselected PDFs remain available for upload via the dashboard during demo / E2E.

---

## 4. Files & changes

| Path | Action | Purpose |
|---|---|---|
| `Makefile` | **new** | Unified developer surface (C7-R14). |
| `docker-compose.yml` | **new** | Backend + frontend + Postgres on a shared network (C7-R1, C7-R12). |
| `.env.example` | **edit** | Document `ANTHROPIC_API_KEY`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `STORAGE_ROOT` (C7-R2). |
| `.gitignore` | **edit** | Ensure `.env` is ignored (C7-R2). |
| `.github/workflows/ci.yml` | **new** | CI runs `make test` then `make e2e` (C7-R10). |
| `.claude/settings.json` | **new** | Stop hook runs `make test` (C7-R7). |
| `backend/build.gradle.kts` | **new** | Spotless, Checkstyle, PMD, JaCoCo 70%, Error Prone, `grepForbiddenStrings` task wired into `check` (C7-R5). |
| `backend/settings.gradle.kts` | **new** | Single-module project. |
| `backend/config/checkstyle/checkstyle.xml` | **new** | Checkstyle ruleset. |
| `backend/config/pmd/pmd-ruleset.xml` | **new** | PMD ruleset. |
| `backend/config/jacoco/exclusions.txt` | **new** | Coverage exclusion list (C7-R5). |
| `backend/src/main/resources/db/migration/V1__init.sql` | **new** | Single baseline migration creating all tables (C7-R3). |
| `backend/src/main/resources/seed/manifest.yaml` | **new** | 12-entry seed manifest (C7-R4). See §3.7. |
| `backend/src/main/resources/seed/files/**/*.pdf` | **new** | 12 PDF copies, mirroring the manifest's relative paths. (Resource-path copies of the corresponding files under `problem-statement/samples/`; `problem-statement/` stays read-only.) |
| `backend/src/main/java/com/docflow/config/AppConfig.java` | **new** | The only legitimate config reader (C7-R13). Records + `@ConfigurationProperties` + `@Validated`. |
| `backend/src/main/java/com/docflow/platform/DocumentEventBus.java` | **new** | Wraps `ApplicationEventPublisher` (C7-R11). |
| `backend/src/main/java/com/docflow/platform/AsyncConfig.java` | **new** | `@EnableAsync`; `AsyncUncaughtExceptionHandler` logging at ERROR. |
| `backend/src/main/resources/application.yml` | **new** | `spring.threads.virtual.enabled=true`, Flyway config, `docflow.*` config tree. |
| `backend/src/test/java/com/docflow/platform/SeedManifestTest.java` | **new** | Asserts post-seed `documents` set matches `manifest.yaml`. |
| `backend/src/test/java/com/docflow/config/MissingApiKeyStartupTest.java` | **new** | `@SpringBootTest(properties = "docflow.llm.api-key=")`; asserts `ConstraintViolationException` root cause. |
| `backend/src/test/java/com/docflow/config/GrepForbiddenStringsTest.java` | **new** | Smoke-tests the regex set against fixture strings (positive + negative). |
| `backend/src/test/java/com/docflow/config/GrepForbiddenStringsFileTest.java` | **new** | Asserts the task fails fast when `config/forbidden-strings.txt` is missing, unreadable, or contains zero usable patterns. |
| `frontend/vitest.config.ts` | **edit (placeholder)** | Reserve `test.coverage.thresholds` slot with `0` and `// TODO(research): set coverage threshold` (C7-R6a; C6 owns the actual number). |
| `README.md` | **edit** | Run instructions, design decisions, seed loading, `make eval`, adding labeled samples, "Production considerations" (C7-R9). |

`AGENTS.md` and `CLAUDE.md` (symlink) are already in place; no change.

---

## 5. Acceptance criteria

1. From a clean checkout: `cp .env.example .env && make build && make start` brings up the stack with no host-side setup beyond Docker. (C7-R1, C7-R9)
2. `make test` returns 0 on a clean tree; injecting a Spotless violation, an unused `@Value` outside the config package, a JaCoCo gap below 70%, or a Checkstyle/PMD violation each independently make it return non-zero. (C7-R5, C7-R7, C7-R10, C7-R13, C7-R14)
3. Stop hook fires on agent turn end; injecting any of the above failures blocks the "done" signal. (C7-R7)
4. Flyway: starting against an empty DB applies `V1__init.sql` cleanly; starting against a DB at V1 is a no-op; editing `V1__init.sql` after it has been applied causes Flyway to fail with a checksum error on next start. (C7-R3)
5. After first boot against an empty DB, `documents` rows match exactly the 12 entries in `seed/manifest.yaml` (org, type, fields). `SeedManifestTest` passes. No `ProcessingDocument` rows exist for seeded data. (C7-R4)
6. `grepForbiddenStrings` rejects: a literal stage name in a service class, a client slug in a switch, `System.getenv` in any non-config file, `@Value` outside `com.docflow.config`. It accepts: `System.getenv` inside `com.docflow.config`. (C7-R5, C7-R13)
7. Removing `ANTHROPIC_API_KEY` from the environment and starting the backend fails fast with an error citing `docflow.llm.apiKey` — never a runtime 500. (C7-R13)
8. `DocumentEventBus.publish(event)` returns synchronously; an `@Async @EventListener` runs on a virtual thread and observes the event. (C7-R11)
9. CI run on a green PR is green; CI run on a PR injecting any §5(2) failure is red. (C7-R10)
10. `make e2e` runs Playwright against the running stack; `make eval` is reachable but never invoked from `make test` or CI. (C7-R10, C7-R14)

---

## 6. Verification

Primary command sequence (local dev, also matches CI):

```
cp .env.example .env
# fill ANTHROPIC_API_KEY
make build && make start && make test
make e2e
make eval     # on-demand only; not in CI
```

Per-requirement spot checks:

- **C7-R3**: `find backend/src/main/resources/db/migration -name 'V*.sql'` lists only `V1__init.sql` at scaffold time.
- **C7-R4**: `./gradlew test --tests SeedManifestTest`.
- **C7-R5**: `./gradlew check` (full); `./gradlew grepForbiddenStrings` (isolated).
- **C7-R7**: trigger Stop hook with a deliberate Spotless violation; confirm it blocks.
- **C7-R11**: `./gradlew test --tests DocumentEventBusTest`.
- **C7-R13**: `./gradlew test --tests MissingApiKeyStartupTest`.
- **C7-R14**: `make build && make start && make stop`; each target's exit code matches the underlying tool's.

---

## 7. Error handling and edge cases

- **Failed Flyway migration on startup**: the app fails to start with the migration's error surfaced; no partial schema is left. The fix is always a new migration (`V2__*`), never editing `V1__init.sql`. The fast path back to a clean state in dev is `docker compose down -v` to drop the volume.
- **Missing required env var**: `AppConfig` validation fails before `ApplicationContext` refresh. Error names the offending key (`docflow.llm.apiKey`) and source. No request-time 500.
- **Empty `ANTHROPIC_API_KEY` (set but blank)**: `@NotBlank` on the record field rejects it the same way as missing.
- **Seed manifest references a missing file**: seeder fails at startup with a clear "seed file not found: \<path\>" error, before any partial inserts. The whole seed transaction is single-shot, not file-by-file.
- **Seed manifest references a file already seeded**: the seeder is idempotent on `(organizationId, sourcePath)` — re-running on a populated DB is a no-op.
- **`grepForbiddenStrings` false positive**: the only legitimate exception is the config package, already allow-listed by path. Suppressing the rule via comment is not supported. If a legitimate hit appears, the right move is either renaming the literal or adding it to the C1 catalog.
- **Hook bypass attempts**: a developer running `git commit --no-verify` skips local pre-commit hooks but **not** the Stop hook (which is harness-side) or CI (which gates merge). The fast gate is enforced at three layers: pre-commit, Stop hook, CI — the Stop hook + CI layers cannot be bypassed without explicit user instruction.
- **Stop hook timeout (600s)**: if `make test` exceeds 10 minutes, the hook reports a timeout; treated identically to a failure. Mitigation if observed: split the gate (format + grep in `PreToolUse`, full check in `Stop`) per research §8 risk #6.
- **JaCoCo exclusion drift**: `config/jacoco/exclusions.txt` is reviewed on every PR that touches it. Adding to it without justification is caught at review.

---

## 8. Migration / backwards compatibility

N/A. Greenfield. Single baseline `V1__init.sql`. No production data, no prior schema. Every subsequent schema change is an additive migration; `V1__init.sql` is never edited once committed.
