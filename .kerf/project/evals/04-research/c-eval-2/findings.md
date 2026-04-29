# Research — C-EVAL-2 (Live-LLM end-to-end harness)

**Status: closed.** Code is written; awaiting first run from the user.

## Summary

`eval/harness/run.py` plus `eval/harness/labels.yaml` and `eval/harness/README.md` constitute a Python harness that drives the running stack via the public HTTP API. Differences from `./gradlew evalRun` (just-landed C3.11) are deliberate and complementary, not redundant.

## Findings consumed by downstream components

1. **`evalRun` and the Python harness measure different things.**
   - `evalRun` calls `LlmClassifier.classify(...)` and `LlmExtractor.extractFields(...)` directly, in-process, with rawText injected from a fixture. It bypasses HTTP, PDFBox, the orchestrator, and the workflow. Signal: prompt quality on a 12-sample corpus.
   - The Python harness uploads each PDF via `POST /api/organizations/{orgId}/documents`, polls `/api/organizations/{orgId}/documents` until `AWAITING_REVIEW`, then captures the `DocumentView` and compares against `labels.yaml`. Signal: full-pipeline end-to-end on the 23-sample corpus.

2. **The two co-exist.** The Python harness fails loudly on schema/API regressions but is less precise about prompt quality (errors compound through PDFBox + classify + extract). `evalRun` is precise about prompt quality but indifferent to plumbing.

3. **Both run on demand.** Neither is in `make test`. Neither runs in CI. Both require `ANTHROPIC_API_KEY`. The Python harness additionally requires the stack to be running (`make start`) — its skip-cleanly behavior surfaces a clear status note instead of a hard failure when the stack is down.

4. **Reusable for the testing-strategy doc.** C-EVAL-5 references the Python harness as the worked example of HTTP-driven end-to-end testing.

## Outputs

- `eval/harness/run.py` — driver.
- `eval/harness/labels.yaml` — 23 hand-authored labels.
- `eval/harness/README.md` — run instructions.

No further work.
