# Agent Guidebook POC Technical Notes

이 문서는 `Agent Guidebook POC` 개념 문서에서 의도적으로 생략한 구현 세부사항을 정리합니다.

## Guideline 파일 스키마

`guideline.yaml`은 다음 구조를 사용합니다.

```yaml
id: service-depends-on-port

scope:
  repositories:
    - acme/backend-*
  languages:
    - kotlin

when:
  - file_changed

match:
  any:
    - regex:
        pattern: 'RepositoryImpl'

content: |
  Services should depend on repository abstractions.
```

`match.any`에 포함된 Regex 중 하나가 일치하면 해당 Guideline은 Match됩니다.

하나의 Regex가 일치한 이후에는 같은 Guideline의 나머지 Regex 평가를 종료할 수 있습니다.

## Search API 계약

Codex Adapter는 Agent Guidebook에 다음 요청을 전달합니다.

```json
{
  "sessionId": "codex-session-id",
  "cwd": "/workspace/backend-order",
  "filepaths": [
    "/workspace/backend-order/src/main/kotlin/OrderService.kt"
  ]
}
```

`cwd`와 `filepaths`는 모두 절대 경로입니다.

Agent Guidebook은 Presentation 대상의 `id`와 `content`를 반환합니다.

```json
[
  {
    "id": "service-depends-on-port",
    "content": "Services should depend on repository abstractions."
  }
]
```

## Filepath 정규화

Codex `tool_response`에서 추출한 경로는 다음 규칙으로 절대 경로로 변환합니다.

```text
path 추출
    ↓
absolute path
    ├─ yes → 그대로 사용
    └─ no  → cwd.resolve(path)
    ↓
normalized absolute filepath
```

검색 시점에 존재하는 파일을 Search Request에 포함합니다.

삭제된 파일은 POC 검색 대상에서 제외합니다.

## Repository Identity 계산

Repository scope를 평가할 때 Git `origin`을 기준으로 Repository Identity를 계산합니다.

```bash
git remote get-url origin
```

SSH와 HTTPS URL을 모두 `owner/repository` 형태로 정규화합니다.

```text
git@github.com:acme/backend-order.git
https://github.com/acme/backend-order.git

            ↓

acme/backend-order
```

최종 비교 단위는 `owner/repository`입니다.

## Matching 실행

각 파일의 source는 하나의 Search에서 한 번 읽고, 해당 파일에 적용되는 Guideline이 같은 source를 사용합니다.

```text
file
 ↓
source 1회 읽기
 ↓
ApplicableGuideline[]
 ↓
각 Guideline의 Regex 평가
 ↓
MatchedGuideline[]
```

현재 요청에서 Match되고 해당 Session에 Presentation 이력이 없는 Guideline은 모두 Presentation 대상이 됩니다.

## Presentation 저장

Presentation 상태는 SQLite에 저장합니다.

```sql
CREATE TABLE presentation (
    session_id TEXT NOT NULL,
    guideline_id TEXT NOT NULL,
    presented_at TEXT NOT NULL,
    PRIMARY KEY (session_id, guideline_id)
);
```

`session_id + guideline_id`가 Presentation의 고유성 기준입니다.

`presented_at`은 UTC 기준 RFC 3339 형식으로 저장합니다.

예:

```text
2026-09-23T09:41:18Z
```

Agent Guidebook 프로세스는 요청마다 새로 시작하며, 요청 사이의 Presentation 상태는 SQLite가 유지합니다.

## 내부 패키지 책임

Agent Guidebook은 Single Module로 구성하고 package 수준에서 책임을 분리합니다.

```text
models
→ 공개 Request, Response, Value Object

services
→ present(request) 등의 공개 행위

client
→ AgentGuidebookClient 진입점과 wiring

core
→ Scope Filtering, Regex Matching

storage
→ YAML, FileSystem, Git, SQLite

errors
→ 호출자가 처리할 오류 모델

codex
→ PostToolUse / additionalContext Adapter
```

## 실행 방식

하나의 Codex Hook 호출이 하나의 Agent Guidebook 실행에 대응합니다.

```text
PostToolUse
    ↓
JVM Process Start
    ↓
Guideline load
    ↓
Match
    ↓
Presentation
    ↓
Response
    ↓
Process Exit
```

POC 기능 검증은 JVM에서 수행합니다.

GraalVM Native Image는 빌드 결과를 실행 Artifact로 신뢰합니다.

## Local Guideline Source

POC의 Guideline Source는 Codex Hook이 실행된 프로젝트 자체입니다.

```text
cwd
 ↓
.agents/guides
 ↓
**/guideline.yaml
```

Guidebook Root는 다음 규칙으로 결정합니다.

```text
guidebookRoot = cwd.resolve(".agents/guides")
```

중앙 Guidebook Server나 별도 동기화 과정보다 먼저, 프로젝트 로컬 Guideline이 Codex 작업에 실제로 주입되는 경로를 검증합니다.
