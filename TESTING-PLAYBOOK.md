# DocFlow Testing Playbook

Read-first guide for any agent acting in the **tester lane**. Walk in cold,
exercise the system in layers, log what you find, file beads for real bugs.

The tester lane never modifies production code (`backend/src/main/**`,
`frontend/src/**`). Tester only reads, runs, observes, logs, and files beads.
Implementor lane fixes code (see `HANDOFF.md`).

---

## How to run a tester session

10-line cold-start:

1. `cd` to the repo root (the working directory of the project).
2. `git status --short` — capture pre-state. Note any WIP not authored by you.
3. `git branch --show-current` and `git rev-parse HEAD` — log these in your session log.
4. Confirm stack is up: `docker ps` should show `basata-backend-1`, `basata-frontend-1`, `basata-postgres-1`. If not, `make start`.
5. `br sync --import-only -vv` to refresh the bead DB from JSONL.
6. Pick a layer (or run several broad layers in order) — see the layered plan below.
7. Open a session log file under `test-logs/` (use the template).
8. Run the layer. Capture command, exit code, summary, and any failures verbatim.
9. For each real failure, file a bead via `br create`. Cross-link bead ID into the session log.
10. End-of-session: `git status --short` confirms only `test-logs/` and (optionally) `TESTING-PLAYBOOK.md` were modified. Do **not** commit other people's WIP.

---

## Pre-flight checklist

Run before any layer:

- [ ] `docker ps` shows backend + frontend + postgres containers `Up`.
- [ ] `.env` exists at repo root (don't print contents). `ls .env` is enough.
- [ ] Anthropic credit non-zero — fastest check: a single classify round-trip
      (L4 below). If 401/429/quota errors, escalate; do not file as a bug.
- [ ] DB seeded — `docker exec basata-postgres-1 psql -U docflow -d docflow -c "select count(*) from organizations"` returns >= 3.
- [ ] Worktree clean OR you've explicitly noted the WIP. If someone else's WIP
      is present, log the file list and proceed without touching those files.

---

## Layered test plan

Layers run cheapest/broadest first. Stop when you've hit your time budget; log
where you stopped.

### L1 — Smoke (target: under 30s)

Stack health. Three probes.

- Backend health: `curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8551/api/health` — expect `200`.
- Frontend root: `curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:5173` — expect `200`.
- Postgres ping: `docker exec basata-postgres-1 psql -U docflow -d docflow -c 'select 1'` — expect `(1 row)`.

Log per probe: command, exit code, HTTP status / SQL result. Any non-200 / non-OK is a P1 bug, file immediately.

### L2 — Backend test suite (target: ~3 min)

Full backend unit + integration. Recommended cleanup of the gradle stale
binaries wart first:

```
rm -rf backend/build/test-results/test/binary
cd backend && ./gradlew check --no-daemon
```

`make test` also works but also runs frontend `check`. For a focused backend
log, use the gradle command above.

Capture: total tests, failed count, failed test FQNs, and one-line cause for
each. **Distinguish** between (a) real failures and (b) failures from the
implementor lane's WIP — read `git status --short` first; failures localized to
files in WIP are likely caused by that WIP, note but don't file beads against
production code.

### L3 — Frontend tests (target: under 1 min)

```
cd frontend && npm run check
```

This umbrella runs `lint`, `format:check`, `typecheck`, and `test:coverage`.
For just the unit suite: `npm run test`. Log each sub-step's pass/fail.

### L4 — API contract round-trip (target: under 1 min)

End-to-end through the real LLM. Takes one Pinnacle invoice from local samples,
uploads it, polls the DB for the resulting row, asserts state.

```
curl -sS -X POST \
  -F "file=@problem-statement/samples/pinnacle-legal/invoices/von_stuffington_expert_witness_jan2024.pdf" \
  http://localhost:8551/api/organizations/pinnacle-legal/documents
sleep 12
docker exec basata-postgres-1 psql -U docflow -d docflow \
  -c "select id, current_stage, document_type_id, classification_status, extraction_status from documents order by created_at desc limit 1"
```

Pass criteria: HTTP 201/202 on upload, document row exists, `classification_status='succeeded'`, `extraction_status='succeeded'`, `current_stage='review'` (or whatever the Pinnacle invoice workflow's first stage is).

A failure here is almost always a real bug or a credit / config issue —
investigate before filing.

### L5 — Scenario harness (target: variable, ~2-5 min)

Stubbed-LLM integration tests under `backend/src/test/resources/scenarios/`.
Run via:

```
cd backend && ./gradlew test --tests 'com.docflow.scenario.ScenarioRunnerIT' --no-daemon
```

These exercise full pipeline branches without burning Anthropic credits.
**Skip this layer if implementor-lane WIP touches `ScenarioRunnerIT.java` or
loose YAMLs in `backend/src/test/resources/scenarios/`** — re-running mid-edit
will surface noise, not bugs. Note the skip in the log.

### L6 — Eval harness scoring (target: ~3-5 min, real LLM cost)

```
python3 eval/harness/run_db_direct.py
```

Outputs to `eval/reports/db_direct_<timestamp>.md`. Compare doc-type and field
accuracy against the most recent baseline (cf. `eval/reports/db_direct_20260429T211251Z.md`: 100% / 95.5%). A regression of >5pp on either metric is a P1 bug.

### L7 — Frontend E2E Playwright (exempt from default; long)

```
cd frontend && npm run test:e2e
```

Run on demand only — first invocation often downloads browsers. Document
result; do not include in default tester sweep.

### L8 — Exploratory (free-form; tester writes notes in log)

Pick 1-2 prompts from "Exploratory prompts" below per session. Click through
the UI or curl the API yourself. Free-form notes in the session log under
`## L8 — Exploratory`. This is where new bugs most often surface.

---

## Logging convention

- Location: `test-logs/`.
- File name: `YYYY-MM-DDTHHMMSSZ-<short-tag>.md` (UTC). Examples:
  `2026-04-29T215332Z-tester-session-broad.md`,
  `2026-04-29T220512Z-eval-regression-investigation.md`.
- Use `test-logs/TEMPLATE.md` as the skeleton.
- Per-layer section captures: command, exit code, summary, raw output snippet (trim long output but keep failures verbatim).
- End each session log with a "Beads filed" table (id, priority, title) and a "Followups" list.

---

## Bug-filing protocol

When a layer fails or exploratory finds something:

1. Reproduce once more. Confirm it's not a cold-cache / first-run flake.
2. Decide: real production bug, or noise from in-flight WIP / infra wart? Only
   file beads for real bugs and clear test-infra defects.
3. File:

```
br create \
  --title="<one line, present tense, what's broken>" \
  --description="<repro steps, expected, actual, log link>" \
  --type=bug \
  --priority=<0|1|2|3|4>
```

Priority: P0 prod-down, P1 high-impact / blocks demo, P2 functional bug worth
fixing soon, P3 small / cosmetic, P4 backlog.

4. Capture the new bead id from `br create` output. Add it to the session log's "Beads filed" table.
5. New evidence on an existing bead goes in the log, not the bead body — cross-link via the bead id.
6. End of session: `br sync --flush-only` so the JSONL reflects new beads.

---

## Exploratory prompts (pick 1-2 per session)

1. **Concurrent approve race.** Open one document detail view, fire `Approve` from two browser tabs nearly simultaneously. Expect: one succeeds, one gets a 409 with a clear ProblemDetail. Watch SSE for duplicate stage transitions.
2. **Malformed PDF upload.** Upload a non-PDF (rename `.txt` to `.pdf`) or a corrupt PDF. Expect: 400-class error, no half-baked document row, ideally no LLM call charged.
3. **Reclassify mid-extraction.** Upload, then immediately `PATCH` the document to change `documentTypeId` while extraction is still running. Expect: re-extract triggered, no double-write.
4. **Switch organization mid-flow.** Open a document detail in org A, manually change the URL `:orgId` to org B. Expect: 403 / 404, not data leakage.
5. **SSE expiry / disconnect.** Open document detail, kill backend container for 10s, restart. Expect: SSE reconnects or shows a clear "disconnected" UI; no zombie spinners.
6. **Approve already-rejected document.** PATCH a `rejected` document with an Approve action. Expect: 409 / 422 with a clear error, no state mutation.
7. **Upload outside supported types.** Pick a document type the org doesn't have (e.g., upload a "lien-waiver" PDF to riverside-bistro). Expect: classified as something or routed to a fallback; document of unsupported type shouldn't silently advance.
8. **Empty / single-page PDFs.** Edge of extractor robustness.
9. **Field with leading/trailing whitespace, currency symbols, dates with locale.** Extractor normalization quality.
10. **Filter+sort the dashboard by every column.** Dashboard repo correctness; see `df-txl` history.

---

## What NOT to do

- Don't fix code. The tester lane reads, runs, logs, files beads. Implementor lane fixes.
- Don't run L7 (E2E) by default. It's slow and the browsers may need download.
- Don't `git stash pop` or `git stash drop` — there may be implementor WIP.
- Don't commit untracked test artifacts you didn't author. Keep diff scoped to `test-logs/` and the playbook.
- Don't modify `backend/src/main/**` or `frontend/src/**` ever in this lane.
- Don't touch in-flight WIP files listed in `git status --short` that you didn't create.
- Don't install dependencies, edit `.env`, or run anything with `sudo`.
- If a command runs longer than 6 minutes, kill it and log "TIMEOUT".
- Don't create beads for someone else's WIP-induced test failure — note in log, move on.

---

## Quick reference

- Backend health: `http://localhost:8551/api/health`
- Frontend: `http://localhost:5173`
- Postgres exec: `docker exec basata-postgres-1 psql -U docflow -d docflow`
- Sample PDFs: `problem-statement/samples/<org>/<type>/*.pdf`
- Eval baseline: `eval/reports/db_direct_20260429T211251Z.md` (100% / 95.5%)
- Recent test result: `make test` was green at 364 backend + 105 frontend on 2026-04-29 (per `HANDOFF-tester.md`)
