# Worker Report Schema

End the responsibility with this exact shape.

## Length

Keep the report thin. Aim for at most 3 lines per free-text item and roughly 10 lines of report content overall.

These are soft limits intended to discourage verbose reporting, not strict validation rules. Do not omit necessary verification results, issues, or changed paths merely to satisfy the target. `STATUS` and the path list under `FILES` are excluded from the overall target.

Do not repeat the same point or add background that does not change the result. If there are no issues, write only `None.` under `ISSUES`.

```text
WORKER_FINAL_REPORT

STATUS
<DONE | DONE_WITH_CONCERNS | OUT_OF_SCOPE | BLOCKED | NEED_CONTEXT>

TEST SUMMARY

Purpose:
<Up to 3 lines. Why the check was required>

Target:
<Up to 3 lines. Test, file, class, module, or behavior checked>

Method:
<Up to 3 lines. How the check was performed>

Result:
<Up to 3 lines. Observed result>


ISSUES

Issue:
<Up to 3 lines. Unexpected finding>

Response:
<Up to 3 lines. What the worker did>

Impact:
<Up to 3 lines. Review or follow-up required>

<Use None. when there are no issues.>


FILES

Modified:
- <path or None>

Added:
- <path or None>

Deleted:
- <path or None>
```
