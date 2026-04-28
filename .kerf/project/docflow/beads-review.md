# Beads Review — Pass 7 → tracker

After loading the 68 Pass-7 tasks into the `br` (beads) issue tracker, three parallel review agents audited the workspace against `07-tasks.md`. All three returned **accept-with-fixes**, no blocking findings.

## Reviews

### 1. Dep edge audit
**Scope.** Walked every `Within-component deps` and `Cross-component deps` line in `07-tasks.md` and verified presence + direction in beads. Filtered out informational backrefs (e.g., "C7.3 hosts records contributed by C1.6/C2.2/C3.6") that should not be modeled as DAG edges.
**Findings.** 225 truth-edges in doc; 208 present in beads; 12 missing (10 transitively redundant via C6.11 → C6.{1..10}; **2 real gaps**), 1 extra, 1 wrong-direction (defensible), 4 known-dropped reverse-edges verified.
**Real fixes:**
- Add `C6.12 → C1.7` (E2E fixtures need seeded org).
- Add `C6.12 → C7.9` (E2E fixtures need application-data seeder).
- Remove `C7.8 → C7.4` (seed manifest YAML doesn't need V1 assembled).

### 2. Per-task content spot-check
**Scope.** Sampled 14 tasks across all 7 components, spanning all sizes (240/480/960 min) and phases (P0, P2, P3, P5–P9). Verified title format, parent epic, phase + component labels, estimate, and that descriptions preserve substantive Spec refs / Deliverables / AC content.
**Findings.** Title / parent / labels / estimate: 100% pass across the sample. Description content: 12 pass, 2 should-fix, 0 blocking.
**Real fixes:**
- C3.11: restore manifest.yaml shape (`filename → { orgId, docType, extractedFields }`) and the "23 samples" coverage count, both dropped during translation.
- C4.5: restore the explicit `DocumentStateChanged` payload field roster (`documentId, storedDocumentId, organizationId, currentStage, currentStatus, reextractionStatus, action?, comment?, at`); had been compressed to "with documented payload."

### 3. Cycle resolution + completeness
**Scope.** Verified beads contains exactly 75 issues (7 epics + 68 tasks); audited the implementer's decision to drop 4 reverse-edges (`C1.5/C2.3/C3.1/C4.3 → C7.4`) that would have caused a cycle.
**Findings.** Counts perfect; no cycles; no duplicates. The implementer's rationale for dropping the reverse-edges is correct in spirit — fragment authoring is independent of V1 assembly. But three of the four tasks (C2.3, C3.1, C4.3) carry ACs that can only be verified post-V1 (e.g., "AC-R9: flywayInfo shows V1 applied"). Leaving the deps dropped *and* the ACs unchanged means those tasks technically cannot be marked "done" until C7.4 lands — a hidden runtime dep.
**Real fixes (doc-side, not beads):**
- C2.3, C3.1, C4.3: rewrite "Cross-component deps" lines to drop C7.4 as a hard dep and describe the relationship as "fragment stitched by C7.4 (forward edge); V1-ordering hint: ..."
- C1.5, C2.3, C3.1, C4.3: rewrite the post-V1 ACs as "**Post-assembly verification (after C7.4):** ..." so the runtime ordering is explicit while the task DAG remains acyclic.
- C7.4: add a note that it is the post-assembly verification gate for the four fragment tasks.
- C1.9 ↔ C7.6 mutual-reference cycle in the source spec: rewrite C1.9's cross-comp deps line as a backref ("the operative DAG edge is C7.6 → C1.9, declared on C7.6's deps line").

## Applied

### Beads (5 changes)
1. `br dep add df-6m8.12 df-sxq.7` (C6.12 → C1.7).
2. `br dep add df-6m8.12 df-9c2.9` (C6.12 → C7.9).
3. `br dep remove df-9c2.8 df-9c2.4` (C7.8 → C7.4).
4. `br update df-2zl.11 --description ...` (C3.11 manifest shape + 23-sample count).
5. `br update df-ln9.5 --description ...` (C4.5 `DocumentStateChanged` payload roster).

`br dep cycles` confirms the workspace stays acyclic after the edits.

### `07-tasks.md` (6 edits)
1. C1.5 AC: "Post-assembly verification (after C7.4): ..." reword + cross-deps clarified.
2. C2.3 ACs + cross-deps: AC-R9 and AC-R5 marked post-V1; cross-deps dropped C7.4 hard dep, kept descriptive forward reference + V1-ordering hint.
3. C3.1 ACs + cross-deps: AC5 and CHECK assertion marked post-V1; cross-deps dropped C7.4 hard dep, kept descriptive forward reference + V1-ordering hints.
4. C4.3 ACs + cross-deps: index-existence and Flyway-migrate ACs marked post-V1; cross-deps dropped C7.4 hard dep, kept descriptive forward reference + V1-ordering hints.
5. C1.9 cross-deps: reworded as backref so the operative DAG direction (C7.6 → C1.9) is unambiguous.
6. C7.4 cross-deps: added the post-assembly verification gate note for C1.5/C2.3/C3.1/C4.3.

## Intentionally left

- **10 transitively-redundant C6.12 within-comp edges** to C6.{1..10}. Beads collapses to just C6.12 → C6.11. An implementer picking up C6.12 sees C6.11 is incomplete until everything beneath it lands — functionally equivalent, stylistically different.
- **C5.3's deps on C3.1/C4.3** (rather than C7.4 directly). Transitively equivalent since C7.4 → C3.1, C4.3.
- **C1.9 ↔ C7.6 directional choice.** Both directions are textually defensible in the doc; beads picked C7.6 → C1.9, which is correct because the grep Gradle task as authored fails fast at execution time if `config/forbidden-strings.txt` is missing. Doc was reworded to align rather than reversing the edge.

## Final state

- **75 issues:** 7 epics + 68 tasks.
- **214 dependency edges** (after +2 added, −1 removed from initial 212; deps remain `blocks`-typed and acyclic).
- **9 ready entry points** (was 8 before review; +1 from removing the spurious C7.8 → C7.4 dep): `C7.1, C1.1, C2.1, C4.1, C4.3, C7.8, C1.9, C3.1, C5.1`.
- **Doc and beads aligned.** Where the doc's prose described co-ownership with mutual references, the doc was rewritten to call out the operative DAG direction and surface post-V1 verification ACs as such, rather than leaving the implementer to infer it.
