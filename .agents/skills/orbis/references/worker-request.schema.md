# Worker Request Schema

Use this exact section order when dispatching a worker.

```text
OBJECTIVE

<The purpose of the logical commit.>


TASK ID

<A stable identifier for this worker assignment.>


ASSIGNMENT

<The bounded implementation responsibility assigned to this worker.>


WORKING DIRECTORY

<The directory in which the worker operates.>


WORKING BRANCH

<The current shared local branch.>


PREPARATION

Read before negotiation:
- <authoritative repository path>

Known facts:
- <settled fact or decision>


OWNERSHIP

Owned:
- <writable path or area>

Read-only dependencies:
- <read-only path or area>

Do not modify:
- <excluded path or area>


ROLE

<The semantic responsibility this worker owns within the objective.>


REPORT CONTRACT

Follow:
- .agents/skills/orbis/references/worker-report.md
- .agents/skills/orbis/references/worker-report.schema.md
- .agents/skills/orbis/references/worker-negotiation.md
- .agents/skills/orbis/references/worker-negotiation.schema.md
```
