# sk-9: Archive 패키징

## Goal

생성된 프로젝트를 디렉터리 결과와 동일한 내용의 재현 가능한 ZIP archive로 제공한다.

## 선행 Task

- sk-8

## Step

### 1. Generation result contract 분리

- 생성된 project root와 전달 형식의 경계를 모델링한다.
- 검증: directory 결과가 기존 생성 흐름을 보존하는지 테스트한다.
- 커밋: `Refactor: 생성 결과와 전달 방식 분리`

### 2. ZIP packager 구현

- project root의 상대 경로와 파일을 ZIP으로 기록한다.
- 검증: 압축 해제 결과의 경로와 내용이 원본과 일치한다.
- 커밋: `Feature: 생성된 프로젝트 ZIP 패키징`

### 3. 재현 가능한 archive 보장

- entry 순서와 도구가 소유한 metadata를 안정화한다.
- 검증: 같은 입력으로 생성한 archive의 byte 또는 정의된 digest가 동일하다.
- 커밋: `Feature: 프로젝트 아카이브 재현성 보장`

### 4. 임시 resource lifecycle 구현

- 패키징 성공과 실패 후 임시 project root 정리 정책을 적용한다.
- 검증: 정상/실패 경로 모두에서 임시 resource가 정리된다.
- 커밋: `Feature: 아카이브 생성 리소스 정리`

### 5. CLI archive option 연결

- directory와 ZIP 출력 방식을 명시적으로 선택하게 한다.
- 검증: CLI로 archive를 만들고 압축 해제한 프로젝트의 Gradle build가 성공한다.
- 커밋: `Feature: ZIP 프로젝트 출력 제공`

## 완료 조건

- directory와 ZIP 내부의 프로젝트 내용이 동일하다.
- 동일 입력의 archive 결과가 재현 가능하다.
- archive 생성 과정의 임시 resource가 누수되지 않는다.

## 비범위

- TAR 등 추가 archive format
- HTTP content negotiation
