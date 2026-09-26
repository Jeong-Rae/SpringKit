# Worker 요청 스키마

Worker를 생성할 때 다음 순서와 형식을 그대로 사용합니다.

```text
OBJECTIVE

<논리적 커밋 전체가 달성해야 하는 목표>


TASK ID

<이 Worker 책임을 식별하는 안정적인 ID>


ASSIGNMENT

<이 Worker가 맡는 구현 책임>


WORKING DIRECTORY

<Worker가 작업할 디렉터리>


WORKING BRANCH

<현재 공유 로컬 브랜치>


PREPARATION

Read before questions:
- <질문 전에 읽을 저장소 기준 자료>

Known facts:
- <이미 확인하거나 확정한 사실>


OWNERSHIP

Owned:
- <수정할 수 있는 경로 또는 영역>

Read-only dependencies:
- <읽기 전용 경로 또는 영역>

Do not modify:
- <변경하지 않는 경로 또는 영역>


ROLE

<전체 목표에서 이 Worker가 맡는 역할>


REPORT CONTRACT

Follow:
- .agents/skills/orbis/references/worker-question.md
- .agents/skills/orbis/references/worker-question.schema.md
- .agents/skills/orbis/references/worker-report.md
- .agents/skills/orbis/references/worker-report.schema.md
```
