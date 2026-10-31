# Agent Guidebook 검토 및 DSL 전환 계획

## 검토 목적과 범위

이 문서는 기존 Agent Guidebook 계약을 보존하면서 코드 가이드 준수 항목과 Presentation 영속성의 Exposed DAO 제거를 순서대로 진행하기 위한 중간 검토 결과입니다. 현재 동작, 실패 원인, 외부에서 관찰되는 부작용을 기준으로 계약 단계와 구현 단계를 분리합니다.

Spotless 설정 검토 결과는 이번 작업에서 제외합니다. 포맷터, ratchet, Spotless 관련 설정은 변경하지 않습니다. 작업 중 발견된 README 문구 수정과 일곱 `package-info.kt` 삭제는 기존 사용자 변경으로 보존합니다.

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

- 검수에서 발견된 한 줄 KDoc을 의미 있는 다중 줄 KDoc으로 정리합니다. 공개 생성자와 함수에는 실제 계약에 필요한 `@param`, `@property`, `@return`, `@throws`만 사용하고 본문을 반복해 형식만 늘리지 않습니다. 모든 주석과 예외 설명은 한국어로 작성합니다.
- 검색 서비스의 절대 경로 검사 및 빈 소스·빈 지침 판단, Core 검색의 기존 ID 판단과 매칭 호출, YAML 필수 키 존재 판단은 이름 있는 Boolean 조건으로 표현합니다. `GuidelineReader`는 중복 ID인지 먼저 조회하고, 중복이면 기존 오류를 던진 뒤 새 ID를 등록하는 순서로 검증과 변경을 구분합니다. 단순 null 비교와 명확한 상태 비교는 별도 변수로 감싸지 않습니다.
- `GuidelineReader.readRootAttributes`가 `NoSuchFileException`에서 `null`을 반환하는 것은 지침 루트 부재를 빈 검색 결과로 취급하는 현재 계약을 위한 내부 표현입니다. helper는 루트 부재를 검색 결과로 결정하지 않으며, 이를 소비하는 `read`가 빈 결과 의미를 맡습니다. 다른 I/O 또는 권한 실패는 기존 `GuidelineLoadException`으로 전달합니다. 이 근거를 helper의 다중 줄 KDoc에 명시하고 동작이나 반환 모델은 바꾸지 않습니다.
- `read`의 같은 수준 하위 작업인 `readRootAttributes`, `findGuidelineFiles`, `validateUniqueIds`를 먼저 배치하고, `findGuidelineFiles`의 하위 함수인 `relativePath`는 그 다음에 둡니다. 후보 정렬과 검증 동작은 바꾸지 않습니다.
- Presentation 통합 테스트의 `createSecondInsertFailureTrigger(..., rollback: Boolean)`은 ABORT trigger 생성과 ROLLBACK trigger 생성 함수로 분리합니다. 기존 rollback assertion, 원인과 suppressed 보존 검증은 그대로 둡니다. 테스트의 직접 SQL은 관찰자 및 오류 fixture에만 유지하고 production SQL 문자열은 추가하지 않습니다.

### 저장소 식별자 협력

`Scope`가 `context.repository.owner`와 `repository`를 직접 조합하는 현재 협력은 정규화 표현을 알고 있는 `RepositoryIdentity`로 이동합니다. `RepositoryIdentity.qualifiedName(): String`을 추가하고, `Scope`는 전달된 identity의 행동으로 패턴을 평가합니다.

| 계약 항목 | 내용 |
| --- | --- |
| 입력 | 기존 `RepositoryIdentity` 값만 사용하며 추가 인자는 없습니다. |
| 출력 | owner와 repository를 `/`로 연결한 문자열이며 두 구성값을 변형하지 않습니다. |
| 조건 | 기존 생성자 조건을 따릅니다. 두 값은 비어 있거나 공백만일 수 없고, 공백 문자나 `/`를 포함할 수 없습니다. 메서드에는 추가 조건이 없습니다. |
| 상태 | 객체 상태를 읽기만 하며 내부 값을 변경하지 않습니다. |
| 불법 인자·상태 | 메서드에서 새 `IllegalArgumentException` 또는 `IllegalStateException`을 발생시키지 않습니다. 유효하지 않은 구성값은 기존 생성자가 거부합니다. |
| 부작용 | 외부 I/O나 다른 상태 변경이 없습니다. |
| 예시 | `RepositoryIdentity("acme", "backend-order")`는 `acme/backend-order`, `RepositoryIdentity("조직.+", "서비스(표준)")`는 `조직.+/서비스(표준)`을 반환합니다. |

ASCII, 한글, 정규식 메타문자 등 실제 구성 값을 변형 없이 보존하는 `withScenarios` 계약으로 이 출력을 검증합니다. 이 변경은 검색 결과, 이벤트 의미, 언어 처리, 저장소 패턴의 와일드카드 의미를 바꾸지 않습니다.

### Exposed typed DSL

SQLite의 테이블 스키마는 `presentation` 테이블의 세 `TEXT NOT NULL` 열과 `(session_id, guideline_id)` 기본 키로 유지합니다. `PresentationTable`은 일반 Exposed `Table`로 두고, 이력 읽기는 현재 typed DSL을 유지합니다. 기록은 typed `Table.insert`로 행별 즉시 수행하며 기본 중복 거부 동작을 유지합니다. INSERT 무시·대체 동작이나 bulk 정책을 새로 도입하지 않습니다. 도메인 `Presentation`으로의 행 변환과 손상 데이터 검증은 Repository 책임입니다.

`CompositeIdTable`, `CompositeEntity`, `PresentationRow`, `CompositeID`, `EntityID`와 DAO 전용 코드를 제거하고 `exposed-dao` 의존성 한 줄만 제거합니다. `exposed-core`와 `exposed-jdbc`, SQLite JDBC, Java 도구 체인은 유지합니다. Config와 UnitOfWork의 연결 수명·스키마 초기화·오류 경계는 기존 구조를 유지하며 변경 목적에 필요한 조정만 합니다. DSL 실행 오류가 callback 실패와 구별되어야 하는 현재 transaction-local 오류 추적 계약도 보존합니다.

## 순차 진행

### A. 계약 테스트 단계

먼저 `RepositoryIdentity.qualifiedName()`의 공개 계약 테스트를 추가하고 구현은 한국어 설명이 있는 컴파일 가능한 `NotImplementedError` stub으로 둡니다. 기존 Scope·검색 계약 테스트는 재사용하고, 신규 표현값 조합은 `withScenarios`로 검증합니다. 테스트는 컴파일된 뒤 stub에서 의도적으로 실패해야 합니다.

기존 `PresentationTest`의 두 업무 시나리오를 책임이 맞는 `PresentationSessionTest` feature로 옮기고 원래 assertion과 시나리오 의미를 보존합니다. Presentation 테스트 fixture의 trigger 선택 플래그도 분리 함수로 바꿉니다. 테스트 의미를 약화하거나 관련 없는 구현을 추가하지 않습니다.

같은 Session과 Guideline ID의 기록이 시각이 달라도 거부되는 시나리오는 Session 기록 feature로 이동합니다. 같은 Guideline ID를 다른 Session에서 제공할 수 있는 시나리오는 Session 미제공 조회 feature로 이동합니다. 테스트 파일 삭제는 동일 assertion을 책임에 맞는 기존 테스트 파일로 옮긴 뒤에만 수행합니다.

이 단계에서는 계약 테스트, 최소 stub 및 지정 문서만 수정합니다. `spotlessApply` 뒤 신규 값 객체 계약 테스트를 실행해 컴파일 성공과 stub에 의한 의도된 RED를 확인합니다. 테스트 계약을 먼저 커밋·게시한 다음 실제 구현 단계로 진행합니다. 이 단계에서는 실제 구현과 DSL 변경을 하지 않습니다.

### B. RepositoryIdentity와 가이드 준수 구현

계약 테스트 단계 뒤 `qualifiedName()`을 구현하고 `Scope`가 해당 행동을 사용하도록 변경합니다. 같은 구현 단계에서 지정된 KDoc, 조건 의도, nullable 해석 문서, BFO 순서를 반영합니다. Core 검색·Presentation 동작과 기존 검색 계약은 바꾸지 않습니다. 관련 값 객체, 검색, Scope 테스트로 확인하고 별도 커밋합니다.

### C. DAO 제거와 typed DSL 구현

B 단계 다음에 일반 `Table` 기반으로 스키마와 Repository 쓰기를 전환하고 DAO 타입 및 의존성 한 줄을 제거합니다. SQLite 스키마, 행 복원, commit 전에 결과를 반환하지 않는 동작, rollback과 예외 원인 보존, 활성 범위 제한, 동시 및 독립 JVM 계약을 기존 통합 테스트로 확인합니다. 기존 assertions와 fixtures의 의미를 바꾸지 않고 별도 검증·커밋합니다.

각 구현 단계는 프로젝트 순서인 `spotlessApply` → 범위 테스트 → 증분 `build`를 따릅니다. 설정 값 그대로 읽기, 설정 객체 기동, 프레임워크 구동만을 확인하는 테스트는 추가하지 않습니다. 도메인 단위 테스트는 실제 파일 시스템이나 DB에 접근하지 않고, Application/영속성 통합 테스트는 기존 테스트 기준에 따라 관찰 가능한 결과와 부작용을 확인합니다.

## 변경 금지 경계

- Spotless 설정과 포매터 동작을 변경하지 않습니다.
- Core의 Presentation 행위 및 검색·Scope 공개 동작을 변경하지 않습니다.
- 새로운 Core 도메인 개념, 일반 Registry, 범용 프레임워크, legacy 호환 API를 추가하지 않습니다.
- 검증을 통과시키려고 기존 assertion을 제거하거나 약화하지 않습니다.
- 사용자 변경인 `agent-guidebook/README.md` 문구와 일곱 `package-info.kt` 삭제를 수정하거나 복원하지 않습니다.
- 계약 테스트, 책임 정리, DAO 제거 순서를 바꾸지 않습니다.
