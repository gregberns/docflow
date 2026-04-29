<!-- PP-TRIAL:v2 2026-04-29 implementation -->
# Session Handoff — implementor lane

**Status:** clean. Branch `implementation` at `cab12c3`. `make test` green (transient gradle test-result race may flake on first Stop-hook fire — re-run, it'll be UP-TO-DATE).

**Two-lane setup.** This is the **implementor lane** (HANDOFF.md). The **tester lane** is a parallel session writing `HANDOFF-tester.md`. Both commit to `implementation` and share `.beads/issues.jsonl`. Don't read or write `HANDOFF-tester.md`. Tester may pre-bundle implementor-agent commits into their own — before cherry-picking a worktree-agent's commit, `git show <recent-tester-commit> --stat` to confirm the fix isn't already in tree.

**Closed this implementor session (12 beads, 1 tombstone).**
- P1 bugs: df-zfy (Flyway boot), df-q8q (nginx in frontend), df-mch (UUIDv7), df-36y (concurrent-action race; tester pre-bundled in `9a77462`), df-txl (real DashboardRepository).
- P1 tasks: df-8x4 (inputModality removal), df-sup (scenario harness scaffolding).
- P1 misdiagnosis: df-uiq closed externally — actual cause was depleted Anthropic credits, not df-8x4. Now topped up; live eval scored 23/23 doc-type, 95.5% fields.
- P2: df-gum (scenarios 01-03 + runner exec body), df-x01 (spec substrate edits).
- P2 epic: df-9c2 (C7 platform).
- P3: df-if4 (testing-strategy verify), df-mch (UUIDv7) — duplicate `df-v80` tombstoned.

**Ready work (implementor lane).**
- **df-97e, df-skw, df-efg** (P2 scenarios) — all unblocked by df-gum. Each adds YAML fixtures + likely extends `ScenarioRunnerIT` for new assertion types (retype actions, SSE, 409 problem-detail). **Sequence rather than parallelize** — they all touch the same runner file.
- **df-woj** (P2) → **df-vf8** (P3) → **df-qwc** (P3) — exception-handler chain. Sequence; all touch `GlobalExceptionHandler`.
- **df-3k9** (P3 prompt tuning) — pinnacle expense-report `matterNumber` decoration. Single-line prompt edit.

**Tester-lane work (don't pick up unless they hand off):**
- df-qv7 (P1, in flight tester-side) — Tailwind v4 base + Topbar shell. **When df-qv7 lands, 7 styling beads unblock for implementor** (df-7cr epic, df-ib5, df-hly, df-4p1, df-qcu, df-vw1, df-5ua) — those become a 4-5 way parallel fan-out (different components, no file conflict).

**Heads-up.**
- **CWD drift** silently lands you in `.claude/worktrees/agent-XXX/` after various ops. Always `cd /Users/gb/github/basata` or `git -C /Users/gb/github/basata` for top-level ops. `br close` returning "Issue not found" is the canonical symptom.
- **Beads JSONL/DB hash cache** — `br sync --import-only` lies that JSONL is current. Use `br sync --import-only -vv` to force real import after pulling/cherry-picking.
- **Don't let worktree agents create duplicate beads.** If their DB doesn't see the target after `br sync --import-only -vv`, brief them to commit code only and let orchestrator close. (df-v80 was a duplicate of df-mch, tombstoned.)
- **Anthropic credits** are now topped up. Live eval (`make eval` / `python3 eval/harness/run.py`) functional again.
- `eval/harness/__pycache__/` and `eval/reports/` untracked — small follow-up: add to `.gitignore`.

**Worktrees outstanding:** ~11 locked agent worktrees from this session under `.claude/worktrees/agent-XXX`. Harness will clean up.

**Files to open first if picking up scenarios:**
- `backend/src/test/java/com/docflow/scenario/ScenarioRunnerIT.java` — current execution body (df-gum). New scenario tickets extend this.
- `backend/src/test/java/com/docflow/scenario/ScenarioFixtureLoader.java:loadAll(Path)` — directory loader.
- `backend/src/test/resources/scenarios/01-happy-path-pinnacle-invoice.yaml` (and 02, 03) — pattern to follow.

**No blocking questions.**
