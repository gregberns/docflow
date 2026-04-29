# Research — C-EVAL-3 (`inputModality` removal)

Most decisions are settled. This pass surfaces three open questions; each has a recommended answer plus rationale.

## Q1: In-place edit of `V1__init.sql` or a new `V2__drop_input_modality.sql`?

**Answer: in-place edit.** This is the established project pattern. Recent commits all edit `V1__init.sql` and the matching fragment together; the most recent (`3fb331b C7.4: Flyway V1__init.sql baseline`) explicitly treats V1 as a baseline that gets revised pre-go-live. Greenfield: nothing has applied the migration in any state of record. A new V2 would be necessary post-go-live; pre-go-live, the additive migration is the more disruptive choice (introduces a no-op DROP; requires a Flyway repair on any developer who pulled an interim state).

**Practical:** edit `backend/src/main/resources/db/migration/V1__init.sql` and `backend/src/main/resources/db/migration/fragments/c1-org-config.sql` together in the same commit. Drop the column from the column list, drop the CHECK constraint.

## Q2: Should the C1 spec retain a comment about "removed `inputModality`" for archaeology?

**Answer: no.** The spec substrate's purpose is to describe the implementation as it is, not as it was. A reader of `c1-config-spec.md` post-removal should not encounter the dead concept at all. The work is captured in beads ticket history and the kerf substrate at `.kerf/projects/basata/evals/`.

## Q3: Are there test fixtures in seed-fourth-org that the production seed never references? Do they need to be edited?

**Yes, edit them.** `backend/src/test/resources/seed-fourth-org/doc-types/` mirrors production seed (Riverside, Pinnacle, Ironworks) and adds a `municipal-clerk/permit-application.yaml` with `inputModality: PDF`. The `OrgConfigSeederIT` and related integration tests load this directory. If `inputModality` is removed from the loader's expected schema, these fixtures must drop the field too — otherwise the loader fails on unknown property (assuming Jackson `FAIL_ON_UNKNOWN_PROPERTIES = true`, which is the project standard).

Loader fixtures (`backend/src/test/resources/loader-fixtures/seed/doc-types/`) similarly need the field removed in all six files. Same for `loader-fixtures/missing-required/seed/doc-types/bad-org/thing.yaml`.

SQL fixtures (`backend/src/test/resources/fixtures/processing-completed-listener-seed.sql` and `retype-flow-listener-seed.sql`) currently `INSERT INTO document_types (organization_id, id, display_name, input_modality, field_schema)`. Each must be edited to drop the column and value from the INSERT.

## Settled decisions

- The PDF branch in `MessageContentBuilder` is the single dead consumer; removing the enum and the branch removes all dependence on `DocumentBlockParam` and `Base64.getEncoder()` from this class.
- `LlmClassifier` and `ExtractRequestBuilder` call sites are simplified by dropping two arguments each (`InputModality.TEXT` and `null` for `pdfBytes`).
- The two `InputModality` enums (`config.org.InputModality` and `MessageContentBuilder.InputModality`) both go away. No type unification work is needed; both die in the same change.
- `DocumentTypeSchemaView.inputModality()` is callable from outside C1 (it is `public`). A grep confirms no production caller — both `LlmClassifier` and `ExtractRequestBuilder` use the schema for `id`, `displayName`, and `fields()`, never `inputModality()`. Safe to remove from the record.
- The schema-removal touches Hibernate; the `@Column(name = "input_modality", nullable = false)` annotation gets deleted with the field. `@IdClass(DocumentTypeId.class)` is unaffected — the composite PK is `(organization_id, id)`, no `input_modality` involvement.

## Risk notes

- **Test inventory must be exhaustive.** Listed in `02-analysis.md` §1.5: `StageGuardConfigTest`, `OrgConfigSeederIT`, `OrgConfigPersistenceFragmentIT`, `SeedFixturesTest`, `ConfigValidatorTest`, `LlmClassifierTest`, `LlmExtractorTest`, `MessageContentBuilderTest`, `LlmCallExecutorTest`. The change-spec enumerates the per-test edit shape so the implementer cannot miss one.
- **Spec edit risk:** `c1-config-spec.md` has at least seven references; `c3-pipeline-spec.md` has §3.5 plus the research summary §1. The change-spec lists each line. Spotless / Checkstyle will not catch a stale spec line; only careful enumeration will.

No external references needed. No SDK quirks. No backwards-compatibility risk. No data migration: greenfield.
