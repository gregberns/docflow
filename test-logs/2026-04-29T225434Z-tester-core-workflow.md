# Tester session log — core workflow drill

- **When:** 2026-04-29T22:54:34Z
- **Branch:** implementation
- **Commit:** 97e8c035dfe01f67d780e043e4a30f69c515a378
- **Agent:** Opus 4.7 / tester lane
- **Pre-state notes:** `M .beads/issues.jsonl` — implementor lane flipped df-qv7 to in_progress (CSS toolchain ticket). Not mine to commit. No other WIP.
- **Plan:** Pre-flight + L1 smoke → L4 round-trip across 3 orgs → core workflow per org via API → off-paths → ≥3 edge cases → eval re-run. Skip L5 scenarios + L7 e2e + L8 styling pass (df-97e/df-ifz not on main; df-qv7 not implemented).

---

## Pre-flight

- [x] `docker ps` shows backend + frontend + postgres `Up` (~11 min)
- [x] `.env` present (assumed via working stack)
- [x] `br sync --import-only -vv` — JSONL hash unchanged from last import; df-qv7 status update in JSONL not yet imported into DB.

```
basata-frontend-1   Up 11 min  0.0.0.0:5173->5173/tcp
basata-backend-1    Up 11 min  0.0.0.0:8551->8080/tcp
basata-postgres-1   Up 11 min (healthy)  0.0.0.0:5432->5432/tcp
```

---

## L1 — Smoke

| Probe | Command | Result |
|---|---|---|
| backend health | `curl /api/health` | **500** (expected 200) → **df-nx5 P1** |
| frontend root | `curl :5173` | 200 ✓ |
| postgres ping | `docker exec ... select count(*) from organizations` | 3 ✓ |
| organizations endpoint (sanity) | `curl /api/organizations` | 200 + JSON ✓ |

### Health probe failure — root-cause

`/api/health` returns **500** with `application/problem+json` ProblemDetail. Backend logs show `NoResourceFoundException: No static resource api/health` bubbling to the generic `@ExceptionHandler(Exception.class)` at `GlobalExceptionHandler.java:90`.

Two compounding issues:

1. **No `/api/health` controller exists** in source. The handoff/playbook's "expect 200" assertion was wrong — even a fresh jar would 404 the route.
2. **Deployed jar predates df-woj fix.** Jar timestamp `Apr 29 19:49 UTC`; df-woj commit `e835c2d` landed at `2026-04-29 21:51 UTC`. Container started at `22:40:58` from the stale jar. Handoff's `make stop && make start && make build` order builds AFTER container start → restart never picks up new image. **Operational, not a code bug;** noted in followups for the user.

Filed **df-nx5 P1**.

---

## L2/L3 — Skipped this session

Skipped backend+frontend test runs this session — focus per handoff was on running-system core workflow drill, not test suite re-run. Last known: 381/381 backend, 105/105 frontend (per HANDOFF-tester.md).

---

## L4 — API contract round-trip (3 orgs)

Uploads + classification + extraction + workflow_instance landing at Review/AWAITING_REVIEW for all 3 orgs:

| Org | Sample | Doc ID | Stored ID | Type | Stage |
|---|---|---|---|---|---|
| pinnacle-legal | von_stuffington_expert_witness_jan2024.pdf | 019ddb74-cbde-... | 019ddb74-b6f1-... | invoice | Review/AWAITING_REVIEW ✓ |
| riverside-bistro | truffle_whisperer_march_2024.pdf | 019ddb74-d1bb-... | 019ddb74-b731-... | invoice | Review/AWAITING_REVIEW ✓ |
| ironworks-construction | thunderbolt_electrical_phase2_inv.pdf | 019ddb74-ced6-... | 019ddb74-b759-... | invoice | Review/AWAITING_REVIEW ✓ |

All 3 uploads HTTP 201; all 3 classified correctly; extracted_fields populated with appropriate schema (riverside has lineItems, pinnacle has matterNumber, ironworks has materials/projectCode). **L4 ✓.**

Schema discrepancy with playbook: actual columns are `documents.detected_document_type` (not `_id` suffix); stage/status live on `workflow_instances`, not `documents`. Updated queries accordingly. Recommend updating TESTING-PLAYBOOK §L4 example query.

---

## Step 3 — Core workflow happy path (API-driven, all 3 orgs)

UI was not exercised — agent has no browser. Drove the equivalent state machine via the actions API. Full Approve chain to Filed for each org:

| Org | Stage chain | Approves | Result |
|---|---|---|---|
| pinnacle-legal | Review → Attorney Approval → Billing Approval → Filed | 3 × HTTP 200 | currentStageId=Filed, currentStatus=FILED ✓ |
| riverside-bistro | Review → Manager Approval → Filed | 2 × HTTP 200 | currentStageId=Filed, currentStatus=FILED ✓ |
| ironworks-construction | Review → Project Manager Approval → Accounting Approval → Filed | 3 × HTTP 200 | currentStageId=Filed, currentStatus=FILED ✓ |

Each transition reflected immediately in the response body's `currentStageId` field. **All happy paths ✓.**

UI visual rendering not validated — out of scope for an agent without Playwright (L7).

---

## Step 4 — Off-paths

Drove each branch on a fresh pinnacle invoice upload.

### Off-path 1 — Flag from Review

| Step | Result |
|---|---|
| Flag from `Review/AWAITING_REVIEW` | **HTTP 409** `INVALID_ACTION` "Action FLAG not allowed in stage Review" |
| Resolve | **HTTP 409** (no flag to resolve) |

**Not a bug.** Flag is intentionally only valid in approval stages — it sends an approval-stage doc back to Review. Flag-from-Review has no semantic meaning. **Playbook description in HANDOFF-tester.md §4 is wrong.** Recommend rewording: "Flag from Approval → Resolve" instead of "Flag → Resolve from Review".

### Off-path 2 — Flag from Approval (origin restoration)

| Step | State |
|---|---|
| Approve from Review | Attorney Approval/AWAITING_APPROVAL ✓ |
| Flag with comment | Review/FLAGGED, origin=Attorney Approval, flag_comment set ✓ |
| Resolve | Attorney Approval/AWAITING_APPROVAL, origin cleared, comment cleared ✓ |

**✓ Working as designed.**

### Off-path 3 — Reclassify (retype) — **P1 BUG**

| Step | State |
|---|---|
| Approve | Attorney Approval/AWAITING_APPROVAL ✓ |
| Flag with comment "Wrong doc type" | Review/FLAGGED, origin=Attorney Approval ✓ |
| POST /review/retype `{newDocumentType:"retainer-agreement"}` | HTTP 202, doc.detected_document_type → retainer-agreement, schema swapped to retainer fields, fields populated ✓ |
| **Wait 30s** | workflow_instance **STILL** `Review/FLAGGED, origin=Attorney Approval, flag_comment set, document_type_id=invoice` (stale!) ✗ |

Backend log confirms root cause:
```
2026-04-29T23:01:45.563Z INFO ExtractionEventListener:
  ExtractionCompleted ignored — documentId=019ddb76-c525-... reextractionStatus=NONE (expected IN_PROGRESS)
```

The completion handler short-circuits because by the time the event fires, `reextraction_status` is already NONE. Doc is permanently stuck FLAGGED with mismatched workflow_instance.document_type_id. Unrecoverable through API.

Unit test `FlagOriginRestorationTest.resolveWithTypeChangeStaysInReviewAndClearsOriginAfterExtractionCompleted` passes via simulated event injection — production integration is broken. **Filed df-pv0 P1.**

### Off-path 4 — Reject from final approval stage

| Step | State |
|---|---|
| Approve from Review | Attorney Approval/AWAITING_APPROVAL ✓ |
| Approve again | Billing Approval/AWAITING_APPROVAL ✓ |
| Reject | Rejected/REJECTED ✓ |

**✓ Working as designed.**

### Off-path 5 — Retype after approval (terminal-state action)

**Skipped** — covered by df-97e on implementor wip branch, not on main (`git log` confirmed). Don't re-test until df-97e merges.

---

## Step 5 — Edge cases

### Edge 1 — Concurrent uploads (3× simultaneous)

3 parallel curl uploads of `artisanal_ice_cube_march_2024.pdf` to riverside-bistro. All 3 returned HTTP 201; all 3 classified as `invoice` independently within 18s (`019ddb7c-dfd6-...`, `019ddb7c-dff9-...`, `019ddb7c-e0b0-...`). No cross-contamination of fields. **✓**

### Edge 2 — Malformed PDF (text masquerading as .pdf)

Created a `.pdf` containing plain text. POST to upload endpoint returned **HTTP 415 UNSUPPORTED_MEDIA_TYPE** with proper ProblemDetail. Defense kicks in at content-type sniffing, not PDF-parse failure. **✓**

### Edge 3 — Wrong-type doc (lien-waiver to riverside-bistro)

Uploaded `concrete_jungle_unconditional_waiver.pdf` (an Ironworks lien-waiver) to riverside-bistro (allowed types: invoice/receipt/expense-report). Classifier picked closest match → `receipt`. Doc advanced to its workflow stages, no crash, would be caught by reviewer at Review stage. **✓ Graceful fallback.**

---

## Step 6 — Eval re-run

```
python3 eval/harness/run_db_direct.py
```

| Metric | This run | Baseline (211251Z) | Δ |
|---|---|---|---|
| doc-type accuracy | 23/23 = 100% | 23/23 = 100% | 0 |
| field accuracy | 105/111 = 94.6% | 106/111 = 95.5% | -0.9pp |
| errors | 0/23 | 0/23 | 0 |

Single-field delta is in riverside-bistro (37 → 36 fields). LLM stochastic variance, well below the 5pp regression threshold. **No bug.**

Report: `eval/reports/db_direct_20260429T230742Z.md`

---

## Step 7 — Scenario harness

**Skipped.** `git log --oneline -20 | grep -E 'df-97e|df-ifz'` shows those bead IDs only appear in session/handoff commits, not as feature merges. Implementor wip branch has not landed on main. Per handoff caveats, don't rerun.

---

## Step 8 — CSS review-pass 2

**Skipped.** df-qv7 was just claimed (in_progress in JSONL) but not yet implemented. `frontend/src/index.css` and `tailwindcss` package don't exist yet.

---

## Beads filed

| ID | Priority | Title |
|---|---|---|
| **df-nx5** | **P1** | Missing /api/health controller — endpoint returns 500 (no route + GlobalExceptionHandler bug compounds it) |
| **df-pv0** | **P1** | Retype completion handler skipped — ExtractionEventListener guard short-circuits, leaves doc stuck FLAGGED with stale workflow_instance |

---

## Followups

- **Operational (user):** Backend container is running a stale jar (Apr 29 19:49 UTC) that predates the df-woj fix (21:51 UTC). Rebuild and restart: `make stop && docker compose build backend && make start`. Even after rebuild, df-nx5 still requires a real `/api/health` controller to return 200.
- **Playbook polish:** Update `TESTING-PLAYBOOK.md` §L4 example query — actual columns are `documents.detected_document_type` (no `_id`) and stage/status live on `workflow_instances`. Update `HANDOFF-tester.md` §4 off-path 1 — flip "Flag from Review" to "Flag from Approval"; the former is correctly rejected by the engine.
- **CSS pass 2 deferred:** Will run when df-qv7 ships.
- **df-pv0 may overlap df-97e:** that ticket covers retype scenarios; verify there's no double-fix when implementor lane closes df-97e from wip.
</content>
</invoke>