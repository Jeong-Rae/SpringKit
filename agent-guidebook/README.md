# Agent Guidebook

## 준비

필요한 도구는 Java 25, Bash, Gradle Wrapper, Codex CLI입니다. SpringKit 루트에서 JVM 앱의 배포본을 준비합니다.

```sh
cd agent-guidebook
./gradlew installDist
cd ..
```

배포본은 `tools/agent-guidebook/build/install/agent-guidebook`에 생성됩니다. 실행 앱은 `bin/agent-guidebook`, Hook 진입점은 `hooks/post-tool-use.sh`입니다.

## 사용

Codex는 SpringKit 프로젝트 루트에서 실행합니다.

```sh
codex
```

프로젝트 `.codex/config.toml`은 `apply_patch` 이후 Agent Guidebook Hook을 호출합니다. 최초 실행 때 `/hooks`에서 이 Hook 정의를 신뢰해야 합니다. 프로젝트 설정 등록은 Hook 신뢰를 대신하지 않습니다. 자세한 내용은 [Codex Hooks 문서](https://learn.chatgpt.com/docs/hooks)를 참고합니다.

프로젝트별 지침은 `.agents/guides` 아래 `guideline.yaml` 파일로 준비합니다. Hook은 프로젝트 Git `origin`과 지침 범위가 일치할 때 관련 지침을 제공합니다.

같은 Session에는 각 지침을 한 번만 제공합니다. 제공 이력은 `~/.adaptive-guidebook/adaptive-guidebook.db`에 기록합니다.
