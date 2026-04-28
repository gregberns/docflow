<!-- PP-TRIAL:v2 2026-04-28 implementation -->
# Session Handoff — Implementation phase

**Status:** clean. Pass 7 (Tasks) is finalized. `kerf finalize docflow --branch implementation` succeeded; this branch is the implementation working tree. The planning artifacts are copied into `.kerf/docflow/` (read those, not `.kerf/project/docflow/`, which is the bench-side source). 75 beads issues (7 epics + 68 tasks), 0 cycles, 9 ready entry points.

**What this is.** DocFlow — a multi-tenant document-processing take-home: org picker → per-org dashboard → upload → live classify+extract → workflow stages (Review / Approval / Filed / Rejected) with flag-and-resolve. Three real clients with nine `(client, doc-type)` workflows; everything driven by declarative config so adding a fourth client is data, not code.

**Next step (the user is handing off here).** A new agent coordinates implementation. Standard loop:
1. `br ready` — pick an unblocked task. Current entry points: `C7.1, C1.1, C2.1, C4.1, C4.3, C7.8, C1.9, C3.1, C5.1`.
2. `br update <id> --status=in_progress` to claim, then implement.
3. `br close <id>` when AC are met. **"Done means green"** per `AGENTS.md` — `make test` exit 0 (lint, format, type-check, unit + property + integration tests, coverage threshold).
4. `br sync --flush-only` before each commit so `.beads/issues.jsonl` stays current.

**Files to open first.**
- `.kerf/docflow/SPEC.md` — implementer entry point (curated tour over the planning artifacts).
- `.kerf/docflow/07-tasks.md` — full task list with deliverables / AC / deps / phase ordering.
- `.kerf/docflow/06-integration.md` — seams, init order, transaction boundaries, integration test strategy.
- `.kerf/docflow/05-specs/c{N}-*-spec.md` — per-component spec for whichever task you're on.
- `AGENTS.md` (`CLAUDE.md` symlinks here) — `'Done' means green`, lint/format rules, beads workflow.
- `.kerf/docflow/beads-review.md` — explains why beads and 07-tasks.md briefly diverged then realigned (relevant if you see "Post-assembly verification (after C7.4)" in fragment-task ACs).

**Posture.** Use judgment on routine moves (commits, format choices, subagent fan-out). Reserve asks for substantive moves (architectural reversals, scope additions, opening a PR / pushing the branch). Don't pad README or production-considerations with operational color the user didn't ask for.

**If the implementer hits the V1 co-ownership pattern.** C7.4 stitches the SQL fragments contributed by C1.5/C2.3/C3.1/C4.3. The fragment tasks ship the SQL + entities; their post-V1 verification ACs run only after C7.4 lands (this is documented in their AC bullets as "Post-assembly verification (after C7.4): ..."). Don't try to add reverse deps in beads — it's a cycle.

**If you need to push or open a PR.** The user hasn't authorized either. Ask first.

**Blocking questions.** None.
