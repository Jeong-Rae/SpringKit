# Agent Guidebook Hook 검증 기록

검증은 2026-10-18에 진행했습니다. 범위는 Codex `PostToolUse` 입력을 생산 Bash wrapper에서 단일 실행 Stub으로 전달하고, Stub의 추가 컨텍스트가 다음 모델 입력에 도달하는지 확인하는 것입니다. Kotlin 검색 앱과의 연결은 검증하지 않았습니다.

## 검증 단계

| 단계 | 근거 | 결과 |
| --- | --- | --- |
| 도메인·책임 분석 | [Hook 도메인 검토](agent-guidebook-hook-domain-review.md) | 새 도메인 모델 없이 Codex payload를 기술 어댑터 경계로 두고 기존 검색 Application 재사용 경계를 기록했습니다. |
| 계약 우선 테스트 | `d475758`, `0bffcf5` | `d475758`에서 기존 136개 통과와 전달 계약 1개 RED를 확인했습니다. `0bffcf5`에서 기존 136개 통과와 전체 계약 테스트 12개 RED를 확인한 뒤 테스트와 계약 문서를 동결했습니다. |
| 생산 Bash 구현 | [프로세스 계약](agent-guidebook-hook-contract.md) | 동결한 계약대로 경로 오류 코드를 검증한 뒤 `exec "$app_executable"`로 실행합니다. 테스트 148개가 통과하고 증분 빌드가 성공했습니다. |
| 실제 Codex runtime | run4 pane 실행 | 좁은 matcher와 생산 Bash를 통해 Hook 입력이 App Stub에 도달했고, 추가 컨텍스트 canary가 최종 모델 응답에 반환됐습니다. |

생산 검증 순서는 `bash -n`과 JSON 문법 확인, `spotlessApply`, 전체 `com.springkit.agentguidebook.*` 테스트, `build`였습니다. JUnit XML은 13개 suite에서 148개 테스트, 실패 0개, 오류 0개, 건너뜀 0개를 기록합니다. `hooks/post-tool-use.sh`의 실행 모드는 `100755`입니다. 이 단계에서 `src/main`, Gradle, 동결된 테스트 계약, 기존 core는 변경하지 않았습니다.

계약 단계에는 앱 경로가 없거나 비어 있거나 상대 경로인 경우 64, 없는 절대 경로는 127, 디렉터리 또는 실행 불가 파일은 126을 반환하는 검증을 추가했습니다. 유효한 경로는 공백과 셸 문자가 있어도 단일 경로 데이터로 실행하며, stdin/stdout/stderr/cwd/앱 종료 코드를 변경하지 않습니다. 테스트는 앱 경로에 공백과 명령 치환 문자열이 있는 경우, Hook JSON에 명령 치환 또는 역따옴표가 있는 경우도 확인합니다.

## run4 설정과 재현

root는 Herdr `w1:p2`에서 `/tmp/springkit-hook-probe-20261018/run-probe-run4.sh`를 실행했습니다. 이 런처는 bare `codex`를 사용하고, 별도 temp 프로젝트와 공백이 포함된 App Stub 경로를 사용했습니다. 실제 `AGENT_GUIDEBOOK_APP_EXECUTABLE` 값은 다음 파일입니다.

```text
/tmp/springkit-hook-probe-20261018/app stub/capture-appstub-run4.py
```

실행 시에는 Codex 사용자 설정을 무시하고 strict config를 활성화한 뒤 CLI inline 설정으로 Hook을 등록했습니다. matcher는 `^apply_patch$`이며 command는 생산 wrapper의 절대 경로입니다.

```sh
-c 'features.hooks=true'
-c 'hooks.PostToolUse=[{matcher="^apply_patch$",hooks=[{type="command",command="/workspaces/SpringKit/agent-guidebook/hooks/post-tool-use.sh",timeout=10,additionalContextLimit=128}]}]'
```

CLI에는 run4 임시 프로젝트 경로에 한정된 프로젝트 trust 설정과, 검토한 해당 CLI 실행 전체에서 Hook trust 확인을 한 번 생략하는 `--dangerously-bypass-hook-trust` 옵션을 함께 전달했습니다. 이 옵션은 프로젝트별 범위 제한이 아니며 전역 trust 설정을 변경하지 않습니다. 런처는 Codex 종료 뒤 `codex.exit-code`를 기록하고, Codex의 JSONL stdout과 stderr를 각각 저장합니다. 실제 Codex 실행 명령은 승인된 런처를 통해서만 실행했습니다.

재현 입력은 `/tmp/springkit-hook-probe-20261018/probe-prompt-run4.txt`이고, 수정 전후 파일은 `/tmp/springkit-hook-probe-20261018/run4-project/src/Probe.kt`와 `results/run4/source-before.txt`입니다. 캡처와 출력 경로는 다음과 같습니다.

- 실제 Hook payload: [`dd7bff2f54334b21aa40fdb5b51c18a8.json`](/tmp/springkit-hook-probe-20261018/results/run4/hook-events/dd7bff2f54334b21aa40fdb5b51c18a8.json)
- Codex JSONL stdout: [`codex.stdout.jsonl`](/tmp/springkit-hook-probe-20261018/results/run4/codex.stdout.jsonl)
- Codex stderr: [`codex.stderr.log`](/tmp/springkit-hook-probe-20261018/results/run4/codex.stderr.log)
- Codex exit code: [`codex.exit-code`](/tmp/springkit-hook-probe-20261018/results/run4/codex.exit-code)

## 실제 관찰 결과

`codex.exit-code`는 `0`입니다. Hook 캡처 파일은 하나이며 `hook_event_name`은 `PostToolUse`, `tool_name`은 `apply_patch`였습니다. `tool_input`은 객체이고 `tool_input.command`는 문자열이었습니다. `tool_response`도 객체가 아닌 문자열이며 `Exit code: 0`과 `src/Probe.kt` 변경 결과를 담았습니다. 저장한 원시 payload는 run4 temp 프로젝트 경로와 Codex 세션 메타데이터를 포함하므로 별도 결과 경로에 두었습니다.

Codex는 `apply_patch` 전에 `Bash`로 `cat src/Probe.kt`를 실행했습니다. narrow matcher 때문에 해당 Bash 이벤트는 생산 Hook에 전달되지 않아 Stub raw capture가 추가되지 않았습니다. 패치 뒤 추가 도구 호출 없이 turn이 완료됐습니다. `Probe.kt` 값은 `before`에서 `after`로 바뀌었습니다.

App Stub은 프롬프트에 없는 `SPRINGKIT_HOOK_CANARY_20261018_RUN4_C8E2B9A47D13`를 `hookSpecificOutput.additionalContext`로 반환했습니다. Codex의 최종 `agent_message`는 이 토큰을 정확히 한 줄로 출력했습니다. 따라서 이번 run4에서는 생산 Bash → App Stub → Hook JSON stdout → 다음 모델 입력 경로가 실제 Codex에서 동작했습니다.

Codex JSONL에는 승인된 `--dangerously-bypass-hook-trust` 사용에 관한 경고 이벤트가 두 건 있습니다. stderr에는 별도의 Firecrawl MCP 인증 오류가 있었지만, Codex 프로세스는 종료 코드 0으로 완료했고 Hook canary 전달도 성공했습니다. 이 외부 오류를 Hook 실행 실패 원인으로 보지 않습니다.

## 한계

- App Stub만 사용했습니다. 실제 Kotlin 검색 앱, 검색 도메인, 파일 경로 추출은 연결하거나 검증하지 않았습니다.
- 실제 runtime은 Codex CLI inline 설정으로 실행했습니다. 공식 문서에 따른 `hooks.json` 예시는 있지만 file 기반 hook discovery는 검증하지 않았습니다.
- run1과 run2에서는 canary가 반환되지 않았고 raw payload도 수집되지 않았습니다. 실패 원인은 귀속하지 않았습니다.
- run4는 실제 `apply_patch` 이벤트 한 건으로 production wrapper를 확인했습니다. 프로젝트 또는 전역 Codex 설정, auth 파일, Hook 계약 테스트는 변경하지 않았습니다.
