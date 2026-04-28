# C1 Config Layer — Change Spec (Pass 5)

Source-of-truth requirements: `.kerf/project/docflow/03-components.md` §C1.
Research inputs: `.kerf/project/docflow/04-research/c1-config/findings.md`.
Code-path validation: `.kerf/project/docflow/02-analysis.md` (greenfield — no
existing source tree to integrate with; all paths below are net-new).

---

## 1. Requirements (carried forward, with coverage map)

Every C1 requirement from `03-components.md` is covered. Coverage column points
at the section of this spec that delivers it.

| ID | Requirement (summary) | Coverage |
|---|---|---|
| **C1-R1** | Organizations defined in committed config: slug, displayName, icon, ordered docTypes | §3.2 records, §4 `organizations.yaml`, §3.5 `OrganizationCatalog.getOrganization` |
| **C1-R2** | Doc-type field schemas: name, type ∈ {string,date,decimal,enum,array}, required, enum values, nested array-of-object sub-schemas; per-doc-type `inputModality ∈ {TEXT, PDF}` (default TEXT) consumed by C3 §3.5 | §3.2 `FieldSchema` / `FieldDefinition` / `ArrayItemSchema` / `InputModality`, §4 `doc-types/**/*.yaml` |
| **C1-R3** | Workflows keyed by `(organizationId, documentTypeId)`; ordered Stages starting at `Review`, terminal `Filed`/`Rejected`; each stage has `id`, `displayName`, `kind ∈ {review,approval,terminal}`, `canonicalStatus`, optional `role`; declares Transitions | §3.2 `WorkflowDefinition` / `StageDefinition`, §4 `workflows/**/*.yaml` |
| **C1-R4** | Transition shape `(fromStage, action, toStage, guard?)`, `action ∈ {AutoAdvance, Approve, Reject, Flag, Resolve}`; guards drive Lien Waiver branch — never stage-name or slug introspection | §3.2 `TransitionDefinition` / `TransitionAction`, §3.3 guard evaluation |
| **C1-R4a** | StageGuard references field by identifier and compares to literal | §3.3 tiny-AST `{field, op, value}` per research Q2 |
| **C1-R5** | Startup validation: unknown doc-type, unknown stage, missing field metadata, duplicate enum values, missing terminal stage | §3.4 `ConfigValidator`, §6 acceptance criteria CV-1..CV-5 |
| **C1-R6** | Typed read APIs: `getOrganization`, `listOrganizations`, `getDocumentTypeSchema`, `listDocumentTypes`, `getWorkflow`, `getAllowedDocTypes` | §3.5 catalog interfaces |
| **C1-R7** | `grepForbiddenStrings` build check returns zero hits for stage strings and client slugs outside config + tests | §3.6 grep enumeration, §4 `grepForbiddenStrings.gradle.kts` (delivered by C7-R5; C1 contributes the literal list) |
| **C1-R8** | Seed three orgs (Riverside, Pinnacle, Ironworks), nine field schemas, nine workflows | §4 seed fixtures (per `02-analysis.md` §1.1, §1.2) |
| **C1-R9** | Fourth-org extensibility: only new YAML/migration + restart needed; verified by seeder unit test | §6 acceptance criteria EXT-1 |
| **C1-R10** | Approval stages carry optional `role` descriptor | §3.2 `StageDefinition.role: String` per research Q4 |
| **C1-R11** | Client data lives in DB; YAML is seed-fixture only; on-empty seeder; subsequent startups read DB | §3.7 seeder, §4 Flyway migrations, §3.5 catalog reads from JPA |
| **C1-R12** | Canonical `WorkflowStatus` enum (`AWAITING_REVIEW`, `FLAGGED`, `AWAITING_APPROVAL`, `FILED`, `REJECTED`); every stage declares `canonicalStatus` (except `FLAGGED`, runtime-only) | §3.2 enum, §3.4 validator enforces mapping presence |

The spec also satisfies the C1 contributions to **C7-R13** (no `@Value` /
`System.getenv` / `.env` access outside `AppConfig`) by routing every external
parameter C1 needs (seed-on-boot toggle, seed resource path) through
`AppConfig.config`. See §3.8.

---

## 2. Research summary (decisions that drove this spec)

Research is in `.kerf/project/docflow/04-research/c1-config/findings.md`.
Decisions adopted here:

- **Q1 — File format: YAML via Jackson 3** (`tools.jackson.dataformat:jackson-dataformat-yaml`), bound to Java records. Per-file layout: one `organizations.yaml`, then `doc-types/{org-slug}/{doc-type-slug}.yaml` and `workflows/{org-slug}/{doc-type-slug}.yaml`. Findings §Q1, recommendation block.
- **Q2 — Guard syntax: tiny AST** `{field, op, value}` with `op ∈ {eq, neq}`. No expression engine (rejects SpEL/MVEL on RCE grounds; rejects JSONPath as overkill). Findings §Q2.
- **Q3 — Validation: Jakarta Bean Validation** for structural rules + **hand-rolled cross-reference** validator that collects all errors in one pass. JSON Schema rejected (drift risk). Findings §Q3.
- **Q4 — Role modeling: string tag on stage**. No `Role` entity, no FK, no separate table. Findings §Q4.
- **Q5 — Hot-reload: rejected. Startup-only.** `docker-compose restart backend` is the supported workflow. Findings §Q5.

Post-research revisions (findings §Post-research revisions) are baked in:
seed-fixture-not-live-config (R11), canonical `WorkflowStatus` (R12),
workflows start at Review (Upload/Classify/Extract are C3 pipeline steps, not
workflow stages), and `kind ∈ {review, approval, terminal}`.

---

## 3. Approach

### 3.1 Architecture decisions

1. **Two layers, not three.** YAML is parsed into immutable Java records by `ConfigLoader`. Seeder writes those records to the DB on first install. Catalog reads are issued against the DB (JPA repositories) — never against the YAML files at runtime. This is direct from C1-R11; YAML is a seed format only.
2. **Two distinct typed objects.** `AppConfig` (per C7-R13) holds environment-sourced runtime parameters (DB URL, API keys, seed paths, `seedOnBoot` toggle). `OrgConfig` is the in-memory aggregate of parsed-and-validated client data (organizations + doc-types + workflows). They are different concepts and live in different packages. The vocabulary `AppConfig` and `OrgConfig` is canonical per the parent prompt.
3. **Catalog interfaces, not direct repository access.** Other components depend on `OrganizationCatalog` / `DocumentTypeCatalog` / `WorkflowCatalog` interfaces (C1-R6). The implementation reads from JPA repositories. No component outside C1 imports the JPA entity types or the YAML records.
4. **Two-stage validation.** Jakarta `@NotBlank` / `@NotEmpty` / `@Valid` runs during YAML→record binding (catches structural errors). `ConfigValidator` runs after binding (catches cross-references: workflow→stage, org→doc-type, terminal-stage presence, duplicate enum values, `canonicalStatus` mapping completeness). All errors collected in one shot; startup fails with a structured `ConfigValidationException` that lists every failure.
5. **Stable enums, not strings, for action and kind.** `TransitionAction` and `StageKind` are Java enums; `canonicalStatus` is the `WorkflowStatus` enum. Jackson maps YAML scalar strings to these enums (case-sensitive — match the YAML in §4 fixtures exactly).

### 3.2 Class layout (Java records + enums)

Package `com.docflow.config.org` (parsed-and-validated client data):

```java
public record OrgConfig(
    List<OrganizationDefinition> organizations,
    List<DocTypeDefinition> docTypes,
    List<WorkflowDefinition> workflows
) {}

public record OrganizationDefinition(
    @NotBlank String id,
    @NotBlank String displayName,
    @NotBlank String iconId,
    @NotEmpty List<String> documentTypeIds
) {}

public record DocTypeDefinition(
    @NotBlank String organizationId,
    @NotBlank String id,
    @NotBlank String displayName,
    @NotNull InputModality inputModality,
    @NotEmpty @Valid List<FieldDefinition> fields
) {}

public enum InputModality { TEXT, PDF }

public record FieldDefinition(
    @NotBlank String name,
    @NotNull FieldType type,
    boolean required,
    List<String> enumValues,
    @Valid ArrayItemSchema itemSchema
) {}

public enum FieldType { STRING, DATE, DECIMAL, ENUM, ARRAY }

public record ArrayItemSchema(
    @NotEmpty @Valid List<FieldDefinition> fields
) {}

public record WorkflowDefinition(
    @NotBlank String organizationId,
    @NotBlank String documentTypeId,
    @NotEmpty @Valid List<StageDefinition> stages,
    @NotEmpty @Valid List<TransitionDefinition> transitions
) {}

public record StageDefinition(
    @NotBlank String id,
    String displayName,
    @NotNull StageKind kind,
    @NotNull WorkflowStatus canonicalStatus,
    String role
) {}

public enum StageKind { REVIEW, APPROVAL, TERMINAL }

public enum WorkflowStatus {
    AWAITING_REVIEW,
    FLAGGED,
    AWAITING_APPROVAL,
    FILED,
    REJECTED
}

public record TransitionDefinition(
    @NotBlank String from,
    @NotNull TransitionAction action,
    @NotBlank String to,
    StageGuardConfig guard
) {}

public enum TransitionAction { AUTO_ADVANCE, APPROVE, REJECT, FLAG, RESOLVE }

public record StageGuardConfig(@NotBlank String field, @NotNull GuardOp op, @NotBlank String value) {
    public boolean evaluate(Map<String, Object> extractedFields) {
        Object actual = extractedFields.get(field);
        return switch (op) {
            case EQ  -> Objects.equals(stringify(actual), value);
            case NEQ -> !Objects.equals(stringify(actual), value);
        };
    }
    private static String stringify(Object v) { return v == null ? null : v.toString(); }
}

public enum GuardOp { EQ, NEQ }
```

`displayName` on `StageDefinition` is nullable — terminal stages
(`Filed`, `Rejected`) inherit a default display name from the stage `id` if
omitted in YAML. The validator (§3.4) requires `displayName` for non-terminal
stages.

### 3.3 Guard evaluation

Guards are evaluated by `StageGuardConfig.evaluate(extractedFields)` against
`Document.extractedFields` (a `Map<String,Object>` on the C4 side). C1 only
defines the type and the evaluator; C4 calls it during transition selection.
This keeps stage-name introspection out of service code (C1-R4).

### 3.4 Validation

Class `ConfigValidator` (in `com.docflow.config.org.validation`):

```java
public final class ConfigValidator {
    public void validate(OrgConfig config) throws ConfigValidationException { ... }
}
```

Cross-reference checks (C1-R5):

- **CV-1.** Every `OrganizationDefinition.documentTypeIds[i]` resolves to a `DocTypeDefinition` whose `(organizationId, id)` matches.
- **CV-2.** Every `WorkflowDefinition.transitions[i].from` and `.to` matches some `WorkflowDefinition.stages[j].id` in the same workflow.
- **CV-3.** Every `WorkflowDefinition` contains at least one `StageDefinition` with `kind == TERMINAL`.
- **CV-4.** No `FieldDefinition.enumValues` contains duplicate values.
- **CV-5.** `WorkflowDefinition.stages` starts with a stage whose `id == "Review"` and `kind == REVIEW`.
- **CV-6.** Every `StageDefinition` has a non-null `canonicalStatus`. Mapping rules: `kind=REVIEW → AWAITING_REVIEW`; `kind=APPROVAL → AWAITING_APPROVAL`; `kind=TERMINAL → FILED or REJECTED`. `FLAGGED` is never declared on a stage (validator rejects it). Validator enforces this kind→status compatibility table.
- **CV-7.** Within one workflow, every `(organizationId, documentTypeId)` is unique (no duplicate workflow rows in DB after seed; pre-seed the validator catches duplicates in YAML).
- **CV-8.** Field metadata for `type=ENUM` requires non-empty `enumValues`; `type=ARRAY` requires non-null `itemSchema`. Other types must omit both.

All failures accumulate; `validate` throws once with all messages or returns silently.

### 3.5 Catalog interfaces (C1-R6)

Package `com.docflow.config.catalog`:

```java
public interface OrganizationCatalog {
    Optional<OrganizationView> getOrganization(String orgId);
    List<OrganizationView> listOrganizations();
    List<String> getAllowedDocTypes(String orgId);
}

public interface DocumentTypeCatalog {
    Optional<DocumentTypeSchemaView> getDocumentTypeSchema(String orgId, String docTypeId);
    List<DocumentTypeSchemaView> listDocumentTypes(String orgId);
}

public interface WorkflowCatalog {
    Optional<WorkflowView> getWorkflow(String orgId, String docTypeId);
}
```

Implementations (`OrganizationCatalogImpl`, etc.) are Spring beans. They JPA-load
once on startup (after seeding, see §3.7) into immutable `*View` records cached
in memory; subsequent reads hit the in-memory cache. This satisfies C1-R6
"typed read APIs" while avoiding per-request JPA round-trips for data that
cannot change without a restart (C1-R5, C1-R11, research Q5).

`*View` records are a thin projection over the JPA entities; they expose only
the fields C2/C3/C4/C5 need (no Hibernate proxies leak across the boundary).

### 3.6 Forbidden-strings enumeration (C1-R7 contribution)

C7-R5 builds the `grepForbiddenStrings` Gradle task. C1 owns the **list of
literals** the task must check. Literal list (committed at
`config/forbidden-strings.txt`):

Stage names:
- `Review`, `Manager Approval`, `Finance Approval`, `Attorney Approval`, `Billing Approval`, `Partner Approval`, `Project Manager Approval`, `Accounting Approval`, `Client Approval`, `Filed`, `Rejected`

Client slugs:
- `riverside-bistro`, `pinnacle-legal`, `ironworks-construction`
- Display variants: `Riverside Bistro`, `Pinnacle Legal Group`, `Ironworks Construction`

The task scans `backend/src/main/java/**/*.java` excluding (a) the C1 config
package (`com.docflow.config.**`), (b) Flyway migration classes, and
(c) test sources. Hits cause `./gradlew check` to fail. Test files and
`backend/src/main/resources/seed/**` are exempt.

Note: `Review`, `Filed`, `Rejected` are also `WorkflowStatus`/stage `id`
values used in code via the enum. The grep targets **literal string
occurrences** (the Java source token `"Review"`, etc.) — enum references
(`WorkflowStatus.FILED`) are not literals and pass.

The grep task implementation lives in C7's Gradle plumbing; **C1 ships the
literal list and the unit tests that prove a representative violation in a
fixture file is detected.**

### 3.7 Seed-on-boot (C1-R11)

Spring `@Component` `OrgConfigSeeder` with `@EventListener(ApplicationReadyEvent.class)`:

```
if (!appConfig.config.seedOnBoot) return;
if (organizationRepository.count() > 0) return;          // idempotent
OrgConfig parsed = configLoader.load(appConfig.config.seedResourcePath);
configValidator.validate(parsed);                         // throws on failure
seedWriter.persist(parsed);                               // single transaction
```

The seeder runs **once** per fresh database. Subsequent restarts skip parsing
and writing. Catalog beans (§3.5) initialize after the seeder via
`@DependsOn("orgConfigSeeder")`.

`seedWriter.persist` is a single `@Transactional` method that inserts
organizations, doc-types, workflows, stages, transitions in dependency order.
On any failure the transaction rolls back and startup fails with the
underlying error.

### 3.8 AppConfig (C7-R13 contribution from C1)

C7 owns the `AppConfig` record and is the **single config reader**: all
`@ConfigurationProperties` binding, `@Value`, `System.getenv`, and `.env`
access are confined to the `com.docflow.config` package (C7's allow-listed
location per `grepForbiddenStrings`). C1 contributes one nested type
definition — `OrgConfigBootstrap` — which lives **inside `com.docflow.config`
alongside `AppConfig` itself**, not inside C1's `com.docflow.config.org.*`
package tree. Other components (including C1's own seeder) consume the
bound values by injection only.

```java
// Located at com.docflow.config (C7's package; C1 contributes the type)
public record AppConfig(
    Llm llm,
    Storage storage,
    Database database,
    OrgConfigBootstrap config        // <-- C1-contributed type
) {
    public record OrgConfigBootstrap(
        boolean seedOnBoot,
        String seedResourcePath
    ) {}
}
```

`seedOnBoot` defaults to `true` in dev profile, `false` in prod (per C1-R11
production guidance — production seeds via Flyway migration, not auto-seed).
`seedResourcePath` defaults to `classpath:seed/`.

These two values are read once via `@ConfigurationProperties` binding inside
the `AppConfig` builder in C7's package. No code outside `com.docflow.config`
reads `@Value`, `System.getenv`, or `.env`. C1's `OrgConfigSeeder` receives
the bound `AppConfig.OrgConfigBootstrap` via constructor injection.

---

## 4. Files & changes

All paths below are **new** (greenfield repo per `02-analysis.md` §7). No
modifications to existing source files; the only existing files are
`problem-statement/` (do not modify), `.kerf/`, and the env scaffolding.

### 4.1 Java sources (create)

C1's parsed-data records and runtime classes live under
`com.docflow.config.org.*`. The single C1-contributed type that participates
in env-var binding (`OrgConfigBootstrap`, §3.8) is **not** in this package —
it is declared as a nested record inside `AppConfig.java` in C7's
`com.docflow.config` package, where all `@ConfigurationProperties` binders
are required to live (C7-R13). C1 ships the type definition through C7's
spec; no `@ConfigurationProperties`, `@Value`, `System.getenv`, or `.env`
read appears anywhere under `com.docflow.config.org.*`.

- `backend/src/main/java/com/docflow/config/org/OrgConfig.java` — record (root container).
- `backend/src/main/java/com/docflow/config/org/OrganizationDefinition.java`
- `backend/src/main/java/com/docflow/config/org/DocTypeDefinition.java`
- `backend/src/main/java/com/docflow/config/org/InputModality.java`
- `backend/src/main/java/com/docflow/config/org/FieldDefinition.java`
- `backend/src/main/java/com/docflow/config/org/FieldType.java`
- `backend/src/main/java/com/docflow/config/org/ArrayItemSchema.java`
- `backend/src/main/java/com/docflow/config/org/WorkflowDefinition.java`
- `backend/src/main/java/com/docflow/config/org/StageDefinition.java`
- `backend/src/main/java/com/docflow/config/org/StageKind.java`
- `backend/src/main/java/com/docflow/config/org/WorkflowStatus.java`
- `backend/src/main/java/com/docflow/config/org/TransitionDefinition.java`
- `backend/src/main/java/com/docflow/config/org/TransitionAction.java`
- `backend/src/main/java/com/docflow/config/org/StageGuardConfig.java`
- `backend/src/main/java/com/docflow/config/org/GuardOp.java`
- `backend/src/main/java/com/docflow/config/org/loader/ConfigLoader.java` — Jackson 3 YAML parser; uses `tools.jackson.dataformat.yaml.YAMLMapper`.
- `backend/src/main/java/com/docflow/config/org/validation/ConfigValidator.java`
- `backend/src/main/java/com/docflow/config/org/validation/ConfigValidationException.java`
- `backend/src/main/java/com/docflow/config/org/seeder/OrgConfigSeeder.java`
- `backend/src/main/java/com/docflow/config/org/seeder/OrgConfigSeedWriter.java`
- `backend/src/main/java/com/docflow/config/catalog/OrganizationCatalog.java`
- `backend/src/main/java/com/docflow/config/catalog/OrganizationCatalogImpl.java`
- `backend/src/main/java/com/docflow/config/catalog/OrganizationView.java`
- `backend/src/main/java/com/docflow/config/catalog/DocumentTypeCatalog.java`
- `backend/src/main/java/com/docflow/config/catalog/DocumentTypeCatalogImpl.java`
- `backend/src/main/java/com/docflow/config/catalog/DocumentTypeSchemaView.java`
- `backend/src/main/java/com/docflow/config/catalog/WorkflowCatalog.java`
- `backend/src/main/java/com/docflow/config/catalog/WorkflowCatalogImpl.java`
- `backend/src/main/java/com/docflow/config/catalog/WorkflowView.java`
- `backend/src/main/java/com/docflow/config/catalog/StageView.java`
- `backend/src/main/java/com/docflow/config/catalog/TransitionView.java`

### 4.2 JPA entities + repositories (create)

Reflect the C1-R11 schema. Entity package
`com.docflow.config.persistence`:

- `OrganizationEntity` — table `organizations` (`id PK varchar`, `display_name`, `icon_id`).
- `OrganizationDocTypeEntity` — join table `organization_doc_types` (`organization_id FK`, `document_type_id FK`, `ordinal int`); composite PK + index on `organization_id`.
- `DocumentTypeEntity` — table `document_types` (`organization_id FK`, `id`, `display_name`, `input_modality varchar` ∈ `{TEXT, PDF}` default `TEXT`, `field_schema JSONB`); composite PK `(organization_id, id)`.
- `WorkflowEntity` — table `workflows` (`organization_id FK`, `document_type_id`, composite FK to `document_types`, PK `(organization_id, document_type_id)`).
- `StageEntity` — table `stages` (`organization_id`, `document_type_id`, `id`, `display_name`, `kind`, `canonical_status`, `role`, `ordinal int`); composite PK `(organization_id, document_type_id, id)`; composite FK to `workflows`.
- `TransitionEntity` — table `transitions` (`organization_id`, `document_type_id`, `from_stage`, `to_stage`, `action`, `guard_field`, `guard_op`, `guard_value`, `ordinal int`); composite FK to `workflows`; FK on `(organization_id, document_type_id, from_stage)` and `(organization_id, document_type_id, to_stage)` to `stages`.

Repositories: `OrganizationRepository`, `DocumentTypeRepository`,
`WorkflowRepository`, `StageRepository`, `TransitionRepository` — each
extending `JpaRepository`. Used by `OrgConfigSeedWriter` and the catalog
impls; not exported outside C1.

Indexes (C1 mandates per CLAUDE.md §Database schema): every FK gets an index.
Additionally: `idx_stages_workflow (organization_id, document_type_id, ordinal)`.

`field_schema` is the only JSONB column — schema legitimately dynamic per
doc-type per CLAUDE.md §Database schema.

### 4.3 Flyway migrations (contribute to V1__init.sql)

Per C7-R3 and HANDOFF.md, the project ships a **single baseline migration
`V1__init.sql`** owned by C7. C1 does **not** add a separate `V{n}__*.sql`
file. Instead, C1 contributes the SQL fragments below; C7 stitches them
into `V1__init.sql` in the correct dependency order alongside the C2/C3/C4
fragments. Mirrors the C3 contribution model.

C1 fragments (all DDL, no DML — seed data is written by `OrgConfigSeeder`,
not by the migration):

- `CREATE TABLE` statements for each entity in §4.2 (`organizations`,
  `organization_doc_types`, `document_types`, `workflows`, `stages`,
  `transitions`).
- Foreign-key declarations as listed in §4.2.
- Indexes per §4.2 (every FK; plus `idx_stages_workflow (organization_id,
  document_type_id, ordinal)`).
- `CHECK` constraints on the enum-valued columns (`kind`, `canonical_status`,
  `action`, `guard_op`, `input_modality`).

The fragments are committed at
`backend/src/main/resources/db/migration/fragments/c1-org-config.sql` (a
non-Flyway-loaded source file consumed by C7's `V1__init.sql` assembly
step). C1 owns the contents; C7 owns the assembled migration file and the
ordering decision.

### 4.4 Seed YAML fixtures (create)

Per research Q1 file layout:

- `backend/src/main/resources/seed/organizations.yaml`
- `backend/src/main/resources/seed/doc-types/riverside-bistro/{invoice,receipt,expense-report}.yaml`
- `backend/src/main/resources/seed/doc-types/pinnacle-legal/{invoice,retainer-agreement,expense-report}.yaml`
- `backend/src/main/resources/seed/doc-types/ironworks-construction/{invoice,change-order,lien-waiver}.yaml`
- `backend/src/main/resources/seed/workflows/riverside-bistro/{invoice,receipt,expense-report}.yaml`
- `backend/src/main/resources/seed/workflows/pinnacle-legal/{invoice,retainer-agreement,expense-report}.yaml`
- `backend/src/main/resources/seed/workflows/ironworks-construction/{invoice,change-order,lien-waiver}.yaml`

Field schemas mirror `02-analysis.md` §1.1 verbatim. Workflows mirror
`02-analysis.md` §1.2 minus the Upload/Classify/Extract prefix (which are C3
pipeline steps, per post-research revision §4 of findings); each workflow
starts at `Review` and ends at `Filed`/`Rejected`.

Each doc-type YAML carries an `inputModality` key (default `TEXT`). The
four doc-types with nested-array schemas — Riverside Invoice, Riverside
Expense Report, Pinnacle Expense Report, Ironworks Invoice — set
`inputModality: PDF`. The remaining five (Riverside Receipt, Pinnacle
Invoice, Pinnacle Retainer Agreement, Ironworks Change Order, Ironworks
Lien Waiver) omit the key and inherit the `TEXT` default. C3 reads
`DocTypeDefinition.inputModality` per the C3 spec §3.5 hybrid decision.

The Ironworks Lien Waiver workflow encodes the conditional via two
`Review → ...` Approve transitions with inverse `waiverType`-eq guards
exactly as findings §Q2 shows.

### 4.5 Build / config plumbing

- `backend/build.gradle.kts` — add dependencies: `tools.jackson.dataformat:jackson-dataformat-yaml`, `org.springframework.boot:spring-boot-starter-validation`. Versions pinned at the project root per `02-analysis.md` §4.2.
- `config/forbidden-strings.txt` — literal list per §3.6. Consumed by C7-R5's `grepForbiddenStrings` task.
- `backend/src/main/resources/application.yml` — `app.config.seedOnBoot: true` (default) + `app.config.seedResourcePath: classpath:seed/`. Bound by `AppConfig.OrgConfigBootstrap` (§3.8). C7 spec owns the rest of `application.yml`; C1 only adds these two keys.

### 4.6 Tests (create)

- `backend/src/test/java/com/docflow/config/org/loader/ConfigLoaderTest.java` — happy-path parse of all nine seed fixtures; asserts records bound correctly.
- `backend/src/test/java/com/docflow/config/org/validation/ConfigValidatorTest.java` — table-driven failure cases CV-1..CV-8 each via a tampered fixture.
- `backend/src/test/java/com/docflow/config/org/StageGuardConfigTest.java` — eq/neq evaluation against sample `extractedFields` maps; null-handling.
- `backend/src/test/java/com/docflow/config/org/seeder/OrgConfigSeederTest.java` — Spring slice test; on-empty seeds nine workflows + three orgs; on-non-empty no-op; idempotent across two restarts.
- `backend/src/test/java/com/docflow/config/catalog/OrganizationCatalogIT.java` — integration test against Postgres (Testcontainers, provided by C7); asserts all six C1-R6 read APIs return expected data after seeding.
- `backend/src/test/java/com/docflow/config/extensibility/FourthOrgSeederTest.java` — drops a fourth-org YAML fixture (`backend/src/test/resources/seed-fourth-org/**`) and runs the seeder against an empty DB; asserts catalog APIs return the new org/doc-type/workflow. Satisfies C1-R9 verification clause verbatim.
- `backend/src/test/java/com/docflow/config/org/GrepForbiddenStringsTest.java` — runs the grep task against a fixture Java file containing a violation; asserts task fails. Provides the C1-side proof; the task wiring is C7's deliverable.

Test fixtures under `backend/src/test/resources/`:
- `seed-fourth-org/organizations.yaml` (one extra org)
- `seed-fourth-org/doc-types/synthetic-org/widget.yaml`
- `seed-fourth-org/workflows/synthetic-org/widget.yaml`
- `validator-fixtures/{cv-1..cv-8}/...` — broken fixtures, one per check.

---

## 5. Acceptance criteria

Each is concrete and verifiable.

### Loader & records (C1-R1, C1-R2, C1-R3, C1-R4, C1-R10, C1-R12)

- **AC-L1.** `ConfigLoader.load("classpath:seed/")` returns an `OrgConfig` with exactly 3 organizations, 9 doc-types, 9 workflows. Verified by `ConfigLoaderTest`.
- **AC-L2.** Round-trip YAML → records: `OrgConfig.docTypes` for `(riverside-bistro, invoice)` contains 9 fields including `lineItems` of type `ARRAY` whose `itemSchema.fields` has 4 entries (`description`, `quantity`, `unitPrice`, `total`). Tested.
- **AC-L3.** Workflow `(ironworks-construction, lien-waiver)` has exactly 2 transitions out of `Review` with `action == APPROVE`, one with guard `{waiverType, EQ, unconditional}`, the other with `{waiverType, NEQ, unconditional}`. Tested.
- **AC-L4.** `StageDefinition.role` is non-null for every stage with `kind == APPROVAL` in the seed fixtures, null for `kind ∈ {REVIEW, TERMINAL}`. Tested.
- **AC-L5.** `WorkflowStatus` enum has exactly 5 values in the order `AWAITING_REVIEW, FLAGGED, AWAITING_APPROVAL, FILED, REJECTED`. Tested.
- **AC-L6.** `InputModality` enum has exactly two values `TEXT, PDF`. After loading `seed/`, `inputModality == PDF` for `(riverside-bistro, invoice)`, `(riverside-bistro, expense-report)`, `(pinnacle-legal, expense-report)`, `(ironworks-construction, invoice)`; the remaining five doc-types report `TEXT`. Tested.

### Validation (C1-R5)

- **AC-V1..AC-V8.** Each `validator-fixtures/cv-N/` triggers exactly the corresponding error: CV-1 unknown doc-type ref, CV-2 unknown stage ref, CV-3 missing terminal, CV-4 duplicate enum, CV-5 first stage not Review, CV-6 wrong canonicalStatus for kind, CV-7 duplicate workflow, CV-8 ENUM without enumValues. Verified by `ConfigValidatorTest`.
- **AC-V9.** When two errors are present in one fixture, `ConfigValidationException.getMessage()` mentions both. Tested.

### Seeder & idempotency (C1-R11)

- **AC-S1.** Against an empty DB, seeder inserts 3 org rows, 9 doc-type rows, 9 workflow rows, ≥27 stage rows, ≥36 transition rows. Counts verified.
- **AC-S2.** Re-running the seeder against the populated DB leaves row counts unchanged (no duplicates, no errors).
- **AC-S3.** With `app.config.seedOnBoot=false`, seeder does not parse YAML or write to DB even if DB is empty. Asserted by checking no `INSERT` is issued.

### Catalog (C1-R6)

- **AC-C1.** `OrganizationCatalog.listOrganizations()` returns 3 entries with stable order matching `organizations.yaml`. Tested.
- **AC-C2.** `OrganizationCatalog.getOrganization("nope")` returns `Optional.empty()`. Tested.
- **AC-C3.** `DocumentTypeCatalog.getDocumentTypeSchema("pinnacle-legal", "retainer-agreement")` returns the 7-field schema with no nested arrays. Tested.
- **AC-C4.** `DocumentTypeCatalog.listDocumentTypes("riverside-bistro")` returns exactly 3 entries in the order specified by `OrganizationDefinition.documentTypeIds`.
- **AC-C5.** `WorkflowCatalog.getWorkflow("ironworks-construction", "lien-waiver")` returns a workflow whose `transitions` list contains both guard-bearing `Review → ...` Approve transitions.
- **AC-C6.** `OrganizationCatalog.getAllowedDocTypes("riverside-bistro")` returns `["invoice", "receipt", "expense-report"]`.

### Extensibility (C1-R9)

- **AC-E1.** `FourthOrgSeederTest`: with `seed-fourth-org/` resources on classpath in place of `seed/`, the seeder loads 4 orgs, 10 doc-types, 10 workflows; catalog APIs return the new entries. No source change in C2/C3/C4/C5/C6 needed (verified by test compiling and passing without modifying any non-test file outside the C1 package).

### Grep enforcement (C1-R7)

- **AC-G1.** `GrepForbiddenStringsTest` plants a Java fixture file under `backend/src/test/resources/grep-fixtures/Bad.java` containing the literal `"Manager Approval"`; the test wraps the grep invocation and asserts it fails with that file/line in the failure message.
- **AC-G2.** Same test asserts a control fixture using `WorkflowStatus.FILED` (enum reference, not literal) does **not** fail.

### AppConfig boundary (C7-R13 contribution)

- **AC-AC1.** Static analysis (covered by C7's grep) finds zero references to `System.getenv`, `@Value`, or `.env` parsing inside `com.docflow.config.**` other than the `AppConfig` binder. Hand-verified for the C1 spec; C7's grep test exists but is C7's deliverable.

---

## 6. Verification

Run the following from repo root:

1. `./gradlew :backend:test --tests "com.docflow.config.*"` — runs all C1 unit and slice tests.
2. `./gradlew :backend:integrationTest --tests "com.docflow.config.*IT"` — runs the catalog Testcontainers IT.
3. `./gradlew :backend:check` — runs the full quality gate including `grepForbiddenStrings`, Spotless, Checkstyle, PMD, Error Prone, JaCoCo. Must pass.
4. `./gradlew :backend:bootRun` against an empty Postgres (per `docker-compose up`) — observe in logs:
   - `OrgConfigSeeder: seeded 3 organizations, 9 document types, 9 workflows`
   - Subsequent `bootRun` invocations log `OrgConfigSeeder: seed skipped (organizations table non-empty)`.
5. Manual sanity: connect to Postgres and run `SELECT count(*) FROM organizations;` → 3.
6. Failure-path check: tamper one seed fixture (e.g., delete `Filed` stage from `riverside-bistro/invoice.yaml`), restart against an empty DB → startup fails with `ConfigValidationException` listing CV-3.

---

## 7. Error handling and edge cases

| Situation | Handling |
|---|---|
| YAML structurally invalid (bad indentation, unknown field) | Jackson throws `JsonProcessingException`; `ConfigLoader` rewraps as `ConfigLoadException` citing file path + line. Startup fails. |
| Bean Validation violation on a record (e.g., `@NotBlank` id missing) | `ConstraintViolationException` from Jakarta Validation surfaced via `ConfigLoader`; converted to a `ConfigValidationException` entry. |
| Cross-reference violation (CV-1..CV-8) | Collected in `ConfigValidator`; thrown once after all fixtures are checked. Startup fails. |
| DB connection failure during seed | Underlying Spring/JPA exception bubbles up; transaction rolls back; startup fails. |
| Seed table partial (e.g., 2 of 3 orgs present) | Out of scope per C1-R11 — the seeder treats `count(*) > 0` as "already seeded". An operator who manually deletes one row gets no automatic re-seed; they must wipe and restart. Tested by AC-S2. |
| `seedOnBoot=true` with non-empty DB | No-op (AC-S2). No error. |
| `seedOnBoot=false` with empty DB | No seed performed. Catalog returns empty lists. Other components (C2 ingest) will reject any upload because `getOrganization` returns empty. Documented behavior; intended for prod where Flyway-driven inserts replace the seeder. |
| Guard with field name not in `extractedFields` | `StageGuardConfig.evaluate` returns `false` for `EQ`, `true` for `NEQ` (since `null != "unconditional"`). Behavior is intentional and aligns with the Lien Waiver semantics — a missing waiverType reads as "not unconditional", routing to PM Approval (the safer branch). Tested. |
| Ordinal collisions between stages or transitions in DB | Prevented by composite PKs and ordinal columns assigned in seeder insertion order. |
| Catalog accessed before seeding completes | `@DependsOn("orgConfigSeeder")` on catalog beans guarantees ordering; if the seeder failed, Spring fails startup before any catalog bean instantiates. |

---

## 8. Migration / backwards compatibility

N/A — greenfield project per `02-analysis.md` §7. No prior schema, no prior
config format. The seeder's idempotency (§3.7) covers the closest analog
("upgrade to a new build with the same DB"): subsequent seeds are no-ops.

In production, future client-data changes are made via new Flyway migrations
(`V_N__update_workflow_xxx.sql`) per C1-R11, never by editing applied
migrations or the seed YAML.
