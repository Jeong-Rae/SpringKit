# sk-2: Canonical Project Description

## Goal

외부 입력을 검증된 canonical `ProjectDescription`으로 변환하고 생성 코어가 이 모델에만 의존하게 한다.

## 선행 Task

- sk-1

## Step

### 1. ProjectDescription 정의

- 프로젝트 생성에 필요한 최소 사실을 불변 모델로 정의한다.
- 검증: 정상 생성과 모델 불변 조건 단위 테스트가 통과한다.
- 커밋: `프로젝트 설명 정의`

### 2. 외부 입력과 converter contract 분리

- nullable/sparse 외부 입력 모델과 변환 contract를 정의한다.
- 검증: 입력값이 대응하는 description 필드로 변환되는지 테스트한다.
- 커밋: `설명 변환기 추가`

### 3. 식별자와 값 검증

- group, artifact, package, application name의 최소 형식 규칙을 적용한다.
- 검증: 유효값, 경계값, 잘못된 값 각각의 parameterized test가 통과한다.
- 커밋: `프로젝트 식별자 검증`

### 4. 생성 action을 description 기반으로 전환

- 고정값 참조를 제거하고 canonical description으로 경로와 내용을 결정한다.
- 검증: 같은 description은 같은 결과를 만들고 다른 description은 격리된 결과를 만든다.
- 커밋: `프로젝트 설명 기반 생성으로 전환`

## 완료 조건

- 생성 코어가 CLI parser나 raw string identifier를 직접 참조하지 않는다.
- 유효한 완전 입력으로 프로젝트 생성이 성공한다.
- 잘못된 입력은 filesystem 변경 전에 거부된다.

## 비범위

- 생략값의 default 적용
- dependency catalogue
