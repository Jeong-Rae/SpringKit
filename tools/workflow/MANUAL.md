# Workflow 명령줄 도구

Workflow CLI(Command line interface, 명령줄 인터페이스)는 저장소 루트에서 실행합니다.

```sh
tools/workflow/bin/workflow <명령> [인자와 옵션]
```

## 명령

| 명령 | 필수 입력 | 주요 옵션과 동작 |
| --- | --- | --- |
| `start` | `--task <task-id>`, `--request-id <request-id>`, `--title <text>` | `--requires <subtask-id>`로 직접 선행 작업을 지정합니다. |
| `check` | 없음 | 현재 Worktree 변경을 검증합니다. |
| `review open` | `--body-file <경로>`, `--risk` (`normal` 또는 `high`) | 변경을 게시하고 초안 PR(Pull request)을 엽니다. |
| `review show` | 없음 | `--diff`, `--threads` (`open` 또는 `all`)를 지원합니다. 기본 조회 범위는 `open`입니다. |
| `review update` | `--revision <revision>` | 변경 커밋을 다시 게시합니다. `--body-file <경로>`를 지정하면 PR 설명도 갱신합니다. |
| `review comment` | `--revision <revision>`, `--level` (`R`, `C` 또는 `A`), `--body <내용>` 또는 `--body-file <경로>` | 본문 입력은 하나만 지정합니다. 인라인 댓글에는 `--path <파일>`과 `--line <번호>`를 함께 지정합니다. |
| `review reply` | `--revision <revision>`, `--thread <thread-id>`, `--body <내용>` 또는 `--body-file <경로>` | 본문 입력은 하나만 지정합니다. |
| `review resolve` | `--revision <revision>`, `--thread <thread-id>` | 지정한 리뷰 대화를 해결 상태로 바꿉니다. |
| `stack` | `--requires <subtask-id>` 또는 `--clear` | 두 옵션 중 하나만 지정합니다. |
| `sync` | 없음 | `--continue`로 동기화를 계속하거나 `--abort`로 중단합니다. 두 옵션은 함께 쓸 수 없습니다. |
| `status` | 없음 | 대상 조회에는 `--subtask`, `--task`, `--candidate`, `--release` 중 하나를 지정합니다. |
| `gate ready <subtask-id>` | `--review-revision <revision>` | PR을 사람 검토 대상으로 전환합니다. |
| `gate approve <subtask-id>` | `--change-revision <revision>` | 코드 변경을 승인합니다. |
| `gate deploy <candidate-id>` | `candidate-id` 인자 | Production Canary 배포를 승인합니다. |
| `gate release <release-id>` | `release-id` 인자 | 외부 공개를 승인합니다. |

각 작업 명령에 `--json`을 붙이면 결과를 구조화된 JSON으로 출력합니다.

`--allow-account-mismatch`는 `review update`, `review comment`, `review reply`, `review resolve`, `gate ready`, `gate approve`, `stack`, `sync`에서 사용할 수 있습니다. PR 변경 전에 현재 `gh` 사용자와 PR 작성자를 비교하며, 다르면 기본적으로 중단합니다. 이 옵션으로 계정 차이만 허용하면 작업을 계속하고 경고를 표준 오류(stderr)에 출력합니다. `--json` 결과는 표준 출력(stdout)에 유지됩니다.

```sh
tools/workflow/bin/workflow start --task SK-33 --request-id sk-33-start --title '문서 추가'
tools/workflow/bin/workflow review open --body-file /tmp/pr-body.md --risk normal
tools/workflow/bin/workflow review show --diff --threads all --json
tools/workflow/bin/workflow review comment --revision abc123 --level C --body '[Agent] 검토 의견입니다.'
tools/workflow/bin/workflow review update --revision rv-4 --allow-account-mismatch --json
tools/workflow/bin/workflow status --subtask sk-33
```

## 댓글과 인증

에이전트가 `review comment` 또는 `review reply`로 작성하는 댓글 본문은 `[Agent]`로 시작해야 합니다. `--body`와 `--body-file` 모두 같은 규칙을 적용합니다.

Workflow CLI는 설정된 인증을 사용하며 로그인이나 인증 절차를 다시 실행하지 않습니다.
