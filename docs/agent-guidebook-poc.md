# Agent Guidebook POC

Agent Guidebook POC는 Coding Agent가 코드를 변경한 직후 현재 작업과 관련된 개발 Guideline을 찾아 다음 inference의 Context로 제공할 수 있는지 검증합니다.

POC의 핵심은 Guideline을 많이 관리하는 시스템을 만드는 것이 아니라, **코드 변경 → 관련 Guideline 탐색 → Agent Context 제공**이라는 연결이 실제 Coding Agent 작업에서 유효하게 동작하는지 확인하는 것입니다.

## 용어

### Agent Guidebook

Coding Agent가 현재 작업과 관련된 개발 Guideline을 찾을 수 있도록 제공하는 시스템입니다.

Agent Guidebook은 변경된 파일과 현재 작업 Context를 입력으로 받아 관련 Guideline을 찾아 Agent에게 제공합니다.

### Guideline

Agent에게 제공할 하나의 개발 지침입니다.

Guideline은 적용 범위와 관련성 조건, Agent에게 전달할 본문을 가집니다.

주요 속성은 `id`, `scope`, `when`, `match`, `content`입니다.

### Applicable Guideline

현재 작업의 repository, language, event 조건에 적용되는 Guideline입니다.

Guideline 전체에서 현재 작업에 적용 가능한 후보를 좁힌 결과입니다.

### Matched Guideline

Applicable Guideline 가운데 현재 source가 `match` 조건을 만족한 Guideline입니다.

POC에서는 Regex를 사용하여 source와 Guideline의 관련성을 판단합니다.

### Presentation

특정 Guideline을 특정 Agent Session에 제공한 사실입니다.

같은 Guideline은 하나의 Codex Session에서 한 번 Presentation합니다.

### Session

Guideline Presentation의 범위를 구분하는 작업 단위입니다.

POC에서는 Codex의 `session_id`를 Session 식별자로 사용합니다.

### Repository Identity

현재 작업이 속한 repository를 식별하는 값입니다.

Git `origin` remote를 `owner/repository` 형태로 정규화하여 사용합니다.

예: `acme/backend-order`

### Guidebook Root

현재 프로젝트의 Guideline을 찾는 기준 디렉터리입니다.

Codex Hook이 전달한 절대 경로 `cwd`를 기준으로 `.agents/guides`를 사용합니다.

예: `/workspace/backend-order/.agents/guides`

### Search Request

Codex Adapter가 Agent Guidebook에 전달하는 요청입니다.

Search Request는 `sessionId`, `cwd`, `filepaths`를 포함합니다.

`cwd`와 `filepaths`는 절대 경로를 사용합니다.

### Search Result

현재 요청에서 Agent에게 새로 Presentation할 Guideline 목록입니다.

각 결과는 Guideline의 `id`와 `content`를 제공합니다.

## 도메인

Agent Guidebook의 중심 도메인은 **현재 코드 변경에 관련된 Guideline을 찾고, Session에서 필요한 Guideline을 한 번 제공하는 것**입니다.

Guideline은 다음 상태를 거쳐 처리됩니다.

`Guideline → ApplicableGuideline → MatchedGuideline → Presentation`

### Guideline

Guideline은 `.agents/guides` 아래의 `guideline.yaml`에 정의합니다.

예시는 다음 의미를 가집니다.

- `id`: Guideline 식별자
- `scope.repositories`: 적용할 repository
- `scope.languages`: 적용할 programming language
- `when`: Guideline을 평가할 event
- `match`: source 관련성 조건
- `content`: Agent에게 제공할 개발 지침

POC의 대상 language는 Kotlin이며 `.kt`, `.kts` 파일을 대상으로 합니다.

POC의 event는 `file_changed`입니다.

### Applicability

Guideline을 모든 변경 파일에 바로 Regex Matching하지 않고, 먼저 현재 작업에 적용 가능한 Guideline을 계산합니다.

Applicability는 다음 Context를 사용합니다.

- Repository Identity
- Language
- Event

이 조건에 부합한 Guideline이 `ApplicableGuideline`입니다.

### Matching

Applicable Guideline은 현재 파일의 source와 함께 평가합니다.

POC에서는 `match.any`에 선언한 Regex 중 하나가 일치하면 `MatchedGuideline`이 됩니다.

Regex Match는 코드가 Guideline을 위반했다는 의미가 아니라, 현재 변경과 Guideline이 관련 있다는 의미입니다.

실제 Guideline의 의미 해석과 적용은 Coding Agent가 `content`를 읽고 수행합니다.

### Presentation

Matched Guideline은 현재 Session의 Presentation 상태와 함께 평가합니다.

`session_id + guideline_id`를 Presentation의 고유 식별 기준으로 사용합니다.

현재 Session에서 Presentation 이력이 없는 Matched Guideline을 모두 Agent에게 제공합니다.

Presentation 사실은 SQLite에 저장합니다.

### Guideline Source와 Presentation State

Guideline 정의와 실행 상태는 서로 다른 저장소에 위치합니다.

Guideline 정의는 프로젝트의 `.agents/guides`에 저장합니다.

Presentation 상태는 사용자 로컬 SQLite에 저장합니다.

이 구조에서 프로젝트는 개발 지침을 소유하고, Agent Guidebook은 각 Agent Session에 어떤 Guideline을 이미 제공했는지 관리합니다.

## 동작

### 개발자가 Codex로 코드를 변경합니다

사용자는 평소와 같이 Codex에 개발 작업을 요청합니다.

Codex가 `apply_patch`를 사용하여 파일을 변경하면 `PostToolUse` Hook이 실행됩니다.

사용자는 Agent Guidebook을 별도로 호출하지 않습니다.

### Codex Hook이 변경 파일을 정리합니다

Codex Adapter는 `apply_patch` 결과에서 변경된 파일 경로를 추출합니다.

상대 경로는 Hook이 실행된 `cwd`를 기준으로 절대 경로로 변환합니다.

검색 시점에 존재하는 파일만 Search Request에 포함합니다.

예를 들어 Codex가 `src/main/kotlin/OrderService.kt`를 변경했고 `cwd`가 `/workspace/backend-order`라면 Agent Guidebook에는 `/workspace/backend-order/src/main/kotlin/OrderService.kt`를 전달합니다.

### Agent Guidebook을 실행합니다

Agent Guidebook은 Hook 요청마다 하나의 프로세스로 실행합니다.

Codex Adapter는 다음 Context를 전달합니다.

- Codex `session_id`
- Hook 실행 위치의 절대 `cwd`
- 변경된 파일의 절대 `filepaths`

Agent Guidebook은 요청을 처리하고 응답을 반환한 뒤 프로세스를 종료합니다.

요청 간 필요한 상태는 SQLite를 통해 유지합니다.

### 프로젝트의 Guideline을 읽습니다

Agent Guidebook은 `cwd/.agents/guides` 아래에서 `guideline.yaml`을 읽습니다.

예를 들어 `cwd`가 `/workspace/backend-order`라면 `/workspace/backend-order/.agents/guides`가 Guidebook Root가 됩니다.

Guideline은 현재 작업 repository 안에서 관리되므로 프로젝트와 함께 버전 관리할 수 있습니다.

### 현재 Repository를 식별합니다

Agent Guidebook은 현재 Git repository의 `origin` URL을 읽습니다.

`git remote get-url origin` 결과를 `owner/repository` 형태의 `RepositoryIdentity`로 변환합니다.

이 값으로 Guideline의 `scope.repositories`를 평가합니다.

### Applicable Guideline을 계산합니다

Repository Identity, Kotlin language, `file_changed` event를 기준으로 현재 요청에 적용되는 Guideline을 계산합니다.

이 단계의 결과가 `ApplicableGuideline`입니다.

### 현재 Source와 Match합니다

Agent Guidebook은 Search Request에 포함된 절대 filepath를 사용하여 현재 파일 내용을 읽습니다.

각 Applicable Guideline의 Regex를 source에 적용합니다.

Regex 조건을 만족한 Guideline은 `MatchedGuideline`이 됩니다.

### Presentation 상태를 적용합니다

Agent Guidebook은 SQLite에서 현재 `session_id`의 Presentation 상태를 조회합니다.

현재 Session에서 새로 Presentation할 Matched Guideline을 계산합니다.

Presentation하기로 결정한 Guideline은 SQLite에 기록합니다.

### Codex Context로 변환합니다

Agent Guidebook은 Presentation 대상의 `id`와 `content`를 Codex Adapter에 반환합니다.

Codex Adapter는 결과를 하나의 Context 문자열로 조합하여 `additionalContext`에 담습니다.

Codex는 이 Context를 포함하여 다음 inference를 수행합니다.

전체 사용자 UseCase는 다음 순서입니다.

`코드 변경 → PostToolUse → Agent Guidebook 실행 → 관련 Guideline 검색 → Presentation → 다음 Codex inference`

## 검증해야 하는 개념

### 변경 직후 Guideline을 주입할 수 있어야 합니다

Codex가 `apply_patch`로 파일을 변경한 뒤 `PostToolUse`를 통해 Agent Guidebook이 실행되어야 합니다.

Agent Guidebook의 결과가 `additionalContext`에 포함되고 다음 Codex inference에서 사용할 수 있어야 합니다.

이 연결이 POC에서 가장 먼저 검증해야 하는 핵심입니다.

### Hook 결과에서 검색 대상 파일을 안정적으로 결정할 수 있어야 합니다

`apply_patch` 결과에서 변경 파일 경로를 추출할 수 있어야 합니다.

상대 경로는 `cwd`를 기준으로 절대 경로로 정규화되어야 합니다.

Agent Guidebook은 정규화된 filepath만으로 실제 source에 접근할 수 있어야 합니다.

### 프로젝트 Context만으로 Guideline Source를 결정할 수 있어야 합니다

Hook에서 전달한 `cwd`만으로 Guidebook Root를 결정할 수 있어야 합니다.

`cwd/.agents/guides` 아래의 Guideline을 재귀적으로 읽고 사용할 수 있어야 합니다.

별도의 Guidebook 경로 설정 없이 프로젝트 자체가 Guideline Source를 제공하는 흐름을 검증합니다.

### Repository Scope를 실제 프로젝트와 연결할 수 있어야 합니다

현재 작업 repository의 Git `origin`을 읽어 안정적인 `RepositoryIdentity`를 계산할 수 있어야 합니다.

이 값으로 `scope.repositories`를 평가하여 repository별 Guideline을 구분할 수 있어야 합니다.

### Scope Filtering이 동작해야 합니다

Repository, language, event 조건으로 Guideline 후보를 줄일 수 있어야 합니다.

현재 작업에 적용되는 Guideline만 Regex Matching 단계로 전달되어야 합니다.

### Regex 기반 관련성 검색이 동작해야 합니다

Agent Guidebook이 변경된 파일의 현재 source를 읽고 Applicable Guideline의 Regex를 평가할 수 있어야 합니다.

관련된 Guideline은 `MatchedGuideline`으로 계산되어야 합니다.

POC에서는 이 과정이 충분한 Guideline retrieval 방식인지 확인합니다.

### Session 단위 Presentation이 유지되어야 합니다

같은 Guideline이 같은 Codex Session에서 반복해서 Match되더라도 Presentation은 한 번만 이루어져야 합니다.

프로세스가 요청마다 종료되더라도 SQLite를 통해 이 상태가 유지되어야 합니다.

### 요청 단위 실행으로 전체 UseCase가 완결되어야 합니다

하나의 Hook 요청 안에서 Guideline loading, Filtering, Matching, Presentation 조회와 기록, 결과 반환이 모두 완료되어야 합니다.

상주 Runtime 없이도 기능적으로 완결된 흐름을 구성할 수 있어야 합니다.

### Agent Guidebook과 Codex의 경계가 분리되어야 합니다

Agent Guidebook의 공개 API는 `sessionId`, `cwd`, `filepaths`를 받아 `{ id, content }` 목록을 반환하는 Agent-neutral 계약으로 동작해야 합니다.

Codex의 `PostToolUse`, `tool_response`, `additionalContext` 형식은 Codex Adapter가 담당해야 합니다.

이를 통해 Guideline 검색 UseCase와 특정 Coding Agent Protocol을 분리할 수 있어야 합니다.

## POC 구현 기준

구현 언어는 Kotlin입니다.

프로젝트는 Single Module로 구성합니다.

패키지는 책임에 따라 `models`, `services`, `client`, `core`, `storage`, `errors`, `codex`로 구분합니다.

외부 진입점은 경량 Client API로 제공합니다.

`AgentGuidebookClient → guidelines() → present(request)`

`present(request)`는 Guideline Filtering, Regex Matching, Presentation 확인, Presentation 기록, 결과 반환을 하나의 UseCase로 수행합니다.

POC 기능 테스트는 JVM Artifact로 수행합니다.

GraalVM Native Image는 빌드 결과를 실행 Artifact로 신뢰합니다.

## Presentation 저장

Presentation 상태는 SQLite에 저장합니다.

SQLite 파일은 `~/.adaptive-guidebook/adaptive-guidebook.db`를 사용합니다.

`presentation`은 `session_id`, `guideline_id`, `presented_at`을 저장합니다.

고유성은 `session_id + guideline_id`로 정의합니다.

`presented_at`은 UTC 기준 RFC 3339 형식으로 저장합니다.

## POC 완료 기준

POC는 하나의 Codex 작업에서 다음 흐름이 실제로 연결되면 완료된 것으로 판단합니다.

Codex가 Kotlin 파일을 변경합니다.

`PostToolUse`가 변경 파일의 절대 경로와 `cwd`, `sessionId`를 Agent Guidebook에 전달합니다.

Agent Guidebook이 `.agents/guides`의 Guideline을 읽고 현재 repository와 Kotlin source에 관련된 Guideline을 찾습니다.

현재 Session에 새로 제공할 Guideline을 SQLite에 기록합니다.

Codex Adapter가 Guideline의 `content`를 `additionalContext`로 반환합니다.

Codex의 다음 inference에서 해당 Guideline Context를 사용할 수 있습니다.
