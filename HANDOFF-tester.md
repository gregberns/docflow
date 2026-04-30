<!-- PP-TRIAL:v2 2026-04-30 implementation -->

# Tester-lane handoff

Two-lane convention still in effect: implementor edits code (HANDOFF.md), tester (this file) exercises the running stack and files beads. **Tester does not modify production code** — except docs (README, etc.), which both lanes touch.

# Status

Stack runs clean. **Implementor closed 25 beads this session** (see commit `e303eef`), including every P1 I filed (df-313, df-xj2, df-1xy, df-qzh, df-jsm, df-gza) plus the older df-pv0 / df-nx5. Only 5 beads remain open — all P2/P3 cleanup. The next tester session has very little queue and a big **verification surface**: drill the closed P1 fixes against the running stack.

# Read these first

1. `CLAUDE.md` — conventions, beads, "done means green".
2. `HANDOFF.md` — implementor's view; will tell you what was actually changed and how.
3. `TESTING-PLAYBOOK.md` — layered tests, log conventions, bug-filing.
4. `test-logs/TEMPLATE.md` — copy for your session log.
5. `git log --oneline ^main implementation` — see all the work since branch.

# What the next session should do

## 1. Verify the closed P1s (the priority — 30 min)

Each of these had a clear repro. Re-run it and confirm the fix held:

- **df-nx5** — `curl -i http://localhost:8551/api/health` → expect 200 with small JSON. Bonus: `curl -i http://localhost:8551/api/does-not-exist` → expect 404 (not 500).
- **df-pv0** — Approve → Flag → POST `/review/retype`. Expect: `workflow_instances` flips to `Review/AWAITING_REVIEW` with `workflow_origin_stage=NULL`, `flag_comment=NULL`, and `document_type_id` matching the new doc type.
- **df-gza** — POST `/api/documents/{id}/review/retype` against a non-flagged Review doc → expect 202 Accepted, then `IN_PROGRESS` → eventual completion at the new doc type's Review stage.
- **df-jsm** — flag a doc back from Attorney Approval, then Approve through Review → assert the new approval-stage row has `workflow_origin_stage IS NULL` and `flag_comment IS NULL`.
- **df-313** — load a Pinnacle Invoice in the SPA → assert `Invoice Date` renders as a date picker, `Amount` renders as currency-formatted, and Receipt's `Category` renders as a dropdown.
- **df-xj2** — fire 5 concurrent retypes against different docs → confirm no `connection-acquisition timeout` and that other endpoints stay responsive.
- **df-1xy** — insert 500 in-flight `processing_documents` rows directly via psql → assert dashboard returns ≤ 200 in the processing list.
- **df-qzh** — run `make test`; verify the new IT proves insert+read in the same transaction sees the inserted row through JPA.

Log evidence into `test-logs/<UTC>-tester-p1-verification.md`.

## 2. CSS pass 2 (10 min)

`df-qv7` (Tailwind v4 + design tokens) is closed. Walk `frontend/src/index.css` `@theme` block against `.kerf/project/styling/01-plan.md` + `02-review-pass-1.md`. File a bead if drift.

## 3. Re-drill off-paths from the prior tester log

Once df-pv0 is verified: re-run **off-paths 2, 3, 4** from `test-logs/2026-04-29T225434Z-tester-core-workflow.md` §"Off-paths".

# Open beads (5)

```
df-l81  P2  persistence: consolidate stored_documents + processing_documents INSERT into single writer
df-ys7  P3  persistence: demote public static INSERT_SQL/UPDATE_SQL constants to private
df-9x1  P3  perf: cursor-based pagination for dashboard documents list
df-hre  P3  scenario harness: add succeed-on-retry mechanism to ScenarioLlmExtractorStub
df-myn  P3  scenario 10: express retype-time terminal failure with intact pre-retype Document fields
```

# Caveats

- **Stop hook still disabled** (`.claude/settings.json: disableAllHooks: true`) per Gradle 9.4.1 + JDK 25 wart. Run `make test` yourself.
- **Agent-as-tester limit:** UI visual rendering still can't be validated without Playwright (L7 exempt). All workflow drilling stays via the actions API.
- **README pipeline section was edited late this session** (now uses an ER diagram for the 3-entity domain model + a trimmed Upload→ready-for-Review flow chart). If the implementor lane wants to swing through the README again, that's the most recently changed area.

# No blocking question.
