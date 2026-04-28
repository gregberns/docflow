# C1 Config Layer — Research Findings

Scope: the five open items flagged in `03-components.md` §C1 and `01-problem-space.md` Deferred. For each question: the question verbatim, the options considered, the recommendation, rationale, and citations/evidence. Recommendations are specific enough that Pass 5 can lift them into a change-spec unchanged.

**Post-research revisions (component walkthroughs with user).**

1. **Seed fixture, not live config.** The YAML files below are *seed fixtures*. On first install an application-level seeder reads them into the database; subsequent startups read from the database. Client data is persistent DB state, not derived from YAML on every start. Details in C1-R11.

2. **Canonical `WorkflowStatus` enum is a first-class domain concept.** Every stage in config declares a `canonicalStatus ∈ {AWAITING_REVIEW, AWAITING_APPROVAL, FILED, REJECTED}`. `FLAGGED` is a runtime-override value (never declared on a stage; applied when a review-kind stage has `workflowOriginStage` set). All filtering, stats aggregation, and dashboard queries run in canonical status; per-org stage names exist only for UI display and write-time routing. Details in C1-R12.

3. **Bounded-context re-split.** C3's classification and extraction results live in C3-owned tables (`document_classifications`, `document_extractions`), not on `Document`. C2's `Document` is pure ingestion (identity + storage + raw text). Details in C2-R4, C3-R14–R16.

4. **Workflow stages list dropped Upload/Classify/Extract.** Those are processing pipeline steps in C3, not workflow stages. Workflows start at Review. The `kind` enum is now `{review, approval, terminal}`; `canonicalStatus` enum drops `CLASSIFYING`/`EXTRACTING`.

Everything else in this findings document (YAML format via Jackson 3, tiny AST for guards, Jakarta Validation + hand-rolled cross-ref, role-as-string, no hot-reload) still applies unchanged.

---

## Q1. Config file format

**Question.** Problem space proposes YAML or DOT/graph. Investigate and recommend.

### Options considered

| Format | Pros | Cons | JVM tooling | Handles our shape? |
|---|---|---|---|---|
| **YAML** | Hand-writable; comments, anchors, multiline strings; universally understood; round-trips through Jackson cleanly | Indentation-sensitive; norway/billion-laughs foot-guns; schema not enforced by format | `jackson-dataformat-yaml` under Jackson 3 (Boot 4's default: `tools.jackson.dataformat:jackson-dataformat-yaml`); SnakeYAML as backing parser | Yes — nested orgs → doc-types → field schemas → workflows → transitions map directly |
| **TOML** | Simpler than YAML for flat config; no significant-whitespace gotchas; comments | Nested tables-of-tables awkward for arrays-of-objects-with-arrays; reads poorly for deep trees | `jackson-dataformat-toml` (Jackson 3) | Poorly — field schemas with nested `lineItems` produce `[[orgs.docTypes.fields.subSchema.fields]]` header noise |
| **HOCON** | Includes, substitutions, comments | Spring Boot does not natively bind HOCON (requires `com.typesafe:config` plus a bridge); niche outside Akka/Play | `com.typesafe:config` | Yes shape-wise, but stack friction is not worth it |
| **DOT / graphviz** | Expressive for the workflow graph; visually renderable | Foreign for field-schema content (DOT is node/edge only); forces a second format for field schemas; no typed JVM reader | JGraphT `DOTImporter` | Only the workflow half — splits config across two formats |
| **JSON** | Ubiquitous; cleanly supported by Jackson; strict schema via JSON Schema | No comments; verbose at this size; awkward to hand-edit | `jackson-databind` built-in | Yes, identical shape to YAML |
| **JSON5 / HJSON** | JSON + comments + trailing commas | Not JVM-native; adds a non-standard parser | `json5-java`, various forks | Yes, but tooling is second-class |

### Recommendation: **YAML, loaded via Jackson 3's `jackson-dataformat-yaml`, bound to Java records**

Three reasons:

1. **Shape fits.** The config is a tree of orgs → doc-types (with field schemas, some containing nested array-of-object sub-schemas) plus workflows (lists of stages, lists of transitions, each with an optional guard). Field schemas with nested `lineItems` read like `lineItems: { type: array, itemSchema: { fields: [...] } }` — no format gymnastics.
2. **Stack fit is clean.** Spring Boot 4 ships Jackson 3. Adding `tools.jackson.dataformat:jackson-dataformat-yaml` gives us typed binding to Java records with zero framework work: `new YAMLMapper().readValue(stream, OrgsConfigFile.class)`.
3. **Hand-writability beats DOT's graph-expressiveness.** The workflow shape is nine workflows, each 5–7 stages in a near-linear topology with exactly one guarded branch. DOT buys nothing over a simple `stages:`/`transitions:` YAML list.

### Files this governs (Pass 5 will create)

- `backend/src/main/resources/config/organizations.yaml` — three organizations per C1-R8.
- `backend/src/main/resources/config/doc-types/{org-slug}/{doc-type-slug}.yaml` — one file per (org, docType).
- `backend/src/main/resources/config/workflows/{org-slug}/{doc-type-slug}.yaml` — one file per workflow.
- `backend/src/main/java/.../config/ConfigLoader.java` — `YAMLMapper`-based loader with binding to records.

Splitting into per-(org,docType) files keeps edits localized and makes the synthetic fourth-org test (C1-R9) a file-drop rather than a surgical edit.

### Concrete sample (Riverside Bistro Invoice)

```yaml
# backend/src/main/resources/config/doc-types/riverside-bistro/invoice.yaml
id: invoice
displayName: Invoice
fields:
  - { name: vendor,         type: string,  required: true }
  - { name: invoiceNumber,  type: string,  required: true }
  - { name: invoiceDate,    type: date,    required: true }
  - { name: dueDate,        type: date,    required: false }
  - { name: subtotal,       type: decimal, required: true }
  - { name: tax,            type: decimal, required: false }
  - { name: totalAmount,    type: decimal, required: true }
  - { name: paymentTerms,   type: string,  required: false }
  - name: lineItems
    type: array
    required: true
    itemSchema:
      fields:
        - { name: description, type: string,  required: true }
        - { name: quantity,    type: decimal, required: true }
        - { name: unitPrice,   type: decimal, required: true }
        - { name: total,       type: decimal, required: true }
```

```yaml
# backend/src/main/resources/seed/workflows/riverside-bistro/invoice.yaml
organizationId: riverside-bistro
documentTypeId: invoice
stages:
  - { id: Review,           kind: review,   canonicalStatus: AWAITING_REVIEW }
  - { id: ManagerApproval,  kind: approval, canonicalStatus: AWAITING_APPROVAL, displayName: "Manager Approval", role: Manager }
  - { id: Filed,            kind: terminal, canonicalStatus: FILED }
  - { id: Rejected,         kind: terminal, canonicalStatus: REJECTED }
transitions:
  - { from: Review,          action: Approve,     to: ManagerApproval }
  - { from: Review,          action: Reject,      to: Rejected }
  - { from: ManagerApproval, action: Approve,     to: Filed }
  - { from: ManagerApproval, action: Flag,        to: Review }
```

**Evidence.** Jackson YAML docs: <https://github.com/FasterXML/jackson-dataformats-text>. Boot 4 binds YAML via Jackson 3; `application.yml` has always been a first-class citizen.

---

## Q2. Stage-guard predicate syntax

**Question.** Options: (a) simple string parsed by a one-off parser; (b) JSONPath + comparison operators; (c) MVEL/SpEL embedded expression; (d) tiny AST in config. For a 3–4 day take-home with ONE conditional, what's the right level of investment?

### Options considered

| Option | Sample | Cost to build | Cost to test | Risk |
|---|---|---|---|---|
| (a) Parsed string `"waiverType == unconditional"` | one-off tokenizer/regex | ~half day | medium — edge cases (quotes, spaces, types) | adds a parser to own forever |
| (b) JSONPath + operator | `{ path: "$.waiverType", op: eq, value: unconditional }` | ~2 hrs | low | overkill — we never nest that deep |
| (c) MVEL / SpEL | `#extractedFields['waiverType'] == 'unconditional'` | ~1 hr | medium — engine footprint, security surface | SpEL has known RCE CVE classes |
| **(d) Tiny AST** | `{ field: waiverType, op: eq, value: unconditional }` | ~30 min | trivial | — |

### Recommendation: **(d) Tiny AST with shape `{ field, op, value }`, `op ∈ {eq, neq}`**

The spec has exactly one conditional. Every other workflow is linear. Every minute spent on expression-language plumbing is a minute not spent on the LLM pipeline.

```java
public record StageGuardConfig(String field, GuardOp op, String value) {
    public boolean evaluate(Map<String, Object> extractedFields) {
        Object actual = extractedFields.get(field);
        return switch (op) {
            case EQ  -> Objects.equals(stringify(actual), value);
            case NEQ -> !Objects.equals(stringify(actual), value);
        };
    }
}
```

Lien Waiver config:

```yaml
transitions:
  - from: Review
    action: Approve
    to: Filed
    guard: { field: waiverType, op: eq,  value: unconditional }
  - from: Review
    action: Approve
    to: ProjectManagerApproval
    guard: { field: waiverType, op: neq, value: unconditional }
```

Extensible later (add `gt`/`lt`/`in`) without touching public API. Impossible to introduce RCE via config.

**Evidence.** SpEL injection CVEs: CVE-2022-22963. JSONPath library is sound but overkill for top-level scalar guards.

---

## Q3. Config validation library

### Recommendation: **Jakarta Bean Validation for structural + hand-rolled Java for cross-reference, both inside `ConfigLoader`**

**Structural (per-record)** — non-null ids, non-empty lists, etc. → `@NotBlank`, `@NotEmpty`, `@Valid` to cascade. Boot 4 ships `jakarta.validation` via `spring-boot-starter-validation`.

**Cross-reference** — "workflow references unknown stage", "org references unknown doc-type", "workflow lacks terminal stage", "every approval stage has a Flag transition back to Review" → plain Java in `ConfigLoader.validate(parsedConfig)`. Collect all failures in one shot; fail startup with a structured `ConfigValidationException`.

Full JSON Schema rejected — our config is first-party files; writing + maintaining a JSON Schema parallel to Java records invites drift.

**Evidence.** Jakarta Bean Validation 3.0 spec; Spring Boot 4 validation starter docs. The networknt JSON schema validator is kept in reserve for C3's LLM response-payload validation.

---

## Q4. Role slot for approval stages

### Recommendation: **String tag on stage**

`01-problem-space.md` Non-goals rules out auth. Without auth, a `Role` entity exists only to be displayed — it has no referent, no permission check.

String tag:
- Satisfies C1-R10 (optional descriptor on approval stages).
- Surfaces in the C5 payload with a one-line serializer addition.
- Renders in C6-R8 ("Attorney Approval — role: Attorney") with a one-line template change.
- Zero migration, zero extra table, zero FK.
- Preserves the stage-vs-role distinction in the config — a future auth layer can promote to an entity later without breaking callers.

Option (b) Role entity would cost: a `roles` table, Flyway migration, upsert-from-config mechanism, FK on stage model, and zero user-visible benefit today. Option (c) implicit drops a meaningful domain distinction the spec surfaces.

---

## Q5. Hot-reload

### Recommendation: **Load once at startup**

Three arguments:
1. C7-R13 already mandates startup-only loading for external configuration. Same principle applies here.
2. `docker-compose restart backend` is ~5 seconds.
3. The config is under the grep test (C1-R7, C4-R10). Editing in production without restart implies deployment that bypasses the test pipeline.

**What would justify hot-reload:** config edited by non-engineers in a UI; uptime SLA forbidding rolling restarts; rapidly-iterating per-customer A/B. None applies here.

---

## Risks & unknowns

1. **YAML indentation breakage.** Mitigated by parser's clear startup error; optional `yamllint` pre-commit.
2. **Bean Validation + Jakarta version drift with Boot 4.** Boot 4 uses Jakarta EE 11; imports must be `jakarta.validation.*`. Compiler catches drift.
3. **Jackson 3 YAML module naming.** Jackson 3 moved packages to `tools.jackson.*`. YAML module is `tools.jackson.dataformat:jackson-dataformat-yaml`. Pin the version; classic `com.fasterxml.jackson.dataformat:*` is Jackson 2.x and will class-clash.
4. **Guard AST may grow.** If a second conditional appears, add `gt`/`lt`/`in` to the enum. Low-cost extension.
5. **Role string collisions across orgs.** Cosmetic without auth; promotion to entity is one migration if auth ever lands.

---

## Summary (for Pass 5 change-spec)

- **Q1 format:** YAML via Jackson 3 (`tools.jackson.dataformat:jackson-dataformat-yaml`), bound to Java records; file layout per-(org,docType) files under `config/doc-types/` and `config/workflows/`. Workflows start at Review (Upload/Classify/Extract are C3 pipeline steps, not workflow stages); stage `kind ∈ {review, approval, terminal}`; stage `canonicalStatus ∈ {AWAITING_REVIEW, AWAITING_APPROVAL, FILED, REJECTED}` (FLAGGED is runtime-only).
- **Q2 guards:** tiny AST `{ field, op, value }`, `op ∈ {eq, neq}`.
- **Q3 validation:** Jakarta Bean Validation for structural + hand-rolled Java for cross-reference.
- **Q4 role:** string tag on stage.
- **Q5 hot-reload:** startup-only.
