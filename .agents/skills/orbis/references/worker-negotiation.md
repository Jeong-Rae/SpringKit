# Worker Negotiation Contract

A worker must negotiate its assignment before modifying files.

## Prepare Before Negotiation

Before responding, the worker must:

- read the complete worker request;
- read all required material in PREPARATION;
- read applicable repository instructions;
- inspect enough relevant code and tests to understand the surrounding flow;
- confirm the stated ownership boundary.

This phase is read-only.

## Negotiation Response

Return this shape:

```text
WORKER_NEGOTIATION

STATUS
<READY | NEED_CONTEXT | OUT_OF_SCOPE | BLOCKED>

UNDERSTANDING
<What the worker believes the assignment requires.>

EXPECTED CHANGES
<Likely implementation areas and observable effects.>

CONTRACTS
<Interfaces and invariants that must remain true.>

RISKS
<Known implementation risks, or None.>

QUESTIONS
<Questions that require orchestrator resolution, or None.>
```

## Status Meaning

`READY` means the worker believes the assignment, context, and ownership are sufficient. READY is not permission to write. The worker waits for `PROCEED`.

`NEED_CONTEXT` means the worker cannot determine the correct behavior from the available information.

`OUT_OF_SCOPE` means the worker can identify work required for the objective, but that work exceeds the assigned responsibility or ownership.

`BLOCKED` means the assignment is understood and in scope, but an execution condition prevents progress.

## Orchestrator Response

The orchestrator reviews the negotiation response and may:

- send `PROCEED`;
- correct an assumption;
- answer a question;
- add preparation context;
- revise ownership;
- reassign the responsibility.

After any material correction to assignment, preparation, ownership, or contract, the worker must return a refreshed negotiation response before writing.

## Autonomous Execution

After `PROCEED`, the worker may inspect, implement, test, and self-review without asking for routine implementation decisions.

The worker must remain inside the approved assignment and ownership. If a new fact makes completion require a boundary change, do not silently expand scope. Stop at a safe point and use the terminal report status that describes the condition.
