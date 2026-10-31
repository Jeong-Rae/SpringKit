# Agent Guidebook Hook 프로세스 계약

## 목적과 범위

Codex `PostToolUse` command hook과 Agent Guidebook 실행 앱 사이의 프로세스 경계를 정의합니다. `hooks/post-tool-use.sh`는 입력 해석이나 검색 없이 설정된 단일 실행 파일에 입력과 표준 스트림을 전달합니다. 실제 Kotlin 앱, Hook JSON 파싱, 파일 경로 추출, 검색, Presentation, SQLite, Gradle 배포는 이 계약에 포함되지 않습니다.

실제 Codex run3에서 Hook 입력은 JSON 한 줄로 제한되지 않은 JSON 텍스트였고, 실행 Stub의 `hookSpecificOutput.additionalContext`가 다음 모델 입력에 전달됐습니다. 실측한 필드와 타입, canary 증거는 [도메인 검토 Artifact](agent-guidebook-hook-domain-review.md)에 기록했습니다. 이 계약 테스트는 Hook 프로세스 경계의 stdin/stdout/stderr/cwd/종료 코드만 확인하며 소스나 YAML 검색을 하지 않습니다.

## 객체와 책임

| 객체 | 책임 |
| --- | --- |
| Codex `PostToolUse` 설정 | matcher에 맞는 이벤트에서 wrapper를 동기 실행하고 Hook JSON을 stdin으로 전달합니다. |
| `hooks/post-tool-use.sh` | 실행 파일 경로를 검증하고, 유효하면 앱을 한 번 실행해 프로세스 경계를 연결합니다. JSON, 도구 이름, 검색 대상을 해석하지 않습니다. |
| `AGENT_GUIDEBOOK_APP_EXECUTABLE` | Kotlin 앱 또는 테스트 대역을 가리키는 단일 실행 파일의 절대 경로입니다. shell 명령 문자열이 아닙니다. |
| 실행 앱 | Hook 입력을 해석하고 필요한 경우 표준 Hook 응답 JSON을 stdout에 씁니다. 실제 Kotlin 앱 연결은 별도 범위입니다. |
| 프로세스 계약 테스트 | Bash wrapper와 실행 Stub을 별도 프로세스로 실행하고 파일 및 byte 출력으로 전달을 확인합니다. |

## 입력

Codex가 stdin에 쓴 원시 byte sequence와 환경 변수 `AGENT_GUIDEBOOK_APP_EXECUTABLE`을 받습니다. wrapper는 stdin을 파싱하거나 재인코딩하지 않으며 JSON 유효성이나 `tool_name`을 검사하지 않습니다. 앱 실행은 Codex가 Hook을 호출한 현재 작업 디렉터리를 상속하고, wrapper는 `cd`를 수행하지 않습니다.

실행 경로는 비어 있지 않은 절대 경로여야 합니다. 경로에 공백이나 셸 문자가 있어도 값 전체를 하나의 경로 데이터로 취급합니다. `eval`, 문자열 보간 후 셸 실행, 입력 내용의 명령 치환은 금지합니다.

## 출력

앱 stdout byte, stderr byte, 종료 코드를 그대로 Codex Hook command에 전달합니다. wrapper는 stdout에 텍스트나 줄바꿈을 추가하지 않습니다. 앱의 stdout이 비어 있고 종료 코드가 0인 경우도 정상입니다.

`PostToolUse` 추가 컨텍스트가 필요하면 앱이 다음 표준 응답을 stdout으로 생성합니다. JSON 응답 생성과 추가 컨텍스트 내용의 결정은 앱 책임입니다.

```json
{
  "hookSpecificOutput": {
    "hookEventName": "PostToolUse",
    "additionalContext": "모델의 다음 입력에 제공할 내용"
  }
}
```

앱이 시작된 뒤 반환한 종료 코드는 그대로 전달합니다. 따라서 0이 아닌 앱 종료 코드도 wrapper 오류 코드로 바꾸지 않습니다.

## 조건과 오류

앱 경로 검증은 앱을 실행하기 전에 수행합니다. wrapper 자체 오류는 한국어 진단을 stderr에 한 줄 쓰고 다음 종료 코드를 반환합니다.

| 조건 | 분류 | stderr | 종료 코드 |
| --- | --- | --- | ---: |
| 환경 변수가 없거나 빈 문자열 | 잘못된 설정 인자 | `앱 실행 파일 경로가 설정되지 않았습니다.` | 64 |
| 환경 변수 값이 상대 경로 | 잘못된 설정 인자 | `앱 실행 파일 경로는 절대 경로여야 합니다.` | 64 |
| 절대 경로의 대상이 없음 | 실행 상태 오류 | `앱 실행 파일을 찾을 수 없습니다.` | 127 |
| 절대 경로가 디렉터리이거나 실행할 수 없음 | 실행 상태 오류 | `앱 실행 파일을 실행할 수 없습니다.` | 126 |
| 실행한 앱이 0이 아닌 종료 코드를 반환 | 앱 결과 | 앱 stderr byte 그대로 | 앱 종료 코드 그대로 |

설정 경로 오류에서는 앱이 시작되지 않으므로 앱 출력이 없고 stdout은 비어 있습니다. JSON 형식 오류는 wrapper 오류가 아닙니다. wrapper는 입력을 그대로 앱에 전달하며, 이를 처리하는 규칙은 향후 앱 계약에서 다룹니다.

## 상태와 부작용

wrapper는 요청 사이에 상태를 보관하지 않습니다. 유효한 경로마다 앱 프로세스를 한 번 시작하고, 앱은 Hook 호출의 cwd를 그대로 상속합니다. wrapper는 입력이나 검색 결과를 파일에 저장하지 않습니다. 앱 실행 자체의 부작용과 상태는 앱이 소유합니다.

표준 스트림은 byte 단위로 전달합니다. 앱의 UTF-8 한글 출력도 변경하지 않으며, 입력 JSON의 `$()`, 역따옴표, 세미콜론, 따옴표나 경로의 셸 문자는 실행되지 않습니다.

## Codex 설정 방법

`AGENT_GUIDEBOOK_APP_EXECUTABLE`을 Codex 프로세스가 상속할 환경에 설정하고 Codex를 시작합니다. 값에는 실행 파일 경로만 지정하며 인자나 셸 구문을 넣지 않습니다.

```sh
export AGENT_GUIDEBOOK_APP_EXECUTABLE="/absolute/path/to/agent-guidebook-app"
codex \
  -c 'features.hooks=true' \
  -c 'hooks.PostToolUse=[{matcher="^apply_patch$",hooks=[{type="command",command="/absolute/path/to/SpringKit/agent-guidebook/hooks/post-tool-use.sh",timeout=10}]}]'
```

실제 run3에서는 `features.hooks=true`와 `hooks.PostToolUse=[...]`를 Codex CLI `-c` inline 설정으로 전달했습니다. 관찰 범위를 넓히기 위해 run3 matcher는 `.*`였고 `apply_patch`, `Bash` 이벤트를 모두 받았습니다. 위 권장 설정은 matcher를 `^apply_patch$`로 제한해 wrapper와 앱에 `apply_patch` 이벤트만 전달합니다. `hooks.json` 파일을 읽는 구성 경로는 이번 runtime probe에서 검증하지 않았습니다.

Codex 공식 문서도 사용자 또는 프로젝트 계층의 `hooks.json` 파일 형식을 제공합니다. 다음은 그 공식 파일 형식을 옮긴 참고 예시이며, 이 작업의 runtime에서 파일 기반 hook discovery가 동작한다고 확인한 설정이 아닙니다. 프로젝트 설정에 적용할 때는 Codex가 프로젝트 `.codex/` 계층을 신뢰하는지 확인하고, 경로를 실제 checkout 위치로 바꾸어야 합니다.

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "^apply_patch$",
        "hooks": [
          {
            "type": "command",
            "command": "/absolute/path/to/SpringKit/agent-guidebook/hooks/post-tool-use.sh"
          }
        ]
      }
    ]
  }
}
```

Hook 설정의 지원 파일 위치와 신뢰 동작은 Codex 공식 [Hooks 안내](https://learn.chatgpt.com/docs/hooks)와 [고급 설정 안내](https://learn.chatgpt.com/docs/config-file/config-advanced)를 따릅니다. 이번 변경은 전역 또는 프로젝트 Codex 설정을 수정하지 않습니다.

## 계약 시나리오

1. 정상 앱에 stdin JSON을 전달하면 입력 byte, 앱 stdout/stderr, cwd, 종료 코드가 보존됩니다.
2. stdout이 비어 있는 앱이 0으로 끝나면 wrapper도 빈 stdout과 0을 반환합니다.
3. 앱이 0이 아닌 종료 코드를 반환하면 한글 stderr와 앱 종료 코드가 그대로 전달됩니다.
4. 앱 경로가 설정되지 않았거나 비어 있으면 stderr 진단과 64를 반환하고 앱을 시작하지 않습니다.
5. 상대 경로는 stderr 진단과 64, 존재하지 않는 절대 경로는 진단과 127을 반환합니다.
6. 디렉터리 또는 실행 불가 경로는 stderr 진단과 126을 반환하고 앱을 시작하지 않습니다.
7. 실행 경로에 셸 문자가 있거나 입력에 셸 구문이 있어도 명령을 실행하지 않고 경로 또는 원시 입력 데이터로 전달합니다.
