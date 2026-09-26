# Acceptance Policy

Apply this policy after receiving a terminal worker report.

A worker report is a claim. Use the actual repository state as the source of truth.

## Inspect

Compare:

- objective and responsibility;
- declared ownership;
- worker status;
- reported files;
- actual repository diff;
- test evidence;
- shared contracts and invariants;
- remaining commit closure.

Do not accept work only because the worker reports `DONE`.

## Ownership

Investigate any changed file that was not declared or reported. Do not approve ownership drift merely because implementation is complete.

## Verification

Confirm that the reported checks prove the behavior required by the responsibility. Request additional verification when the evidence is insufficient.

## Commit Closure

Ask both questions:

1. Is every current change required for this logical commit?
2. Is any additional change required before this logical commit is complete?

A missing responsibility inside the same logical commit expands the closure. An independent atomic change must be split from the current orchestration.

## Decisions

- `ACCEPT`: the responsibility satisfies its contract and belongs in the current logical commit.
- `FOLLOW_UP`: the same worker must correct or complete work within the same responsibility.
- `EXPAND`: a new responsibility is required for the same commit closure.
- `SPLIT`: a discovered change belongs to an independent logical commit.
- `REASSIGN`: responsibility or ownership was assigned incorrectly.

Review `DONE_WITH_CONCERNS` before acceptance. Resolve `OUT_OF_SCOPE` and `BLOCKED` before further implementation.

`NEED_CONTEXT` means the worker stopped at a safe point after `PROCEED` because implementation or verification cannot be completed without additional information. Provide the missing context and continue the same worker with a follow-up.

The orchestrator decides acceptance and next actions but does not repair implementation code. Keep repository-level commit and publication outside workers.
