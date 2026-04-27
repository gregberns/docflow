# DocFlow — Analysis

Factual map of the territory we will be building on. This is **greenfield** — there is no existing application code, so the usual "current codebase" survey is replaced by a survey of the input material (spec, mockups, sample corpus) and a concrete reality-check on the chosen tech stack. Git history is empty; the only tracked files at the time of writing are `problem-statement/` (the take-home inputs), `.kerf/project/` (this planning work), `AGENTS.md` / `CLAUDE.md`, and the env-file scaffolding.

Source inputs referenced below:

- `problem-statement/DocFlow_Take_Home_Exercise_greg_berns.md` — the spec (extracted from the provided PDF).
- `problem-statement/mockups/*.html` — 13 HTML mockup files.
- `problem-statement/samples/**/*.pdf` — 24 sample PDFs across 9 (client × doc-type) buckets.

Downstream passes should treat this document plus `01-problem-space.md` as the primary reference.

---

## 1. Spec inventory

### 1.1 Fields by client and document type

#### Riverside Bistro (restaurant chain)

| Doc type | Scalar fields | Nested structure | Enumerated values |
|---|---|---|---|
| **Invoice** | vendor, invoiceNumber, invoiceDate, dueDate, subtotal, tax, totalAmount, paymentTerms | `lineItems: [{description, quantity, unitPrice, total}]` | — |
| **Receipt** | merchant, date, amount, paymentMethod | — | `category ∈ {food, supplies, equipment, services}` |
| **Expense Report** | employeeName, department, submissionDate, totalAmount | `items: [{date, description, amount, category}]` | `category` (same enum as Receipt, implicit) |

#### Pinnacle Legal Group (law firm)

| Doc type | Scalar fields | Nested structure | Enumerated values |
|---|---|---|---|
| **Invoice** | vendor, invoiceNumber, invoiceDate, matterNumber, matterName, amount, billingPeriod, paymentTerms | — | — |
| **Retainer Agreement** | clientName, matterType, hourlyRate, retainerAmount, effectiveDate, termLength, scope | — | — |
| **Expense Report** | attorneyName, matterNumber, submissionDate, totalAmount | `items: [{date, description, amount, billable}]` | `billable ∈ {yes, no}` |

#### Ironworks Construction (general contractor)

| Doc type | Scalar fields | Nested structure | Enumerated values |
|---|---|---|---|
| **Invoice** | vendor, invoiceNumber, invoiceDate, projectCode, projectName, amount, deliveryDate, paymentTerms | `materials: [{item, quantity, unitCost}]` | — |
| **Change Order** | projectCode, projectName, description, costImpact, scheduleImpact, requestedBy, approvedBy | — | — |
| **Lien Waiver** | subcontractor, projectCode, projectName, amount, throughDate | — | `waiverType ∈ {conditional, unconditional}` |

Every list field is structured (objects-with-named-fields), never bare scalars.

### 1.2 Workflows

All workflows start with the same three system stages: **Upload → Classify → Extract**. After that:

| Client | Doc type | Post-extract stages | Notes |
|---|---|---|---|
| Riverside | Invoice | Review → Manager Approval → Filed | |
| Riverside | Receipt | Review → Filed | Shortest workflow |
| Riverside | Expense Report | Review → Manager Approval → Finance Approval → Filed | |
| Pinnacle | Invoice | Review → Attorney Approval → Billing Approval → Filed | |
| Pinnacle | Retainer Agreement | Review → Partner Approval → Filed | |
| Pinnacle | Expense Report | Review → Attorney Approval → Billing Approval → Filed | Same as Pinnacle Invoice |
| Ironworks | Invoice | Review → Project Manager Approval → Accounting Approval → Filed | |
| Ironworks | Change Order | Review → Project Manager Approval → Client Approval → Filed | |
| Ironworks | Lien Waiver | Review → Project Manager Approval → Filed | **Conditional:** if `waiverType == unconditional`, skip PM Approval → Filed |

### 1.3 Stage taxonomy

- **System stages** (automatic, no human action): Upload, Classify, Extract.
- **Human review stage**: Review (editable form; Approve / Reject / Resolve-when-flagged).
- **Human approval stages** (read-only form; Approve / Flag with required comment):
  Manager Approval, Finance Approval, Attorney Approval, Billing Approval, Partner Approval, Project Manager Approval, Accounting Approval, Client Approval. Eight distinct approval names across the three clients; none shared across clients.
- **Terminal states**: Filed, Rejected.

**Domain-modeling implication (open).** The eight approval stages map to distinct **roles** ("Manager", "Finance Specialist", "Attorney", "Billing Specialist", "Partner", "Project Manager", "Accountant", "Client"). The spec itself uses the approval-stage name to mean both *step in a workflow* and *kind of human approver* — these are logically different concepts. Since the app has no auth and no user identity, we can't enforce role-based access, but we can still model "this stage is approved by the X role" as config metadata, display the role name in the UI alongside the stage label, and preserve the distinction in the data so a future auth layer could attach user.role → allowed-stage rules. Decision on whether to model role as (a) a string tag on each stage in config, (b) a first-class `Role` entity with a FK from stage, or (c) leave implicit and ship stage names only — deferred to Pass 4 research. Flagged in `01-problem-space.md` Deferred section.

### 1.4 State transitions

- System: `Upload → Classify → Extract → Review` (automatic chain).
- Review approve: `Review → <next stage in workflow>`.
- Review reject: `Review → Rejected` (terminal).
- Review → re-extract trigger: if the user changes the document type on the Review form, the system re-runs extraction using the new type's schema; the doc stays in Review after the re-extract completes. An alert is shown before committing the change.
- Approval approve: `<approval stage> → <next stage in workflow>`.
- Approval flag: `<approval stage> → Review` with `originStage = <approval stage>` recorded and a required `flagComment` captured.
- Review (flagged) resolve: if the user did **not** change the document type → `Review → originStage`, with `flagComment` and `originStage` cleared. If they **did** change the type → re-extraction runs, after which the document remains in `Review` (not the origin stage) — the flag is effectively reset because the underlying data changed.
- Filed / Rejected: no outgoing transitions.

### 1.5 Under-specified items

The spec is intentionally light on these; some are answered during decomposition, some are deliberate non-concerns.

1. Field-level validation rules on the Review form (required vs. optional, date format, min/max, numeric precision).
2. Exact UI affordance for the re-classification alert (modal confirm vs. inline warning banner). Mockups answer this — see §2.
3. Editing semantics for nested arrays in the Review form (add/remove rows, inline edit, empty-row placeholder).
4. Exact data types — `amount` etc. are not typed in the spec. Dollar amounts in samples are all `$NN,NNN.NN` strings.
5. Pagination / sorting / search on the dashboard. Mockup shows pagination controls ("1–10 of 76") — see §2. No sort affordance in mockup column headers.
6. **Dashboard sort order.** Spec is silent on default sort. Mockup has an `Updated` column but no sort controls. Decomposition assumes in-flight at the top, then `uploadedAt DESC`.
7. **Authentication / user identity.** Spec contains no mention of users, login, or roles. Deliberately out of scope per `01-problem-space.md` Non-goals, but still a genuine spec gap — noted here for traceability.
8. **Reject-undo semantics.** Spec says Rejected is terminal but doesn't explicitly rule out a future "reopen" path. Not implemented.
9. Document deletion. Not mentioned; assume not required.
10. Error handling when Classify or Extract fails. Not mentioned; needs a decision.
11. File format support. Spec says "PDF or image" without enumerating image formats.
12. Flag modal constraints (comment length, character set).
13. Dashboard filter behavior when no documents match — presumably an empty state.
14. Concurrent-edit semantics. Presumably out of scope given no auth.
15. Whether classification can return "unknown" / "other".

### 1.6 Cross-cutting requirements (explicit)

- Must start via `docker-compose up`.
- Classification and extraction must use a real external LLM API (Anthropic chosen).
- Sample documents are provided under `problem-statement/samples/`.
- Any tech stack is acceptable; reference stack is Java 25 / Spring Boot 4 / React+TS / Postgres.
- Multi-client-from-day-one; data-driven architecture expected.

### 1.7 Cross-client commonalities

- Every client has an **Invoice** (different field sets) and an **Expense Report** (different field sets).
- Every workflow starts with Upload → Classify → Extract → Review.
- Every workflow ends at Filed (success) or Rejected (reject-from-Review).
- Every approval stage has the same UX pattern: read-only data, Approve / Flag-with-comment.
- The Review stage is always the only stage with an editable form.
- Only one conditional branch in the entire spec: Ironworks Lien Waiver `unconditional` skips PM Approval.

---

## 2. Mockup inventory

13 HTML files in `problem-statement/mockups/`. Styling is plain CSS inlined in `<style>` blocks — no framework, no build step. A rough color/state vocabulary is reused across files.

### 2.1 Screen catalogue

| Filename | Screen | Layout |
|---|---|---|
| `00-organization-picker.html` | Organization Picker | Centered logo + 3-card grid of orgs |
| `01-dashboard-pinnacle.html`, `01-dashboard-riverside.html` | Dashboard | Top dark navbar + filters + 4 stat cards + document table + pagination |
| `02-review-pinnacle.html` | Review — Pinnacle Invoice | Two-panel: PDF left (flex), form right (420px) |
| `02-review-flagged-pinnacle.html` | Review — flagged | Same as above + flag-banner with origin-stage note; Resolve replaces Approve |
| `02-review-reclassify-pinnacle.html` | Review — reclassify modal | Same as above + modal overlay ("Re-extract as …" / "Keep as …") |
| `02-review-riverside.html` | Review — Riverside Receipt | Two-panel; short workflow (5 stages) |
| `03-approval-pinnacle.html` | Approval — Partner (Pinnacle Retainer) | Two-panel; read-only form; Flag + Approve |
| `03-approval-flag-modal-pinnacle.html` | Approval — flag modal | Modal overlay with required textarea; Cancel / Send to Review |
| `03-approval-ironworks.html` | Approval — Ironworks Invoice | Two-panel; read-only with materials table |
| `04-filed-ironworks.html` | Filed — Change Order | Two-panel; all stages green; single "Back to Documents" |
| `04-filed-riverside.html` | Filed — Receipt | Two-panel; short workflow; all green |
| `04-rejected-pinnacle.html` | Rejected — Invoice | Two-panel; 4 stages green + Rejected stage red |

### 2.1a Dashboard stats row (explicit)

The four stat cards on the dashboard mockups carry specific labels — not just "some counts":

1. **In Progress** — documents currently in `Classify` or `Extract`.
2. **Awaiting Review** — documents in `Review` stage.
3. **Flagged** — documents in `Review` with a non-null `workflowOriginStage` (i.e., flagged back from an approval).
4. **Filed This Month** — documents in `Filed` whose `stageAt(Filed)` timestamp falls in the current calendar month.

"Filed This Month" is the only stat that requires a time-scoped query. Decomposition must account for this.

### 2.2 Design-system vocabulary

- **Primary layout** for detail views: left PDF viewer (flex, dark background `#2a2a2e`), right form panel (420px, light).
- **Stage progress indicator:** horizontal dot-and-line; colors by state — green (done), purple/pink (current approval), amber (regressed after flag), red (rejected), gray (upcoming).
- **Badges:** stage (review=purple, approval=pink, filed=green, rejected=red, processing=gray, flagged=rose), type (neutral gray).
- **Processing rows** on the dashboard: reduced opacity (0.55), inline spinner, `Classifying` / `Extracting` badges, dashed separator.
- **Flag banner** on Review (flagged state): amber/orange background, icon, title, "sent back from <stage>" subtitle, embedded comment box.
- **Reclassify alert:** amber-highlighted category box + modal overlay with "Keep as …" / "Re-extract as …" options.
- **Approval flag modal:** amber primary, required textarea with hint "What needs to be addressed?", Cancel / Send to Review.
- **Terminal views (Filed, Rejected):** read-only form, single "Back to Documents" button, stage progress fully-colored (green all / red at Rejected).

### 2.3 UI component atoms implied

Inferred from the screens above, for decomposition:

- `OrgPickerCard`
- `AppTopbar` (with org indicator + switch affordance)
- `DashboardFilterBar` (Stage dropdown, Type dropdown, Upload Document button)
- `StatsCard` (title + count)
- `DocumentTableRow` (with processing-variant)
- `Pagination`
- `StageProgress` (data-driven by workflow config)
- `StageBadge`, `TypeBadge`, `FlagIndicator`
- `DocumentDetailLayout` (two-panel)
- `PdfViewer`
- `DocumentHeader` (doc id, metadata, badges)
- `ReviewForm` (dynamic fields by doc-type schema)
- `ReadonlySummary` (same data, no inputs)
- `ActionBar` (context-specific buttons)
- `FlagBanner`
- `ReclassifyAlertModal`
- `FlagModal`
- `BackToDocumentsButton`

### 2.4 Behaviors implied

- Dashboard filter options (stages, types) are scoped to the selected org.
- Processing rows float to the top of the list.
- Uploading is initiated from the dashboard (`Upload Document` button) — the spec confirms this.
- Flag from an approval opens a modal requiring a comment.
- Re-classify opens a modal requiring explicit confirmation because re-extraction is destructive.
- Stage-progress colors encode state; stage count varies by workflow (5–7 stages seen).

---

## 3. Sample document corpus

### 3.1 Character

Uniformly **clean digital PDFs, single page**. No scans, no OCR noise, no multi-column layouts, no handwriting. Templates are consistent within a (client, doc-type) bucket. Test data is whimsical ("Concrete Jungle Suppliers", "Absolutely Legitimate Court Reporting Services", "Comically Large Spoon Warehouse") but structurally real — every sample is a well-formed business document.

### 3.2 Hardest cases (from recon)

Targets the eval holdout set should cover:

1. **Pinnacle Retainer Agreement `scope`** — multi-paragraph narrative prose; LLM must extract an unstructured field from legal preamble. Client name is also embedded in the preamble ("by and between Pinnacle Legal Group … and the estate of Reginald P. Bigglesworth, acting through its executor Fuzzy Paws Inc.") — requires semantic parsing.
2. **Ironworks Change Order `description`** — multi-paragraph technical narrative about site conditions and remediation.
3. **Riverside Receipt `category`** — **not labeled** on the document; must be inferred from item descriptions ("Replacement tablecloths" → supplies vs. equipment is ambiguous).
4. **Pinnacle Invoice `lineItems`** — variable rate types (per-page, per-hour, flat fee with no rate shown). LLM must handle missing/mixed `unitPrice` semantics.
5. **Ironworks Lien Waiver `waiverType`** — the *word* `Unconditional` is explicit on the waiver samples, but the distinguishing legal language ("This waiver is not conditioned upon receipt of payment" vs. "Upon receipt of payment") is subtle and worth covering.

### 3.3 Nested-data patterns

- Invoices: `lineItems` / `materials` with `{description, quantity, unitPrice, total}` (restaurant/legal) or `{item, quantity, unitCost}` (construction).
- Expense reports: `items` with a category or billable flag per row.
- Change orders and retainers: **no nested data**, just fields.
- Lien waivers: **no nested data**.
- Implication: extraction prompts likely split into "top-level fields" + "nested table" sub-tasks for the doc-types that have nested data.

### 3.4 Corpus size

**23 PDFs total** (not 24 — original count was off by one):

```
samples/
  riverside-bistro/      invoices:3  receipts:3  expense-reports:2    (8)
  pinnacle-legal/        invoices:3  retainer-agreements:2  expense-reports:2    (7)
  ironworks-construction/invoices:3  change-orders:2  lien-waivers:3    (8)
```

Given the goal in `01-problem-space.md` to auto-seed ~half the corpus: roughly 11 PDFs seeded, 12 available to upload. The tune/verify split for eval is orthogonal; both need to be decided in research.

---

## 4. Tech stack reality

All facts here are verified as of 2026-04-24. Citations included.

### 4.1 Java 25

- **GA 2025-09-16**, LTS (next after 21). Oracle NFTC support through at least Sep 2028, OTN through Sep 2033. ([Oracle announcement](https://www.oracle.com/news/announcement/oracle-releases-java-25-2025-09-16/), [OpenJDK JDK 25](https://openjdk.org/projects/jdk/25/))
- **Docker base images:** `eclipse-temurin:25-jdk` for build, `eclipse-temurin:25-jre` for runtime. Pin exact tag; never `:latest`.
- **New language features** (relevant because older tooling may choke): scoped values, module imports, compact source files.

### 4.2 Spring Boot 4.0

- **GA 2025-11-20**, built on Spring Framework 7. Minimum Java 17, but Java 25 is first-class. ([Spring blog](https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/), [Migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide))
- **Breaking changes that matter even for greenfield:**
  - Targets Jakarta EE 11 / Servlet 6.1 — `jakarta.*` imports, not `javax.*`.
  - Default JSON is **Jackson 3** — `tools.jackson.*`, not `com.fasterxml.jackson.*`.
  - Auto-configuration is **modularized** — must use `spring-boot-starter-flyway`, `spring-boot-starter-liquibase`, etc., not bare third-party deps.
- **Gradle:** 9.x recommended (min 8.14 on the 8 line).
- **Spring Data JPA / Web / Security and the Postgres driver** are all fully updated for the 4.0 train. No separate "Spring Data PostgreSQL" — use JPA or JDBC with `org.postgresql:postgresql`.

### 4.3 Quality-gate tooling

| Tool | Java 25 support | Notes |
|---|---|---|
| **Spotless** (Gradle plugin) | Yes | 8.4.0 is current; pin `google-java-format` ≥ 1.28 |
| **Checkstyle** | Recent 10.x | Java 25 grammar support: **unverified** at exact minor; test on pick |
| **SpotBugs** | Unverified (likely lagging) | Fallback: **Error Prone** if SpotBugs doesn't handle Java 25 bytecode |
| **JaCoCo** | Yes (0.8.14+) | [issue #1933](https://github.com/jacoco/jacoco/issues/1933) |
| **PMD** | Yes (7.16.0+, 7.22.0 latest 2026-02) | [PMD 7.16 release](https://pmd.github.io/2025/07/25/PMD-7.16.0/) |

Implication: default on Spotless + Checkstyle + JaCoCo + PMD. SpotBugs is aspirational; Error Prone is the safe alternative for static analysis on Java 25.

### 4.4 Anthropic Java SDK

- **Official SDK** on Maven Central as `com.anthropic:anthropic-java`, MIT-licensed. Latest observed ~2.26.0 (pin live at project start). ([GitHub](https://github.com/anthropics/anthropic-sdk-java), [Maven Central](https://central.sonatype.com/artifact/com.anthropic/anthropic-java))
- Supports sync + async clients, streaming, retries, tool use, and **document/PDF content blocks** natively (base64 or Files-API `file_id`).
- **Model pinned: `claude-sonnet-4-6`** for both classification and extraction. Quality-oriented default that handles text, vision, and PDF input. The model ID is a constant in the typed config object (see `01-problem-space.md` Goal 14 / C7-R13) so it is not scattered across the codebase and is easy to swap during the eval-tuning pass if accuracy demands a switch to `claude-haiku-4-5` (cheaper) or `claude-opus-4-7` (heavier reasoning).

### 4.5 LLM pipeline shape (committed)

- **Two-call pipeline per document:** (1) classify to pick the doc-type from the current org's allowed types, (2) extract using the schema for that type. On reclassification in Review, only extract re-runs.
- **Structured output via tool use:** define a tool per `(client, docType)` whose JSON input schema mirrors the spec's field list (§1.1). Force `tool_choice: { type: "tool", name: <tool> }`; read `tool_use.input` as the structured payload. No free-text JSON parsing.
- **Raw text extracted and persisted:** on upload, the backend extracts text from the PDF (candidate library: **Apache PDFBox**, standard on the JVM, handles the clean digital PDFs in the sample corpus trivially) and stores it on the document record. Storage is unconditional on whether the text is the input to the LLM — it's kept for UI preview, debugging, and future search value.

### 4.6 LLM pipeline shape (open — research questions)

These are called out in `01-problem-space.md` and will be answered in Pass 4:

- **Input modality to Claude.** Three candidates: (a) text-only (extracted by PDFBox, sent as `text` content blocks), (b) native PDF (`document` content block, base64 or Files-API `file_id`), (c) hybrid (e.g., text for classify, PDF for extract). Tradeoff axis is cost/latency vs. spatial-structure fidelity; spatial fidelity matters most for tabular nested fields (`lineItems`, `materials`). The eval runs both modalities on the tune set to pick.
- **Prompt management / versioning.** Storage location (code constants, versioned resource files, DB rows), versioning scheme (semver filename, content hash, explicit version column), audit granularity (which prompt was used for which doc vs. per-prompt score-over-time), and how the eval harness targets a named prompt version for A/B comparison.


### 4.7 React + TypeScript + Vite

- **React 19** (stable since 2024-12-05), TypeScript 5.x current, **Vite 8** (primary), Vite 7.3/6.4 still maintained. ([React 19](https://react.dev/blog/2024/12/05/react-19), [Vite releases](https://vite.dev/releases))
- **Native `EventSource` is sufficient for SSE** from a Spring Boot `SseEmitter` endpoint. Only reach for `fetch`-based streaming if we need custom auth headers — n/a for this build since there's no auth.

### 4.8 PostgreSQL

- Current stable majors: **17** and **18** (18.3 latest as of 2026-02-26). Both fully supported. Either is fine; pick 17 for stability or 18 for newest. ([versioning policy](https://www.postgresql.org/support/versioning/))
- Hibernate 7 ships with Boot 4; use version-less `PostgreSQLDialect`, let it auto-detect. JSON/JSONB mapping cleanest via `@JdbcTypeCode(SqlTypes.JSON)`.
- Watch serialization changes because Jackson 3 is in Boot 4.

### 4.9 Flyway vs Liquibase

- Both have first-class Boot 4 autoconfig. Spring Boot picks whichever starter is on the classpath.
- For a small project, **Flyway is simpler** — plain SQL files under `db/migration`, `V1__init.sql` naming, no XML. Community default for Spring Boot.

---

## 5. Constraints imposed on later passes

Derived from the inputs above.

### 5.1 From the spec (unchangeable)

- Workflows and field schemas must match §1.1 and §1.2 exactly. These are test-verifiable.
- The Lien Waiver `unconditional` shortcut is the only non-linear transition in the spec.
- Flag must track origin stage; Resolve must return there unless type changed.
- Classification and extraction must be real LLM calls.
- Entire app must start via `docker-compose up`.

### 5.2 From the chosen stack

- Jakarta namespaces (`jakarta.*`), Jackson 3 (`tools.jackson.*`) — watch for tutorials / snippets that use the old names.
- Gradle 9, Spring Boot starters (modularized), Flyway (simpler choice).
- SpotBugs is a risk; plan Error Prone as alternative if SpotBugs fails on Java 25.
- Anthropic SDK supports PDF content blocks natively; no need to convert PDFs to images or extract text ourselves before sending.

### 5.3 From the mockups

- Two-panel detail layout is the visual contract.
- Stage progress must render dynamically based on the workflow config (stage count varies 5–7).
- Reclassify modal and flag modal are explicit UI contracts (confirm-before-destructive-action).
- Dashboard must show a "processing" row state distinct from interactive rows.

### 5.4 From the sample corpus

- Classification prompt needs to handle whimsical-but-well-formed documents (this is actually good — they test semantic robustness without OCR noise).
- Extraction prompts benefit from splitting into "scalar fields" + "nested table" sub-tasks where nested data exists.
- The eval holdout should include the hard cases from §3.2.

---

## 6. Conventions to follow

Already captured in `AGENTS.md`:

- "Done means green": build + fast tests + lint/format/type pass before declaring done.
- No `@SuppressWarnings` / `eslint-disable` without a one-line justification.
- Default to no comments; only when the *why* is non-obvious.
- Schema normalized to 3NF; FKs on every reference; migrations versioned (Flyway).
- Code conventions: match surrounding style; no speculative abstractions.

No pre-existing codebase style to inherit — conventions will be set by the initial scaffold and enforced by the build.

---

## 7. Git history

Empty repo. Initial commits include kerf scaffolding and problem-statement material only. Nothing to preserve; nothing to be wary of.

---

## 8. Open items carried into decomposition / research

From `01-problem-space.md` "Deferred — to be specified in later passes":

- **System architecture.** Module boundaries, layering, workflow engine as a bounded context, async mechanism (in-process `@Async` + SSE vs. queue + worker).
- **Workflow configuration format.** YAML vs. DOT vs. other; conditional transitions (Lien Waiver); load-time validation; hot-reload?
- **Frontend testing strategy.** Vitest + RTL vs. Jest; component-vs-E2E boundary; coverage bar; optional agent-driven exploratory click-through.
- **LLM eval strategy.** Metrics, tune/verify split file, reproducibility, recorded vs. live, markdown report format.
- **LLM input modality.** Text vs. native PDF vs. hybrid; driven by eval on the tune set.
- **Prompt management / versioning.** Where prompts live, how they're versioned, how per-document audit links back to "which prompt was used," how the eval targets a named version.

These are the open problems decomposition needs to carve into components, and research needs to resolve.
