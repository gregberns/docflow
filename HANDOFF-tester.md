<!-- PP-TRIAL:v2 2026-04-29 implementation -->

# Tester-lane handoff

This branch runs two agent lanes in parallel:
- **Implementor** writes / refreshes `HANDOFF.md` — code fixes.
- **Tester** (this file) exercises the running system, files beads, plans adjacent work. **Tester does not modify production code.**

# Status

Stack runs end-to-end. Containers were just restarted (`make stop && make start && make build`) so the `df-woj` health-endpoint fix is now live; UI renders the org picker. Backend tests **381/381**, frontend **105/105**, API round-trip clean as of 2026-04-29 evening. The Stop hook is disabled (`.claude/settings.json: disableAllHooks: true`) because of a Gradle 9.4.1 + JDK 25 wart that wipes the test-results dir mid-run; leave it off for now.

# Read these first (in this order)

1. `CLAUDE.md` — conventions, beads, "done means green".
2. `TESTING-PLAYBOOK.md` — read-first guide for the tester lane. 8 layered tests + log conventions + bug-filing protocol + 10 exploratory prompts.
3. `test-logs/TEMPLATE.md` — copy this for your session log.
4. `test-logs/2026-04-29T215332Z-tester-session-broad.md` — the prior session's record so you don't repeat it.
5. `.kerf/project/styling/02-review-pass-1.md` — CSS-rebuild review findings (only relevant if you're touching CSS work).
6. `HANDOFF.md` — what the implementor lane is doing right now.

# What the next session should do — focus: core workflow

The user explicitly wants this session to **drill the core workflow**: upload → classify → extract → review → approve → filed (and the off-paths: flag, resolve, reclassify, retype, reject). Cover it broadly first, then exercise edge cases.

## 1. Pre-flight + L1 smoke (under 1 min)

Open a session log under `test-logs/` (use `TEMPLATE.md`). Then:

- `docker ps` — backend, frontend, postgres all `Up`.
- `curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8551/api/health` — expect **200** now (was 500 last session).
- `curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:5173` — expect 200.
- `docker exec basata-postgres-1 psql -U docflow -d docflow -c 'select count(*) from organizations'` — expect 3.
- `br sync --import-only -vv` — refresh bead DB.

If any of these fail: file a P1 bead and stop. If all green: proceed.

## 2. L4 API round-trip — three orgs (5–10 min)

Repeat the curl-upload + DB-poll pattern from the playbook L4 across **all three orgs** to confirm the pipeline works end-to-end for each:

- `pinnacle-legal` — invoice (e.g. `samples/pinnacle-legal/invoices/von_stuffington_expert_witness_jan2024.pdf`)
- `riverside-bistro` — invoice or receipt
- `ironworks-construction` — invoice or change-order

For each: upload via curl, sleep 12s, query `documents` table, confirm `detected_document_type` matches expectation and `extracted_fields` is populated, confirm workflow_instances row exists at `Review/AWAITING_REVIEW`. Log doc IDs in your session log. Any failure → P1 bead.

## 3. Core workflow via UI — happy path per org (15–20 min)

Open `http://localhost:5173`. For **each of the three orgs** in turn:

1. Pick the org.
2. See the dashboard. Confirm processing rows show, then transition to AWAITING_REVIEW.
3. Click into one document.
4. Verify the PDF renders, the extracted fields appear in the form panel, and StageProgress shows correctly.
5. **Approve** through every stage (Review → next → next → Filed). Watch SSE updates the dashboard in real time.
6. Confirm Filed state in the DB and in the UI.

Log: per-org any visual glitches (raw HTML showing, broken images, layout overflow, console errors). Don't file CSS bugs as new beads — they're tracked under `df-7cr` umbrella. **Do** file functional bugs (wrong fields, wrong stages, SSE not updating, action buttons broken).

## 4. Off-paths — drill each branch (20–30 min)

Each as a separate document so paths don't entangle:

- **Flag → Resolve from Review.** Upload, AWAITING_REVIEW, click Flag, type a comment, submit. Confirm flag banner appears, document status flips. Resolve. Confirm origin restoration (banner says origin = previous stage). Approve to filed.
- **Flag from Approval (origin = approval stage).** Same as above but flag from a later stage; confirm the resolve flow returns to that stage.
- **Reclassify mid-Review.** Upload, AWAITING_REVIEW, click Reclassify, pick a different doc type, submit. Confirm re-extraction kicks off (banner), new fields populate, status returns to AWAITING_REVIEW for the new type.
- **Reject from final stage.** Upload, walk through to last approval stage, click Reject. Confirm doc goes to terminal Rejected state with the rejected stage-progress styling.
- **Retype after approval (terminal-state action).** This is what `df-97e` covers when it lands. Try the action; expected behavior depends on whether the implementor merged df-97e + df-ifz from their wip branch.

For each branch, log the doc ID + the SSE event sequence + the final DB state. **Discrepancies between expected workflow vs observed → P1 bead.**

## 5. Edge cases of the core workflow (15 min)

Pick at least 3:

- **Concurrent uploads** — fire 3 uploads at once via curl `&`. Confirm all three are processed independently, no cross-contamination of fields.
- **Malformed PDF** — upload a non-PDF file (e.g. a .txt renamed to .pdf). Expected: pipeline error path, document marked failed, error visible in UI.
- **Wrong-type classification** — upload a doc that doesn't match any of the org's expected types. Expected: classification error or "uncategorized", surfaces in dashboard.
- **Missing-required-field extraction** — upload a doc the model can't extract a required field from. Expected: extraction-error path, dashboard shows the failure, retry available.
- **SSE drop / reconnect** — open the dashboard, kill the network briefly, restore. Confirm dashboard reconnects and catches up.
- **Switch org mid-flow** — start a doc upload on org A, switch to org B, switch back. Confirm dashboard state is consistent.
- **Browser refresh during processing** — upload, refresh while still in CLASSIFYING. Confirm dashboard re-reads state correctly.

## 6. Re-run eval scoring (5 min)

`python3 eval/harness/run_db_direct.py` — full mode. Last run scored 23/23 doc-type, 106/111 fields. If field accuracy regresses, file a bead.

## 7. Scenario harness — only if df-97e + df-ifz merged (10 min)

Check `git log --oneline -10` for those bead IDs landing on main. If yes:

- Pre-clean: `rm -rf backend/build/test-results/test/binary` (workaround for the Gradle wart).
- Run `cd backend && ./gradlew test --no-daemon --tests 'com.docflow.scenario.*'`.
- If exit code is non-zero AND the wart is the cause, check `backend/build/test-results/test/*.xml` directly for actual pass/fail counts. The wart leaves the dir empty if it crashes early — that means real failures may be hidden.

## 8. CSS review-pass 2 — only if df-qv7 has been implemented (10 min)

- Check if `frontend/src/index.css` exists and `tailwindcss` is in `package.json`.
- If yes: read the file, walk the actual `index.css` `@theme` block against the design tokens listed in `.kerf/project/styling/01-plan.md` and the corrections in `02-review-pass-1.md`. File a bead if the implementation drifted.
- If no: skip; df-qv7 is still open.

# Beads — current state

```
br ready  → 7 unblocked (post-restart):
  df-qv7 P1 — Tailwind v4 toolchain (start of CSS rebuild)
  df-97e P2 — Scenarios 04, 09, 10, 11 (implementor wip)
  df-skw P2 — Scenarios 06, 07, 08, 12
  df-efg P2 — Scenario 05 (concurrent uploads + SSE)
  df-vf8 P3 — ProblemDetail Jackson leak
  df-qwc P3 — SSE Broken Pipe spam
  df-ifz P3 — scenario harness deviations (implementor wip)

Blocked on df-qv7: 8 styling children (df-5ua, df-vw1, df-qcu, df-4p1, df-hly, df-ib5, df-k0u, df-ge4) under umbrella df-7cr.
```

Closed yesterday: `df-woj`, `df-3k9` (implementor), `df-36y`, `df-txl` (earlier).

# Filing bugs

Per `TESTING-PLAYBOOK.md` §"Bug-filing protocol":

```
br create --title="<short>" --description="<body with repro>" --type=bug --priority=1|2|3
```

Cross-link the bead ID into your session log as `[df-xyz]`. Always run `br sync --flush-only` before ending the session.

# Caveats / known state

- **Stop hook is disabled.** `.claude/settings.json` has `disableAllHooks: true`. Don't rely on the hook to enforce green tests — run them yourself per the playbook.
- **Gradle wart.** `./gradlew check` (and `make test`) crashes with `NoSuchFileException: in-progress-results-generic*.bin` ~2 min in. Workaround: clear `backend/build/test-results/test/binary` between runs and inspect the XMLs directly for actual pass/fail. The wart's BUILD FAILED line is unreliable — trust the XMLs.
- **Implementor wip branch.** df-97e + df-ifz are not on main yet. Don't rerun those scenarios assuming they pass; check git first.
- **Container freshness.** If you don't see expected behavior after a backend code change merges, `make stop && make start && make build` to pick it up.

# No blocking question.
