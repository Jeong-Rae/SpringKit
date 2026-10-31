# Agent Guidebook Presentation 계약

Agent Guidebook은 프로젝트에서 관련 Guideline을 검색하고, 같은 Session에 아직 제공하지 않은 항목만 반환합니다. Presentation 행위는 SQLite 이력을 관리하며 기존 검색 API의 규칙과 출력 순서를 유지합니다.

## 공개 Application API

```kotlin
class GuidelinePresentationRequest(
    val sessionId: SessionId,
    val cwd: Path,
    filepaths: Collection<Path>,
) {
    val filepaths: List<Path>
}

class GuidelinePresentationService(
    guidelineSearchService: GuidelineSearchService,
    unitOfWork: UnitOfWork,
    presentationRepository: PresentationRepository,
    clock: Clock,
) {
    fun present(request: GuidelinePresentationRequest): List<MatchedGuideline>
}

object GuidelinePresentationFactory {
    fun fileSystem(): GuidelinePresentationService
}
```

`GuidelinePresentationRequest`는 파일 경로 목록을 생성 시 방어 복사합니다. `SessionId`는 비어 있거나 공백뿐인 값을 허용하지 않고 원본 문자열을 보존합니다. `cwd`와 filepath 검증, 정규화, Kotlin 파일 선별은 `GuidelineSearchService.search` 계약을 그대로 따릅니다.

정상 출력은 새로 제공할 `MatchedGuideline` 목록입니다. 출력 순서는 기존 검색 후보 순서입니다. 같은 Session에서 이미 제공한 ID는 본문이 달라져도 출력하지 않습니다. 다른 Session에는 독립 이력을 적용합니다.

## 도메인 API

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

`notPresented`는 읽기 전용 조회입니다. Session 이력에 없는 Guideline ID를 입력 순서로 반환합니다. 같은 ID가 조회 입력에서 반복되면 첫 항목만 반환합니다. 조회 전후 Session 상태는 같습니다.

`record`는 기록 명령이며 반환값이 없습니다. 기록을 적용하기 전에 입력 전체를 복사하고 검증합니다. 다른 Session의 기록 또는 입력 내부의 중복 Guideline ID는 `IllegalArgumentException`, 기존 이력과 중복은 `IllegalStateException`입니다. 어느 검증이든 실패하면 이력 전체가 호출 전 상태로 남습니다. 빈 입력은 상태를 바꾸지 않습니다.

생성 이력에 다른 Session의 기록이나 중복 Guideline ID가 있으면 `PresentationSession`은 `IllegalArgumentException`으로 생성을 거부합니다. SQLite 행에서 잘못된 Session ID, Guideline ID, 시각을 읽거나 중복 이력을 발견하면 `SqlitePresentationRepository`가 이를 `PresentationDatabaseCorruptException`으로 변환하고 원인을 보존합니다.

기록 키는 `(sessionId, guidelineId)`입니다. `presentedAt`, 저장 경로, Guideline 본문은 키에 포함하지 않습니다. `PresentationSession`은 전달받은 이력을 복사하고 내부 컬렉션을 노출하지 않습니다.

## 저장소 포트와 Application 호출식

```kotlin
interface PresentationRepository {
    fun presentations(sessionId: SessionId): List<Presentation>

    fun record(presentations: Collection<Presentation>)
}

interface UnitOfWork {
    fun <T> execute(operation: () -> T): T
}
```

`PresentationRepository`는 지정 Session의 기존 행을 `Presentation` 목록으로 읽고 새 기록을 저장합니다. 이력이 없으면 빈 목록을 반환합니다. 조회 목록은 저장소 내부 상태와 분리합니다. 저장소 메서드는 `UnitOfWork.execute`가 연 활성 transaction 안에서만 실행하며, 그 밖에서 호출하면 DB I/O 전에 `IllegalStateException`을 던집니다. Repository는 transaction을 열거나 commit하지 않습니다.

`UnitOfWork.execute`는 callback을 실행하고 성공 시 commit한 뒤에만 결과를 반환합니다. callback 또는 commit 실패는 전체 rollback을 시도합니다. 같은 Database의 암묵적 중첩 transaction은 부모 transaction을 재사용할 수 있으므로, 현재 Exposed transaction이 하나라도 활성 상태면 같은 UnitOfWork 또는 다른 UnitOfWork의 중첩 `execute`를 `IllegalStateException`으로 거부합니다. 중첩 또는 propagation 정책은 제공하지 않습니다.

SQLite 경로, 연결 설정과 스키마 초기화는 `config.SqlitePresentationConfig(databasePath: Path)`가 맡습니다. 기본 DB 경로는 `user.home/.adaptive-guidebook/adaptive-guidebook.db`이며 통합 테스트는 임시 경로를 전달합니다. Config 생성자는 경로 정규화, 디렉터리 생성, DB 연결 또는 스키마 초기화를 하지 않습니다. UnitOfWork가 중첩 경계를 확인한 뒤 실행 시점에 호출하는 내부 `openDatabase(connectionRegistration): Database`가 경로를 정규화하고 상위 디렉터리를 준비해 SQLite driver 설정, connection factory와 Exposed 설정을 적용한 새 Exposed `Database` 객체를 반환합니다. 경로 준비 실패는 Config가 기존 환경 오류로 변환합니다. connection factory는 실제 `Connection`을 열지 않고 Exposed transaction 시작까지 연결을 지연합니다. UnitOfWork가 연 Exposed transaction block 안에서 `initializeSchema()`가 `SchemaUtils.create(PresentationTable)`을 수행합니다. Exposed의 `create()`는 현재 transaction을 내부 commit하고 user data를 비우므로, UnitOfWork는 스키마 준비 뒤 transaction-local scope context를 등록합니다.

`storage.SqlitePresentationUnitOfWork(config)`는 각 `execute`에서 실행별 연결 실패 수집기를 만들고 `config.openDatabase(connectionRegistration)`으로 받은 Exposed `Database`에서 transaction을 엽니다. transaction block에서 먼저 `config.initializeSchema()`를 호출하고, Exposed 내부 commit 뒤 transaction-local context를 등록한 다음 callback을 실행합니다. callback의 이력 조회와 기록은 block 종료 시 최종 commit 또는 rollback을 함께 적용합니다. transaction이 끝나면 `finally`에서 Database를 해제합니다. UnitOfWork는 경로, SQLite driver 설정, `PresentationTable` 또는 구체 DDL을 직접 다루지 않고 transaction 경계와 오류 수명을 관리합니다. `storage.SqlitePresentationRepository(unitOfWork)`는 매 호출 때 주입된 UnitOfWork의 현재 transaction-local context를 확인해 소유 경계 안에서 CRUD를 수행합니다. factory는 하나의 UnitOfWork 인스턴스를 만들어 Application과 Repository에 각각 주입합니다. 매 요청의 Database와 실패 상태는 실행별로 분리되어 adapter 객체는 여러 요청에서 재사용할 수 있습니다.

Application은 검색을 트랜잭션 전에 실행합니다. 검색 결과가 비어 있으면 빈 목록을 반환하고 SQLite를 열지 않습니다. 검색 결과가 있으면 아래 동작 전체를 하나의 콜백으로 전달합니다.

```kotlin
val matched = guidelineSearchService.search(
    GuidelineSearchRequest(request.cwd, request.filepaths),
)

if (matched.isEmpty()) {
    return emptyList()
}

return unitOfWork.execute {
    val session =
        PresentationSession(
            request.sessionId,
            presentationRepository.presentations(request.sessionId),
        )
    val newGuidelines = session.notPresented(matched)

    if (newGuidelines.isEmpty()) {
        return@execute emptyList()
    }

    val presentedAt = clock.instant()
    val presentations =
        newGuidelines.map { guideline ->
            Presentation(request.sessionId, guideline.id, presentedAt)
        }

    session.record(presentations)
    presentationRepository.record(presentations)
    newGuidelines
}
```

`SqlitePresentationUnitOfWork`는 callback보다 먼저 `IMMEDIATE` transaction을 시작합니다. callback 성공 시 commit을 완료한 뒤 결과를 반환합니다. 검색, 파일 읽기, YAML 로딩, Git 조회는 이 트랜잭션 밖에 있습니다. 이력 조회, 도메인 판정과 기록, SQL 저장은 트랜잭션 안에 있습니다.

`session.record`는 SQL을 호출하지 않습니다. `presentationRepository.record`는 도메인 판정을 하지 않습니다. Application만 검색, Session Aggregate, UnitOfWork와 저장소를 순서대로 연결합니다. `present`는 UnitOfWork callback이 성공하고 commit된 뒤에만 목록을 반환합니다.

## SQLite 계약

기본 경로는 `Path.of(System.getProperty("user.home"), ".adaptive-guidebook", "adaptive-guidebook.db")`입니다. Config 생성은 경로를 정규화하거나 상위 디렉터리를 만들지 않습니다. 실제 `execute`에서 Config의 `openDatabase`가 상위 디렉터리와 Exposed `Database`를 준비하고, UnitOfWork transaction block 안에서 Config의 `initializeSchema()`가 `presentation` 테이블을 준비합니다. Exposed `SchemaUtils.create`는 같은 block의 현재 transaction을 내부 commit하고 transaction user data를 초기화합니다. UnitOfWork는 이 호출 뒤 context를 등록하고 Application callback을 실행하므로 이력 조회와 기록은 callback 이후 단일 commit/rollback 경계에서 원자적으로 처리됩니다. 경로를 검증하는 테스트는 임시 경로를 `-Duser.home`으로 전달한 독립 JVM에서 기본 factory를 호출합니다. 사용자 `HOME` 환경 변수와 실제 사용자 홈을 변경하지 않습니다.

```sql
CREATE TABLE IF NOT EXISTS presentation (
    session_id TEXT NOT NULL,
    guideline_id TEXT NOT NULL,
    presented_at TEXT NOT NULL,
    PRIMARY KEY (session_id, guideline_id)
);
```

`presented_at`은 `Clock`의 `Instant`를 UTC 기준 RFC 3339 형식으로 저장합니다. UTC 표기는 `Z`를 사용합니다. 한 요청에서 새로 반환할 Guideline은 한 번 얻은 `clock.instant()` 값을 공유합니다.

각 트랜잭션 연결은 SQLite driver 설정으로 busy timeout 1,000ms와 `IMMEDIATE` transaction mode를 적용합니다. Exposed transaction의 최대 시도 횟수는 1회이며 자동 재시도 지연은 0입니다. UnitOfWork가 이력 조회 전에 `IMMEDIATE` transaction을 시작하여 읽기와 기록 사이에 다른 연결이 같은 Session 상태를 변경하지 못하게 합니다. ID당 단일 행은 기본 키가 보장합니다. 두 번째 또는 이후 행에서 SQL 오류가 나면 같은 transaction의 모든 앞선 변경을 rollback합니다.

저장 행을 Session, Guideline, 시각 값으로 재구성하지 못하거나 잘못된 중복 이력을 읽으면 `PresentationDatabaseCorruptException`으로 변환합니다. 도메인 또는 Application callback 자체에서 발생한 오류는 rollback한 뒤 기존 예외 타입과 원인을 그대로 전달합니다. callback이 직접 던진 `SQLException`은 원래 객체를 유지합니다. 저장 오류 타입으로 덮지 않습니다. Repository SQL 실패와 callback SQL 실패는 Exposed transaction-local metadata에서 원본 실패 객체를 구분해 공개 오류를 분류합니다. rollback 실패 시 `PresentationTransactionRollbackException`의 cause에는 원래 SQL 오류를, suppressed에는 rollback 실패를 보존합니다.

영속성 어댑터는 Exposed 1.6.0의 `core`, `jdbc`, `dao` API를 사용하고 SQLite JDBC 3.53.4.0 driver를 유지합니다. UnitOfWork가 callback 전에 현재 transaction block에서 `SqlitePresentationConfig.initializeSchema()`를 호출하며, Config는 내부에서 `SchemaUtils.create(PresentationTable)`을 수행합니다. `create()`의 내부 commit으로 user data가 초기화된 뒤 UnitOfWork context를 등록합니다. 이력 조회와 기록은 그 이후 callback transaction 안에서 함께 commit 또는 rollback됩니다. 어댑터 내부의 복합 키 테이블과 DAO Entity는 기존 3컬럼 SQLite 스키마를 표현하며 도메인 `Presentation`을 대체하지 않습니다. TTL, 세션 테이블, 별도 캐시, 자동 삭제는 추가하지 않습니다. 실제 Hook과 CLI 연결은 이 계약에 포함하지 않습니다.

## 실패 계약

| 실패 조건 | 공개 결과 | 호출자 대응 |
| --- | --- | --- |
| `sessionId`가 비어 있거나 공백뿐인 경우 | `IllegalArgumentException` | 입력을 올바르게 구성합니다. |
| 활성 UnitOfWork 밖에서 Repository 조회 또는 기록 | `IllegalStateException` | Application 호출을 UnitOfWork 안에서 수행합니다. DB I/O는 시작하지 않습니다. |
| 같은 Database 또는 다른 Database의 Exposed transaction 안에서 UnitOfWork를 중첩 실행 | `IllegalStateException` | 기존 transaction을 끝낸 뒤 Presentation 작업을 시작합니다. |
| Session이 다른 Session의 기록을 받는 경우 | `IllegalArgumentException` | 기록 입력을 바로잡습니다. Aggregate 상태는 유지됩니다. |
| 한 기록 명령에 같은 Guideline ID가 둘 이상 있는 경우 | `IllegalArgumentException` | 중복 입력을 제거합니다. Aggregate 상태는 유지됩니다. |
| 기존 이력을 다시 기록하는 경우 | `IllegalStateException` | 호출 순서나 이전 선별 결과를 수정합니다. Aggregate 상태는 유지됩니다. |
| SQLite busy/locked 대기가 1,000ms를 넘은 경우 | `RetryablePresentationStorageException` | 같은 요청을 재시도할 수 있습니다. 원인을 보존합니다. |
| DB 경로, 권한, 읽기 전용, 디스크 공간 등 환경 문제 | `PresentationStorageUnavailableException` | 환경을 복구한 뒤 호출합니다. 원인을 보존합니다. |
| SQLite DB 손상 또는 SQLite DB가 아닌 파일 | `PresentationDatabaseCorruptException` | DB를 복구하거나 백업에서 복원한 뒤 호출합니다. 원인을 보존합니다. |
| 저장된 Session ID, Guideline ID, 시각 또는 이력이 올바르지 않은 경우 | `PresentationDatabaseCorruptException` | DB를 복구하거나 백업에서 복원한 뒤 호출합니다. 변환 원인을 보존합니다. |
| 스키마 초기화 실패 | 저장소 예외를 전달하고 callback을 실행하지 않음 | DB 환경을 확인합니다. 성공한 스키마 준비의 내부 commit은 이후 이력 rollback 대상이 아닙니다. |
| 그 밖의 영속성 실패 | `PresentationStorageException` | 실패 원인을 확인하고 저장소 상태를 점검합니다. 원인을 보존합니다. |
| transaction 실패 후 rollback 실패 | `PresentationTransactionRollbackException` | DB 상태를 확인합니다. 원래 실패는 cause, rollback 실패는 suppressed 원인으로 보존합니다. |
| 기존 검색 요청 또는 읽기 실패 | 기존 검색 계약의 예외 | 기존 검색 호출자 계약에 따라 처리합니다. Presentation 행은 기록하지 않습니다. |

도메인 호출이나 Application callback에서 발생한 `IllegalArgumentException`, `IllegalStateException` 등은 rollback 뒤 기존 예외 타입으로 전달합니다. 이 오류를 SQLite 저장 오류로 바꾸지 않습니다. rollback 자체가 실패한 경우에만 `PresentationTransactionRollbackException`으로 변환합니다.

저장 실패는 빈 목록으로 바꾸지 않습니다. 정상 반환은 commit 성공을 의미합니다. commit 후 응답 전 프로세스가 종료되면 저장된 ID가 남고 다음 요청은 해당 ID를 제외합니다. 수신 확인, 재전송, 정확히 한 번 전달은 보장하지 않습니다. SQLite 오류에서 자동 삭제나 빈 DB 재생성으로 복구하지 않습니다.

## 정상, 경계, 실패 시나리오

| 시나리오 | 예상 결과 |
| --- | --- |
| 이력 없는 새 Session에서 두 Guideline이 검색됨 | 검색 순서의 두 결과를 반환하고 같은 ID 두 행을 저장합니다. |
| 기존 ID와 새 ID가 함께 검색됨 | 새 ID만 반환하고 기록합니다. 기존 ID는 검색 순서에서 건너뜁니다. |
| 동일 Session에서 본문이 바뀐 같은 Guideline ID가 검색됨 | 결과에서 제외합니다. 저장 키에 본문은 포함하지 않습니다. |
| 다른 Session에서 같은 Guideline ID가 검색됨 | 새 Session의 결과로 반환하고 별도 행을 저장합니다. |
| 빈 filepath 목록, 비 Kotlin 파일만 있음, 관련 지침 없음 | 기존 검색 계약의 빈 목록을 반환하고 SQLite를 사용하지 않습니다. |
| 모든 검색 결과가 이미 기록됨 | 빈 목록을 반환합니다. 이력 조회 트랜잭션은 성공적으로 commit되어야 합니다. |
| 상대 `cwd` 또는 filepath | 기존 `InvalidSearchRequestException`을 반환하고 이력을 바꾸지 않습니다. |
| 소스, YAML, Git 입력 읽기 실패 | 기존 typed 예외와 원인을 전달하고 이력을 바꾸지 않습니다. |
| 독립 연결 두 개가 같은 Session을 동시 요청 | 직렬화된 조회로 한 요청만 ID를 반환합니다. 다른 요청은 빈 결과를 받거나 busy 제한 뒤 재시도 예외를 받습니다. |
| 독립 프로세스 두 개가 같은 DB와 Session을 동시 요청 | 각 프로세스가 독립 연결을 사용해도 DB 잠금과 기본 키로 동일한 단일 기록을 보장합니다. |
| 첫 행 저장 뒤 두 번째 행 저장이 trigger로 실패 | transaction 전체를 rollback하여 첫 행도 남지 않습니다. Application은 목록을 반환하지 않고 저장 예외를 전달합니다. |
| commit 뒤 응답 전에 프로세스 종료 | 행은 영속되고 다음 요청에서 ID를 제외합니다. 재전송은 하지 않습니다. |
| 다른 Guideline이 저장되는 중 동일 세션 ID의 충돌이 이미 존재 | 도메인 명령이 전체 기록 입력을 적용하기 전에 거부하고 부분 상태를 남기지 않습니다. |
| DB busy가 제한 시간 안에 해소됨 | 대기 후 같은 transaction을 진행하고 정상 commit이면 결과를 반환합니다. |
| DB busy가 제한 시간 뒤에도 지속됨 | `RetryablePresentationStorageException`을 반환하고 결과 목록은 반환하지 않습니다. |
| SQLite DB 파일이 손상됨 | `PresentationDatabaseCorruptException`을 반환합니다. 빈 성공으로 처리하지 않습니다. |
| transaction rollback 자체가 실패 | `PresentationTransactionRollbackException`을 반환하고 두 실패 원인을 보존합니다. |

## 테스트 실행 기준

테스트는 실제 객체 협력과 외부에서 보이는 결과를 확인합니다. Kotest `FeatureSpec`, 완전한 한국어 Scenario, Given/When/Then 사이 빈 줄을 사용합니다. 동일한 규칙의 입력 조합은 `withScenarios`로 작성합니다. Mock 및 호출 횟수 검증은 사용하지 않습니다.

현재 변경은 두 계약 Step으로 나누어 진행합니다. 기존 Core 테스트인 `PresentationSessionTest`, `PresentationTest`와 값 객체 테스트는 변경하지 않습니다.

1. **Application 계약 Step**: `GuidelinePresentationServiceTest`는 기존 검색 서비스, 실제 도메인 객체, 메모리 Repository Fake, 별도 Fake UnitOfWork와 고정 Clock을 연결합니다. Repository Fake는 활성 UnitOfWork 안에서만 CRUD를 허용하고 commit된 상태를 확인합니다. 검색 입력 Fake는 active transaction 안에서 검색이 시작되면 실패해 검색이 저장 transaction 밖에 있음을 보장합니다. 빈 검색 Scenario는 저장소뿐 아니라 UnitOfWork도 실행되지 않게 합니다. 기존 `GuidelinePresentationIntegrationTest`는 이 Step에서 생성자와 fixture 조립만 변경하며 assertion과 Scenario는 추가하지 않습니다.
2. **영속성 경계 계약 Step**: `SqlitePresentationUnitOfWorkIntegrationTest`는 아래 SQLite 경계 Scenario를 실행합니다.
   - 세 `withScenarios` 입력: 활성 UnitOfWork 밖에서 이력 조회, 빈 이력 기록, 유효 Presentation 기록
   - Repository A를 UnitOfWork B 안에서 조회하거나 기록했을 때 A 경계 거부, B의 기존 행 보존, A 경로 미생성
   - 같은 UnitOfWork 또는 다른 UnitOfWork를 중첩했을 때 거부, 부모 변경 rollback, 자식 경로 미생성
   - 정상 scope가 끝난 뒤 Repository 직접 조회 거부 및 commit 행 유지
   - 예외 scope가 끝난 뒤 직접 기록 거부, 행 rollback, 같은 adapter 재사용 후 새 요청 commit
   - callback이 직접 던진 `SQLException`의 동일 객체 전달과 새 기록 rollback
   - 같은 UnitOfWork와 Repository를 공유하는 두 스레드의 반환값과 commit 행

   DB 행은 Session ID, Guideline ID와 시각 값으로 관찰하며 도메인 객체 동등성에는 의존하지 않습니다. 기존 22개 SQLite 통합 Scenario의 assertion은 그대로 둡니다. 기존 빈 검색 통합 Scenario에는 DB 상위 경로 미생성 assertion만 더합니다.

각 Step은 root 검토 후 커밋·게시합니다. 두 계약 Step이 모두 끝나기 전에는 실제 구현을 추가하지 않습니다. RED 단계에는 한국어 설명이 있는 compileable `NotImplementedError` stub을 사용합니다. 첫 Step은 Application 테스트, 두 번째 Step은 `SqlitePresentationUnitOfWorkIntegrationTest`만 실행해 컴파일 성공 후 해당 stub에서 실패하는지 확인합니다. Configuration 또는 라이브러리 기동 전용 테스트는 추가하지 않습니다. Hook/CLI 프로세스 연결도 이 계약에 포함하지 않습니다.

SQLite 원자성 테스트는 테스트 DB에 실제 `BEFORE INSERT` trigger를 설치해 특정 두 번째 Guideline 행 삽입을 실패시킵니다. 운영 코드의 오류 주입 옵션은 만들지 않습니다. 독립 프로세스 테스트도 같은 테스트 진입점을 사용하며 운영 CLI를 변경하지 않습니다.

각 계약 Step은 `spotlessApply` 뒤 해당 Step의 테스트만 실행합니다. 두 번째 Step도 `SqlitePresentationUnitOfWorkIntegrationTest`만 실행해 컴파일 성공과 SQLite UnitOfWork/Repository `NotImplementedError`에 의한 의도한 RED를 확인합니다. 계약 테스트를 모두 커밋·게시한 뒤 Config, UnitOfWork, Repository, Application wiring 순으로 구현합니다. 기존 Core 동작은 변경하지 않습니다. 실제 SQLite 및 독립 JVM 통합 검증은 하위 구현 뒤에 수행합니다.
