# Agent Guidebook 영속성 경계 분리 검토

## 목표와 범위

`PresentationRepository`가 함께 맡던 DB 접근, SQLite 연결 설정, 스키마 준비, 트랜잭션 제어와 오류 분류를 역할별로 나눕니다. `GuidelinePresentationService`는 검색, `PresentationSession`의 선별과 기록, 저장소 호출을 조율합니다.

기존 Presentation 도메인 행위, 검색 규칙, SQLite 스키마와 공개 오류 의미는 유지합니다. 새 Core 개념, 중첩 전파 정책, 범용 트랜잭션 프레임워크, 직접 SQL 문자열은 추가하지 않습니다.

## 책임과 협력

| 구성 요소 | 책임 |
| --- | --- |
| `services.UnitOfWork` | 애플리케이션 포트. `execute(operation: () -> T)`의 실행 범위를 열고, callback 성공 시 commit한 뒤 결과를 반환합니다. 실패 시 rollback을 수행합니다. |
| `services.PresentationRepository` | 애플리케이션 포트. 지정 Session의 `Presentation`을 조회하고 새 기록을 저장합니다. 트랜잭션을 열거나 도메인 선별 규칙을 적용하지 않습니다. |
| `storage.SqlitePresentationUnitOfWork` | Exposed 트랜잭션과 연결 수명을 관리하고 활성 경계를 확인합니다. 트랜잭션 중복 실행을 거부하며, SQLite 연결과 트랜잭션 실패를 공개 저장 오류로 변환합니다. |
| `storage.SqlitePresentationRepository` | Exposed typed DSL/DAO로 조회와 기록을 수행하고 행과 도메인 `Presentation` 사이를 변환합니다. 활성 UnitOfWork 밖의 CRUD를 거부합니다. |
| `config.SqlitePresentationConfig` | DB 경로, SQLite driver 설정, Exposed 설정을 관리하고 `openDatabase(connectionRegistration): Database`로 실행별 Exposed Database 객체를 조립합니다. 연결은 지연되며 설정 생성은 파일 시스템이나 실제 DB 연결에 접근하지 않습니다. |
| `GuidelinePresentationService` | 검색은 트랜잭션 전에 수행합니다. 결과가 있으면 UnitOfWork 안에서 저장소 조회, Aggregate 선별, 기록 생성과 저장을 순서대로 조율합니다. |

Application은 `UnitOfWork`와 `PresentationRepository`를 별도로 주입받습니다. SQLite 조립은 한 `SqlitePresentationUnitOfWork`를 만들고 같은 인스턴스를 `SqlitePresentationRepository`에 전달합니다.

```kotlin
class SqlitePresentationConfig(
    val databasePath: Path = defaultDatabasePath(),
)

class SqlitePresentationUnitOfWork(
    private val config: SqlitePresentationConfig,
) : UnitOfWork

class SqlitePresentationRepository(
    private val unitOfWork: SqlitePresentationUnitOfWork,
) : PresentationRepository
```

`SqlitePresentationConfig`는 외부에서 DB 경로를 지정할 수 있게 하고, timeout, transaction mode와 재시도 제한은 현재 계약값으로 고정합니다. 내부 `openDatabase(connectionRegistration): Database`는 SQLite 설정, connection factory와 Exposed `DatabaseConfig`를 조립해 Exposed `Database` 객체를 반환합니다. connection factory는 SQLite 설정 객체와 파일 경로를 캡처할 뿐 실제 JDBC 연결을 열지 않습니다. Config 생성자는 파일, 연결, 스키마를 만들지 않습니다. 별도의 descriptor 자료형이나 전역·재사용 `Database` 객체는 두지 않습니다.

`SqlitePresentationUnitOfWork.execute`는 실행마다 연결 실패 수집기를 만들고 `config.openDatabase(connectionRegistration)`을 호출해 새 Exposed `Database`를 받습니다. `TransactionManager.closeAndUnregister(database)`는 해당 실행의 `finally`에서 호출합니다. UnitOfWork와 Repository 객체는 설정 및 같은 UnitOfWork 참조만 보유하므로 여러 요청에서 재사용할 수 있고, 매 요청의 연결 실패 상태와 Database 수명은 서로 분리됩니다.

UnitOfWork는 실행마다 만든 내부 `SqlitePresentationTransactionContext`를 Exposed `Transaction.userData(Key)`에 저장합니다. 이 context에는 소유 UnitOfWork, 실행별 Database, rollback/close 실패 수집기, Repository SQL 실패 원인이 들어갑니다. `SqlitePresentationRepository.currentContext()`는 주입받은 UnitOfWork에 현재 context를 요청합니다. UnitOfWork는 `TransactionManager.currentOrNull()` 결과, 같은 Database identity와 owner identity를 검사하고, 불일치하면 DB 작업 전에 `IllegalStateException`을 던집니다. 이 기술 context는 실행 범위와 원인 보존을 연결하는 내부 adapter 자료형이며 Application callback에 노출하지 않고 일반 context/registry나 Core 개념으로 확장하지 않습니다.

## API와 Application 흐름

```kotlin
interface UnitOfWork {
    fun <T> execute(operation: () -> T): T
}

interface PresentationRepository {
    fun presentations(sessionId: SessionId): List<Presentation>

    fun record(presentations: Collection<Presentation>)
}
```

`GuidelinePresentationService`는 기존 검색 서비스, `UnitOfWork`, `PresentationRepository`, `Clock`을 주입받습니다.

1. 기존 `GuidelineSearchService.search`를 실행합니다.
2. 검색 결과가 비어 있으면 빈 목록을 반환합니다. UnitOfWork와 저장소를 호출하지 않습니다.
3. 결과가 있으면 `unitOfWork.execute { ... }` 안에서 이력을 조회하고 `PresentationSession`으로 새 결과를 선별합니다.
4. 새 결과가 없으면 callback에서 빈 목록을 반환합니다. 트랜잭션은 정상 종료하고 commit되어야 Application 결과를 반환합니다.
5. 새 결과가 있으면 `clock.instant()`를 한 번 얻고, `PresentationSession.record` 뒤에 `presentationRepository.record`를 호출합니다.
6. `execute`가 commit을 완료한 뒤에만 새 `MatchedGuideline` 목록을 반환합니다.

검색 오류는 UnitOfWork 진입 전에 발생하므로 기존 예외 객체와 이력을 유지합니다. 검색 결과가 비어 있지 않더라도 UnitOfWork와 DB 설정은 생성 시 파일이나 디렉터리를 만들지 않습니다. SQLite I/O는 실제 저장 범위가 실행될 때만 시작합니다.

## 설정, 트랜잭션과 스키마

기본 DB 경로는 `user.home/.adaptive-guidebook/adaptive-guidebook.db`입니다. 테스트용 경로는 `SqlitePresentationConfig(databasePath = path)`로 지정합니다. Config 생성과 service factory 조립은 디렉터리 생성, JDBC 연결, DB 객체의 장기 등록 또는 스키마 초기화를 하지 않습니다. UnitOfWork는 다른 UnitOfWork를 포함해 현재 Exposed transaction이 하나라도 활성 상태면 새 경로 접근이나 DB 객체 생성 전에 `IllegalStateException`으로 중첩 실행을 거부합니다. 이후 경로 정규화와 상위 디렉터리 생성은 해당 `execute`에서만 수행하고 파일 시스템 오류는 `PresentationStorageUnavailableException`으로 변환합니다.

SQLite 연결은 `SQLiteConfig`의 busy timeout 및 `IMMEDIATE` transaction 설정을 사용합니다. Exposed transaction의 최대 시도 횟수는 1회이며 자동 재시도 지연은 0입니다. 운영 코드에서 `BEGIN`, `COMMIT`, `ROLLBACK`, `PRAGMA`를 SQL 문자열로 직접 실행하지 않습니다. 실제 commit과 rollback은 Exposed transaction API에 위임합니다.

UnitOfWork가 매 실행 `config.openDatabase(connectionRegistration)`을 호출해 새 Exposed `Database`를 받고 `transaction(db = database)`에 명시적으로 전달합니다. CRUD는 이 transaction 안에서만 수행합니다. Exposed는 같은 Database의 기본 중첩 transaction에서 부모 transaction 객체와 자원을 재사용하므로 callback 반환 뒤 독립 commit이 보장되지 않습니다. UnitOfWork는 Database 종류와 관계없이 현재 Exposed transaction이 하나라도 활성 상태면 시작을 거부합니다. 독립 중첩 transaction이나 propagation 정책은 추가하지 않습니다.

스키마 정의는 `storage.PresentationTable`에 둡니다. `SqlitePresentationUnitOfWork`가 활성 transaction 안에서 Application callback보다 먼저 `SchemaUtils.create(PresentationTable)`을 실행합니다. 스키마 초기화 실패는 transaction 실패로 rollback하고 UnitOfWork의 저장 오류 경계에서 분류합니다. 이 배치는 설정 생성과 저장소 CRUD에서 스키마 생성을 분리하고, 조회 전에 필요한 테이블이 같은 Exposed transaction에서 준비되도록 합니다. 마이그레이션, 자동 열 복구와 빈 DB 재생성은 추가하지 않습니다.

저장소 조회와 기록은 Exposed Table DSL 및 DAO를 사용합니다. 조회 결과는 transaction 안에서 `Presentation`으로 복원하고 저장 전용 Entity를 외부에 반환하지 않습니다. 기존 복합 키 `(session_id, guideline_id)`, 세 컬럼, UTC `Instant` 문자열을 유지합니다.

## 실패와 상태 계약

| 조건 | 결과 |
| --- | --- |
| 검색 결과가 비어 있음 | 빈 목록. UnitOfWork, Repository, SQLite 파일과 상위 디렉터리에 접근하지 않음 |
| Repository CRUD를 활성 UnitOfWork 밖에서 호출 | DB I/O 전에 `IllegalStateException` |
| 같은 또는 다른 Database/UnitOfWork의 Exposed transaction이 이미 활성 상태에서 실행 | 내부 transaction을 암묵적으로 재사용하지 않고 `IllegalStateException` |
| callback 성공 및 commit 성공 | callback 결과를 반환 |
| callback, 저장, 스키마 준비 또는 commit 실패 | 전체 작업 rollback을 시도하고 성공 결과를 반환하지 않음 |
| callback이 직접 `SQLException`을 던짐 | 원래 예외 객체를 그대로 전달 |
| Repository SQL 작업 실패 | UnitOfWork 경계에서 기존 `BUSY`/`LOCKED`, 환경, 손상, 일반 저장 오류 타입으로 분류 |
| 저장 행 변환 실패 또는 중복 이력 | Repository에서 `PresentationDatabaseCorruptException`으로 전달하고 원인을 보존 |
| rollback 실패 | `PresentationTransactionRollbackException`을 던짐. 원래 작업 실패는 cause, rollback 실패는 suppressed로 보존 |
| rollback과 close가 모두 실패 | 원래 작업 실패를 cause로 두고 rollback 및 별도 close 실패를 suppressed로 보존 |
| commit 뒤 close 실패 | 저장 오류를 전달하고 결과를 반환하지 않음 |

UnitOfWork는 중첩 transaction이나 propagation 정책을 구현하지 않습니다. 같은 실행 경계의 재진입을 시작 시 거부해 `execute` 반환 전 commit 보장을 유지합니다. UnitOfWork 사이의 중첩 실행도 지원 계약에 포함하지 않습니다.

Repository는 주입된 UnitOfWork가 실행마다 transaction-local context를 찾고 owner identity와 활성 `JdbcTransaction.db`를 검증하도록 요청합니다. 활성 context가 없거나 다른 UnitOfWork의 context이면 `IllegalStateException`을 던집니다. 이 검사는 SQL 조회나 DB 생성을 시작하기 전에 수행합니다.

Repository의 SQL 호출은 실패한 예외 객체를 실행별 transaction context에 기록하고 같은 객체를 다시 던집니다. UnitOfWork는 transaction 종료 뒤 최종 실패 객체와 기록된 저장 실패를 대조해 Repository SQL 실패만 공개 오류로 분류합니다. 별도 wrapper가 callback 예외의 타입이나 객체 동일성을 바꾸지 않습니다. Application callback에서 직접 던진 `SQLException`은 Repository 저장 실패로 표시되지 않으므로 같은 객체로 전파합니다. Repository 호출에서 `ExposedSQLException`이 발생하면 기록한 외부 예외와 실제 `SQLException` 원인을 함께 보존해 rollback 실패 시 원본 SQL을 cause로 사용합니다. 연결 생성, 스키마 준비, commit, rollback 및 close에서 난 SQL 오류는 해당 작업을 수행하는 UnitOfWork가 분류합니다.

UnitOfWork는 현재 Exposed transaction이 어떤 Database에서 열렸는지와 관계없이 중첩 실행을 시작 전에 거부해 Exposed의 암묵적인 transaction 재사용을 막습니다. Repository SQL 실패 원인을 context에 기록해 callback이 직접 던진 SQL 예외와 저장소 DSL/DAO에서 발생한 SQL 예외를 구분합니다. UnitOfWork는 transaction 종료 직전 실패만 해당 기록과 비교하므로 callback이 저장 오류를 처리한 뒤 다른 예외로 실패한 경우 그 새 예외를 보존합니다. 기존 `PresentationTransactionRollbackException`은 저장 SQL의 원본 `SQLException`을 cause로, rollback 실패를 suppressed로 받습니다.

Exposed 연결 adapter는 rollback과 close 실패를 기록해 Exposed가 로그만 남기고 삼키는 원인을 보존합니다. UnitOfWork는 실패 종류별로 한 번만 변환합니다. rollback 실패 시 원래 callback 또는 저장 실패 객체를 cause로 사용합니다. close 실패를 추가할 때 같은 예외 객체를 cause나 기존 suppressed 원인으로 중복 추가하지 않습니다.

## 부수 효과와 불변 조건

- 영속 상태는 기존 Presentation 이력 행뿐입니다. 도메인 선별과 키는 `(sessionId, guidelineId)`로 유지합니다.
- 검색은 SQLite transaction 밖에서 실행합니다. 빈 결과는 DB와 상위 디렉터리를 생성하지 않습니다.
- 검색 결과가 모두 기존 이력이어도 이력 조회 transaction은 정상 commit되어야 빈 결과를 반환합니다.
- 새 결과가 있을 때만 한 번 얻은 시각으로 행을 추가합니다. 기록 중 하나라도 실패하면 이전 삽입을 포함해 전체를 rollback합니다.
- 같은 Session의 동시 요청은 `IMMEDIATE` transaction으로 직렬화됩니다. 독립 JVM에서도 동일 Guideline을 최대 한 번만 반환하고 기록합니다.
- Core의 `PresentationSession`, `Presentation`, 값 객체 행위와 예외 의미는 변경하지 않습니다.
- `BuildConfigurationTest`와 같은 설정 또는 프레임워크 기동 전용 테스트를 추가하지 않습니다. 기존 빈 검색 통합 시나리오에서 DB 파일과 상위 경로가 생기지 않는 결과를 확인합니다.

## 계약 테스트와 RED 단계

테스트는 기존 `FeatureSpec`, 한글 Given/When/Then, 필요한 경우 `withScenarios` 규칙을 유지합니다. 동작 결과와 예외 객체, DB 행을 확인하며 호출 횟수 검증이나 Mock은 사용하지 않습니다. 기존 assertion을 약화하거나 목적 없는 fixture 외 변경으로 GREEN을 만들지 않습니다.

### 첫 번째 계약 Step: Application

기존 `GuidelinePresentationServiceTest`를 새 포트에 맞춥니다. 실제 도메인 객체, 메모리 Repository Fake, commit/rollback을 수행하는 Fake UnitOfWork와 고정 Clock으로 검색 순서, Session 중복 제외, 검색 오류, 저장 실패 원자성, commit 실패를 검증합니다. 두 Fake는 별도 객체로 만들어 함께 주입합니다. Repository 접근은 활성 Fake UnitOfWork 안에서만 허용하고 commit된 상태만 테스트 검증에 노출합니다. 입력 reader는 활성 UnitOfWork 여부를 검사해 검색을 저장 transaction 바깥에서 수행하게 하며, 빈 검색은 호출 시 실패하는 Unexpected UnitOfWork와 Repository를 사용해 둘 다 실행되지 않음을 확인합니다.

기존 `GuidelinePresentationIntegrationTest`는 첫 Step에서 생성자와 fixture 조립을 새 API로 옮깁니다. 기존 assertion을 유지하고 새 통합 시나리오를 추가하지 않습니다. Core 테스트도 수정하지 않습니다. Config, UnitOfWork, Repository와 Application 메서드는 컴파일 가능한 최소 stub으로 남깁니다.

첫 Step 검증은 `spotlessApply` 뒤 `GuidelinePresentationServiceTest`만 실행합니다. 전체 테스트 소스 컴파일은 성공하고 대상 Scenario가 Application `NotImplementedError` stub에서 실패해야 합니다. 컴파일 오류나 Fake wiring 오류를 RED로 인정하지 않습니다. root가 이 Step을 검토하고 커밋·게시한 뒤 다음 계약 Step으로 진행합니다.

### 두 번째 계약 Step: 하위 영속성 경계

Repository가 실제 transaction 안에서 조회·기록하는 계약, UnitOfWork 밖의 CRUD 거부, 모든 활성 Exposed transaction에서의 중첩 거부, commit 전 반환 금지, callback SQL 예외 동일성, rollback·close 원인 보존을 별도 Scenario로 검증합니다. 기존 통합 테스트의 DB 행, 오류 원인, 동시성 assertion은 유지합니다. 첫 Step 승인 전에는 하위 경계 Scenario를 추가하지 않습니다.

두 계약 Step을 각각 root가 검토하고 커밋·게시한 뒤 구현합니다. Core 회귀 테스트는 변경하지 않고 구현 후 실행합니다.

API를 바꾼 테스트 전용 단계에서는 새 `UnitOfWork` 포트와 Repository CRUD 시그니처를 먼저 추가하고, `SqlitePresentationConfig.openDatabase`, UnitOfWork, Repository와 Application의 최소 stub은 모두 한글 설명이 있는 `NotImplementedError`를 던지게 합니다. Config 생성자와 factory 조립은 passive 상태로 유지합니다. 서비스 및 테스트 조립을 새 API에 맞춰 모두 컴파일할 수 있게 하되 stub은 결과를 성공 처리하거나 기존 오류를 흉내 내지 않습니다. RED 실행에서 실패한 계약 이름과 실제 원인을 확인해 구현 누락임을 검증합니다. 기존 운영 동작의 assertion을 삭제하거나 빈 구현으로 GREEN 처리하지 않습니다.

계약 테스트 전용 단계에서는 새 `UnitOfWork` 포트와 Repository CRUD 시그니처를 먼저 추가하고, `SqlitePresentationConfig.openDatabase`, UnitOfWork, Repository와 Application의 최소 stub은 모두 한글 설명이 있는 `NotImplementedError`를 던지게 합니다. Config 생성자와 factory 조립은 passive 상태로 유지합니다. stub은 결과를 성공 처리하거나 기존 오류를 흉내 내지 않습니다. 의도한 RED는 root가 실패 원인을 확인합니다.

계약 테스트와 최소 stub을 두 단계로 검토·커밋·게시한 뒤 실제 구현을 시작합니다. 구현 단계에서는 아래 순서를 따르고 각 하위 경계가 관련 테스트를 통과한 뒤 다음 경계로 진행합니다.

## 구현 순서

1. **Config**: SQLite 경로와 연결·시도 정책을 보유하는 설정과 `openDatabase(connectionRegistration)`을 추가합니다. 호출 시 실제 연결은 열지 않으며 생성 시 파일, 디렉터리, 스키마도 만들지 않습니다.
2. **UnitOfWork**: Exposed Database 연결, 활성 경계 검사, 중첩 거부, 스키마 초기화, commit/rollback/close, SQL 오류 분류와 원인 보존을 구현합니다.
3. **Repository**: 활성 Database transaction을 요구하는 Exposed CRUD와 저장 행 변환을 구현합니다. 직접 SQL 문자열을 사용하지 않습니다.
4. **Application 및 factory**: 별도 UnitOfWork와 Repository를 주입하고 검색 전후 순서를 유지합니다. factory는 passive config와 adapter만 조립하고 빈 검색의 지연 동작을 보존합니다.
5. **회귀 검증**: Core 테스트, Application 테스트, SQLite 및 독립 JVM 통합 시나리오 순으로 검증합니다. 실행은 `spotlessApply`, 해당 패키지 테스트, clean 없는 incremental `build` 순서를 따릅니다.

## 남은 검토

- Exposed 1.6.0에서 `Transaction.userData(Key)`, `TransactionManager.currentOrNull()`, `JdbcTransaction.db`를 제공하는 버전 소스와 transaction 동작 문서를 확인했습니다. 구현 시 이 API만 사용하고 별도 전역 상태를 추가하지 않습니다.
- 테스트 단계에서 최소 stub 적용 범위와 기존 통합 테스트의 RED 원인을 root가 검토한 뒤 실제 구현 단계로 진행합니다.

## 참고 자료

- [Exposed 1.6.0 transaction 문서](https://www.jetbrains.com/help/docs/1.6.0/transactions.html): CRUD transaction 요구, `db` 선택, 기본 중첩 재사용과 `maxAttempts` 동작.
- [Exposed 1.6.0 `Transaction.kt`](https://github.com/JetBrains/Exposed/blob/1.6.0/exposed-core/src/main/kotlin/org/jetbrains/exposed/v1/core/Transaction.kt): `Key`, `UserDataHolder`, `Transaction`의 transaction-local user data.
- [Exposed 1.6.0 `TransactionManager.kt`](https://github.com/JetBrains/Exposed/blob/1.6.0/exposed-jdbc/src/main/kotlin/org/jetbrains/exposed/v1/jdbc/transactions/TransactionManager.kt): `currentOrNull()`과 현재 transaction 확인.
