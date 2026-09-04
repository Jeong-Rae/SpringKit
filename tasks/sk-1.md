# sk-1: 최소 실행형 생성기

## Goal

하나의 명령으로 고정된 최소 프로젝트 디렉터리를 생성하는 빌드·테스트·실행 가능한 Kotlin/JVM 애플리케이션을 제공한다.

## 선행 Task

없음.

## 구현 정책

- SpringKit 애플리케이션과 테스트는 Kotlin으로 구현한다.
- JVM group은 `me.jeongrae`, package root는 `me.jeongrae.springkit`을 사용한다.
- `springkit/kit/` 아래에 `core`, `api`, `client`, `service`, `common` 멀티 모듈을 둔다.
- 도메인 객체는 데이터 클래스와 열거형으로 표현하고, 상태·속성 파일과 행위 파일을 분리한다.
- Java식 SAM 인터페이스는 규약을 보존할 수 있다면 Kotlin `fun interface`로 표현한다.
- Java 정적 유틸리티나 불필요한 보유 클래스 대신 Kotlin 최상위 함수와 함수 참조를 우선한다.
- 유스케이스는 파일당 하나를 두고 `operator fun invoke`와 필요한 중첩 `Command`, `Query`, `Result` 데이터 클래스로 구성한다.
- `SpringKit.kt`는 인수 확인, 객체 조립, 유스케이스 실행, 결과 출력만 담당한다.

## Step

### 1. Gradle 멀티 모듈 기반 구성

- Kotlin/JVM 소스와 테스트 소스 모음을 구성한다.
- `springkit/kit/` 아래에 키트 멀티 모듈을 구성한다.
- 검증: `./gradlew build`와 애플리케이션 실행이 성공한다.
- 커밋: `Build: SpringKit 키트 멀티 모듈 구성`

### 2. 핵심 도메인과 생성 행위 분리

- `ProjectRoot`를 상태만 가진 핵심 도메인으로 정의한다.
- 명시된 출력 경로에 프로젝트 루트를 준비하는 규약을 분리한다.
- 기존 파일을 암묵적으로 덮어쓰지 않는 실패 규칙을 둔다.
- 검증: 빈 경로, 기존 경로, 생성 불가능한 경로의 단위 테스트가 통과한다.
- 커밋: `Refactor: 핵심 도메인과 생성 행위 분리`

### 3. 클라이언트 구현과 생성 유스케이스 분리

- 파일 시스템 디렉터리 생성과 파일 쓰기를 `client`에 배치한다.
- 프로젝트 생성을 `GenerateFixedProject` 유스케이스로 분리한다.
- 검증: 클라이언트의 실패 규칙과 유스케이스의 실행 순서 테스트가 통과한다.
- 커밋: `Refactor: 클라이언트 구현과 프로젝트 생성 유스케이스 분리`

### 4. API 진입점으로 고정 프로젝트 생성 연결

- `SpringKit.kt`를 `api` 모듈에 배치하고 실행과 종합 책임만 남긴다.
- 기본 파일 하나를 쓰는 생성 작업을 유스케이스에 연결한다.
- 검증: 실행 후 예상 디렉터리와 파일이 존재하고 내용이 일치한다.
- 커밋: `Refactor: API 진입점 책임 축소`

## 완료 조건

- `./gradlew build`가 성공한다.
- 독립된 임시 경로에서 `./gradlew :springkit:kit:api:run`을 실행해 고정 프로젝트를 생성할 수 있다.
- 생성 실패는 0이 아닌 종료 코드 또는 명시적 예외로 관찰할 수 있다.

## 비범위

- 사용자별 project metadata
- 관례와 의존성 정책
- archive 출력
