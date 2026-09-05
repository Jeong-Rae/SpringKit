# Task 분해와 Task Graph 구성 명세

Task 분해 및 Task Graph 구성 Instruction
1. 작업 간단 설명
이 Instruction은 입력된 SPEC과 프로젝트 정보를 분석하여 배포 가능한 Task로 분해하고, Task 사이의 선행 관계를 DAG(Directed Acyclic Graph)로 구성하는 규칙을 정의한다.
작업 모델은 Task와 Step의 두 계층으로 구성한다.

계층
책임

Task
정확히 하나의 Goal을 달성하며 배포 가능성을 보장한다.

Step
하나의 Task를 구성하며 독립적인 실행 가능성과 검증 가능성을 보장한다.


전체 구조는 다음과 같다.
text
SPEC / Project Context
        ↓
     Task DAG
        ↓
      Task
        ↓
   Step Sequence

Task Graph의 Node는 Task만 사용한다.
Step은 Task 내부의 순차 실행 단위이며 별도의 Graph를 구성하지 않는다.
규범 표현
[Def]는 개념을 정의한다.
[Must]와 [Must Not]은 반드시 준수해야 하는 규칙이다.
[Should]는 특별한 사유가 없다면 준수해야 하는 규칙이다.
[Must] 또는 [Must Not]을 위반한 결과는 유효한 Task 구성으로 간주하지 않는다.
정보 표현 규칙
[Must] 설명 없이 동일 수준의 정보를 단순 나열하는 경우 최대 3개까지만 나열해야 한다.
4개 이상의 정보가 필요하면 의미에 따라 그룹화하거나 표, Schema 또는 계층 구조로 표현해야 한다.
Enum, 상태 집합, Schema처럼 전체 항목 자체가 계약인 경우에는 이 제한을 적용하지 않는다.

───

2. Task 정의
2.1 Task
[Def] Task란 정확히 하나의 Goal을 달성하기 위한 작업 단위이며, 완료된 결과는 배포 가능성을 보장한다.
여기서 배포 가능성이란 실제 운영 환경에 배포가 완료되었다는 의미가 아니다.
Task의 모든 선행 조건이 충족된 상태에서 해당 Task를 완료하면, 후속 Task가 추가로 구현되지 않아도 현재 Task의 Goal이 완전하게 성립하고 프로젝트가 정한 전달 또는 배포 절차를 진행할 수 있어야 한다.
[Must] 하나의 Task는 정확히 하나의 Goal만 가져야 한다.
[Must] 하나의 Task는 한 개 이상의 Step으로 구성해야 한다.
[Must] Goal은 구현 방법이 아니라 Task 완료 후 성립하는 결과를 표현해야 한다.
[Must Not] 후속 Task가 완료되어야만 현재 Task의 Goal이 성립하는 작업을 독립적인 Task로 정의해서는 안 된다.
허용 가능한 예
Task
sk-24 주문 취소 기능 제공
Goal
사용자는 배송이 시작되기 전에 주문을 취소할 수 있다.
Task 자체를 완료하면 하나의 기능적 Goal이 완성된다.
허용 불가능한 예
Task
sk-24 주문 취소 인터페이스 추가
Goal
후속 Task에서 주문 취소 구현을 작성할 수 있도록 인터페이스를 만든다.
이 작업은 후속 구현 없이는 사용자 또는 시스템 관점의 Goal을 완성하지 못하므로 Task 경계로 부적절하다.

───

2.2 Task Type
[Def] Task Type은 해당 Task가 프로젝트에 가하는 변경의 성격을 나타낸다.
하나의 Task는 정확히 하나의 Type을 가진다.

Type
정의
SPEC 관계

feature
SPEC을 반영하기 위한 기능 추가 또는 기능 변경
SPEC 참조 필요

fix
현재 구현을 SPEC에 정의된 정상 동작으로 복구
SPEC 참조 필요

refactor
외부 동작을 유지하면서 내부 구조를 변경
SPEC과 독립 가능

ci
빌드, 배포, 테스트 자동화 또는 인프라를 변경
SPEC과 독립 가능

dep
프로젝트 의존성을 추가, 제거 또는 변경
SPEC과 독립 가능


[Must] Task는 정확히 하나의 Type만 가져야 한다.
[Must Not] 하나의 Task에 서로 다른 변경 목적의 Type을 동시에 부여해서는 안 된다.
허용 가능한 예
yaml
type: feature

SPEC에 새롭게 정의된 주문 취소 기능을 구현한다.
허용 불가능한 예
yaml
type:
  - feature
  - refactor

독립적인 리팩터링 Goal이 존재한다면 별도의 Task로 분리해야 한다.

───

2.3 SPEC 참조
[Must] feature와 fix Task는 구현 또는 복구의 기준이 되는 SPEC을 추적할 수 있어야 한다.
SPEC에 Requirement 식별자가 존재하면 해당 Task와 직접 관련된 Requirement를 참조한다.
허용 가능한 예
yaml
spec_refs:
  - REQ-003
  - REQ-007

SPEC에 개별 Requirement 식별자가 존재하지 않는 경우 전체 SPEC을 참조한다.
yaml
spec_refs:
  - SPEC

[Must Not] Task를 생성하기 위해 존재하지 않는 Requirement 식별자를 임의로 생성해서는 안 된다.
refactor, ci, dep처럼 SPEC과 무관한 작업은 SPEC 참조를 갖지 않을 수 있다.
yaml
spec_refs: []


───

2.4 Task Verification
[Def] Task Verification은 모든 Step을 완료한 결과 Task의 Goal이 최종적으로 충족되었는지를 판정하는 조건이다.
[Must] 모든 Task는 하나 이상의 Verification을 가져야 한다.
[Must] Task Verification은 전체 Task 결과를 기준으로 판단할 수 있어야 한다.
[Must Not] 각 Step의 Verification을 그대로 반복해서 Task Verification으로 사용해서는 안 된다.
허용 가능한 예
배송 전 주문 취소 동작이 전체 시스템 경계에서 정상적으로 제공된다.
허용 불가능한 예
클래스가 생성되었다.
구현 세부사항만 확인하며 Task Goal 달성을 검증하지 않는다.

───

2.5 Task 내부 정보
Task Card의 Task 수준 정보는 다음 영역으로 구성한다.

영역
정보

Identity
id, title, type, status

Intent
goal, spec_refs

Boundary
scope, depends_on, references, issues

Completion
verification, steps


[Must] goal은 배열이 아니라 단일 값이어야 한다.
[Must] depends_on에는 Task Graph의 직접 선행 Task만 기록해야 한다.

───

3. Step 정의
3.1 Step
[Def] Step이란 하나의 Task를 구성하는 하위 작업 단위이며, 독립적인 실행 가능성과 검증 가능성을 보장한다.
Step은 배포 가능성을 보장하지 않는다.
Step은 Task의 Goal을 완성하기 위한 의미 있는 중간 결과를 만든다.
[Must] 각 Step은 정확히 하나의 Objective를 가져야 한다.
[Must] 각 Step은 독립적으로 실행할 수 있을 만큼 작업 범위가 명확해야 한다.
[Must] 각 Step은 자체 Verification으로 완료 여부를 판정할 수 있어야 한다.
[Must Not] Step을 Git commit, branch, PR 또는 기타 형상관리 단위로 정의해서는 안 된다.

───

3.2 Step Objective
[Def] Objective는 해당 Step이 완료되었을 때 확보해야 하는 중간 결과를 표현한다.
허용 가능한 예
Step
sk-24-1 주문 취소 정책 구현
Objective
배송 상태를 기준으로 주문 취소 가능 여부를 판정할 수 있다.
허용 불가능한 예
Objective
CancellationPolicy.java를 생성한다.
구현 방법만 표현하며 의미 있는 중간 결과를 정의하지 않는다.

───

3.3 Step Verification
[Must] 모든 Step은 하나 이상의 Verification을 가져야 한다.
[Must] Verification은 현재 Step만 수행한 상태에서 결과를 판정할 수 있어야 한다.
[Must] 현재 Step의 Verification이 성공한 이후에만 다음 Step을 진행해야 한다.
허용 가능한 예
배송 시작 전 주문은 취소 가능으로 판정되고 배송 시작 후 주문은 취소 불가능으로 판정된다.
허용 불가능한 예
구현이 정상적으로 되었는지 확인한다.
성공과 실패를 판단할 명확한 기준이 없다.

───

3.4 Step 실행 순서
[Def] Task Card에 선언된 Step의 순서는 실제 실행 순서를 의미한다.
예:
text
sk-24-1
   ↓
Verify
   ↓
sk-24-2
   ↓
Verify
   ↓
sk-24-3

[Must] Step은 선언된 순서대로 실행해야 한다.
[Must] 이전 Step이 성공적으로 검증되기 전에는 다음 Step을 시작해서는 안 된다.
[Must Not] Step별 depends_on을 정의하거나 별도의 Step Graph를 만들어서는 안 된다.
Task 내부에서 병렬 실행해야 할 정도로 독립적인 작업이 발견되면 별도의 Task로 분리할 수 있는지 다시 판단해야 한다.

───

3.5 Step 내부 정보
Step은 다음 영역으로 구성한다.

영역
정보

Identity
id, title, status

Execution
objective, work

Completion
verification, issues, references


work는 Objective를 달성하기 위해 수행해야 할 의미 있는 작업 범위를 표현한다.
허용 가능한 예
yaml
work:
  - 배송 상태를 기반으로 주문 취소 가능 여부를 판정하는 도메인 동작을 구현한다.

허용 불가능한 예
yaml
work:
  - 파일을 연다.
  - 메서드를 추가한다.
  - if 문을 작성한다.

구현 절차를 지나치게 세분화하고 있으며 Step의 의미 있는 작업 범위를 표현하지 않는다.

───

4. Task-Step 네이밍 규칙
4.1 Project ID
[Def] Project ID는 Task 식별자의 prefix로 사용하는 프로젝트 식별자다.
SpringKit의 Project ID는 다음과 같다.
text
sk


───

4.2 Task ID
Task ID는 다음 형식을 사용한다.
text
<project-id>-<task-sequence>

예:
text
sk-1
sk-2
sk-204

[Must] Task 순번은 프로젝트 내부에서 유일해야 한다.
[Must] 외부에서 이미 Task ID가 제공된 경우 해당 ID를 변경하지 않고 사용해야 한다.
[Must] 새로운 Task ID가 필요한 경우 issue-task-id.ts를 사용해야 한다.
[Must Not] Task ID의 숫자를 실행 순서 또는 DAG 순서로 해석해서는 안 된다.
허용 가능한 예
text
sk-204 → sk-37

sk-37이 sk-204의 결과를 필요로 한다면 유효한 dependency다.
허용 불가능한 예
sk-37의 번호가 작으므로 sk-204보다 먼저 실행한다.
Task 번호를 실행 순서로 해석하고 있다.

───

4.3 Step ID
Step ID는 다음 형식을 사용한다.
text
<task-id>-<step-sequence>

예:
text
sk-1-1
sk-1-2
sk-1-3

sk-204-1

[Must] Step ID는 자신이 속한 Task ID를 prefix로 사용해야 한다.
[Must] Step 순번은 1부터 시작해야 한다.
[Must] Step 순번은 Task Card에 선언된 실행 순서와 동일하게 연속적으로 증가해야 한다.
허용 가능한 예
text
Task sk-12

sk-12-1
sk-12-2
sk-12-3

허용 불가능한 예
text
Task sk-12

sk-12-1
sk-13-2

Step의 상위 Task ID가 일치하지 않는다.
허용 불가능한 예
text
sk-12-1
sk-12-3
sk-12-2

Step ID와 실행 순서가 일치하지 않는다.

───

5. Task 분리 규칙
5.1 Task와 Step 경계
Task와 Step을 구분할 때 다음 세 기준을 순서대로 적용한다.
Rule 1. Goal이 다른가?
서로 다른 Goal을 달성한다면 별도의 Task로 분리한다.
Rule 2. 독립적으로 배포 가능한가?
하나의 변경이 후속 Task 없이 완전한 Goal을 달성하고 독립적인 전달 단위가 될 수 있다면 Task로 분리할 수 있다.
Rule 3. 하나의 Goal을 완성하기 위한 과정인가?
동일한 Goal을 달성하기 위해 순차적으로 수행해야 하는 검증 가능한 작업이라면 하나의 Task 내부 Step으로 구성한다.

───

허용 가능한 예
text
sk-30 주문 취소 기능 제공
  sk-30-1 주문 취소 도메인 정책 구현
  sk-30-2 주문 취소 Application 동작 구현
  sk-30-3 주문 취소 API 통합

세 Step은 각각 실행하고 검증할 수 있지만 모두 하나의 Goal을 완성한다.
허용 불가능한 예
text
sk-30 주문 취소 정책 구현
sk-31 주문 취소 Application 구현
sk-32 주문 취소 API 구현

세 작업이 각각 독립적인 배포 가능한 Goal을 제공하지 못하고 하나의 기능을 완성하기 위한 순차 과정이라면 Task가 아니라 Step으로 표현해야 한다.

───

5.2 Step 완전성
[Must] Task를 완료하는 데 필요한 의미 있는 작업을 한 개 이상의 Step으로 완전히 분해해야 한다.
[Must] Task의 실제 구현에 필요하지만 어느 Step에도 포함되지 않은 숨겨진 작업이 존재해서는 안 된다.
[Must] 각 Step은 이전 Step이 검증된 상태를 전제로 다음 Step으로 자연스럽게 이어질 수 있어야 한다.

───

5.3 Task Dependency
[Def] Task dependency는 한 Task가 다른 Task의 완료 결과를 prerequisite로 요구하는 관계다.
Task B가 Task A의 완료 결과를 반드시 필요로 하면 다음과 같이 표현한다.
text
A → B

[Must] dependency는 실제 선행 결과가 필요한 경우에만 생성해야 한다.
[Must Not] 같은 모듈, 같은 SPEC 또는 같은 기능 영역이라는 이유만으로 dependency를 생성해서는 안 된다.
Task dependency는 add-task.ts를 통해서만 추가한다.
AI가 Task Card의 depends_on을 직접 변경해서는 안 된다.

───

5.4 직접 Dependency
depends_on에는 직접적인 prerequisite만 포함해야 한다.
다음 Graph가 있다고 가정한다.
text
A → B → C

허용 가능한 예
text
C depends_on B

허용 불가능한 예
text
C depends_on A, B

A는 B를 통해 이미 전이적으로 충족된다.

───

5.5 병렬 Task
서로의 완료 결과를 필요로 하지 않는 Task는 sibling Task로 구성할 수 있다.
text
sk-10
       /   \
      ↓     ↓
   sk-11   sk-12

sk-11과 sk-12가 서로 dependency를 가지지 않으면 sk-10 완료 후 독립적으로 실행할 수 있다.
[Must Not] Task 내부 Step을 이러한 방식으로 병렬 DAG로 구성해서는 안 된다.

───

5.6 Cycle 금지
Task Graph에는 cycle이 존재해서는 안 된다.
허용 불가능한 예
text
sk-1 → sk-2 → sk-3 → sk-1

Cycle 여부는 최종적으로 validate-tasks.ts를 통해 검증한다.

───

6. Task File 작성 형식
각 Task는 하나의 Task Card 파일로 표현한다.
Task Card는 하나의 Goal, Task의 실행 경계, 순차 Step과 최종 완료 조건을 함께 정의한다.
Task Card의 정확한 canonical format과 단일 Card 수준의 기계적 유효성은 lint-task.ts가 관리한다.

───

6.1 Task Card 구조
Task Card는 다음 네 영역으로 구성한다.

영역
책임

Metadata
Task의 식별자, Type과 상태를 정의한다.

Contract
Goal, SPEC 참조, Scope와 Task Verification을 정의한다.

Steps
Task를 순차 실행 가능한 Step으로 분해한다.

Context
Reference와 Issue 등 추가 실행 정보를 정의한다.



───

6.2 Task Card 예시
yaml
id: sk-30
title: 주문 취소 기능 제공
type: feature
status: pending

goal: >
  사용자는 배송이 시작되기 전에 주문을 취소할 수 있다.

depends_on:
  - sk-12

spec_refs:
  - REQ-003
  - REQ-004

scope:
  in:
    - 주문 취소 정책
    - 주문 상태 변경
    - 예약 재고 복구
  out:
    - 결제 취소
    - 주문 취소 UI

verification:
  - 배송 시작 전 주문 취소가 성공한다.
  - 배송 시작 이후 주문 취소가 거부된다.
  - 전체 변경 결과가 배포 가능한 상태다.

issues: []

references:
  - docs/order-domain.md

steps:
  - id: sk-30-1
    title: 주문 취소 정책 구현
    status: pending
    objective: >
      배송 상태를 기준으로 주문 취소 가능 여부를 판정할 수 있다.
    work:
      - 주문 취소 가능 여부를 표현하는 도메인 동작을 구현한다.
    verification:
      - 배송 시작 전 주문은 취소 가능으로 판정된다.
      - 배송 시작 후 주문은 취소 불가능으로 판정된다.
    issues: []
    references: []

  - id: sk-30-2
    title: 주문 취소 Application 동작 구현
    status: pending
    objective: >
      취소 가능한 주문의 상태를 변경하고 관련 후속 동작을 수행할 수 있다.
    work:
      - 주문 취소 use case를 구현한다.
    verification:
      - 취소 성공 시 주문 상태가 취소 상태가 된다.
    issues: []
    references: []

위 예시는 의미 구조를 설명하기 위한 예시다.
실제 필드 순서와 formatting은 lint-task.ts가 정한 canonical format을 따른다.

───

6.3 Task 상태
Task 상태는 다음 Enum으로 제한한다.
text
pending | running | verifying | blocked | done | cancelled

runnable은 Task 상태로 저장하지 않는다.
Runnable 여부는 Task Graph에서 계산한다.
text
Task.status == pending
AND
모든 직접 dependency의 status == done


───

6.4 Step 상태
Step 상태는 다음 Enum으로 제한한다.
text
pending | running | verifying | blocked | done | cancelled

[Must] 이전 Step이 done이 아니면 다음 Step을 running, verifying 또는 done 상태로 전환해서는 안 된다.
[Must] Step Verification이 성공하지 않았다면 해당 Step을 done으로 처리해서는 안 된다.
[Must] Task가 done이면 모든 Step도 done이어야 한다.

───

6.5 Task 완료 조건
Task는 다음 조건이 모두 충족되었을 때만 done으로 처리할 수 있다.
첫째, 모든 Step이 done이어야 한다.
둘째, 모든 Step Verification이 성공해야 한다.
셋째, Task Verification이 성공해야 한다.
프로젝트별 전달, 배포 또는 형상관리 절차는 이 Task-Step 모델과 별개의 프로젝트 규칙으로 적용한다.

───

7. Task DAG 검증 스크립트
Task 시스템에서 기계적으로 처리할 수 있는 작업은 지정된 스크립트를 사용한다.
이 Instruction에서는 스크립트의 책임과 사용 조건만 정의하며 실제 구현은 정의하지 않는다.
issue-task-id.ts
새로운 Task ID를 발급한다.
외부에서 이미 Task ID가 주어진 경우에는 해당 ID를 그대로 사용한다.
Step ID는 상위 Task ID와 Step 순서로 결정하므로 별도의 ID 발급 대상이 아니다.

───

add-task.ts
Task Graph에 직접 dependency를 추가한다.
이 스크립트는 Task와 Task 사이의 edge만 관리한다.
Step 관계를 관리하는 용도로 사용해서는 안 된다.

───

lint-task.ts
개별 Task Card의 Schema, 로컬 불변식과 canonical format을 검증한다.
검증 책임은 다음과 같이 구분한다.

영역
주요 검증

Task
단일 Goal, Type, SPEC 관계와 필수 정보

Step
Step 존재 여부, Step ID와 순서, Objective와 Verification

Local State
Task와 Step 내부 상태의 일관성


lint-task.ts는 전체 Task Graph의 topology를 판단하지 않는다.

───

validate-tasks.ts
전체 Task Card를 읽어 Task Graph의 유효성을 검증한다.
검증 책임은 다음과 같다.

영역
주요 검증

Reference
dependency 대상 Task의 존재 여부

DAG
cycle, self dependency, 전이적으로 중복된 dependency

Scheduling
Root, topological layer, Runnable Task


Step은 Task Graph의 Node가 아니므로 DAG 검증 대상에 포함하지 않는다.

───

7.1 Task 작성 절차
Task 구성은 다음 순서를 따른다.
text
SPEC / Project Context 분석
        ↓
Task Goal 결정
        ↓
Task와 Step 경계 결정
        ↓
Task ID 확정
        ↓
Task Card 작성
        ↓
lint-task.ts
        ↓
add-task.ts
        ↓
validate-tasks.ts
        ↓
완료

새로운 Task ID가 필요한 경우에만 issue-task-id.ts를 사용한다.

───

완료 조건
Task 분해와 Task Graph 구성이 완료되려면 세 수준의 조건을 모두 충족해야 한다.
Task 수준
각 Task는 정확히 하나의 Goal을 가지며, 완료된 결과는 후속 Task에 의존하지 않는 배포 가능한 변경 단위를 형성해야 한다.
Step 수준
각 Task는 하나 이상의 순차 Step으로 완전히 분해되어 있어야 하며, 모든 Step은 독립적으로 실행하고 검증할 수 있어야 한다.
Graph 수준
Task dependency는 실제 prerequisite만 표현해야 하며, 전체 Task Graph는 유효한 DAG여야 한다.
Task Card 자체의 적합성은 lint-task.ts, Task Graph의 적합성은 validate-tasks.ts의 결과를 기준으로 판단한다.
