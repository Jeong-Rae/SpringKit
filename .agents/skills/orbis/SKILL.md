---
name: orbis
description: >-
  하나의 커밋 클로저 안에서 오케스트레이터와 범위가 정해진 Worker를 조율해
  코딩 작업을 자동화한다. 코딩 요청을 Worker 책임으로 나누고 실행 전에 필요한
  질문과 답변으로 범위와 계약을 확인한 뒤 native Codex subagent에 구현을
  위임한다. 오케스트레이터가 실제 저장소 상태를 검증하고 커밋 클로저가
  완결될 때까지 후속 작업을 조정할 때 사용한다.
---

# Orbis

버전: 0.0.1

오케스트레이터는 변경의 의미와 경계를 맡고, Worker는 승인된 책임 안에서 구현과 검증을 맡습니다.

## Worker 실행

모든 Worker는 native Codex subagent로 생성합니다.

```text
spawn_agent(
  task_name="<TASK ID>",
  message="<COMPOSED WORKER REQUEST>",
  model="gpt-6-luna",
  reasoning_effort="xhigh",
  fork_turns="none"
)
```

모든 Worker는 `gpt-6-luna`와 `xhigh` reasoning을 사용하며 오케스트레이터의 대화 기록을 상속하지 않습니다.

Worker가 질문을 모두 해소하고 `READY`를 반환하면 오케스트레이터가 같은 Worker에 `PROCEED`를 보냅니다.

```text
followup_task(
  target="<TASK ID>",
  message="PROCEED"
)
```

같은 책임 안에서 수정이나 추가 작업이 필요하면 같은 Worker를 이어서 사용합니다.

## 역할 경계

- 하위 agent는 모두 Worker라고 부릅니다.
- Worker는 구현 전에 적용되는 저장소 지침을 읽습니다.
- Worker는 다른 Worker나 subagent를 생성하지 않습니다.
- Worker는 승인된 책임이나 소유권을 임의로 넓히지 않습니다.
- Worker는 허가받지 않은 공유 Git 상태를 변경하지 않습니다.
- 오케스트레이터는 프로덕션 코드, 테스트 코드, 통합 코드를 직접 작성하지 않습니다. 구현은 Worker에 위임합니다.

## 커밋 클로저

하나의 오케스트레이션 단위는 하나의 논리적 커밋 안에 둡니다.

커밋 클로저(commit closure)는 하나의 논리적 변경을 완결하고 독립적으로 검토하는 데 필요한 변경 전체입니다. 목표를 완결하려면 새 책임이 필요할 때 Worker를 추가하거나 기존 책임을 조정합니다. 별도의 변경 이유를 가진 작업은 현재 오케스트레이션에서 분리합니다.

Worker 경계와 커밋 경계는 다릅니다. 하나의 논리적 커밋은 여러 Worker의 순차 또는 병렬 작업으로 구성될 수 있습니다.

## 참고 문서

위임과 수용을 판단할 때 다음 문서를 읽습니다.

- [위임 정책](references/delegation-policy.md)
- [수용 정책](references/acceptance-policy.md)

Worker 메시지의 의미와 형식은 다음 문서를 따릅니다.

- [Worker 요청](references/worker-request.md)
- [Worker 요청 스키마](references/worker-request.schema.md)
- [Worker 질의](references/worker-question.md)
- [Worker 질의 스키마](references/worker-question.schema.md)
- [Worker 보고](references/worker-report.md)
- [Worker 보고 스키마](references/worker-report.schema.md)

영문 참고본은 [English version](en/skill.md)에 보관합니다.

## 작업 흐름

1. 사용자 요청과 적용되는 저장소 지침을 읽습니다.
2. 목표와 논리적 커밋 경계를 정합니다.
3. 위임 정책을 읽고 변경을 책임 단위로 나눈 뒤 의존 관계를 정합니다.
4. Worker 요청 문서와 스키마로 요청을 작성하고 Worker 실행 계약에 따라 `spawn_agent(...)`를 호출합니다.
5. Worker는 저장소와 준비 자료를 읽고 스스로 확정하기 어려운 사항이 있으면 Worker 질의 스키마에 맞춰 질문합니다.
6. 오케스트레이터는 질문에 답하고 잘못된 이해를 고치며 필요한 정보와 판단을 제공합니다.
7. Worker는 질문이 남아 있으면 다시 `QUESTION`을 반환하고, 모두 해소되면 `READY`를 반환합니다.
8. Worker가 `READY`를 반환하면 `followup_task(..., message="PROCEED")`를 호출합니다.
9. Worker는 승인된 책임 안에서 구현, 테스트, 자체 검토를 마치고 Worker 보고 스키마에 맞춰 종료 응답을 반환합니다.
10. 수용 정책을 읽고 실제 저장소 상태를 확인합니다.
11. 결과를 수용하거나, 같은 책임의 후속 작업을 요청하거나, 같은 커밋 클로저를 확장하거나, 독립 변경을 분리하거나, 책임을 다시 배정합니다.
12. 목표를 충족하고 커밋 클로저를 완결한 뒤 작업을 끝냅니다.

## 제어 규칙

- 파일 수가 아니라 응집된 책임을 기준으로 위임합니다.
- 동시에 수정하는 파일은 Worker 한 명만 소유합니다.
- 한 Worker가 다른 Worker의 미완료 변경을 입력으로 써야 하면 순차로 실행합니다.
- 여러 Worker가 같은 인터페이스에 의존하면 병렬 작업 전에 계약을 확정합니다.
- 통합 파일은 Worker 한 명이 소유합니다.
- Worker 보고는 주장으로 보고 실제 저장소 상태를 기준으로 판단합니다.
- 커밋, 게시, pull request는 Worker 밖에서 처리하고 저장소의 Git 작업 흐름을 따릅니다.
