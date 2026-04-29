# PDFBox 3.0.3 Text-Extraction Quality Report

## Aggregate summary

| Bucket | Count |
|---|---|
| **clean** | 23 |
| **usable-with-caveats** | 0 |
| **poor** | 0 |
| **Total PDFs** | 23 |

**Overall verdict: text-only path is viable.**

All 23 sample PDFs extract cleanly through the production code path (`Loader.loadPDF(bytes)` + `new PDFTextStripper().getText(document)`, PDFBox 3.0.3, matching `com.docflow.c3.pipeline.TextExtractStep`). No exceptions were thrown. Every body paragraph, label, line item, date, amount, signature line, and footer note appears in the extracted text in reading order. Output sizes range from 513 to 1101 chars, all single-page documents.

## Representative findings

1. **No extraction failures.** 23/23 documents loaded and produced text output.
2. **All key fields preserved.** Invoice numbers, dates, totals, hourly rates, retainer amounts, project codes, matter numbers, and signature blocks come through verbatim.
3. **Tables read row-by-row, left-to-right.** Multi-column tables (Description / Qty / Unit Price / Amount) flatten to single space-separated lines per row. No column garbling, no row reordering, no empty/missing cells. Em-dash placeholders for "no qty/rate" rows are preserved as `—`.
4. **Unicode preserved.** Diacritics (`Señor`, `Périgord`), em-dashes (`—`), en-dashes (`–`), and curly quotes all round-trip correctly.
5. **Bold/headline visual styling is lost** — expected, as `PDFTextStripper` is text-only. The literal text of every emphasized phrase still appears, just without the bold attribute.
6. **Layout adjacency carries minor side effects** that are not quality issues but worth noting:
   - The `BILL TO` block renders the recipient name and street address as a single line (e.g., `Pinnacle Legal Group 1200 Justice Lane, …`) because the source PDF places them on one rendered line.
   - Right-aligned headlines (`INVOICE`, `EXPENSE REPORT`, `RETAINER AGREEMENT`) and their document-id sub-line appear as separate adjacent lines in the extracted text rather than next to the org block. This is reading-order driven by the source's absolute positioning — an LLM consumer reads them without trouble.
   - Receipt-style layouts use space-separated `item xN $price` runs instead of columnar tables — fully readable.

## Per-file results

| PDF path | Text path | Verdict | Notes |
|---|---|---|---|
| `problem-statement/samples/ironworks-construction/change-orders/infinity_pool_to_moat_change_order.pdf` | `eval/pdfbox-check/ironworks-construction/change-orders/infinity_pool_to_moat_change_order.txt` | clean | All fields present (CO#, project code, change description, $45k cost impact, +18 days, signature lines). |
| `problem-statement/samples/ironworks-construction/change-orders/underground_spring_foundation_change_order.pdf` | `eval/pdfbox-check/ironworks-construction/change-orders/underground_spring_foundation_change_order.txt` | clean | Grid coordinates (`B4-B6`), depth, $82,500 cost, +12 days all preserved. |
| `problem-statement/samples/ironworks-construction/invoices/concrete_jungle_phase2_foundation_inv.pdf` | `eval/pdfbox-check/ironworks-construction/invoices/concrete_jungle_phase2_foundation_inv.txt` | clean | 5 line items, subtotal/tax/total intact ($26,705 → $29,001.63). |
| `problem-statement/samples/ironworks-construction/invoices/exotic_aquatic_moat_materials_inv.pdf` | `eval/pdfbox-check/ironworks-construction/invoices/exotic_aquatic_moat_materials_inv.txt` | clean | 6 line items with em-dash placeholders for consultation row. Total $44,678.04 preserved. |
| `problem-statement/samples/ironworks-construction/invoices/thunderbolt_electrical_phase2_inv.pdf` | `eval/pdfbox-check/ironworks-construction/invoices/thunderbolt_electrical_phase2_inv.txt` | clean | Mixed-unit row (`80 hrs $95.00/hr $7,600.00`) reads cleanly. |
| `problem-statement/samples/ironworks-construction/lien-waivers/concrete_jungle_unconditional_waiver.pdf` | `eval/pdfbox-check/ironworks-construction/lien-waivers/concrete_jungle_unconditional_waiver.txt` | clean | Header, all metadata, prose body, signatory all present. |
| `problem-statement/samples/ironworks-construction/lien-waivers/exotic_aquatic_conditional_waiver.pdf` | `eval/pdfbox-check/ironworks-construction/lien-waivers/exotic_aquatic_conditional_waiver.txt` | clean | Conditional language preserved, $44,678.04 echoed in body. |
| `problem-statement/samples/ironworks-construction/lien-waivers/thunderbolt_electrical_conditional_waiver.pdf` | `eval/pdfbox-check/ironworks-construction/lien-waivers/thunderbolt_electrical_conditional_waiver.txt` | clean | "Null and void" condition clause intact. |
| `problem-statement/samples/pinnacle-legal/expense-reports/chen_david_wellington_trust_march.pdf` | `eval/pdfbox-check/pinnacle-legal/expense-reports/chen_david_wellington_trust_march.txt` | clean | Billable Yes/No column preserved per row. Total $742.80 correct. |
| `problem-statement/samples/pinnacle-legal/expense-reports/park_james_henderson_ip_march.pdf` | `eval/pdfbox-check/pinnacle-legal/expense-reports/park_james_henderson_ip_march.txt` | clean | 5 line items, dates, amounts, total $1,576.50 all present. |
| `problem-statement/samples/pinnacle-legal/invoices/absolutely_legitimate_court_reporting_feb2024.pdf` | `eval/pdfbox-check/pinnacle-legal/invoices/absolutely_legitimate_court_reporting_feb2024.txt` | clean | Mixed unit-types row (`487 $7.50/pg`, `18 hrs $150/hr`, `— —`) read correctly. |
| `problem-statement/samples/pinnacle-legal/invoices/dewey_cheatham_howe_feb_2024.pdf` | `eval/pdfbox-check/pinnacle-legal/invoices/dewey_cheatham_howe_feb_2024.txt` | clean | Date range with en-dash (`January 1 – January 31, 2024`) preserved. Footer "Dewey, Cheatham & Howe." wraps to a 2nd line, mirroring the source layout. |
| `problem-statement/samples/pinnacle-legal/invoices/von_stuffington_expert_witness_jan2024.pdf` | `eval/pdfbox-check/pinnacle-legal/invoices/von_stuffington_expert_witness_jan2024.txt` | clean | Multi-credential name (`Dr. Reginald Von Stuffington III, Esq., PhD`) intact. Total $51,987.50 preserved. |
| `problem-statement/samples/pinnacle-legal/retainer-agreements/bigglesworth_estate_retainer_2024.pdf` | `eval/pdfbox-check/pinnacle-legal/retainer-agreements/bigglesworth_estate_retainer_2024.txt` | clean | Long prose scope-of-representation paragraph wraps to 4 lines preserving sentence structure. Both signatory lines present. |
| `problem-statement/samples/pinnacle-legal/retainer-agreements/tanaka_corp_restructuring_retainer_2024.pdf` | `eval/pdfbox-check/pinnacle-legal/retainer-agreements/tanaka_corp_restructuring_retainer_2024.txt` | clean | All fields and prose body intact. |
| `problem-statement/samples/riverside-bistro/expense-reports/chen_lisa_front_of_house_march.pdf` | `eval/pdfbox-check/riverside-bistro/expense-reports/chen_lisa_front_of_house_march.txt` | clean | 4 expense rows, category column preserved, total $288.25. |
| `problem-statement/samples/riverside-bistro/expense-reports/martinez_sofia_kitchen_ops_march.pdf` | `eval/pdfbox-check/riverside-bistro/expense-reports/martinez_sofia_kitchen_ops_march.txt` | clean | 5 rows, em-dashes in descriptions preserved, total $522.09. |
| `problem-statement/samples/riverside-bistro/invoices/artisanal_ice_cube_march_2024.pdf` | `eval/pdfbox-check/riverside-bistro/invoices/artisanal_ice_cube_march_2024.txt` | clean | All 4 line items + Net 15 + total $1,178.31 preserved. |
| `problem-statement/samples/riverside-bistro/invoices/senor_tacos_wholesale_march_2024.pdf` | `eval/pdfbox-check/riverside-bistro/invoices/senor_tacos_wholesale_march_2024.txt` | clean | `Señor` diacritic preserved. $0.00 complimentary row preserved. |
| `problem-statement/samples/riverside-bistro/invoices/truffle_whisperer_march_2024.pdf` | `eval/pdfbox-check/riverside-bistro/invoices/truffle_whisperer_march_2024.txt` | clean | `Périgord` diacritic preserved. Curly quotes around "brain food special" preserved. |
| `problem-statement/samples/riverside-bistro/receipts/comically_large_spoon_receipt_0318.pdf` | `eval/pdfbox-check/riverside-bistro/receipts/comically_large_spoon_receipt_0318.txt` | clean | Receipt-style layout (no explicit columns) reads correctly: `item xN $price`. Dashed visual separators in source are not emitted (they are graphics, not text). |
| `problem-statement/samples/riverside-bistro/receipts/margarita_machine_parts_receipt_0315.pdf` | `eval/pdfbox-check/riverside-bistro/receipts/margarita_machine_parts_receipt_0315.txt` | clean | Same as above. All 5 part lines + tax + total intact. |
| `problem-statement/samples/riverside-bistro/receipts/suspicious_butter_receipt_0312.pdf` | `eval/pdfbox-check/riverside-bistro/receipts/suspicious_butter_receipt_0312.txt` | clean | 3 product rows + tax + total + footer all present. |

## Sample breakdown

- **Riverside Bistro** (8): 3 invoices, 2 expense reports, 3 receipts
- **Pinnacle Legal** (7): 3 invoices, 2 expense reports, 2 retainer agreements
- **Ironworks Construction** (8): 3 invoices, 2 change orders, 3 lien waivers

## Harness

The extraction harness lives at `eval/pdfbox-check/_scratch/PdfboxCheck.java` (compiled `.class` alongside it). Placed outside `src/main/java` and `src/test/java` so it's not subject to project lint/format/build rules and has zero effect on the gradle build. Uses the exact same PDFBox API path as `TextExtractStep`:

```java
byte[] bytes = Files.readAllBytes(pdf);
try (PDDocument document = Loader.loadPDF(bytes)) {
    PDFTextStripper stripper = new PDFTextStripper();
    String text = stripper.getText(document);
    Files.writeString(outFile, text);
}
```

Compiled with `/opt/homebrew/opt/openjdk/bin/javac` (OpenJDK 25.0.2, matching project toolchain) against the cached PDFBox 3.0.3 jars from `~/.gradle/caches/modules-2/files-2.1/org.apache.pdfbox/`. To re-run:

```bash
PDFBOX_JAR=/Users/gb/.gradle/caches/modules-2/files-2.1/org.apache.pdfbox/pdfbox/3.0.3/a739bfc1b72d2f98d973cd1419f5ae2decd36068/pdfbox-3.0.3.jar
PDFBOX_IO_JAR=/Users/gb/.gradle/caches/modules-2/files-2.1/org.apache.pdfbox/pdfbox-io/3.0.3/c40dcf555b72b1d8c0cd19391d63e5b58382b9cb/pdfbox-io-3.0.3.jar
FONTBOX_JAR=/Users/gb/.gradle/caches/modules-2/files-2.1/org.apache.pdfbox/fontbox/3.0.3/9eebd1ee868a79fcf7390283b2baf4179dadb8ed/fontbox-3.0.3.jar
COMMONS_JAR=/Users/gb/.gradle/caches/modules-2/files-2.1/commons-logging/commons-logging/1.3.5/a3fcc5d3c29b2b03433aa2d2f2d2c1b1638924a1/commons-logging-1.3.5.jar
CP="/Users/gb/github/basata/eval/pdfbox-check/_scratch:$PDFBOX_JAR:$PDFBOX_IO_JAR:$FONTBOX_JAR:$COMMONS_JAR"
/opt/homebrew/opt/openjdk/bin/java -cp "$CP" PdfboxCheck \
  /Users/gb/github/basata/problem-statement/samples \
  /Users/gb/github/basata/eval/pdfbox-check
```
