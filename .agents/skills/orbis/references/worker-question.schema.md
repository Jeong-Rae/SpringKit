# Worker 질의 스키마

파일을 수정하기 전에 다음 형식으로 응답합니다.

질문이 있으면 `QUESTION`을 먼저 적습니다. 각 자유 서술 필드는 2줄을 기본으로 쓰며 1~3줄 안에서 끝냅니다.

```text
WORKER_QUESTION

STATUS
<QUESTION | READY | OUT_OF_SCOPE | BLOCKED>

QUESTION
<1~3줄. 오케스트레이터의 답이나 판단이 필요한 질문. READY이면 None.>

UNDERSTANDING
<1~3줄. 질문과 관련해 Worker가 현재 이해한 사실, 책임, 경계>

QUESTION INTENT
<1~3줄. 무엇을 확정하려는 질문인지, 답이 어떤 판단을 결정하는지>
```

`QUESTION`이면 질문을 하나만 적습니다. 답을 받은 뒤 추가 질문이 있으면 다음 응답에서 묻습니다.

`READY`이면 `QUESTION`에 `None.`을 적고, `UNDERSTANDING`과 `QUESTION INTENT`에는 실행 전에 확인한 핵심 판단을 짧게 적습니다.
