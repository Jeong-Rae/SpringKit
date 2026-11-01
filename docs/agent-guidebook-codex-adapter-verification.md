# Codex 입출력 Adapter 검증 기록

검증 범위와 시나리오는 [도메인 분석과 테스트 목록](agent-guidebook-codex-adapter-domain-review.md)을 따릅니다. 오케스트레이터가 목록과 범위를 확정하고 Worker가 테스트와 구현을 작성합니다. Worker 보고와 별도로 실제 diff와 Gradle XML을 확인합니다.

## 계약 작성

| 책임 | 검증 결과 | 판단 |
| --- | --- | --- |
| 기존 `PresentationSession` | 16개 성공, 실패·오류 0개 | 선별·기록·인자·상태 계약 재사용 |
| 기존 `SessionId`, `GuidelineId`, 요청 Model | 9개 성공, 실패·오류 0개 | 새 값 객체 없이 기존 계약 재사용 |
| UseCase의 Session별 제공 | 5개 실행, 5개 `NotImplementedError`, 오류 0개 | 컴파일 가능한 지정 Stub에서 RED |
| UseCase의 실행 경계 포함 | 12개 실행, 12개 실패, 오류 0개 | 예외 검증의 실패 원인도 같은 Stub임을 확인 |
| Codex 이벤트와 입력 검증 | 23개 실행, 23개 실패, 오류 0개 | 모든 실패에서 입력 Adapter Stub 확인 |
| Codex 변경 경로 포함 | 35개 실행, 35개 실패, 오류 0개 | 실제 디렉터리 제외 사례를 보완한 뒤 같은 Stub의 RED 확인 |
| Codex Context 출력 | 3개 실행, 3개 실패, 오류 0개 | 출력 Adapter Stub에서 RED |
| 실행 앱의 스트림과 실패 | 7개 실행, 7개 실패, 오류 0개 | JSON fixture의 newline을 수정한 뒤 run Stub의 RED 재확인 |
| 실제 배포 앱 프로세스 | 4개 실행, 4개 실패, 오류 0개 | 각 프로세스의 stderr에서 MainKt Stub 확인 |

분석은 `251a305`, 제공 행위 계약은 `c9e2788`, 실행·실패 계약은 `dca703e`에서 확정했습니다.
입력 이벤트 계약과 JSON 의존성은 `3528a99`에서 확정했습니다.
변경 경로 계약은 `4d9d176`에서 확정했습니다.
Context 출력 계약은 `cdaa000`에서 확정했습니다.
앱 계약은 `5434ae8`에서 확정했습니다. 테스트 검토에서 raw string의 이중 이스케이프가 실제 줄바꿈을 만들지 않는 문제를 발견했습니다. JSON builder로 수정하고 decode 결과를 확인한 뒤 재검증했습니다.
실제 배포 프로세스 계약은 `ed88df3`에서 확정했습니다.

새 계약은 UseCase 12개, 입력 35개, 출력 3개, 앱 7개, 프로세스 4개로 총 61개입니다. 모든 테스트의 소스가 컴파일되고 지정한 Stub에서 RED임을 확인한 뒤 실제 구현을 시작합니다.

검증 중 생성한 원본 로그와 XML은 `/tmp/sk-39-verification/`에 보관합니다. 이 임시 경로는 이후 환경에서 유지되지 않을 수 있습니다. 이 문서에는 책임별 결과와 커밋을 남깁니다.

`spotlessApply`를 먼저 실행한 뒤 책임별 `test --tests`를 실행합니다. RED에서 컴파일 오류, 다른 실패 원인과 기존 계약 파손을 의도한 실패로 취급하지 않습니다. 실제 구현은 모든 새 계약과 호출식을 확정한 뒤 시작합니다.

## 구현 검증

| 책임 | 결과 | 구현 커밋 |
| --- | --- | --- |
| 입력·출력 Adapter | 새 계약 38개와 기존 wrapper 계약 12개 성공 | `c7ef5bf` |
| VSA UseCase와 기존 서비스·SQLite 통합 | 108개 성공, 실패·오류·생략 0개 | `f126802` |
| 실행 앱과 생산 배포 프로세스 | 앱 7개와 프로세스 4개 성공 | `96c64d0` |

실제 앱의 첫 검증에서는 앱 계약 7개와 프로세스 계약 3개가 성공했고, 정상 stderr 계약 1개가 실패했습니다. 자식 프로세스의 stderr에 SLF4J provider 부재와 Java 25의 SQLite native access 경고가 있었습니다. 배포 앱에 기존 API와 같은 버전의 `slf4j-nop:2.0.18`을 runtime dependency로 추가하고 `--enable-native-access=ALL-UNNAMED`를 기본 실행 옵션으로 지정합니다. [SLF4J provider 설명](https://www.slf4j.org/manual.html)을 참고합니다.

실행 진입점은 stdout 쓰기 실패가 앱까지 전파되도록 descriptor 기반 스트림을 주입합니다. `PrintStream`이 쓰기 오류를 감추는 경로를 피하고, 실제 배포 앱의 stdout을 `/dev/full`로 연결해 종료 코드와 저장 이력을 확인합니다.

`/dev/full` smoke에서 종료 코드 1과 `No space left on device` 진단을 확인했습니다. 임시 사용자 홈의 SQLite에는 `dev-full-smoke-session + full-stdout-rule` 행 1개가 남았습니다. 따라서 commit 뒤 출력이 실패하는 경우에 저장 이력을 되돌리거나 자동 재전송하지 않는 기존 계약을 유지합니다.

## 최종 Gradle 검증

2026년 11월 1일에 오케스트레이터가 `agent-guidebook`에서 다음 명령을 순서대로 실행했습니다.

```sh
./gradlew spotlessApply
./gradlew test
./gradlew build
```

23개 테스트 클래스의 Scenario 284개가 모두 성공했습니다. 실패, 오류와 생략은 각각 0개입니다. 증분 `build`도 성공했고 테스트 작업은 다시 실행하지 않고 기존 성공 결과를 사용했습니다. `clean`과 별도 `check` 명령은 실행하지 않았습니다. 최종 XML은 `/tmp/sk-39-verification/final-green/`에 보관했습니다.

## 실제 Codex 검증

Codex CLI 0.166.0의 임시 Git 프로젝트에서 생산 `hooks/post-tool-use.sh`와 실제 `installDist` 앱을 연결했습니다. 프로젝트와 앱의 SQLite 사용자 홈은 공백이 포함된 별도 임시 경로입니다. 초기 프롬프트에는 알려진 소스의 `before`를 `after`로 바꾸는 요청만 주고, 고유 토큰은 프로젝트의 지침 본문에만 저장했습니다.

CLI inline `hooks.PostToolUse` 설정의 matcher는 `^apply_patch$`, timeout은 30초입니다. `--ignore-user-config --strict-config --ephemeral --json`, `--sandbox workspace-write`, `--ask-for-approval never`와 검토한 생산 Hook에 한정된 invocation의 `--dangerously-bypass-hook-trust`를 사용했습니다. 프로젝트·전역 Codex 설정과 trust 파일, `HOME` 및 `CODEX_HOME`은 변경하지 않았습니다. 앱의 `user.home`만 자식 JVM 옵션으로 분리했습니다.

| 관찰 대상 | 결과 |
| --- | --- |
| Codex 종료 코드 | 0 |
| 소스 변경 | `src/Probe.kt`의 `before`가 `after`로 변경 |
| Git 변경 범위 | `src/Probe.kt` 한 파일 |
| JSONL 도구 실행 | 소스 변경 한 건, shell command나 다른 도구 실행 항목 없음 |
| Presentation 이력 | Codex thread ID와 일치하는 Session에 `codex-runtime-canary` 행 1개 |
| 초기 프롬프트 | 지침의 고유 토큰 없음 |
| 최종 모델 응답 | 지침 본문의 고유 토큰과 정확히 일치 |

오케스트레이터가 최종 응답, 프롬프트, JSONL, 변경 파일과 SQLite를 직접 대조했습니다. 따라서 기존 App Stub canary와 구분하여 실제 검색·기록 앱의 Context가 다음 모델 입력에 도달했음을 확인했습니다.

실행 launcher는 `/tmp/sk-39-codex-runtime-probe/run-probe.sh`입니다. 실제 명령은 `evidence/invocation.txt`, 초기 프롬프트는 `initial-prompt.txt`, Codex 이벤트는 `codex.jsonl`, 모델 응답은 `final-message.txt`, 원시 stderr는 `codex.stderr`, 이력 요약은 `presentation-rows.txt`에 있습니다. 이 파일들은 `/tmp/sk-39-codex-runtime-probe/evidence/`에 보관했습니다. 임시 파일과 실제 세션 메타데이터는 저장소에 게시하지 않습니다.

## 한계

Codex 전체 stderr에는 별도 `rmcp` worker의 HTTP 403 응답이 있었습니다. Codex 종료와 지침 Context 전달은 성공했으므로 이를 Hook 실패로 판단하지 않습니다. 정상 앱 자체의 빈 stderr는 프로세스 계약 테스트로 검증했으며, 전체 Codex 환경의 stderr가 비었다고 주장하지 않습니다.

실제 Codex 실행은 CLI inline Hook 설정으로 검증했습니다. 예제 `hooks.json` 파일의 발견과 로딩은 검증하지 않았습니다. 현재 GitHub workflow는 수동 실행용이며 job도 비활성화되어 있어 자동 CI 결과는 없습니다. 최종 로컬 Gradle 검증과 실제 실행 증거를 사용합니다.
