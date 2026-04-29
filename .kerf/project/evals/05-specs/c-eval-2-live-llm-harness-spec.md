# Change Spec — C-EVAL-2 (Live-LLM end-to-end harness)

**Status: implementation complete; awaiting first user-driven run.**

## Requirements

R-EVAL-2.1 through R-EVAL-2.4 — code-complete. Run-complete pending the user starting the stack and providing an API key.

## Outputs (already in place)

- `eval/harness/run.py` — driver. Uploads each PDF, polls for `AWAITING_REVIEW`, captures the resulting `DocumentView`, compares against labels.
- `eval/harness/labels.yaml` — 23-sample label set.
- `eval/harness/README.md` — run instructions.

## Verification

```
make start
ANTHROPIC_API_KEY=<key> python3 /Users/gb/github/basata/eval/harness/run.py
ls /Users/gb/github/basata/eval/reports/
```

Expected: a timestamped report file is produced.

If the stack is down or the key is absent, the harness emits a clear status note and exits non-zero (per R-EVAL-2.3).

## Acceptance criteria

All four met at code level. A successful first run produces a report; that is the user's gate.

## Notes for downstream components

- The Python harness is referenced by the testing-strategy doc (C-EVAL-5) as the worked example of HTTP-driven end-to-end testing.
- It is **not** in `make test` and **not** in CI.
