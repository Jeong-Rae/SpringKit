@/home/codespace/.codex/RTK.md

# Task

- [Def] `Task`란 하나의 Goal을 가진 작업 단위로, 배포 가능성을 보장합니다.
- [Def] `Step`이란 Task의 하위 구성 작업으로, 실행 가능성을 보장합니다. Task는 한 개 이상의 Step으로 구성합니다.
- [Must] 전체 Task를 독립적으로 검증 가능한 Step으로 나누고, 각 Step을 검증한 뒤 다음 Step을 진행해야 합니다.
- [Should] 각 Step마다 최소 한 개의 커밋을 남겨야 합니다.

# Git: PR First

`develop` 반영은 원격 GitHub PR의 merge commit만 SoT로 사용해야 합니다. 원격 병합이 끝난 feature 브랜치의 로컬 정리는 `git flow feature finish`만 사용해야 합니다.

## Task 식별자

- [Must] Task 식별자는 사용자가 입력한 값만 그대로 사용해야 합니다.
- [Must] 브랜치 이름은 `<type>/<task-id>` 형식만 사용해야 합니다.
- [Must] PR 제목은 `[<task-id>] <Type>: <description>` 형식만 사용해야 합니다.
- [Must] 커밋 제목은 `<Type>: <description>` 형식을 사용해야 합니다. `Feature`, `Build`, `Fix`, `Refactor`, `Test`, `Docs`, `Chore`와 같은 영문 유형 키워드는 허용합니다.
- [Must] 커밋 제목의 설명과 본문은 한글로 작성해야 합니다.
- [Must] 작성 시 `keyword`는 `키워드`, `dependency`는 `의존성`처럼 가능한 한글 음차와 번역을 우선해야 합니다. `EC2`와 같이 대체할 수 없는 고유명사와 코드 식별자만 영문으로 작성합니다.

## Git Command

Git feature 작업은 다음 Kotlin 스크립트만 사용해 수행해야 합니다. 에이전트가 feature 생성, 최초 PR 게시, 리뷰 반영 게시, 병합 후 정리에 개입하는 모든 경우에도 이 인터페이스만 사용해야 합니다.

```sh
kotlin scripts/git/git-workflow.main.kts start TASK_ID
kotlin scripts/git/git-workflow.main.kts publish \
  "[TASK_ID] Feature: 사용자 프로필 API 추가" \
  --body-file /tmp/pr-body.md
kotlin scripts/git/git-workflow.main.kts update
kotlin scripts/git/git-workflow.main.kts finish TASK_ID
```

- `start`는 현재 브랜치가 최신 `develop`인지 확인한 후 `git flow feature start <task-id>`를 실행해야 합니다.
- `publish`는 PR 본문 파일을 검증하고 `git flow feature publish <task-id>`를 실행한 뒤, `develop` 대상 draft PR을 생성해야 합니다.
- `update`는 현재 feature의 열린 PR을 확인한 뒤, 리뷰 반영 커밋을 `git flow feature publish <task-id>`로 게시해야 합니다.
- `finish`는 PR이 `MERGED`인지 확인한 뒤 `develop`을 fast-forward로 최신화하고 Gradle build를 통과시킨 후 `git flow feature finish <task-id>`를 실행해야 합니다.

## PR Workflow

### 1. Feature 및 PR 생성

1. [Must] feature는 최신 `develop`에서 `start` 명령으로만 생성해야 합니다.
2. [Must] 서로 의존하지 않는 병렬 작업은 `.worktree` 아래의 별도 worktree에서만 수행해야 합니다.
3. [Must] 각 Step의 구현과 검증을 완료하고 커밋해야 합니다.
4. [Must] feature 게시와 draft PR 생성은 `publish` 명령으로만 수행해야 합니다.
5. [Must] PR 대상 브랜치는 `develop`만 사용해야 합니다.

### 2. PR 리뷰 및 반영

1. [Must] PR 리뷰는 draft PR의 diff, CI 결과, 리뷰 의견을 기준으로 수행해야 합니다.
2. [Must] 에이전트는 리뷰 요청을 받으면 `gh pr view`, `gh pr diff`, `gh pr checks`로 현재 상태를 조회하고 결과를 사용자에게 보고해야 합니다.
3. [Must] 에이전트의 코드 변경은 사용자가 리뷰 의견 반영을 요청한 범위에서만 수행해야 합니다.
4. [Must] 리뷰 반영은 동일한 feature 브랜치에서 구현, 관련 Gradle 검증, 커밋 순서로 수행해야 합니다.
5. [Must] 에이전트가 리뷰 반영 커밋을 원격에 게시할 때는 `update` 명령만 사용해야 합니다.
6. [Must] 승인과 CI 통과 여부 확인은 변경 게시 후 다시 수행해야 합니다.
7. [Must] draft PR의 ready 전환과 최종 merge는 사용자만 결정해야 합니다.

### 3. 원격 병합 및 종료

1. [Must] `develop` 반영은 사용자 승인과 CI 통과 후 GitHub 원격 PR의 merge commit 방식으로만 수행해야 합니다.
2. [Must] 에이전트의 원격 PR merge는 사용자가 명시적으로 요청한 경우에만 수행해야 합니다.
3. [Must] `finish` 명령은 원격 PR의 `MERGED` 상태가 확인된 뒤에만 실행해야 합니다.
4. [Must] `finish` 과정은 `develop`의 `git pull --ff-only origin develop`과 `gradlew build`가 모두 성공한 경우에만 완료해야 합니다.
5. [Must] 다음 feature 브랜치는 이전 feature의 `finish`가 성공한 뒤에만 생성해야 합니다.

# Commit and PR Style

- [Must] 커밋과 PR footer에는 프로젝트에서 명시적으로 요구한 항목만 포함해야 합니다.
- [Must] 커밋 제목은 영문 유형 키워드와 한글 설명으로, 본문은 한글로 작성해야 합니다.
- [Must] 일반 용어는 한글 음차와 번역을 우선하고, 대체할 수 없는 고유명사와 코드 식별자만 영문으로 작성해야 합니다.
- [Should] 커밋 메시지는 간결하고 변경 내용을 명확하게 설명해야 합니다.
- [Must] PR 본문은 무엇을 변경했고 왜 변경했는지에 집중해야 합니다.
- [Must] PR 생성 상태는 draft만 사용해야 하며, ready 전환은 사용자만 수행해야 합니다.
- [Must] 에이전트의 원격 PR 병합은 사용자가 명시적으로 요청한 경우에만 수행해야 합니다.

# Build Command

- [Must] 빌드 및 배포 작업은 프로젝트에서 정한 CLI와 명령으로만 수행해야 합니다.
- [Must] JVM 빌드는 Gradle만 사용해야 합니다.
- [Must] build는 clean 없이 증분 빌드로 수행해야 합니다.

```sh
./gradlew build # build 시에 사용하며, clean 하지 않고 증분 빌드함.
./gradlew test --tests "com.example.mypackage.*" # Task 작업 테스트 시 전체 테스트가 아닌 경우 패키지 단위로 Task 범위를 잡고 수행함.
./gradlew spotlessApply # Check 후 직접 수정하는 것이 아니라, 기계적인 전체 Apply를 수행함.
```

# Key Directories

Not yet written

# Specialized Skills

- `$create-pr`: 작업을 검증하고 커밋한 뒤 draft PR을 생성할 때 사용할 수 있습니다.
