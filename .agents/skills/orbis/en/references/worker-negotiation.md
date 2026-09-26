# Worker Negotiation

Negotiate the assignment before modifying files.

Read [Worker Negotiation Schema](worker-negotiation.schema.md) for the exact response shape.

## Preparation

Before responding:

- read the complete worker request;
- read every required item in PREPARATION;
- read applicable repository instructions;
- inspect enough relevant code and tests to understand the surrounding flow;
- confirm the stated ownership boundary.

Keep this phase read-only.

## Status Semantics

`READY` means the assignment, context, contracts, and ownership are sufficient. READY does not authorize writing. Wait for `PROCEED`.

`NEED_CONTEXT` means the correct behavior cannot be determined from the available information.

`OUT_OF_SCOPE` means required work is understood but exceeds the approved assignment or ownership.

`BLOCKED` means the assignment is understood and in scope, but an execution condition prevents progress.

## Orchestrator Response

The orchestrator may send `PROCEED`, correct an assumption, answer a question, add preparation context, revise ownership, or reassign responsibility.

After a material change to assignment, preparation, ownership, or contracts, return a refreshed negotiation message before writing.

## Autonomous Execution

After `PROCEED`, inspect, implement, test, and self-review autonomously within the approved assignment and ownership.

If completion requires a boundary change, stop at a safe point and return the terminal report status that represents the condition.
