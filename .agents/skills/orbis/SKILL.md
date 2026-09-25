---
name: orbis
description: >-
  Automate subagent-based coding work within a single commit closure through an
  orchestrator and bounded workers. Use when a coding task should be decomposed
  into explicit worker responsibilities, negotiated before execution, delegated
  to native Codex subagents, and verified by the orchestrator until the commit
  closure is complete.
---

# Orbis

Version: 0.0.1

Coordinate one logical coding change through an orchestrator and bounded
workers. The orchestrator owns semantic control. Workers own implementation
inside approved assignments.

## Worker Runtime

Spawn every worker through the native Codex subagent function:

```text
spawn_agent(
  task_name="<TASK ID>",
  message="<COMPOSED WORKER REQUEST>",
  model="gpt-6-luna",
  reasoning_effort="xhigh",
  fork_turns="none"
)
```

Every worker uses `gpt-6-luna` with `xhigh` reasoning and a clean context.

When negotiation is accepted, continue the same worker:

```text
followup_task(
  target="<TASK ID>",
  message="PROCEED"
)
```

Use the same worker for corrections or follow-up work that remains inside the
approved assignment.

## Role Boundaries

- Refer to child agents as workers throughout this Skill.
- Require workers to read applicable repository instructions before
  implementation.
- Do not allow workers to spawn nested workers or subagents.
- Do not allow workers to silently expand their assignment or ownership.
- Do not allow workers to mutate shared Git state unless explicitly authorized.
- The orchestrator must not implement production code, test code, or integration
  code itself. Delegate implementation changes to workers.

## Commit Closure

One orchestrated change unit must fit within one logical commit.

A logical commit contains all and only the changes required to make one coherent
behavior change complete and independently reviewable. The orchestrator may add
workers when a newly discovered responsibility is required to close the same
logical commit. If a discovered change forms an independent atomic change,
split it from the current orchestration unit.

Do not equate a worker boundary with a commit boundary. One logical commit may
contain several sequential or parallel worker assignments.

## Workflow

1. Read the user request and applicable repository instructions.
2. Define the objective and the logical-commit boundary.
3. Read [delegation-policy.md](references/delegation-policy.md) and decompose the
   change into cohesive responsibilities with explicit dependencies.
4. Before dispatching a worker, read
   [worker-request.md](references/worker-request.md),
   [worker-negotiation.md](references/worker-negotiation.md), and
   [worker-report.md](references/worker-report.md).
5. Compose the worker request and call `spawn_agent(...)` using the Worker
   Runtime contract.
6. Require the worker to perform preparation and negotiation before writing.
7. Review the negotiation response. Correct assumptions, answer questions,
   revise ownership, or reassign the task when necessary.
8. Call `followup_task(..., message="PROCEED")` only when the assignment and
   boundaries are acceptable.
9. Let the worker implement, test, self-review, and report autonomously inside
   the approved assignment.
10. Receive the terminal worker report. Read
    [acceptance-policy.md](references/acceptance-policy.md) and inspect the
    actual repository state.
11. Accept the result, send a follow-up, expand the same commit closure, split
    an independent change, or reassign responsibility.
12. Finish only when the objective is satisfied and the logical commit is
    closed.

## Control Rules

- Delegate by cohesive responsibility rather than file count.
- Give every concurrently modified file exactly one worker owner.
- Do not run workers in parallel when one worker requires another worker's
  in-progress write as an input.
- Freeze shared interfaces before parallel workers depend on them.
- Keep integration files under one worker owner.
- Treat worker reports as claims. Treat the actual repository state as the
  source of truth.
- Keep repository-level commit, publication, and pull-request operations outside
  workers and follow the repository's Git workflow.
