# Agent Guidebook 검토 및 DSL 전환 결과

## 검토 목적과 범위

이 문서는 기존 Agent Guidebook 계약을 보존하며 코드 가이드 정리와 Presentation 영속성의 Exposed typed DSL 전환 결과를 기록합니다. 현재 동작, 실패 원인, 외부에서 관찰되는 부작용을 기준으로 책임과 경계를 설명합니다.

Spotless 설정과 포매터 동작은 변경하지 않았습니다.

## 유지할 책임과 계약

| 책임 | 입력과 결과 | 조건과 부작용 |
| --- | --- | --- |
| `GuidelineSearchService` | 검색 요청을 받아 관련 지침을 반환 | 절대 경로 검증과 정규화 후 입력 경계를 통해 읽습니다. 대상 소스나 지침이 없으면 저장소 식별자 조회를 요구하지 않고 빈 결과를 반환합니다. 검색 단계에서 SQLite에 접근하지 않습니다. |
| `GuidelineSearch`와 검색 도메인 | 저장소, 이벤트, 소스, 지침으로 매칭 결과를 계산 | 소스별 언어와 공통 저장소·이벤트를 평가하고 입력 지침 순서를 보존합니다. 여러 소스에서 같은 ID가 일치해도 한 번만 반환합니다. |
| `PresentationSession` | 세션 이력과 검색 결과로 새로 제공할 지침을 판정하고 이력을 기록 | 키는 `(sessionId, guidelineId)`입니다. 입력 순서와 첫 ID를 보존하고, 잘못된 기록은 전체 적용 전에 거부합니다. Core의 Session/Presentation/값 객체 책임과 행위는 변경하지 않습니다. |
| `RepositoryIdentity`와 `Scope` | 정규화된 owner/repository 식별자를 저장소 패턴과 비교 | 정규화된 `owner/repository` 표현은 `RepositoryIdentity.qualifiedName()`으로 제공합니다. `Scope`는 owner와 repository 내부 필드를 조합하지 않고 이 행동과 기존 패턴 매칭 규칙을 사용합니다. |
| SQLite Repository와 UnitOfWork | Repository는 활성 UnitOfWork에서 이력 행을 읽고 기록하며 UnitOfWork는 콜백 실행과 commit/rollback을 관리 | 검색 결과가 비어 있으면 Application은 UnitOfWork를 실행하지 않습니다. Repository는 활성 범위 밖에서 DB 접근을 시작하지 않습니다. UnitOfWork와 설정 객체의 수명 및 기존 SQLite 오류 계약을 보존합니다. |

검색 실패, YAML 오류, 저장소 식별자 오류는 기존 예외 타입과 원인을 유지합니다. Presentation 저장 실패도 빈 성공 결과로 바꾸지 않습니다. callback이 직접 던진 `SQLException` 객체의 동일성, 저장 실패 분류, rollback 및 suppressed 원인, 동시 요청과 독립 JVM의 중복 방지, 전체 작업 rollback을 기존 통합 계약으로 유지합니다.

## 검토 결과와 결정

### 문서화와 조건 의도

- 검수에서 발견된 한 줄 KDoc은 의미 있는 한국어 다중 줄 계약으로 정리했습니다. 공개 생성자와 함수에는 해당 계약에 필요한 `@param`, `@property`, `@return`, `@throws`를 설명하고, 같은 의미를 본문과 태그에서 반복하지 않습니다.
- 검색 서비스의 절대 경로 및 빈 결과 조건, Core 검색의 기존 ID와 소스 매칭, YAML 필수 키 판단은 이름 있는 Boolean 조건으로 표현합니다. `GuidelineReader`는 중복 ID를 먼저 조회하고 중복이면 거부한 뒤 ID를 등록해 검증과 변경을 구분합니다. 단순 null 비교와 명확한 상태 비교는 직접 유지합니다.
- `GuidelineReader.readRootAttributes`는 `NoSuchFileException`에서 `null`을 반환합니다. 프로젝트에 지침 루트가 없는 경우 빈 결과로 처리하는 계약이 있으며, 이 helper는 루트 속성 조회만 담당하므로 목록을 구성하는 `read`가 부재를 빈 목록으로 해석합니다. 다른 I/O 또는 권한 실패는 기존 `GuidelineLoadException`으로 전달합니다. KDoc은 이 배경과 책임을 설명하며 반환 모델과 동작은 유지합니다.
- `read`의 같은 수준 하위 작업인 `readRootAttributes`, `findGuidelineFiles`, `validateUniqueIds`를 먼저 배치하고 `findGuidelineFiles`의 하위 함수인 `relativePath`를 그 아래로 옮겼습니다. 후보 정렬과 검증 동작은 같습니다.
- Presentation 통합 테스트의 trigger fixture를 ABORT 생성과 ROLLBACK 생성 함수로 나눴습니다. 기존 실패·rollback 원인·suppressed assertions를 보존했으며 테스트 전용 SQL은 관찰자와 오류 fixture에만 남아 있습니다.

### 저장소 식별자 협력

`Scope`가 `context.repository.owner`와 `repository`를 직접 조합하던 책임을 `RepositoryIdentity`로 이동했습니다. `qualifiedName(): String`은 정규화된 구성값을 `owner/repository` 형식으로 그대로 반환하고, `Scope`는 이 행동으로 기존 패턴을 평가합니다.

| 계약 항목 | 내용 |
| --- | --- |
| 입력 | 기존 `RepositoryIdentity` 값만 사용하며 추가 인자는 없습니다. |
| 출력 | owner와 repository를 `/`로 연결한 문자열이며 두 구성값을 변형하지 않습니다. |
| 조건 | 기존 생성자 조건을 따릅니다. 두 값은 비어 있거나 공백만일 수 없고, 공백 문자나 `/`를 포함할 수 없습니다. 메서드에는 추가 조건이 없습니다. |
| 상태 | 객체 상태를 읽기만 하며 내부 값을 변경하지 않습니다. |
| 불법 인자·상태 | 메서드에서 새 `IllegalArgumentException` 또는 `IllegalStateException`을 발생시키지 않습니다. 유효하지 않은 구성값은 기존 생성자가 거부합니다. |
| 부작용 | 외부 I/O나 다른 상태 변경이 없습니다. |
| 예시 | `RepositoryIdentity("acme", "backend-order")`는 `acme/backend-order`, `RepositoryIdentity("조직.+", "서비스(표준)")`는 `조직.+/서비스(표준)`을 반환합니다. |

ASCII, 한글, 정규식 메타문자 등 구성값 보존을 `withScenarios` 계약으로 확인합니다. 이 변경은 검색 결과, 이벤트 의미, 언어 처리, 저장소 패턴의 와일드카드 의미를 바꾸지 않습니다.

### Exposed typed DSL

SQLite의 `presentation` 스키마는 세 `TEXT NOT NULL` 열과 `(session_id, guideline_id)` 기본 키를 유지합니다. `PresentationTable`은 일반 Exposed `Table`이며 이력 읽기는 typed DSL `selectAll`을, 기록은 행별 `Table.insert`를 사용합니다. 기본 삽입은 중복을 거부합니다. INSERT 무시·대체 동작이나 bulk 정책은 도입하지 않았습니다. 문자열 행의 도메인 `Presentation` 복원과 손상 데이터 검증은 Repository 책임입니다.

`CompositeIdTable`, `CompositeEntity`, `PresentationRow`, `CompositeID`, `EntityID`와 `exposed-dao` 의존성을 제거했습니다. `exposed-core`, `exposed-jdbc`, SQLite JDBC, Java 도구 체인과 Config·UnitOfWork의 연결 수명·스키마 초기화·오류 경계는 유지합니다. Repository SQL 실패와 callback 실패를 가르는 transaction-local 오류 추적도 그대로 사용합니다.

## 적용 순서와 유지한 실패 경계

### A. 계약 테스트 단계

`RepositoryIdentity.qualifiedName()` 공개 계약은 컴파일 가능한 `NotImplementedError` stub으로 먼저 고정했습니다. stub에서 의도된 RED를 확인한 계약 테스트를 먼저 커밋·게시한 뒤 실제 메서드와 `Scope` 협력을 구현했습니다. 계약에는 ASCII, 한글, 메타문자를 포함한 구성값의 원형 보존을 포함합니다.

`PresentationTest`의 두 업무 시나리오를 조회와 기록이라는 `PresentationSessionTest`의 책임 feature로 옮겼습니다. 원래 assertion과 시나리오 의미는 보존했습니다.

같은 Session ID와 Guideline ID의 기록이 다른 시각이어도 거부되는 시나리오는 기록 feature에, 같은 Guideline ID를 다른 Session에서 제공하는 시나리오는 미제공 조회 feature에 배치했습니다. 기존 SQLite 계약 테스트와 trigger SQL assertion은 그대로 유지합니다.

### B. RepositoryIdentity와 가이드 준수 구현

`qualifiedName()`을 구현하고 `Scope`가 해당 행동을 사용하도록 변경했습니다. KDoc, 조건 의도, nullable 해석 문서와 BFO 순서를 반영했으며 Core 검색·Presentation 동작과 검색 계약은 바꾸지 않았습니다.

### C. typed DSL 적용

Repository를 일반 `Table` 기반 typed DSL로 전환하고 DAO 타입과 의존성을 제거했습니다. 기존 SQLite 스키마와 행 복원, commit 전 결과 미반환, rollback·예외 원인 보존, 활성 범위 제한, 동시·독립 JVM 계약은 유지합니다.

검증은 기존 도메인·Application·SQLite 계약 테스트로 수행합니다. 도메인 단위 테스트는 실제 파일 시스템이나 DB에 접근하지 않고, Application/영속성 통합 테스트는 외부에서 관찰되는 결과와 부작용을 확인합니다. 설정 값 조회나 프레임워크 기동만을 위한 테스트는 추가하지 않습니다.

유지하는 위험 계약은 중간 행 삽입 실패의 전체 rollback, SQLite trigger의 자동 rollback 뒤 명시 rollback 실패 원인 보존, callback 직접 `SQLException` 객체 동일성, UnitOfWork 밖과 중첩 범위 거부, 동시·독립 JVM 중복 방지, 재시작 뒤 기록 유지, 손상 행의 typed 오류 전달입니다.

## 변경 금지 경계

- Spotless 설정과 포매터 동작을 변경하지 않습니다.
- Core의 Presentation 행위 및 검색·Scope 공개 동작을 변경하지 않습니다.
- 새로운 Core 도메인 개념, 일반 Registry, 범용 프레임워크, legacy 호환 API를 추가하지 않습니다.
- 검증을 통과시키려고 기존 assertion을 제거하거나 약화하지 않습니다.
