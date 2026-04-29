# Tester session log — <SHORT-TITLE>

- **When:** <YYYY-MM-DDTHH:MM:SSZ>
- **Branch:** <branch>
- **Commit:** <full sha>
- **Agent:** <model / lane / handle>
- **Pre-state notes:** <e.g. "implementor-lane WIP in backend/src/test/java/com/docflow/scenario/*">
- **Plan:** <which layers will run>

---

## Pre-flight

- [ ] `docker ps` shows backend, frontend, postgres
- [ ] `.env` present
- [ ] `br sync --import-only -vv`

```
<paste docker ps output, abbreviated>
```

---

## L1 — Smoke

| Probe | Command | Result |
|---|---|---|
| backend health | `curl ... /api/health` | <status> |
| frontend root | `curl ... :5173` | <status> |
| postgres ping | `docker exec ... psql ... 'select 1'` | <result> |

Notes:

---

## L2 — Backend test suite

Command:

```
rm -rf backend/build/test-results/test/binary
cd backend && ./gradlew check --no-daemon
```

Exit code: <n>
Total tests: <n>, failed: <n>

Failures:

| FQN | One-line cause | WIP-induced? |
|---|---|---|
| <pkg.Class.method> | <cause> | <yes/no> |

Raw output (trimmed to failures):

```
<paste verbatim>
```

---

## L3 — Frontend tests

Command: `cd frontend && npm run check`
Exit code: <n>

| Sub-step | Result |
|---|---|
| lint | <pass/fail> |
| format:check | <pass/fail> |
| typecheck | <pass/fail> |
| test:coverage | <pass/fail, X tests> |

Failures: <none / details>

---

## L4 — API contract round-trip

Sample: `<path>`

Upload command + response:

```
<curl command>
<response body / status>
```

DB query + result:

```
<psql query>
<rows>
```

Verdict: <pass/fail>

---

## L5 — Scenario harness (if run)

<...or "Skipped — implementor WIP touches scenario files">

---

## L6 — Eval harness (if run)

<...or "Not run this session">

---

## L8 — Exploratory (if run)

Prompt(s) selected:

Findings:

---

## Beads filed

| ID | Priority | Title |
|---|---|---|
| | | |

---

## Followups

-
