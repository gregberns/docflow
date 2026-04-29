<!-- PP-TRIAL:v2 2026-04-29 implementation -->
# Session Handoff — implementation phase

**Status:** clean, both lanes idle. Branch `implementation` at `e30df23`. Backend `./gradlew check` and frontend `npm run check` (105 tests, gate 70/60/70/70) both green. **C6 frontend epic complete** — all 14 children closed (12 numbered + StageProgress mount + onApprove wire-up). 19 beads closed this session; 5 new beads filed (3 already closed same-session).

**Two-lane orchestration in effect.** This session ran as orchestrator with two parallel agents: a backend lane (this session: df-rar P1 boot fix) and a single-stream UI lane (this session: df-6m8.5 → .12 + df-u7q + df-01n). **Cap UI to one agent at a time** — vitest memory was a problem when multiple frontend worktrees ran concurrently. `frontend/vitest.config.ts` now sets `pool: "threads"` with `maxThreads: 2` (commit `92722f3`); a single test pass takes ~55s on cold cache, but memory stays bounded. Backend agents can still run alongside the UI agent — different toolchain.

**There is another agent working on backend/scenario/docs.** Their lane covers df-8x4 (P1), the scenario harness chain (df-sup → df-gum → {df-97e, df-skw, df-efg}), and docs (df-x01, df-if4). They also committed eval tooling and kerf "evals" work this session (commits `a7678f2`, `c1825af`, `cd26477`) and ship the live port-mapping changes (`.env.example` / `docker-compose.yml` / `frontend/vite.config.ts` published backend on host port 18080 to avoid conflicts). **Do not pick up their beads** unless coordinated.

**Unclaimed adjacent work** — fair game if no UI is queued:
- **df-9c2.11** (P2) — `.github/workflows/ci.yml`. Spec at `c7-platform-spec.md §3.5`. Run `make test`, `make build`, then `make start &` + healthcheck wait + `make e2e` + `make stop`. Don't invoke `make eval`.
- **df-9c2.12** (P2, blocked on .11) — `README.md` per `c7-platform-spec.md §4`. Memory feedback `feedback_no_unprompted_prod_caveats.md` applies — keep "Production Considerations" scoped, no padding.

**Two latent bugs filed mid-session** (df-rar agent uncovered while writing the regression test, didn't fix):
- **df-xqh** (P2 bug) — `OrganizationCatalogImpl.loadOnReady` and `PromptLibrary.validateOnReady` share `@Order(LOWEST_PRECEDENCE)`. Production happy-path works deterministically by bean-name ordering; with `seedOnBoot=false` it's ambiguous and crashes.
- **df-9kx** (P3 bug) — `FlywayConfig` conditionals don't activate Flyway under `@SpringBootTest` in Spring Boot 4. `make start` works because the `@Bean(initMethod="migrate")` runs during refresh; only test contexts are affected.

**Next step.** Run `br ready`. If UI work is queued → take exactly one in a worktree. Otherwise df-9c2.11 (CI) → df-9c2.12 (README) is the natural pickup, after a quick check that the other agent isn't already on it (`git log --oneline -5` and look for fresh commits in `.github/` or `README.md`).

**Files to open first** for the likely paths:
- For CI: `Makefile` (existing targets), `c7-platform-spec.md §3.5`, `frontend/playwright.config.ts` (just landed in commit `56d6144`).
- For README: `c7-platform-spec.md §4`, `03-components.md` C7-R9 production-considerations enumeration.
- For any new UI: `frontend/src/routes/DocumentDetailPage.tsx` is the central page; `FormPanel.tsx` is the dispatcher; `useDocumentActions.ts` owns the mutations.

**Heads-up — known LLM/Spring quirks (still relevant).** Spring Boot 4: no `TestRestTemplate`, use `RestTemplate` + `@LocalServerPort`. Narrow `@SpringBootTest` configs that include `com.docflow.workflow` but not `com.docflow.c3` need a `@Bean Mockito.mock(LlmExtractor.class)`. AppConfig's nested records (`Storage`, `Llm`, `Database`, `OrgConfigBootstrap`) are now exposed via `AppConfigBeans` — don't remove that file.

**No blocking questions.**
