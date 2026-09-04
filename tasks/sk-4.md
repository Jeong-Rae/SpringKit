# sk-4: Generation Plan

## Goal

최종 `ProjectDescription`에서 적용 가능한 action을 선택하고 안정적으로 정렬한 부작용 없는 generation plan을 만든다.

## 선행 Task

- sk-3

## Step

### 1. Action applicability 정의

- 각 action이 description에 적용 가능한지 판단하는 predicate를 추가한다.
- 검증: 조건별 활성/비활성 단위 테스트가 통과한다.
- 커밋: `적용 가능한 생성 작업 선택`

### 2. Action order 모델 정의

- action 우선순위와 동률 처리 규칙을 명시한다.
- 검증: 등록 순서를 바꿔도 실행 순서가 안정적인지 테스트한다.
- 커밋: `생성 작업 순서 고정`

### 3. GenerationPlan 조립

- 최종 description과 선택된 ordered action 목록을 plan으로 표현한다.
- 검증: 대표 description별 action 목록 snapshot 또는 구조 테스트가 통과한다.
- 커밋: `생성 계획 조립`

### 4. Plan과 materialization 분리

- plan 작성 중에는 filesystem을 변경하지 않고 runner 실행 시에만 변경한다.
- 검증: plan 생성 전후 filesystem 불변성과 실행 후 변경을 각각 테스트한다.
- 커밋: `계획과 물질화 분리`

### 5. 충돌 검출 추가

- 서로 양립할 수 없거나 동일 산출물을 소유한 action의 처리 규칙을 둔다.
- 검증: 충돌 plan이 실행 전에 거부되는지 테스트한다.
- 커밋: `생성 계획 충돌 검출`

## 완료 조건

- `Description → Plan` 단계가 filesystem과 독립적이다.
- plan의 action 집합과 순서가 결정적이다.
- 유효하지 않은 plan은 materialization 전에 실패한다.

## 비범위

- 구체적인 Gradle/source/resource 파일 전체
- 요청별 context lifecycle
