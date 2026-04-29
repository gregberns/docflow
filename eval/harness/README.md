# DocFlow live-LLM eval harness

A small Python script that uploads each labelled sample PDF to the running
DocFlow backend, polls the dashboard until the document is materialized, and
scores the detected `documentType` + `extractedFields` against
`labels.yaml`. It writes a markdown report per run to `eval/reports/`.

This is the "cheap harness" that gives first signal on prompt quality. It's
not the production `make eval` rig — that's a separate ticket.

## Prereqs

1. Backend stack running on `http://localhost:8080` (start with `make start`
   from the repo root). The harness pre-flights `GET /api/organizations` and
   bails fast if it can't reach the Spring Boot service.
2. `ANTHROPIC_API_KEY` set in the backend's environment (via the repo-root
   `.env` consumed by docker compose). If empty, classification + extraction
   will fail and most samples will time out.
3. Python 3.11+ with `requests` and `pyyaml`:

   ```
   python3 -m pip install --user requests pyyaml
   ```

## Usage

From any directory:

```
# Smoke run (1 sample per org, 3 total) — sanity check before burning the full batch
python3 eval/harness/run.py --smoke

# Full batch — all 23 samples
python3 eval/harness/run.py

# Run specific samples
python3 eval/harness/run.py --samples pinnacle-retainer-tanaka riverside-receipt-suspicious-butter

# Override base URL or report path
python3 eval/harness/run.py --base-url http://localhost:8080 --report-path /tmp/run.md
```

## Files

- `run.py` — the harness (stdlib + `requests` + `pyyaml`).
- `labels.yaml` — hand-authored ground truth for all 23 samples. Each entry
  records `org_slug`, `doc_type`, `pdf_path` (relative to
  `problem-statement/samples/`), and 3-5 `expected_fields` that are clearly
  extractable from the document body. Numeric values are decimal strings
  without currency or thousands separators; dates are ISO-8601.
- Reports land in `eval/reports/<UTC-timestamp>.md`.

## Scoring

For each sample the harness records:

- **Doc-type match** — exact equality between `detectedDocumentType` and the
  labelled `doc_type`.
- **Per-field match** — light normalization, then equality:
  - whitespace collapsed and trimmed, lowercased
  - smart quotes / em + en dashes folded to ASCII
  - accents stripped (NFKD + drop combining marks)
  - if the expected value looks numeric, currency symbols + thousands
    separators are stripped on both sides and the values compared as floats

The report includes aggregate accuracy, per-org breakdown, a per-sample
table (latency, status, hit count), and field-level detail for any sample
that didn't get a perfect score.

## Cost

Each sample triggers two LLM calls (classify + extract) against the
Anthropic SDK. Sample text is short, so a full 23-sample run is roughly
$1-3.

## Editing labels

Be conservative — better to label fewer fields well than many wrongly. If a
sample's correct answer is genuinely ambiguous (e.g., the `paymentTerms`
field could plausibly be expressed several ways), drop the field rather
than try to canonicalize it in the normalizer.
