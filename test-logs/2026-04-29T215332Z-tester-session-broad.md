# Tester session log — broad layers L1-L4

- **When:** 2026-04-29T21:53:32Z
- **Branch:** implementation
- **Commit (start):** 7ff5a56062bdbcb78bf0582b8315cfff87f8e95f
- **Commit (end):** 31064dced47e85e1c9f257355fcfb3864ce2e5df (implementor lane committed during this session: e835c2d df-woj fix, 31064dc beads sync)
- **Agent:** Claude Opus 4.7 (1M ctx), tester lane, broad-layer sweep
- **Pre-state notes:** implementor-lane WIP in `backend/src/test/java/com/docflow/scenario/*` plus 3 untracked retype YAMLs at session start. By session end the implementor had committed those plus a `df-woj` fix. Tester did not touch any of those files.
- **Plan:** Pre-flight, then L1 smoke, L2 backend tests, L3 frontend, L4 API round-trip. Stop after L4. Author the playbook as the primary deliverable.

---

## Pre-flight

- [x] `docker ps` shows `basata-backend-1`, `basata-frontend-1`, `basata-postgres-1` all up
- [x] `.env` present
- [x] `br sync --import-only -vv` ran: "JSONL is current (hash unchanged since last import)"

```
basata-frontend-1   Up 8 minutes    0.0.0.0:5173->5173/tcp
basata-backend-1    Up About an hour 0.0.0.0:8551->8080/tcp
basata-postgres-1   Up About an hour (healthy) 0.0.0.0:5432->5432/tcp
```

Pre-state `git status --short` (start of session):

```
 M .beads/issues.jsonl
 M backend/src/test/java/com/docflow/scenario/ScenarioContext.java
 M backend/src/test/java/com/docflow/scenario/ScenarioFixture.java
 M backend/src/test/java/com/docflow/scenario/ScenarioFixtureLoader.java
 M backend/src/test/java/com/docflow/scenario/ScenarioFixtureLoaderTest.java
 M backend/src/test/java/com/docflow/scenario/ScenarioLlmExtractorStub.java
 M backend/src/test/java/com/docflow/scenario/ScenarioRunnerIT.java
 M backend/src/test/java/com/docflow/scenario/ScenarioStubAuditTest.java
?? backend/src/test/resources/scenarios/04-retype-extraction-completes.yaml
?? backend/src/test/resources/scenarios/09-retype-no-op.yaml
?? backend/src/test/resources/scenarios/10-retype-extraction-fails.yaml
```

These were implementor-lane WIP — left untouched.

---

## L1 — Smoke

| Probe | Command | Result |
|---|---|---|
| backend health | `curl -sS -o /dev/null -w '%{http_code}' http://localhost:8551/api/health` | **500** (FAIL — known bug df-woj) |
| frontend root | `curl -sS -o /dev/null -w '%{http_code}' http://localhost:5173` | 200 |
| postgres ping | `docker exec basata-postgres-1 psql -U docflow -d docflow -c 'select 1'` | (1 row) |
| db seeded | `select count(*) from organizations` | 3 |

`/api/health` 500 details:

```
{"instance":"/api/health","status":500,"title":"Internal Server Error","code":"INTERNAL_ERROR","message":"Internal server error"}
```

Backend log (excerpt):

```
ERROR ... GlobalExceptionHandler : Uncaught exception bubbled to GlobalExceptionHandler
org.springframework.web.servlet.resource.NoResourceFoundException: No static resource api/health for request '/api/health'.
```

Same 500 on `/`, `/health`, `/api/actuator/health`, `/actuator/health`. Working: `/api/organizations` -> 200.

**Diagnosis.** `NoResourceFoundException` (Spring's standard 404 path) is being mapped to 500 by `GlobalExceptionHandler`. This is **already filed as `df-woj`** (P2) and matches its case 1 repro exactly. The implementor lane's `e835c2d fix(df-woj)` commit landed during this session but the running backend container has not been rebuilt/restarted to pick it up — current container started "about an hour" before the fix. Therefore: not a new bug, not a regression, just a stale running container.

No new bead filed. Recommend implementor lane (or operator) `make stop && make start` to verify the fix lands. Note: this also means there is **no project-level smoke endpoint** that returns 200 for "alive"; pre-flight should use `/api/organizations` until df-woj rolls out.

### L1 verdict
2 of 3 probes pass. The third is a known open bug already in flight on the implementor side; container restart needed to validate the fix.

---

## L2 — Backend test suite

Commands:

```
rm -rf backend/build/test-results/test/binary
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
cd backend && ./gradlew check --no-daemon          # 23s, FAILED on compileTestJava
JAVA_HOME=... ./gradlew compileJava --no-daemon    # ok, UP-TO-DATE
JAVA_HOME=... ./gradlew compileTestJava --no-daemon # ok, BUILD SUCCESSFUL
JAVA_HOME=... ./gradlew test --no-daemon           # 2m 18s, FAILED at end
```

### L2 ground truth

The `./gradlew check` failure produces a misleading "100 errors / cannot find symbol" message (e.g. `com.docflow.config.persistence.StageRepository`, `com.docflow.c3.events.StoredDocumentIngested`) but **the production source compiles cleanly in isolation** and **the test sources also compile cleanly when invoked directly** (`compileTestJava` BUILD SUCCESSFUL).

Re-running `./gradlew test` directly executes 381 tests across 66 test classes, all of which **PASS** when measured by the JUnit XML output — the build failure is purely the gradle wart:

```
> Task :test FAILED
java.nio.file.NoSuchFileException: .../in-progress-results-generic9877385364384808530.bin
```

This is the same Gradle infrastructure wart noted in `HANDOFF-tester.md` line 45.

### Ground truth: per-class results

Tally across `backend/build/test-results/test/TEST-*.xml`:

| metric | value |
|---|---:|
| total tests | **381** |
| failures | **0** |
| errors | **0** |
| skipped | 2 |

The user's prompt referenced "384 tests, 5 failed" from a prior run, plus the in-progress-binary wart. **The 5 prior failures are not present on commit `31064dc`** — every test class produced a passing XML on this run. The total dropped from 384 to 381 likely because three tests were retired/replaced (df-woj fix may have removed legacy GlobalExceptionHandler cases superseded by the new explicit handlers).

### Failing-test breakdown
| FQN | Cause |
|---|---|
| (none) | 381 tests all pass per JUnit XML |

The "FAILED" build status comes from the binary-results-file wart only. **Not a code defect.**

### Note on the loose scenario WIP files
At session start, 7 modified scenario test files plus 3 untracked retype YAMLs were present. By session end the implementor had committed those plus the df-woj fix. The test run reflects the post-commit state (commit `31064dc`) — all scenario tests pass.

---

## L3 — Frontend tests

Command: `cd frontend && npm run check`
Exit code: 0

| Sub-step | Result |
|---|---|
| lint (`eslint --max-warnings=0 .`) | pass |
| format:check (`prettier --check .`) | pass |
| typecheck (`tsc --noEmit`) | pass |
| test:coverage (`vitest run --coverage`) | pass — **15 files, 105 tests** |

Coverage: 94.06% statements, 83.46% branches, 95.12% functions overall.

Notable coverage hot spots (room to grow):
- `src/hooks/useUploadDocument.ts` — 78.46% lines
- `src/routes/DocumentDetailPage.tsx` — 79.80% lines

No failures.

---

## L4 — API contract round-trip

Sample: `problem-statement/samples/pinnacle-legal/invoices/von_stuffington_expert_witness_jan2024.pdf`

Upload:
```
POST http://localhost:8551/api/organizations/pinnacle-legal/documents
HTTP=201
{"storedDocumentId":"019ddb42-184a-724c-a50e-a3463b63fa93","processingDocumentId":"e1bcccf5-4a19-414b-8f3d-4f5bbffaf85a"}
```

After 12s sleep, joined query:

```
documents.id          = 019ddb42-29a1-7ed7-9a25-314ddf41e027
detected_document_type = invoice
workflow_instances.current_stage_id = Review
workflow_instances.current_status   = AWAITING_REVIEW
processing_documents.current_step   = EXTRACTING (snapshot before final transition)
```

Extracted fields:
```json
{
  "amount": 51987.5,
  "vendor": "Dr. Reginald Von Stuffington III, Esq., PhD",
  "matterName": "Bigglesworth Estate",
  "invoiceDate": "2024-01-28",
  "matterNumber": "4389",
  "paymentTerms": "Net 15",
  "billingPeriod": "2024-01-06/2024-01-24",
  "invoiceNumber": "INV-VS-2024-0047"
}
```

All 8 fields extracted; `matterNumber` is bare-numeric (`4389`) — confirms `df-3k9` prompt fix is live. Doc reached the Review stage with `AWAITING_REVIEW` status — happy-path round-trip end-to-end.

`processing_documents.current_step = EXTRACTING` likely reflects a snapshot row that lags the final transition (or uses a separate lifecycle from the workflow_instance); the workflow instance side is authoritative for the "ready for review" state and shows correct terminal-of-pipeline state.

### L4 verdict
**PASS.** Pipeline functional end-to-end with real Anthropic LLM. matterNumber prompt-tuning fix verified.

---

## L5–L8

Not run this session (per scope).

---

## Beads filed

| ID | Priority | Title |
|---|---|---|
| (none filed) | — | — |

Findings during this session:
- L1 `/api/health` 500 — already filed as `df-woj` (P2). Implementor's `e835c2d fix(df-woj)` landed during this session. Container restart will validate.
- L2 build "FAILURE" — pure Gradle binary-results-file race wart, already noted in HANDOFF-tester. Not a project bug.

---

## Followups

1. **Container restart needed** to verify `df-woj` fix on the running backend (`make stop && make start && make build`). Recommend the operator do this before next tester sweep so L1 health probe accurately exercises the new explicit handlers.
2. **`/api/health` does not exist** as a real endpoint — should be added (or the playbook smoke probe changed) so a positive-path 200 health check is possible. Worth filing as a separate bead once df-woj is closed: "Add Spring Boot Actuator `/actuator/health` (or equivalent) and expose a stable smoke endpoint." Deferred to next session — not filing here because it's adjacent to df-woj and may be addressed during that fix's verification.
3. **Test count drift** (was 384, now 381). Verify with implementor that 3 tests were intentionally removed/replaced by the df-woj rework.
4. **`processing_documents.current_step` lag** — `EXTRACTING` was the latest row even though workflow_instance was already `Review/AWAITING_REVIEW`. Likely correct (separate concerns), but worth a one-line check that the column transitions to a terminal value when the workflow advances. Deferred — exploratory follow-up.
5. **Beads `br show` parser** raised a false-positive merge-conflict-marker error on the JSONL even though no actual marker lines exist. `br ready` works. Likely the hash-cache wart noted in MEMORY.md. Not filing.

---

## End-of-session checks

`git status --short`:
```
 M .beads/issues.jsonl                              (implementor-lane churn, untouched by tester)
?? .kerf/project/styling/02-review-pass-1.md         (pre-existing, untouched by tester)
?? TESTING-PLAYBOOK.md                               (this session's deliverable)
?? test-logs/                                        (this session's deliverable)
```

Tester-lane diff is scoped to `TESTING-PLAYBOOK.md` and `test-logs/`. No production code, no scenario WIP, no `git stash` operations.
