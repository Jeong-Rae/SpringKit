# Agent Guidebook

Agent Guidebook은 변경된 Kotlin 파일과 프로젝트 지침을 검색해 관련 지침의
ID와 본문을 반환하는 Kotlin 라이브러리입니다. 요구사항은
[검색 계약](../docs/agent-guidebook-search-contract.md), [POC 문서](../docs/agent-guidebook-poc.md),
[기술 노트](../docs/agent-guidebook-poc-technical-notes.md)를 따릅니다.

## 패키지

기본 패키지는 `com.springkit.agentguidebook`입니다.

| 패키지 | 책임 |
| --- | --- |
| `models` | 공개 요청, 응답과 값 객체 |
| `services` | 지침 검색 등의 공개 행위 |
| `client` | `AgentGuidebookClient` 진입점과 의존성 구성 |
| `core` | Scope Filtering, Regex Matching |
| `storage` | YAML, 파일 시스템, Git |
| `errors` | 호출자가 처리할 오류 모델 |
| `codex` | PostToolUse, additionalContext 변환 |

패키지별 `package-info.kt`에는 책임을 기록합니다. 공개 검색 API는 다음과 같이
호출합니다.

```kotlin
import com.springkit.agentguidebook.models.GuidelineSearchRequest
import com.springkit.agentguidebook.services.GuidelineSearchService
import java.nio.file.Path

val matches =
    GuidelineSearchService().search(
        GuidelineSearchRequest(
            cwd = Path.of("/workspace/backend-order"),
            filepaths = listOf(Path.of("/workspace/backend-order/src/OrderService.kt")),
        )
    )
```

검색은 `.kt`와 `.kts` 파일만 읽고, `cwd/.agents/guides` 아래의 `guideline.yaml`을
상대 경로 사전순으로 평가합니다. YAML 지침의 저장소, 언어, `file_changed` 이벤트와
정규식 조건을 모두 만족한 지침을 ID와 UTF-8 본문으로 반환합니다. Kotlin 대상 파일이나
지침 후보가 없으면 저장소 `origin` 없이 빈 목록을 반환합니다. 둘 다 있을 때는
`git remote get-url origin`으로 저장소 범위를 확인합니다.

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
