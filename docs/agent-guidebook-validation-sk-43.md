# SK-43 Agent Guidebook 실사용 검증

## 결과

Codex CLI 0.166.0에서 `apply_patch` 후 `PostToolUse` Hook, 설치된 JVM 앱, `additionalContext`, 다음 모델 응답까지 연결되는 것을 두 번 확인했습니다. 첫 실행에서는 테스트용 지침의 응답 문구가 다음 응답에 나타났고, 두 번째 실행에서는 실제 프로젝트 지침 ID가 다음 응답에 나타났습니다.

전역 Codex 설정에는 Hook이 등록되어 있지 않았습니다. 이번 검증은 Codex 실행별 `--config` 설정으로 Hook을 전달했습니다. 사용자 전역 설정과 Codex 인증 파일은 변경하거나 복사하지 않았습니다. Agent Guidebook의 SQLite 이력은 별도 `user.home` 아래에 두었습니다.

검증 환경: 2026년 11월 4일 UTC, 기준 HEAD `678b79fff3a83741fe19fb34883411b647e78f1e`, Linux, Java 25.0.2, Codex 모델 `gpt-6.1-sol`.

## 구성과 지침

테스트 프로젝트는 Git 제외 경로인 `.local/guidebook-validation/project`에 만들었습니다. `origin`은 `https://github.com/Jeong-Rae/SpringKit.git`으로 설정했습니다. 프로젝트의 `.agents/guides`에는 원본 가이드 19개를 복사했으며, 원본과 비교한 SHA-256 불일치는 없었습니다. 테스트 전용 가이드 4개를 더해 전체 23개를 사용했습니다.

테스트 전용 가이드는 양성 범위 1개와 음성 범위 3개로 구성했습니다. 양성 가이드는 `Jeong-Rae/SpringKit`, Kotlin, `file_changed`에 적용하고 `sk43CanaryProbe`를 찾습니다. 음성 가이드는 동일한 변경 source를 보되 각각 저장소, 언어, 이벤트 범위를 다르게 설정했습니다. 이 세 가이드는 scope filtering 확인에만 사용했습니다.

실행할 때 설치된 `hooks/post-tool-use.sh`를 호출했고, 해당 스크립트가 설치된 `bin/agent-guidebook`을 실행하도록 설정했습니다. 로컬 계측 래퍼는 Hook 입력과 출력, 실행 시간을 제외 경로에 저장한 뒤 설치된 Hook을 그대로 호출했습니다. `JAVA_OPTS`의 `-Duser.home`은 `.local/guidebook-validation/dbhome`을 가리켰습니다.

`agent-guidebook/hooks/post-tool-use.sh`, `agent-guidebook/hooks/hooks.example.json`과 설치 경로의 대응 파일은 바이트 단위로 같았습니다. 설치된 앱 실행 파일, Hook과 JSON 예제의 존재 및 실행 권한도 확인했습니다.

읽기 전용 설정 확인에서 사용자 `config.toml`에는 `features.hooks`와 `hooks` 항목이 없었고, 사용자 `hooks.json`도 찾지 못했습니다. 현재 프로세스에 `AGENT_GUIDEBOOK_APP_EXECUTABLE`도 설정되지 않았습니다. 따라서 배포 파일은 준비되어 있지만 상시 Codex Hook은 등록되지 않은 상태였습니다.

Codex 실행에는 `--json`, `--ephemeral`, `--ignore-user-config`, `--dangerously-bypass-hook-trust`, `--enable hooks`, `--sandbox workspace-write`를 사용했습니다. `PostToolUse` Hook은 해당 실행의 inline `--config`에만 등록했습니다. CLI 도움말에서 `--ephemeral`과 설정 override를 확인했고, `codex features list`에서 `hooks`를 stable로 확인했습니다. 실제 Codex 인증은 기존 인증을 사용했으며 인증 정보는 출력하거나 파일에 복제하지 않았습니다.

## 실제 Codex 실행

| 실행 | 실제 `apply_patch` 변경 | 설치 Hook의 `additionalContext` | 다음 모델 응답 |
| --- | --- | --- | --- |
| 1 | `Probe.kt`에서 `sk43CanaryProbe` 값을 `before`에서 `after`로 변경 | `[sk43-runtime-canary]`와 테스트 전용 응답 지침 | `SK43_RUNTIME_CONTEXT_ACK_7F31D9C2` |
| 2 | `Repository.kt`에서 `class GuidebookProbeRepository`를 `interface GuidebookProbeRepository`로 변경 | `[abstract-dependency-boundaries-and-confirmed-extension-points]`와 원본 지침 본문 | `abstract-dependency-boundaries-and-confirmed-extension-points` |

각 Codex JSONL에는 `file_change` 뒤에 `agent_message`가 기록됐습니다. Hook이 받은 입력은 `PostToolUse`, `apply_patch`, 테스트 프로젝트의 작업 경로(`cwd`)와 변경 파일 경로를 포함했습니다. 두 프롬프트는 `.agents/guides`를 열거나 검색하지 말라고 했고, JSONL에도 선행 셸 읽기는 없었습니다. Codex 최종 응답은 두 실행 모두 종료 코드 0으로 완료됐습니다.

첫 실행에서는 실제 파일 변경과 일치하는 양성 가이드만 제공됐습니다. 저장소, 언어, 이벤트 범위가 다른 세 테스트 가이드는 응답에 포함되지 않았습니다. 두 번째 실행에서는 `interface`가 원본 정규식에 일치해 실제 프로젝트 지침이 제공됐고, 다음 모델 응답이 해당 ID를 반환했습니다.

Codex 전체 실행 시간은 첫 실행 18.193초, 두 번째 실행 13.784초였습니다. 원자료는 `functions.exec_command`가 반환한 `wall_time_seconds`이며 값은 `.local/guidebook-validation/logs/codex-command-times.csv`에도 기록했습니다. 이 구간은 Codex 명령 시작부터 종료까지이며 초기 추론, patch, Hook 및 다음 추론을 포함합니다. 계측 래퍼가 기록한 Hook 경과 시간은 각각 1,653.828밀리초와 1,485.739밀리초였습니다.

## 독립 JVM Hook 측정

아래 측정은 실제 Codex 실행과 구분한 보조 검증입니다. 실제 Codex가 보낸 Hook 입력 JSON을 복사하고 `session_id`만 바꿔, 설치된 `post-tool-use.sh`를 독립 프로세스로 호출했습니다. 각 표본은 A 세션 첫 요청 → A 세션 반복 요청 → 별도 B 세션 첫 요청 순서로 실행했습니다. 따라서 첫 번째와 세 번째 요청은 모두 새 세션이며, 두 번째 요청만 첫 요청과 같은 세션을 재사용합니다. 이 세 요청 묶음을 5회 반복했습니다.

측정 시각은 Python `perf_counter_ns`로 Hook 프로세스 시작 직전부터 종료 직후까지 계산했습니다. 따라서 값에는 Hook 셸 스크립트 시작, JVM 시작, YAML 읽기와 범위/일치 처리, SQLite 이력 확인과 기록, 출력이 포함됩니다. 이 값을 YAML 로딩 시간으로 해석하지 않습니다.

| 요청 유형 | 표본 수 | 중앙값 | 범위 | 결과 |
| --- | ---: | ---: | ---: | --- |
| A 세션 첫 요청 | 5 | 1,493.961밀리초 | 1,435.044~1,705.272밀리초 | 매번 원본 지침 ID 제공 |
| 같은 세션 반복 | 5 | 1,475.134밀리초 | 1,340.869~1,697.234밀리초 | 매번 빈 stdout |
| B 세션 첫 요청 | 5 | 1,502.809밀리초 | 1,425.988~1,520.606밀리초 | 매번 원본 지침 ID 재제공 |

범위 필터링 요청 1회는 1,571.188밀리초가 걸렸습니다. 이 요청은 양성 테스트 ID만 반환했고, 저장소·언어·이벤트가 맞지 않는 가이드의 내용은 반환하지 않았습니다. 반복 실행 후 SQLite에는 13개 제공 행과 13개 고유 session이 있었으며, 중복 행은 없었습니다.

각 요청은 새 JVM 프로세스였지만 JVM/OS 캐시를 비운 cold-start 조건은 만들지 않았습니다. 설치 JAR와 파일 시스템 캐시는 요청 간 통제하지 않았습니다. 별도 YAML 로더 계측 API는 사용하지 않았으며 YAML 전용 시간도 측정하지 않았습니다.

## Gradle 검증

오케스트레이터가 다음 순서로 실행했으며 모두 성공했습니다.

| 명령 | 결과 |
| --- | --- |
| `spotlessApply` | 성공, 11초 |
| `test --tests 'com.springkit.agentguidebook.*'` | 성공, 27초; 26 suites, 271 tests, 실패·오류·건너뜀 0 |
| `build` | 성공, 23초; 설치 배포 작업은 최신 상태 |

별도 `check` 명령은 실행하지 않았습니다. `build`의 Gradle 작업 그래프에는 `:check`가 포함됐습니다. SQLite native-access 경고는 테스트 JVM에서만 출력됐고 앱 stdout 결과에는 영향을 주지 않았습니다.

## 제한 사항

- Codex 실행별 inline Hook 설정만 확인했습니다. 사용자 전역 설정의 지속적인 Hook 등록이나 `hooks.example.json` 파일을 Codex가 직접 읽는 방식은 검증하지 않았습니다.
- 직접 JVM 반복 측정은 실제 Codex가 생성한 payload를 바탕으로 한 보조 호출입니다. 같은 세션 반복과 새 세션 분리는 실제 Codex 대화가 아니라 설치 앱의 세션 이력으로 확인했습니다.
- 이번 검증은 지침이 Codex에 전달되고 다음 응답에서 사용할 수 있는지만 확인했습니다. 지침에 따라 설계나 코드가 개선되는지는 별도로 검증하지 않았습니다.
- Codex stderr에는 별도 MCP worker의 HTTP 403 로그가 포함됐지만, 두 Codex 실행과 Guidebook Hook은 성공했습니다.
- Herdr 경로는 실행하지 않았습니다. Codex 직접 실행에서 필요한 연결과 다음 추론 결과를 확인했기 때문입니다.

## 원자료

실행 JSONL, Hook 입력·출력, stderr, SQLite DB와 반복 측정 CSV는 `.local/guidebook-validation/` 아래에 보관했습니다. 이 경로는 Git 제외 영역이며 최종 Git 변경은 이 보고서 한 파일입니다.
