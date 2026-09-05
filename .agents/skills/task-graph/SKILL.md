---
name: task-graph
description: Turn product requirements and project context into deployable Tasks, sequential Steps, and a valid Task DAG. Use when creating or validating Task Cards, issuing a SpringKit Task ID, adding direct Task dependencies, or checking Task state.
---

# Task Graph

Create and manage canonical JSON Task Cards in `tasks/`.

## Goal

Turn product requirements and project context into a validated Task Graph of independently deployable Tasks and sequential, verifiable Steps.

## Core Model

- A Task is a deployable unit of work organized around exactly one Goal. It must be independently deployable after its direct prerequisites are complete.
- A Step is a sequential, independently verifiable unit of work within one Task. It is not a commit, branch, pull request, or DAG node.
- The Task Graph contains only Tasks. `depends_on` contains direct prerequisites only and must not contain cycles, self-references, duplicates, or transitive dependencies.

Every Task and Step needs at least one Verification. Do not start a later Step until the preceding Step is verified and marked `done`. A Task can be marked `done` only after all Steps and Task-level Verification succeed.

Use [the canonical Task Card example](references/task-card.example.json) when creating or editing a Card. Preserve a user-provided Task ID.

## Scripts

Resolve this Skill directory and invoke its scripts directly with Node.js. The scripts use only Node.js standard modules and require no package installation.

```sh
node <skill-dir>/scripts/issue-task-id.mjs [--tasks-dir <path>]
node <skill-dir>/scripts/lint-task.mjs <card-file>
node <skill-dir>/scripts/add-task.mjs register <card-file> [--depends-on <task-id>...] [--tasks-dir <path>]
node <skill-dir>/scripts/add-task.mjs dependency <task-id> <dependency-id> [--tasks-dir <path>]
node <skill-dir>/scripts/validate-tasks.mjs [--tasks-dir <path>]
```

The default Task directory is `./tasks`. Issue a new ID only when the user did not provide one. Add dependencies only through `add-task.mjs`; never edit `depends_on` directly. Run `lint-task.mjs` before registration and `validate-tasks.mjs` after every graph change.
