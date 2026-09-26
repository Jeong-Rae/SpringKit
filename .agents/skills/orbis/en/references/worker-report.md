# Worker Report

End every worker responsibility with one terminal report.

Read [Worker Report Schema](worker-report.schema.md) for the exact response shape.

`WORKER_FINAL_REPORT` marks the response as terminal. It does not imply success.

## Status Semantics

- `DONE`: the approved responsibility was completed normally.
- `DONE_WITH_CONCERNS`: the responsibility is complete, but explicit orchestrator review is required.
- `OUT_OF_SCOPE`: completion requires work outside the approved responsibility or ownership.
- `BLOCKED`: the in-scope work is understood, but execution cannot continue.
- `NEED_CONTEXT`: after `PROCEED`, the worker stopped at a safe point because implementation or verification cannot be completed without additional information.

## Test Summary

For each relevant check, report the purpose, target, method, and observed result. If a required check was not run, state why.

## Issues

Report unexpected findings and the response taken. Write `None.` when there are no such findings.

## Files

Report every file actually modified, added, or deleted. The orchestrator compares this list with declared ownership and the actual repository diff.
