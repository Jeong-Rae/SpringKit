# Workflow CLI 명세

Workflow CLI(Command line interface)는 외부 Task를 코드 변경 단위인 SubTask로 전환하고, 각 SubTask가 구현부터 production까지 일관된 경로로 진행되도록 제어하는 단일 인터페이스입니다.

이 문서는 [Workflow System](Workflow_System.md)의 정책을 Agent가 실행할 수 있는 명령 계약으로 구체화합니다. 두 문서가 충돌하면 Workflow System을 기준으로 합니다.

## 기본 원칙

- 통합된 코드의 기준: 원격 `main`
- 작업 단위: 독립적으로 검증하고 배포할 수 있는 SubTask
- 작업 공간: 활성 SubTask마다 분리된 Worktree
- 코드 의존성: 미통합 선행 코드가 실제로 필요할 때만 Stack으로 연결
- 원격 게시: Draft PR로 시작
- 코드 통합: Merge Queue 검증을 통과한 뒤 squash merge
- 배포: 고정된 `main` revision을 배포 후보로 선택
- 공개: 새로운 use case는 Feature Flag로 production 배포와 외부 공개를 분리
- 사람의 결정: Ready, Approve, `high` 위험 배포 승인, 외부 공개 시작

SubTask는 독립적으로 리뷰하고 검증하고 `main`에 통합할 수 있어야 합니다. 해당 SubTask까지만 production에 존재해도 안전해야 합니다. Task 전체의 완료 여부는 개별 SubTask의 통합 조건이 아닙니다.

## Agent 계약

Agent는 Workflow CLI가 공개한 다음 정보만 사용합니다.

- 명령과 하위 명령
- 설명과 인자
- 예시와 구조화된 결과
- 차단 조건과 다음에 가능한 행동

Agent는 다음 구현을 직접 조작하지 않습니다.

- Git과 GitHub API
- Branch 생성과 삭제
- Git Worktree 생성과 제거
- 파일 staging
- commit과 push
- rebase와 restack
- force push 정책
- PR base 변경
- Merge Queue 등록과 squash merge
- 외부 Task API
- CI provider와 배포 provider

Workflow CLI가 Git과 외부 시스템을 조작하는 신뢰 가능한 기계적 경계입니다. Agent는 `git`, `gh` 또는 provider별 명령으로 Workflow 상태를 우회하지 않습니다.

## 실행 형식

실행 파일은 `tools` 디렉터리에 둡니다. 관련 테스트는 `tools/test` 디렉터리에 둡니다.

```bash
./tools/workflow <command> [subcommand] [arguments]
```

`tools/workflow`는 Kotlin과 Clikt로 구현한 CLI를 GraalVM Native Image로 빌드한 실행 바이너리입니다. 실행 환경에 별도의 JVM이나 Node.js가 없어도 동작해야 합니다. 빌드와 테스트는 Gradle task로 실행합니다.

CLI는 Inbound Adapter입니다. 인자 해석과 출력만 담당하며 Workflow 정책이나 provider 호출을 직접 구현하지 않습니다. Application이 명령별 use case와 트랜잭션을 조정하고 Domain이 상태와 전이 규칙을 판단합니다. Workflow Store, Task, Git, Review, CI, Merge Queue, Deployment, Release와 Identity 연동은 각 Outbound Adapter가 담당합니다.

Native Image 구성은 빌드에 포함합니다. reflection, resource, dynamic proxy 또는 serialization metadata가 필요하면 선언된 설정과 테스트로 고정합니다. 런타임 module 경로나 script를 통해 Adapter 구현을 동적으로 불러오지 않습니다.

JSON parser, renderer, 파일 시스템과 범용 네트워크 기능을 직접 구현하지 않습니다. Kotlin 친화성, 공개 검증 이력과 가벼운 의존성 순서로 라이브러리를 선택합니다. JSON과 상태 직렬화에는 reflection이 필요 없는 `kotlinx.serialization`을 사용하고, 파일 I/O는 Okio, HTTP는 필요한 Ktor Client 모듈을 우선합니다. 라이브러리로 해결되지 않는 얇은 공통 유틸리티만 `io.springkit.workflow.comoon` 패키지에 둡니다.

| 명령 | 책임 |
| --- | --- |
| `start` | SubTask와 전용 Worktree 시작 |
| `check` | 현재 Worktree의 변경 검증 |
| `review` | 원격 게시와 PR 리뷰 lifecycle 관리 |
| `stack` | SubTask의 직접 코드 의존성 관리 |
| `sync` | Worktree, Branch, Stack과 원격 상태 동기화 |
| `status` | Workflow 상태, 차단 조건과 다음 행동 조회 |
| `gate` | 사람만 수행할 수 있는 의사결정 기록 |

## 구현 검증

구현은 다음 계층을 따로 검증합니다.

- Domain 단위 테스트: 외부 시스템 없이 상태 전이, 차단 조건, revision과 diff identity 규칙 검증
- Application 단위 테스트: 가짜 Port를 사용한 명령 use case와 실패 시 원자성 검증
- Adapter 계약 테스트: Store와 각 외부 시스템의 요청 및 응답 변환 검증
- 라이브러리 경계 테스트: JSON, 파일과 네트워크의 표준 동작을 재구현하지 않고 선택한 라이브러리 설정과 Domain 변환만 검증
- CLI 통합 테스트: Clikt 인자, 종료 코드, 표준 출력과 표준 오류 계약 검증
- Native Image smoke test: 생성한 `tools/workflow` 바이너리의 시작, JSON 출력과 대표 실패 경로 검증

## 식별자와 Branch

Workflow는 다음 형식으로 SubTask ID를 발급합니다.

```text
<project>-<number>
```

예:

- `sk-101`
- `sk-102`
- `api-31`

번호는 작업 순서나 의존 관계를 나타내지 않습니다. Branch 이름은 SubTask ID와 같습니다.

```text
SubTask sk-102
Branch  sk-102
```

Branch 이름에 변경 유형, 외부 Task ID, 부모 SubTask, Stack 깊이, 배포 환경 또는 위험도를 추가하지 않습니다.

## Worktree 계약

Workflow CLI로 시작한 모든 활성 SubTask는 독립적인 관리 Worktree를 가집니다.

```text
Repository
├── main checkout
└── managed worktrees
    ├── sk-101 → sk-101
    ├── sk-102 → sk-102
    └── sk-103 → sk-103
```

하나의 SubTask는 정확히 하나의 Worktree, 하나의 Branch, 하나의 PR과 연결됩니다.

```text
1 SubTask = 1 Worktree = 1 Branch = 1 PR
```

Agent는 기본 checkout이나 다른 SubTask의 Worktree에서 현재 SubTask의 파일을 수정하지 않습니다. `start` 결과의 `workspace.path`에서만 파일을 읽고 수정합니다.

Workflow는 다음 조건을 보장합니다.

- 서로 다른 SubTask가 같은 Worktree나 Branch를 공유하지 않습니다.
- Stack의 부모와 자식도 각각 별도의 Worktree를 사용합니다.
- 한 Worktree의 `sync`가 다른 Worktree의 작업 파일을 변경하지 않습니다.
- Agent 작업 때문에 기본 `main` checkout을 변경하지 않습니다.
- 원격 변경을 이유로 다른 SubTask의 로컬 변경을 덮어쓰지 않습니다.

현재 SubTask를 대상으로 하는 `check`, 모든 `review` 하위 명령, `stack`, `sync`는 관리 Worktree에서 실행해야 합니다. `status`는 Worktree 밖에서도 실행할 수 있습니다.

잘못된 위치에서 실행하면 상태를 변경하지 않습니다.

```json
{
  "type": "failure",
  "data": {
    "code": "WORKTREE_REQUIRED",
    "message": "이 작업은 관리되는 SubTask Worktree에서 실행해야 합니다.",
    "next": [
      {
        "actor": "agent",
        "action": "locate_workspace",
        "command": "./tools/workflow status --subtask sk-102 --json"
      }
    ]
  }
}
```

## 공통 결과 계약

`--json`을 지원하는 모든 명령은 같은 최상위 형식을 사용합니다.

성공:

```json
{
  "type": "success",
  "data": {}
}
```

실패:

```json
{
  "type": "failure",
  "data": {
    "code": "STATE_CONFLICT",
    "message": "현재 상태에서는 요청한 작업을 수행할 수 없습니다.",
    "blocked_by": [],
    "next": []
  }
}
```

`type`은 `success` 또는 `failure`만 가집니다. 명령별 결과는 모두 `data`에 위치합니다.

`type: failure`는 명령 자체가 요청한 동작을 수행하지 못했다는 뜻입니다. 실패한 CI, Merge Queue 또는 배포 상태를 정상적으로 조회한 `status`는 `type: success`를 반환하고 해당 하위 상태를 `FAILED`로 표시합니다.

`blocked_by`는 다음 형식의 객체 배열입니다.

```json
{
  "code": "HUMAN_APPROVAL_REQUIRED",
  "message": "사람의 코드 승인이 필요합니다.",
  "target": "sk-102"
}
```

`next`는 다음 형식의 객체 배열입니다.

```json
{
  "actor": "human",
  "action": "approve_change",
  "command": "./tools/workflow gate approve sk-102 --change-revision cr-4"
}
```

`actor`는 `agent`, `human`, `workflow` 중 하나입니다. `workflow`가 자동으로 수행할 행동에는 `command`를 넣지 않고 `action`만 반환합니다.

`--json`을 사용하면 표준 출력에는 JSON 객체 하나만 기록합니다. 사람이 읽는 진행 메시지는 표준 오류에 기록합니다. 성공은 종료 코드 `0`, 실패는 `0`이 아닌 종료 코드를 반환합니다.

상태를 변경하는 명령은 성공 시 전체 변경을 반영하고, 실패 시 문서에 별도로 명시한 복구 상태를 제외하면 실행 전 상태를 유지합니다.

## 상태와 revision

SubTask의 코드 진행 상태는 다음 값을 사용합니다.

| 상태 | 의미 |
| --- | --- |
| `DEVELOPMENT` | 원격 Review를 열기 전 구현 단계 |
| `DRAFT` | Draft PR에서 구현, CI와 AI 사전 리뷰 진행 |
| `READY` | 사람이 정식 리뷰를 시작할 수 있다고 판단한 상태 |
| `REVIEW` | 사람이 코드와 판단 근거를 검토하는 상태 |
| `APPROVED` | 필요한 사람의 코드 승인을 받은 상태 |
| `BLOCKED` | 선행 SubTask, 필수 검증 또는 `[R]` thread 때문에 Queue 진입 불가 |
| `QUEUED` | Merge Queue에서 통합 후보 검증 중 |
| `MERGED` | 원격 `main`에 squash merge 완료 |

배포와 외부 공개는 SubTask 코드 상태와 별도로 관리합니다. `status`는 `review`, `integration`, `deployment`, `release` 객체를 분리해서 반환합니다.

Review는 다음 두 revision을 구분합니다.

- `review_revision`: PR 본문, thread와 원격 갱신을 포함한 리뷰 동시성 토큰
- `change_revision`: 사람이 확인하는 실제 코드 변경 단위

PR 본문이나 thread만 바뀌면 `review_revision`만 변경할 수 있습니다. 실제 diff가 바뀌면 두 revision을 모두 변경하고 기존 코드 승인을 무효화합니다. 기계적인 restack 뒤 diff가 같으면 `change_revision`과 승인을 유지할 수 있습니다.

## `workflow start`

새 SubTask를 발급하고 전용 Worktree를 시작합니다.

독립적인 SubTask:

```bash
./tools/workflow start \
  --task TASK-42 \
  --request-id recommendation-boundary \
  --title "추천 정책 선택 경계를 추가한다"
```

미통합 선행 SubTask의 코드가 필요한 경우:

```bash
./tools/workflow start \
  --task TASK-42 \
  --request-id recommendation-policy \
  --title "신규 추천 정책을 구현한다" \
  --requires sk-101
```

| 인자 | 필수 | 설명 |
| --- | --- | --- |
| `--task <id>` | O | 외부 Task ID |
| `--request-id <id>` | O | 같은 시작 요청의 재시도를 식별하는 안정적인 키 |
| `--title <text>` | O | SubTask의 변경 의도 |
| `--requires <subtask-id>` | X | 직접 필요한 미통합 선행 SubTask |
| `--json` | X | JSON 결과 반환 |

`start`는 다음 작업을 하나의 동작으로 수행합니다.

- 외부 Task 검증
- SubTask ID 발급과 외부 Task 연결
- SubTask metadata 생성
- 최신 원격 `main`과 기준 코드 상태 확인
- Branch와 전용 Worktree 생성
- 코드 의존 관계 설정
- 작업 경로 반환

독립적인 SubTask는 최신 원격 `main`에서 시작합니다. 미통합 선행 SubTask가 있으면 해당 SubTask의 코드를 기준으로 Branch를 구성하되 별도의 Worktree를 만듭니다.

같은 외부 Task와 `request-id`로 다시 호출하면 중복 SubTask나 Worktree를 만들지 않고 기존 관리 상태를 반환합니다. 같은 키에 다른 제목이나 의존 관계를 사용하면 `IDEMPOTENCY_CONFLICT`를 반환합니다.

```json
{
  "type": "success",
  "data": {
    "task": "TASK-42",
    "subtask": "sk-102",
    "branch": "sk-102",
    "workspace": {
      "path": "/repo-managed/sk-102"
    },
    "state": "DEVELOPMENT",
    "next": [
      {
        "actor": "agent",
        "action": "implement_change",
        "command": null
      }
    ]
  }
}
```

Agent는 이후 작업을 `workspace.path`에서 수행합니다. 실제 Worktree 저장 위치는 CLI 내부 구현이며 Agent 계약에 포함하지 않습니다.

## `workflow check`

현재 Worktree의 변경을 저장소 정책에 따라 검증합니다.

```bash
./tools/workflow check
```

| 인자 | 필수 | 설명 |
| --- | --- | --- |
| `--json` | X | JSON 결과 반환 |

`check`는 다음 항목을 확인합니다.

- 관리 Worktree와 현재 SubTask
- 변경된 코드
- 저장소가 요구하는 빌드, 테스트와 정적 검사
- 미통합 선행 코드 상태
- 원격 게시 가능 여부

`check`는 파일과 Git 상태를 변경하지 않는 검사만 실행합니다. 포맷 적용처럼 파일을 변경하는 작업은 저장소가 별도 명령으로 제공해야 합니다.

성공한 결과는 현재 Worktree 콘텐츠의 fingerprint와 연결됩니다. 파일 변경, `sync` 또는 충돌 해결로 fingerprint가 달라지면 기존 결과를 무효화하고 `check`를 다시 실행해야 합니다.

## `workflow review`

`review`는 SubTask의 원격 게시와 PR 리뷰 lifecycle을 담당합니다.

```text
review open
review show
review update
review comment
review reply
review resolve
```

모든 `review` 하위 명령은 현재 관리 Worktree의 SubTask를 대상으로 합니다.

### `workflow review open`

현재 Worktree의 변경을 처음 게시하고 Draft PR을 생성합니다.

기존 use case를 바꾸지 않는 변경:

```bash
./tools/workflow review open \
  --body-file pr.md \
  --risk normal \
  --exposure unchanged
```

새 use case를 Feature Flag 뒤에 게시하는 변경:

```bash
./tools/workflow review open \
  --body-file pr.md \
  --risk high \
  --exposure feature-flag \
  --feature-flag recommendation-v2
```

| 인자 | 필수 | 설명 |
| --- | --- | --- |
| `--body-file <path>` | O | PR 본문, 현재 Worktree 기준 경로 |
| `--risk <normal\|high>` | O | 변경 위험도 |
| `--exposure <unchanged\|feature-flag>` | O | 외부 use case 변경 여부와 공개 방식 |
| `--feature-flag <id>` | 조건부 | `--exposure feature-flag`일 때 사용할 Feature Flag |
| `--json` | X | JSON 결과 반환 |

`review open`은 다음 작업을 수행합니다.

- 현재 Worktree와 SubTask 확인
- 현재 fingerprint에 연결된 최신 `check` 결과 확인
- Feature Flag의 안전한 기본 동작 확인
- 변경을 게시 가능한 Git 상태로 기록
- 원격 Branch 게시와 Draft PR 생성
- `[<subtask-id>] <description>` 형식의 PR 제목 생성
- PR 본문, 위험도와 공개 metadata 적용
- 코드 의존 관계에 따른 PR base 설정
- PR CI와 AI 사전 리뷰 시작
- 첫 `review_revision`과 `change_revision` 생성

Agent는 staging, commit 또는 push를 직접 수행하지 않습니다.

독립 SubTask의 PR base는 `main`입니다. Stacked SubTask의 PR base는 직접 필요한 선행 SubTask입니다. 선행 SubTask가 `main`에 통합되면 Workflow가 후속 PR을 `main` 기준으로 restack하고 PR base를 갱신합니다.

PR 본문은 Workflow System의 PR Template을 따릅니다.

- 해결하려는 문제
- 왜 지금 해결해야 하는가
- 어떻게 해결했는가
- 한계와 트레이드오프
- 기존 기능에 미치는 영향
- Edge Case와 실패 시나리오
- 검토한 대안과 선택 이유
- 리뷰 포인트

존재하지 않는 트레이드오프나 대안을 만들지 않습니다. Task ID, SubTask ID, 의존 관계와 Workflow 상태처럼 시스템이 관리하는 metadata는 본문에 반복하지 않습니다.

### `workflow review show`

현재 Review 상태를 조회합니다.

```bash
./tools/workflow review show
./tools/workflow review show --diff --threads open
```

| 인자 | 필수 | 설명 |
| --- | --- | --- |
| `--diff` | X | Review 대상 변경 포함 |
| `--threads <open\|all>` | X | 반환할 thread 범위 |
| `--json` | X | JSON 결과 반환 |

`review show`는 다음 정보를 반환합니다.

- PR 제목과 본문
- `review_revision`과 `change_revision`
- Review 대상 diff
- 위험도와 공개 metadata
- CI와 AI 사전 리뷰 상태
- Draft부터 Merged까지의 코드 진행 상태
- 사람의 Ready와 Approve 상태
- `[R]`, `[C]`, `[A]` thread
- 작성자, 코드 위치와 thread 해결 상태
- Merge Queue 진입을 막는 조건
- 다음 행동과 행동 주체

### `workflow review update`

리뷰 이후 같은 Worktree에서 수정한 코드나 PR 본문을 다시 게시합니다.

```bash
./tools/workflow review update --revision rv-7
```

PR 본문도 함께 변경할 수 있습니다.

```bash
./tools/workflow review update \
  --revision rv-7 \
  --body-file pr.md
```

| 인자 | 필수 | 설명 |
| --- | --- | --- |
| `--revision <id>` | O | 마지막으로 확인한 `review_revision` |
| `--body-file <path>` | X | 변경할 PR 본문, 현재 Worktree 기준 경로 |
| `--json` | X | JSON 결과 반환 |

다른 작업으로 `review_revision`이 바뀌면 `STALE_REVISION`을 반환하고 갱신하지 않습니다.

코드가 바뀌면 현재 fingerprint의 최신 `check`가 필요합니다. Workflow는 변경 기록, 원격 Branch와 PR 갱신, CI와 AI 사전 리뷰 재실행, 새 `review_revision`과 `change_revision` 생성을 수행합니다. 기존 Approve는 무효화됩니다.

본문만 바뀌고 코드 fingerprint가 같으면 새 `review_revision`만 생성합니다. CI, `change_revision`과 기존 Approve는 유지합니다. PR 본문은 항상 현재 코드와 일치해야 합니다.

### `workflow review comment`

새로운 Review 의견을 추가합니다.

- `[R]`: 승인 전에 반드시 해결해야 하며 Approve와 Queue 진입을 차단하는 문제
- `[C]`: 현재 변경에서 반영하거나 반영하지 않는 이유를 설명해야 하는 개선
- `[A]`: 승인과 직접 연결되지 않으며 반영을 위해 범위를 확장하지 않는 개선

문자열 본문:

```bash
./tools/workflow review comment \
  --revision rv-8 \
  --level R \
  --body "fallback 경로에서 기존 동작이 유지되지 않습니다."
```

파일 본문과 코드 위치:

```bash
./tools/workflow review comment \
  --revision rv-8 \
  --level R \
  --path src/main/... \
  --line 42 \
  --body-file comment.md
```

| 인자 | 필수 | 설명 |
| --- | --- | --- |
| `--revision <id>` | O | 현재 `review_revision` |
| `--level <R\|C\|A>` | O | 의견 등급 |
| `--body <text>` | 조건부 | 문자열 본문 |
| `--body-file <path>` | 조건부 | 파일 본문, 현재 Worktree 기준 경로 |
| `--path <path>` | X | PR diff 안의 대상 파일 |
| `--line <number>` | X | `--path`와 함께 사용하는 diff 코드 위치 |
| `--json` | X | JSON 결과 반환 |

`--body`와 `--body-file` 중 하나만 사용합니다. `--line`은 `--path`와 함께 사용해야 합니다.

코멘트에는 문제 조건, 예상 영향, 판단 근거와 확인하거나 수정할 부분을 포함합니다. 현재 SubTask 밖의 개선은 PR 범위를 확장하지 않고 후속 SubTask 후보로 남깁니다. AI 리뷰는 사람의 승인이나 CI를 대신하지 않습니다.

### `workflow review reply`

Review thread에 답변합니다.

```bash
./tools/workflow review reply \
  --revision rv-8 \
  --thread thread-31 \
  --body "수정하고 회귀 테스트를 추가했습니다."
```

| 인자 | 필수 | 설명 |
| --- | --- | --- |
| `--revision <id>` | O | 현재 `review_revision` |
| `--thread <id>` | O | 대상 thread |
| `--body <text>` | 조건부 | 문자열 본문 |
| `--body-file <path>` | 조건부 | 파일 본문, 현재 Worktree 기준 경로 |
| `--json` | X | JSON 결과 반환 |

`--body`와 `--body-file` 중 하나만 사용합니다. `reply`는 thread를 임의로 해결하지 않습니다.

Agent가 작성하는 PR과 Issue 코멘트 및 답글에는 CLI가 `[Agent]`를 추가합니다. CLI는 인증된 실행 주체로 사람과 Agent를 구분합니다.

### `workflow review resolve`

처리가 끝난 Review thread를 해결 상태로 변경합니다.

```bash
./tools/workflow review resolve \
  --revision rv-8 \
  --thread thread-31
```

사람이 작성했거나 사람의 확인이 필요한 thread는 Agent가 해결할 수 없습니다.

```json
{
  "type": "failure",
  "data": {
    "code": "HUMAN_REQUIRED",
    "message": "사람 리뷰어의 확인이 필요합니다.",
    "blocked_by": [
      {
        "code": "HUMAN_REVIEW_REQUIRED",
        "message": "사람이 작성한 thread는 사람이 해결해야 합니다.",
        "target": "thread-31"
      }
    ],
    "next": []
  }
}
```

해결되지 않은 `[R]` thread는 Approve와 Merge Queue 진입을 차단합니다.

## `workflow stack`

현재 SubTask의 직접 코드 의존성을 변경합니다.

설정:

```bash
./tools/workflow stack --requires sk-101
```

제거:

```bash
./tools/workflow stack --clear
```

| 인자 | 필수 | 설명 |
| --- | --- | --- |
| `--requires <subtask-id>` | 조건부 | 직접 필요한 미통합 선행 SubTask 1개 |
| `--clear` | 조건부 | 현재 직접 의존 관계 제거 |
| `--json` | X | JSON 결과 반환 |

`--requires`와 `--clear` 중 하나만 사용합니다. 하나의 SubTask는 최대 1개의 직접 선행 SubTask를 가집니다. 하나의 선행 SubTask에는 여러 자식 SubTask가 의존할 수 있습니다.

`stack`은 다음 작업을 수행합니다.

- 현재 관리 Worktree 확인
- 선행 SubTask와 순환 의존성 검증
- 직접 코드 의존 관계 변경
- Branch 관계와 PR base 변경
- 필요한 restack
- `review_revision`, `change_revision`, 검증과 승인에 미치는 영향 판단
- 재검증 필요 여부 반환

Stack은 작업 순서, 번호 또는 같은 외부 Task에 속한다는 이유로 만들지 않습니다. 현재 SubTask가 미통합 선행 SubTask의 코드를 실제로 필요로 할 때만 설정합니다.

Agent는 Branch를 직접 checkout하거나 rebase하지 않습니다. 자동 restack으로 Review 대상 diff가 유지되면 `change_revision`과 기존 승인을 유지합니다. 충돌 해결이나 추가 수정으로 diff가 달라지면 기존 검증과 승인을 무효화합니다.

## `workflow sync`

현재 Worktree를 신뢰 가능한 원격 상태와 동기화합니다.

```bash
./tools/workflow sync
```

`sync`는 다음 작업을 수행합니다.

- 현재 관리 Worktree 확인
- 최신 원격 `main` 확인
- 선행 SubTask 상태 확인
- 필요한 restack
- 부모 SubTask가 merge된 경우 최신 `main` 기준으로 정리
- PR base와 Stack metadata 동기화
- Review revision, 검증과 승인에 미치는 영향 판단

자동으로 해결할 수 없는 충돌이 발생하면 Worktree를 `SYNC_CONFLICT` 복구 상태로 전환합니다. Workflow가 관리하는 Git 상태는 Agent가 직접 조작하지 않습니다.

```json
{
  "type": "failure",
  "data": {
    "code": "SYNC_CONFLICT",
    "message": "자동으로 동기화할 수 없는 코드 충돌이 있습니다.",
    "workspace": {
      "path": "/repo-managed/sk-102"
    },
    "conflicts": [
      {
        "path": "src/main/...",
        "kind": "content"
      }
    ],
    "next": [
      {
        "actor": "agent",
        "action": "resolve_conflicts",
        "command": null
      },
      {
        "actor": "agent",
        "action": "continue_sync",
        "command": "./tools/workflow sync --continue"
      },
      {
        "actor": "agent",
        "action": "abort_sync",
        "command": "./tools/workflow sync --abort"
      }
    ]
  }
}
```

Agent는 반환된 Worktree에서 충돌 파일만 수정한 뒤 다음 명령으로 복구를 계속합니다.

```bash
./tools/workflow sync --continue
```

`--continue`는 충돌이 모두 해결됐는지 확인하고 Workflow가 restack을 완료합니다. 완료 후 `check`를 다시 실행해야 합니다.

동기화를 취소하려면 다음 명령을 사용합니다.

```bash
./tools/workflow sync --abort
```

`--abort`는 Worktree와 Workflow metadata를 동기화 시작 전 상태로 복구합니다. `--continue`와 `--abort`는 `SYNC_CONFLICT` 상태에서만 사용할 수 있습니다.

## `workflow status`

Workflow 상태를 조회합니다. `status`는 Worktree 밖에서도 실행할 수 있습니다.

현재 Worktree:

```bash
./tools/workflow status
```

특정 SubTask 또는 외부 Task:

```bash
./tools/workflow status --subtask sk-102
./tools/workflow status --task TASK-42
```

배포 후보 또는 공개 대상:

```bash
./tools/workflow status --candidate dc-17
./tools/workflow status --release rel-31
```

| 인자 | 필수 | 설명 |
| --- | --- | --- |
| `--subtask <id>` | X | 특정 SubTask |
| `--task <id>` | X | 외부 Task와 소속 SubTask 그래프 |
| `--candidate <id>` | X | 고정된 배포 후보 |
| `--release <id>` | X | Feature Flag 외부 공개 대상 |
| `--json` | X | JSON 결과 반환 |

대상 선택 인자는 최대 1개만 사용합니다. 대상 인자가 없으면 현재 관리 Worktree의 SubTask를 조회합니다.

`status`는 대상에 따라 다음 정보를 반환합니다.

- 외부 Task와 소속 SubTask 그래프
- Worktree 경로와 Branch
- 직접 코드 의존 관계
- PR과 두 Review revision
- Review thread, CI와 AI 사전 리뷰
- 사람의 Ready와 Approve
- Merge Queue와 `main` 통합 상태
- 배포 후보의 고정 `main` revision과 포함된 SubTask
- 위험도, 검수, Canary와 production 배포 상태
- Feature Flag, 내부 검수와 외부 공개 상태
- 차단 조건
- 다음 행동과 행동 주체

SubTask 조회 예:

```json
{
  "type": "success",
  "data": {
    "task": "TASK-42",
    "subtask": "sk-102",
    "workspace": {
      "path": "/repo-managed/sk-102"
    },
    "branch": "sk-102",
    "state": "MERGED",
    "review": {
      "state": "APPROVED",
      "review_revision": "rv-8",
      "change_revision": "cr-4"
    },
    "integration": {
      "merge_queue": {
        "state": "PASSED"
      },
      "main": {
        "revision": "8a21c4f"
      }
    },
    "deployment": {
      "candidate_id": "dc-17",
      "state": "CANARY",
      "main_revision": "8a21c4f",
      "included_subtasks": [
        "sk-101",
        "sk-102"
      ],
      "risk": "high",
      "gate_required": false
    },
    "release": {
      "release_id": "rel-31",
      "feature_flag_id": "recommendation-v2",
      "state": "SAFE_DEFAULT",
      "gate_required": false
    },
    "blocked_by": [],
    "next": [
      {
        "actor": "workflow",
        "action": "verify_canary"
      }
    ]
  }
}
```

배포 후보 상태는 다음 값을 사용합니다.

- `NOT_SELECTED`
- `CANDIDATE`
- `VALIDATING`
- `AWAITING_DEPLOY_APPROVAL`
- `CANARY`
- `PRODUCTION`
- `FAILED`

외부 공개 상태는 다음 값을 사용합니다.

- `NOT_APPLICABLE`
- `SAFE_DEFAULT`
- `INTERNAL_VALIDATION`
- `AWAITING_RELEASE_APPROVAL`
- `ROLLOUT`
- `RELEASED`
- `CLEANUP_REQUIRED`

## `workflow gate`

`gate`는 사람이 내려야 하는 결정을 기록합니다. Agent는 실행할 수 없습니다.

```bash
./tools/workflow gate ready sk-102 --review-revision rv-8
./tools/workflow gate approve sk-102 --change-revision cr-4
./tools/workflow gate deploy dc-17
./tools/workflow gate release rel-31
```

| 하위 명령 | 대상과 전제조건 |
| --- | --- |
| `ready` | Draft PR과 현재 `review_revision`, 코드와 일치하는 PR 본문 |
| `approve` | Ready 또는 Review 상태와 사람이 확인한 `change_revision` |
| `deploy` | 검수를 통과하고 Production Canary 직전인 `high` 위험 배포 후보 |
| `release` | production 배포와 내부 검수를 마친 Feature Flag 공개 대상 |

`ready`는 PR을 사람의 최종 리뷰 대상으로 전환합니다. `approve`는 특정 commit SHA가 아니라 `change_revision`이 나타내는 코드 변경을 승인합니다.

`deploy`는 해당 배포 후보의 Production Canary 진행을 승인합니다. `normal` 변경만 포함한 후보에는 이 gate를 사용하지 않습니다.

`release`는 외부 공개 시작만 승인합니다. 실제 공개 범위의 점진적 확대는 Workflow가 수행합니다.

Workflow는 인증된 human principal만 `gate` 호출자로 허용합니다. 성공한 결정에는 실행 주체, 시각, 대상 ID, `review_revision`, `change_revision` 또는 `main_revision`을 감사 기록으로 남깁니다.

Agent가 `gate`를 실행하면 `HUMAN_REQUIRED`를 반환하고 상태를 변경하지 않습니다.

## Merge Queue와 통합

사람의 Approve 이후 Workflow는 다음 조건을 다시 평가합니다.

- Ready와 Approve 완료
- 현재 `change_revision`과 승인 대상 일치
- 필수 PR CI 성공
- 해결되지 않은 `[R]` thread 없음
- 미통합 선행 SubTask 조건 충족

조건을 충족하면 Workflow가 PR을 Merge Queue에 자동으로 등록합니다. 별도의 `queue` 또는 `merge` 명령은 제공하지 않습니다.

Merge Queue는 최신 원격 `main`과 앞선 Queue 변경을 포함한 통합 후보를 검증합니다. 성공한 PR만 `main`에 squash merge합니다. PR CI 성공만으로 최신 `main`과의 통합 성공을 보장하지 않습니다.

Agent나 개발자는 로컬에서 SubTask Branch를 `main`에 merge하지 않습니다. PR이 승인돼도 임의로 직접 merge하거나 별도의 `finish` 작업을 실행하지 않습니다.

`main`의 squash commit 제목은 PR 제목과 같은 형식을 사용합니다.

```text
[sk-101] 추천 방식 선택 경계를 추가한다
```

## Stack과 Worktree 정리

SubTask가 `main`에 통합되면 Workflow는 다음 순서로 상태를 정리합니다.

1. 해당 SubTask를 부모로 사용하는 활성 Stack을 새 `main` 기준으로 restack합니다.
2. 후속 PR의 base와 Stack metadata를 갱신합니다.
3. 더 이상 참조되지 않는 원격 SubTask Branch를 제거합니다.
4. 게시되지 않은 로컬 변경이 없는 Worktree와 로컬 Branch를 제거합니다.
5. 원격 `main` 상태를 다시 가져옵니다.

게시되지 않은 변경이 있거나 후속 Stack 동기화가 끝나지 않으면 Worktree를 자동으로 제거하지 않습니다.

Cleanup 실패는 성공한 원격 merge를 취소하지 않습니다. 통합 완료와 로컬 정리는 별개의 상태입니다. Cleanup이 끝나지 않아도 다른 독립 SubTask를 시작할 수 있습니다.

## 배포 후보와 production

Workflow는 검증된 `main`의 특정 revision을 고정된 배포 후보로 선택합니다. 배포 후보에는 현재 production revision 이후부터 후보 revision까지 통합된 SubTask가 모두 포함됩니다.

배포가 시작된 뒤 다른 PR이 merge돼도 이미 고정된 후보의 `main_revision`과 `included_subtasks`는 바뀌지 않습니다. 코드 통합과 배포 실행은 일대일 관계가 아닙니다.

개발과 검수 환경을 위한 장기 Branch는 사용하지 않습니다. 동일한 배포 후보가 다음 단계를 진행합니다.

```text
main → deployment candidate → validation → Production Canary → Production
```

`normal` 변경만 포함한 후보는 필수 검증을 통과하면 자동으로 Canary를 시작할 수 있습니다. `high` 위험 SubTask가 하나라도 포함된 후보는 Production Canary 전에 사람의 `gate deploy`가 필요합니다.

Workflow는 Canary 검증을 통과한 같은 후보를 production으로 승격합니다. 배포 완료는 요청 시작이 아니라 대상 환경 적용과 필수 검증 완료를 뜻합니다.

## Feature Flag와 외부 공개

새로운 use case를 포함한 코드는 Feature Flag의 안전한 기본 동작으로 보호합니다. 코드는 production에 배포된 뒤에도 외부 사용자에게 자동으로 공개되지 않습니다.

production 배포와 내부 검수가 끝나면 사람이 `gate release`로 외부 공개 시작을 결정합니다. Workflow는 승인 이후 점진적 공개를 수행합니다.

공개가 완료된 임시 Feature Flag와 분기는 기존 SubTask를 다시 열어 제거하지 않습니다. 공개 상태를 `CLEANUP_REQUIRED`로 전환하고 새 Cleanup SubTask 생성을 다음 행동으로 반환합니다.

## 외부 Task 동기화

Workflow는 SubTask의 `main` 통합, 배포와 공개 상태를 외부 하위 작업에 반영합니다.

모든 SubTask가 merge됐다는 이유만으로 상위 Task를 자동 완료하지 않습니다. 상위 Task의 완료는 외부 시스템이 정의한 제품 및 공개 조건에 따라 판단합니다.

통합이나 배포가 끝난 SubTask에 추가 수정이 필요하면 기존 SubTask를 다시 열지 않고 새 SubTask를 시작합니다.

## 자동 처리

다음 작업은 Agent용 명령으로 노출하지 않습니다.

- Branch와 Worktree 생성 또는 제거
- 파일 staging과 commit 생성
- push와 force push 정책 적용
- rebase와 restack
- PR base 변경
- Merge Queue 등록과 squash merge
- 후속 Stack 정리
- 배포 후보 선택과 고정
- 검수 환경 배포
- `normal` 후보의 Canary 시작
- Production Canary 검증과 production 승격
- 외부 공개 승인 이후 점진적 확대
- SubTask 상태의 외부 시스템 반영

Workflow는 현재 상태와 공개 명령의 의도에 따라 이 작업을 자동으로 수행합니다.

## Agent 기본 흐름

```text
workflow start
    │
    ▼
workspace.path 반환
    │
    ▼
Agent가 해당 Worktree에서 코드와 테스트 작성
    │
    ▼
workflow check
    │
    ▼
workflow review open
    │
    ▼
Draft PR + CI + AI 사전 리뷰
    │
    ├── review show/comment/reply/resolve
    │
    └── 같은 Worktree에서 수정
            │
            ▼
        workflow check
            │
            ▼
        workflow review update
```

Ready와 Approve가 필요하면 `status`가 `actor: human`인 다음 행동을 반환합니다. Agent는 사람의 결정을 기다리는 동안 다른 독립 SubTask를 진행할 수 있습니다.

승인과 검증 조건을 충족한 PR의 Merge Queue 등록, `main` 통합, Stack 정리와 배포 후보 진행은 Workflow가 수행합니다.

## Stack 예시

```text
main
└── sk-101
    ├── sk-102
    └── sk-103

Worktrees
├── worktree(sk-101)
├── worktree(sk-102)
└── worktree(sk-103)
```

`sk-102`와 `sk-103`은 모두 `sk-101`의 코드에 의존하지만 서로 의존하지 않습니다. 각 SubTask는 독립된 Worktree와 PR을 사용합니다.

`sk-101`이 merge되면 Workflow는 두 SubTask를 새 `main` 기준으로 각각 restack합니다. 이후 두 PR은 서로 독립적으로 Merge Queue에 들어갈 수 있습니다.
