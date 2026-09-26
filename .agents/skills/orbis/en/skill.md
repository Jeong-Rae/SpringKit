---
name: orbis
description: >-
  Automate subagent-based coding work within a single commit closure through an
  orchestrator and bounded workers. Use when a coding task should be decomposed
  into explicit worker responsibilities, clarified through worker questions
  before execution, delegated to native Codex subagents, and verified by the
  orchestrator until the commit closure is complete.
---

# Orbis

Version: 0.0.1

Coordinate one logical coding change through an orchestrator and bounded workers. The orchestrator owns semantic control. Workers own implementation inside approved assignments.

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

When the worker has resolved its questions and returns `READY`, continue the same worker:

```text
followup_task(
  target="<TASK ID>",
  message="PROCEED"
)
```

Use the same worker for corrections or follow-up work that remains inside the approved assignment.

## Role Boundaries

- Refer to child agents as workers throughout this Skill.
- Workers read applicable repository instructions before implementation.
- Workers do not spawn nested workers or subagents.
- Workers do not expand their assignment or ownership without approval.
- Workers do not mutate shared Git state unless explicitly authorized.
- The orchestrator does not implement production code, test code, or integration code. Delegate implementation changes to workers.

## Commit Closure

Keep one orchestrated change unit within one logical commit.

A commit closure contains all changes required to complete one coherent behavior change and make it independently reviewable. Add or revise worker responsibilities when the current objective requires more work. Split work that has an independent reason for change.

A worker boundary is different from a commit boundary. One logical commit may contain several sequential or parallel worker assignments.

## References

For delegation and acceptance decisions, read:

- [Delegation Policy](references/delegation-policy.md)
- [Acceptance Policy](references/acceptance-policy.md)

For worker interaction semantics and exact message shapes, read:

- [Worker Request](references/worker-request.md)
- [Worker Request Schema](references/worker-request.schema.md)
- [Worker Question](references/worker-question.md)
- [Worker Question Schema](references/worker-question.schema.md)
- [Worker Report](references/worker-report.md)
- [Worker Report Schema](references/worker-report.schema.md)

## Workflow

1. Read the user request and applicable repository instructions.
2. Define the objective and logical-commit boundary.
3. Read the delegation policy and decompose the change into cohesive responsibilities with explicit dependencies.
4. Read the worker request protocol and schema, compose the request, and call `spawn_agent(...)` using the Worker Runtime contract.
5. The worker reads repository context and asks a question when it cannot confidently determine a required fact or decision.
6. The orchestrator answers the question, corrects misunderstandings, and provides required decisions or context.
7. The worker returns `QUESTION` again while uncertainties remain and returns `READY` when they are resolved.
8. Call `followup_task(..., message="PROCEED")` only after the worker returns `READY`.
9. The worker implements, tests, self-reviews, and returns a terminal response conforming to the worker report schema.
10. Read the acceptance policy and inspect the actual repository state.
11. Accept the result, send a follow-up, expand the same commit closure, split an independent change, or reassign responsibility.
12. Finish only when the objective is satisfied and the logical commit is closed.

## Control Rules

- Delegate by cohesive responsibility rather than file count.
- Give every concurrently modified file exactly one worker owner.
- Run workers sequentially when one requires another worker's in-progress write as input.
- Stabilize shared interfaces before parallel workers depend on them.
- Keep integration files under one worker owner.
- Treat worker reports as claims and the actual repository state as the source of truth.
- Keep repository-level commit, publication, and pull-request operations outside workers and follow the repository's Git workflow.
