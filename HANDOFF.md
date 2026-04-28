<!-- PP-TRIAL:v2 2026-04-28 implementation -->
# Session Handoff — implementation phase

**Status:** clean. 9 beads tasks closed this session, repo on `implementation`, working tree clean, last commit `a9b5614`. `./gradlew check` BUILD SUCCESSFUL (16 tasks all green incl. `:grepForbiddenStrings` and the Testcontainer ITs). 5 ready entry points; ~46 tasks remain.

**What we're doing.** DocFlow take-home — multi-tenant document processing. Implementing against the kerf plan in `.kerf/docflow/`; beads (`br`) is the tracker.

**How to run (the user's standing instructions, unchanged from prior handoff).**
- **Orchestrator + ralph loop:** delegate to sub-agents, then verify with a separate sub-agent against `br show <id>` + spec ACs. Verifier passes ⇒ close + commit. Verifier finds defects ⇒ back to implementer.
- **Parallel where independent.** Worktree isolation (`isolation: "worktree"`) handled the C2.3/C3.1/C4.3 fan-out and the C7.6/C1.2/C2.2 fan-out cleanly this session — file ownership is the only constraint (one owner of `build.gradle.kts` per batch unless worktrees absorb it). 4-way parallel feasible for the next batch.
- **No permission asks for routine moves.** Substantive risks only (push, PR, architectural reversal, scope expansion).
- **CWD trap:** `br` operates on whatever directory you're in — running it inside a worktree path hits that worktree's beads DB, not main's. Always `cd /Users/gb/github/basata` (or use `git -C <main>` for git) before `br ready` / `br close` / `br create`. Lost ~10 minutes to this; flagging.

**Closed this session (commit order):** C1.5, C2.3, C3.1, C4.3, **type-fix**, C7.4, C7.6, C1.2, C2.2, C5.2. Also filed **df-5my** (P3 chore) for a C1.9 gap (see below).

**Two cross-cutting fixes worth knowing.**
1. **`organizationId` is a `String` slug, not a `UUID`.** C1 ships `organizations.id` as `VARCHAR(255)` (slugs like `pinnacle-legal`); C2.1 had typed it as UUID and propagated through `DocumentEvent` + the 6 event records. Audit confirmed bounded surface; flipped all 12 sites + 2 test fixtures in commit `6e4ccee`. Surrogate UUIDs (stored_documents.id, documents.id, etc.) remain UUID — only category-1 config-slug IDs were wrong.
2. **Spring Boot 4.0.0 ships without `FlywayAutoConfiguration`.** `FlywayConfig` (committed in C7.4 / `3fb331b`) wires it manually with `@ConditionalOnBean(DataSource.class)` + `@ConditionalOnProperty("spring.flyway.enabled", matchIfMissing = true)` so fragment-level ITs that disable flyway keep working unmodified.

**Heads-up: scope expansion in C5.2.** `DocflowException` is `sealed`, but C5.1 only shipped 6 subclasses while C5-R9a / AC10 require the contract test to exercise 11 codes. C5.2 added 3 minimal `permits` subclasses (`UnknownProcessingDocumentException`, `UnknownDocTypeException`, `InvalidFileException`). Verifier ruled this justified — spec §4.3's class list is illustrative, the codes are canonical, and C5.4–C5.6 controllers will need throwers anyway.

**Followup filed: df-5my.** `config/forbidden-strings.txt` (C1.9) is missing the env-read patterns and `.env` literal that c7-platform-spec.md §3.6 / C7-R13 say should live in the file. C7.6's Gradle task hard-codes `System.getenv` and `@Value` to keep the build closed; `.env` is unenforced. Tighten when convenient.

**Next step.** Run `br ready`. The 5 ready tasks are:
1. **C3.2** (df-2zl.2) — ProcessingDocument entity, writer, reader (under `com.docflow.c3.persistence` likely). Depends on C7.4 schema, now landed.
2. **C3.3** (df-2zl.3) — LlmCallAudit record + LlmCallAuditWriter (INSERT-only).
3. **C1.3** (df-sxq.5) — ConfigValidator (CV-1..CV-8). Pure Java; consumes the OrgConfig records and the loader from C1.2.
4. **C7.10** (df-9c2.10) — Stop hook in `.claude/settings.json` running `make test` with 600s timeout.
5. **df-5my** (P3 chore) — see followup above.

C3.2 + C3.3 + C1.3 are independent file-wise (different packages, no shared `build.gradle.kts` touches likely) → 3-way parallel candidate. C7.10 only modifies `.claude/settings.json` and is a one-shot.

**Toolchain gotchas (cumulative).**
- PATH/JAVA_HOME for gradle: `export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"; export JAVA_HOME="/opt/homebrew/opt/openjdk"`. Build from `/Users/gb/github/basata/backend/`.
- Jackson 3 namespace is `tools.jackson.*` (NOT `com.fasterxml.jackson.*`). YAML artifact: `tools.jackson.dataformat:jackson-dataformat-yaml` (BOM-managed at 3.0.2).
- Testcontainers 2.0 artifact names are `testcontainers-postgresql` / `testcontainers-junit-jupiter`; Java packages unchanged.
- google-java-format pinned 1.28.0 (JDK 25).
- `:grepForbiddenStrings` now runs as part of `:check`. New code under `com.docflow.config` is allow-listed for `System.getenv` + `@Value`; stage names and client slugs are forbidden everywhere.
- `WorkflowStatus` lives at `com.docflow.config.org.WorkflowStatus` (C1 ownership), not `com.docflow.workflow` despite c4-workflow-spec.md's table.
- `br sync --flush-only` says "Nothing to export" when the SQLite hash matches; use `--force` if you need to force-export after `br create` / `br close`.

**Files to open first.**
- `.kerf/docflow/SPEC.md`, `.kerf/docflow/07-tasks.md`, `.kerf/docflow/06-integration.md`
- `.kerf/docflow/05-specs/c{N}-*-spec.md` for the picked task
- `AGENTS.md` (= `CLAUDE.md`)

**No blocking questions.**
