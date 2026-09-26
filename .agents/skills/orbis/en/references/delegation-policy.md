# Delegation Policy

Apply this policy before defining worker responsibilities.

## Preserve One Logical Commit

Keep the orchestration scope within one logical commit. Include changes required to complete the objective and exclude independent cleanup, refactoring, or follow-up work.

When a new responsibility appears after workers begin, decide whether it belongs to the current commit closure.

- Same logical commit: add or revise a responsibility and continue closing the commit.
- Independent atomic change: split it from the current orchestration.

Do not use the initially predicted file list as the commit boundary.

## Delegate by Responsibility

Give each worker one cohesive implementation responsibility. Do not split work merely because several files are involved.

A worker may own several implementation and test files when they express one responsibility and can be verified together. Split responsibilities only when their contracts and verification can remain independent.

The orchestrator delegates implementation and retains decisions about the objective, dependency graph, ownership, and commit closure.

## Define Ownership

For each responsibility, define:

- writable areas;
- read-only dependencies;
- excluded areas.

Each concurrently modified file has one worker owner.

Workers do not change files outside their ownership merely because doing so would make implementation easier. The orchestrator decides whether an out-of-ownership change should expand or revise the responsibility.

## Order Dependencies

Represent worker relationships as a dependency graph.

Run workers in the same stage only when all of these conditions hold:

```text
write(A) ∩ write(B) = ∅

write(A) ∩ requiredInput(B) = ∅

write(B) ∩ requiredInput(A) = ∅
```

`requiredInput(X)` means finalized output from another worker that worker X needs to continue or complete its responsibility.

If one worker requires another worker's new output, run them sequentially.

## Stabilize Contracts Before Parallel Work

When several workers depend on the same interface, finalize the contract before parallel work begins.

If the interface itself must be decided or changed, finish that work first. Downstream workers then work against the finalized contract.

Workers do not negotiate shared interfaces directly with each other. The orchestrator finalizes shared contracts.

## Assign Integration Explicitly

Give integration code and shared wiring one worker owner. An existing worker may own it when integration is part of that responsibility. Otherwise, delegate it to another worker after upstream work is complete.

The orchestrator does not write integration code.
