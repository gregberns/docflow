# Integration — `evals`

How the five components connect, the order in which the implementation agent works through them, and the cross-cutting concerns that no single component spec owns.

---

## 1. Component connection map

```
[C-EVAL-1: PDFBox validation] (done)
    │
    └──► informs: PDFBox is viable for scenario tests; no fakes needed
    │
[C-EVAL-2: Live-LLM harness] (done)
    │
    └──► referenced by: testing-strategy doc as the live-API end-to-end example
    │
[C-EVAL-3: inputModality removal] ◄────── independent ──────►  [C-EVAL-4: Scenario framework]
    │                                                                 │
    │                                                                 └──► writes spec edits to:
    │                                                                       c3-pipeline-spec.md (R12, R17, R18)
    │                                                                       c4-workflow-spec.md (R14)
    │                                                                       c5-api-spec.md     (§6 wording, R10)
    │                                                                       c7-platform-spec.md (R15)
    │
    └──► writes spec edits to: c1-config-spec.md (multiple lines), c3-pipeline-spec.md §3.5
    │
    └──► writes code/schema/yaml deletions enumerated in c-eval-3 spec
    │
[C-EVAL-5: Testing-strategy doc]
    │
    └──► consumed later by: df-9c2.12 README ticket (open)
```

C-EVAL-3 and C-EVAL-4 are technically independent — neither's code touches the other's. The recommended ordering (C-EVAL-3 first) is hygiene only: it avoids C-EVAL-4 having to ship YAML test seeds that still carry the dead field.

---

## 2. Integration order (for the implementation agent)

The recommended ticket order:

1. **C-EVAL-3 — `inputModality` removal.** Lands first. Cleans the spec substrate, schema, and code so the scenario tests don't have to reference dead concepts.
2. **C-EVAL-4 — scenario framework.** Lands second. Ships the framework code, all 12 scenario fixtures, and the four spec edits.
3. **C-EVAL-5 — testing-strategy doc.** Lands third (or alongside C-EVAL-4). The doc references the scenario suite as a real, runnable thing.
4. **C7.12 README** (separate ticket, open today as `df-9c2.12`). Consumes the testing-strategy doc when the README ticket is worked.

C-EVAL-1 and C-EVAL-2 are pre-existing artifacts; no integration work.

---

## 3. Beads ticket shape recommendation

Decompose into 1–2-hour tickets where possible. Suggested decomposition (the user creates beads tickets; this is shape only, not creation):

- C-EVAL-3 (1 ticket; ~2h): "Remove inputModality field from spec, schema, code, YAML, tests."
- C-EVAL-4: 4–5 tickets:
  - "Scenario harness — base wiring (AbstractScenarioIT, ScenarioStubConfig, ScenarioContext, fixture loader + loader unit tests)" (~3h)
  - "Scenario fixture schema + ScenarioRunnerIT skeleton + scenarios 01, 02, 03" (~2h)
  - "Scenarios 04, 09, 10, 11 (retype paths + origin restoration)" (~2h)
  - "Scenarios 06, 07, 08, 12 (corrupt PDF, lien-waiver guard, terminal-state action)" (~2h)
  - "Scenario 05 (concurrent uploads + SSE assertion)" (~2h)
  - "Spec edits — c3, c4, c5, c7 R-tags and prose" (~30 min)
- C-EVAL-5 (1 ticket; ~30 min): "Write testing-strategy.md substrate doc."

---

## 4. Shared state across components

- **`problem-statement/samples/`** — read by both C-EVAL-1 (already, done) and C-EVAL-4 (scenario fixtures point at PDFs in this tree). Read-only by project convention; nothing in this work writes to it.
- **`backend/src/main/resources/db/migration/V1__init.sql`** — edited by C-EVAL-3. C-EVAL-4 depends on it (Flyway runs the same migration when scenario tests boot). Edits to V1 must land before any scenario test boots; that ordering is naturally enforced because both go through `make test`.
- **`backend/src/main/resources/seed/doc-types/*.yaml`** — edited by C-EVAL-3. C-EVAL-4's scenario tests load these via `seed-on-boot=true`. Edits land before scenario tests; if both ship in the same PR, the test-seed YAML edits land in the same commit as the loader / record edits to keep the build green at every commit.
- **`.kerf/project/docflow/05-specs/`** — both C-EVAL-3 and C-EVAL-4 edit files here. No file overlap (C-EVAL-3 edits c1 + c3 §3.5; C-EVAL-4 edits c3 verification cell + c4 + c5 + c7). The two changes can be made independently in any order without merge conflicts.
- **`eval/`** — already populated by C-EVAL-1 (`pdfbox-check/`) and C-EVAL-2 (`harness/`). New contents (`reports/<timestamp>.md`) are produced at run time, not authored.

---

## 5. Cross-cutting concerns

### 5.1 Build-green discipline

`./gradlew check` must pass at every commit. The CLAUDE.md "done means green" rule applies.

For C-EVAL-3, this means the production code edits (`DocumentTypeEntity`, `DocTypeDefinition`, `MessageContentBuilder`, `LlmClassifier`, `ExtractRequestBuilder`, etc.), the test edits (`ConfigValidatorTest`, four LLM-class unit tests, four IT tests with deletions), the schema edit (V1 + fragment), and the YAML/SQL fixture edits all land in one commit. Splitting across commits introduces a window where Hibernate's `@Column` mismatches the schema or the loader fails on an unknown property.

For C-EVAL-4, the scenario suite is purely additive. Each ticket can land in its own commit; the harness scaffolding ticket lands first (with no scenarios), then scenarios accrue.

### 5.2 Audit-row invariant (C3-R5a)

`LlmCallAudit` rows must be written for every classify and every extract call (per `c3-pipeline-spec.md` §3.7–§3.8). The scenario stubs (`ScenarioLlmClassifierStub`, `ScenarioLlmExtractorStub`) honor this: they call `LlmCallAuditWriter.insert(...)` exactly the way the production classes do. Otherwise the scenario suite would diverge from production at the data layer, defeating the point of full-stack integration testing.

The audit-table-row count assertion (C-EVAL-4 acceptance criterion 4) verifies this end-to-end.

### 5.3 Profile isolation (`scenario`)

The `scenario` profile is the only place `LlmClassifier` and `LlmExtractor` are stubbed. The profile is activated only by `AbstractScenarioIT`. Production boots without the profile and uses the real `@Component` classes. `HappyPathSmokeTest` does **not** activate the profile — it intentionally hits the live API.

### 5.4 Spec-edit consistency

Both C-EVAL-3 and C-EVAL-4 touch `c3-pipeline-spec.md`. Distinct sections:
- C-EVAL-3 edits §3.5 (input modality) and the research-summary §1 prose at L37.
- C-EVAL-4 edits the C3-R12 verification cell at L22 and adds C3-R17 / C3-R18 rows.

No file conflict. If both PRs are open simultaneously, the C-EVAL-3 PR rebases on top of C-EVAL-4's R-tag insertions or vice versa — resolution is mechanical.

### 5.5 Stop hook

The `.claude/settings.json` Stop hook runs `make test`. The scenario suite must complete deterministically inside the 600-second hook timeout. C-EVAL-4 acceptance criterion 7 (full suite under 5 minutes) exists for this reason.

### 5.6 CI

GitHub Actions invokes `make test` per C7-R10. After C-EVAL-4 lands, the scenario suite runs in CI on every PR. No `ANTHROPIC_API_KEY` is provided to CI; the suite passes without one.

`make eval` is not in CI — `evalRun` is on-demand only. The Python harness is not in CI — also on-demand.

---

## 6. Cross-component error handling

There is no runtime error propagation across these components — they do not share runtime code paths.

At authoring time:
- If C-EVAL-3 lands but mismatches between the YAML files and the loader record cause a startup failure, the `OrgConfigSeederIT` test fails first. This catches the mismatch before merge.
- If C-EVAL-4 lands and a fixture references a missing PDF, the scenario test fails at fixture-load time with a clear message naming the missing path.
- If C-EVAL-4 stubs misroute a `rawText` to the wrong fixture, the stub throws `IllegalStateException` with the rawText length and the org id — visible in test output, not silent.

---

## 7. Integration testing strategy

The scenario suite (C-EVAL-4) **is** the integration testing strategy for DocFlow as a whole. Pre-existing integration coverage (`HappyPathSmokeTest` for live API, `RetypeFlowIT` for retype-listener wiring) does not change. Existing fragment-level ITs (`OrgConfigPersistenceFragmentIT`, etc.) continue to validate per-fragment schema invariants.

After this work lands, DocFlow's integration test inventory:

| Test | Stack | LLM | Speed | When |
|---|---|---|---|---|
| Per-component unit tests | none | n/a | <1m | every `make test` |
| Fragment-level ITs (existing) | Postgres only | n/a | seconds | every `make test` |
| `RetypeFlowIT` (existing) | curated Spring + Postgres | mocked LlmExtractor | seconds | every `make test` |
| **Scenario suite (new — 12 tests)** | **full Spring + Postgres + PDFBox** | **stubbed seam** | **2–5 min** | **every `make test`** |
| `HappyPathSmokeTest` (existing, edited prose only) | full Spring + Postgres | live API | 1–2 min | `make test` if `ANTHROPIC_API_KEY` set |
| `evalRun` | none (in-process) | live API | 2–5 min | on demand (`make eval`) |
| Python harness | running stack | live API | 5–10 min | on demand (manual) |

The four-layer separation in the testing-strategy doc (C-EVAL-5) describes this inventory verbatim.

---

## 8. Review against `01-problem-space.md` success criteria

| Criterion | Component | Status |
|---|---|---|
| 1. PDFBox verdict produced | C-EVAL-1 | met |
| 2. Live-API end-to-end harness exists | C-EVAL-2 | met |
| 3. `inputModality` removal plan enumerates every file | C-EVAL-3 spec | met (see file table) |
| 4. Scenario framework spec defines stub seam, fixture YAML, profile, loader, 12 scenarios | C-EVAL-4 spec | met |
| 5. Beads-ready ticket descriptions exist for (3) and (4) | tasks pass (NOT THIS PASS) | deferred — explicit instruction was to stop at integration |
| 6. Spec edit list is precise | C-EVAL-3 and C-EVAL-4 specs | met (file-level + line-level entries) |
| 7. Testing-strategy doc differentiates the four layers | C-EVAL-5 spec + `testing-strategy.md` | met |

---

## 9. What this kerf work explicitly does not produce

- Beads tickets. Created by the user as the next step.
- Code changes. Implementation handed off via beads.
- New eval scoring metrics. Out of scope per `01-problem-space.md` non-goals.
