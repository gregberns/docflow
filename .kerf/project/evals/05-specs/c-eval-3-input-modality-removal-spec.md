# Change Spec — C-EVAL-3 (`inputModality` removal)

Remove the `inputModality` field from spec, database schema, seed YAML, JPA entity, catalog view, and the LLM message-builder code. The PDF code branch in `MessageContentBuilder` is dead — no production caller passes `InputModality.PDF` — and the field carries no runtime semantics. Greenfield: edit `V1__init.sql` in place.

---

## Requirements (from `03-components.md`)

R-EVAL-3.1 through R-EVAL-3.9 (verbatim).

## Research summary (from `04-research/c-eval-3/findings.md`)

- In-place edit of `V1__init.sql` and the `c1-org-config.sql` fragment is the project-established pattern for greenfield baseline changes. No `V2__` migration.
- No spec archaeology comment is left behind. The kerf substrate plus beads history is the record.
- All test seed directories (`seed-fourth-org`, `loader-fixtures/`) and SQL fixtures (`processing-completed-listener-seed.sql`, `retype-flow-listener-seed.sql`) need the field dropped together.
- Two `InputModality` enums die in the same change: `com.docflow.config.org.InputModality` and the nested `MessageContentBuilder.InputModality`. No type unification needed.
- The two LLM call sites simplify by dropping `InputModality.TEXT` and `null pdfBytes` arguments.

## Approach

One commit (or one short commit chain) touches three layers in lockstep so the build passes at every step:

1. **Schema** — drop the column and CHECK constraint from `V1__init.sql` and the fragment.
2. **Production code** — drop the field from `DocTypeDefinition`, `DocumentTypeEntity`, `DocumentTypeSchemaView`, `DocumentTypeCatalogImpl`, `OrgConfigSeedWriter`. Drop the enum file `com.docflow.config.org.InputModality`. Drop the nested `MessageContentBuilder.InputModality` and the `pdfBytes` parameter; collapse `buildContentBlocks` to text-only. Update `LlmClassifier` and `ExtractRequestBuilder` call sites.
3. **YAML and SQL fixtures** — drop the `inputModality:` line from every seed YAML; drop the `input_modality` column and value from the two SQL fixtures.
4. **Tests** — delete the four assertions that lock in the field; remove imports and the literal `InputModality.TEXT` arguments from the LLM unit tests; remove the literal `InputModality.TEXT` from `ConfigValidatorTest` fixture builders.
5. **Spec substrate** — edit `c1-config-spec.md` (seven references) and `c3-pipeline-spec.md` §3.5 plus research summary §1.

After the change, `grep -r "inputModality\|input_modality\|InputModality" backend/ src/main/resources/` returns zero results in the listed directories.

## Files & changes

### Schema

| File | Change |
|---|---|
| `backend/src/main/resources/db/migration/V1__init.sql` | Delete the `input_modality VARCHAR(16) NOT NULL DEFAULT 'TEXT',` column line (currently L19). Delete the trailing comma on the `display_name` line if necessary. Delete the `CONSTRAINT ck_document_types_input_modality CHECK (input_modality IN ('TEXT', 'PDF'))` block (currently L24–L25). Adjust whatever comma trails `field_schema JSONB NOT NULL,` so the table definition is syntactically clean. |
| `backend/src/main/resources/db/migration/fragments/c1-org-config.sql` | Same two deletions, on L15 and L20–L21. |

### Production code

| File | Change |
|---|---|
| `backend/src/main/java/com/docflow/config/org/InputModality.java` | **Delete file.** |
| `backend/src/main/java/com/docflow/config/org/DocTypeDefinition.java` | Remove the `@NotNull InputModality inputModality,` parameter (currently L13). Remove the import line for `InputModality`. |
| `backend/src/main/java/com/docflow/config/persistence/DocumentTypeEntity.java` | Remove the `@Column(name = "input_modality", nullable = false) private String inputModality;` field (L28–L29). Remove the `String inputModality` constructor parameter (L41) and the `this.inputModality = inputModality;` assignment (L46). Remove the `getInputModality()` accessor (L62–L64). |
| `backend/src/main/java/com/docflow/config/catalog/DocumentTypeSchemaView.java` | Remove the `String inputModality,` field from the record (L9). |
| `backend/src/main/java/com/docflow/config/catalog/DocumentTypeCatalogImpl.java` | Remove the `entity.getInputModality(),` argument from the `DocumentTypeSchemaView` constructor call (L95). |
| `backend/src/main/java/com/docflow/config/org/seeder/OrgConfigSeedWriter.java` | Remove the `dt.inputModality().name(),` argument from the `DocumentTypeEntity` constructor call (L81). |
| `backend/src/main/java/com/docflow/c3/llm/MessageContentBuilder.java` | Remove the nested `enum InputModality { TEXT, PDF }` (L25–L28). Remove the `InputModality modality` parameter from `build(...)` (L39). Remove the `byte[] pdfBytes` parameter from `build(...)` (L41). Remove the modality null-check (L52–L54). Replace `buildContentBlocks(...)` (L74–L89) with a text-only implementation: validate `text` non-blank, build a `TextBlockParam`, return single-element list. Drop the `Base64`, `DocumentBlockParam` imports; keep `TextBlockParam`. |
| `backend/src/main/java/com/docflow/c3/llm/LlmClassifier.java` | Remove the import for `MessageContentBuilder.InputModality` (L9). Update the `messageContentBuilder.build(...)` call (L75–L83) to drop the `InputModality.TEXT` argument and the `null` `pdfBytes` argument. New signature: `build(modelId, systemPrompt, toolSchema, CLASSIFY_MAX_TOKENS, rawText)`. |
| `backend/src/main/java/com/docflow/c3/llm/ExtractRequestBuilder.java` | Remove the import for `MessageContentBuilder.InputModality` (L4). Update the `messageContentBuilder.build(...)` call (L31–L38) the same way: drop `InputModality.TEXT` and `null pdfBytes` arguments. |

### Seed YAML (production)

For each of the nine files, delete the `inputModality: <value>` line (currently L4). Files:

- `backend/src/main/resources/seed/doc-types/riverside-bistro/invoice.yaml`
- `backend/src/main/resources/seed/doc-types/riverside-bistro/expense-report.yaml`
- `backend/src/main/resources/seed/doc-types/riverside-bistro/receipt.yaml`
- `backend/src/main/resources/seed/doc-types/pinnacle-legal/invoice.yaml`
- `backend/src/main/resources/seed/doc-types/pinnacle-legal/expense-report.yaml`
- `backend/src/main/resources/seed/doc-types/pinnacle-legal/retainer-agreement.yaml`
- `backend/src/main/resources/seed/doc-types/ironworks-construction/invoice.yaml`
- `backend/src/main/resources/seed/doc-types/ironworks-construction/lien-waiver.yaml`
- `backend/src/main/resources/seed/doc-types/ironworks-construction/change-order.yaml`

### Seed YAML (test fixtures)

Same line deletion in each:

- `backend/src/test/resources/seed-fourth-org/doc-types/riverside-bistro/invoice.yaml`
- `backend/src/test/resources/seed-fourth-org/doc-types/riverside-bistro/expense-report.yaml`
- `backend/src/test/resources/seed-fourth-org/doc-types/riverside-bistro/receipt.yaml`
- `backend/src/test/resources/seed-fourth-org/doc-types/pinnacle-legal/invoice.yaml`
- `backend/src/test/resources/seed-fourth-org/doc-types/pinnacle-legal/expense-report.yaml`
- `backend/src/test/resources/seed-fourth-org/doc-types/pinnacle-legal/retainer-agreement.yaml`
- `backend/src/test/resources/seed-fourth-org/doc-types/ironworks-construction/invoice.yaml`
- `backend/src/test/resources/seed-fourth-org/doc-types/ironworks-construction/lien-waiver.yaml`
- `backend/src/test/resources/seed-fourth-org/doc-types/ironworks-construction/change-order.yaml`
- `backend/src/test/resources/seed-fourth-org/doc-types/municipal-clerk/permit-application.yaml`
- `backend/src/test/resources/loader-fixtures/seed/doc-types/test-bistro/invoice.yaml`
- `backend/src/test/resources/loader-fixtures/seed/doc-types/test-bistro/expense-report.yaml`
- `backend/src/test/resources/loader-fixtures/seed/doc-types/test-bistro/receipt.yaml`
- `backend/src/test/resources/loader-fixtures/seed/doc-types/test-legal/invoice.yaml`
- `backend/src/test/resources/loader-fixtures/seed/doc-types/test-legal/expense-report.yaml`
- `backend/src/test/resources/loader-fixtures/seed/doc-types/test-legal/retainer.yaml`
- `backend/src/test/resources/loader-fixtures/seed/doc-types/test-construction/invoice.yaml`
- `backend/src/test/resources/loader-fixtures/seed/doc-types/test-construction/change-order.yaml`
- `backend/src/test/resources/loader-fixtures/seed/doc-types/test-construction/lien-waiver.yaml`
- `backend/src/test/resources/loader-fixtures/missing-required/seed/doc-types/bad-org/thing.yaml`

### SQL fixtures

| File | Change |
|---|---|
| `backend/src/test/resources/fixtures/processing-completed-listener-seed.sql` | The INSERT on L4 reads `INSERT INTO document_types (organization_id, id, display_name, input_modality, field_schema) VALUES (...)`. Drop `input_modality,` from the column list and the corresponding value from the `VALUES` list. |
| `backend/src/test/resources/fixtures/retype-flow-listener-seed.sql` | Same change, same line. |

### Tests to delete

| Test method | File | Action |
|---|---|---|
| `inputModalityEnumExposesExactlyTextAndPdf` | `backend/src/test/java/com/docflow/config/org/StageGuardConfigTest.java` (L95–L96) | Delete the test method entirely. |
| `acS1_documentTypeWritesInputModalityAndFieldSchema` | `backend/src/test/java/com/docflow/config/org/seeder/OrgConfigSeederIT.java` (L96–L103) | Delete the test method, or rename and prune to assert only `field_schema`. Recommend delete. |
| `inputModalityIsPdfForNestedArrayDocTypesAndTextForTheRest` | `backend/src/test/java/com/docflow/config/org/SeedFixturesTest.java` (L84–L97) | Delete the test method. |
| Inside `OrgConfigPersistenceFragmentIT` (L123) | `backend/src/test/java/com/docflow/config/persistence/OrgConfigPersistenceFragmentIT.java` | The `loaded.getInputModality()` assertion must be removed; the rest of the test (which asserts on field_schema round-trip) stays. |

### Tests to edit

| File | Change |
|---|---|
| `backend/src/test/java/com/docflow/config/org/validation/ConfigValidatorTest.java` | Remove import (L10). Remove the `InputModality.TEXT,` argument from the four `DocTypeDefinition` constructions at L113, L234, L260, L286, L314. The fixture builders no longer take this argument. |
| `backend/src/test/java/com/docflow/c3/llm/MessageContentBuilderTest.java` | Remove the import (L9). Remove the `InputModality.TEXT` and `InputModality.PDF` arguments from the seven calls to `builder.build(...)` (L32, L52, L64, L74, L90, L102). Delete the test that asserts the PDF branch (the `InputModality.PDF` test cases — they exercise dead code). Adjust other tests' parameter lists to match the new `build(...)` signature: `(modelId, systemPrompt, toolSchema, maxTokens, text)`. |
| `backend/src/test/java/com/docflow/c3/llm/LlmClassifierTest.java` | Remove the import for `MessageContentBuilder.InputModality`. Update the mock setup for `messageContentBuilder.build(...)` to use the new signature (no `InputModality.TEXT`, no `null pdfBytes`). |
| `backend/src/test/java/com/docflow/c3/llm/LlmExtractorTest.java` | Same as above. |
| `backend/src/test/java/com/docflow/c3/llm/LlmCallExecutorTest.java` | Same as above. |

### Spec substrate edits

`backend/src/main/resources/db/migration/...` notwithstanding, the spec substrate at `.kerf/project/docflow/05-specs/` is the agent-readable architecture record. The implementation agent edits these files (the parent kerf substrate is not read-only; only `problem-statement/` is read-only).

`backend/.kerf/project/docflow/05-specs/c1-config-spec.md` (path: `/Users/gb/github/basata/.kerf/project/docflow/05-specs/c1-config-spec.md`):

| Edit | What to change |
|---|---|
| L18 (C1-R2) | Remove the trailing clause `; per-doc-type \`inputModality ∈ {TEXT, PDF}\` (default TEXT) consumed by C3 §3.5`. The R-tag remains; the wording loses the modality reference. |
| L88 | Remove the line `@NotNull InputModality inputModality,` from the `DocTypeDefinition` record snippet. |
| L92 | Remove the `public enum InputModality { TEXT, PDF }` declaration. |
| L326 (file inventory) | Remove the line `- backend/src/main/java/com/docflow/config/org/InputModality.java`. |
| L362 (entity description) | Remove `, input_modality varchar ∈ {TEXT, PDF} default TEXT`. |
| L396 (allowed-strings) | Remove `input_modality` from the list. |
| L421–L427 (seed prose) | Delete the paragraph "Each doc-type YAML carries an `inputModality` key (default `TEXT`). The four ... per the C3 spec §3.5 hybrid decision." |
| L468 (AC-L6) | Delete this acceptance criterion entirely. Renumber following ACs is **not** needed — the substrate uses gap-tolerated AC numbering elsewhere. |

`/Users/gb/github/basata/.kerf/project/docflow/05-specs/c3-pipeline-spec.md`:

| Edit | What to change |
|---|---|
| L37 (research summary §1) | Replace `Hybrid (text for classify, PDF for extract on doc-types with nested arrays) is the recommended default; text-only is the cheap fallback if the eval shows hybrid offers no measurable lift on the corpus.` with `Text-only is the chosen modality. PDFBox extraction quality on the sample corpus has been validated independently (eval/pdfbox-check/REPORT.md); the additional latency and token cost of hybrid PDF input is not justified.` Drop the trailing `Base64 inline beats Files-API for take-home (...)` clause. |
| §3.5 (L149–L155) | Replace the entire subsection with a single line: `Text-only. PDFBox-extracted rawText is the sole input to both classify and extract calls.` Or delete §3.5 and renumber following subsections. Recommend keeping the heading and trimming to one line — preserves the substrate's section-numbering and signals that an explicit choice was made and recorded. |

## Acceptance criteria

1. `cd /Users/gb/github/basata/backend && ./gradlew check` exits 0.
2. `grep -r "inputModality\|input_modality\|InputModality" /Users/gb/github/basata/backend/src/main/ /Users/gb/github/basata/backend/src/test/ /Users/gb/github/basata/backend/src/main/resources/` returns no results.
3. `grep -rn "inputModality\|input_modality\|InputModality" /Users/gb/github/basata/.kerf/project/docflow/05-specs/c1-config-spec.md /Users/gb/github/basata/.kerf/project/docflow/05-specs/c3-pipeline-spec.md` returns no results.
4. The `document_types` table in the Postgres schema (after Flyway runs `V1__init.sql`) has columns `(organization_id, id, display_name, field_schema)` only — no `input_modality`. Verify by `\d document_types` in psql against a Testcontainers instance, or by running an existing IT (`OrgConfigPersistenceFragmentIT`) that loads the schema.
5. The seed loader successfully parses all 9 production seed YAMLs and all 10 test seed YAMLs after the field is removed (verified by `OrgConfigSeederIT` passing).
6. `MessageContentBuilder.build(...)` has the signature `MessageCreateParams build(String modelId, String systemPrompt, ToolSchema toolSchema, int maxTokens, String text)` — five parameters, not seven.
7. `LlmClassifier` and `ExtractRequestBuilder` compile and pass their unit tests with the new signature.

## Verification

```
make test
```

Expected: passes. If a test fails, it is one of the assertions listed in the "Tests to delete" / "Tests to edit" sections — re-check that table.

Manual schema check:

```
cd /Users/gb/github/basata/backend && ./gradlew test --tests "com.docflow.config.persistence.OrgConfigPersistenceFragmentIT"
```

Manual grep gate:

```
grep -rn "inputModality\|input_modality\|InputModality" /Users/gb/github/basata/backend/src/ /Users/gb/github/basata/backend/src/main/resources/ /Users/gb/github/basata/.kerf/project/docflow/05-specs/c1-config-spec.md /Users/gb/github/basata/.kerf/project/docflow/05-specs/c3-pipeline-spec.md
```

Expected: no output.

## Error handling and edge cases

- **Loader fails on unknown property.** `ConfigLoader` is configured (per project convention) with strict YAML parsing. Removing the field from the record makes `inputModality:` an unknown property in the YAML; the loader rejects fixtures that still carry the line. Mitigation: edit the YAML files in the same change as the record. The change-spec lists every file.
- **Hibernate column mapping mismatch.** Removing the `@Column(name = "input_modality")` annotation while the column still exists in the schema causes a startup warning but no failure. Removing them in the same commit avoids any window of mismatch.
- **Migration drift in dev environments.** Greenfield: no environment has applied V1 in any state of record. If a developer has a Postgres volume from a prior in-place V1 edit, they wipe and re-create. The Stop hook test runs against Testcontainers; volume persistence is not a concern.
- **Test fixture SQL referencing dropped column.** Caught at test runtime — the `INSERT INTO document_types (..., input_modality, ...)` would fail. Mitigation: edit the two SQL fixtures in the same change.

## Migration / backwards compatibility

Greenfield. No backwards-compatibility burden. No data migration. Every consumer of the field is internal.
