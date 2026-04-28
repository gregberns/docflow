# C2 Document Ingestion & Storage — Research Findings

**Post-research revision (component walkthrough with user).** C2's scope narrowed significantly. C2 now owns only `StoredDocument` (file reference: `id, organizationId, uploadedAt, sourceFilename, mimeType, storagePath`). Text extraction (PDFBox) moved to C3's processing pipeline as its first step. The library recommendations in this document still stand; only the bounded context changed.

Short research note — most of C2 is well-understood territory. Three questions covered inline.

---

## 1. Text extraction library — Apache PDFBox vs. Apache Tika

### Options

| Library | Scope | Dependencies | Fit for DocFlow |
|---|---|---|---|
| **Apache PDFBox 3.x** | Pure PDF manipulation + text extraction | ~3 MB | Exactly what we need for PDF text extraction |
| Apache Tika | Broad format router — uses PDFBox for PDFs, POI for Office, image OCR via Tesseract when configured | ~50+ MB transitive | Overkill — we don't extract text from non-PDF types |
| pdf.js (server-side via Node) | Works but requires a Node runtime in the backend container | — | Wrong stack |

### Recommendation: **Apache PDFBox 3.x directly** (now lives in C3, not C2)

> **Bounded-context note.** Post-revision, PDFBox lives in **C3 Processing**'s `pipeline/` sub-package as the first step (text-extract → classify → extract). C2 no longer holds extracted text or `textExtractionStatus` — those belong to `ProcessingDocument` in C3. The library choice and API usage below are unchanged; only ownership moved.

- Samples are all PDFs (per `02-analysis.md` §3.1). Image uploads have no meaningful "text" to extract — C3's pipeline skips extraction for images and sets its `textExtractionStatus = skipped` on the transient `ProcessingDocument`. The LLM handles vision for images (Anthropic supports image content blocks).
- PDFBox 3.x is Java 25-compatible and has a stable `PDFTextStripper` API.
- Tika's advantage (multi-format routing) doesn't apply — we only handle PDFs and images.
- PDFBox's preserve-layout option (`setSortByPosition(true)`) gives reasonable column-aligned text for the clean digital samples. For the nested-table cases where text extraction is lossy, we already plan to send native PDF to Claude per C3 research (hybrid modality).

```java
try (var doc = Loader.loadPDF(bytes)) {
    var stripper = new PDFTextStripper();
    stripper.setSortByPosition(true);
    return stripper.getText(doc);
}
```

**Evidence.** PDFBox 3.0 release notes: https://pdfbox.apache.org/3.0/migration.html

---

## 2. File storage path convention

### Options

| Scheme | Sample | Pros | Cons |
|---|---|---|---|
| `{id}.bin` | `3f2a...-d4c9.bin` | Deterministic; no filename sanitization concerns; decoupled from user input | Can't identify file type from FS |
| `{id}.{ext}` | `3f2a...-d4c9.pdf` | Human-inspectable on disk | Must sanitize `ext` from user input |
| `{org}/{yyyy-mm}/{id}.bin` | `pinnacle/2026-04/3f2a...-d4c9.bin` | Partitioning for future S3 migration | Over-engineered for take-home |

### Recommendation: **`{storageRoot}/{id}.bin`**

Deterministic from `StoredDocument.id`, zero collision risk, no sanitization required. Owned by C2's `StoredDocumentStorage`. `Content-Type` is served from `StoredDocument.mimeType` (stored in DB from upload-time sniffing — see Q3). Orphan detection is a directory scan vs. a DB `SELECT id FROM stored_documents`.

S3 migration is one-for-one: `{s3-bucket}/{id}.bin`. No structural changes to consumers.

---

## 3. MIME type handling

### Options

- **Trust client-claimed Content-Type header.** Fast, but a malicious or buggy client can lie.
- **Sniff bytes on ingestion.** Apache Tika's `tika-core` (or PDFBox's PDF magic detection) identifies type from the first few bytes.
- **Both — trust but verify.** Validate claimed type against sniffed type.

### Recommendation: **Sniff on ingestion via `tika-core`**

Even without auth, corrupted uploads happen (browser bugs, client truncation). A claimed `application/pdf` that is actually a zero-byte file or a partial PDF should be rejected with `INVALID_FILE` (C5-R9a) at upload time, not when the user opens it three clicks later.

`tika-core` is ~5 MB and ships pure-Java magic-byte detection — no Tesseract, no POI, no transitive bloat.

```java
import org.apache.tika.Tika;

Tika tika = new Tika();
String sniffedType = tika.detect(bytes, originalFilename);
if (!ALLOWED_TYPES.contains(sniffedType)) {
    throw new DocflowException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, sniffedType);
}
// Persist sniffedType on StoredDocument.mimeType, ignoring the client-claimed header.
```

`ALLOWED_TYPES = { "application/pdf", "image/png", "image/jpeg" }` — matches the spec's "PDF or image" wording.

**Evidence.** Apache Tika `tika-core`: https://tika.apache.org/2.9.2/

---

## Risks & unknowns

1. **PDFBox on unusual PDFs.** (Now a C3 concern.) The sample corpus is clean digital PDFs. Some real-world PDFs (scanned, encrypted, multi-layer form overlays) may defeat `PDFTextStripper`. Mitigation: C3's `textExtractionStatus ∈ {ok, failed, skipped}` on `ProcessingDocument` allows the pipeline to continue without text; the LLM path uses native PDF in that case.
2. **Tika magic-byte limitations.** Some PDFs with preamble junk may mis-sniff. Fallback: if sniff returns `application/octet-stream`, trust the claimed header as a second opinion, and fail if neither is in `ALLOWED_TYPES`.
3. **Encrypted PDFs.** (Now a C3 concern.) `PDFTextStripper` throws on password-protected files. C3 treats as `textExtractionStatus = failed`; surface a typed error to the user if they retry.

---

## Summary (for Pass 5 change-spec)

- **Text extraction (C3, not C2):** Apache PDFBox 3.x in C3's `pipeline/` sub-package, `PDFTextStripper` with `setSortByPosition(true)`. Skip for images.
- **File storage path (C2):** `{storageRoot}/{storedDocumentId}.bin`, owned by `StoredDocumentStorage`; content-type from `StoredDocument.mimeType`.
- **MIME sniffing (C2):** `tika-core` on ingestion gates upload acceptance; reject non-allowed types with `INVALID_FILE`.
- **Bounded contexts:** C2 owns `StoredDocument` (file reference + Tika sniffing). C3 owns `ProcessingDocument` (transient pipeline state, including PDFBox text extraction).
