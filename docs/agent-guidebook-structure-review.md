# Agent Guidebook 설치 실행과 구조 점검

목표는 설치 위치와 프로젝트 위치가 달라도 전체 지침 제공을 실행할 수 있게 하고, 기능별 패키지와 도메인 책임을 일관되게 정리하는 것입니다. GitHub Release와 GraalVM Native Image 구성은 후속 작업입니다. 현재 JVM 프로그램을 기준으로 검증합니다.

## 확인한 상태

| 점검 대상 | 확인 결과 | 조치 |
| --- | --- | --- |
| 설치 파일 | `installDist` 결과는 `bin`과 `lib`만 포함 | 기존 Hook과 설정 예제를 설치 결과에 포함 |
| 실제 실행 검증 | 프로세스 테스트는 checkout의 `hooks/post-tool-use.sh`와 설치된 실행 파일을 함께 사용 | 설치 결과 전체를 별도 경로에 복사하고 그 안의 Hook과 실행 파일만 사용 |
| 도메인 책임 | Guideline의 적용·일치, 검색, Session별 선별·기록은 기존 도메인 객체가 소유 | 행위와 불변식 유지. 새 Aggregate나 기술적 Domain 객체 추가 없음 |
| VSA 조율 | 검색은 `services`, 제공은 `features.presentation`에 배치 | 두 기능 모두 feature의 application UseCase로 조율 |
| 패키지 의존 | `services → features.presentation → services` 순환 | canonical feature가 레거시 서비스에 의존하지 않도록 검색 경계와 포트 이동 |
| 영속 경계 | `config`가 `storage.PresentationTable`을 사용하고 `storage`가 다시 config를 사용 | SQLite 구성·테이블·연결·Repository·UnitOfWork를 같은 feature infrastructure에 배치 |
| 공개 Model | Request, 값 객체와 평가 입력은 프레임워크에 의존하지 않는 기존 공개 API | 공개 이름과 도메인 행위를 유지하고 공유 입력·값의 역할을 명시 |
| 외부 의존 | 파일·Git·YAML·SQLite·JSON은 읽기 경계와 Codex Adapter에서 처리 | Domain과 application에 구현 라이브러리가 유입되지 않도록 의존 방향 확인 |

`Guideline`, `Scope`, `MatchCondition`, `GuidelineSearch`, `PresentationSession`의 기존 행위를 재사용합니다. `Presentation`은 제공 사실이며 데이터만 가진다는 이유로 새 행위를 추가하지 않습니다. `SourceFile`과 `ApplicabilityContext`는 검색 평가에 필요한 불변 자료이며 새 Entity나 Aggregate로 취급하지 않습니다.

## Minimalism 적용과 패키지 경계

2026년 11월 2일 최신 develop의 `minimalism` 스킬을 적용해 범위를 재검토했습니다. 초기의 도메인 타입 전체 이동, 새 shared/adapters/bootstrap 계층, 추가 조립 factory 계획을 축소합니다. 확인된 미충족 조건은 Application 패키지 순환, feature별 인프라 책임의 분산, 설치 Hook 누락입니다. 도메인은 이미 행위와 불변식을 소유하므로 이를 새 구조로 옮기는 비용을 추가하지 않습니다.

기본 패키지 `com.springkit.agentguidebook`과 단일 Gradle 모듈, 공개 `core/models/errors`, 기존 `client/codex` 경계를 유지합니다. 검색·제공의 UseCase와 포트 및 실제 인프라 구현을 기능별로 묶고 순환 의존을 제거합니다. 이 문서의 최신 경계가 초기 이동 계획보다 우선합니다.

```text
features.search
  application: SearchGuidelinesUseCase, SearchGuidelines, 입력 읽기 포트,
               기존 공개 Request·오류 계약 사용
  infrastructure: 파일·YAML·Git reader와 parser
features.presentation
  application: PresentGuidelinesUseCase, PresentationRepository, UnitOfWork,
               기존 공개 Request·오류 계약 사용
  infrastructure: SQLite config·table·connection·repository·unit of work
core: 기존 Rich Domain Model과 검색 Domain Service
models: 기존 공개 요청·값 객체·평가 입력
errors: 호출자가 사용하는 기존 실패 계약
client: 기존 공개 factory와 실제 구성 요소 조립
codex: 기존 Hook Adapter와 실행 진입점
services: 기존 공개 service와 이동한 포트의 호환 이름
storage / config / features.presentation: 이동한 공개 타입의 호환 이름
```

공유 도메인과 공개 입력 타입은 기존 위치에서 직접 재사용합니다. 범용 helper나 미래 확장용 추상화를 추가하지 않습니다. 도메인 타입은 JSON, Exposed, JGit, YAML, SQLite, 파일 읽기 API를 참조하지 않습니다. `Path`와 `Instant`처럼 입력 값을 표현하는 JDK 타입은 허용합니다.

Search application의 `SearchGuidelines`는 `execute(GuidelineSearchRequest): List<MatchedGuideline>` 계약입니다. `SearchGuidelinesUseCase`가 이를 구현합니다. Presentation application은 이 경계에 의존하고 구체 레거시 `GuidelineSearchService`를 참조하지 않습니다. 검색은 transaction 밖, 이력 조회·선별·기록은 기존 UnitOfWork 안에서 실행하는 순서를 유지합니다.

기존 client factory가 실제 reader, SQLite config, UnitOfWork, Repository와 Clock을 조립합니다. 두 feature의 application과 infrastructure는 이전 `services/storage/config`를 참조하지 않습니다. 기존 서비스는 `.search`와 `.present` API를 유지하며 UseCase로 요청을 연결합니다. 기존 `codex.MainKt`와 Gradle mainClass를 유지하고 별도 bootstrap factory를 추가하지 않습니다.

기존 `client` factory와 `services`의 생성자·메서드 이름, 도메인·Model·오류의 공개 타입 이름을 유지합니다. 실제 이동한 포트·SQLite 타입·기존 Presentation UseCase 이름에만 Kotlin typealias를 제공합니다. 이동 타입의 호환 기준은 Kotlin 소스 재컴파일이며, 이전 FQCN의 JVM 클래스와 이미 컴파일된 바이너리 호환을 보장하지 않습니다. 실제 구현은 새 feature 경계를 사용하고 기존 공개 API 통합 테스트로 호출 호환을 확인합니다.

## 설치 파일 계약

```text
<installation>/
  bin/agent-guidebook
  bin/agent-guidebook.bat
  lib/*.jar
  hooks/post-tool-use.sh
  hooks/hooks.example.json
```

현재 Linux JVM 실행을 대상으로 설치 경로에 공백을 포함해 검증합니다. 실행 파일과 Hook은 설치 경로에서 가져오고, 프로젝트의 소스·Git origin·`.agents/guides`는 요청 `cwd`에서 읽습니다. SQLite 이력은 별도 `user.home`에 저장합니다. 설치 디렉터리는 실행 중 변경하지 않습니다. 새 Windows Hook이나 Windows/GraalVM 실행 검증은 이 작업에서 추가하지 않습니다.

## 오케스트레이터가 확정한 테스트 목록과 순서

1. 기존 AR → Entity → VO 계약을 회귀 검증합니다. 실제 도메인 규칙은 변경하지 않으므로 중복 계약을 새로 만들지 않습니다.
2. 기존 `GuidelineSearchServiceTest`의 시나리오를 `features.search.application.SearchGuidelinesUseCaseTest`로 옮깁니다. SUT는 `SearchGuidelinesUseCase.execute`로 바꾸고 입력 읽기 Fake와 정상·빈 결과·읽기 오류·불법 경로 계약을 유지합니다. 최소 Stub에서 컴파일 성공 후 의도한 RED를 확인합니다. 기존 public search integration은 유지하여 façade 호환을 검증합니다.
3. 설치 구조 계약을 추가합니다. 설치 실행 파일, runtime 라이브러리, 실행 가능한 Hook, 유효한 JSON 설정 예제가 함께 있어야 합니다. 누락 자산이 현재 RED의 원인이어야 합니다.
4. 기존 프로세스 계약 4개를 강화합니다. 설치 전체를 임시 경로로 복사하고 복사한 Hook과 실행 파일만 사용합니다. 최초 제공·같은 Session 제외·다른 Session 분리, 삭제·누락 대상의 DB 미생성, 입력 오류 64, 저장 실패 1 계약을 유지합니다. 실행 전후 설치 파일이 변경되지 않음도 확인합니다. 정상 프로젝트 cwd와 임시 user.home은 설치 경로와 분리합니다.
5. 전체 신규 계약을 확정한 뒤 실제 순환을 만드는 Application·포트·인프라 경계만 정리하고 설치 구성 누락을 수정합니다. 도메인 타입·Codex 실행 진입점과 기존 시나리오는 유지합니다. 이름 이동만으로 생긴 컴파일 실패를 RED로 인정하지 않습니다.

테스트 작성 목록과 범위는 오케스트레이터가 결정하고 Worker가 코드로 작성합니다. 새 architecture 검사 라이브러리나 내부 파일 위치만 확인하는 구현 추종 테스트는 추가하지 않습니다. 패키지 이동은 기존 계약 회귀와 import 의존 감사로 검증합니다. 설치 파일 위치는 사용자가 Hook을 연결할 때 사용하는 외부 계약이므로 실제 파일 구성과 실행으로 검증합니다.

## Step과 수용 기준

| Step | 작업 | 검증 |
| --- | --- | --- |
| 1 | 구조 감사와 계약 확정 | 실제 소유권·의존·설치 결과를 분석 Artifact와 대조 |
| 2 | 검색 UseCase 계약 | 최소 Stub에서 지정 실패, 기존 도메인 회귀 성공 |
| 3 | 설치 파일·프로세스 계약 | 컴파일 성공, checkout 의존과 누락 Hook 때문에 발생한 RED 확인 |
| 4 | 기능별 패키지와 조립 정리 | 설치 자산 미구현 계약을 제외한 기존·검색 계약 성공, canonical 순환·역방향 의존 없음 |
| 5 | 설치 파일 구성 정리 | 복사한 설치 결과만으로 전체 프로세스 계약 성공 |
| 6 | 문서·전체 검증·PR 반영 | spotlessApply → test → 증분 build 성공, 최종 diff와 PR 본문 일치 |

각 Step을 검증하고 커밋한 뒤 다음 Step으로 진행합니다. 분석 완료 후 사용자 승인 대기 없이 계약 작성으로 진행합니다. GitHub Release, Native Image, 새 도메인 규칙과 자동 설치 도구는 추가하지 않습니다.
