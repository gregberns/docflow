# Change Spec — C-EVAL-1 (PDFBox text-extraction validation)

**Status: closed.** No implementation work remaining.

## Requirements

R-EVAL-1.1 through R-EVAL-1.3 — all met.

## Outputs (already in place)

- `eval/pdfbox-check/REPORT.md` — verdict ("text-only path is viable") and per-file table covering all 23 samples.
- `eval/pdfbox-check/<org>/<doc-type>/<file>.txt` — extracted text for each sample.
- `eval/pdfbox-check/_scratch/PdfboxCheck.java` — harness compiled outside the gradle build.

## Verification

```
ls /Users/gb/github/basata/eval/pdfbox-check/REPORT.md
```

Expected: file exists. Reading it confirms 23/23 clean.

## Acceptance criteria

All met. This spec is referenced by C-EVAL-3 and C-EVAL-4 as justification, not as work to do.

## Notes for downstream components

- The verdict justifies dropping the `inputModality = PDF` branch (C-EVAL-3).
- The verdict justifies the scenario suite running real PDFBox (C-EVAL-4) — flakiness from the text-extraction layer is not a concern.
