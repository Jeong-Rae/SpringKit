# Worker 보고 스키마

책임을 마칠 때 다음 형식으로 응답합니다.

## 분량

보고는 얇게 유지합니다. 각 자유 서술 항목은 최대 3줄, 전체 보고 내용은 약 10줄 수준을 목표로 합니다.

이 분량은 형식 검증을 위한 엄격한 제한이 아니라 장황한 응답을 막기 위한 가이드입니다. 필요한 검증 결과, 이슈, 변경 경로는 줄 수를 맞추려고 생략하지 않습니다. `STATUS`와 `FILES`의 경로 목록은 이 분량 목표에서 제외합니다.

같은 내용을 반복하거나 배경 설명을 덧붙이지 않습니다. 발견 사항이 없으면 `ISSUES`에는 `None.`만 적습니다.

```text
WORKER_FINAL_REPORT

STATUS
<DONE | DONE_WITH_CONCERNS | OUT_OF_SCOPE | BLOCKED | NEED_CONTEXT>

TEST SUMMARY

Purpose:
<최대 3줄. 검증 이유>

Target:
<최대 3줄. 검증한 테스트, 파일, 클래스, 모듈 또는 동작>

Method:
<최대 3줄. 검증 방법>

Result:
<최대 3줄. 관찰한 결과>


ISSUES

Issue:
<최대 3줄. 새로 발견한 문제 또는 사실>

Response:
<최대 3줄. Worker가 취한 대응>

Impact:
<최대 3줄. 검토하거나 후속 처리할 영향>

<발견 사항이 없으면 None.>


FILES

Modified:
- <경로 또는 None>

Added:
- <경로 또는 None>

Deleted:
- <경로 또는 None>
```
