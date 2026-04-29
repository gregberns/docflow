# Research — C-EVAL-1 (PDFBox text-extraction validation)

**Status: closed.** No open research questions; the component is already implemented and the verdict is recorded.

## Summary

`eval/pdfbox-check/REPORT.md` documents a 23/23 clean verdict. PDFBox 3.0.3 (`Loader.loadPDF` + `PDFTextStripper`, identical to `TextExtractStep`) extracts every body paragraph, label, line item, date, amount, signature line, and footer note in reading order across the full sample corpus.

Findings consumed by downstream components:

1. **The text-only path is viable.** This justifies C-EVAL-3 (drop PDF modality) and C-EVAL-4 (scenario tests can rely on real PDFBox without flakiness).
2. **No format-specific quirks block the scenario harness.** All three orgs' samples — the busiest tables (Pinnacle invoices, Ironworks change orders), the Unicode-heavy receipts (`Señor`, `Périgord`, em-dashes, curly quotes) — round-trip cleanly. Scenario fixtures can reference any of the 23 PDFs.
3. **Some adjacency artifacts exist but do not affect correctness.** `BILL TO` joins recipient name + street; right-aligned headlines float as separate lines. Documented; LLM-readable.
4. **Bold styling is lost.** Text-only by design. Not relevant to scenario assertions, which compare canned classification/extraction values.

## Outputs

- `eval/pdfbox-check/REPORT.md` — verdict + per-file table.
- `eval/pdfbox-check/<org>/<doc-type>/<file>.txt` — extracted text for each sample.
- `eval/pdfbox-check/_scratch/PdfboxCheck.java` — harness; isolated from the gradle build.

No further work.
