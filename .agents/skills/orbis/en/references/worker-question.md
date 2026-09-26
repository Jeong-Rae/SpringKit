# Worker Question

Before modifying files, the worker asks the orchestrator about anything it cannot confidently determine.

Read [Worker Question Schema](worker-question.schema.md) for the exact response shape.

## Preparation

Before asking a question:

- read the complete worker request;
- read every required item in PREPARATION;
- read applicable repository instructions;
- inspect enough relevant code and tests;
- confirm the stated ownership boundary.

Do not ask about information that can be resolved directly from the repository. Keep this phase read-only.

## Question First

Ask first about anything that is difficult to understand or unsafe to decide independently.

After the question, briefly state the current understanding and the intent of the question. This lets the orchestrator see what the worker already knows and which decision the answer will resolve.

Ask the most blocking question first instead of combining several independent decisions in one response.

## Status

`QUESTION` means an answer or decision from the orchestrator is required.

`READY` means the worker has no unresolved question about the assignment, context, contracts, or ownership. READY does not authorize writing. Wait for `PROCEED`.

`OUT_OF_SCOPE` means required work exceeds the approved assignment or ownership.

`BLOCKED` means the assignment is understood and in scope, but an execution condition prevents progress.

## Question and Answer

The orchestrator answers the question, corrects an understanding, adds preparation context, or revises ownership or responsibility.

After each answer, reassess the assignment. Return `QUESTION` again if another uncertainty remains, or return `READY` when all questions are resolved.

After a material change to ASSIGNMENT, PREPARATION, OWNERSHIP, or CONTRACTS, re-read the changed boundary and continue asking only what remains unresolved.

## Execution

The orchestrator sends `PROCEED` only after the worker returns `READY`.

After `PROCEED`, implement, test, and self-review within the approved assignment and ownership. If a new uncertainty appears during implementation, stop at a safe point and report the appropriate terminal status.
