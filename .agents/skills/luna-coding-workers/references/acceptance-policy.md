# Acceptance Policy

Use this policy after receiving a terminal worker report.

A worker report is a claim. The repository state is the source of truth.

## Inspect

Compare:

- objective and assignment;
- declared ownership;
- worker status;
- reported files;
- actual repository diff;
- test evidence;
- shared contracts and invariants;
- remaining logical-commit closure.

Do not accept an assignment only because the worker reports `DONE`.

## Ownership

Investigate every changed file that was not declared or reported. Do not normalize ownership drift after implementation.

## Verification

Confirm that the reported checks prove the behavior required by the assignment. Request narrower or additional verification when the evidence is insufficient.

## Commit Closure

Ask both questions:

1. Is every current change required for this logical commit?
2. Is any additional change required before this logical commit is complete?

A missing responsibility inside the same logical commit expands the closure. An independent atomic change must be split from the current orchestration unit.

## Decisions

- `ACCEPT`: the assignment satisfies its contract and fits the logical commit.
- `FOLLOW_UP`: the same worker must correct or complete work inside the same responsibility.
- `EXPAND`: a new responsibility is required for the same commit closure.
- `SPLIT`: a discovered change belongs to an independent logical commit.
- `REASSIGN`: responsibility or ownership was assigned incorrectly.

Review `DONE_WITH_CONCERNS` issues explicitly before acceptance. Resolve `OUT_OF_SCOPE`, `BLOCKED`, and `NEED_CONTEXT` before further implementation.

The orchestrator decides acceptance and next actions but does not repair implementation code itself. Keep repository-level commit and publication outside workers.
