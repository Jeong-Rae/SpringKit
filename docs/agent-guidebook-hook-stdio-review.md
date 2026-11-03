# Codex Hook 입출력 책임 분리

목표는 `CodexHookApplication`의 스트림 처리를 Hook 전용 Concrete Port인 `CodexHookInput`과 `CodexHookOutput`에 모으는 것입니다. Application은 요청 수신, 기존 UseCase 실행, 결과 제공과 종료 코드 결정만 조율합니다. 도메인·UseCase·입출력 Adapter의 규칙은 변경하지 않습니다.

## 기존 책임과 재사용

현재 Application은 stdin 전체 읽기, UTF-8 변환, JSON Adapter 호출, stdout 쓰기, stderr 진단과 flush를 직접 수행합니다. 기존 입력 Adapter는 요청 변환과 경로 검증을, 출력 Adapter는 JSON 렌더링을 이미 담당합니다. 이 행위는 그대로 재사용합니다. 새 도메인 객체나 인터페이스, 범용 Stdio 계층은 추가하지 않습니다.

| 객체 | 생성자 의존성 | 행위와 계약 |
| --- | --- | --- |
| `CodexHookInput` | `input: InputStream`, `inputAdapter: CodexPostToolUseInputAdapter` | `read(): GuidelinePresentationRequest?`. EOF까지 읽고 UTF-8로 변환하여 Adapter에 전달. 처리 제외는 null, 파싱·읽기 실패는 원래 예외 전파 |
| `CodexHookOutput` | `stdout: OutputStream`, `stderr: OutputStream`, `outputAdapter: CodexPostToolUseOutputAdapter` | `present(List<MatchedGuideline>)`. 렌더링 결과가 null이면 쓰기·flush 생략. 그 외에는 UTF-8 응답을 stdout에 추가 줄바꿈 없이 쓰고 flush. 쓰기·flush 예외 전파 |
| `CodexHookOutput` | 같은 의존성 | `diagnose(String)`. 메시지에 `\n`을 붙여 stderr에 UTF-8로 쓰고 flush. 기존과 같이 진단 중 발생한 Exception은 내부에서 처리 |
| `CodexHookApplication` | 기존 `presentGuidelines` 함수 | `run(input: CodexHookInput, output: CodexHookOutput): Int`. 입력 null과 정상 출력은 0, InvalidCodexHookInputException은 64, 그 외 Exception은 1 |

스트림은 호출자가 소유합니다. 입출력 객체는 정상·생략·실패 경로 모두에서 스트림을 닫지 않습니다. 정상 응답과 진단의 출력 채널은 분리하지만 두 행위는 하나의 Output 객체에 둡니다. 예외 원인 체인의 메시지 순서, 중복 제거와 빈 메시지의 클래스명 대체는 Application의 기존 `exceptionMessages()`를 유지합니다.

`Main.kt`에서 새 객체와 기존 Adapter를 조립합니다. stdin은 `System.in`을 사용합니다. stdout과 stderr는 기존 `FileOutputStream(FileDescriptor.out/err)` 연결을 유지합니다. 이는 실제 표준 출력 쓰기 실패가 `PrintStream`에 감춰지지 않고 종료 코드 1로 전달되게 하는 기존 계약입니다. 새 API로 Application을 호출하고 이전 raw stream 실행 메서드의 호환 overload는 추가하지 않습니다.

## 오케스트레이터가 확정한 테스트

새 도메인 규칙은 없으므로 기존 도메인 계약을 유지하고 기술적 입출력 책임을 검증합니다. Application 계약부터 작성하고 이후 Concrete Port 계약을 작성합니다. 모든 계약은 컴파일 가능한 Stub에서 지정한 NotImplementedError로 실패함을 확인한 뒤 실제 구현을 시작합니다.

### Application

기존 7개 시나리오의 의미를 유지하며 새 객체 호출로 조정합니다. 정상 JSON 출력, 무관한 이벤트의 UseCase 미실행, 새 지침 없는 출력 생략, 잘못된 입력 64, 검색·저장·stdout 쓰기 실패 1을 포함합니다.

다음 7개 시나리오를 추가합니다.

- stdin 읽기 실패: UseCase 미실행, 빈 stdout, 진단과 종료 코드 1
- stdout flush 실패: 진단과 종료 코드 1
- 잘못된 입력에서 stderr 쓰기 실패와 flush 실패: 각각 종료 코드 64 유지
- 실행 실패에서 stderr 쓰기 실패와 flush 실패: 각각 종료 코드 1 유지
- 중복·빈 메시지가 포함된 원인 체인: 기존 순서·중복 제거·클래스명 대체와 진단 줄바꿈 유지

### Input

- 나누어 읽히는 UTF-8 입력을 EOF까지 수신하고 정상 요청으로 변환
- 무관한 이벤트에서 null 반환
- 잘못된 JSON의 입력 예외와 원인 전파
- 읽기 실패의 동일 IOException 전파

ByteArrayInputStream과 필요한 최소 실패 스트림을 사용합니다. 정상·실패 경로에서 호출자 스트림이 닫히지 않음도 확인합니다.

### Output

- 정상 응답의 UTF-8 바이트 보존, stdout만 사용하고 쓰기 뒤 flush
- 렌더링 null에서 두 채널의 출력과 flush 생략
- stdout 쓰기 실패와 flush 실패의 동일 예외 전파
- 진단 메시지와 줄바꿈의 UTF-8 바이트 보존, stderr만 사용하고 쓰기 뒤 flush
- stderr 쓰기 실패와 flush 실패를 각각 내부에서 처리

총 7개 시나리오에서 ByteArrayOutputStream과 최소 실패 스트림을 사용하고 호출자 리소스의 소유권을 확인합니다. 실패 시 진단이 stdout으로 우회하지 않음도 검증합니다.

## 진행 순서와 검증

1. 분석과 계약 목록 확정
2. Application 계약 14개와 컴파일에 필요한 최소 Stub·진입점 연결 작성, 지정한 RED 확인
3. Input 4개·Output 7개 계약 작성, 지정한 RED 확인
4. Input·Output 구현, 11개 계약 GREEN 확인
5. Application 조율 구현, 관련 Codex·설치 프로세스 회귀와 증분 build 검증

각 범위를 검증하고 커밋한 뒤 다음 범위로 진행합니다. 모든 신규 계약을 작성한 뒤 하위 구성 요소부터 구현합니다. 검증 명령은 `spotlessApply` → 범위 `test` → 최종 증분 `build` 순서를 따릅니다. 오케스트레이터가 실제 diff와 테스트 XML을 독립 확인하며, 정상 출력에 추가 줄바꿈이나 진단이 섞이지 않는지 확인합니다.

## 검증 결과

2026년 11월 2일 오케스트레이터가 실제 diff와 XML을 확인했습니다.

| 범위 | 결과 | 커밋 |
| --- | --- | --- |
| Application 계약 | 전체 소스 컴파일 성공, 14개 모두 Application Stub의 NotImplementedError로 실패 | `1b7b7bc` |
| Input·Output 계약 | 11개 모두 각 Port Stub의 NotImplementedError로 실패 | `73e79b7` |
| Input·Output 구현 | 11개 성공, 실패·오류·생략 각각 0개 | `dc0131e` |
| Application 연결 | Codex·설치 프로세스 82개 성공 | `095c18c` |
| 전체 회귀와 증분 build | 26개 클래스의 305개 성공, 실패·오류·생략 각각 0개, build 성공 | `095c18c` |

UTF-8 응답 바이트와 진단 줄바꿈, 쓰기 뒤 flush, 출력 생략과 호출자 리소스의 미종료를 확인했습니다. 입력·stdout의 원래 IOException은 그대로 전파되고, stderr 쓰기·flush 실패에서도 종료 코드 64와 1이 유지됩니다. 기존 예외 원인 체인 처리와 종료 코드 상수는 원문 그대로입니다. Application에는 스트림·인코딩·Adapter 접근이 남아 있지 않으며, 기존 도메인·UseCase·Adapter·Hook 구현은 이번 변경에서 수정하지 않았습니다.

설치 전체를 별도 경로로 복사한 기존 프로세스 테스트도 통과했습니다. 정상 제공, 같은 Session의 중복 제외, 다른 Session 분리, 빈 대상의 DB 미생성, 입력·저장 오류와 설치 파일 보존을 검증합니다. 실행 진입점의 descriptor 연결도 유지합니다.

검증 로그와 XML은 `/tmp/sk-39-hook-stdio/`에 보관했습니다. 이 임시 경로는 이후 환경에서 유지되지 않을 수 있습니다. 테스트 JVM에는 기존 SQLite native access 경고가 있었으나 빌드는 성공했습니다. 앱 launcher의 기존 native access 옵션과 실제 프로세스의 출력 계약은 유지합니다.
