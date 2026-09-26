# Worker 보고 스키마

책임을 마칠 때 다음 형식으로 응답합니다.

## 응답 길이

- 각 자유 서술 필드는 2줄을 기본으로 쓰며 1~3줄 안에서 끝냅니다.
- 같은 내용을 반복하거나 배경 설명을 덧붙이지 않습니다.
- `STATUS`는 상태 값 한 줄만 씁니다.
- `FILES`는 길이 제한을 적용하지 않고 실제 변경 경로를 모두 적습니다.
- 발견 사항이 없으면 `ISSUES`에는 `None.`만 적습니다.

```text
WORKER_FINAL_REPORT

STATUS
<DONE | DONE_WITH_CONCERNS | OUT_OF_SCOPE | BLOCKED | NEED_CONTEXT>

TEST SUMMARY

Purpose:
<1~3줄. 검증 이유>

Target:
<1~3줄. 검증한 테스트, 파일, 클래스, 모듈 또는 동작>

Method:
<1~3줄. 검증 방법>

Result:
<1~3줄. 관찰한 결과>


ISSUES

Issue:
<1~3줄. 새로 발견한 문제 또는 사실>

Response:
<1~3줄. Worker가 취한 대응>

Impact:
<1~3줄. 검토하거나 후속 처리할 영향>

<발견 사항이 없으면 None.>


FILES

Modified:
- <경로 또는 None>

Added:
- <경로 또는 None>

Deleted:
- <경로 또는 None>
```
