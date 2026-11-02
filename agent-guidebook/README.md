# Agent Guidebook

Agent Guidebook은 변경된 Kotlin 파일에 적용할 프로젝트 지침을 검색하고, 요청한
Session에 새로 제공한 지침을 기록하는 Kotlin 라이브러리와 JVM 실행 앱입니다.
요구사항은 [검색 계약](../docs/agent-guidebook-search-contract.md),
[Presentation 계약](../docs/agent-guidebook-presentation-contract.md),
[POC 문서](../docs/agent-guidebook-poc.md), [기술 노트](../docs/agent-guidebook-poc-technical-notes.md)를 따릅니다.

## 패키지

기본 패키지는 `com.springkit.agentguidebook`입니다.

| 패키지 | 책임 |
| --- | --- |
| `features.search.application` | 지침 검색 UseCase와 입력 읽기 포트 |
| `features.search.infrastructure` | 파일 시스템, YAML, Git 검색 입력 어댑터 |
| `features.presentation.application` | Session별 제공 UseCase와 이력 저장 포트 |
| `features.presentation.infrastructure` | SQLite 이력 저장소, transaction 경계와 연결 설정 |
| `models` | 공개 요청, 응답과 값 객체 |
| `services` | 기존 호출자를 위한 검색·Presentation 서비스와 호환 별칭 |
| `client` | 공개 검색·지침 제공 factory와 실제 입력 경계 조립 |
| `core` | 적용 범위, 정규식 일치, 지침 검색 규칙, Session별 제공 이력의 선별과 기록 |
| `config`, `storage` | 기존 SQLite 타입 경로의 Kotlin 호환 별칭 |
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

`GuidelineSearchService`는 기존 공개 API를 유지하는 facade이며 검색 처리는
`features.search.application.SearchGuidelinesUseCase`가 맡습니다. UseCase는
`GuidelineSearchInputReader` 포트에 입력 읽기를 위임합니다. 테스트에서는 메모리 기반 입력
경계를 주입해 실제 파일 시스템과 Git 없이 검색 흐름을 확인할 수 있습니다.

`PresentationRepository`는 이력 조회·기록을 맡고, `UnitOfWork`가 실행 범위를 관리합니다.
두 포트와 Session별 제공 UseCase는 `features.presentation.application`에 있습니다.
SQLite 연결 설정과 초기화는 `features.presentation.infrastructure`에서 구성합니다. 객체 조립과 실행 범위는
[영속성 경계 검토](../docs/agent-guidebook-unit-of-work-review.md)에서 확인할 수 있습니다.

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

기존 `GuidelineSearchService`와 `GuidelinePresentationService`의 생성자 및 `search`·`present` 메서드, `client` factory는 유지합니다. 이동한 application 포트, SQLite 타입, Presentation UseCase의 기존 이름은 Kotlin `typealias`로 제공해 Kotlin 소스 재컴파일 호환을 지원합니다. 이전 FQCN의 이미 컴파일된 JVM 바이너리 호환은 보장하지 않습니다. 자세한 내용은 [구조 검토](../docs/agent-guidebook-structure-review.md)를 참고합니다.

기본 이력 DB는 `user.home/.adaptive-guidebook/adaptive-guidebook.db`입니다. 이력은
Session ID와 Guideline ID 조합으로 저장되므로 본문이 바뀌어도 같은 Guideline ID를 다시 제공하지 않습니다.
`present`는 해당 호출의 SQLite transaction commit이 성공한 뒤에만 새 지침 목록을 반환합니다.
Commit 후 반환 전에 프로세스가 끝나면 다음 요청은 저장된 ID를 제외합니다. 수신 확인이나 재전송은 하지 않습니다.
SQLite 잠금은 최대 1,000ms 대기하며, 그 뒤에도 잠금이 유지되면 재시도 가능한 저장소 예외를 전달합니다.
이력 저장은 Exposed 1.6.0의 `core`, `jdbc` typed DSL과 SQLite JDBC 3.53.4.0 driver를 사용합니다.
세 `TEXT` 열과 `(session_id, guideline_id)` 기본 키는 일반 Exposed `Table`로 정의하고, Repository는 typed `selectAll`과 `insert`로 조회·기록합니다. 조회한 문자열 행은 transaction 안에서 도메인 `Presentation`으로 복원합니다. 구현 판단과 실패 경계는
[SQLite 저장소 라이브러리 검토](../docs/agent-guidebook-sqlite-library-review.md)에서 확인할 수 있습니다.

Codex `PostToolUse` Hook wrapper는 배포한 JVM 앱에 연결되어 있습니다. 진입점은
`CodexHookInput`, `CodexHookOutput`과 기존 `GuidelinePresentationFactory`를 조립합니다.
입력 객체는 EOF까지 UTF-8 입력을 읽어 기존 Adapter로 요청을 변환하고, 출력 객체는
정상 응답과 진단을 각각 stdout과 stderr에 기록한 뒤 flush합니다. 호출자가 전달한 스트림은 닫지 않습니다.
`CodexHookApplication.run(input, output)`은 실행 흐름과 종료 코드만 결정합니다.
객체별 계약과 실패 경계는 [Hook 입출력 책임 검토](../docs/agent-guidebook-hook-stdio-review.md)를 따릅니다.
`features.presentation.application`의
`PresentGuidelinesUseCase`가 검색과 Session별 이력 기록을 조율하며, 기존
`GuidelinePresentationService.present`도 같은 UseCase로 위임합니다. Session별 제공 이력의 검증 단계와 결과는
[Presentation 검증 기록](../docs/agent-guidebook-presentation-verification.md)에서 확인할 수 있습니다.

검색은 `.kt`와 `.kts` 파일만 읽고, `cwd/.agents/guides` 아래의 `guideline.yaml`을
상대 경로 사전순으로 평가합니다. YAML 지침의 저장소, 언어, `file_changed` 이벤트와
정규식 조건을 모두 만족한 지침을 ID와 UTF-8 본문으로 반환합니다. Kotlin 대상 파일이나
지침 후보가 없으면 저장소 `origin` 없이 빈 목록을 반환합니다. 둘 다 있을 때는
JGit 7.8.0으로 현재 경로부터 상위 Git 저장소를 찾아 첫 `origin` URL을 읽고
`url.*.insteadOf` 재작성을 적용합니다. 원격 네트워크 작업이나 설정 변경은 하지 않습니다.
JGit의 시스템 설정 탐색은 환경에 따라 Git 프로세스를 실행할 수 있습니다. 지원 주소와
예외 계약, 구현 한계는 [입출력 라이브러리 검토](../docs/agent-guidebook-io-library-review.md)에서 확인할 수 있습니다.

## Codex PostToolUse Hook

설치 디렉터리에는 `bin/agent-guidebook`, `bin/agent-guidebook.bat`, `lib/*.jar`, `hooks/post-tool-use.sh`, `hooks/hooks.example.json`이 있습니다. `bin` 실행 스크립트는 같은 설치 디렉터리의 `lib`을 사용하므로, 실행 시 빌드 checkout이 필요하지 않습니다. `hooks/post-tool-use.sh`는 Codex가 보낸 Hook stdin을 단일 실행 파일에 그대로 전달합니다. wrapper는 Hook JSON을 해석하지 않습니다. JVM 앱이 입력을 읽고 필요한 경우 `hookSpecificOutput.additionalContext` JSON을 stdout에 씁니다.

`AGENT_GUIDEBOOK_APP_EXECUTABLE`에는 설치된 `bin/agent-guidebook`의 절대 경로를 지정합니다. Codex 설정에는 설치된 `hooks/hooks.example.json`을 참고해 `PostToolUse` command Hook을 등록하고, `command`가 설치 경로의 `hooks/post-tool-use.sh`를 가리키게 합니다. 경로에 공백이 있으면 JSON의 `command` 값처럼 전체 경로를 따옴표로 감쌉니다.

```sh
export AGENT_GUIDEBOOK_APP_EXECUTABLE="/absolute/path/to/Agent Guidebook installation/bin/agent-guidebook"
cd /absolute/path/to/target-project
codex
```

환경 변수 값은 실행 파일 경로 하나입니다. 공백이 있는 경로도 따옴표로 감싸 전체 경로를 전달합니다. 인자나 셸 명령 문자열은 넣지 않습니다. wrapper가 해당 경로를 따옴표로 실행하므로 `$()`나 역따옴표 같은 셸 문자가 실행되지 않습니다. Codex는 target project의 작업 디렉터리에서 실행하며, SQLite 이력은 실행 계정의 `user.home`에 저장됩니다. 설치 경로, 프로젝트 경로, 사용자 DB 경로는 서로 독립적입니다.

생산 wrapper와 배포 앱의 표준 입력·출력·오류·종료 코드는 프로세스 통합 테스트로 검증합니다. Codex CLI 0.166.0의 임시 Git 프로젝트에서 CLI inline Hook 설정으로 실행해, 실제 추가 컨텍스트가 다음 모델 입력에 전달되는 것을 확인했습니다. 이 실행은 성공했으며, Codex stderr에는 별도 MCP worker의 HTTP 403 로그도 포함됐습니다. [Codex Hook adapter 검증 기록](../docs/agent-guidebook-codex-adapter-verification.md)에 실행 증거를 기록합니다. 예제 `hooks.json` 파일 형식은 Codex 공식 구성을 참고한 것이며, 파일 기반 설정 로딩은 아직 runtime 검증되지 않았습니다.

검증 단계와 실제 run4 payload, canary 결과는 [Hook 검증 기록](../docs/agent-guidebook-hook-verification.md)에서 확인할 수 있습니다.

입력 Adapter는 `PostToolUse`의 `apply_patch` 이벤트만 처리합니다. 대상 요청에는 비어 있지 않은 `session_id`, 절대 경로 `cwd`, 문자열 `tool_response`가 필요합니다. 성공한 `tool_response`의 `Exit code: 0`과 `Success. Updated the following files:` marker 아래에서 추가·수정된 경로를 읽습니다. marker부터 시작하는 원래 도구 출력도 지원합니다. 상대 경로는 `cwd` 기준으로 해석합니다. 삭제되거나 현재 없는 경로와 디렉터리는 검색하지 않습니다. 기존 검색 서비스가 `.kt`와 `.kts` 파일만 읽습니다. 다른 이벤트·도구, 실패한 패치, 검색 대상이 없는 요청은 빈 stdout과 종료 코드 0을 반환합니다.

잘못된 Hook JSON이나 대상 이벤트의 필드 오류는 stderr 진단과 종료 코드 64를 반환합니다. 검색·저장·표준 스트림 오류는 원인 메시지를 stderr에 쓰고 종료 코드 1을 반환합니다. stdout에는 진단이나 실패 JSON을 쓰지 않습니다. wrapper의 실행 파일 설정 오류는 64, 파일을 찾을 수 없는 경우는 127, 실행할 수 없는 경우는 126을 반환합니다. 유효한 앱을 실행한 뒤에는 앱의 종료 코드를 그대로 전달합니다. 기본 테스트와 실행 방법은 아래 [검증](#검증) 절을 따릅니다.

## 검증

Java 25 toolchain을 사용합니다. Kotlin 소스와 Gradle 스크립트는
Spotless의 ktfmt로 포맷합니다. Kotest는 JUnit Platform에서 실행합니다.

`agent-guidebook` 디렉터리에서 다음 순서로 실행합니다.

```sh
./gradlew spotlessApply
./gradlew test --tests "com.springkit.agentguidebook.*"
./gradlew build
```

`./gradlew installDist`는 `build/install/agent-guidebook/bin/agent-guidebook` 실행 스크립트와 JVM 의존성을 설치합니다. 배포 스크립트에는 SQLite JDBC의 Java 25 native access 옵션과 SLF4J no-op provider가 포함되어 정상 기동 때 라이브러리 경고를 stderr에 쓰지 않습니다. 테스트는 이 배포 스크립트를 사용해 Hook 프로세스를 검증합니다. 빌드 결과에는 `build/libs/agent-guidebook-0.0.1-SNAPSHOT.jar`도 포함됩니다.
