# Worker Request Contract

Compose every worker dispatch with the sections below. Keep the request self-contained for a worker that does not inherit the orchestrator's conversation.

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

<Authoritative material to read and known facts to understand before negotiation.>


OWNERSHIP

<Owned write areas, read-only dependencies, and explicitly excluded areas.>


ROLE

<The responsibility this worker owns within the objective.>


REPORT CONTRACT

<The worker-report.md reference to follow for the terminal response.>
```

## OBJECTIVE

State the behavior change that the whole logical commit must achieve. Several workers may share the same objective.

Do not replace the objective with a file list.

## TASK ID

Give the assignment a stable identifier that the orchestrator can correlate with negotiation, follow-up, and final report messages.

## ASSIGNMENT

Describe the concrete implementation responsibility owned by this worker. Describe the required outcome and important constraints without prescribing irrelevant implementation details.

## WORKING DIRECTORY

State the worker's effective working directory.

## WORKING BRANCH

State the current branch. Workers may inspect the branch but must follow the Skill's Git mutation restrictions.

## PREPARATION

List what the worker must know before negotiation.

Prefer direct repository references for authoritative material:

```text
Read before negotiation:
- AGENTS.md
- module/AGENTS.md
- path/to/RelevantService.java
- path/to/RelevantServiceTest.java

Known facts:
- The public API remains unchanged.
- The existing transaction boundary is authoritative.
- Another worker owns path/to/SharedAdapter.java.
```

Use known facts only for decisions already established by the orchestrator. Do not paraphrase repository rules when the worker can read their source directly.

## OWNERSHIP

Describe authority explicitly:

```text
Owned:
- path/to/Implementation.java
- path/to/ImplementationTest.java

Read-only dependencies:
- path/to/PublicContract.java

Do not modify:
- path/to/OtherWorkerFile.java
- build configuration
```

Ownership is a boundary, not a prediction of which files will necessarily change.

## ROLE

State the responsibility represented by the assignment. Keep it distinct from the concrete task wording.

## REPORT CONTRACT

Point the worker to:

```text
.agents/skills/luna-coding-workers/references/worker-report.md
```

The worker must also follow `.agents/skills/luna-coding-workers/references/worker-negotiation.md` before writing.
