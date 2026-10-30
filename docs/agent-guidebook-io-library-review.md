# Agent Guidebook 입출력 라이브러리 검토

검토 기준: 2026년 10월 30일, `agent-guidebook` 현재 검색 계약

이 문서는 구현 전 중간보고입니다. 이번 단계에서는 검색 통합 테스트에 기존 동작을 고정하는 시나리오를 추가했으며 프로덕션 코드는 수정하지 않았습니다.

## 결정 요약

| 대상 | 결정 | 근거 |
| --- | --- | --- |
| Git 저장소 탐색과 origin 파싱 | JGit 7.8 검토 후 적용 | 프로세스 수명 관리와 SCP 주소 정규식을 없애고 저장소 탐색 및 URL 파싱을 라이브러리에 맡길 수 있습니다. |
| 엄격한 UTF-8 파일 읽기 | JDK `Files.readString` 사용 | 소스 파일과 YAML 파서의 중복 디코더를 없애며 잘못된 UTF-8 거부와 기존 예외 변환을 유지합니다. |
| YAML 문서 변환 | SnakeYAML 유지 | DTO 전환이 필드 검증 코드를 줄일 수 있지만, 현재 계약을 엄격히 유지할 구성과 새 의존성까지 합친 순감소가 확인되지 않았습니다. |
| 지침 파일 탐색 | `Files.walk` 유지 | JDK 표준 API가 이미 재귀 탐색, 자원 종료, 정렬에 필요한 기능을 제공합니다. 별도 파일 시스템 라이브러리는 부담을 줄이지 않습니다. |
| 테스트 및 Hook 프로세스 | 현재 방식 유지 | 프로세스 경계의 실제 입력·출력·종료 코드와 독립 JVM 동작을 검증합니다. Hook 스크립트는 실행 파일로 바로 연결하는 얇은 `exec` 래퍼입니다. |

JGit 7.8.0의 Maven 버전은 `7.8.0.202609011348-r`입니다. Eclipse는 7.8.0을 2026년 9월 9일 릴리스로 기록하고, 8.0.0은 2026년 12월 릴리스로 예정하고 있습니다. 따라서 이번 선택 후보는 7.8.0입니다. [Eclipse JGit 7.8.0 릴리스](https://projects.eclipse.org/projects/technology.jgit/releases/7.8.0), [Eclipse JGit 릴리스 목록](https://projects.eclipse.org/projects/technology.jgit/governance), [Maven Central 배포 POM](https://repo.maven.apache.org/maven2/org/eclipse/jgit/org.eclipse.jgit/7.8.0.202609011348-r/org.eclipse.jgit-7.8.0.202609011348-r.pom)

## JGit 적용 경계

현재 `RepositoryIdentityReader`는 `git -C <cwd> remote get-url origin` 프로세스를 시작하고, 출력을 읽고, 종료를 기다리며, 종료 실패와 중단을 별도로 처리합니다. 주소 변환에는 URI 처리와 SCP 정규식도 사용합니다. JGit의 `FileRepositoryBuilder`와 `URIish`가 저장소 발견 및 Git 주소 파싱을 제공하므로 이 수동 처리를 줄일 수 있습니다. 저장소는 `cwd`에서 위로 탐색하고, 반환한 `Repository`는 `use`로 닫는 형태가 적절합니다. [현재 구현](../agent-guidebook/src/main/kotlin/com/springkit/agentguidebook/storage/RepositoryIdentityReader.kt), [FileRepositoryBuilder 소스](https://github.com/eclipse-jgit/jgit/blob/v7.8.0.202609011348-r/org.eclipse.jgit/src/org/eclipse/jgit/storage/file/FileRepositoryBuilder.java), [URIish 소스](https://github.com/eclipse-jgit/jgit/blob/v7.8.0.202609011348-r/org.eclipse.jgit/src/org/eclipse/jgit/transport/URIish.java)

JGit 구성은 읽기 전용 저장소 설정만 조회해야 합니다. 원격 fetch, 연결, 설정 변경은 하지 않습니다. JGit의 시스템 설정 발견 코드는 환경에 따라 `git --version` 또는 `git config`를 실행할 수 있습니다. 따라서 직접 구현한 origin 조회 프로세스는 제거해도, JGit 내부까지 포함해 프로세스를 전혀 실행하지 않는다고 보장하지 않습니다. 이를 막으려고 전역 `SystemReader`나 별도 파일 시스템 구현을 추가하지 않습니다. [JGit 파일 시스템 설정 발견 소스](https://github.com/eclipse-jgit/jgit/blob/v7.8.0.202609011348-r/org.eclipse.jgit/src/org/eclipse/jgit/util/FS.java)

`RemoteConfig` 생성자는 설정된 origin URL을 모두 `URIish`로 즉시 파싱합니다. 기존 `git remote get-url origin`은 기본 호출에서 첫 URL만 반환하므로 이를 그대로 대체하면 뒤에 있는 잘못된 URL 때문에 결과가 달라질 수 있습니다. 첫 URL만 보존하려면 저장소의 유효 설정에서 `remote.origin.url` 첫 값을 읽고, `UrlConfig.replace`로 `url.*.insteadOf` 치환을 적용한 뒤, 그 값을 `URIish`로 파싱하는 방식이 맞습니다. [RemoteConfig 소스](https://github.com/eclipse-jgit/jgit/blob/v7.8.0.202609011348-r/org.eclipse.jgit/src/org/eclipse/jgit/transport/RemoteConfig.java), [UrlConfig 소스](https://github.com/eclipse-jgit/jgit/blob/v7.8.0.202609011348-r/org.eclipse.jgit/src/org/eclipse/jgit/transport/UrlConfig.java)

`URIish`는 여러 프로토콜을 허용하며 query와 fragment를 별도 값으로 제공하지 않습니다. 따라서 `URIish`만으로 기존 유효성 검사를 대체하면 안 됩니다. HTTPS와 `ssh://` 주소는 기존 `java.net.URI` 검증을 유지해 허용 scheme, 비어 있지 않은 host, query와 fragment 거부를 확인합니다. SCP 주소는 `URIish`로 파싱하되 기존 저장소 경로 조건을 별도로 확인합니다. 두 형식 모두 다음 정규화를 유지합니다.

- `owner/repository`의 두 경로 항목만 허용합니다.
- 경로에서 비어 있는 항목을 제거한 뒤 두 항목인지 확인합니다.
- 저장소 이름에서 소문자 `.git` 접미사만 제거합니다.
- `RepositoryIdentity`의 공백 및 슬래시 검증을 그대로 사용합니다.
- 모든 저장소 탐색, 설정, 파싱 실패를 `RepositoryIdentityUnavailableException`으로 변환하고 원인을 보존합니다.

검색 서비스의 입력·출력·조건·상태·부수 효과 계약은 유지합니다. 입력은 서비스가 정규화한 절대 `cwd`입니다. 성공 출력은 기존 `RepositoryIdentity(owner, repository)`입니다. 읽기 경계는 호출 간 상태를 보유하지 않으며 파일, Git 설정, 네트워크 상태를 변경하지 않습니다. `RepositoryIdentityReader` 자체가 `IllegalArgumentException`이나 `IllegalStateException`을 공개하지 않고, 기존과 같이 원인을 담은 `RepositoryIdentityUnavailableException`을 전달합니다. Session이나 도메인 객체는 이 흐름에 추가하지 않습니다.

### 구현 전 확인한 기존 계약

기존 `GuidelineSearchIntegrationTest`는 SCP, SSH URL, HTTPS 정규화를 확인하고, origin 누락과 잘못된 주소의 예외를 확인했습니다. 서비스 진입 통합 테스트에 다음 기존 동작을 특성화하는 시나리오를 추가했습니다.

- 저장소 하위 디렉터리를 `cwd`로 전달해도 상위 저장소의 origin을 읽습니다.
- linked worktree의 `.git` 파일과 common directory를 통해 origin을 읽습니다.
- 저장소 로컬 `insteadOf` 규칙을 적용한 URL로 기존과 같은 저장소 ID를 얻습니다.
- origin URL이 여러 개면 첫 URL만 사용하며, 뒤의 URL 파싱 결과가 첫 결과를 바꾸지 않습니다.
- HTTPS 또는 SSH URL에 query나 fragment가 있으면 기존 예외를 전달합니다.

이 항목은 새 도메인 동작이 아니라 기존 CLI 동작의 특성화입니다. 도메인 테스트와 포트 계약은 변경하지 않았습니다. 추가한 시나리오는 현재 CLI 구현에서 통과했습니다. 테스트 변경은 프로덕션 구현과 분리해 검토하고 커밋합니다.

## 엄격한 UTF-8 읽기

`SourceFileReader`와 `GuidelineYamlParser`는 각각 `Files.readAllBytes`와 `CharsetDecoder`를 직접 조합해 잘못되었거나 매핑할 수 없는 UTF-8을 거부합니다. Java 25의 `Files.readString(path, UTF_8)`은 같은 입력에서 `IOException`을 던지고 파일을 닫습니다. 두 위치의 바이트 배열, `ByteBuffer`, `CodingErrorAction` 구성을 `Files.readString`으로 바꿀 수 있습니다. [현재 SourceFileReader](../agent-guidebook/src/main/kotlin/com/springkit/agentguidebook/storage/SourceFileReader.kt), [현재 GuidelineYamlParser](../agent-guidebook/src/main/kotlin/com/springkit/agentguidebook/storage/GuidelineYamlParser.kt), [Java 25 Files API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#readString(java.nio.file.Path,java.nio.charset.Charset))

각 경계의 예외 의미는 유지합니다. 소스 읽기의 `IOException`은 `SourceReadException`으로, YAML 읽기의 `IOException`은 `GuidelineLoadException`으로 변환하고 원인을 보존합니다. `SecurityException`도 현재처럼 각 경계의 예외로 변환합니다. 경로 검사, Kotlin 확장자 선별, 검색 입력의 불변성, 도메인 언어 판단은 변경하지 않습니다. 이 변경은 추가 라이브러리 없이 중복 디코딩 코드를 줄입니다.

## 유지할 처리

`GuidelineReader`의 `Files.walk(root).use`는 하위 `guideline.yaml` 탐색, 루트 상대 경로 정렬, stream 종료를 이미 담당합니다. Commons IO나 Okio를 추가해 감싸면 호출 경계와 의존성만 늘어납니다.

`ProcessBuilder`는 검색 테스트에서 로컬 Git fixture를 만들고, Hook 테스트에서 stdin, stdout, stderr, cwd, 종료 코드를 관찰하며, 독립 JVM 테스트에서 SQLite 동시 요청을 검증합니다. 이 프로세스 경계는 각각의 계약을 직접 확인하므로 공통 프로세스 라이브러리로 옮기지 않습니다. `hooks/post-tool-use.sh`도 입력과 출력을 해석하지 않고 설정된 실행 파일에 연결하는 계약을 유지합니다. SQLite Exposed 전환은 이번 검토 범위에서 다루지 않습니다.

## SnakeYAML 유지

현재 YAML 파서는 SnakeYAML 2.6 `SafeConstructor`와 중복 키 거부를 사용한 뒤 `Guideline`과 기존 도메인 객체로 변환합니다. 직접 검증하는 부분은 YAML 구조와 필드 타입입니다. 배열의 null 원소, 숫자나 Boolean의 문자열 강제 변환, 누락·명시적 null, 알 수 없는 필드, root 타입, 중복 키, 잘못된 사용자 정의 태그를 현재 실패 의미로 매핑합니다. 정규식 개수와 문법, 공백 scope 값, 중복 Guideline ID는 기존 도메인 및 로딩 오류 경계를 사용합니다.

Jackson YAML과 Kotlin module의 DTO 매핑은 필드 경로 검증을 줄일 수 있습니다. 다만 agent-guidebook에는 해당 의존성이 없으며, DTO뿐 아니라 중복 키, 알 수 없는 필드, scalar coercion, null 원소와 필수값을 엄격히 제한하는 설정이 필요합니다. 안전한 YAML 태그 처리와 기존 `GuidelineLoadException` 원인 보존도 별도로 확인해야 합니다. 기존 엄격한 계약을 유지하면서 DTO와 설정을 더한 전체 부담이 줄어든다는 근거가 없으므로 SnakeYAML을 유지합니다. [현재 YAML 파서](../agent-guidebook/src/main/kotlin/com/springkit/agentguidebook/storage/GuidelineYamlParser.kt)

## 다음 단계

1. 추가한 테스트를 검토하고 별도 커밋으로 마칩니다.
2. 별도 승인을 받은 뒤 `RepositoryIdentityReader`에 JGit을 적용하고 두 엄격 UTF-8 읽기를 `Files.readString`으로 단순화합니다.
3. 프로덕션 변경 뒤 검색 및 Presentation 검증으로 외부 계약을 확인합니다.

이번 단계의 `spotlessApply`, 지정 검색 통합 테스트, 증분 `build`가 모두 통과했습니다. 프로덕션 변경은 다음 단계에서 별도 승인 후 진행합니다.
