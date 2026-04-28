# C3 — LLM Pipeline & Eval: Research Findings

**Post-research revision (component walkthrough with user).** Major architectural change: C3 now owns a `ProcessingDocument` entity (transient, deleted on processing success) instead of writing to `document_classifications` and `document_extractions` tables. Text extraction moves into C3 as the first step of the processing pipeline. The LLM-side recommendations below (input modality, prompt versioning, eval scoring, tool-use pattern, recording format) all still apply unchanged. Only the persistence shape changed.

Updated lifecycle: `ProcessingDocumentService.start(storedDocumentId)` → text-extract → update currentStep → classify (LLM call, audited via llm_call_audit) → update currentStep → extract (LLM call) → on success emits `ProcessingCompleted` event with `{detectedDocumentType, extractedFields, rawText}`; C4 creates `Document` + `WorkflowInstance` transactionally and signals C3 to delete the ProcessingDocument.

Re-extraction (user retype in Review) does NOT go through ProcessingDocument — C4 invokes `LlmExtractor.extract(documentId, newDocType)` directly; C3 emits `ExtractionCompleted` with results; C4 updates Document.

`document_classifications` and `document_extractions` tables are gone. `llm_call_audit` remains as the call history. `Document.detectedDocumentType` and `Document.extractedFields` (owned by C4) hold current values.

---

Pass 4 research for the C3 component of DocFlow. Inputs: `01-problem-space.md` (Goals 3, 13, 14; Deferred section), `02-analysis.md` (§3 corpus character, §4.4 Anthropic SDK, §4.5/§4.6 pipeline shape), `03-components.md` C3-R1..C3-R13. Locked facts: model `claude-sonnet-4-6`, `com.anthropic:anthropic-java`, two-call pipeline (classify then extract via tool use), raw text extracted server-side via PDFBox, `llm_call_audit` table, recorded-mode eval for CI.

This document is Pass-4 exploration. It recommends — Pass 5 (change-spec) will pick.

---

## 1. Input modality: text vs. native PDF vs. hybrid

**Question (verbatim).** Input modality: text vs. native PDF vs. hybrid. The corpus is clean digital PDFs (per `02-analysis.md` §3). For each of: text-only (extract with PDFBox/Tika, send as text content blocks), native PDF (send as `document` content block, base64 or Files-API `file_id`), hybrid (text for classify, PDF for extract, OR both in every call), characterize: token/cost profile, expected accuracy impact on hard cases (narrative fields like retainer `scope`, variable-rate line items, receipt category inference), implementation complexity, fixture/recording size implications. Recommend one.

### How Claude handles PDFs (cited)

Per Anthropic's PDF docs ([platform.claude.com/docs/en/docs/build-with-claude/pdf-support](https://platform.claude.com/docs/en/docs/build-with-claude/pdf-support)):

- Claude converts each PDF page into an **image** AND extracts the text; both are provided to the model. This is strictly a superset of what PDFBox text gives us — spatial layout is preserved through the image channel.
- Per-page cost: "typically 1,500–3,000 tokens per page depending on content density" for the text channel, plus image tokens computed via the vision cost formula.
- Three delivery options: URL reference, base64 in a `document` block, or Files-API `file_id`. Files-API is a beta (`anthropic-beta: files-api-2025-04-14`) and lets you avoid re-encoding for repeat calls.
- `cache_control: { type: "ephemeral" }` is supported on document blocks, so a PDF can be cached for the ~5 min window between the classify call and the extract call.

### Options compared

| Axis | Text-only | Native PDF | Hybrid (text→classify, PDF→extract) |
|---|---|---|---|
| **Tokens / call (single-page sample)** | ~500–1,500 input tokens (raw text) | ~2,000–4,500 tokens (text + image) | classify: ~500–1,500; extract: ~2,000–4,500 |
| **Cost per doc (2 calls)** | lowest (2× text cost) | highest (2× PDF cost), mitigated ~85–90% if cached | middle (text + PDF) |
| **Spatial fidelity** | Lost — tables reflow to reading order, column alignment gone | Preserved — model sees the rendered page | Preserved for extract (the call that needs it) |
| **Hard cases (`02-analysis.md` §3.2)** | | | |
| &nbsp;&nbsp;Retainer `scope` (narrative) | Good — plain prose extracts cleanly from text | Good — text channel still present | Good |
| &nbsp;&nbsp;Change Order `description` (narrative) | Good | Good | Good |
| &nbsp;&nbsp;Receipt `category` (inferred, no label) | Fine — item descriptions extract to text | Slight edge — merchant logo / receipt format can hint (e.g., kitchen-supply logo) | Slight edge |
| &nbsp;&nbsp;Pinnacle Invoice `lineItems` (variable rate types, missing unit prices) | **At risk** — column alignment lost; harder for model to associate "per page" with the right row | **Best** — preserved table layout | **Best** for this call |
| &nbsp;&nbsp;Lien Waiver `waiverType` (subtle legal phrasing) | Good — the disambiguating language is in-text | Good | Good |
| **Implementation complexity** | Lowest — already have `rawText` per C2-R3 | Moderate — need base64 encoding or Files-API flow; SDK classes `DocumentBlockParam` / `Base64PdfSource` / `UrlPdfSource` | Moderate — same as PDF for the extract path |
| **Fixture / recording size** | Tiny — kilobytes | Larger — but the recording stores the *response*, not the request PDF bytes (see §5); the recorded payload shape is similar | Same as PDF for extract |
| **Cache hit rate** | N/A | High if the same PDF is reused within 5 min between classify and extract | Lower — classify and extract send different content, so no cross-call cache benefit |

### Accuracy expectation on our corpus

Per `02-analysis.md` §3.1 the corpus is "uniformly clean digital PDFs, single page, no scans, no OCR noise, no multi-column layouts." PDFBox on these samples will produce nearly-perfect text; the spatial fidelity argument is weaker than it would be on messy real-world scans. The specific place where native PDF *does* measurably help is `lineItems` / `materials` tables where column alignment matters (Pinnacle Invoice §3.2.4 in particular — variable rate types with missing `unitPrice`). For narrative fields and flat-field extraction, text-only should be indistinguishable.

### Recommendation

**Default without running the eval: hybrid (text for classify, PDF-for-extract-only-for-doc-types-with-nested-tables).**

Rationale:
- Classification is a choose-from-N-types decision; spatial structure adds nothing. Text-only classification is cheaper and the recorded-mode fixtures stay small.
- Extraction for doc-types with `lineItems` / `materials` / `items` (Riverside Invoice, Pinnacle Invoice, Ironworks Invoice, Riverside Expense Report, Pinnacle Expense Report) benefits from native PDF.
- Extraction for flat doc-types (Riverside Receipt, Pinnacle Retainer, Ironworks Change Order, Ironworks Lien Waiver) is pure text → text extract is enough.

The hybrid rule is a config flag on each doc-type schema in C1: `inputModality ∈ {text, pdf, both}`, default `text`. Doc-types with a nested-array field set `pdf`. This keeps the 80th-percentile doc-type cheap and the table-heavy cases accurate.

**If the eval runs both ways on the tune set and hybrid is not materially better than text-only: fall back to text-only.** Simpler, cheaper, smaller recordings. The call is to run the eval — C3-R7/R8 makes this cheap — but the default ship-without-eval answer is hybrid as scoped above.

**Why not native-PDF-for-everything:** higher per-call cost on the 50% of doc-types that don't need it, larger fixture footprint, slower recorded-mode replay in CI, no accuracy win on flat doc-types.

**Files-API vs. base64 for PDFs:** prefer **base64** for a take-home. Files-API is still in beta (`anthropic-beta: files-api-2025-04-14`), adds a second API call (upload), and the caching benefit is marginal for our corpus sizes (single-page PDFs, ~50 KB each). base64-inline keeps the pipeline to one HTTP call per LLM invocation.

Citations:
- [Anthropic PDF support](https://platform.claude.com/docs/en/docs/build-with-claude/pdf-support)
- [Vision limitations](https://platform.claude.com/docs/en/build-with-claude/vision) (PDF support inherits these)
- [Files API](https://platform.claude.com/docs/en/build-with-claude/files)
- SDK classes: `com.anthropic.models.messages.DocumentBlockParam`, `Base64PdfSource`, `UrlPdfSource` ([anthropic-sdk-java](https://github.com/anthropics/anthropic-sdk-java))

---

## 2. Prompt management & versioning

**Question (verbatim).** Options: (a) prompts as constants in a Java class (`PromptLibrary.java`); (b) prompts as resource files under `src/main/resources/prompts/` with semantic version filenames (`classify_v1.txt`); (c) prompts in DB rows with a `prompt_version` FK; (d) prompts in code + content-hash lookup at call time. For each: how `llm_call_audit` would record the `prompt_version_or_hash`, how the eval harness targets a named prompt version for A/B, how a PR reviewer sees prompt changes in the diff. Recommend a scheme.

### Options compared

| Option | Audit recording | Eval A/B targeting | PR diff experience | Complexity |
|---|---|---|---|---|
| **(a) constants in Java class** | single `prompt_version_or_hash = PROMPT_VERSION` constant | requires git checkout to A/B; no runtime selection | Java diff — readable for short prompts, ugly for long templates | trivial |
| **(b) resource files w/ semver filename** (e.g. `classify_v1.txt`, `extract_riverside_invoice_v2.txt`) | `prompt_identifier = classify`, `prompt_version_or_hash = v1` | pass `--prompt-version classify=v2,extract_*=v1` to the eval harness; both files live in the repo simultaneously | plain-text diffs — best readability | low — file resolver at startup |
| **(c) DB rows + FK** | `prompt_version_or_hash = prompt_versions.id` | update DB or use a named seed | not diffable in a PR; reviewer has to inspect a migration | high — migrations per prompt change, fixture bloat |
| **(d) code + content-hash lookup** | `prompt_version_or_hash = sha256(prompt_text)[:12]` | no named versions — only "the version whose hash matches X" | diff shows prompt change but hash changes opaquely | medium — need a registry |

### Recommendation

**(b) resource files with semver filename.** Specifically:

```
backend/src/main/resources/prompts/
  classify/
    v1.txt                     # system + user template for classification
  extract/
    riverside_invoice/v1.txt
    riverside_receipt/v1.txt
    riverside_expense_report/v1.txt
    pinnacle_invoice/v1.txt
    pinnacle_retainer_agreement/v1.txt
    pinnacle_expense_report/v1.txt
    ironworks_invoice/v1.txt
    ironworks_change_order/v1.txt
    ironworks_lien_waiver/v1.txt
```

- A `PromptLibrary` bean loads all files at startup (per C7-R13 startup-only loading). Each file resolves to a `PromptTemplate { identifier, version, templateText, contentHash }`. `contentHash = sha256(templateText)[:12]`, computed at load time.
- Each LLM call carries a `PromptTemplate` reference; `llm_call_audit` records `prompt_identifier = "extract/pinnacle_invoice"`, `prompt_version_or_hash = "v2+a1b2c3d4"` (semver + short hash, belt-and-suspenders — the hash catches the case where someone edits `v1.txt` in place, which is an anti-pattern but auditable).
- Eval harness takes `--prompt-override <identifier>=<version>` (e.g. `--prompt-override extract/pinnacle_invoice=v2`) to run the holdout against a specific version. Default is the version stamped on `AppConfig.llm.prompts.defaults` (itself a committed YAML mapping identifier → version).
- PR reviewer sees the prompt change directly in the text-file diff. A new prompt version = a new file `v2.txt`, not an edit to `v1.txt` — so old recorded fixtures under `eval/recordings/{sampleId}/v1/...` keep working while the new ones are captured under `v2`.
- For local prompts rendered with runtime substitutions (e.g., "{{ALLOWED_DOC_TYPES}}"), use a minimal `{{variable}}` templater (Mustache or even `String.replace` is fine) — this is a take-home, not a production CMS.

Tool JSON schemas are generated deterministically from C1's doc-type schema (C3-R6) rather than stored as prompt files — their `tool_schema_hash` is already a column on `llm_call_audit` per C3-R5a.

### AppConfig wiring

```java
record LlmConfig(String modelId, PromptDefaults prompts, ...) {}
record PromptDefaults(String classify, Map<String, String> extractByDocType) {}
// populated from src/main/resources/application.yaml:
//   llm.prompts.classify: v1
//   llm.prompts.extract-by-doc-type:
//     riverside_invoice: v1
//     pinnacle_invoice: v2
//     ...
```

At startup `PromptLibrary.validate()` fails fast if any referenced version file is missing (per C1-R5 pattern applied to prompts).

---

## 3. Eval scoring methodology

**Question (verbatim).** Classification per-(org, doc-type) accuracy, overall, holdout. Extraction scoring for 8 scalar fields + nested `lineItems` array. Candidate metrics: field-level exact match, normalized exact match, precision/recall over field sets, Levenshtein. Propose metric palette + concrete sample. Sketch holdout markdown report.

### Classification

Straightforward. Three metrics, all derived from confusion matrix of predicted `docType` vs. ground-truth `docType`:

1. **Overall accuracy** — `correct / total`.
2. **Per-(org, docType) accuracy** — `correct_in_bucket / total_in_bucket`. Flags buckets with too-few samples (<3) explicitly rather than reporting a misleading 100% / 0%.
3. **Holdout vs. tune separation** — each metric reported twice: tune-set and holdout-set. The SC in `01-problem-space.md` Goal 13 is ≥ 95% on the holdout.

### Extraction — metric palette

Per-field metric is **assigned by field type**, not one-metric-fits-all:

| Field type | Metric | Rationale |
|---|---|---|
| `string` (short identifier — `invoiceNumber`, `matterNumber`, `projectCode`) | **normalized exact match** — strip whitespace + case-insensitive | "INV-4821" vs "inv-4821" should count as match |
| `string` (vendor / merchant / client name) | **normalized exact match** with punctuation stripped; Levenshtein ≤ 2 as a secondary "near-match" bucket reported but not counted toward primary accuracy | The ground-truth file typically normalizes "LLC" vs "L.L.C." issues; near-match visibility helps detect prompt drift |
| `date` | **parsed-and-equal** — both values parsed to `LocalDate`; match if equal; both null → match; one null → miss | "2025-03-15" vs "March 15, 2025" should match |
| `decimal` / amount | **numeric equal within 0.01** — strip `$`, `,` and compare | "$1,234.56" vs "1234.56" should match |
| `enum` (`category`, `billable`, `waiverType`) | **exact match on canonical value** | model must return one of the enumerated values; anything else is a miss |
| `narrative string` (`scope`, `description`, `paymentTerms`) | **normalized containment + Levenshtein ratio** — `normalized_distance = levenshtein / max(len(a), len(b))`; report both "exact-match rate" and "mean similarity" | `scope` is never going to exact-match; we care whether the key phrases are there |
| **Nested array** (`lineItems`, `materials`, `items`) | **set-based precision / recall over rows**, where each row is matched against ground-truth rows by best-match on a designated key field (e.g., `description`). Report P, R, F1 at the row level; within matched pairs, score each field as above and aggregate | 5 of 7 lineItems correct, 1 spurious, 1 missed → P=5/6, R=5/7 |

Aggregate extraction accuracy = **mean of per-field accuracies** on the holdout, per-(org, docType) and overall. Narrative-field similarity reported separately (not rolled into one headline number — averaging a Levenshtein ratio with binary exact-match hides information).

### Concrete Riverside Invoice example

**Ground truth (`eval/labels/riverside_invoice_001.yaml`):**

```yaml
docType: invoice
extractedFields:
  vendor: "Comically Large Spoon Warehouse"
  invoiceNumber: "CLSW-2024-0312"
  invoiceDate: "2024-03-12"
  dueDate: "2024-04-11"
  subtotal: 842.50
  tax: 73.72
  totalAmount: 916.22
  paymentTerms: "Net 30"
  lineItems:
    - description: "24-inch serving spoon (stainless)"
      quantity: 12
      unitPrice: 45.00
      total: 540.00
    - description: "36-inch ladle (copper)"
      quantity: 5
      unitPrice: 60.50
      total: 302.50
```

**Model prediction:**

```yaml
docType: invoice
extractedFields:
  vendor: "Comically Large Spoon Warehouse, LLC"      # near-match (Levenshtein=5)
  invoiceNumber: "CLSW-2024-0312"                     # match
  invoiceDate: "March 12, 2024"                       # match (parsed date equal)
  dueDate: "2024-04-11"                               # match
  subtotal: 842.50                                    # match
  tax: 73.72                                          # match
  totalAmount: 916.22                                 # match
  paymentTerms: "Net 30 days"                         # near-match (narrative)
  lineItems:
    - description: "24-inch serving spoon (stainless)"
      quantity: 12
      unitPrice: 45.00
      total: 540.00                                   # row match — all fields
    - description: "36-inch ladle, copper"            # fuzzy row match on description
      quantity: 5
      unitPrice: 60.50
      total: 302.50                                   # row match — all fields
```

**Scoring:**
- Classification: correct.
- Scalar fields (7 scored with exact/parsed, 1 narrative): 7/7 primary matches. `vendor` is near-match but counts as miss under strict normalized-exact (report separately as a "near miss" for diagnostics). `paymentTerms` narrative ratio ~0.91 (over threshold, count as match if threshold is 0.85).
- `lineItems`: 2 ground-truth rows, 2 predicted. Row pairing by best Levenshtein match on `description`: 2/2 paired. Per-pair field match: 8/8 in pair 1, 8/8 in pair 2 (the description near-miss is the pairing key, not a per-field score). P = R = F1 = 1.0 at row level.

### Holdout markdown report sketch

```markdown
# DocFlow LLM Eval — 2026-04-24T14:02Z

Model: claude-sonnet-4-6   Prompts: classify=v1, extract=v1 (all doc-types)
Mode: recorded   Git rev: 3f2a81c

## Classification

| Set | Correct | Total | Accuracy |
|---|---|---|---|
| Tune  | 11 | 11 | 100.0% |
| **Holdout** | **11** | **12** | **91.7%** |

### Per-(org, docType) on holdout

| Org | DocType | Correct | Total | Accuracy |
|---|---|---|---|---|
| riverside | invoice          | 2 | 2 | 100.0% |
| riverside | receipt          | 1 | 2 |  50.0%  WARN |
| riverside | expense_report   | 1 | 1 | 100.0% |
| pinnacle  | invoice          | 2 | 2 | 100.0% |
| pinnacle  | retainer         | 1 | 1 | 100.0% |
| pinnacle  | expense_report   | 1 | 1 | 100.0% |
| ironworks | invoice          | 1 | 1 | 100.0% |
| ironworks | change_order     | 1 | 1 | 100.0% |
| ironworks | lien_waiver      | 1 | 1 | 100.0% |

WARN Riverside receipt: 1 miss — receipt_holdout_002.pdf classified as invoice. See `eval/recordings/receipt_holdout_002/v1/classify.json`.

## Extraction — holdout, per field (exact-match rate)

### Riverside Invoice (n=2)

| Field | Match rate |
|---|---|
| vendor            | 2/2 (100%) |
| invoiceNumber     | 2/2 (100%) |
| invoiceDate       | 2/2 (100%) |
| dueDate           | 2/2 (100%) |
| subtotal          | 2/2 (100%) |
| tax               | 2/2 (100%) |
| totalAmount       | 2/2 (100%) |
| paymentTerms      | 1/2 ( 50%)  narrative-similarity mean 0.88 |
| lineItems (row P) | 1.00 |
| lineItems (row R) | 1.00 |
| lineItems (row F1)| 1.00 |

### Pinnacle Retainer Agreement (n=1)

| Field | Match rate | Notes |
|---|---|---|
| clientName    | 1/1 | |
| matterType    | 1/1 | |
| hourlyRate    | 1/1 | |
| retainerAmount| 1/1 | |
| effectiveDate | 1/1 | |
| termLength    | 1/1 | |
| scope         | 0/1 | Levenshtein ratio 0.71 (under 0.85 threshold) — narrative field |

## Near-miss report

| Sample | Field | Ground truth | Prediction | Reason |
|---|---|---|---|---|
| riverside_invoice_003 | vendor | Comically Large Spoon Warehouse | Comically Large Spoon Warehouse, LLC | suffix added |
| pinnacle_retainer_002 | scope  | (125-word block) | (112-word block) | summarized, key phrases present |
```

Format notes:
- Committed at `eval/reports/latest.md` plus an archive at `eval/reports/{yyyy-mm-dd-hhmm}.md`. PR reviewers see eval deltas as a git diff on `latest.md`.
- Near-miss table is the debugging surface — it's where prompt tuning starts.
- Rendering the report is a dedicated `EvalReportWriter` (pure function from `EvalResult` -> markdown string). Unit-tested with a tiny fixture.

---

## 4. Tool-use pattern with `com.anthropic:anthropic-java`

**Question (verbatim).** Confirm the idiomatic code shape for forcing a specific tool call and reading `tool_use.input`. Reference the SDK's documented example(s). Include a 15–25-line Java sketch showing classification with a forced `select_doc_type` tool and extraction with a forced `extract_<docType>` tool.

### Confirmed SDK shape

From `BetaMessagesToolsExample.java` in [anthropic-sdk-java](https://github.com/anthropics/anthropic-sdk-java/blob/main/anthropic-java-example/src/main/java/com/anthropic/example/BetaMessagesToolsExample.java):

- Tools are registered via `MessageCreateParams.builder().addTool(...)`. The SDK provides two paths: (i) a Jackson-annotated POJO (`addTool(MyClass.class)`) and (ii) an explicit `Tool.builder().name(...).description(...).inputSchema(InputSchema.builder()...)` using `JsonValue.from(Map.of(...))` for properties. **We use the explicit `Tool.builder()` path** because our schemas come from C1 config (dynamic) not Jackson classes.
- Reading tool calls: iterate `response.content()` as a stream and pull `contentBlock.toolUse()` (which returns `Optional<ToolUseBlock>`). `toolUseBlock.name()`, `toolUseBlock.id()`, and `toolUseBlock._input()` give the raw JSON input; typed access is via `toolUseBlock.input(MyClass.class)`.
- Forcing a specific tool: the SDK accepts `toolChoice(ToolChoice.ofTool(ToolChoiceTool.builder().name("select_doc_type").build()))` (per the `tool_choice` docs at [define-tools § Forcing tool use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools)).

### Java sketch — classification

```java
// C1-supplied list of allowed doc-types for the org
List<String> allowed = catalog.getAllowedDocTypes(orgId);

Tool selectDocType = Tool.builder()
    .name("select_doc_type")
    .description("Choose which document type best describes the uploaded file.")
    .inputSchema(InputSchema.builder()
        .properties(JsonValue.from(Map.of(
            "docType", Map.of("type", "string", "enum", allowed))))
        .putAdditionalProperty("required", JsonValue.from(List.of("docType")))
        .build())
    .build();

MessageCreateParams params = MessageCreateParams.builder()
    .model(config.llm().modelId())                       // claude-sonnet-4-6
    .maxTokens(512)
    .system(promptLibrary.get("classify").render(Map.of("allowed", allowed)))
    .addTool(selectDocType)
    .toolChoice(ToolChoice.ofTool(ToolChoiceTool.builder().name("select_doc_type").build()))
    .addUserMessageOfBlockParams(contentBlocks(documentId))   // text or document per modality
    .build();

Message response = client.messages().create(params);
String docType = response.content().stream()
    .flatMap(cb -> cb.toolUse().stream())
    .findFirst()
    .orElseThrow(() -> new LlmProtocolException("no tool_use in classify response"))
    ._input().asObject().get("docType").asString()
    .orElseThrow();
```

### Java sketch — extraction

```java
DocTypeSchema schema = catalog.getDocumentTypeSchema(orgId, docType);
Tool extractTool = Tool.builder()
    .name("extract_" + docType)                                         // e.g. extract_pinnacle_invoice
    .description("Extract the structured fields for a " + docType + ".")
    .inputSchema(schemaToInputSchema(schema))                           // deterministic per C3-R6
    .build();

MessageCreateParams params = MessageCreateParams.builder()
    .model(config.llm().modelId())
    .maxTokens(2048)
    .system(promptLibrary.get("extract/" + docType).render(Map.of()))
    .addTool(extractTool)
    .toolChoice(ToolChoice.ofTool(ToolChoiceTool.builder().name(extractTool.name()).build()))
    .addUserMessageOfBlockParams(contentBlocks(documentId))
    .build();

Message response = client.messages().create(params);
JsonValue fields = response.content().stream()
    .flatMap(cb -> cb.toolUse().stream())
    .findFirst()
    .orElseThrow()
    ._input();      // raw JsonValue — emitted via ProcessingCompleted/ExtractionCompleted event; C4 persists as JSONB into Document.extractedFields
```

`contentBlocks(documentId)` returns `List<ContentBlockParam>`: either `ContentBlockParam.ofText(...)` (text modality) or `ContentBlockParam.ofDocument(DocumentBlockParam.builder().source(Base64PdfSource.builder().data(base64).build()).cacheControl(CacheControlEphemeral.builder().build()).build())` (PDF modality), per the hybrid decision in §1.

### Error handling notes

- If `toolUse()` stream is empty -> `LlmProtocolException`; C3-R2 specifies single retry.
- If the returned input fails schema validation (e.g., `docType` isn't in `allowed`) -> same retry path.
- SDK throws `AnthropicException` on 429/5xx -> wrap and surface as `LlmUnavailable` (matches C5-R9a error code).

---

## 5. Recording format & location

**Question (verbatim).** `eval/recordings/{sampleId}/{promptVersion}/{classify|extract}.json` is the committed location (C3-R11). What exactly goes in the JSON: full HTTP-wire payload (headers + body request/response) or the SDK's domain response object serialized? What about non-determinism from the API (message IDs, timestamps)? Propose the exact record shape and a small sample.

### Options

| Shape | Pros | Cons |
|---|---|---|
| **Full HTTP wire** (request + response, headers included) | maximum fidelity; survives SDK version bumps | headers leak API keys unless scrubbed; request PDF-base64 bloats the file |
| **SDK domain request -> SDK domain response** (both serialized as JSON) | clean, typed on replay; regenerable | tied to SDK class shape — version bump may break replay |
| **Hybrid: request fingerprint + full response body** | best replay-matching; small recording | throws away request body so you can't re-derive it |

### Recommendation

**Record the response body as raw JSON (SDK domain `Message` serialized) plus a minimal request fingerprint for matching.** The request fingerprint is deterministic and does not include PDF bytes.

File path (per C3-R11): `eval/recordings/{sampleId}/{promptVersion}/{classify|extract}.json`.

### Record shape

```json
{
  "meta": {
    "recordedAt": "2026-04-24T13:55:12Z",
    "sdkVersion": "com.anthropic:anthropic-java:2.26.0",
    "docflowRev": "3f2a81c"
  },
  "request": {
    "fingerprint": {
      "model": "claude-sonnet-4-6",
      "promptIdentifier": "extract/pinnacle_invoice",
      "promptVersion": "v1",
      "promptContentHash": "a1b2c3d4e5f6",
      "toolName": "extract_pinnacle_invoice",
      "toolSchemaHash": "98a7b6c5",
      "inputModality": "pdf",
      "contentSha256": "b8f0d7e9..."
    }
  },
  "response": {
    "id": "msg_REDACTED",
    "type": "message",
    "role": "assistant",
    "model": "claude-sonnet-4-6",
    "content": [
      {
        "type": "tool_use",
        "id": "toolu_REDACTED",
        "name": "extract_pinnacle_invoice",
        "input": {
          "vendor": "Absolutely Legitimate Court Reporting Services",
          "invoiceNumber": "ALCRS-2024-0817",
          "invoiceDate": "2024-08-17",
          "matterNumber": "M-7731",
          "matterName": "Paws v. Paws (custody)",
          "amount": 2450.00,
          "billingPeriod": "2024-07-01 to 2024-07-31",
          "paymentTerms": "Net 30"
        }
      }
    ],
    "stop_reason": "tool_use",
    "stop_sequence": null,
    "usage": { "input_tokens": 2813, "output_tokens": 187 }
  }
}
```

### Non-determinism handling

- **Message `id` and tool_use `id`** — scrub to the literal string `"msg_REDACTED"` / `"toolu_REDACTED"` at record time. The eval harness never reads them; they exist only to satisfy the SDK's deserializer. On replay, the recorder injects fresh UUIDs so each replay produces unique IDs (matches real API behavior).
- **`recordedAt` timestamp** — committed into the file once at recording time; serves as a git-observable "when was this re-recorded" signal. The `Message.id` itself has no timestamp.
- **`usage` token counts** — kept as recorded; small drift between real and replayed runs is acceptable. The eval doesn't score on tokens.
- **Request PDF bytes** — NOT stored in the recording (they live in `problem-statement/samples/`; `contentSha256` fingerprints them so a mismatched sample file fails replay loudly, satisfying C3-R11's "missing recordings cause the eval's recorded mode to fail loudly").
- **API key and auth headers** — never present in the recording. Only request fingerprint data goes in.

### Replay mechanism

A `RecordedAnthropicClient` wraps the real `AnthropicClient`. In recorded mode, it:
1. Computes the request fingerprint from `MessageCreateParams`.
2. Resolves the recording file path from `(sampleId, promptVersion, callType)`.
3. Validates fingerprint matches recorded fingerprint; fails loudly on mismatch (prompt change without re-recording).
4. Returns the deserialized `Message` domain object.

In `--live` mode it calls the real client and (if `--record` also set) writes a new recording. The integration tests at C3-R12 use this same harness.

---

## Risks & unknowns

1. **PDFBox text quality on the whimsical samples.** §3.1 says clean digital PDFs, but one of the retainer samples with unusual serif kerning may produce slightly garbled text. Pass-5 implementation should dry-run PDFBox on all 23 samples early and flag outliers. Mitigation: if >1 sample is garbled, escalate the default from text-only classify to PDF classify.
2. **`claude-sonnet-4-6` behavior under forced tool_choice with a dynamic enum.** The `select_doc_type` tool uses a runtime `enum` list derived from the org's allowed types. If the model occasionally returns a `docType` not in the enum, C3-R1 says fail with a typed error — but this risks flapping. Tune-set eval should exercise this with a synthetic "what if the document is none of these" sample; we may need an explicit `"other"` enum value. Spec question 15 in `02-analysis.md` §1.5 flags this.
3. **Base64 PDF size vs. context window.** Single-page PDFs in the corpus are small, but a real deployment would need splitting. Out of scope for the take-home but worth a README note.
4. **Files API beta instability.** We chose base64 over Files API precisely to avoid beta churn. If a future prompt-caching advantage pushes us back to Files API, re-evaluate.
5. **Prompt version explosion.** If prompt iteration is heavy during eval tuning, `prompts/extract/pinnacle_invoice/v{1..5}.txt` could pile up. Acceptable for the take-home; for production, garbage-collect versions older than the defaults after a release.
6. **Eval reproducibility across SDK bumps.** Recording stores the SDK response body shape. A major SDK version change may break deserialization on replay. The `meta.sdkVersion` field lets us detect this; mitigation is to re-record.
7. **Narrative-field similarity threshold.** Picking 0.85 Levenshtein ratio is arbitrary. Tune it once against the tune set and freeze; otherwise the metric drifts with every eval tweak.
8. **Row-matching for `lineItems` when descriptions are near-identical across rows.** If two line items share a description ("spoons"), best-match pairing can degenerate. Fall back to positional pairing when all candidate pairs have similarity >= 0.95; or require the ground-truth labels to include a distinguishing field. Not expected on the 23-sample corpus but worth a unit test.
9. **Hybrid modality doubles the number of tool_schema_hash permutations recorded in `llm_call_audit`.** Not a bug, just a note for dashboard building if we ever add one.
10. **ProcessingDocument cleanup discipline.** Per the revised model, `ProcessingDocument` is deleted on successful handoff to C4 and persists indefinitely on `currentStep = FAILED`. Risks: (a) orphaned rows where the C4 handoff partially succeeded (Document created, delete signal lost) — need an idempotent reconciliation path keyed on `storedDocumentId`; (b) FAILED rows accumulating without admin visibility — surface them in admin tooling with a retry affordance (re-run the pipeline from the failed step). Add a periodic sanity check (or a startup audit) that flags `ProcessingDocument` rows whose `storedDocumentId` already has a `Document` row.

---

## Summary of recommendations

| # | Question | Recommendation |
|---|---|---|
| 1 | Input modality | **Hybrid**: text for classify; PDF for extract on doc-types with nested arrays; text for extract on flat doc-types. Per-doc-type config flag. Base64 inline (not Files API). Run eval both ways on tune set to confirm. |
| 2 | Prompt management | Resource files under `src/main/resources/prompts/<identifier>/<version>.txt`; audit records `identifier` + `version+hash`; eval targets a version via `--prompt-override`. |
| 3 | Eval scoring | Classification: accuracy per-(org, docType), overall, holdout-separated. Extraction: per-field metric chosen by field type (normalized-exact for short strings / IDs, parsed-equal for dates, numeric-equal for amounts, enum-exact for enums, Levenshtein-ratio for narratives, row-level P/R/F1 for nested arrays). Committed markdown report at `eval/reports/latest.md`. |
| 4 | Tool use in SDK | `Tool.builder()` with dynamic `InputSchema`; `ToolChoice.ofTool(...)` to force; read via `response.content().stream().flatMap(cb -> cb.toolUse().stream())` then `._input()`. |
| 5 | Recording shape | Scrubbed SDK `Message` JSON + request fingerprint (no PDF bytes, no auth). Path `eval/recordings/{sampleId}/{promptVersion}/{classify\|extract}.json`. IDs redacted at record time, re-injected at replay. |

Pass 5 turns these into a change-spec (file paths, table columns, task breakdown).
