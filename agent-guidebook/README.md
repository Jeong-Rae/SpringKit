# Agent Guidebook

Agent Guidebook은 변경된 Kotlin 파일과 프로젝트 지침을 검색해 관련 지침의
ID와 본문을 반환하고, 요청한 Session에 이미 제공한 지침을 기록하는 Kotlin 라이브러리입니다.
요구사항은 [검색 계약](../docs/agent-guidebook-search-contract.md),
[Presentation 계약](../docs/agent-guidebook-presentation-contract.md),
[POC 문서](../docs/agent-guidebook-poc.md), [기술 노트](../docs/agent-guidebook-poc-technical-notes.md)를 따릅니다.

## 패키지

기본 패키지는 `com.springkit.agentguidebook`입니다.

| 패키지 | 책임 |
| --- | --- |
| `models` | 공개 요청, 응답과 값 객체 |
| `services` | 검색 및 Session별 지침 제공 조율과 입력 읽기 경계 |
| `client` | 공개 검색·지침 제공 factory와 실제 입력 경계 조립 |
| `core` | 적용 범위, 정규식 일치, 지침 검색 규칙, Session별 제공 이력의 선별과 기록 |
| `storage` | 파일 시스템, YAML, Git 입력 및 SQLite 이력 어댑터 |
| `errors` | 호출자가 처리할 오류 모델 |
| `codex` | PostToolUse, additionalContext 변환 |

패키지별 `package-info.kt`에는 책임을 기록합니다. 실제 프로젝트 파일을 검색할 때는
파일 시스템 입력을 조립하는 팩토리로 서비스를 생성합니다.

```kotlin
import com.springkit.agentguidebook.client.GuidelineSearchFactory
import com.springkit.agentguidebook.models.GuidelineSearchRequest
import java.nio.file.Path

val matches =
    GuidelineSearchFactory.fileSystem().search(
        GuidelineSearchRequest(
            cwd = Path.of("/workspace/backend-order"),
            filepaths = listOf(Path.of("/workspace/backend-order/src/OrderService.kt")),
        )
    )
```

`GuidelineSearchService`는 `GuidelineSearchInputReader`에 입력 읽기를 위임합니다. 테스트에서는
메모리 기반 입력 경계를 주입해 실제 파일 시스템과 Git 없이 검색 흐름을 확인할 수 있습니다.

Session별로 아직 제공하지 않은 지침을 검색하고 SQLite에 기록하려면 Presentation factory를 사용합니다.

```kotlin
import com.springkit.agentguidebook.client.GuidelinePresentationFactory
import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import com.springkit.agentguidebook.models.SessionId
import java.nio.file.Path

val newGuidelines =
    GuidelinePresentationFactory.fileSystem().present(
        GuidelinePresentationRequest(
            sessionId = SessionId("codex-session-123"),
            cwd = Path.of("/workspace/backend-order"),
            filepaths = listOf(Path.of("/workspace/backend-order/src/OrderService.kt")),
        )
    )
```

기본 이력 DB는 `user.home/.adaptive-guidebook/adaptive-guidebook.db`입니다. 이력은
Session ID와 Guideline ID 조합으로 저장되므로 본문이 바뀌어도 같은 Guideline ID를 다시 제공하지 않습니다.
`present`는 해당 호출의 SQLite transaction commit이 성공한 뒤에만 새 지침 목록을 반환합니다.
Commit 후 반환 전에 프로세스가 끝나면 다음 요청은 저장된 ID를 제외합니다. 수신 확인이나 재전송은 하지 않습니다.
SQLite 잠금은 최대 1,000ms 대기하며, 그 뒤에도 잠금이 유지되면 재시도 가능한 저장소 예외를 전달합니다.

이 Kotlin API는 기존 Codex Hook이나 운영 CLI와 연결되어 있지 않습니다.
Session별 제공 이력의 검증 단계와 결과는
[Presentation 검증 기록](../docs/agent-guidebook-presentation-verification.md)에서 확인할 수 있습니다.

검색은 `.kt`와 `.kts` 파일만 읽고, `cwd/.agents/guides` 아래의 `guideline.yaml`을
상대 경로 사전순으로 평가합니다. YAML 지침의 저장소, 언어, `file_changed` 이벤트와
정규식 조건을 모두 만족한 지침을 ID와 UTF-8 본문으로 반환합니다. Kotlin 대상 파일이나
지침 후보가 없으면 저장소 `origin` 없이 빈 목록을 반환합니다. 둘 다 있을 때는
`git remote get-url origin`으로 저장소 범위를 확인합니다.

## Codex PostToolUse Hook

`hooks/post-tool-use.sh`는 Codex가 보낸 Hook stdin을 단일 실행 파일에 그대로 전달합니다. 실행 파일의 stdout, stderr, 종료 코드와 Codex 호출 cwd를 보존합니다. wrapper는 Hook JSON을 읽거나 변환하지 않으며 Kotlin 검색 앱과 연결되어 있지 않습니다. 실행 앱은 Hook 응답 JSON을 stdout에 직접 써야 합니다.

실행 파일은 `AGENT_GUIDEBOOK_APP_EXECUTABLE` 환경 변수에 절대 경로로 지정하고, Codex 프로세스가 그 환경을 상속하도록 시작합니다.

```sh
export AGENT_GUIDEBOOK_APP_EXECUTABLE="/absolute/path/to/agent-guidebook-app"
codex
```

환경 변수 값은 실행 파일 경로 하나입니다. 공백이 있는 경로도 따옴표로 감싸 전체 경로를 전달합니다. 인자나 셸 명령 문자열은 넣지 않습니다. wrapper가 해당 경로를 따옴표로 실행하므로 `$()`나 역따옴표 같은 셸 문자가 실행되지 않습니다.

Codex 설정에 `PostToolUse` command hook을 등록하고, wrapper의 절대 경로를 지정합니다. [예제 설정](hooks/hooks.example.json)은 matcher를 `^apply_patch$`로 제한합니다. JSON의 `command` 값은 셸 명령 문자열이므로, 공백이 있는 wrapper 경로는 JSON 문자열 내부에서 다시 따옴표로 감싸야 합니다. 예제의 checkout 경로는 실제 위치로 바꾸고, 프로젝트 설정을 쓸 때는 Codex에서 해당 `.codex/` 계층을 신뢰해야 합니다.

실제 Hook probe에서는 Codex CLI inline 설정을 검증했습니다. 예제 `hooks.json` 파일 형식은 Codex 공식 구성을 참고한 것이며, 파일 기반 설정 로딩은 별도 runtime 검증 전까지 미확인입니다.

검증 단계와 실제 run4 payload, canary 결과는 [Hook 검증 기록](../docs/agent-guidebook-hook-verification.md)에서 확인할 수 있습니다.

환경 변수나 앱 경로가 잘못되면 wrapper는 한국어 진단을 stderr에 쓰고 종료 코드 64, 127 또는 126을 반환합니다. 유효한 앱이 실행되면 앱이 반환한 종료 코드를 그대로 전달합니다. 기본 테스트와 실행 방법은 아래 [검증](#검증) 절을 따릅니다.

## 검증

Java 25 toolchain을 사용합니다. Kotlin 소스와 Gradle 스크립트는
Spotless의 ktfmt로 포맷합니다. Kotest는 JUnit Platform에서 실행합니다.

`agent-guidebook` 디렉터리에서 다음 순서로 실행합니다.

```sh
./gradlew spotlessApply
./gradlew test --tests "com.springkit.agentguidebook.*"
./gradlew build
```

빌드 결과는 `build/libs/agent-guidebook-0.0.1-SNAPSHOT.jar`입니다.
