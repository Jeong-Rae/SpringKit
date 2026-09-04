# sk-1: 최소 실행형 생성기

## Goal

하나의 명령으로 고정된 최소 프로젝트 디렉터리를 생성하는 빌드·테스트·실행 가능한 Kotlin/JVM 애플리케이션을 제공한다.

## 선행 Task

없음.

## 구현 정책

- SpringKit 애플리케이션과 테스트는 Kotlin으로 구현한다.
- JVM group은 `me.jeongrae`, package root는 `me.jeongrae.springkit`을 사용한다.
- Java식 SAM interface는 contract를 보존할 수 있다면 Kotlin `fun interface`로 표현한다.
- Java static utility나 불필요한 holder class 대신 Kotlin top-level function과 함수 참조를 우선한다.
- Java API와 연동할 때도 Kotlin의 null-safety, 불변 값, 표준 library extension을 사용해 경계를 명시한다.

## Step

### 1. Gradle 애플리케이션 기반 구성

- Kotlin/JVM 소스와 테스트 source set을 구성한다.
- `me.jeongrae.springkit`에 Kotlin top-level 애플리케이션 진입점을 추가한다.
- 검증: `./gradlew build`와 애플리케이션 실행이 성공한다.
- 커밋: `Kotlin 실행 애플리케이션 기반 구성`

### 2. Project root 생성 contract 구현

- 명시된 출력 경로에 project root를 준비하는 책임을 분리한다.
- 기존 파일을 암묵적으로 덮어쓰지 않는 실패 규칙을 둔다.
- 검증: 빈 경로, 기존 경로, 생성 불가능한 경로의 단위 테스트가 통과한다.
- 커밋: `프로젝트 루트 생성 추가`

### 3. 최소 GenerationAction과 runner 구현

- `projectRoot`에 한 가지 변경을 적용하는 action contract를 Kotlin `fun interface`로 정의한다.
- 여러 action을 정해진 순서로 실행하는 runner를 구현한다.
- 검증: action 호출 순서와 실패 전파 단위 테스트가 통과한다.
- 커밋: `생성 작업 실행기 추가`

### 4. 고정 프로젝트 생성 연결

- 기본 파일 하나를 쓰는 action을 애플리케이션 진입점에 연결한다.
- 검증: 실행 후 예상 디렉터리와 파일이 존재하고 내용이 일치한다.
- 커밋: `고정 시작 프로젝트 생성`

## 완료 조건

- `./gradlew build`가 성공한다.
- 독립된 임시 경로에서 명령을 실행해 고정 프로젝트를 생성할 수 있다.
- 생성 실패는 non-zero 종료 또는 명시적 예외로 관찰할 수 있다.

## 비범위

- 사용자별 project metadata
- convention과 dependency 정책
- archive 출력
