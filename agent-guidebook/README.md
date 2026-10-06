# Agent Guidebook

Agent Guidebook POC를 구현하기 위한 Kotlin 단일 모듈 프로젝트입니다.
현재는 패키지 구조와 Gradle·Kotest 설정을 제공합니다.

요구사항은 [POC 문서](../docs/agent-guidebook-poc.md)와
[기술 노트](../docs/agent-guidebook-poc-technical-notes.md)를 따릅니다.
빌드 설정은 `template/spring-boot-kotlin-multi-module`을 참고합니다.

## 패키지

기본 패키지는 `com.springkit.agentguidebook`입니다.

| 패키지 | 책임 |
| --- | --- |
| `models` | 공개 요청, 응답과 값 객체 |
| `services` | `present(request)` 등의 공개 행위 |
| `client` | `AgentGuidebookClient` 진입점과 의존성 구성 |
| `core` | Scope Filtering, Regex Matching |
| `storage` | YAML, 파일 시스템, Git, SQLite |
| `errors` | 호출자가 처리할 오류 모델 |
| `codex` | PostToolUse, additionalContext Adapter |

패키지별 `package-info.kt`에는 책임을 기록합니다. 검색 API, 저장소와
Codex Adapter 구현은 후속 작업에서 추가합니다.

## 검증

Java 25 toolchain을 사용합니다. Kotlin 소스와 Gradle 스크립트는
Spotless의 ktfmt로 포맷합니다. Kotest는 JUnit Platform에서 실행합니다.
`BuildConfigurationTest`는 테스트가 Java 25에서 실행되는지 확인합니다.

`agent-guidebook` 디렉터리에서 다음 순서로 실행합니다.

```sh
./gradlew spotlessApply
./gradlew test --tests "com.springkit.agentguidebook.*"
./gradlew build
```

빌드 결과는 `build/libs/agent-guidebook-0.0.1-SNAPSHOT.jar`입니다.
