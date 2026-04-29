<!-- PP-TRIAL:v2 2026-04-29 implementation -->
# Session Handoff — implementation phase

**Status:** clean. Branch `implementation` at `5033407`. `./gradlew build` GREEN. **4 P2 tasks landed this session** in two orchestrated 2-way parallel batches: `5b557ab` C3.8 (LlmExtractor), `c5cb89b` C7.9 (SeedDataLoader), `97ec5af` C3.9 (ProcessingPipelineOrchestrator), `5dceeff` C4.5 (WorkflowEngine.applyAction). Both prior session's stomped-and-rolled-back tasks (C3.8, C7.9) are now done; pipeline now has all three C3 steps wired and C4's engine dispatches actions through TransitionResolver.

**What we're doing.** DocFlow take-home, multi-tenant doc processing. Working through kerf plan + beads (`br`). C1 done end-to-end; C2 has upload + integration test; C3 has prompt config + tool schema + Anthropic client + classifier + extractor + orchestrator + step classes + trigger listener; C4 has Document/WorkflowInstance writers + TransitionResolver + WorkflowEngine.applyAction; C5 has org/upload/document/dashboard controllers; C7 has seed loader + 12 seeded docs.

**Next step.** Run `br ready`. **8 P2 tasks** unblocked plus a P3 chore. Two epics in the list are placeholders (ignore). Highest-leverage targets:

- **df-6aj** (P2 bug, **filed this session**) — `WorkflowEngine` retype path double-marks `reextractionStatus=IN_PROGRESS` and would always throw against the live `LlmExtractor` concurrency guard. Currently masked because C4.5 unit test mocks the extractor. **Fix this BEFORE C4.7 lands** (the retype IT will surface it). See bug body for design options — recommend either making the engine NOT pre-mark and letting the extractor own the lifecycle write, OR adding an internal extractor entry point that skips the guard for engine-driven retype. **This is design work, not parallel-able.**
- **C4.6** (`df-ln9.6`) — `ProcessingCompletedListener` (C3 → C4 handoff). 16h.
- **C4.7** (`df-ln9.7`) — `ExtractionEventListener` (retype completion). 16h. **Blocked in spirit by df-6aj** — fix the bug first or this listener won't have a working retype to listen to.
- **C5.6** (`df-eiu.6`) — `DocumentActionController` + `ReviewController`. 16h.
- **C4.8** (`df-ln9.8`) — jqwik property-test suite. 16h.
- **C3.10** (`df-2zl.10`) — Live-API smoke test (PipelineSmokeIT). On-demand, requires `ANTHROPIC_API_KEY`.
- **C3.11** (`df-2zl.11`) — Eval harness + `evalRun` Gradle task. On-demand. Modifies `build.gradle.kts`.

**Heads-up — don't repeat last session's mistake.** Stop hook runs `make test` after every turn. When orchestrating parallel sub-agents with `isolation: "worktree"`, **never embed `/Users/gb/github/basata/...` absolute paths in the brief** — auto-backgrounded agents take them literally and stomp the main repo. **Use repo-relative paths and a `pwd` preflight in every brief.** Memory: `~/.claude/projects/-Users-gb-github-basata/memory/feedback_worktree_relative_paths.md`. **2-agent batches stayed foreground reliably this session** (4/4 agents foreground, all green). 3-way is the cap; cap at 2 if briefs are heavy (16h tasks each).

**One brief-template note worth tracking:** the C7.7 event record skeletons (`StoredDocumentIngested`, `ProcessingStepChanged`, `ProcessingCompleted`, `ExtractionCompleted`, `ExtractionFailed`, `DocumentStateChanged`) are **already concrete** — earlier sessions filled them in. Future briefs that say "fill in skeleton" should instead say "verify shape matches AC; modify if needed."

**Files to open first.**
- For df-6aj: `backend/src/main/java/com/docflow/workflow/WorkflowEngine.java` (`handleResolveWithTypeChange`) and `backend/src/main/java/com/docflow/c3/llm/LlmExtractor.java` (the `extract(documentId, newDocTypeId)` retype surface, line ~85 has the IN_PROGRESS check).
- For C4.6: existing event listeners (look for `@EventListener` in `com.docflow.workflow.*`) and `ProcessingCompleted` event under `com.docflow.c3.events`.
- For C4.7: `LlmExtractor.extract` retype path's `ExtractionCompleted`/`ExtractionFailed` events; the listener updates `WorkflowInstance` based on retype outcome.
- For C5.6: `backend/src/main/java/com/docflow/c5/...` for existing controller patterns; `WorkflowEngine` becomes the controller's collaborator. The C5 controllers will need the engine wired — note that C4.5 deliberately did NOT mark `WorkflowEngine` `@Component` because `JdbcDocumentReader` / `JdbcWorkflowInstanceReader` impls don't exist yet. C5.6 may need to land those JDBC impls + flip the engine to `@Component`, or wire via `@Bean`.

**Toolchain (unchanged).** `export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"; export JAVA_HOME="/opt/homebrew/opt/openjdk"`. Build from `backend/`. Spring Boot 4 quirks: `tools.jackson.*`, `org.springframework.boot.persistence.autoconfigure.EntityScan`, `MockMvcBuilders.webAppContextSetup`. `organizationId` is a String slug; surrogate IDs are UUID. Anthropic SDK 2.10.0 still uses Jackson 2 internally — `JsonValue.convert(Map.class)` requires `@SuppressWarnings("unchecked")` with a `// why:` comment.

**This session's pipeline additions.** `PipelineStep` interface + `StepResult` sealed (`Success | Failure(message)`) + `PipelineContext` (mutable). Three steps: `TextExtractStep` (PDFBox 3.0.3 via `Loader.loadPDF`), `ClassifyStep` (wraps `LlmClassifier`), `ExtractStep` (wraps `LlmExtractor.extractFields` text-only surface). `ProcessingDocumentService.start(...)` is `@Async`. `PipelineTriggerListener` `@EventListener(StoredDocumentIngested)`. `WorkflowEngine` returns `WorkflowOutcome` sealed sum (`Success(WorkflowInstance)` / `Failure(WorkflowError)` with InvalidAction / ValidationFailed / Unknown / ExtractionInProgress variants). Modality is hard-coded TEXT in `ExtractRequestBuilder` — when a doc-type schema needs PDF modality, that's a separate enhancement.

**Worktrees.** Several locked `.claude/worktrees/agent-*` from this session — all four worked cleanly (no stomping). Discard the leftover dirs at your leisure.

**No blocking questions.**
