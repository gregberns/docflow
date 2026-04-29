<!-- PP-TRIAL:v2 2026-04-29 implementation tester-lane -->

# Tester-lane handoff

This branch has two agent lanes operating in parallel:
- **Implementor** writes / refreshes `HANDOFF.md` — see that file for backend / kerf-evals fix progress.
- **Tester** (this lane, this file) exercises the running system, files beads, plans adjacent work. This session's task notifications and findings live here.

# Status

**Stack runs end-to-end.** Pipeline works post Anthropic credit top-up — Pinnacle invoice round-trip at 21:07Z extracted all 8 fields correctly. UI is reachable at `http://localhost:5173` (Docker `df-q8q` nginx fix landed). But manual testing this session surfaced ~7 bugs filed as new beads. **Don't fix code in the next session — triage and file.**

# Two lanes are running in parallel on this branch

- **Implementor lane** (separate worktree, working through kerf-evals tickets and now bug fixes). They've already closed `df-rar`, `df-zfy`, `df-9kx`, `df-xqh`, `df-9c2.11`, `df-9c2.12`, `df-sup`, `df-8x4`, `df-x01`, `df-if4`, `df-q8q`, plus the C7 epic itself.
- **Testing/triage lane** (this session). Goal: exercise the running system, file beads, plan adjacent work (CSS), do not modify production code.

# Subagent results

**Eval-lite (DB-direct workaround for `df-txl`) — DONE.** Strong signal:
- Doc-type accuracy: **23/23 (100%)** — classification is solid.
- Field accuracy: **106/111 (95.5%)** — extraction is solid.
- New harness: `eval/harness/run_db_direct.py`. Reports: `eval/reports/db_direct_20260429T211251Z.md` (full).
- Pattern worth filing: pinnacle expense-reports lose `matterNumber` 2/2 times — model decorates with `#NNNN - matter-name`. Filed as **`df-3k9`** (P3, prompt tuning).

**CSS planning for `df-7cr` — DONE.** Tailwind v4 + `@tailwindcss/vite` plugin chosen. ~17h across 8 tickets. Plan: `.kerf/projects/basata/styling/01-plan.md`.

| ID | P | Title | Deps |
|---|---|---|---|
| `df-qv7` | 1 | Tailwind install + base theme + Topbar | (toolchain) |
| `df-5ua` | 2 | OrgPickerPage + Card | df-qv7 |
| `df-vw1` | 2 | DashboardPage + stats / filters / table | df-qv7 |
| `df-qcu` | 2 | DocumentDetailPage + PdfViewer + StageProgress | df-qv7 |
| `df-4p1` | 2 | FormPanel + ReviewForm + FieldArrayTable | df-qv7, df-qcu |
| `df-hly` | 2 | ReclassifyModal + FlagModal | df-qv7, df-qcu |
| `df-ib5` | 2 | Replace broken icon images with inline SVGs / emoji tiles | df-qv7, df-5ua, df-vw1, df-qcu |
| `df-ge4` | 3 | Polish + cross-route sanity sweep | all of above |

`df-7cr` umbrella depends on all 8 children. Start work at `df-qv7` (only unblocked styling ticket).

**Test suite + scenario harness review — DONE.**
- `make test` GREEN: 364 backend tests + 105 frontend tests, 2m 49s total. No failures.
- `df-sup` harness review: stub seam correct (`LlmClassifier` + `LlmExtractor`, not `LlmCallExecutor`), profile/wiring/fixture-schema all match the kerf spec. Audit-invariant preserved. Room left for df-gum to build on. **Verdict: ready for df-gum.**
- Two minor spec deviations filed as **`df-ifz`** (P3): extract-retry stub doesn't support "succeed on retry" recovery branch; fixture loader defers PDF-existence check to runtime instead of load-time. Neither blocks the first three df-gum scenarios.
- Minor flake noted (not filed): `./gradlew test --rerun-tasks` fails with stale `in-progress-results-generic*.bin` if prior run was interrupted. Gradle infra wart, not a project bug.

# New beads filed this session

| ID | P | Title |
|---|---|---|
| `df-7cr` | 1 | Frontend has no CSS — umbrella |
| `df-txl` | 1 | DashboardRepository is a stub |
| `df-36y` | 1 | Concurrent Approve race — duplicate SSE events, lost state |
| `df-woj` | 2 | Exception handler maps NoResource/TypeMismatch/OptimisticLock to 500 |
| `df-vf8` | 3 | ProblemDetail leaks Jackson polymorphic-deserializer internals |
| `df-mch` | 3 | ProcessingDocument.id is UUIDv4 instead of UUIDv7 |
| `df-qwc` | 3 | SSE Broken Pipe spams GlobalExceptionHandler |
| `df-3k9` | 3 | Pinnacle expense-report matterNumber consistently decorated |
| `df-ifz` | 3 | df-sup harness deviates from kerf spec on extract-retry + load-time PDF check |
| `df-qv7`–`df-ge4` | 1–3 | 8 CSS sub-tickets (see table above) |

**Closed this session:** `df-uiq` (wrong diagnosis — actual cause was depleted Anthropic credits, not df-8x4 refactor).

# Next steps when resuming

1. `br sync --import-only` first if local DB is stale.
2. Append the three subagent findings to this file.
3. `br ready` and `br list --status=open` for current state.
4. Confirm pipeline still works: `curl -F file=@problem-statement/samples/pinnacle-legal/invoices/von_stuffington_expert_witness_jan2024.pdf http://localhost:8551/api/organizations/pinnacle-legal/documents`, wait 10s, query DB.

# Files to open first

- This file.
- `eval/reports/` — eval-lite scoring output if the agent finished.
- `.kerf/projects/basata/styling/01-plan.md` — CSS plan if that agent finished.
- `br ready` output for currently-actionable work.

# Carryover wisdom from the prior implementor handoff

- **CWD drift with worktrees:** four locked agent worktrees under `.claude/worktrees/agent-*` from prior sessions. Always `cd /Users/gb/github/basata` for top-level operations.
- **Beads JSONL** auto-merges cleanly during cherry-pick but stay alert.
- Spring Boot 4 + Flyway + `@Order` quirks: nested AppConfig records exposed via `AppConfigBeans`; ambiguous `@Order` between catalogs and PromptLibrary now fixed (gap of 100); `@SpringBootTest` without c3 needs a mock `LlmExtractor`.

# Caveats

- Anthropic credits were depleted earlier; user topped up. Pipeline confirmed working at 21:07Z.
- Implementor's local `beads.db` is gitignored (per worktree). They run `br sync --import-only` to see new beads filed in this session.

No blocking questions.
