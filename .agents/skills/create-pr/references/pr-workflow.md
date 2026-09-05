# PR Workflow

Use this workflow to create or update a pull request. The repository's
`AGENTS.md` takes precedence.

## Create a Pull Request

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

   Use the Task identifier exactly as supplied. The script verifies the
   `develop` base before creating `feature/<task-id>`.

3. Validate and commit:

   ```bash
   rtk ./gradlew test --tests "<related-test-pattern>"
   rtk git add <paths>
   rtk git diff --cached --check
   rtk git commit -m "<Type>: <한글 설명>"
   ```

   Select the narrowest meaningful Gradle verification. Stage only Task files.
   Apply `$writing-guide` to the commit message and include only
   repository-required commit footers.

4. Write the pull request body to a temporary Markdown file. Read
   [pr-description.md](pr-description.md) and apply `$writing-guide` to every
   Korean sentence. Use the current diff and verification results.

5. Publish the feature and create the draft pull request through the workflow
   script:

   ```bash
   rtk kotlin scripts/git/git-workflow.main.kts publish \
     "[<task-id>] Feature: <description>" \
     --body-file <pr-body-path>
   ```

   The script enforces the current feature branch, clean tracked state, title
   format, unique head PR, `develop` base, and draft state. The user owns the
   ready transition and merge decision.

## Recover a Failed Publication

- Inspect the branch pull request before retrying publication:

  ```bash
  rtk gh pr view <branch> --json url,isDraft,title,baseRefName,state
  ```

- If publication partly succeeded, report the completed state and failing
  command. Retry only after checking whether a pull request already exists.
- If GitHub authentication fails, ask the user to restore authentication before
  continuing.

## Update an Existing Pull Request

Follow the review workflow in `AGENTS.md`. After implementing, validating, and
committing requested changes, publish them with:

```bash
rtk kotlin scripts/git/git-workflow.main.kts update
```
