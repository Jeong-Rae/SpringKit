# Worker Report

End every worker assignment with one terminal report.

Read [Worker Report Schema](worker-report.schema.md) for the exact response shape.

`WORKER_FINAL_REPORT` marks the response as terminal. It does not imply success.

## Status Semantics

- `DONE`: assignment completed normally.
- `DONE_WITH_CONCERNS`: assignment completed, but explicit orchestrator review is required.
- `OUT_OF_SCOPE`: completion requires work outside approved assignment or ownership.
- `BLOCKED`: in-scope work is understood, but execution cannot continue.
- `NEED_CONTEXT`: the correct behavior cannot be determined from available context.

## Test Summary

For every relevant verification, report its purpose, target, method, and observed result. State any relevant check that was not run and explain why.

## Issues

Report unexpected findings and extra handling that were not already implied by the assignment. Write `None.` when there are no such findings.

## Files

Report every file actually modified, added, or deleted. The orchestrator compares this list with declared ownership and the actual repository diff.
