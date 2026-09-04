# sk-3: Convention 정책

## Goal

작은 사용자 입력을 ordered policy로 확장해 완전한 `ProjectDescription`을 만든다.

## 선행 Task

- sk-2

## Step

### 1. Sparse input 경계 정의

- 사용자가 생략할 수 있는 값과 반드시 제공해야 하는 값을 명시한다.
- 검증: 최소 입력과 완전 입력의 변환 테스트가 통과한다.
- 커밋: `Feature: support sparse project input`

### 2. DescriptionPolicy contract 구현

- description 후보에 한 가지 정책을 적용하는 contract를 정의한다.
- 검증: policy가 명시된 필드만 변경하는 단위 테스트가 통과한다.
- 커밋: `Feature: add description policy contract`

### 3. 기본값 정책 추가

- language, build system, group, artifact, version 등 도구 소유 기본값을 적용한다.
- 사용자가 제공한 값을 기본값이 덮어쓰지 않게 한다.
- 검증: 필드별 생략/명시 조합 테스트가 통과한다.
- 커밋: `Feature: apply project defaults`

### 4. 파생값 정책 추가

- artifact/name에서 package와 application name을 파생한다.
- 검증: 정상 이름과 정규화가 필요한 이름의 결과를 테스트한다.
- 커밋: `Feature: derive project conventions`

### 5. Policy ordering 고정

- 기본값 적용 후 파생값이 계산되도록 order를 명시한다.
- 검증: 등록 순서와 무관하게 동일한 최종 description이 만들어진다.
- 커밋: `Feature: order description policies`

## 완료 조건

- 문서화된 최소 입력만으로 완전한 description이 생성된다.
- 사용자 입력과 tool-owned convention의 책임이 분리된다.
- 동일 입력은 항상 동일한 description을 만든다.

## 비범위

- action 선택
- dependency 버전 호환성
