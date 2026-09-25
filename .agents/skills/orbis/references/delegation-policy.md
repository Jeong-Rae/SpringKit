# Delegation Policy

Use this policy before creating worker assignments.

## Preserve One Logical Commit

Keep the orchestrated change within one logical commit. Include every change required to make the objective complete, but exclude independent cleanup, refactoring, or follow-up changes.

When a required responsibility is discovered after workers begin, determine whether it belongs to the same logical commit:

- Same logical commit: add or revise an assignment and continue toward commit closure.
- Independent atomic change: split it from the current orchestration unit.

Do not use the initially predicted file list as the commit boundary.

## Delegate by Responsibility

Assign one cohesive implementation responsibility to each worker. Do not split work merely because several files are involved.

A worker may own multiple implementation and test files when they express one responsibility and can be verified together. Separate responsibilities only when their contracts and verification can remain independent.

The orchestrator delegates implementation work. It retains responsibility for the objective, dependency graph, ownership boundaries, and closure decisions.

## Define Ownership

For each assignment, identify:

- owned write areas;
- read-only dependencies;
- explicitly excluded areas.

One concurrently modified file must have exactly one worker owner.

Workers must not change files outside their ownership merely because doing so would make the implementation easier. A required out-of-ownership change is an orchestration decision.

## Order Dependencies

Represent worker relationships as a dependency graph.

Workers may run in the same stage only when all of these conditions hold:

```text
write(A) ∩ write(B) = ∅

write(A) ∩ requiredInput(B) = ∅

write(B) ∩ requiredInput(A) = ∅
```

If a worker requires another worker's new output, run them in sequential stages.

## Stabilize Contracts Before Parallel Work

Parallel workers may depend on a shared interface only when the relevant contract is already stable for the current logical commit.

If the interface itself must be decided or changed, resolve that dependency first. Then dispatch downstream workers against the agreed contract.

Do not ask sibling workers to coordinate their interface by exchanging unstructured reasoning.

## Assign Integration Explicitly

Give integration code and shared wiring one owner. That owner may be an existing worker when integration is part of its cohesive responsibility, or a later worker after upstream assignments reach a barrier.

The orchestrator does not write integration code itself.
