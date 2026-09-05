# Task와 Step 관리 명세

이 문서는 Task와 Step의 경계, Task DAG와 관리 스크립트의 계약을 정의합니다. 원본 요구사항은 작업자가 제공한 `task spec.txt`을 기준으로 하며, 구현이 자동으로 판정할 수 있는 규칙을 아래에 정리합니다.

## 작업 모델

Task는 정확히 하나의 결과 중심 Goal을 달성하는 배포 가능한 단위입니다. Task는 한 개 이상의 Step으로 구성합니다. Step은 독립적으로 실행하고 검증할 수 있는 순차 작업이며 별도 Graph를 구성하지 않습니다.

Task Graph의 Node에는 Task만 사용합니다. `depends_on`에는 실제 결과가 필요한 직접 선행 Task만 기록합니다. 같은 모듈이나 같은 SPEC이라는 이유만으로 dependency를 만들지 않습니다.

## Task Card 계약

Task Card는 `tasks/<task-id>.yaml`에 저장합니다. Task ID는 `sk-<양의 정수>`, Step ID는 `<task-id>-<1부터 시작하는 연속 순번>` 형식입니다.

Task 필드 순서는 다음과 같습니다.

```text
id, title, type, status, goal, depends_on, spec_refs, scope,
verification, issues, references, steps
```

Step 필드 순서는 다음과 같습니다.

```text
id, title, status, objective, work, verification, issues, references
```

Task Type은 `feature`, `fix`, `refactor`, `ci`, `dep` 중 하나입니다. Task와 Step 상태는 `pending`, `running`, `verifying`, `blocked`, `done`, `cancelled` 중 하나입니다.

`feature`와 `fix`는 구현 기준인 SPEC을 참조해야 합니다. Requirement 식별자가 없으면 `SPEC`을 사용합니다. 존재하지 않는 Requirement 식별자를 만들지 않습니다.

## 상태와 검증 규칙

모든 Task와 Step에는 한 개 이상의 Verification이 필요합니다. Step은 선언 순서대로 실행합니다. 이전 Step이 `done`이 아니면 다음 Step을 `running`, `verifying`, `done`으로 전환할 수 없습니다.

Task를 `done`으로 전환하려면 모든 Step이 `done`이어야 합니다. 실제 Verification의 성공 여부는 실행 결과로 확인하며 YAML 상태만으로 추정하지 않습니다.

## DAG 규칙

모든 dependency는 존재하는 Task를 가리켜야 합니다. 자기 의존, 중복 dependency와 cycle은 허용하지 않습니다. 이미 다른 직접 선행 Task를 통해 충족되는 전이 dependency도 `depends_on`에 기록하지 않습니다.

새로운 Task ID는 `issue-task-id.ts`로 계산합니다. Task 등록과 dependency 추가는 `add-task.ts`로 수행합니다. 단일 Card는 `lint-task.ts`, 전체 DAG는 `validate-tasks.ts`로 검증합니다.

자연어 Goal과 Objective가 결과를 표현하는지 여부처럼 기계적으로 판단할 수 없는 규칙은 작업 검토에서 확인합니다.
