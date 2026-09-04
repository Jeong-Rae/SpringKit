# sk-5: 기본 Project Contributor

## Goal

책임별 contributor를 통해 실제로 Gradle build가 가능한 최소 프로젝트를 생성한다.

## 선행 Task

- sk-4

## Step

### 1. 기본 파일 contributor 구현

- `.gitignore`와 최소 `README.md`를 생성한다.
- 검증: contributor별 경로와 파일 내용 테스트가 통과한다.
- 커밋: `Feature: generate basic project files`

### 2. Gradle build contributor 구현

- settings와 build script를 생성한다.
- 검증: description 값이 정확한 Gradle 설정으로 렌더링되는지 테스트한다.
- 커밋: `Feature: generate Gradle build files`

### 3. Source contributor 구현

- package 경로와 application class를 생성한다.
- 검증: package/name 조합별 경로와 소스 내용 테스트가 통과한다.
- 커밋: `Feature: generate application source`

### 4. Resource contributor 구현

- 표준 resource 디렉터리와 기본 설정 파일을 생성한다.
- 검증: 예상 경로와 설정 내용 테스트가 통과한다.
- 커밋: `Feature: generate application resources`

### 5. 생성 프로젝트 build 검증

- 임시 디렉터리에 생성한 프로젝트의 Gradle build를 실행하는 통합 테스트를 추가한다.
- 검증: 생성된 프로젝트에서 clean 없이 Gradle build가 성공한다.
- 커밋: `Test: verify generated project build`

## 완료 조건

- contributor들이 서로를 직접 호출하지 않는다.
- 각 contributor가 소유한 파일 경계가 명확하다.
- 생성된 최소 프로젝트의 Gradle build가 성공한다.

## 비범위

- 선택 dependency
- ZIP 패키징
