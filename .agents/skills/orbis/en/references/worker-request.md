# Worker Request

Compose one self-contained request for a worker that does not inherit the orchestrator conversation.

Read [Worker Request Schema](worker-request.schema.md) for the exact message shape.

The orchestrator manages the mapping between internal task names and Worker IDs. Do not include task-management information in the worker request.

## Field Semantics

### OBJECTIVE

State the behavior change that the whole logical commit must achieve. Several workers may share the same objective.

### ASSIGNMENT

Describe the concrete implementation responsibility owned by this worker. State the required outcome and important constraints without prescribing irrelevant implementation details.

### WORKING DIRECTORY

State the worker's effective working directory.

### WORKING BRANCH

State the current branch. The worker may inspect the branch but must follow the Skill's Git mutation restrictions.

### PREPARATION

List authoritative repository material and established facts the worker must read before asking questions.

Prefer direct repository references. Use known facts only for decisions already established by the orchestrator.

### OWNERSHIP

Define writable areas, read-only dependencies, and excluded areas. Ownership is a boundary rather than a prediction of which files will change.

### ROLE

State the semantic responsibility represented by the assignment. Keep it distinct from the concrete task wording.

### REPORT CONTRACT

Point the worker to the worker question and final-report protocols and schemas.
