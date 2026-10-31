# Agent Guidebook Hook 도메인 검토

## 목적과 범위

Codex `PostToolUse` 입력을 단일 실행 파일로 전달하고, 실행 파일의 표준 출력에 있는 Hook 응답을 Codex에 돌려주는 프로세스 경계를 정의합니다. 이번 단계의 구현 대상은 `hooks/post-tool-use.sh`와 프로세스 계약 테스트입니다. Kotlin 검색 앱, Hook JSON 파싱, 변경 경로 추출, Presentation, SQLite, Gradle 배포 연결은 포함하지 않습니다.

실제 Codex 실행에서는 `PostToolUse` 입력이 Bash wrapper를 거쳐 앱 실행 Stub으로 전달됐고, Stub이 반환한 `hookSpecificOutput.additionalContext` canary가 다음 모델 입력에 도달했습니다. 이 검증은 CLI inline hook 설정, 모든 도구를 관찰하는 matcher, bare `codex` 실행, Bash bridge와 App Stub을 함께 사용했습니다. 앞선 run1과 run2의 실패 원인은 분리해 확정하지 않습니다.

## 실제 이벤트 관찰

run3에서 저장한 원시 이벤트는 UUID 파일 두 개입니다. 파일을 읽어 확인한 이벤트는 성공한 `apply_patch`와 소스 확인을 위한 `Bash`였으며, 둘 다 `hook_event_name`은 `PostToolUse`였습니다. Hook 설정의 matcher는 run3에서 `.*`였고, 실제 도구 이름은 각각 `apply_patch`와 `Bash`였습니다.

실제 공통 필드는 문자열 `session_id`, `turn_id`, `transcript_path`, `cwd`, `hook_event_name`, `model`, `permission_mode`, `tool_name`, `tool_use_id`였습니다. `tool_input`은 객체이며 `command` 문자열을 포함했습니다. `apply_patch` 이벤트에서 `tool_input.command`는 patch 전문이었습니다. `tool_response`는 객체가 아니라 `Exit code`, `Wall time`, `Output`과 변경 파일을 포함하는 문자열이었습니다. `Bash` 이벤트의 `tool_input.command`는 `cat src/Probe.kt` 문자열이었고 `tool_response`도 문자열이었습니다.

아래는 실제 입력의 필드명과 타입을 유지하면서 session 식별자, transcript 경로, 작업 경로를 익명화한 예입니다. Hook 입력 전체를 Bash가 해석하지 않고 그대로 전달하므로, 이 문자열 필드가 이후 Bash에서 명령으로 평가되어서는 안 됩니다.

```json
{
  "session_id": "<SESSION_ID>",
  "turn_id": "<TURN_ID>",
  "transcript_path": "<TRANSCRIPT_PATH>",
  "cwd": "<PROBE_CWD>",
  "hook_event_name": "PostToolUse",
  "model": "<MODEL_NAME>",
  "permission_mode": "bypassPermissions",
  "tool_name": "apply_patch",
  "tool_input": {
    "command": "*** Begin Patch\n*** Update File: <PROBE_ROOT>/src/Probe.kt\n@@\n-val probeValue = \"before\"\n+val probeValue = \"after\"\n*** End Patch"
  },
  "tool_response": "Exit code: 0\nWall time: 0.1s\nOutput:\nSuccess. Updated the following files:\nM <PROBE_ROOT>/src/Probe.kt\n",
  "tool_use_id": "<TOOL_USE_ID>"
}
```

run3 App Stub은 `apply_patch` 입력에만 다음 구조의 JSON을 stdout으로 반환했습니다. `Bash` 입력에서는 stdout을 비웠습니다. 실제 Codex 최종 응답은 사용자 프롬프트에 없던 `SPRINGKIT_HOOK_CANARY_20261018_RUN3_7D4A90C31E`를 그대로 포함했습니다. 이는 추가 컨텍스트가 다음 inference에 전달됐다는 runtime 증거입니다.

```json
{
  "hookSpecificOutput": {
    "hookEventName": "PostToolUse",
    "additionalContext": "PostToolUse canary: SPRINGKIT_HOOK_CANARY_20261018_RUN3_7D4A90C31E. 다음 응답에 토큰을 그대로 출력하세요."
  }
}
```

## 도메인 판단과 재사용

Hook 프로세스 전달에는 새 도메인 개념이 필요하지 않습니다. Codex의 `PostToolUse`, `tool_input`, `tool_response`, `additionalContext`는 기술 프로토콜이며 core Aggregate, Entity, Value Object로 만들지 않습니다. Hook 입력을 해석하는 DTO가 나중에 필요하더라도 Codex adapter 경계에 둡니다.

기존 검색 도메인은 수정 없이 재사용합니다. 이후 Kotlin 앱 연결 단계에서 입력을 검색 요청으로 변환할 수 있게 되면 `GuidelineSearchService`, `GuidelineSearchRequest`, `GuidelineSearchFactory.fileSystem()`과 순수 도메인 `GuidelineSearch`를 사용합니다. 이 작업에서는 JSON 파싱과 filepath 해석이 범위 밖이므로 아직 이 API를 호출하지 않습니다.

`session_id`는 원시 Hook 프로토콜의 메타데이터로 보존될 뿐입니다. 이번 범위에서는 Session 객체, 상태 저장, 같은 지침의 중복 방지를 추가하지 않습니다.

## 객체와 책임

| 객체 또는 경계 | 책임 |
| --- | --- |
| Codex `PostToolUse` hook 설정 | Hook 이벤트를 Bash command에 전달합니다. 실측한 도구 이름은 `apply_patch`, `Bash`이며 run3의 관찰 matcher는 `.*`였습니다. |
| `hooks/post-tool-use.sh` | `AGENT_GUIDEBOOK_APP_EXECUTABLE` 설정을 확인하고, 실행 파일을 한 번 `exec`합니다. JSON 파싱, 경로 추출, 검색, 도메인 판단을 하지 않습니다. |
| `AGENT_GUIDEBOOK_APP_EXECUTABLE` | 단일 실행 파일의 절대 경로입니다. 경로에 공백을 허용하며 shell command 문자열이 아닙니다. |
| App 실행 파일 | stdin 원문을 받고 stdout Hook 응답을 반환합니다. stderr와 종료 코드는 프로세스 경계로 돌려줍니다. 현재는 Kotlin 앱 대신 테스트 Stub으로 검증합니다. |
| `PostToolUseHookCommandTest` | `ProcessBuilder`로 실제 Bash wrapper와 App Test Double을 실행하고 파일, byte 출력, stderr, 종료 코드로 계약을 확인합니다. mock이나 호출 횟수 검증을 사용하지 않습니다. |
| 기존 Application 및 core 검색 | 후속 Kotlin 앱 연결에서 검색 요청을 처리합니다. 이번 프로세스 전달 계약에는 참여하지 않습니다. |

wrapper는 child process를 별도로 기다리는 대신 `exec "$AGENT_GUIDEBOOK_APP_EXECUTABLE"`를 사용합니다. 따라서 stdin, stdout, stderr가 직접 연결되고 실행된 앱의 종료 코드가 Hook command의 종료 코드가 됩니다. 앱 경로 값과 Hook 입력은 모두 데이터로 취급하며 `eval`, 문자열 조립 후 shell 실행, 입력 내용의 명령 치환은 금지합니다.
wrapper는 작업 디렉터리를 바꾸지 않으며 앱은 Codex가 Hook을 호출한 현재 작업 디렉터리를 그대로 상속합니다.

## 계약

| 항목 | 계약 |
| --- | --- |
| 입력 | Codex가 stdin으로 보낸 원시 JSON byte sequence와 환경 변수 `AGENT_GUIDEBOOK_APP_EXECUTABLE`입니다. Hook 입력은 Bash가 파싱하거나 재인코딩하지 않습니다. |
| 앱 경로 조건 | 값은 비어 있지 않은 절대 경로이며 하나의 실행 파일을 가리킵니다. 공백과 shell 문자를 포함할 수 있습니다. |
| 정상 출력 | 앱이 stdout에 쓴 byte를 그대로 반환합니다. JSON Hook 응답을 만드는 책임은 앱에 있으며 wrapper는 유효성 검사나 변경을 하지 않습니다. |
| 추가 컨텍스트 | 앱은 필요할 때 `{ "hookSpecificOutput": { "hookEventName": "PostToolUse", "additionalContext": "..." } }`를 반환합니다. 빈 stdout과 종료 코드 0은 정상이며 wrapper가 내용을 추가하지 않습니다. |
| stderr | 앱이 쓴 byte를 그대로 반환합니다. wrapper 자체의 구성 또는 실행 오류는 한글 진단을 stderr에 기록합니다. |
| 종료 코드 | 앱이 실행되면 앱의 종료 코드를 그대로 반환합니다. 앱 실행 전 구성 오류만 아래 정의한 wrapper 오류 코드를 사용합니다. |
| 처리 조건 | Codex matcher가 command를 호출하면 wrapper는 tool name이나 payload 내용을 보지 않고 앱을 한 번 실행합니다. 현재 matcher 선택과 event filtering은 Hook 설정/앱 책임입니다. |
| 상태 | wrapper는 세션 또는 요청 간 상태를 보관하지 않습니다. 요청마다 별도 앱 프로세스가 한 번 실행됩니다. |
| 부작용 | wrapper 자체는 프로세스를 시작하고 표준 스트림을 연결하는 것 외에 파일이나 검색 상태를 변경하지 않습니다. 앱의 부작용은 앱 계약에 속합니다. |

### 인자 오류와 실행 오류

| 조건 | 분류 | stderr 진단 | 종료 코드 |
| --- | --- | --- | ---: |
| 환경 변수가 없거나 빈 문자열 | 잘못된 설정 인자 | `앱 실행 파일 경로가 설정되지 않았습니다.` | 64 |
| 환경 변수 값이 상대 경로 | 잘못된 설정 인자 | `앱 실행 파일 경로는 절대 경로여야 합니다.` | 64 |
| 절대 경로에 실행 파일이 없음 | 실행 상태 오류 | `앱 실행 파일을 찾을 수 없습니다.` | 127 |
| 경로가 디렉터리이거나 파일을 실행할 수 없음 | 실행 상태 오류 | `앱 실행 파일을 실행할 수 없습니다.` | 126 |
| 실행 가능한 앱이 시작되어 non-zero 종료 | 앱 결과 | 앱 stderr byte를 그대로 전달 | 앱 종료 코드 |

JSON 형식 오류는 Bash wrapper의 잘못된 인자가 아닙니다. wrapper는 유효성 검사를 하지 않고 어떤 stdin byte sequence든 실행 파일에 전달합니다. 실제 Kotlin adapter가 입력을 검증하는 계약은 별도 범위에서 정의합니다.

## 상태와 불변식

요청 간 상태 전이는 없습니다. 한 번의 Hook invocation은 앱 프로세스 한 번을 시작하며, `session_id`를 읽거나 저장하지 않습니다. 출력 JSON에 있는 `additionalContext`는 다음 Codex inference가 사용할 응답일 뿐, wrapper가 저장하는 상태가 아닙니다.

입력 JSON과 앱 경로는 데이터로만 전달됩니다. JSON 안의 `$()`, backtick, 세미콜론, 따옴표와 앱 경로 안의 shell 문자가 shell 문법으로 평가되지 않아야 합니다. 앱의 UTF-8 한글 출력도 byte-for-byte로 보존합니다.

## 계약 시나리오

1. 유효한 JSON 입력과 공백이 포함된 절대 앱 경로, 한글이 포함된 Hook JSON 출력으로 실행하면 앱이 저장한 stdin이 원문과 같고 stdout byte와 종료 코드 0이 보존됩니다.
2. 앱이 stdout을 비우고 종료 코드 0으로 끝나면 wrapper도 빈 stdout과 0을 반환합니다.
3. 앱이 한글 stderr를 기록하고 non-zero 코드로 끝나면 stderr byte와 종료 코드를 그대로 반환합니다.
4. 앱 경로가 설정되지 않았거나 상대 경로이면 앱을 실행하지 않고 한글 진단 및 64를 반환합니다.
5. 절대 경로의 앱 파일이 없으면 127, 경로가 디렉터리이거나 실행할 수 없으면 126을 반환합니다.
6. 입력 JSON에 shell 문법 문자열이 있거나 실행 파일 경로에 shell 문자가 포함되어도 명령이 실행되지 않고, 해당 문자열은 입력 또는 경로 데이터로만 취급됩니다.

이 시나리오들은 `ProcessBuilder`와 일반 App Test Double을 사용하는 프로세스 계약 테스트로 검증합니다. Stub이 stdin을 파일로 남기고 기대 응답을 쓰게 하여 실제 호출과 전달을 확인합니다. `times(1)` 같은 mock 검증은 사용하지 않습니다.
