# Worker Report Schema

End the assignment with this exact shape.

## Response Length

- Keep each free-text field to 2 lines by default and within 1-3 lines.
- Do not repeat the same point or add background that does not change the result.
- Write only the single status value under `STATUS`.
- Do not apply the line limit to `FILES`; list every actual changed path.
- If there are no issues, write only `None.` under `ISSUES`.

```text
WORKER_FINAL_REPORT

STATUS
<DONE | DONE_WITH_CONCERNS | OUT_OF_SCOPE | BLOCKED | NEED_CONTEXT>

TEST SUMMARY

Purpose:
<1-3 lines. Why the check was required>

Target:
<1-3 lines. Test, file, class, module, or behavior checked>

Method:
<1-3 lines. How the check was performed>

Result:
<1-3 lines. Observed result>


ISSUES

Issue:
<1-3 lines. Unexpected finding>

Response:
<1-3 lines. What the worker did>

Impact:
<1-3 lines. Review or follow-up required>

<Use None. when there are no issues.>


FILES

Modified:
- <path or None>

Added:
- <path or None>

Deleted:
- <path or None>
```
