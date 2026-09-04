# sk-7: 요청별 Generation Context

## Goal

각 생성 요청의 description, policy, plan, resource lifecycle을 다른 요청과 격리한다.

## 선행 Task

- sk-4

## Step

### 1. GenerationContext 정의

- 요청 하나의 description과 policy/action registry를 보유하는 일반 객체를 구현한다.
- 검증: context가 요청 범위의 객체만 노출하는 단위 테스트가 통과한다.
- 커밋: `Feature: add generation context`

### 2. 요청별 Context factory 구현

- 생성 호출마다 새 context를 만든다.
- 검증: 연속된 두 요청 사이에서 변경 가능한 상태가 공유되지 않는다.
- 커밋: `Feature: isolate generation contexts`

### 3. Description 확정 순서 적용

- policy 적용을 완료한 description으로만 action을 활성화한다.
- 검증: policy 적용 전후 조건이 달라지는 action 선택 테스트가 통과한다.
- 커밋: `Feature: finalize description before planning`

### 4. Context 종료 처리

- 성공과 예외 경로 모두에서 요청별 resource를 닫는다.
- 검증: 정상/실패 경로의 close 호출 테스트가 통과한다.
- 커밋: `Feature: manage generation lifecycle`

### 5. 동시 생성 격리 검증

- 서로 다른 description으로 동시에 생성하는 통합 테스트를 추가한다.
- 검증: 결과 파일과 context 상태가 요청 사이에 섞이지 않는다.
- 커밋: `Test: verify concurrent generation isolation`

## 완료 조건

- context가 전역 mutable state에 의존하지 않는다.
- 최종 description 확정이 action plan 작성보다 항상 먼저 일어난다.
- 동시 요청 결과가 완전히 격리된다.

## 비범위

- Spring ApplicationContext 또는 DI framework 도입
- CLI option 설계
