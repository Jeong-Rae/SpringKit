# sk-8: CLI Adapter

## Goal

생성 코어를 변경하지 않고 sparse 사용자 입력을 받아 프로젝트 디렉터리를 생성하는 안정적인 CLI를 제공한다.

## 선행 Task

- sk-6
- sk-7

## Step

### 1. CLI command와 option 정의

- project metadata, dependency, output 경로를 입력받는 command contract를 정의한다.
- 검증: option별 parsing 단위 테스트가 통과한다.
- 커밋: `프로젝트 생성 명령 정의`

### 2. Sparse request 매핑

- CLI 입력을 외부 request 모델로만 변환하고 default 결정은 policy 계층에 위임한다.
- 검증: 생략된 option이 adapter에서 임의의 값으로 치환되지 않는다.
- 커밋: `CLI 입력을 프로젝트 요청으로 변환`

### 3. 오류와 종료 코드 정의

- 입력 오류, compatibility 오류, output 오류, 내부 실패를 구분한다.
- 검증: 오류 종류별 메시지와 종료 코드 테스트가 통과한다.
- 커밋: `생성 명령 실패 보고`

### 4. Directory 생성 end-to-end 연결

- CLI에서 converter, policy, context, plan, runner 전체 흐름을 호출한다.
- 검증: 실제 command 실행으로 생성된 프로젝트의 Gradle build가 성공한다.
- 커밋: `프로젝트 생성 CLI 제공`

### 5. Help와 사용 예시 제공

- 지원 option, default 의미, dependency 입력 방법을 문서화한다.
- 검증: help command가 성공하고 문서 예제 command가 실행된다.
- 커밋: `프로젝트 생성 CLI 문서화`

## 완료 조건

- CLI가 generation core의 concrete contributor를 직접 조립하지 않는다.
- 최소 입력과 완전 입력 모두 정상 처리된다.
- CLI로 생성된 프로젝트의 Gradle build가 성공한다.

## 비범위

- HTTP adapter
- archive 생성
