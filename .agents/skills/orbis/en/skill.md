---
name: orbis
description: >-
  Coordinate coding work within one commit closure through an orchestrator and
  bounded workers. Workers clarify responsibility and boundaries through
  questions before implementation, then implement and verify only within the
  approved scope. The orchestrator verifies the actual repository state and
  coordinates follow-up work until the logical commit is complete. Use when one
  coding request should be divided across workers and completed as one logical
  commit.
---

# Orbis

Version: 0.0.1

The orchestrator owns the meaning and boundaries of the change. Workers own implementation and verification within approved responsibilities.

## Worker Runtime

Spawn every worker through the native Codex subagent function:

```text
spawn_agent(
  task_name="<ORCHESTRATOR TASK NAME>",
  message="<COMPOSED WORKER REQUEST>",
  model="gpt-6-luna",
  reasoning_effort="xhigh",
  fork_turns="none"
)
```

Every worker uses `gpt-6-luna` with `xhigh` reasoning and does not inherit the orchestrator conversation.

`task_name` is an internal name used only by the orchestrator to organize work. Do not include task-management information in Orbis worker requests, questions, or reports.

Record the `agent_id` returned by `spawn_agent(...)` as the Worker ID. Use that Worker ID to correlate worker responses and follow-up calls.

When the worker resolves all questions and returns `READY`, continue the same worker:

```text
followup_task(
  target="<WORKER ID>",
  message="PROCEED"
)
```

Use the same worker for corrections or follow-up work that remains inside the approved responsibility.

## Role Boundaries

- Refer to child agents as workers throughout this Skill.
- Workers read applicable repository instructions before implementation.
- Workers do not spawn nested workers or subagents.
- Workers do not expand their responsibility or ownership without approval.
- Workers do not mutate shared Git state unless explicitly authorized.
- The orchestrator does not implement production code, test code, or integration code. Delegate implementation changes to workers.

## Commit Closure

Keep one orchestration unit within one logical commit.

A commit closure contains all changes required to complete one coherent behavior change and make it independently reviewable. Add or revise worker responsibilities when the objective requires more work. Split work that has an independent reason for change.

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
4. Read the worker request protocol and schema, compose the request, and call `spawn_agent(...)`.
5. Record the returned `agent_id` as the Worker ID and map it to the orchestrator's internal task record.
6. The worker reads repository context and asks a question when it cannot confidently determine a required fact or decision.
7. The orchestrator answers the question, corrects misunderstandings, and provides required decisions or context.
8. The worker returns `QUESTION` while uncertainties remain and returns `READY` when they are resolved.
9. After `READY`, call `followup_task(..., message="PROCEED")` with the Worker ID as `target`.
10. The worker implements, tests, self-reviews, and returns a terminal response conforming to the worker report schema.
11. Read the acceptance policy and inspect the actual repository state.
12. Accept the result, send a follow-up, expand the same commit closure, split an independent change, or reassign responsibility.
13. Finish only when the objective is satisfied and the logical commit is closed.

## Control Rules

- Delegate by cohesive responsibility rather than file count.
- Give every concurrently modified file exactly one worker owner.
- Run workers sequentially when one requires another worker's unfinished write as input.
- Stabilize shared interfaces before parallel workers depend on them.
- Keep integration files under one worker owner.
- Treat worker reports as claims and the actual repository state as the source of truth.
- The orchestrator owns the mapping between internal task names and Worker IDs.
- Keep repository-level commit, publication, and pull-request operations outside workers and follow the repository's Git workflow.
