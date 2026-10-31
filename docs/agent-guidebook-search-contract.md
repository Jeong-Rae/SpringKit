# Agent Guidebook 검색 계약

Agent Guidebook은 변경된 Kotlin 소스와 프로젝트의 지침 파일을 입력받아 관련 지침의 ID와 본문을 반환합니다. 이 계약은 공개 서비스 API와 core 검색 API의 입출력 및 실패 의미를 정의합니다.

## 공개 서비스 API

```kotlin
class GuidelineSearchService(inputReader: GuidelineSearchInputReader) {
    fun search(request: GuidelineSearchRequest): List<MatchedGuideline>
}

object GuidelineSearchFactory {
    fun fileSystem(): GuidelineSearchService
}

class GuidelineSearchRequest(
    val cwd: Path,
    filepaths: Collection<Path>,
)

data class MatchedGuideline(
    val id: GuidelineId,
    val content: String,
)
```

`cwd`와 각 `filepath`는 절대 경로입니다. 요청은 생성 시 전달받은 파일 경로 목록의 복사본을 보유합니다. 결과는 지침 ID와 UTF-8 본문을 보존합니다.

`GuidelineSearchService`는 무인자 생성자를 제공하지 않습니다. 인메모리 입력 등 별도 읽기 경계를 사용하는 경우 `GuidelineSearchInputReader`를 생성자에 전달하고, 실제 파일 시스템을 사용하는 경우 `GuidelineSearchFactory.fileSystem().search(request)`로 호출합니다.

## 외부 읽기 경계

```kotlin
interface GuidelineSearchInputReader {
    fun validateWorkingDirectory(cwd: Path)
    fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile>
    fun readGuidelines(root: Path): List<Guideline>
    fun readRepositoryIdentity(cwd: Path): RepositoryIdentity
}
```

서비스는 경로의 절대성을 확인하고 정규화한 뒤, 동일한 파일 경로를 한 번만 읽도록 정리합니다. 이어서 읽기 경계를 통해 작업 디렉터리를 확인하고 소스, 지침, 저장소 식별자를 차례로 읽습니다. 소스나 지침이 비어 있으면 이후 입력을 요구하지 않고 빈 결과를 반환합니다. Core 검색은 이 경계에 의존하지 않습니다.

## Core API

```kotlin
class GuidelineSearch {
    fun search(
        repository: RepositoryIdentity,
        event: GuidelineEvent,
        sources: Collection<SourceFile>,
        guidelines: Collection<Guideline>,
    ): List<MatchedGuideline>
}

data class SourceFile(
    val filepath: Path,
    val content: String,
    val language: Language,
)
```

Core 검색은 파일별 `language`와 공통 `repository`, `event`로 지침 적용 여부를 확인한 뒤 해당 소스 코드 내용의 관련성을 평가합니다. 결과는 입력 지침 순서를 따르며, 여러 파일에서 같은 지침이 일치해도 ID당 한 번 반환합니다.

`Scope`를 생성할 때 저장소 또는 언어 조건 항목이 빈 문자열이거나 공백으로만 이루어져 있으면 `IllegalArgumentException`이 발생합니다. 유효한 값과 공백 조건이 함께 있어도 생성이 거부됩니다. 조건 목록 자체가 비어 있는 것은 유효하며, 해당 범위는 일치하지 않습니다.

## 검색 규칙

- 지침은 `cwd/.agents/guides` 아래에서 `guideline.yaml`을 재귀적으로 찾습니다.
- 지침 후보는 루트 기준 상대 경로의 사전순으로 평가합니다. 결과는 후보 순서를 유지합니다.
- `.kt`와 `.kts` 파일만 Kotlin 소스 코드로 검색합니다. 경로를 정규화한 뒤 파일을 한 번 읽고 해당 내용은 모든 지침이 공유합니다.
- 서비스 검색 이벤트는 `file_changed`입니다.
- Repository와 language 조건은 모두 만족해야 합니다. 각 조건 목록 안에서는 하나 이상이 일치해야 합니다. `*`는 전체 조건이며 저장소 조건은 기존 `Scope`의 별표 패턴을 따릅니다.
- `match.any`에는 정규식을 0개 또는 1개 지정할 수 있습니다. 0개면 일치하지 않습니다. 정규식은 소스 코드 안의 부분 일치로 평가합니다.
- 여러 지침이 일치하면 모두 반환합니다. YAML에 중복 ID가 있으면 지침 로딩 오류입니다.

## 빈 결과와 실패

빈 파일 경로 목록, 비 Kotlin 파일만 있는 요청, `.agents/guides` 부재, 지침 후보가 없는 경우에는 빈 목록을 반환합니다. `.agents/guides`가 디렉터리가 아닌 파일이면 지침 로딩 오류입니다. 유효한 Kotlin 대상이 없으면 저장소 식별자도 요구하지 않습니다.

| 상황 | 예외 |
| --- | --- |
| `cwd`가 상대 경로, 미존재 경로 또는 파일인 경우 | `InvalidSearchRequestException` |
| 상대 `filepath`가 요청에 포함된 경우 | `InvalidSearchRequestException` |
| 대상 Kotlin 파일이 없거나 디렉터리이거나 읽기 실패인 경우 | `SourceReadException` |
| `.agents/guides`가 디렉터리가 아닌 파일이거나 YAML 문법, 필수 필드, 필드 타입, 중복 키, 정규식 또는 중복 ID가 잘못된 경우 | `GuidelineLoadException` |
| Kotlin 대상 파일과 지침 후보가 있는데 `origin`을 얻거나 정규화할 수 없는 경우 | `RepositoryIdentityUnavailableException` |

모든 예외는 `RuntimeException`을 상속하며 한글 메시지를 제공합니다. 원인이 있는 실패는 `cause`를 보존합니다. 예상하지 못한 오류를 빈 결과로 바꾸지 않습니다.

SSH `origin`은 콜론을 쓰는 `git@host:owner/repository.git` 형식과 URL인 `ssh://git@host/owner/repository.git` 형식을 허용합니다. HTTPS `origin`도 허용하며, 세 형식 모두 `owner/repository`로 정규화합니다. 예를 들어 `git@host:acme/backend-order.git`, `ssh://git@host/acme/backend-order.git`, `https://host/acme/backend-order.git`은 `acme/backend-order`를 나타냅니다.

## 범위

검색 계약은 지침 탐색 결과까지만 다룹니다. Session 식별, Presentation 기록, SQLite 저장, Codex adapter는 이 API에 포함하지 않습니다.
