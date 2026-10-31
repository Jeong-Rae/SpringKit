# SQLite 저장소의 Exposed 전환 검토

이 문서는 저장 adapter의 ORM 교체 범위와 중요한 오류 경계, 적용 결과를 정리합니다. 비즈니스 행위와 도메인·포트 계약은 변경하지 않았습니다.

현재 Config, UnitOfWork와 Repository의 책임 분리는 [영속성 경계 검토](agent-guidebook-unit-of-work-review.md)를 기준으로 합니다. 이 문서의 당시 adapter 경계 설명은 Exposed 전환 기록으로 읽습니다.

## 결정

Exposed 1.6.0의 `core`, `jdbc`, `dao`를 사용하고 SQLite JDBC 3.53.4.0은 유지합니다. Exposed 모듈 POM은 Maven Central에 게시되어 있으며 Kotlin 2.3.21 프로젝트에서 컴파일과 빌드를 완료해 호환성을 확인했습니다.

테이블과 저장 전용 DAO Entity는 `storage` 안에 둡니다. `PresentationSession`, `Presentation`, Application 서비스, 저장소 포트는 그대로 재사용합니다. 기존 도메인 Entity를 ORM Entity로 대체하지 않습니다. ORM 조회 결과는 transaction 안에서 `Presentation`으로 복원하며 저장 전용 DAO Entity를 밖으로 반환하지 않습니다.

## 저장 모델

```kotlin
object PresentationTable : CompositeIdTable("presentation") {
    val sessionId = text("session_id").entityId()
    val guidelineId = text("guideline_id").entityId()
    val presentedAt = text("presented_at")

    override val primaryKey = PrimaryKey(sessionId, guidelineId)
}

class PresentationRow(id: EntityID<CompositeID>) : CompositeEntity(id) {
    companion object : CompositeEntityClass<PresentationRow>(PresentationTable)

    var presentedAt by PresentationTable.presentedAt
}
```

세 컬럼과 `(session_id, guideline_id)` 복합 기본 키를 유지합니다. synthetic ID나 날짜 타입 컬럼은 추가하지 않습니다. `presented_at`에는 기존처럼 `Instant.toString()`을 `TEXT`로 저장해 UTC `Z` 표기를 보존합니다. ORM 조회 결과는 transaction 안에서 기존 `Presentation`으로 복원하며 저장 전용 DAO Entity를 외부로 반환하지 않습니다.

저장은 두 키 컬럼의 `CompositeID`를 `CompositeEntity.new`에 전달합니다. 조회는 typed Table DSL로 수행하며 EntityID 컬럼을 기존 Session ID 값으로 필터링합니다.

```kotlin
val id = CompositeID {
    it[PresentationTable.sessionId] = presentation.sessionId.value
    it[PresentationTable.guidelineId] = presentation.guidelineId.value
}

PresentationRow.new(id) {
    presentedAt = presentation.presentedAt.toString()
}
```

스키마는 `SchemaUtils.create(PresentationTable)`로 없을 때만 만듭니다. 마이그레이션, `createMissingTablesAndColumns`, 기본 키 자동 복구는 사용하지 않습니다. 운영 코드에서 직접 SQL 문자열을 작성하지 않습니다.

실제 adapter는 SchemaUtils, DSL, DAO와 Exposed transaction API를 사용하며 수동 SQL과 transaction 문장을 제거했습니다. Exposed가 rollback과 close 실패를 로그로만 처리하므로 기존 원인 보존을 위해 작은 연결 오류 adapter는 남깁니다.

typed Table DSL이 반환하는 `ResultRow`를 행마다 도메인 값으로 복원한 뒤 Guideline ID 중복을 검사합니다. DAO Entity 조회를 사용하지 않아 손상 DB의 중복 행을 가리지 않습니다.

## transaction과 연결 수명

`Database.connect`로 만든 DB를 `transaction(db = database)`에 명시적으로 전달합니다. Exposed의 기본 thread context, JDBC 연결 생성·닫기, statement 정리를 사용합니다. 요청마다 등록한 DB는 transaction이 끝난 뒤 `finally`에서 `TransactionManager.closeAndUnregister(database)`로 해제합니다. 저장 adapter에서 ThreadLocal, 연결 풀, 자체 transaction manager를 추가하지 않습니다.

각 JDBC 연결에는 `SQLiteConfig.setBusyTimeout(1_000)`과 `setTransactionMode(IMMEDIATE)`를 적용합니다. Exposed는 JDBC `autoCommit`을 끄므로 SQLiteConfig가 설정한 IMMEDIATE 모드로 transaction을 시작합니다. `SchemaUtils.create`, 조회, 도메인 판정, DAO 저장을 하나의 transaction에서 처리하고 Exposed transaction API가 commit 뒤에만 결과를 반환하게 합니다.

Exposed 기본 transaction 재시도는 3회입니다. `DatabaseConfig.defaultMaxAttempts`와 transaction `maxAttempts`를 모두 1로 지정합니다. 기본 최소·최대 retry delay도 0으로 지정해 1,000ms busy 대기 뒤 추가 지연이나 재실행이 발생하지 않게 합니다.

검색은 기존처럼 transaction 밖에서 합니다. 검색 결과가 비면 저장소를 호출하지 않아 SQLite 파일을 만들지 않습니다. 같은 Session에서 이미 본 ID를 제외하는 기준과 검색 순서는 변경하지 않습니다.

`IllegalArgumentException`과 `IllegalStateException` 등 Application callback 예외는 rollback 뒤 기존 타입과 객체로 전달합니다. 도메인 행위나 저장소 포트 계약은 변경하지 않습니다.

## rollback 예외 보존

Exposed 1.6.0은 callback 실패 뒤 rollback을 시도하지만 rollback 및 connection close 실패를 로그로 처리합니다. 기본 transaction API만 사용하면 기존의 `PresentationTransactionRollbackException` cause/suppressed 계약을 보장할 수 없습니다.

보완은 저장소 연결 경계에 한정합니다. Exposed의 `DatabaseConnectionAutoRegistration`에 등록한 작은 연결 decorator가 rollback과 close 실패를 호출별 상태에 기록합니다.

1. `Database.connect(connectionAutoRegistration = ...)`에 `ExposedConnection` 위임 adapter를 등록합니다.
2. adapter는 `rollback()`과 `close()` 실패 SQLException을 요청별로 기록한 뒤 그대로 다시 던집니다. Exposed 기본 transaction, context, 정리 흐름은 유지합니다.
3. transaction 종료 뒤 기록된 rollback 실패가 있으면 `PresentationTransactionRollbackException`에 원래 작업 실패를 cause로 둡니다. close도 실패한 경우 두 연결 실패를 suppressed로 보존합니다.
4. rollback 실패가 없으면 callback 예외의 타입과 객체를 보존합니다. commit 뒤 close만 실패하면 기존 저장 오류 분류를 적용하고 결과를 반환하지 않습니다.

저장소의 SchemaUtils, DSL, DAO 호출에서 발생한 SQLException은 호출별로 구분해 공개 저장 예외로 변환합니다. Application callback이 직접 던진 SQLException은 저장소 SQL 오류와 구분해 원래 객체로 전달합니다. rollback만 실패한 경우 기존처럼 suppressed 원인은 하나입니다.

이 보완은 `SqlitePresentationRepository`의 예외 경계에서만 사용합니다. 일반 transaction 프레임워크, 재귀 예외 그래프 탐색, 수동 transaction SQL, Exposed 내부 API 호출은 추가하지 않습니다. 특히 SQLite `RAISE(ROLLBACK)` 뒤 Exposed의 후속 rollback이 실패하는 경우 두 번째 SQLException을 수집해 기존 공개 계약으로 변환합니다.

## 재사용할 검증

기존 `GuidelinePresentationIntegrationTest`, `GuidelinePresentationServiceTest`, `PresentationSessionTest`를 변경 없이 사용합니다. ORM 기동이나 설정만 확인하는 새 테스트는 추가하지 않습니다.

중요 시나리오는 다음과 같습니다.

- 빈 검색은 DB를 만들지 않고, 검색 예외는 이력 변경 없이 원래 타입으로 전달합니다.
- 기존 ID는 본문이 바뀌어도 같은 Session에서 다시 제공하지 않으며 다른 Session은 독립적으로 처리합니다.
- 두 번째 행 저장 실패는 전체 rollback하고 결과를 반환하지 않습니다.
- `RAISE(ROLLBACK)` 뒤 rollback 실패는 원래 SQL cause와 rollback SQLException을 보존합니다.
- commit 시 잠금이 남아 있으면 저장 실패로 분류하고 성공 결과를 반환하지 않습니다.
- busy/locked는 1,000ms 대기 후 자동 재시도 없이 재시도 가능 저장 예외로 전달합니다.
- 잘못된 저장 행과 중복 행은 손상 예외로 전달하고 자동 복구하지 않습니다.

기존 계약 문서의 `SQLite JDBC만 사용` 및 `ORM 미사용` 기술 설명은 Exposed 적용에 맞게 수정했습니다. 비즈니스 행위 계약과 동결한 테스트는 변경하지 않았습니다.

## 참고 자료

- [Exposed Table types](https://www.jetbrains.com/help/exposed/dao-table-types.html), [CRUD operations](https://www.jetbrains.com/help/exposed/dao-crud-operations.html), [transactions](https://www.jetbrains.com/help/exposed/transactions.html)
- [Exposed 1.6.0 Transactions.kt](https://github.com/JetBrains/Exposed/blob/1.6.0/exposed-jdbc/src/main/kotlin/org/jetbrains/exposed/v1/jdbc/transactions/Transactions.kt), [TransactionManager.kt](https://github.com/JetBrains/Exposed/blob/1.6.0/exposed-jdbc/src/main/kotlin/org/jetbrains/exposed/v1/jdbc/transactions/TransactionManager.kt), [Database.kt](https://github.com/JetBrains/Exposed/blob/1.6.0/exposed-jdbc/src/main/kotlin/org/jetbrains/exposed/v1/jdbc/Database.kt)
- [SQLite JDBC 3.53.4.0 SQLiteConfig.java](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/src/main/java/org/sqlite/SQLiteConfig.java)
- Maven Central: [exposed-core](https://repo.maven.apache.org/maven2/org/jetbrains/exposed/exposed-core/1.6.0/exposed-core-1.6.0.pom), [exposed-jdbc](https://repo.maven.apache.org/maven2/org/jetbrains/exposed/exposed-jdbc/1.6.0/exposed-jdbc-1.6.0.pom), [exposed-dao](https://repo.maven.apache.org/maven2/org/jetbrains/exposed/exposed-dao/1.6.0/exposed-dao-1.6.0.pom)
