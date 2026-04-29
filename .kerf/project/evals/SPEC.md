# `evals` — Consolidated Spec

Single-document reference for the implementation agent. Assembles the problem space, component decomposition, change specs, and integration plan. Beads tickets are created from this document by the user.

## Purpose

Three deliverables, in priority order:

1. **Remove the dead `inputModality = PDF` option** from spec, schema, YAML, JPA entity, view, and the LLM message-builder. The PDF code path exists but is never reached at runtime — `LlmClassifier` and `ExtractRequestBuilder` hardcode `InputModality.TEXT` and pass `null` for PDF bytes.
2. **Build a deterministic scenario-test framework** that boots the full Spring stack with stubbed `LlmClassifier` / `LlmExtractor` seams. 12 scenarios cover happy path, classification edge cases, retype paths, workflow guards, concurrent uploads, and error handling. Runs in `make test`, no API key required.
3. **Document the testing strategy** so the existing eval rigs and the new scenario layer are clearly differentiated. Reusable for the open `df-9c2.12 — C7.12 README` ticket.

## Inputs already in place

- `eval/pdfbox-check/REPORT.md` — verdict: text-only path is viable, all 23 sample PDFs extract cleanly. Justifies (1) and (2).
- `eval/harness/run.py`, `labels.yaml`, `README.md` — Python harness driving the live API end-to-end. Run-pending; complements `./gradlew evalRun` (Java-side, isolated to LLM seam, 12 samples).
- `./gradlew evalRun` (closed today as `df-2zl.11`) — LlmClassifier + LlmExtractor in isolation against a 12-sample manifest, live API, prompt-quality signal.

## Components

| ID | Description | Status |
|---|---|---|
| C-EVAL-1 | PDFBox text-extraction validation | done |
| C-EVAL-2 | Live-LLM end-to-end harness (Python) | done (run pending) |
| C-EVAL-3 | `inputModality` removal | plan only — implementation via beads |
| C-EVAL-4 | Scenario-test framework | plan only — implementation via beads |
| C-EVAL-5 | Testing-strategy documentation | doc drafted in `05-specs/testing-strategy.md` |

## Implementation order

1. C-EVAL-3 (cleans the spec substrate and code)
2. C-EVAL-4 (adds the new test layer)
3. C-EVAL-5 (the testing-strategy doc; reusable for C7.12 README)

C-EVAL-3 and C-EVAL-4 are technically independent (no shared code or files); the ordering is hygiene only.

## C-EVAL-3 — `inputModality` removal

**Scope.** Every reference to `inputModality` / `input_modality` / `InputModality` is removed from production code, JPA, schema, seed YAML, test fixtures, and the spec substrate.

**Files (concrete enumeration, see `05-specs/c-eval-3-input-modality-removal-spec.md` for line-level changes):**

- *Schema:* `backend/src/main/resources/db/migration/V1__init.sql` (drop column + CHECK), `backend/src/main/resources/db/migration/fragments/c1-org-config.sql` (same).
- *Code (delete):* `backend/src/main/java/com/docflow/config/org/InputModality.java`.
- *Code (edit):* `DocTypeDefinition.java`, `DocumentTypeEntity.java`, `DocumentTypeSchemaView.java`, `DocumentTypeCatalogImpl.java`, `OrgConfigSeedWriter.java`, `MessageContentBuilder.java`, `LlmClassifier.java`, `ExtractRequestBuilder.java`.
- *Seed YAML:* 9 production files + 10 test seed files + 1 missing-required fixture file. Drop `inputModality:` line.
- *SQL fixtures:* `processing-completed-listener-seed.sql`, `retype-flow-listener-seed.sql`. Drop `input_modality` column from INSERT.
- *Tests (delete):* four assertion methods named in the spec.
- *Tests (edit):* five test files (`ConfigValidatorTest`, `OrgConfigPersistenceFragmentIT`, `LlmClassifierTest`, `LlmExtractorTest`, `MessageContentBuilderTest`, `LlmCallExecutorTest`).
- *Spec edits:* `c1-config-spec.md` (seven line-level edits), `c3-pipeline-spec.md` (§1 research summary L37, §3.5 L149–L155).

**Key acceptance criterion.** `grep -r "inputModality\|input_modality\|InputModality" backend/src/ backend/src/main/resources/ .kerf/project/docflow/05-specs/c1-config-spec.md .kerf/project/docflow/05-specs/c3-pipeline-spec.md` returns no results. `make test` passes.

## C-EVAL-4 — Scenario-test framework

**Architecture.** Real `Application.class` boot + Testcontainers Postgres + real Flyway + real PDFBox + stubbed `LlmClassifier` + stubbed `LlmExtractor`. Profile name `scenario`. `@TestConfiguration ScenarioStubConfig` declares `@Bean @Primary` overrides. Stubs subclass production classes and consult `ScenarioContext` (mutable singleton) for the active fixture's canned values.

**Fixture YAML schema.** `scenarioId`, `inputPdf` (or `inputs` array for multi-upload), `organizationId`, `classification.{docType | error}`, `extraction.{fields | error}`, optional `actions[]`, `expectedEndState.{document, workflowInstance, events[]}`. Strict YAML parsing — unknown properties fail fixture-load.

**Directory layout.**
- `backend/src/test/java/com/docflow/scenario/` — 11 new Java files.
- `backend/src/test/resources/scenarios/` — 12 YAML fixtures + 1 corrupt-PDF helper.

**The 12 scenarios** (one-paragraph each in `05-specs/c-eval-4-scenario-framework-spec.md` §3):

1. Happy path — Pinnacle invoice classifies, extracts, advances to Filed.
2. Wrong-type classification — stub returns disallowed `docType`; production rejects with `LlmSchemaViolation`; orchestrator marks FAILED.
3. Missing required field — stub returns extraction map missing required key; schema-validation triggers FAILED.
4. Retype `Resolve` with type change → re-extraction completes; `Document` updated.
5. Two concurrent uploads on a single SSE stream — both flow correctly.
6. Corrupt PDF — PDFBox fails; orchestrator marks FAILED.
7. Lien-waiver guard, conditional → Project Manager Approval.
8. Lien-waiver guard, unconditional → Filed.
9. Retype `Resolve` no type change → no-op; `LlmExtractor.extract` not called; origin restored.
10. `ExtractionFailed` after retype — `reextractionStatus = FAILED`, original fields untouched.
11. Flag from Attorney Approval, resolve in Review without type change → returns to Attorney Approval (origin restoration).
12. Approve action on a Filed document → 409 / `INVALID_ACTION`.

**Spec edits.**
- `c3-pipeline-spec.md`: append clause to C3-R12 verification cell; add C3-R17 (scenario tests in `make test`, no key) and C3-R18 (fixture loader strict-mode).
- `c4-workflow-spec.md`: add C4-R14 (workflow scenarios cover the documented set).
- `c5-api-spec.md`: §6 prose update (insert "live-API"); add C5-R10 (concurrent SSE).
- `c7-platform-spec.md`: add C7-R15 (scenario tests in fast gate; excluded from e2e and eval).

**Key acceptance criterion.** `./gradlew check` passes without `ANTHROPIC_API_KEY` in env. `ScenarioRunnerIT` runs all 12 scenarios; each passes. Total wall-clock under 5 minutes.

## C-EVAL-5 — Testing-strategy doc

**Output.** `05-specs/testing-strategy.md` (already drafted in this kerf substrate). Describes the four layers: unit, scenario, live HTTP smoke, eval rigs. Covers scope, run cadence, prerequisites, and a "when to add to which layer" decision table.

**Reusability.** The doc is consumed verbatim (or near-verbatim) by the open `df-9c2.12 — C7.12 README` ticket.

## Cross-cutting concerns

1. **Build-green discipline** — every commit must pass `./gradlew check`. C-EVAL-3 lands as one (or a tightly-coupled set of) commit(s); the production code, schema, YAML, and tests change together.
2. **Audit-row invariant** — scenario stubs call `LlmCallAuditWriter.insert(...)` so `llm_call_audit` rows appear identically whether the system ran live or stubbed.
3. **Profile isolation** — only `AbstractScenarioIT` activates the `scenario` profile. Production and `HappyPathSmokeTest` use the real LLM seams.
4. **CI** — `make test` (run by GitHub Actions) does not provide `ANTHROPIC_API_KEY`. The scenario suite passes without it; `HappyPathSmokeTest` is skipped.
5. **Spec-edit no-conflict** — C-EVAL-3 edits §3.5 of `c3-pipeline-spec.md`; C-EVAL-4 edits the verification cell of C3-R12 and adds new R-tag rows. No file collisions.

## Verification (full)

```
make test
grep -rn "inputModality\|input_modality\|InputModality" backend/src/ backend/src/main/resources/ .kerf/project/docflow/05-specs/c1-config-spec.md .kerf/project/docflow/05-specs/c3-pipeline-spec.md
```

Expected: `make test` exits 0; grep returns no output.

## What this kerf work does not include

- Beads ticket creation (next step, user-driven).
- Implementation of any code (beads-driven).
- Wiring up `inputModality = PDF` (explicitly removed, not implemented).
- Replacing or duplicating `./gradlew evalRun`.
- New eval scoring metrics.

## Pointers to the detailed specs

- `01-problem-space.md` — context, goals, constraints.
- `02-analysis.md` — current state of the affected codebase.
- `03-components.md` — five components, requirements, dependencies, interfaces.
- `04-research/c-eval-{1..5}/findings.md` — research notes per component.
- `05-specs/c-eval-1-pdfbox-validation-spec.md` — done; reference only.
- `05-specs/c-eval-2-live-llm-harness-spec.md` — done; reference only.
- `05-specs/c-eval-3-input-modality-removal-spec.md` — full file-level + line-level removal plan.
- `05-specs/c-eval-4-scenario-framework-spec.md` — full framework spec, fixture schema, 12 scenarios, spec edits.
- `05-specs/c-eval-5-testing-strategy-spec.md` — outline + acceptance for the doc.
- `05-specs/testing-strategy.md` — the doc itself.
- `06-integration.md` — integration order, shared state, cross-cutting concerns.
