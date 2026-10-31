# Agent Guidebook Presentation 도메인 검토

## 목표와 범위

같은 Codex Session에는 검색된 Guideline을 한 번만 반환하고, 제공 이력을 SQLite에 남깁니다. Application 행위는 기존 `GuidelineSearchService.search`와 `GuidelineSearch`, `MatchedGuideline`을 재사용합니다.

이번 설계는 Presentation 도메인과 Application, 영속성 경계를 정합니다. Hook, CLI 연결, Codex 응답 변환, 새 Session 생명주기, TTL, 이력 삭제, 캐시, ORM은 포함하지 않습니다. 기존 PoC 패키지는 이동하지 않습니다. 기존 `core`, `services`, `storage`, `client`, `models`, `errors` 패키지 안에 필요한 책임을 추가합니다.

## 도메인 책임

`PresentationSession`은 한 Session의 Presentation 이력을 소유하는 Aggregate Root(집합 루트)입니다. 이 객체는 검색 결과 중 아직 제공하지 않은 Guideline을 조회하고, 제공 사실을 기록하는 두 행동을 맡습니다. Application은 저장된 ID를 꺼내 직접 필터링하지 않습니다.

`Presentation`은 특정 Session에 특정 Guideline을 제공한 사실을 나타내는 Entity(엔티티)입니다. Entity의 업무상 식별 기준은 `sessionId + guidelineId`입니다. `presentedAt`은 기록의 시각이며 식별 기준에 포함하지 않습니다.

`SessionId`는 Session 범위를 나타내는 Value Object(값 객체)입니다. 비어 있거나 공백뿐인 문자열을 거부하고 입력 문자열을 보존합니다. 기존 `GuidelineId`를 재사용합니다.

`MatchedGuideline`은 기존 검색 도메인의 결과입니다. Presentation 도메인은 검색 규칙을 복제하거나 검색 결과의 순서를 바꾸지 않습니다.

## 객체 계약

```kotlin
class PresentationSession(
    val id: SessionId,
    history: Collection<Presentation>,
) {
    fun notPresented(guidelines: Collection<MatchedGuideline>): List<MatchedGuideline>

    fun record(presentations: Collection<Presentation>)
}

class Presentation(
    val sessionId: SessionId,
    val guidelineId: GuidelineId,
    val presentedAt: Instant,
)

@JvmInline
value class SessionId(val value: String)
```

`PresentationSession`은 생성 시 이력 컬렉션을 방어 복사합니다. 이력에 다른 Session의 `Presentation`이 있거나 Guideline ID가 중복되면 `IllegalArgumentException`으로 생성을 거부합니다. 호출자에게 내부 컬렉션을 노출하지 않습니다.

`notPresented`는 상태를 바꾸지 않는 조회입니다. 전달된 `MatchedGuideline` 순서를 보존하고, 세션 이력에 없는 Guideline만 반환합니다. 같은 Guideline ID가 여러 번 입력되면 첫 항목만 반환합니다. 판정 기준은 본문이 아니라 ID이므로 같은 ID의 `content`가 바뀌어도 재제공하지 않습니다.

`record`는 상태를 바꾸는 명령입니다. 입력 컬렉션을 먼저 복사하고 전체를 검증한 다음 이력을 한 번에 갱신합니다. 다른 Session의 기록이 있거나 입력 안에서 같은 Guideline ID가 중복되면 `IllegalArgumentException`을 던집니다. 이미 기록한 Guideline이 포함되면 `IllegalStateException`을 던집니다. 거부된 호출은 Aggregate 상태를 일부도 변경하지 않습니다. 빈 컬렉션은 상태를 바꾸지 않습니다.

조회와 명령을 나눈 이유는 `notPresented`가 기록까지 암묵적으로 수행하거나 `record`가 선택과 저장을 대신하지 않게 하기 위해서입니다. 두 호출은 하나의 Application 트랜잭션에서 순서대로 협력합니다.

## Application 흐름

공개 행위는 `GuidelinePresentationService.present(request)`입니다. 요청은 기존 검색 입력과 Session 식별자를 함께 보유합니다.

```kotlin
class GuidelinePresentationRequest(
    val sessionId: SessionId,
    val cwd: Path,
    filepaths: Collection<Path>,
) {
    val filepaths: List<Path>
}

class GuidelinePresentationService(
    private val guidelineSearchService: GuidelineSearchService,
    private val presentationRepository: PresentationRepository,
    private val clock: Clock,
) {
    fun present(request: GuidelinePresentationRequest): List<MatchedGuideline>
}
```

요청은 `GuidelineSearchRequest`와 같이 입력 파일 경로를 생성 시 방어 복사합니다. 서비스는 `GuidelineSearchRequest(request.cwd, request.filepaths)`를 만들어 기존 검색 서비스에 전달합니다. 검색은 SQLite 트랜잭션 밖에서 수행합니다. 검색 오류는 기존 타입 예외와 원인을 그대로 전달하며 Presentation 이력은 바뀌지 않습니다.

검색 결과가 비어 있으면 빈 목록을 반환하고 SQLite를 열지 않습니다. 결과가 있으면 다음 처리를 하나의 저장소 트랜잭션 콜백 안에서 수행합니다.

```kotlin
val matched = guidelineSearchService.search(
    GuidelineSearchRequest(request.cwd, request.filepaths),
)

if (matched.isEmpty()) {
    return emptyList()
}

return presentationRepository.inImmediateTransaction { transaction ->
    val history = transaction.presentations(request.sessionId)
    val session = PresentationSession(request.sessionId, history)
    val newGuidelines = session.notPresented(matched)

    if (newGuidelines.isEmpty()) {
        return@inImmediateTransaction emptyList()
    }

    val presentedAt = clock.instant()
    val presentations =
        newGuidelines.map { guideline ->
            Presentation(request.sessionId, guideline.id, presentedAt)
        }

    session.record(presentations)
    transaction.record(presentations)
    newGuidelines
}
```

저장소 콜백은 검색 종료 후 `BEGIN IMMEDIATE`로 시작합니다. 이력 조회, Aggregate 생성, 선별, 기록, 행 저장, commit이 모두 같은 트랜잭션 안에서 일어납니다. 저장소 콜백은 commit 성공 뒤에만 결과를 반환합니다. `present`도 commit 성공 뒤에만 `MatchedGuideline` 목록을 반환합니다.

## 영속성 경계

```kotlin
interface PresentationRepository {
    fun <T> inImmediateTransaction(
        operation: (PresentationTransaction) -> T,
    ): T
}

interface PresentationTransaction {
    fun presentations(sessionId: SessionId): List<Presentation>

    fun record(presentations: Collection<Presentation>)
}
```

`PresentationRepository`는 트랜잭션의 시작과 종료를 보장합니다. `PresentationTransaction`의 조회와 명령은 같은 연결과 트랜잭션을 사용합니다. 조회가 돌려준 목록은 저장소 내부 상태와 분리된 복사본입니다. 저장 경계는 행을 기록할 뿐 도메인 선별 규칙을 구현하지 않습니다.

`storage.SqlitePresentationRepository`의 기본 factory는 `~/.adaptive-guidebook/adaptive-guidebook.db`를 사용합니다. 사용자 홈은 `user.home`에서 구합니다. `SqlitePresentationRepository(databasePath: Path)` 생성자는 통합 테스트가 임시 DB 파일을 지정할 때 사용합니다. 별도의 builder나 설정 API는 추가하지 않습니다. 연결마다 `PRAGMA busy_timeout = 1000`을 적용하고, 이력 조회 전에 `BEGIN IMMEDIATE`를 실행합니다. 예외가 나면 전체 트랜잭션을 rollback합니다. commit 실패 시에도 결과를 반환하지 않습니다.

```sql
CREATE TABLE IF NOT EXISTS presentation (
    session_id TEXT NOT NULL,
    guideline_id TEXT NOT NULL,
    presented_at TEXT NOT NULL,
    PRIMARY KEY (session_id, guideline_id)
);
```

`presented_at`은 Application의 `Clock`이 제공한 `Instant`를 UTC 기준 RFC 3339 문자열로 저장합니다. 한 `present` 호출에서 새로 기록하는 행은 같은 시각을 사용합니다. 새 Session 테이블은 만들지 않습니다. `PresentationSession`은 각 호출의 트랜잭션 안에서 저장 행으로부터 구성하는 도메인 객체입니다.

기본 조립은 `GuidelinePresentationFactory.fileSystem()`이 기존 파일 검색 경계와 SQLite 저장소, `Clock.systemUTC()`를 연결합니다. Application 단위 테스트는 실제 도메인 객체와 메모리 Fake, 고정 Clock을 사용합니다. 테스트는 저장소 구현이나 SQLite 연결을 Application에 노출하지 않습니다. 기본 factory의 경로 검증은 독립 테스트 JVM에 임시 `-Duser.home` 값을 전달해 수행합니다. 사용자 `HOME` 환경 변수나 실제 사용자 홈의 DB는 변경하지 않습니다.

## 입력, 조건과 출력

| 구분 | 계약 |
| --- | --- |
| 입력 | `sessionId`, 절대 `cwd`, 절대 `filepaths`를 받습니다. 경로 검증과 정규화는 기존 검색 서비스 계약을 따릅니다. |
| 검색 조건 | 기존 `GuidelineSearchService.search`와 동일한 Repository, Kotlin 언어, `file_changed`, YAML 및 정규식 규칙을 적용합니다. |
| Presentation 조건 | 같은 `sessionId + guidelineId` 기록이 없을 때만 결과에 포함합니다. 본문 변경은 이력 키에 영향을 주지 않습니다. |
| 출력 | 새로 제공할 `MatchedGuideline(id, content)` 목록을 검색 순서로 반환합니다. |
| 빈 검색 | 검색 결과가 없으면 빈 목록을 반환하고 파일 검색의 기존 빈 결과 규칙을 유지합니다. SQLite 트랜잭션은 시작하지 않습니다. |
| 이미 제공된 결과 | 검색 결과가 모두 이력에 있으면 빈 목록을 반환합니다. 조회 트랜잭션은 commit되어야 결과를 반환합니다. |
| 부수 효과 | 새 결과가 있을 때만 SQLite 행을 추가합니다. 파일, YAML, Git 저장소, 검색 입력, 외부 제공 결과를 변경하지 않습니다. |
| 상태 | Session 이력만 SQLite에 영속됩니다. 서비스와 Aggregate 인스턴스는 요청이 끝난 뒤 상태를 공유하지 않습니다. |

## 실패 의미와 상태

`SessionId`가 비어 있거나 공백뿐이면 VO 생성 시 `IllegalArgumentException`을 던집니다. Presentation 기록의 Session 불일치나 한 명령 안의 중복 Guideline ID도 `IllegalArgumentException`입니다. 이력에 이미 존재하는 Guideline을 다시 기록하는 도메인 명령은 `IllegalStateException`입니다. 모든 실패에서 Aggregate 이력은 호출 전 상태로 남습니다.

SQLite의 `BUSY` 또는 `LOCKED`가 1,000ms 제한 뒤에도 지속되면 `RetryablePresentationStorageException`으로 변환합니다. 호출자는 동일 요청을 재시도할 수 있습니다. 경로 접근 권한, 읽기 전용 파일, 디스크 공간 부족 등 환경에서 해결해야 하는 오류는 `PresentationStorageUnavailableException`으로 전달합니다. `CORRUPT` 또는 `NOTADB`는 `PresentationDatabaseCorruptException`으로 전달하며 호출자는 DB 복구 또는 백업 복원을 마친 뒤 재개해야 합니다. 원인을 확인할 수 없는 영속성 실패는 `PresentationStorageException`으로 전달합니다. 이 오류들은 모두 unchecked 예외이며 원인 예외를 보존합니다.

검색 오류는 검색 계약에 정의된 `InvalidSearchRequestException`, `SourceReadException`, `GuidelineLoadException`, `RepositoryIdentityUnavailableException`을 그대로 전달합니다. 검색 또는 저장 실패를 빈 성공으로 바꾸지 않습니다.

DB 행을 `SessionId`, `GuidelineId`, `Instant`로 재구성하지 못하거나 저장된 이력이 중복되면 저장 경계가 `PresentationDatabaseCorruptException`으로 변환합니다. 원래 변환 원인을 보존합니다. 도메인 오류나 Application callback 오류는 전체 변경을 rollback한 뒤 원래 타입과 원인을 그대로 전달합니다. 저장소 오류가 이를 덮지 않습니다.

SQL 기록이나 commit에 실패하면 전체 변경을 rollback합니다. rollback도 실패하면 `PresentationTransactionRollbackException`을 던집니다. 이 경우 원래 작업 실패를 cause로 보존하고 rollback 실패를 suppressed 예외로 남깁니다. 트랜잭션 결과가 불확실하므로 호출자는 이를 자동 재시도 대상으로 처리하지 않고 저장소 상태를 확인합니다. commit 성공 후 응답을 만들기 전에 프로세스가 중단되면 기록은 남습니다. 다음 요청은 해당 Guideline을 제외합니다. 수신 확인, 자동 재전송, 중복 반환은 제공하지 않습니다.

## 호출 시나리오

| 구분 | Given | When | Then |
| --- | --- | --- | --- |
| 첫 제공 | Session 이력이 없고 검색 결과가 있습니다. | `present`를 호출합니다. | 검색 순서의 결과를 반환하고 각 ID를 한 번 기록합니다. |
| 같은 Session 재검색 | 일부 또는 전체 ID가 이미 기록되어 있습니다. | 같은 소스를 다시 `present`합니다. | 새 ID만 검색 순서로 반환하고 기존 ID는 본문이 바뀌어도 제외합니다. |
| 다른 Session | 기존 Session에 같은 Guideline ID가 있습니다. | 새 `sessionId`로 `present`합니다. | 해당 Session에는 새 제공으로 반환하고 기록합니다. |
| 결과 없음 | 검색 결과가 비어 있습니다. | `present`를 호출합니다. | 빈 목록을 반환하고 Presentation 저장소를 사용하지 않습니다. |
| 입력 경계 | `cwd` 또는 filepath가 기존 검색 계약을 위반합니다. | `present`를 호출합니다. | 검색 예외를 전달하고 Presentation 이력을 바꾸지 않습니다. |
| 동시 요청 | 독립 연결 두 개가 같은 Session과 Guideline을 동시에 요청합니다. | CountDownLatch 또는 자식 프로세스의 READY/GO 신호로 요청 시작을 조율합니다. | 잠금을 얻은 요청 하나만 Guideline을 반환합니다. 다른 요청은 빈 결과를 반환하거나 제한 시간 후 재시도 가능 예외를 받습니다. |
| 기록 실패 | 두 번째 SQL 행 삽입이 실패합니다. | 여러 새 결과를 저장합니다. | 전체 rollback으로 첫 행도 남지 않고 성공 목록을 반환하지 않습니다. |
| rollback 실패 | 원래 SQL 실패 뒤 rollback도 실패합니다. | 저장 요청을 처리합니다. | `PresentationTransactionRollbackException`을 전달하고 두 원인을 보존합니다. |
| commit 뒤 응답 전 중단 | 기록 transaction은 commit됐습니다. | 응답 전에 프로세스가 종료된 뒤 재호출합니다. | 기록된 Guideline은 재호출 결과에서 제외됩니다. |
