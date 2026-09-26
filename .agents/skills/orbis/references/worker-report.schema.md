# Worker 보고 스키마

책임을 마칠 때 다음 형식으로 응답합니다.

```text
WORKER_FINAL_REPORT

STATUS
<DONE | DONE_WITH_CONCERNS | OUT_OF_SCOPE | BLOCKED | NEED_CONTEXT>

TEST SUMMARY

Purpose:
<이 검증이 필요한 이유>

Target:
<검증한 테스트, 파일, 클래스, 모듈 또는 동작>

Method:
<검증한 방법>

Result:
<관찰한 결과>


ISSUES

Issue:
<새로 발견한 문제 또는 사실>

Response:
<Worker가 취한 대응>

Impact:
<검토하거나 후속 처리해야 할 영향>

<발견 사항이 없으면 None.>


FILES

Modified:
- <경로 또는 None>

Added:
- <경로 또는 None>

Deleted:
- <경로 또는 None>
```
