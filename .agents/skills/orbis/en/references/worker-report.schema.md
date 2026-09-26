# Worker Report Schema

End the assignment with this exact shape.

```text
WORKER_FINAL_REPORT

STATUS
<DONE | DONE_WITH_CONCERNS | OUT_OF_SCOPE | BLOCKED | NEED_CONTEXT>

TEST SUMMARY

Purpose:
<why the check was required>

Target:
<test, file, class, module, or behavior>

Method:
<how the check was performed>

Result:
<observed result>


ISSUES

Issue:
<unexpected finding>

Response:
<what the worker did>

Impact:
<review or follow-up required>

<Use None. when there are no issues.>


FILES

Modified:
- <path or None>

Added:
- <path or None>

Deleted:
- <path or None>
```
