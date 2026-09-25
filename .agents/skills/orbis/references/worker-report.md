# Worker Report Contract

Every worker ends with this terminal response:

```text
WORKER_FINAL_REPORT

STATUS
<DONE | DONE_WITH_CONCERNS | OUT_OF_SCOPE | BLOCKED | NEED_CONTEXT>

TEST SUMMARY

ISSUES

FILES
```

`WORKER_FINAL_REPORT` marks the response as terminal. It does not imply success.

## Status

- `DONE`: assignment completed normally.
- `DONE_WITH_CONCERNS`: assignment completed, but explicit orchestrator review is requested.
- `OUT_OF_SCOPE`: completion requires work outside approved assignment or ownership.
- `BLOCKED`: in-scope work is understood, but execution cannot continue.
- `NEED_CONTEXT`: the correct behavior cannot be determined from available context.

## Test Summary

For each verification, report:

```text
Purpose:
<why the check was required>

Target:
<test, file, class, module, or behavior>

Method:
<how the check was performed>

Result:
<observed result>
```

State relevant checks that were not run and why.

## Issues

Report unexpected findings and any extra handling that was not already implied by the assignment.

```text
Issue:
<new problem or fact>

Response:
<what the worker did>

Impact:
<review or follow-up required>
```

Write `None.` when there are no such findings.

## Files

Report every file actually changed:

```text
Modified:
- path/to/File.java

Added:
- None

Deleted:
- None
```

The orchestrator compares this report with declared ownership and the actual repository diff.
