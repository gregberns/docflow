<!-- PP-TRIAL:v2 2026-04-28 implementation -->
# Session Handoff — implementation phase

**Status:** clean. 13 beads tasks closed this session, repo on `implementation`, working tree clean, last commit `e7433bd`. Both pipelines green: `./gradlew check` BUILD SUCCESSFUL, `npm --prefix frontend run check` passes (5/5 tests). 8 ready entry points; 55 tasks remain.

**What we're doing.** DocFlow take-home — multi-tenant document processing. Implementation against the kerf plan in `.kerf/docflow/`; beads (`br`) is the work tracker.

**How to run (the user's standing instructions for this work).**
- **Operate as orchestrator.** Delegate substantive work to sub-agents via the Agent tool. Keep the main thread for coordination, integration, and committing. Run independent tasks in parallel — 2- and 3-way batches landed cleanly this session; the only constraint is one agent per shared file (e.g., one owner of `build.gradle.kts` per batch).
- **Per-task ralph loop:** spawn an implementer agent, then a verifier agent for the same task. The verifier reads the same `br show <id>` + spec, runs `./gradlew check` independently, and checks AC by AC. Verifier passes ⇒ close + commit. Verifier finds defects ⇒ send back to implementer with the defect list.
- **`br show <id>` is the brief.** Beads carries the full task definition (deliverables + ACs + spec refs). Point sub-agents at it directly; don't paraphrase.
- **No permission asks for routine moves.** Commits, format choices, dep version picks, kerf-status flips, brew-installing missing toolchain — all pre-authorized. Ask only for substantive risks: opening a PR, pushing the branch, architectural reversals, scope expansion.

**Closed this session (in order):** C7.1, C7.2, C7.3, C7.5, C7.8, C1.9, C6.1, C1.1, C2.1, C4.1, C5.1, C1.6, C7.7. Foundations + scaffolds + the cross-cutting platform pieces (config, quality gates, event bus, error taxonomy, application shell, seed data) all in place.

**Next step.** Run `br ready` and pick. The eight ready tasks are:
1. **C1.5 / C2.3 / C3.1 / C4.3** — V1 SQL fragment + JPA entity tasks (the V1 co-ownership pattern from the original handoff still applies; their post-V1 verification ACs run only after C7.4 stitches). One of these four should add Flyway + JDBC + spring-data-jpa to `backend/build.gradle.kts`; the others ride on those deps. Worth doing one alone first to land the deps cleanly.
2. **C7.6** — `grepForbiddenStrings` Gradle task + tests. Consumes `config/forbidden-strings.txt` (already committed in C1.9). Modifies build.gradle.kts.
3. **C1.2** — ConfigLoader (Jackson 3 YAML → records). Tests bind the seed YAML.
4. **C2.2** — FilesystemStoredDocumentStorage with atomic-move.
5. **C5.2** — GlobalExceptionHandler with RFC 7807 ProblemDetail. Probably needs `spring-boot-starter-web` (not yet in build).

**Toolchain gotchas (don't lose time re-discovering).**
- macOS host has no Java/Gradle by default. We installed via brew earlier: `brew install gradle` brought OpenJDK 25.0.2 + Gradle 9.4.1. **All gradle commands need:** `export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"; export JAVA_HOME="/opt/homebrew/opt/openjdk";` before `./gradlew`. Without that the `/usr/bin/java` shim fails.
- Spring Boot 4.0.0 + Java 25 + Gradle 9 work; the gradle wrapper is generated for 9.4.1.
- `google-java-format` had to be pinned to **1.28.0** under JDK 25 (older GJF tripped on javac internals).
- Quality gates are live — every backend change must pass Spotless / Checkstyle 10.21.1 / PMD 7.22.0 / JaCoCo ≥0.70 line / Error Prone (replacing SpotBugs per analysis §4.3).
- Pure-value records and enums go into `backend/config/jacoco/exclusions.txt` to keep coverage realistic. The file has explanatory section headers — keep that pattern.

**Cross-component spec resolution worth remembering.** `WorkflowStatus` lives at `com.docflow.config.org.WorkflowStatus` (C1 ownership per c1-config-spec §3.2 / C1-R12), not under `com.docflow.workflow` even though c4-workflow-spec.md's table shows the latter. C4 imports it.

**Files to open first.**
- `.kerf/docflow/SPEC.md` — implementer entry tour.
- `.kerf/docflow/07-tasks.md` — full task list.
- `.kerf/docflow/06-integration.md` — seam shapes; SQL fragments are described here.
- `.kerf/docflow/05-specs/c{N}-*-spec.md` — per-component spec for whichever you pick.
- `AGENTS.md` (= CLAUDE.md) — `'Done' means green`, beads workflow.

**No blocking questions.**
