# DocFlow

This project was completely written through Claude Code, with extensive planning, a long task implementation session, then a cleanup/fine-tune session.

This is the only file hand written - to make sure I communicated a couple of the core things I was focused on.

## Focus

My core areas of focus were:
* Have a reasonable data and domain model
* A working end to end solution
* A validation system to prevent issues

There are a couple areas that I paid less attention to:
* Java code structure - personal preference/experience may suggest the class/folder structures could be improved
* UI - There are at least a couple visual aspects that could be polished further
* Tradeoffs - this isn't actually prod ready - there are definite gaps (ex: I'd imagine putting 100k documents in might find some query perf issues)

## A Key Design Decision

One interesting thing I ran into: 

If you have a single domain entity 'Document' that goes from upload, through the pipeline, gets reviewed, then just exists 'forever' - you see that 'pending' documents (not processed yet) look very different than completed ones. This has pretty significant downstream impacts - query times, processing pipeline changes impacting the Document domain entity, separating out write heavy tables, etc.

So instead, the PDF comes in and is saved as a `StoredDocument`. Then the pipeline handles a `ProcessingDocument`, which stores everything needed for the pipeline. Finally, the needed data is moved onto a `Document`. This means the pipeline can evolve significantly, without the core entity `Document` needing to be changed.

Realized this during data modeling and it changed my strategy - figured I'd point it out.

## Process

You can see the plans/specs/tasks used to generate the project in the `.kerf/project` folder. (`kerf` is a little tool I wrote that helps formalize the 'idea' -> 'tasks ready to implement' process)

The process I've been using for ~4 months, and used in this project, goes something like this:

* Planning
  * Brain Dump (in this case spec dump)
  * Sketch out the broad strokes - with agent, refine the major things to be done, architecture/domain, non-functional requirements, etc
  * Agent breaks up the project into 'components'/subsystems
  * Human/Agent dive deep into each component, exposing incorrect assumptions, refining the domain, workflow, etc (Most of the time was probably spent here)
  * Numerous agents review these components to find issues

* Spec Building
  * The components are broken down into specs - very specific descriptions of what needs to be built
  * Numerous agents review - looking at the specs from various angles

* Task Creation
  * From the specs, tasks are created that an agent can execute within a reasonably sized context window
  * Dependencies between tasks are identified - to make sure ordering is correct
  * Tasks are reviewed by numerous agents to check details and ordering
  * Tasks are loaded in to [beads](https://github.com/gastownhall/beads) for execution, and reviewed

* Implementation
  * An agent is spun up with instructions to implement those beads 'As the orchestrator' - meaning it will use subagents and worktrees to implement each task/bead in its own context, then merge into an integration branch
  * In this case, after a batch of work, I'd restart the main orchestration thread

* Refinement/Cleanup
  * After the implementation there were many bugs, gaps, UI issues. One agent was in charge of searching these out (plus notes from my manual testing). The other agent was in charge of fixing. The beads were used to communicate the tasks to be completed.

## Testing and Validation

Used quite a few techniques to try and find as many issues as possible. I guided the agent to what needed to be done (the type of tests) - it handled most of the details.

* Standard unit tests - I left the minimum code coverage a little low (70% I believe) - but in prod I'd probably raise that
* UI tests - Playwright was used to perform UI testing
* Scenario tests - the pipeline's LLM calls were mocked out, then various tests were written to exercise the system.
* Evals - checked that the prompts classified and extracted data correctly
  * Note: I didn't need to spend much time here - it just worked well. But would in a prod system
* Agentic Exploratory testing - TESTING-PLAYBOOK.md contains instructions an agent can take to do exploratory testing on a running system
