---
name: create-pr
description: >
  Create a reviewable, validated draft pull request in a Kotlin/Gradle
  repository that uses the PR First Git Flow workflow. Use when starting a
  feature, committing its changes, writing a PR title or body, publishing it,
  opening a draft PR, or recovering from PR creation failures. Follow AGENTS.md
  for review updates and post-merge completion.
metadata:
  internal: true
---

# Create PR

Use this skill to turn one feature's verified changes into a draft pull request
whose description explains the reason for the change and directs reviewers to
the important risks. Follow the repository's `AGENTS.md` when it defines
stricter branch, commit, build, or authorization rules.

## Workflow

1. Inspect the current state before changing Git:

   ```bash
   rtk git status --short
   rtk git branch --show-current
   rtk git diff -- <paths>
   ```

   Preserve unrelated user changes and operate only on files in the requested Task.

2. Start a feature only when one does not already exist for the Task:

   ```bash
   rtk kotlin scripts/git/git-workflow.main.kts start <task-id>
   ```

   Task identifiers are used exactly as supplied. The script verifies the
   `develop` base before creating `feature/<task-id>`.

3. Validate and commit:

   ```bash
   rtk ./gradlew test --tests "<related-test-pattern>"
   rtk git add <paths>
   rtk git diff --cached --check
   rtk git commit -m "<Type>: <한글 설명>"
   ```

   Select the narrowest meaningful Gradle verification. Stage only Task files.
   Apply `$writing-guide` to the commit message, and include only
   repository-required commit footers.

4. Write the PR body to a temporary Markdown file:

   Read [references/pr-description.md](references/pr-description.md) fully and
   apply it to the current diff and verification results. Apply
   `$writing-guide` to every Korean sentence. Write the finished body to a
   temporary file so commands, code spans, and line breaks remain unchanged.

5. Publish the feature and create the draft PR through the workflow script:

   ```bash
   rtk kotlin scripts/git/git-workflow.main.kts publish \
     "[<task-id>] Feature: <description>" \
     --body-file <pr-body-path>
   ```

   The script enforces the current feature branch, clean tracked state, title
   format, unique head PR, `develop` base, and draft state. The user owns the
   ready transition and merge decision.

## Recovery

- Inspect the branch PR before retrying publication:

  ```bash
  rtk gh pr view <branch> --json url,isDraft,title,baseRefName,state
  ```

- If the feature was published but PR creation failed, report the completed
  state and the failing command. Retry only after identifying whether a PR
  already exists.
- If GitHub authentication fails, ask the user to restore the configured
  authentication provider before continuing.

## After PR Creation

- Follow the PR review workflow in `AGENTS.md` after the draft PR exists.
- Publish committed review changes through the workflow script:

  ```bash
  rtk kotlin scripts/git/git-workflow.main.kts update
  ```
