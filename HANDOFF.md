<!-- PP-TRIAL:v2 2026-04-29 implementation -->
# Session Handoff — implementation phase

**Status:** clean. Branch `implementation` at `f307351`. `./gradlew build` GREEN, `frontend npm run check` GREEN. **9 P2 tasks + 1 P3 chore landed this session** across five orchestrated 2-way parallel batches plus inline glue: df-6aj fix (`39e0e9a`), C4.6 (`b5a240b`), C5.6 (`4cb14dc`), C4.7 (`b290d02`), C4.8 (`325dfe9`), C5.7 (`715682f`), C6.2 (`e384e51`), df-5uw engine wiring (`f235fe8`), C5.8 (`473acdb`), C4.9 (`c93f6d9`). The full backend pipeline now runs end-to-end at the Java layer; frontend has its API client.

**What we're doing.** DocFlow take-home, multi-tenant doc processing. Working through kerf plan + beads (`br`). Backend is essentially feature-complete except for chore-grade tasks: C1–C5 + C7 all have their core pieces, C4 has full property-test + contract-test coverage, C5 has its only HTTP-seam smoke test (gated on `ANTHROPIC_API_KEY`), and the WorkflowEngine is wired as `@Component` in production. Frontend has C6.1 (shell) + C6.2 (API client + types + MSW) — pages still to build.

**Next step.** Run `br ready`. Highest-leverage targets:

- **df-g1x** (P2 **bug filed this session**) — production seed YAMLs (Ironworks, Pinnacle, Riverside) define **zero `FLAG` transitions** on approval stages. A real user clicking "Flag" would get `InvalidAction` 100% of the time. C4.9 synthesizes the missing transitions in tests; production seed needs them added. Likely one transition per approval stage `(approvalStage → reviewStage, FLAG, no guard, comment required)`. **This is content + light coding, not deep design.**
- **C4.10** (`df-ln9.10`) — grepForbiddenStrings sweep over C4 source. Small chore, ~1–2h.
- **C6.3** (`df-6m8.3`) — OrgPickerPage. 16h frontend.
- **C6.4** (`df-6m8.4`) — Dashboard skeleton + stats bar + filters. 16h frontend. Pairs nicely with C6.3 in parallel — different routes, shared API client already in.

**Heads-up — Spring Boot 4 quirk surfaced this session.** `TestRestTemplate` was removed from `spring-boot-test*` jars in Boot 4. For `RANDOM_PORT` tests, use plain `RestTemplate` + `@LocalServerPort`. C5.8's `HappyPathSmokeTest` is the canonical example. Add to the existing Spring Boot 4 quirks list (`tools.jackson.*`, `org.springframework.boot.persistence.autoconfigure.EntityScan`, `MockMvcBuilders.webAppContextSetup`).

**Heads-up — narrow `@SpringBootTest` configs may need a mock `LlmExtractor`.** Now that `WorkflowEngine` is `@Component`, any test whose `scanBasePackages` includes `com.docflow.workflow` but NOT `com.docflow.c3` will fail to wire the engine. `SeedManifestTest` was patched this session with a `@Bean Mockito.mock(LlmExtractor.class)`. If new tests trip the same wire, mirror that fix.

**Open follow-up beads filed this session.**
- **df-fwr** (P3) — refactor `ExtractionEventListener` inline `CLEAR_FLAG_KEEP_STAGE_SQL` into a new `WorkflowInstanceWriter` method (`clearOriginKeepStage`). Listener currently carries `NamedParameterJdbcOperations` to bypass `clearFlag`'s "return to origin stage" semantics, which don't match retype completion's "stay at Review" requirement.

**Files to open first.**
- For df-g1x: `backend/src/main/resources/seed/organizations.yaml` and the 9 workflow YAMLs (one per org/doc-type — `backend/src/main/resources/seed/<org>/<docType>-workflow.yaml` or similar; verify path). C4.9's `FlagOriginRestorationTest.java` enumerates all 8 approval-stage names by parsing the YAMLs — its parser code is a free reference.
- For C4.10: `.kerf/project/docflow/specs/c4-workflow-spec.md` (forbidden-string list) and `backend/build.gradle.kts` (find the existing `grepForbiddenStrings` task).
- For C6.3 / C6.4: `frontend/src/routes/` (existing pages from C6.1), `frontend/src/api/` (just landed), and `problem-statement/mockups/` for the design.

**Toolchain (unchanged).** `export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"; export JAVA_HOME="/opt/homebrew/opt/openjdk"`. Build from `backend/`. Frontend: `cd frontend && npm run check`.

**Worktrees.** All this session's `.claude/worktrees/agent-*` are cleaned up. Older worktrees from prior sessions still around — discard at leisure.

**No blocking questions.**
