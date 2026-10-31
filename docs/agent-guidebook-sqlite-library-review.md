# SQLite Presentation 저장소의 Exposed typed DSL

이 문서는 현재 SQLite adapter의 테이블 정의, 조회·기록 방식과 실패 경계를 설명합니다. 저장 계약은 [Presentation 계약](agent-guidebook-presentation-contract.md), Config와 UnitOfWork 책임은 [영속성 경계 검토](agent-guidebook-unit-of-work-review.md)를 따릅니다.

## 테이블과 Repository

영속성은 Exposed 1.6.0의 `exposed-core`, `exposed-jdbc` typed DSL을 사용합니다. 별도 DAO 모듈이나 ORM Entity는 필요하지 않습니다. SQLite JDBC driver와 기존 SQLite 스키마는 유지합니다.

```kotlin
internal object PresentationTable : Table("presentation") {
    val sessionId = text("session_id")
    val guidelineId = text("guideline_id")
    val presentedAt = text("presented_at")

    override val primaryKey = PrimaryKey(sessionId, guidelineId)
}
```

일반 `Table`과 `text()` 열은 기존 세 `TEXT NOT NULL` 열을 표현하며 기본 키는 `(session_id, guideline_id)`입니다. `presented_at`은 기존처럼 `Instant.toString()` 값을 저장해 UTC `Z` 표기를 유지합니다. synthetic ID, 날짜 전용 열, 세션 테이블은 추가하지 않습니다.

Repository는 typed `selectAll`로 Session 행을 읽고, typed `Table.insert`를 각 기록 행에 실행합니다.

```kotlin
PresentationTable.insert {
    it[PresentationTable.sessionId] = presentation.sessionId.value
    it[PresentationTable.guidelineId] = presentation.guidelineId.value
    it[PresentationTable.presentedAt] = presentation.presentedAt.toString()
}
```

기본 `insert`는 키가 중복되면 실패합니다. `insertIgnore`, `replace`, bulk insert로 충돌이나 실행 방식을 바꾸지 않습니다. Repository가 `ResultRow`의 문자열 값을 `SessionId`, `GuidelineId`, `Instant`로 복원하고 잘못된 값과 중복 이력을 `PresentationDatabaseCorruptException`으로 변환합니다. 별도 `PresentationRow`, `CompositeID`, `EntityID`는 사용하지 않습니다.

## 스키마와 transaction

실행마다 Config가 새 Exposed `Database`를 구성하고, UnitOfWork가 이를 명시한 Exposed transaction을 엽니다. callback 전에 Config의 `initializeSchema()`가 `SchemaUtils.create(PresentationTable)`을 호출합니다. 이 호출은 현재 transaction의 스키마 작업을 내부 commit하고 transaction user data를 비웁니다. 따라서 UnitOfWork는 스키마 준비 뒤 transaction-local context를 등록합니다. 스키마 준비의 내부 commit은 뒤이은 callback 실패로 rollback되지 않습니다.

각 연결에는 busy timeout 1,000ms와 `IMMEDIATE` transaction mode를 적용합니다. Config는 `DatabaseConfig.defaultMaxAttempts = 1` 및 최소·최대 retry delay 0을 설정합니다. 이 시도 제한은 Database 기본값으로 설정하며 transaction 호출에 별도 횟수를 전달하지 않습니다.

그 후 callback에서 수행하는 이력 조회, 미제공 지침 판정과 각 `insert`는 UnitOfWork의 완료 경계에서 함께 commit 또는 rollback됩니다. 기록 결과는 해당 최종 commit이 성공한 뒤에만 반환됩니다. 검색은 transaction 밖에서 수행하고 빈 검색은 DB와 상위 디렉터리에 접근하지 않습니다.

## 실패 원인 보존

Repository는 활성 UnitOfWork의 transaction-local context를 먼저 확인합니다. 이 context가 없거나 Repository 소유 UnitOfWork가 다르면 `IllegalStateException`으로 거부하고 SQL을 실행하지 않습니다. 빈 기록도 이 범위 검사를 먼저 수행합니다.

Repository의 typed DSL에서 발생한 `SQLException`은 같은 객체를 transaction-local context에 기록한 뒤 그대로 다시 던집니다. UnitOfWork는 transaction 종료 뒤 최종 실패 객체와 기록된 저장 실패를 대조해 저장 SQL 실패를 분류합니다. callback이 직접 던진 `SQLException`은 Repository 실패 표시가 없으므로 같은 객체로 전파됩니다.

Exposed 연결 adapter는 rollback과 close 실패 원인을 요청별로 보존합니다. rollback까지 실패하면 `PresentationTransactionRollbackException`의 cause에는 원래 저장 또는 callback 실패를, suppressed에는 rollback 실패를 둡니다. SQLite trigger의 `RAISE(ROLLBACK)`으로 DB가 먼저 rollback한 뒤 명시 rollback이 실패하는 경우에도 두 원인을 유지합니다. 이 예외 경계는 `SqlitePresentationUnitOfWork`가 담당합니다.

## 유지하는 실패·경계 계약

- 중간 행 `insert`가 실패하면 앞서 기록한 행도 전체 rollback됩니다.
- callback의 직접 `SQLException`은 객체 동일성을 유지하고 저장 오류로 재분류하지 않습니다.
- 활성 UnitOfWork 밖의 Repository 조회·기록과 같은/다른 UnitOfWork의 중첩 실행은 DB 작업 전에 거부합니다.
- 같은 Session의 동시 요청과 독립 JVM의 요청은 기존 단일 제공·기록 경계를 유지합니다. commit 뒤 재시작 시 이미 저장한 ID를 다시 제공하지 않습니다.
- 잘못된 저장 행과 중복 이력은 손상 오류로 전달하고 DB를 자동 삭제하거나 재생성하지 않습니다.

## 참고 자료

- [Exposed 1.6.0 CRUD operations](https://www.jetbrains.com/help/exposed/dsl-crud-operations.html)
- [Exposed 1.6.0 Working with tables](https://www.jetbrains.com/help/exposed/working-with-tables.html)
