<!-- PP-TRIAL:v2 2026-04-28 implementation -->
# Session Handoff — implementation phase

**Status:** clean. Branch `implementation` at `d69c43e`. `make test` GREEN. **8 tasks landed this session** in three orchestrated parallel batches: `cdf606c` C4.2, `af4cce3` C4.4, `7fdad15` C3.5, `4d5861b` C3.6, `7db7930` C5.4, `176068e` C5.5, `e0db3c6` C2.5, `d69c43e` C3.7.

**What we're doing.** DocFlow take-home, multi-tenant doc processing. Working through kerf plan + beads (`br`). C1 done end-to-end; C2 has upload + integration test; C3 has prompt config + tool schema + Anthropic client + classifier; C4 has the Document/WorkflowInstance writer + TransitionResolver; C5 has org/upload/document/dashboard controllers.

**Next step.** Run `br ready`. **6 open tasks** unblocked, biggest two are P2:
- **C3.8** (`df-2zl.8`) — LlmExtractor (initial-pipeline + retype + retry). 16h. `com.docflow.c3.llm`.
- **C7.9** (`df-9c2.9`) — SeedDataLoader + SeedManifestTest. 16h. `com.docflow.platform`.
- Plus: C4.5 WorkflowEngine, C5.6 Action/Review controllers, C3.9 pipeline orchestrator, P3 chore.

Both C3.8 and C7.9 were attempted this session but their agents auto-backgrounded and stomped the main worktree (see heads-up). They were rolled back. Re-dispatch with corrected briefs.

**Heads-up — important.** Stop hook runs `make test` after every turn (timeout 600s). When orchestrating parallel sub-agents with `isolation: "worktree"`, **never embed `/Users/gb/github/basata/...` absolute paths in the brief** — auto-backgrounded agents take them literally and write to the main repo, breaking the build. Use repo-relative paths and a `pwd` preflight. Detail saved at `~/.claude/projects/-Users-gb-github-basata/memory/feedback_worktree_relative_paths.md` — read it. Cap parallel batch size at ~3 to keep agents in foreground.

**Files to open first.** For C3.8: `backend/src/main/java/com/docflow/c3/llm/LlmClassifier.java` (sibling, mirror its try/finally audit pattern), `LlmCallExecutor.java`, `MessageContentBuilder.java`. For C7.9: `backend/src/main/resources/seed/manifest.yaml`, `WorkflowInstanceWriter.java` (needs an `insert(WorkflowInstance)` method added — interface + JDBC impl).

**Toolchain (unchanged).** `export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"; export JAVA_HOME="/opt/homebrew/opt/openjdk"`. Build from `backend/`. Spring Boot 4 quirks: `tools.jackson.*`, `org.springframework.boot.persistence.autoconfigure.EntityScan`, `MockMvcBuilders.webAppContextSetup`. `organizationId` is a String slug; surrogate IDs are UUID. `@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")` precedent for per-row construction loops. Anthropic SDK 2.10.0 added (still uses Jackson 2 internally — `JsonValue.convert(Map.class)` requires `@SuppressWarnings("unchecked")` with a `why`). C7.4 Flyway baseline IS done (was wrong in the prior handoff).

**Worktrees.** Several locked worktrees under `.claude/worktrees/agent-*` from this session — `agent-a70e1cddbf40425da` has uncommitted C7.9 partial work if you want to salvage; otherwise discard.

**No blocking questions.**
