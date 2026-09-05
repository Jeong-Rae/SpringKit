# Task와 Step

- [Def] `Task`는 하나의 Goal을 가진 작업 단위이며, 배포 가능성을 보장합니다.
- [Def] `Step`은 Task를 구성하는 작업 단위이며, 실행 가능성을 보장합니다. Task는 한 개 이상의 Step으로 구성합니다.
- [Must] 전체 Task를 독립적으로 검증할 수 있는 Step으로 나눠야 합니다. 각 Step을 검증한 뒤 다음 Step을 진행해야 합니다.
- [Should] 각 Step마다 한 개 이상의 커밋을 남겨야 합니다.

# Git: PR 우선

`develop` 반영에는 원격 GitHub PR의 병합 커밋만 SoT(Single source of truth, 단일 기준 정보)로 사용해야 합니다. 원격에서 병합한 feature 브랜치는 로컬에서 `git flow feature finish`로만 정리해야 합니다.

## Task 식별자

- [Must] 사용자가 입력한 Task 식별자를 변경하지 않고 사용해야 합니다.
- [Must] 브랜치 이름은 `<type>/<task-id>` 형식만 사용해야 합니다.
- [Must] PR 제목은 `[<task-id>] <Type>: <description>` 형식만 사용해야 합니다.
- [Must] 커밋 제목은 `<Type>: <description>` 형식을 사용해야 합니다. 영문 유형 키워드는 `Feature`, `Build`, `Fix`, `Refactor`, `Test`, `Docs`, `Chore` 등을 사용할 수 있습니다.

## Git 명령

Git feature 작업에는 다음 Kotlin 스크립트만 사용해야 합니다. 에이전트가 feature 생성, 최초 PR 게시, 리뷰 반영 게시, 병합 후 정리에 참여할 때도 이 스크립트만 사용해야 합니다.

```sh
kotlin scripts/git/git-workflow.main.kts start TASK_ID
kotlin scripts/git/git-workflow.main.kts publish \
  "[TASK_ID] Feature: 사용자 프로필 API 추가" \
  --body-file /tmp/pr-body.md
kotlin scripts/git/git-workflow.main.kts update
kotlin scripts/git/git-workflow.main.kts finish TASK_ID
```

- `start`는 현재 브랜치가 최신 `develop`인지 확인한 뒤 `git flow feature start <task-id>`를 실행해야 합니다.
- `publish`는 PR 본문 파일을 검증한 뒤 `git flow feature publish <task-id>`를 실행해야 합니다. 이어서 `develop` 대상 draft PR을 생성해야 합니다.
- `update`는 현재 feature의 열린 PR을 확인한 뒤 `git flow feature publish <task-id>`로 리뷰 반영 커밋을 게시해야 합니다.
- `finish`는 PR의 `MERGED` 상태를 확인해야 합니다. 이어서 `develop`을 fast-forward로 최신화하고 Gradle build를 통과한 뒤 `git flow feature finish <task-id>`를 실행해야 합니다.

## PR 작업 흐름

### 1. Feature 및 PR 생성

1. [Must] feature는 최신 `develop`에서 `start` 명령으로만 생성해야 합니다.
2. [Must] 서로 의존하지 않는 작업을 병렬로 수행할 때는 `.worktree` 아래의 별도 worktree를 사용해야 합니다.
3. [Must] 각 Step의 구현과 검증을 완료한 뒤 커밋해야 합니다.
4. [Must] feature 게시와 draft PR 생성에는 `publish` 명령만 사용해야 합니다.
5. [Must] PR 대상 브랜치는 `develop`만 사용해야 합니다.

### 2. PR 리뷰 및 반영

1. [Must] draft PR의 diff, CI 결과, 리뷰 의견을 기준으로 PR을 리뷰해야 합니다.
2. [Must] 에이전트는 리뷰 요청을 받으면 `gh pr view`, `gh pr diff`, `gh pr checks`로 현재 상태를 조회해야 합니다. 조회 결과는 사용자에게 보고해야 합니다.
3. [Must] 에이전트가 GitHub에 작성하는 모든 PR 및 Issue 댓글과 답글은 `[Agent]` 접두사로 시작해야 합니다.
4. [Must] 에이전트는 사용자가 반영을 요청한 리뷰 의견의 범위에서만 코드를 변경해야 합니다.
5. [Must] 동일한 feature 브랜치에서 리뷰 반영을 구현하고 관련 Gradle 검증을 마친 뒤 커밋해야 합니다.
6. [Must] 에이전트가 리뷰 반영 커밋을 원격에 게시할 때는 `update` 명령만 사용해야 합니다.
7. [Must] 변경을 게시한 뒤 승인과 CI 통과 여부를 다시 확인해야 합니다.
8. [Must] draft PR의 ready 전환과 최종 병합은 사용자만 결정해야 합니다.

### 3. 원격 병합 및 종료

1. [Must] `develop`에는 사용자 승인과 CI 통과 후 GitHub 원격 PR의 병합 커밋으로만 반영해야 합니다.
2. [Must] 에이전트는 사용자가 명시적으로 요청한 경우에만 원격 PR을 병합해야 합니다.
3. [Must] 원격 PR의 `MERGED` 상태를 확인한 뒤에만 `finish` 명령을 실행해야 합니다.
4. [Must] `finish`는 `develop`의 `git pull --ff-only origin develop`과 `gradlew build`가 모두 성공해야 완료할 수 있습니다.
5. [Must] 이전 feature의 `finish`가 성공한 뒤에만 다음 feature 브랜치를 생성해야 합니다.

# 커밋과 PR 형식

- [Must] 커밋과 PR의 푸터(Footer)에는 프로젝트에서 명시적으로 요구한 항목만 포함해야 합니다.
- [Must] PR은 draft 상태로만 생성해야 합니다. ready 전환은 사용자만 수행해야 합니다.
- [Must] 에이전트는 사용자가 명시적으로 요청한 경우에만 원격 PR을 병합해야 합니다.

# 빌드 명령

- [Must] 빌드와 배포에는 프로젝트에서 정한 CLI(Command line interface)와 명령만 사용해야 합니다.
- [Must] JVM 빌드에는 Gradle만 사용해야 합니다.
- [Must] build는 clean 없이 증분 방식으로 수행해야 합니다.

```sh
./gradlew build # build 시에 사용하며, clean 하지 않고 증분 빌드함.
./gradlew test --tests "com.example.mypackage.*" # Task 작업 테스트 시 전체 테스트가 아닌 경우 패키지 단위로 Task 범위를 잡고 수행함.
./gradlew spotlessApply # Check 후 직접 수정하는 것이 아니라, 기계적인 전체 Apply를 수행함.
```

# 주요 디렉터리

아직 작성하지 않았습니다.

# 전문 스킬

- [Must] 모든 응답과 작성하는 글에 `$writing-guide`를 사용해야 합니다. 기술 문서를 작성, 수정, 검토할 때는 스킬이 정한 문서 작성 기준도 적용해야 합니다.
- `$create-pr`: 작업을 검증하고 커밋한 뒤 draft PR을 생성할 때 사용할 수 있습니다.
