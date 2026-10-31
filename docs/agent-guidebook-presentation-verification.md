# Agent Guidebook Presentation 검증 기록

작성일: 2026년 10월 29일

## 목표와 구현 경계

같은 Session에는 각 Guideline ID를 한 번만 반환하고, SQLite 파일에 제공 이력을 저장합니다. 기존 검색 결과의 ID와 순서를 유지하며 Hook, 운영 CLI, Session 생명주기, TTL, 삭제, 캐시, ORM은 이번 범위에 포함하지 않습니다.

| 책임 | 구현 |
| --- | --- |
| 도메인 | `PresentationSession`이 이력 ID를 기준으로 미제공 Guideline을 선별하고, 입력 전체를 검증한 뒤 기록합니다. `Presentation`의 키는 Session ID와 Guideline ID입니다. |
| Application | `GuidelinePresentationService`가 기존 검색 서비스, Session Aggregate, 저장소 포트를 연결합니다. 검색은 SQLite 트랜잭션 밖에서 수행합니다. |
| 저장 | `SqlitePresentationRepository`가 연결, `BEGIN IMMEDIATE`, 이력 조회와 삽입, commit 또는 rollback, 저장 오류 변환을 담당합니다. 도메인 선별 규칙은 복제하지 않습니다. |
| 조립 | `GuidelinePresentationFactory`가 기존 `GuidelineSearchFactory.fileSystem()`을 재사용하고 SQLite 저장소와 UTC 시스템 시계를 연결합니다. |

Presentation 처리 순서는 검색, `BEGIN IMMEDIATE`, Session 이력 조회, Aggregate 선별, 한 번 얻은 `Clock` 시각으로 기록 생성, Aggregate 검증, SQL 삽입, commit, 결과 반환입니다. 검색 결과가 비어 있으면 저장소를 호출하지 않습니다. commit 성공 전에 결과 목록을 반환하지 않습니다.

기본 DB 경로는 `user.home/.adaptive-guidebook/adaptive-guidebook.db`입니다. 테이블은 `session_id`, `guideline_id`, `presented_at` 세 열만 두며 기본 키는 `(session_id, guideline_id)`입니다. 시각은 UTC 기준 RFC 3339 `Z` 문자열로 저장합니다. 연결별 busy 대기 제한은 1,000ms입니다.

Callback 오류는 rollback 뒤 원래 타입으로 전달합니다. `BUSY`/`LOCKED`는 재시도 가능 오류로, `CORRUPT`/`NOTADB`와 잘못된 저장 값은 DB 손상 오류로, 경로·권한·읽기 전용·용량 오류는 저장소 사용 불가 오류로 변환합니다. rollback 실패는 원래 실패를 cause로, rollback 오류를 suppressed 예외로 보존합니다. SQL wrapper의 suppressed 예외도 공개 저장소 예외에 전달합니다.

## 구현 및 검증 결과

### 이전 계약 단계의 RED 기록

계약 테스트는 `d7d81e2` → `4dd5898` → `25438c9` → `66dc87e` 순서로 추가했습니다. 계약을 고정한 뒤 구현은 역방향 의존 순서로 진행했습니다. 도메인은 `2631f3d`, Application은 `7400ad1`, SQLite 저장소와 factory는 그 다음 단계입니다. `66dc87e`에서는 SQLite 의존성과 테스트 runtime classpath도 추가했습니다.

| 단계 | 확인한 결과 | 근거 |
| --- | --- | --- |
| 계약 테스트 커밋 `66dc87e` | 전체 203개 중 53개 실패, 150개 통과 | 이전 실행에서 확인한 RED 결과입니다. 해당 실행 로그와 `/tmp/guidebook-presentation-contract-red`는 현재 남아 있지 않습니다. 계약 테스트는 `66dc87e`에서 다시 확인할 수 있습니다. |
| 도메인 구현 커밋 `2631f3d` | 중간 실행에서 32개 실패 | 이전 실행의 단계별 결과입니다. |
| Application 구현 커밋 `7400ad1` | 중간 실행에서 SQLite와 factory 관련 18개 실패 | 이전 실행의 단계별 결과입니다. |

위 RED 수치는 현재 실행의 결과가 아닙니다. 보존된 전체 테스트 결과는 아래 최종 검증뿐입니다.

### 최종 검증

| 순서 | 실제 명령 (`agent-guidebook/`에서 실행) | 결과 | 로그 |
| --- | --- | --- | --- |
| 1 | `rtk proxy ./gradlew spotlessApply` | 성공, 52초 | `/tmp/guidebook-presentation-spotless.log` |
| 2 | `rtk proxy ./gradlew test --tests "com.springkit.agentguidebook.*"` | 성공, 32초. XML 19개에서 테스트 203개, 실패 0개, 오류 0개, 건너뜀 0개 | `/tmp/guidebook-presentation-test.log`; `agent-guidebook/build/test-results/test/TEST-*.xml` |
| 3 | `rtk proxy ./gradlew build` | 성공, 11초. 증분 빌드이며 `clean`을 실행하지 않았습니다. | `/tmp/guidebook-presentation-build.log` |

### 대표 시나리오 결과

| 시나리오 | 결과 |
| --- | --- |
| 임시 `user.home`에서 독립 JVM으로 같은 Session을 두 번 요청 | 첫 요청은 Guideline을 반환하고, 재실행은 빈 결과를 반환했습니다. 임시 홈 아래 DB에는 1행이 남았습니다. |
| 독립 JVM 2개가 READY/GO 신호 뒤 동시에 요청 | Guideline은 최대 한 번 반환했고 DB에는 1행이 기록됐습니다. 다른 프로세스는 빈 결과 또는 재시도 가능 오류를 받았습니다. |
| 두 번째 INSERT를 trigger가 실패시킴 | 전체 transaction이 rollback되어 첫 INSERT도 남지 않았습니다. |
| 다른 연결이 write lock 또는 commit 중 shared read lock을 유지 | 제한 시간 뒤 재시도 가능 저장소 예외가 전달되고 새 행은 남지 않았습니다. |
| SQLite가 아닌 파일, 손상 schema, 잘못된 저장 값 | `PresentationDatabaseCorruptException`이 원인을 보존해 전달됐습니다. |

독립 JVM 검증은 `-Duser.home`을 임시 디렉터리로 지정했습니다. 실제 사용자 홈이나 그 안의 DB에는 접근하지 않았습니다.

### 변경 범위 확인

`66dc87e` 이후 구현 단계에서는 테스트 소스, 계약 문서, `agent-guidebook/build.gradle.kts`를 변경하지 않았습니다. 앞선 계약 단계에서 `66dc87e`가 SQLite 의존성과 테스트 runtime classpath를 추가했습니다. Spotless 적용, 전체 패키지 테스트, 빌드가 지정된 순서로 성공했습니다. 기존 Hook 및 운영 CLI는 이번 구현과 연결하지 않았습니다.
