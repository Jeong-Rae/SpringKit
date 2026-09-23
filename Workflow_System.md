# Workflow System

Workflow System은 외부 Task를 실제 코드 변경인 SubTask로 전환하고, SubTask가 구현에서 production까지 일관된 경로로 진행되도록 제어합니다.

개발자는 GitHub, Git, CI, Merge Queue와 배포 도구의 세부 절차를 직접 조합하지 않습니다. Workflow가 현재 SubTask의 상태와 의존 관계를 확인하고 가능한 작업을 결정합니다.

Workflow의 기본 흐름은 다음과 같습니다.

**Task → SubTask → Branch/Worktree → Draft PR → CI·AI Review → Human Review → Merge Queue → `main` → Deployment Candidate → Production Canary → Production**

새로운 use case는 production에 코드가 배포된 뒤에도 바로 외부 사용자에게 공개하지 않습니다. Feature Flag를 통해 내부에서 먼저 검수하고, 사람이 외부 공개 시작을 결정합니다.

Workflow의 목적은 절차를 직렬화하는 것이 아닙니다. 독립적인 SubTask는 병렬로 진행하고, 실제 코드 의존성이 있는 SubTask만 Stack으로 연결합니다.

## Workflow

Workflow는 SubTask의 현재 상태를 기준으로 다음 행동을 허용하거나 제한합니다.

사용자가 SubTask를 시작하면 Workflow는 식별자를 발급하고 적절한 기준 브랜치에서 작업 공간을 만듭니다. 사용자가 변경을 게시하면 원격 브랜치와 Draft PR을 만들고 CI와 AI 사전 리뷰를 시작합니다.

사람이 PR을 Ready로 전환하고 코드를 승인하면 Workflow는 선행 SubTask와 CI 상태를 확인합니다. 모든 조건을 충족한 PR은 Merge Queue에 전달합니다.

Merge Queue가 통합 검증을 마치면 PR을 `main`에 squash merge합니다. 이후 Workflow는 후속 Stack을 정리하고, `main`의 검증된 상태를 배포 후보로 진행합니다.

Workflow는 다음 원칙을 유지합니다.

- 통합된 코드의 기준은 원격 `main`입니다.
- Task 전체의 완료를 기다리지 않고 안전한 SubTask부터 통합합니다.
- 코드 의존성이 없는 SubTask는 병렬로 진행합니다.
- 코드 의존성이 있는 SubTask는 미통합 선행 코드 위에서 계속 개발하고 리뷰합니다.
- 사람의 리뷰 대기를 이유로 구현을 불필요하게 멈추지 않습니다.
- Ready와 Approve는 사람이 결정합니다.
- Merge Queue와 CI 검증은 우회하지 않습니다.
- 코드 통합, production 배포, 사용자 공개를 서로 다른 사건으로 관리합니다.
- 사용자의 use case를 변경하는 코드는 Feature Flag로 공개 시점을 분리합니다.

## 구현 아키텍처

Workflow 도구는 Kotlin으로 구현합니다. CLI(Command line interface) 계층은 Clikt를 사용하고, 배포 산출물은 GraalVM Native Image로 생성한 단일 실행 바이너리입니다. 개발과 검증에 JVM을 사용할 수 있지만 사용자가 실행하는 `tools/workflow`는 별도의 JVM 설치를 요구하지 않아야 합니다.

코드는 Domain, Application과 Adapter 경계로 나눕니다.

- Domain: SubTask, dependency, Review revision, diff identity, Gate, Merge Queue, deployment와 release 규칙 및 상태 전이
- Application: 명령별 use case, 트랜잭션 범위, 차단 조건과 다음 행동 계산, Domain과 Port 조합
- Inbound Adapter: Clikt 명령과 인자를 Application 요청으로 변환하고 공통 결과를 출력
- Outbound Adapter: Workflow Store, Task, Git, Review, CI, Merge Queue, Deployment, Release와 Identity 시스템 연결

Domain과 Application은 Clikt, 파일 시스템, 프로세스 실행, 네트워크와 provider SDK에 의존하지 않습니다. 외부 상태는 소유 Adapter의 Port를 통해서만 읽고 변경합니다. CLI 표현과 provider 응답 형식은 Adapter에서 Domain 모델로 변환합니다.

`change_revision`은 사람이 확인하는 코드 변경 단위입니다. 그 안의 `diff.identity`는 로컬 코드의 fingerprint를 나타냅니다. `provider_revision`은 GitHub head SHA처럼 외부 provider가 코드 상태를 식별하는 revision입니다. Adapter는 CI 조회와 코드 위치 Review 요청에 `provider_revision`을 사용하며, 이 값을 `diff.identity`로 대체하지 않습니다. 같은 `provider_revision`을 다시 동기화할 때는 `change_revision`과 기존 승인을 유지합니다.

기반 기능은 직접 다시 구현하지 않고 다음 기준에 따라 외부 라이브러리를 우선 사용합니다.

1. Kotlin API와 컴파일 시점 타입 검증을 자연스럽게 지원합니다.
2. 충분한 사용자와 공개 검증 이력이 있는 안정적인 라이브러리입니다.
3. 필요한 기능만 선택할 수 있고 Native Image와 실행 바이너리에 불필요한 무게를 더하지 않습니다.

JSON은 `kotlinx.serialization`, 파일 시스템 I/O는 Okio, HTTP 연동은 필요한 Ktor Client 모듈을 우선 사용합니다. 라이브러리로 표현할 수 없는 작은 공통 변환과 검증만 `io.springkit.workflow.common` 패키지에 둡니다. 이 패키지는 Domain 정책이나 Adapter 책임을 대신하지 않습니다.

GitHub Copilot Review는 현재 Workflow Adapter 범위에 포함하지 않습니다. AI Review 공급자는 별도 지시로 연결하며, 공급자가 없으면 외부 AI 호출 없이 상태를 `PENDING`으로 유지합니다. 이 상태는 CI와 사람의 Ready·Approve Gate를 대신하지 않으며 Merge Queue 진입을 추가로 막지 않습니다.

Git Adapter는 설치된 `git` CLI를 호출해 Worktree를 생성하고 이동하고 정리합니다. GitHub Adapter는 설치된 `gh` CLI를 호출해 PR, 리뷰와 GitHub 상태를 조회하고 변경합니다. 각 CLI가 인증, 전송과 provider 동작을 담당하므로 같은 기능을 다시 구현하지 않습니다. `io.springkit.workflow.common`에는 작업 디렉터리, 종료 코드, 표준 출력과 표준 오류를 다루는 최소 프로세스 유틸리티만 둡니다. Git과 GitHub 명령 구성 및 Domain 결과 변환은 각 Adapter가 담당합니다.

배포, 외부 공개와 Feature Flag Adapter도 검증된 provider CLI를 토큰 배열로 호출합니다. Workflow는 JSON 요청과 응답을 Domain 모델로 변환하며 provider 인증, 전송과 실제 배포 동작을 재구현하지 않습니다. 명령 구성과 JSON 계약은 [Workflow CLI 명세](workflow-cli.md#외부-provider-cli-구성)을 따릅니다.

외부 이벤트 ingress는 Workflow Application을 호스팅하는 실행 환경이 `WorkflowEventPort`로 주입합니다. Native CLI는 Agent용 공개 명령에 webhook이나 이벤트 daemon을 추가하지 않습니다.

Native Image 빌드는 reflection, resource, dynamic proxy와 동적 class loading 요구를 명시적으로 관리해야 합니다. 런타임에 임의 코드를 불러오는 plugin 방식은 사용하지 않습니다. 필요한 Adapter는 컴파일 시점의 구성 또는 명시적인 런타임 설정으로 선택합니다.

## 작업 흐름

외부 Task에는 하나 이상의 SubTask가 존재합니다.

각 SubTask는 독립적으로 리뷰하고 검증하고 `main`에 통합할 수 있으며, 해당 SubTask까지만 production에 존재해도 안전해야 합니다.

독립적인 SubTask는 최신 원격 `main`에서 각각 시작합니다.

선행 SubTask의 미통합 코드가 필요하면 해당 SubTask를 `requires`로 명시하고 선행 코드 위에서 작업합니다. 선행 SubTask가 아직 리뷰 중이어도 후속 SubTask의 구현과 리뷰를 진행할 수 있습니다.

SubTask는 다음 순서로 진행합니다.

1. 외부 Task와 SubTask의 작업 맥락을 확인합니다.
2. Workflow가 SubTask ID와 Branch, Worktree를 준비합니다.
3. 사람 또는 Agent가 코드와 테스트를 작성합니다.
4. 로컬 검증을 수행합니다.
5. 변경을 원격에 게시하고 Draft PR을 생성합니다.
6. CI와 AI 사전 리뷰를 수행합니다.
7. 작성 담당자가 코드와 PR 설명을 정리합니다.
8. 사람이 PR을 Ready로 전환합니다.
9. 사람이 실제 코드와 검증 결과를 리뷰합니다.
10. 사람이 PR을 Approve합니다.
11. Workflow가 선행 SubTask와 필수 검증 상태를 확인합니다.
12. Merge Queue가 최신 `main`과의 통합을 검증합니다.
13. PR을 `main`에 squash merge합니다.
14. Workflow가 후속 Stack과 Worktree를 정리합니다.
15. 검증된 `main` 상태를 배포 후보로 진행합니다.
16. Production Canary와 배포 검증을 수행합니다.
17. use case 변경은 내부 검수 후 사람이 외부 공개 시작을 결정합니다.
18. 공개가 완료된 임시 Feature Flag와 분기는 Cleanup SubTask로 제거합니다.

하나의 SubTask가 리뷰 중이거나 배포 중이라는 사실만으로 다른 독립 SubTask의 진행을 막지 않습니다.

### 사람

사람은 코드와 제품 동작에 대한 판단을 담당합니다.

작성 담당자는 SubTask의 변경 목적과 범위를 이해하고, PR에 실제 구현과 일치하는 설명을 제공할 책임이 있습니다. Agent가 코드나 PR 본문을 작성했더라도 이 책임은 작성 담당자에게 있습니다.

사람은 다음 결정을 직접 수행합니다.

- PR을 사람의 최종 리뷰 대상으로 전환하는 Ready 결정
- 코드 변경에 대한 Approve
- `high` 위험 변경의 production 배포 승인
- 새로운 use case의 외부 공개 시작

Approve는 특정 commit SHA에 대한 승인이 아니라 리뷰한 코드 변경에 대한 승인입니다. Stack 정리나 rebase 때문에 SHA가 변경돼도 실제 리뷰 대상 코드가 유지되면 기존 승인의 의미를 유지합니다.

실제 코드 변경이 달라지면 사람이 변경된 범위를 다시 확인합니다.

### Agent

Agent는 사람이 판단할 변경을 준비하고 Workflow의 반복 작업을 수행합니다.

Agent는 다음 작업을 수행할 수 있습니다.

- 외부 Task와 기존 코드 분석
- SubTask 분해 제안
- 코드 의존 관계 분석
- 코드와 테스트 구현
- 로컬 검증
- Branch와 Worktree 준비
- Draft PR 게시
- PR 본문 작성과 갱신
- AI 사전 코드 리뷰
- 리뷰 코멘트 작성
- 사람이 요청한 리뷰 의견 반영
- Stack 동기화와 기계적인 restack
- CI·PR·Merge Queue 상태 확인
- 배포 후보와 포함 변경의 설명 준비

Agent는 다음 결정을 대신하지 않습니다.

- Ready 결정
- Approve
- 사람에게 할당된 production 배포 승인
- 외부 공개 시작 결정

Agent가 GitHub PR이나 Issue에 작성하는 코멘트에는 `[Agent]`를 표시하여 작성 주체를 구분합니다.

# Git Branch 및 Worktree

## Naming

SubTask는 중앙 Workflow가 발급하는 다음 형식의 식별자를 사용합니다.

`<project>-<number>`

예:

- `sk-101`
- `sk-102`
- `api-31`

번호는 실행 순서나 의존 관계를 나타내지 않습니다.

Branch 이름은 SubTask ID를 그대로 사용합니다.

예:

- `sk-101`
- `sk-102`
- `api-31`

Branch 이름에 변경 유형, 외부 Task ID, 부모 SubTask, Stack 깊이, 배포 환경이나 위험도를 추가하지 않습니다.

SubTask ID가 Branch와 PR을 연결하는 기본 식별자가 됩니다. 변경의 성격과 작업 맥락은 PR과 외부 Task에서 설명합니다.

## Worktree

하나의 활성 SubTask는 하나의 독립된 Worktree에서 작업할 수 있습니다.

여러 SubTask를 동시에 진행할 때 같은 Working Tree에서 branch checkout을 반복하지 않습니다. 각 SubTask의 작업 공간을 분리하여 한 작업의 미커밋 변경이 다른 작업에 영향을 주지 않도록 합니다.

Worktree는 SubTask Branch와 같은 lifecycle을 가집니다.

SubTask가 `main`에 통합되고 후속 Stack이 새 기준으로 정리되면 더 이상 필요하지 않은 Worktree를 제거합니다.

Worktree 정리는 다음 SubTask를 시작하기 위한 선행 조건이 아닙니다.

## Stacking

Stack은 실제 코드 의존성이 있는 미통합 SubTask를 계속 개발하고 리뷰하기 위해 사용합니다.

`sk-102`가 아직 통합되지 않은 `sk-101`의 코드를 필요로 하면 다음 관계를 기록합니다.

`sk-102 requires sk-101`

Branch 관계도 같은 선행 조건을 반영합니다.

`main < sk-101 < sk-102`

`sk-102`가 `sk-101`에 의존한다고 해서 `sk-101`의 리뷰나 merge가 끝날 때까지 개발을 기다리지 않습니다.

하나의 선행 SubTask에 여러 SubTask가 독립적으로 의존할 수 있습니다.

예:

- `sk-102 requires sk-101`
- `sk-103 requires sk-101`

이 경우 `sk-102`와 `sk-103`은 서로 의존하지 않습니다.

따라서 구조는 다음 의미를 가집니다.

`main < sk-101 < sk-102`

`main < sk-101 < sk-103`

`sk-101`이 merge되면 Workflow는 두 SubTask를 새 `main` 기준으로 각각 정리합니다. 이후 두 PR은 서로 독립적으로 Merge Queue에 들어갈 수 있습니다.

반대로 다음 구조에서는 실제 연속 의존성이 존재합니다.

`main < sk-101 < sk-102 < sk-103`

이 경우 `sk-103`은 `sk-102`의 코드가 필요합니다.

Stack은 작업 순서나 같은 Task에 속한다는 이유로 만들지 않습니다. 실제 미통합 코드가 필요할 때만 만듭니다.

부모 SubTask가 squash merge되거나 수정되면 Workflow가 자식 SubTask를 restack합니다.

기계적인 restack으로 실제 코드 diff가 유지되면 기존 코드 승인을 유지할 수 있습니다. 충돌 해결이나 추가 수정으로 실제 변경이 달라지면 해당 변경은 다시 리뷰합니다.

# Git Review 및 PR

## PR 제목

하나의 PR은 하나의 SubTask를 나타냅니다.

PR 제목에는 SubTask ID와 현재 변경을 설명하는 제목만 사용합니다.

형식:

`[<subtask-id>] <description>`

예:

`[sk-101] 추천 방식 선택 경계를 추가한다`

PR 제목에는 `Feature`, `Fix`, `Refactor` 같은 변경 유형을 별도로 넣지 않습니다.

변경 유형만으로 PR을 분류하지 않습니다. 리뷰어가 필요한 판단은 PR 본문의 문제, 필요성, 해결 방법과 트레이드오프에서 확인합니다.

## PR Template

PR은 체크리스트가 아니라 **의사결정을 전달하는 문서**로 작성합니다.

카카오페이손해보험 사례와 같이 작성자가 코드를 설명하기 전에 먼저 문제와 필요성을 설명하고, 리뷰어가 구현 선택의 이유와 위험을 이해할 수 있도록 구성합니다. 특히 구현이 쉬워졌다는 사실과 지금 그 변경을 해야 한다는 판단을 구분하기 위해 변경의 필요성을 명시합니다.

PR 본문은 다음 구조를 사용합니다.

### 해결하려는 문제

현재 SubTask가 해결하려는 문제를 설명합니다.

구현 결과부터 설명하지 않고 현재 코드나 제품에서 어떤 문제가 존재하는지 먼저 설명합니다.

### 왜 지금 해결해야 하는가

현재 시점에 이 변경이 필요한 이유를 설명합니다.

단순히 구현할 수 있다는 사실은 변경 이유가 되지 않습니다. 현재 변경을 수행하지 않았을 때의 문제와 Task의 목적을 기준으로 필요성을 설명합니다.

이 항목은 리뷰어뿐 아니라 작성자가 현재 변경 자체의 필요성을 다시 판단하기 위한 기준으로 사용합니다.

### 어떻게 해결했는가

선택한 구현 방식과 현재 SubTask의 변경 범위를 설명합니다.

구체적인 코드 목록을 반복하기보다 어떤 구조와 원칙으로 문제를 해결했는지를 설명합니다.

### 한계와 트레이드오프

현재 구현이 가지는 한계와 선택 과정에서 감수한 비용을 설명합니다.

성능, 복잡도, 확장성, 운영 비용이나 다른 설계 제약처럼 사람이 판단해야 하는 부분을 명확하게 남깁니다.

특별한 트레이드오프가 없다면 억지로 내용을 만들지 않습니다.

### 기존 기능에 미치는 영향

현재 변경이 기존 동작과 호출 관계에 어떤 영향을 주는지 설명합니다.

Feature Flag 뒤에서 기존 동작을 유지한다면 그 관계도 설명합니다.

### Edge Case와 실패 시나리오

정상 경로가 아닌 주요 조건과 실패 가능성을 설명합니다.

예외 처리, 부분 실패, 재시도, 데이터 불일치와 같이 리뷰 과정에서 확인해야 할 경계를 작성합니다.

### 검토한 대안과 선택 이유

실제로 고려했던 의미 있는 대안을 설명합니다.

각 대안을 단순히 나열하지 않고 현재 방식을 선택한 이유를 설명합니다.

대안을 검토하지 않은 단순한 변경에서는 존재하지 않는 대안을 만들어 작성하지 않습니다.

### 리뷰 포인트

사람이 집중해서 확인해야 할 코드와 판단 지점을 설명합니다.

파일이나 영역에 따라 검토 중요도가 다르면 이를 표시합니다. 리뷰어가 전체 diff를 같은 비중으로 읽는 대신 실제 판단이 필요한 곳에 시간을 사용할 수 있게 합니다.

PR Template은 코드가 바뀔 때마다 현재 구현과 일치하도록 유지합니다.

Agent가 초안을 작성할 수 있지만, PR을 Ready로 전환하는 사람은 본문의 설명이 실제 코드와 일치하는지 확인해야 합니다.

Task ID, SubTask ID, 선행 SubTask와 Workflow 상태처럼 시스템이 알고 있는 정보는 Workflow가 관리합니다. 작성자가 PR 본문에서 같은 metadata를 반복해서 유지하지 않도록 합니다.

## PR 게시

PR은 처음 원격에 게시할 때 Draft 상태로 생성합니다.

Draft 상태에서 다음 작업을 진행할 수 있습니다.

- CI 검증
- AI 사전 리뷰
- PR 본문 작성과 갱신
- 조기 설계 의견 수렴
- 리뷰 코멘트 반영

Draft는 단순히 미완성 코드를 보관하는 상태가 아닙니다. 사람이 최종적으로 판단하기 전에 코드와 판단 근거를 정리하는 단계입니다.

사람이 변경 내용과 PR 설명을 확인하고 정식 리뷰가 가능하다고 판단하면 Ready로 전환합니다.

## 코멘트

AI와 사람의 리뷰 코멘트는 의견의 중요도를 구분합니다.

태그는 대문자로 표기합니다. 각 단계는 반드시 반영해야 하는 의견, 가급적 반영해야 하는 의견, 사소한 의견으로 구분합니다.

### `[R]` 꼭 반영해야 하는 의견

현재 PR을 승인하기 전에 해결해야 하는 문제입니다.

정확성, 장애 가능성, 데이터 손상, 보안, 계약 위반이나 명백한 요구사항 누락처럼 현재 변경을 그대로 수용하기 어려운 경우에 사용합니다.

작성자는 코드를 수정하거나, 리뷰어와 논의하여 해당 문제가 존재하지 않거나 이미 해결되었다는 점을 확인해야 합니다.

`[R]`이 해결되지 않은 상태에서는 PR을 승인 대상으로 진행하지 않습니다.

### `[C]` 웬만하면 반영해야 하는 의견

현재 PR에서 반영하는 편이 적절한 개선 의견입니다.

`[R]`처럼 반드시 병합을 차단하는 문제는 아니지만, 현재 변경의 품질과 유지보수성을 위해 충분히 고려할 가치가 있는 사항에 사용합니다.

작성자는 의견을 검토하고 반영하거나, 반영하지 않는다면 그 이유를 설명할 수 있습니다.

### `[A]` 사소한 의견

현재 PR의 승인 여부와 직접 연결하지 않는 가벼운 의견입니다.

표현, 가독성이나 작은 구조 개선처럼 현재 변경에서 선택적으로 반영할 수 있는 사항에 사용합니다.

`[A]`를 모두 반영하기 위해 PR의 범위를 확장하지 않습니다.

Agent가 작성하는 코멘트는 작성 주체와 중요도를 함께 표시합니다.

예:

- `[Agent] [R]`
- `[Agent] [C]`
- `[Agent] [A]`

코멘트에는 단순한 수정 명령보다 다음 내용을 포함합니다.

- 문제가 발생하는 조건
- 예상되는 영향
- 판단 근거
- 확인하거나 수정할 부분

AI 리뷰는 사람의 승인이나 CI의 실행 결과를 대신하지 않습니다.

AI가 작성한 코멘트도 현재 SubTask의 변경 범위를 기준으로 판단합니다. 별도의 변경 의도에 해당하는 개선은 현재 PR을 계속 확장하는 대신 후속 SubTask 후보로 분리합니다.

## PR Status

Workflow는 PR의 상태를 단순한 GitHub open/closed 상태보다 작업 흐름에 맞게 해석합니다.

**Draft**에서는 구현, CI, AI 사전 리뷰와 조기 의견 수렴을 진행합니다.

**Ready**는 사람이 현재 변경을 정식 리뷰할 수 있다고 판단한 상태입니다.

**Review**에서는 사람이 실제 코드와 PR에 기록된 판단 근거를 확인합니다.

**Approved**는 필요한 사람의 코드 승인을 받은 상태입니다.

**Blocked**는 선행 SubTask, 필수 검증 또는 해결되지 않은 `[R]` 리뷰 의견 때문에 Merge Queue에 들어갈 수 없는 상태입니다.

**Queued**는 모든 통합 조건을 만족해 Merge Queue가 처리하고 있는 상태입니다.

**Merged**는 SubTask가 원격 `main`에 squash merge된 상태입니다.

Workflow는 현재 상태뿐 아니라 다음 단계로 진행하지 못하는 이유를 함께 표시해야 합니다.

# Git Commit, Merge와 Refresh

## Commit

SubTask Branch에는 구현 과정에서 여러 commit을 만들 수 있습니다.

로컬 commit은 개발자가 작업을 구성하고 검증하기 위한 기록입니다. 최종 `main` history의 단위와 같을 필요는 없습니다.

PR을 최종 merge할 때는 SubTask 전체를 하나의 squash commit으로 만듭니다.

`main`의 squash commit은 PR 제목을 기준으로 SubTask ID와 변경 의도를 보존합니다.

예:

`[sk-101] 추천 방식 선택 경계를 추가한다`

따라서 `main`에서는 하나의 SubTask가 하나의 변경 기록으로 남습니다.

## Local과 Remote, Merge

원격 `main`을 통합된 코드의 신뢰 기준으로 사용합니다.

Workflow가 새로운 독립 SubTask를 시작하거나 현재 작업을 동기화할 때 먼저 원격 상태를 갱신합니다.

로컬 `main`이 오래되었다는 이유로 로컬 상태를 기준으로 새 작업을 만들지 않습니다.

SubTask를 최종적으로 `main`에 병합하는 작업은 원격 Merge Queue에서만 수행합니다.

로컬에서 SubTask Branch를 `main`에 merge하거나 별도의 finish 작업으로 통합하지 않습니다.

PR이 승인됐더라도 개발자가 임의의 시점에 직접 merge하지 않습니다.

Merge Queue가 최신 `main`과 앞선 queue 변경을 포함한 통합 후보를 검증하고 성공한 변경을 squash merge합니다.

GitHub Merge Queue와 self-hosted bors는 이 역할의 구현체가 될 수 있습니다.

Workflow의 정책은 특정 Queue 제품에 의존하지 않습니다.

## Local과 Remote Cleanup

SubTask가 merge되면 Workflow는 다음 순서로 작업 상태를 정리합니다.

먼저 해당 SubTask를 부모로 사용하는 활성 Stack을 새로운 기준으로 restack합니다.

이후 더 이상 참조되지 않는 원격 SubTask Branch를 삭제할 수 있습니다.

로컬 Worktree와 로컬 Branch도 더 이상 필요하지 않을 때 제거합니다.

원격 `main`은 최신 상태로 다시 가져옵니다.

Cleanup 실패는 이미 성공한 원격 merge를 취소하지 않습니다. Workflow는 통합 완료와 로컬 정리를 별개의 상태로 취급합니다.

Cleanup이 끝나지 않았다는 이유로 다른 독립 SubTask의 시작을 막지 않습니다.

# CI Validation과 Deploy

CI는 한 번의 검사로 모든 상태를 보장하지 않습니다. SubTask가 진행하는 위치에 따라 다른 코드 조합을 검증합니다.

## PR Validation

원격에 게시된 SubTask Branch를 검증합니다.

Stacked SubTask라면 선행 코드까지 포함한 실제 Branch 상태를 검증합니다.

검증에는 프로젝트가 정의한 빌드, 테스트와 정적 검사가 포함됩니다.

PR CI가 성공했다는 사실은 해당 SubTask Branch가 검증됐다는 의미입니다. 최신 `main`과의 최종 통합을 보장하지는 않습니다.

## Merge Validation

Merge Queue는 실제로 `main`에 들어갈 후보 상태를 다시 검증합니다.

PR을 승인한 이후 다른 변경이 `main`에 들어왔더라도 Queue의 통합 검증을 통과해야 합니다.

Merge Queue 검증에 성공한 후보만 `main`에 반영합니다.

## Deployment Candidate

`main`에 SubTask가 merge될 때마다 개발자의 작업을 다시 기다리지 않고 다음 SubTask를 계속 통합할 수 있습니다.

배포는 각 PR merge와 반드시 일대일로 실행하지 않습니다.

Workflow는 검증된 `main`의 특정 상태를 고정된 배포 후보로 선택합니다.

배포 후보에는 현재 production 버전 이후 해당 `main` revision까지 통합된 SubTask가 모두 포함됩니다.

이미 시작한 배포의 대상은 그 뒤에 다른 PR이 merge되더라도 변경하지 않습니다.

이 구조를 통해 코드 통합 속도와 실제 배포 실행 속도를 분리합니다.

## Develop과 Production

`develop`을 별도의 장기 Git Branch로 사용하지 않습니다.

개발과 검수 환경은 Git Branch가 아니라 배포 후보가 진행하는 환경입니다.

모든 코드 통합은 `main`을 기준으로 합니다.

동일한 배포 후보가 필요한 검증 단계를 통과하면서 production으로 전진합니다.

개념적인 배포 흐름은 다음과 같습니다.

**`main` → 배포 후보 → 검수 → Production Canary → Production**

Production 배포는 Canary를 기본으로 사용합니다.

배포 후보에 `high` 위험 SubTask가 포함되어 있으면 Production Canary를 시작하기 전에 사람이 배포를 승인합니다.

`normal` 변경으로만 구성된 후보는 필수 검증을 통과하면 자동으로 진행할 수 있습니다.

새로운 use case를 포함한 코드는 production에 존재하더라도 Feature Flag의 안전한 기본 동작을 유지합니다.

해당 기능은 production 배포 이후 내부에서 먼저 검수합니다.

사람이 외부 공개 시작을 승인하면 공개 범위를 자동으로 점진 확대합니다.

배포 완료는 배포 요청을 시작했다는 의미가 아닙니다. 선택한 후보가 대상 환경에 적용되고 필요한 검증을 완료한 상태를 의미합니다.

# Task Graph 및 Task 관리

Task는 ClickUp과 같은 외부 일정 관리 시스템에서 관리하는 업무 단위입니다.

SubTask는 실제 Git 변경과 PR을 나타내는 코드 작업 단위입니다.

하나의 Task에는 하나 이상의 SubTask가 존재합니다.

외부 일정 시스템의 하위 작업과 Workflow의 SubTask를 서로 연결합니다.

예:

**Task: 추천 알고리즘 실험**

- 외부 SubTask ↔ `sk-101` ↔ PR
- 외부 SubTask ↔ `sk-102` ↔ PR
- 외부 SubTask ↔ `sk-103` ↔ PR

Task는 목적, 일정, 담당자와 업무 완료 조건을 관리합니다.

SubTask는 다음 내용을 관리합니다.

- SubTask ID
- 소속 Task
- Git Branch
- Worktree
- GitHub PR
- 코드 의존 관계
- CI 상태
- Review 상태
- Merge 상태

Task Graph에서 SubTask의 기본 관계는 병렬입니다.

같은 Task에 속한다는 사실, SubTask 번호, 생성 순서와 외부 도구에 표시되는 순서는 코드 의존성을 의미하지 않습니다.

미통합 코드가 실제로 필요할 때만 `requires` 관계를 만듭니다.

Task Graph의 의존 관계는 Git Stack과 PR base를 구성하는 근거로 사용합니다.

외부 일정 관리 도구의 상태와 Git의 코드 상태는 서로 다른 사실을 나타냅니다.

외부 도구에서 SubTask를 완료했다고 표시했다는 이유로 GitHub PR을 merge된 것으로 취급하지 않습니다.

GitHub PR이 `main`에 실제로 merge되면 Workflow가 해당 결과를 외부 SubTask에 반영합니다.

모든 SubTask가 merge됐다는 사실만으로 상위 Task를 자동 완료하지 않습니다. Task는 외부 시스템에 정의된 업무 결과와 완료 조건을 기준으로 판단합니다.

새로운 사실을 발견하면 Task에 SubTask를 추가할 수 있습니다.

기존 SubTask가 하나의 리뷰 판단으로 다루기 어려워지면 여러 SubTask로 분리할 수 있습니다.

배포나 검수에서 추가 수정이 필요해지면 완료된 SubTask를 다시 열기보다 새로운 SubTask를 생성합니다.

실험이나 기능 공개가 끝난 뒤 임시 Feature Flag와 불필요한 코드 제거가 필요하면 Cleanup도 새로운 SubTask로 수행합니다.

Workflow System은 이 Task Graph를 Git 작업과 연결하여 다음 상태를 하나의 맥락에서 보여 줘야 합니다.

- 어떤 SubTask가 구현 중인지
- 어떤 SubTask가 리뷰 중인지
- 어떤 SubTask가 다른 코드에 의존하는지
- 어떤 SubTask가 Merge Queue를 기다리는지
- 어떤 SubTask가 `main`에 통합됐는지
- 현재 배포 후보에 어떤 SubTask가 포함됐는지

이를 통해 개발자는 외부 일정, Git Branch, PR, CI와 배포 시스템을 각각 해석하지 않고 현재 작업이 어디까지 진행됐으며 다음에 어떤 행동이 가능한지 확인할 수 있습니다.
