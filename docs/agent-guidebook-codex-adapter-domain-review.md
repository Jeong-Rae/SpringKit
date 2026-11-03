# Agent Guidebook Codex 입출력 Adapter 분석

SK 39는 성공한 Codex `apply_patch`의 변경 파일로 지침을 검색하고, 현재 Session에 새로 제공할 지침을 `additionalContext`로 반환합니다. 테스트 목록과 범위는 오케스트레이터가 확정하며 Worker는 해당 계약의 테스트 코드와 구현을 작성합니다.

## 범위와 기존 책임

기존 Bash wrapper, 검색 도메인, `PresentationSession`, `SessionId`, `GuidelineId`, `GuidelinePresentationRequest`, SQLite Repository와 UnitOfWork를 재사용합니다. 새 Aggregate나 Entity는 추가하지 않습니다. JSON, 도구 결과 문자열과 경로 추출은 Codex Adapter의 기술 책임입니다.

`features.presentation.PresentGuidelinesUseCase.execute(request)`가 검색, 이력 조회, 도메인 판단과 저장을 조율합니다. 기존 `GuidelinePresentationService.present`는 같은 생성자와 공개 호출을 유지하며 UseCase로 위임합니다. 검색 알고리즘, Session 중복 판정과 저장 규칙을 복제하지 않습니다. 기존 전체 패키지를 이동하지 않습니다.

호출식은 `Bash wrapper → 실행 진입점 → Codex 입력 Adapter → PresentGuidelinesUseCase → 기존 검색 / PresentationSession / SQLite → Codex 출력 Adapter → stdout`입니다. 실행 진입점은 조립과 표준 스트림을 담당하고 도메인 판단을 하지 않습니다.

다른 Agent, 다른 검색 언어, 상주 서버, TTL, 수신 확인, 자동 재전송, Native Image와 사용자 전역 Codex 설정 변경은 제외합니다.

## 행위 계약

| 주체 | 입력과 조건 | 결과와 부작용 |
| --- | --- | --- |
| 입력 Adapter | JSON 객체의 `hook_event_name`, `tool_name` | `PostToolUse` / `apply_patch`만 처리. 다른 이벤트와 도구는 빈 처리 결과. 무관한 이벤트의 Session·경로는 검증하지 않음 |
| 입력 Adapter | 대상 이벤트의 문자열 `session_id`, 절대 `cwd`, 문자열 `tool_response` | 필수 필드 누락·타입 오류·빈 Session·상대 cwd는 입력 오류. 검색과 저장 전 거부 |
| 입력 Adapter | 실측 fixture의 `Exit code: 0`과 `Success. Updated the following files:` 변경 목록 | 성공 목록의 `A ` / `M ` 경로만 사용. 실패한 패치는 처리하지 않음. 성공 표시만 있는 원래 도구 출력도 지원. 알 수 없는 성공 목록 형식은 입력 오류 |
| 입력 Adapter | 변경 경로 | 상대 경로는 payload cwd 기준 절대 경로로 정규화. 최초 순서로 중복 제거. `D `와 현재 없는 파일, 디렉터리는 제외. 접근 실패는 실행 실패. patch 본문을 대체 검색원으로 사용하지 않음 |
| UseCase | 기존 `GuidelinePresentationRequest` | 검색은 transaction 밖에서 수행. 검색이 비면 저장 경계 미접근. 이력 조회·도메인 선별·기록은 같은 UnitOfWork 안에서 수행 |
| PresentationSession | 검색된 지침과 기존 이력 | 같은 Session·Guideline ID는 본문이나 시각이 달라도 재제공하지 않음. 선별은 상태를 바꾸지 않음. 잘못된 기록 명령은 전체 거부 |
| UseCase | 새 지침 목록 | 같은 시각으로 이력 기록. commit 성공 뒤에만 결과 반환. 검색·저장 실패 원인 보존. rollback 계약은 기존 저장 경계 유지 |
| 출력 Adapter | 새 지침의 ID와 본문 목록 | 각 항목을 `[id]` 줄과 원문 본문으로 구성하고 항목 사이를 빈 줄로 연결. 검색 순서 유지. JSON 라이브러리로 인코딩 |
| 출력 Adapter | 빈 목록 | 응답 없음. 실행 앱은 stdout에 byte를 쓰지 않음 |
| 실행 진입점 | stdin UTF-8 JSON | 응답이 있으면 `hookSpecificOutput.hookEventName = PostToolUse`, `additionalContext` 문자열을 가진 JSON 객체 하나를 stdout에 출력. 정상 진단 없음 |
| 실행 진입점 | 잘못된 입력 / 검색·저장·입출력 실패 | 입력 오류는 stderr 진단과 64, 실행 실패는 stderr 진단과 1. 오류를 빈 성공으로 바꾸지 않음. 예기한 처리 제외는 빈 stdout과 0 |

요청 간 메모리 상태는 공유하지 않습니다. 영속 상태는 SQLite의 제공 이력만 유지합니다. commit 뒤 stdout 기록이 실패하면 이력은 이미 남을 수 있습니다. 이를 rollback하거나 자동 재전송하는 계약은 추가하지 않습니다.

## 오케스트레이터가 확정한 테스트 목록

기존 도메인 계약은 상위 객체부터 회귀 검증합니다. `PresentationSessionTest`의 미제공 선별, 이력 순서, 중복 ID, 다른 Session, 불법 인자와 불법 상태의 원자성을 먼저 확인합니다. 이어 Entity의 기존 행위와 `SessionIdTest`, `GuidelineIdTest`, `GuidelinePresentationRequestTest`를 확인합니다. 데이터만 보유한 `Presentation`에 구현을 따라가는 테스트는 추가하지 않습니다.

| 작성 순서 | 테스트 책임 | 필수 시나리오 |
| --- | --- | --- |
| 1 | `PresentGuidelinesUseCaseTest` 제공 | 최초 제공 순서와 동일 시각, 반복 요청 제외, 일부 이력 제외, 다른 Session 분리, 본문 변경 후 같은 ID 제외 |
| 2 | `PresentGuidelinesUseCaseTest` 실행 경계 | 빈 검색의 저장 미접근, 검색 오류 원인·이력 보존, 일부 기록 실패 rollback, commit 실패 rollback, 검색과 transaction 분리, 상대 요청 경로 거부 |
| 3 | `CodexPostToolUseInputAdapterTest` 이벤트와 입력 | 실측 필드 fixture, 무관한 이벤트·도구, 실패한 패치, JSON 구문·최상위 타입 오류, 필수 필드 누락·타입 오류, 빈 Session, 상대 cwd |
| 4 | `CodexPostToolUseInputAdapterTest` 변경 경로 | A/M/D 혼합, 상대·절대 경로, 정규화 중복, 공백·한글 파일명, 존재하지 않는 파일·디렉터리 제외, payload cwd 기준 처리, 비어 있는 변경 대상, 잘못된 성공 목록 |
| 5 | `CodexPostToolUseOutputAdapterTest` 응답 | 순서와 정확한 Context 형식, 빈 결과의 응답 없음, 한글·따옴표·역슬래시·줄바꿈의 JSON 보존 |
| 6 | 실행 앱 계약 | 정상 출력, 처리 제외의 UseCase 미실행, 잘못된 입력과 UseCase 실패의 stderr·종료 코드, stdout 실패의 nonzero 종료 |
| 7 | 실제 앱 프로세스 통합 | 생산 wrapper부터 실제 앱·Git·YAML·SQLite·응답 연결, 같은 Session 재실행 제외, 다른 Session 제공, 빈 대상에서 DB 미생성, 손상된 입력에서 DB 미생성, 저장 실패에서 Context 미반환 |

Kotest FeatureSpec의 한국어 Scenario, 실제 도메인 객체와 메모리 Fake를 사용합니다. DB 통합은 임시 SQLite를 사용하고, 프로세스 테스트는 자식 JVM에 임시 `user.home`을 전달합니다. 실제 사용자 홈 DB와 전역 설정을 변경하지 않습니다. 호출 횟수 mock 대신 반환값, Fake가 허용하는 실행 상태, 저장 이력과 표준 스트림을 검증합니다.

## Step 검증과 커밋

분석 Artifact를 확정한 뒤 승인 대기 없이 진행합니다. 기존 도메인 회귀 검증 다음 UseCase, 입력 Adapter, 출력·실행 계약을 순서대로 작성합니다. 계약 작성 커밋은 제공 행위, 실행 실패, 입력 해석, 경로 처리, 출력 형식과 프로세스 경계를 구분합니다. 각 커밋은 해당 책임의 테스트와 컴파일 가능한 최소 Stub만 포함할 수 있습니다.

새 계약의 RED는 컴파일 성공 후 미구현 Stub에서 의도한 실패가 나는 상태입니다. 기존 회귀의 GREEN과 구분하여 명령, 성공·실패 수와 실패 원인을 기록합니다. 컴파일 실패나 다른 원인의 실패는 RED로 수용하지 않습니다. 모든 계약과 호출식 확정 뒤 하위 Model·Adapter, UseCase, 실행 조립 순서로 실제 구현합니다. 새 도메인 행위가 필요해지면 재사용과 도메인 근거를 다시 검토하고 계약을 먼저 수정합니다.

각 Step은 검증한 뒤 다음 Step으로 진행합니다. 최종 검증은 `agent-guidebook`에서 `./gradlew spotlessApply`, 범위 테스트, `./gradlew build` 순서로 수행합니다. `clean`과 별도 `check`는 실행하지 않습니다. 프로세스 검증과 실제 Codex 다음 입력에서의 관찰 결과는 구분해 기록합니다.

## 근거

- [POC 요구사항](agent-guidebook-poc.md)
- [검색 계약](agent-guidebook-search-contract.md)
- [Presentation 계약](agent-guidebook-presentation-contract.md)
- [Hook 실측과 책임](agent-guidebook-hook-domain-review.md)
- [Hook 프로세스 계약](agent-guidebook-hook-contract.md)
- [공식 Codex Hook 문서](https://learn.chatgpt.com/docs/hooks): `tool_response`는 도구별 JSON 값이며, 이번 Adapter는 저장소에서 관찰한 문자열 형식만 지원합니다. 다른 출력 형식을 추측해 지원하지 않습니다.
- [Kotlin Serialization 문서](https://kotlinlang.org/docs/serialization.html): 직접 JSON parser를 구현하지 않고 JSON tree API를 사용합니다.
