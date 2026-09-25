---
name: luna-coding-workers
description: >-
  Orchestrate one logical coding change through clean-context Luna workers.
  Use when implementation should be delegated to native Codex subagents while
  the orchestrator retains responsibility for logical-commit boundaries,
  dependency ordering, ownership, negotiation, acceptance, and commit closure.
  Workers implement and test bounded assignments, use Luna with fork_turns set
  to none, and report through the defined worker protocol.
---

# Luna Coding Workers

Coordinate one logical coding change through clean-context workers. The
orchestrator owns semantic control. Workers own implementation inside approved
assignments.

## Runtime Contract

- Refer to child agents as workers throughout this Skill.
- Use Luna as the model for every worker.
- Spawn each worker as a native Codex subagent with `fork_turns: "none"`.
- Do not inherit the orchestrator's conversation history into a worker.
- Give each worker only its explicit request and repository-visible context.
- Keep workers in the current working directory and branch unless repository
  instructions require a different execution boundary.
- Require workers to read applicable repository instructions before
  implementation.
- Do not allow workers to spawn nested workers or subagents.
- Do not allow workers to mutate shared Git state unless the request explicitly
  authorizes it.
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
5. Compose the worker request from the request contract and spawn the worker
   with the Runtime Contract.
6. Require the worker to perform preparation and negotiation before writing.
7. Review the negotiation response. Correct assumptions, answer questions,
   revise ownership, or reassign the task when necessary. Send `PROCEED` only
   when the assignment and boundaries are acceptable.
8. After `PROCEED`, let the worker implement, test, self-review, and report
   autonomously inside the approved assignment.
9. Receive the terminal worker report. Read
   [acceptance-policy.md](references/acceptance-policy.md) and inspect the actual
   repository state.
10. Accept the result, send a follow-up, expand the same commit closure, split
    an independent change, or reassign responsibility.
11. Finish only when the objective is satisfied and the logical commit is
    closed.

## Control Rules

- Delegate by cohesive responsibility rather than file count.
- Give every concurrently modified file exactly one worker owner.
- Do not run workers in parallel when one worker requires another worker's
  in-progress write as an input.
- Freeze shared interfaces before parallel workers depend on them.
- Keep integration files under one worker owner.
- Do not let workers silently expand ownership.
- Treat worker reports as claims. Treat the actual repository state as the
  source of truth.
- Keep repository-level commit, publication, and pull-request operations outside
  workers and follow the repository's Git workflow.
