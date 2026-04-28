# DocFlow (basata)

Multi-client document processing platform built against the take-home spec in `problem-statement/`.

This file is the canonical agent guide. `CLAUDE.md` is a symlink to this file so any agent harness (Claude Code, Cursor, generic AGENTS.md readers) picks up the same instructions.

## Repository layout

- **`problem-statement/`** — read-only source of truth. Contains the original spec PDF, the extracted markdown (`DocFlow_Take_Home_Exercise_greg_berns.md`), sample documents under `samples/`, and HTML mockups under `mockups/`. Do not modify files in this directory; treat them as inputs.
- **`.kerf/project/`** — kerf work artifacts (problem space, analysis, specs, tasks). Committed. Symlinked from `~/.kerf/projects/basata` so `kerf` commands write directly into the repo.
- **`.env.example`** — documents required environment variables. Copy to `.env` and fill in before running.
- Implementation lives at the repo root (outside `problem-statement/` and `.kerf/`).

## Non-negotiable conventions

These apply to every change, whether made by a human or an agent.

### "Done" means green

Before declaring any implementation task complete:

1. Run the full build (`./gradlew build` or the frontend equivalent).
2. Run the full fast test suite (unit + integration + property-based). Long-running tests (E2E browser tests, full seed-data load tests) are exempt and can be run on demand.
3. Run lint, format-check, and type-check. All must pass.
4. If any of the above fail, the task is **not** done — fix the failure, do not hand off broken work.

A Stop hook in `.claude/settings.json` (added once the build is in place) will enforce this automatically when the agent finishes a turn.

### Lint, format, and type discipline

- **Backend:** Spotless (format), Checkstyle or PMD (lint), SpotBugs (static analysis). Wired into the Gradle/Maven build so `./gradlew build` fails on violations.
- **Frontend:** ESLint (lint), Prettier (format), TypeScript strict mode. Wired into the build + a pre-commit hook.
- No `// eslint-disable` / `@SuppressWarnings` without a one-line comment explaining why.
- The agent adjusts its own output to match project style — it does not suppress rules to get past them.

### Database schema

- PostgreSQL, normalized to at least 3NF for transactional tables.
- Foreign keys on every reference. Indexes on every foreign key and on columns used in WHERE / ORDER BY.
- Schema migrations live under version control (Flyway or Liquibase). Never edit an applied migration — add a new one.
- JSON columns are reserved for extracted-field payloads where the schema is genuinely dynamic per document type. Everything else is relational.

### Code conventions

- Default to writing no comments. Code explains the *what*; a comment exists only when the *why* is non-obvious.
- Prefer editing existing files to creating new ones.
- No speculative abstractions. Don't build for hypothetical future requirements.
- Match surrounding style.

## Planning with kerf

This project uses kerf for structured planning. Before implementing non-trivial
changes (new features, refactors, bug investigations), create a kerf work:

  kerf new <codename>

This creates a work on the bench and shows the process to follow. The jig
(process template) guides you through structured passes — problem space,
decomposition, research, detailed spec, integration, and tasks.

### Key commands

  kerf new <codename>              Create a new work
  kerf show <codename>             See current state + jig instructions for next steps
  kerf status <codename>           Check current status
  kerf status <codename> <status>  Advance to next pass
  kerf shelve <codename>           Save progress when ending a session
  kerf resume <codename>           Pick up where you left off
  kerf square <codename>           Verify the work is complete
  kerf finalize <codename> --branch <name>  Package for implementation

### When to use kerf

- New features or subsystems → kerf new --jig plan (or spec)
- Bug investigations → kerf new --jig bug
- Trivial changes (typos, one-line fixes) → skip kerf, just make the change

### Workflow

1. kerf new <codename> — read the output, it tells you exactly what to do
2. Follow each pass: write the artifacts, advance status
3. kerf show <codename> — if you lose context, this shows where you are
4. kerf shelve / kerf resume — for multi-session work
5. kerf square — verify everything is complete
6. kerf finalize — package into a git branch for implementation

Don't skip the planning process. Measure twice, cut once.

<!-- br-agent-instructions-v1 -->

---

## Beads Workflow Integration

This project uses [beads_rust](https://github.com/Dicklesworthstone/beads_rust) (`br`/`bd`) for issue tracking. Issues are stored in `.beads/` and tracked in git.

### Essential Commands

```bash
# View ready issues (open, unblocked, not deferred)
br ready              # or: bd ready

# List and search
br list --status=open # All open issues
br show <id>          # Full issue details with dependencies
br search "keyword"   # Full-text search

# Create and update
br create --title="..." --description="..." --type=task --priority=2
br update <id> --status=in_progress
br close <id> --reason="Completed"
br close <id1> <id2>  # Close multiple issues at once

# Sync with git
br sync --flush-only  # Export DB to JSONL
br sync --status      # Check sync status
```

### Workflow Pattern

1. **Start**: Run `br ready` to find actionable work
2. **Claim**: Use `br update <id> --status=in_progress`
3. **Work**: Implement the task
4. **Complete**: Use `br close <id>`
5. **Sync**: Always run `br sync --flush-only` at session end

### Key Concepts

- **Dependencies**: Issues can block other issues. `br ready` shows only open, unblocked work.
- **Priority**: P0=critical, P1=high, P2=medium, P3=low, P4=backlog (use numbers 0-4, not words)
- **Types**: task, bug, feature, epic, chore, docs, question
- **Blocking**: `br dep add <issue> <depends-on>` to add dependencies

### Session Protocol

**Before ending any session, run this checklist:**

```bash
git status              # Check what changed
git add <files>         # Stage code changes
br sync --flush-only    # Export beads changes to JSONL
git commit -m "..."     # Commit everything
git push                # Push to remote
```

### Best Practices

- Check `br ready` at session start to find available work
- Update status as you work (in_progress → closed)
- Create new issues with `br create` when you discover tasks
- Use descriptive titles and set appropriate priority/type
- Always sync before ending session

<!-- end-br-agent-instructions -->
