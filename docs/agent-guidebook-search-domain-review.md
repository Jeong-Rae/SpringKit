# Agent Guidebook 검색 도메인 검토

## 목표와 범위

검색 요청의 절대 `cwd`와 `filepaths`로 프로젝트의 지침과 Kotlin 소스를 읽고, 기존 도메인 규칙으로 일치한 지침의 `id`와 `content`를 반환합니다. 이번 범위는 Application 검색 행위와 읽기 경계, 도메인 검색 규칙까지입니다. Presentation, SQLite, Codex adapter, 다른 언어는 포함하지 않습니다.

새 Entity, Aggregate Root, Value Object를 계층을 채우기 위해 추가하지 않습니다. 기존 `Guideline`, `Scope`, `MatchCondition`, `RepositoryIdentity`, `SourceFile`, `MatchedGuideline`을 사용합니다. 검색 도메인 서비스 `GuidelineSearch`는 상태를 저장하지 않고 각 `Guideline`에 적용 여부와 소스 관련성 판단을 요청합니다.

## 현재 책임과 경계

`GuidelineSearch`는 전달받은 지침 순서로 소스들을 평가하고, 한 지침이 여러 소스에 일치해도 ID당 한 번 결과를 반환합니다. 파일별 언어는 각 `SourceFile`에서 가져옵니다. `Guideline.isApplicable`은 `Scope`와 이벤트를 평가하고, `Guideline.matches`는 `MatchCondition`에 소스 내용을 전달합니다.

현재 `GuidelineSearchService`는 이 검색 흐름 외에 `Files.isDirectory`를 직접 호출하고 `GuidelineReader`, `SourceFileReader`, `RepositoryIdentityReader`를 생성합니다. Application 행위가 실제 파일 시스템과 Git 실행 방식에 결합되어 있으므로 서비스가 읽기 의존성을 협력자로 받아야 합니다. 도메인 검색은 순수 입력만 사용하며 읽기 경계에 의존하지 않습니다.

## 읽기 경계와 조립

Application이 소유하는 하나의 외부 읽기 계약을 둡니다.

```kotlin
interface GuidelineSearchInputReader {
    fun validateWorkingDirectory(cwd: Path)
    fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile>
    fun readGuidelines(root: Path): List<Guideline>
    fun readRepositoryIdentity(cwd: Path): RepositoryIdentity
}
```

`validateWorkingDirectory`는 존재하지 않거나 디렉터리가 아닌 경로, 접근 실패를 `InvalidSearchRequestException`으로 전달합니다. 원인이 있으면 예외 원인을 보존합니다. `readKotlinSources`, `readGuidelines`, `readRepositoryIdentity`는 각각 기존 `SourceFileReader`, `GuidelineReader`, `RepositoryIdentityReader`가 정한 검색 읽기 오류를 유지합니다.

`storage.FileSystemGuidelineSearchInputReader`는 이 계약을 구현하고 기존 세 읽기 객체와 디렉터리 검사를 조합합니다. `services.GuidelineSearchService`는 포트만 받아 Application 흐름을 조정합니다. 공개 무인자 생성자는 제거합니다. 실제 파일 시스템 구현은 다음 조립 함수가 제공합니다.

```kotlin
object GuidelineSearchFactory {
    fun fileSystem(): GuidelineSearchService
}
```

호출자는 `GuidelineSearchFactory.fileSystem().search(request)`를 사용합니다. `GuidelineSearchService.search(request)`의 공개 입력, 출력, 예외 계약은 유지합니다. 추가 Client facade는 만들지 않습니다.

## 검색 실행 순서

1. 서비스는 `cwd`와 각 파일 경로가 절대 경로인지 검증합니다.
2. 서비스는 경로를 정규화하고, 입력 순서를 유지하며 동일한 정규화 경로를 한 번만 남깁니다.
3. 포트가 작업 디렉터리의 존재와 디렉터리 형식을 확인합니다.
4. 포트가 Kotlin 대상 소스를 한 번의 묶음 읽기로 반환합니다. `.kt`와 `.kts`만 Kotlin 소스로 다루며, 각 정규화 경로의 소스는 검색 안에서 모든 지침이 공유합니다.
5. 소스가 없으면 빈 결과를 반환합니다. 저장소 식별자와 지침은 요구하지 않습니다.
6. 포트가 `cwd/.agents/guides`의 `guideline.yaml`을 재귀적으로 읽습니다. 지침 디렉터리나 후보가 없으면 빈 결과를 반환합니다.
7. 지침 후보는 루트 기준 상대 경로의 사전순을 유지합니다. 후보가 없으면 저장소 식별자를 요구하지 않습니다.
8. 포트가 Git `origin`에서 저장소 식별자를 읽고, 서비스는 `file_changed`와 함께 기존 `GuidelineSearch`에 전달합니다.
9. 도메인은 지침 순서로 결과를 만들고, ID와 UTF-8 본문을 보존합니다.

`readKotlinSources`는 검색 요청마다 실행하여 같은 서비스의 후속 검색에서도 변경된 파일을 읽습니다. 검색은 파일이나 YAML을 수정하지 않습니다. 기존 `RepositoryIdentityReader`가 지원하는 SCP 형태 SSH, `ssh://` URL, HTTPS 주소를 유지합니다.

## 계약

| 구분 | 계약 |
| --- | --- |
| 입력 | `cwd`와 `filepaths`는 절대 경로입니다. 요청은 생성 시 파일 경로 목록의 복사본을 보유합니다. |
| 결과 | 성공한 검색은 매칭 지침의 `id`와 `content`를 후보 경로 순서대로 반환합니다. 같은 지침은 여러 파일에 일치해도 한 번 반환합니다. |
| 조건 | 저장소 범위와 언어 범위는 모두 만족해야 합니다. 각 범위 목록 안에서는 하나 이상 일치해야 합니다. 언어는 소스별로 판단합니다. |
| 이벤트 | 서비스 검색 이벤트는 `file_changed`입니다. |
| 정규식 | `match.any`는 0개 또는 1개입니다. 0개면 비일치입니다. 하나면 소스 내용의 부분 일치를 찾습니다. 2개 이상 또는 문법 오류는 지침 오류입니다. |
| 빈 결과 | 파일 목록이 비었거나 Kotlin 파일이 없거나 지침 경로·후보가 없으면 빈 목록을 반환합니다. 지침 경로가 파일이면 지침 오류입니다. |
| 요청 거부 | 상대 `cwd` 또는 상대 파일 경로는 읽기 전에 `InvalidSearchRequestException`으로 거부합니다. 존재하지 않거나 파일인 `cwd`도 같은 예외로 거부합니다. |
| 소스 실패 | 대상 `.kt` 또는 `.kts` 파일이 없거나 일반 파일이 아니거나 엄격한 UTF-8 읽기에 실패하면 `SourceReadException`을 전달합니다. 원인이 있으면 보존합니다. |
| 지침 실패 | YAML 구문·구조·타입·필수 값·중복 키·정규식·중복 ID 오류는 `GuidelineLoadException`으로 전달합니다. |
| 저장소 실패 | 소스와 지침 후보가 모두 있고 `origin`을 읽거나 정규화하지 못하면 `RepositoryIdentityUnavailableException`을 전달합니다. |
| 부수 효과 | Application과 도메인은 파일과 Git 정보를 읽기만 합니다. 파일 내용, YAML 바이트, 검색 입력 컬렉션을 변경하지 않습니다. |
| 상태 | 검색 서비스와 도메인 검색은 요청 간 상태를 보유하지 않습니다. 같은 입력을 다시 검색하면 같은 결과를 반환하고, 후속 호출에서는 현재 파일을 다시 읽습니다. |

## 도메인 생성 조건

`Scope`는 저장소와 언어 조건을 모두 평가합니다. 각 목록은 OR, 두 목록 사이는 AND입니다. 저장소 패턴의 `*` 동작과 언어 `*` 전체 일치 규칙은 기존 계약을 유지합니다.

빈 저장소 목록이나 빈 언어 목록은 유효한 값이며 어떤 Context에도 일치하지 않습니다. 목록 안의 빈 문자열 또는 공백만 있는 값은 `Scope`를 만들 때 `IllegalArgumentException`으로 거부합니다. 이 제약은 YAML 파서에만 두지 않고 도메인 생성 계약으로 둡니다.

검색은 조회 행위이므로 `Guideline`, `SourceFile`, 검색 입력을 변경하지 않습니다. 상태 전이, 불법 상태, 저장된 결과는 없습니다. 유효하지 않은 요청이나 읽기 실패는 빈 결과로 바꾸지 않고 호출자가 처리할 수 있는 타입 예외로 전달합니다.

## 테스트 책임과 순서

1. `GuidelineSearchTest`는 실제 `Guideline`, `Scope`, `MatchCondition`, `SourceFile` 협력으로 조건 조합, 파일별 언어, 입력 순서, 중복 ID, 반복 조회의 동일 결과를 검증합니다. 기존 검색 테스트는 유지합니다.
2. `GuidelineTest`는 `isApplicable`이 `Scope`와 이벤트를 평가하고 `matches`가 `MatchCondition`에 소스 판단을 맡기는 계약을 유지합니다. 기존 테스트가 이미 이 책임을 검증하므로 중복 테스트를 추가하지 않습니다.
3. `ScopeTest`는 공백 조건 거부를 추가로 고정하고, 빈 목록의 비일치 테스트를 유지합니다.
4. Application 단위 테스트는 실제 도메인 객체와 외부 읽기 경계의 메모리 Fake를 사용합니다. Mock 및 상호작용 횟수 검증은 사용하지 않습니다.
5. Application 진입 통합 테스트만 임시 파일 시스템과 로컬 Git 저장소를 사용합니다. 실제 YAML·Kotlin 소스·origin을 연결하고 재검색, 입력 보존, typed error와 정상 빈 결과를 검증합니다.

테스트 계약 커밋이 모두 끝날 때까지 외부 읽기 경계, factory, `Scope` 검증의 실제 동작을 구현하지 않습니다. 프로덕션 변경은 계약에 필요한 컴파일 선언과 한글 `NotImplementedError` stub으로 제한합니다.
