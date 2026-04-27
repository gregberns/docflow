# Analyze Review

Sanity-check of `02-analysis.md` against the source spec, mockups, and sample corpus. Summary verdict from the reviewer: the analysis stands up as a factual map; amendments below are worth making but none block later passes from treating this as ground truth.

## Corrections applied

- **§3.4 corpus count was wrong.** Actual total is **23 PDFs**, not 24 (3+3+2 + 3+2+2 + 3+2+3 = 23). Seeding math updated to "~11 of 23 seeded, ~12 available for manual upload."
- **§4 had duplicate subsection numbering** (4.5 and 4.6 appeared twice). Renumbered React → §4.7, PostgreSQL → §4.8, Flyway → §4.9.
- **§1.5 missing gaps.** Added: dashboard sort order (spec silent), authentication / user identity (spec silent, deliberately out-of-scope per problem space but still a spec gap), reject-undo semantics (spec says terminal; no reversal described).
- **§2 mockup filenames** in the catalogue were abbreviated; replaced with real filenames so a reader can open them by path.
- **§2 stats cards** on the dashboard (In Progress / Awaiting Review / Flagged / Filed This Month) were not catalogued. Noted — "Filed This Month" implies a time-scoped query.
- **§4.4 Anthropic models list** flagged as potentially stale (reviewer noted the model IDs will drift between now and project start; the "pin live at project start" note stays but strengthened).

## Non-blocking items not applied

- §2.2 color gloss "purple/pink (current approval)" conflates Review's `#7c3aed` (purple) with approval's `#be185d` (pink). Precise colors aren't load-bearing for decomposition; left as-is.
- §4.2 Gradle "9.x recommended (min 8.14)" unsourced. Standing assertion is correct based on Spring Boot 4.0 release notes; not worth re-citing.
- §4.3 SpotBugs→Error Prone fallback is technically a different class of tool (compiler plugin vs. bytecode analyzer). Acknowledged in the analysis as "Unverified; Error Prone is the safe alternative" — intentionally imprecise. Pass 4 research will nail the exact fallback.
- §3.2 claim about Lien Waiver legal-language distinction between conditional and unconditional variants was verified by filename only, not by PDF content inspection. If the LLM eval depends on the distinction, we re-check in research.
