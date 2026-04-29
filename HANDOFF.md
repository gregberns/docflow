<!-- PP-TRIAL:v2 2026-04-29 implementation -->
# Session Handoff — implementor lane

**Status:** clean. Branch `implementation` at `31064dc`. `make test` green. Stop hook was removed from `.claude/settings.json` mid-session (it was racing on gradle test-results) — the modified `.claude/settings.json` in `git status` is that change, leave it staged for tester to commit.

**Two-lane setup** still in effect — implementor (HANDOFF.md) + tester (HANDOFF-tester.md). Don't touch tester files. Untracked `TESTING-PLAYBOOK.md`, `test-logs/`, `.kerf/project/styling/02-review-pass-1.md`, and modified `HANDOFF-tester.md` are tester's. The `.beads/issues.jsonl` diff is a JSONL re-export from tester sync — leave it.

**Closed this session (2 beads).** df-3k9 (Pinnacle expense-report `matterNumber` prompt → bare digits, scoped to legal tenant), df-woj (3 explicit `@ExceptionHandler` mappings: 404/400/409 instead of catch-all 500). Plus a tiny chore: `eval/harness/__pycache__/` + `eval/reports/` added to `.gitignore`.

**Parked on wip branch `wip/df-ifz-df-97e-leak-recovery` (commit `e02ae41`).** Two beads of agent work that leaked into main and need a design pass before resuming:
- df-ifz wrote a per-input persistent attempt counter (`extraction.errorRecoversOnRetry`) and an eager PDF check.
- df-97e wrote scenarios 04/09/10 YAMLs + ScenarioRunnerIT updates (no scenario 11 yet).
- **Open semantic issue:** scenario 10 asserts terminal extraction failure on retype, but df-ifz's persistent counter means once initial extraction has incremented attempts past 1, retype with `recovers=true` always succeeds. Needs a clear answer to "should the counter scope to the call (extractFields vs extract), or should scenario 10 use a different fixture path?" Format violations + 1 PMD violation also outstanding on the wip branch — run `./gradlew :spotlessApply` before resuming.

**Ready work for implementor (in order):**
- **df-vf8** (P3 bug) → **df-qwc** (P3 bug) — exception-handler chain. Sequence; both touch `GlobalExceptionHandler`. Now unblocked by df-woj.
- df-ifz / df-97e / df-skw / df-efg are blocked behind the wip-branch design issue above.

**Heads-up — agent worktree leak.** Two of four parallel worktree agents this session (df-ifz, df-97e) wrote changes into the main worktree instead of their isolated worktrees, even with strict repo-relative-path briefs. df-3k9 and df-woj behaved correctly. Cause unconfirmed; the workaround is in memory: after every agent-completion notification, run `git status` on main and on the agent's worktree before cherry-picking — if the worktree is empty but main has changes, the worktree commit is empty and you need to commit from main or branch the leak. See [`feedback_verify_main_before_cherrypick.md`](../.claude/projects/-Users-gb-github-basata/memory/feedback_verify_main_before_cherrypick.md) and [`feedback_wip_branch_over_stash.md`](../.claude/projects/-Users-gb-github-basata/memory/feedback_wip_branch_over_stash.md).

**Files to open first:**
- `backend/src/main/java/com/docflow/api/error/GlobalExceptionHandler.java` — for df-vf8 / df-qwc.
- `backend/src/test/java/com/docflow/api/error/GlobalExceptionHandlerContractTest.java` — match its style.
- For wip-branch resumption: `backend/src/test/java/com/docflow/scenario/ScenarioLlmExtractorStub.java` and `backend/src/test/resources/scenarios/10-retype-extraction-fails.yaml` on `wip/df-ifz-df-97e-leak-recovery`.

**No blocking questions.**
