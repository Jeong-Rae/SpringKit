# Worker 교섭 스키마

구현을 시작하기 전에 다음 형식으로 응답합니다.

```text
WORKER_NEGOTIATION

STATUS
<READY | NEED_CONTEXT | OUT_OF_SCOPE | BLOCKED>

UNDERSTANDING
<Worker가 이해한 책임과 요구사항>

EXPECTED CHANGES
<예상하는 구현 영역과 동작 변화>

CONTRACTS
<유지해야 하는 인터페이스와 불변 조건>

RISKS
<알고 있는 구현 위험 또는 None.>

QUESTIONS
<오케스트레이터의 답이나 판단이 필요한 질문 또는 None.>
```
