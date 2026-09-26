---
name: orbis
description: >-
  하나의 커밋 클로저 안에서 오케스트레이터와 범위가 명확히 정해진 Worker를
  조율해 코딩 작업을 자동화한다. 코딩 요청을 독립적인 Worker 책임으로 나누고,
  실행 전에 범위와 계약을 확인한 뒤 native Codex subagent에 구현을 위임하며,
  오케스트레이터가 실제 저장소 상태를 검증해 논리적 커밋이 완결될 때까지
  후속 작업을 조정해야 할 때 사용한다.
---

# Orbis

버전: 0.0.1

하나의 논리적 커밋을 완결하는 범위에서 코딩 작업을 조율합니다. 오케스트레이터는 변경의 의미와 경계를 통제하고, Worker는 승인된 책임 안에서 구현과 검증을 수행합니다.

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

모든 Worker는 `gpt-6-luna`와 `xhigh` reasoning을 사용하며, 오케스트레이터의 대화 기록을 상속하지 않습니다.

교섭이 승인되면 같은 Worker에 후속 작업을 전달합니다.

```text
followup_task(
  target="<TASK ID>",
  message="PROCEED"
)
```

승인된 책임 안에서 수정이나 추가 작업이 필요하면 같은 Worker를 이어서 사용합니다.

## 역할 경계

- 하위 agent는 이 Skill 전체에서 Worker라고 부릅니다.
- Worker는 구현 전에 적용되는 저장소 지침을 직접 읽어야 합니다.
- Worker가 다른 Worker나 subagent를 생성하지 않게 합니다.
- Worker는 승인된 책임과 소유권을 임의로 넓히지 않습니다.
- Worker는 명시적으로 허용받지 않은 공유 Git 상태를 변경하지 않습니다.
- 오케스트레이터는 production code, test code, integration code를 직접 구현하지 않고 Worker에 위임합니다.

## 커밋 클로저

하나의 오케스트레이션 단위는 하나의 논리적 커밋 범위를 넘지 않아야 합니다.

커밋 클로저(commit closure)는 하나의 논리적 변경을 완결하고 독립적으로 검토할 수 있게 만드는 데 필요한 변경의 집합입니다. 현재 목표를 완결하는 데 새로운 책임이 필요하면 같은 커밋 클로저 안에서 Worker를 추가하거나 기존 책임을 조정합니다. 별개의 변경 이유를 가진 작업이 발견되면 현재 오케스트레이션에서 분리합니다.

Worker 경계와 커밋 경계를 동일하게 취급하지 않습니다. 하나의 논리적 커밋은 여러 Worker의 순차 또는 병렬 작업으로 구성될 수 있습니다.

## 참고 문서

위임과 수용 판단에는 다음 문서를 사용합니다.

- [위임 정책](references/delegation-policy.md)
- [수용 정책](references/acceptance-policy.md)

Worker와 주고받는 메시지의 의미와 정확한 형식은 다음 문서를 사용합니다.

- [Worker 요청](references/worker-request.md)
- [Worker 요청 스키마](references/worker-request.schema.md)
- [Worker 교섭](references/worker-negotiation.md)
- [Worker 교섭 스키마](references/worker-negotiation.schema.md)
- [Worker 보고](references/worker-report.md)
- [Worker 보고 스키마](references/worker-report.schema.md)

영문 참고본은 [English version](en/skill.md)에 보관합니다.

## 작업 흐름

1. 사용자 요청과 적용되는 저장소 지침을 읽습니다.
2. 목표와 논리적 커밋 경계를 정합니다.
3. 위임 정책을 읽고 변경을 응집된 책임으로 나눈 뒤 의존 관계를 정합니다.
4. Worker 요청 문서와 스키마를 읽고 요청을 작성한 뒤 Worker 실행 계약에 따라 `spawn_agent(...)`를 호출합니다.
5. Worker가 파일을 수정하기 전에 준비 작업을 마치고 Worker 교섭 스키마에 맞는 응답을 반환하게 합니다.
6. 교섭 결과를 검토합니다. 잘못된 가정을 바로잡고, 필요한 정보를 제공하며, 소유권을 조정하거나 책임을 다시 배정합니다.
7. 책임과 경계가 충분히 합의된 경우에만 `followup_task(..., message="PROCEED")`를 호출합니다.
8. Worker가 승인된 책임 안에서 구현, 테스트, 자체 검토를 마치고 Worker 보고 스키마에 맞는 종료 응답을 반환하게 합니다.
9. 수용 정책을 읽고 실제 저장소 상태를 확인합니다.
10. 결과를 수용하거나, 같은 책임의 후속 작업을 요청하거나, 같은 커밋 클로저를 확장하거나, 독립 변경을 분리하거나, 책임을 다시 배정합니다.
11. 목표가 충족되고 커밋 클로저가 완결된 경우에만 작업을 끝냅니다.

## 제어 규칙

- 파일 수가 아니라 응집된 책임을 기준으로 위임합니다.
- 동시에 수정되는 파일에는 Worker 소유자를 하나만 둡니다.
- 한 Worker가 다른 Worker의 아직 완료되지 않은 변경을 입력으로 사용해야 하면 병렬로 실행하지 않습니다.
- 여러 Worker가 공유 인터페이스에 의존한다면 병렬 작업 전에 해당 계약을 확정합니다.
- integration 파일에는 Worker 소유자를 하나만 둡니다.
- Worker 보고는 주장으로 취급하고 실제 저장소 상태를 기준 정보로 사용합니다.
- 커밋, 게시, pull request와 같은 저장소 수준 작업은 Worker 밖에서 처리하고 저장소의 Git 작업 흐름을 따릅니다.
