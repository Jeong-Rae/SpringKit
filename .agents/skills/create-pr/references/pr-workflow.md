# PR Workflow

Use this workflow with any programming language, build system, or branching
model. Repository instructions and pull request templates take precedence.

## Create a Pull Request

1. Inspect the working tree and current branch:

   ```bash
   rtk git status --short
   rtk git branch --show-current
   rtk git diff -- <paths>
   ```

   Preserve unrelated changes and limit work to the requested change.

2. Read the applicable repository instructions. Identify the required branch
   name, base branch, commit format, validation commands, publication method,
   pull request state, and authorization boundaries before changing Git.

3. Create or enter the branch through the repository-required workflow. If the
   repository defines no workflow, use its established branch convention and
   ordinary Git commands.

4. Run the narrowest validation that covers the change. Use the repository's
   test, lint, build, or documentation checks instead of assuming a language or
   build tool. Then stage only relevant files and commit:

   ```bash
   rtk git add <paths>
   rtk git diff --cached --check
   rtk git commit -m "<repository-compliant title>"
   ```

5. Read [pr-description.md](pr-description.md) and write the pull request body
   to a temporary Markdown file. Apply the repository's or user's language and
   writing requirements.

6. Publish through the repository-required command or script. If none exists,
   push the branch and create a draft pull request:

   ```bash
   rtk git push -u origin <branch>
   rtk gh pr create --draft --base <base> --title "<title>" --body-file <path>
   ```

   Keep the pull request in the required state and do not broaden authorization
   to ready, merge, deployment, or other external actions.

## Recover a Failed Publication

Inspect the branch and its pull request before retrying:

```bash
rtk git status --short --branch
rtk gh pr view <branch> --json url,isDraft,title,baseRefName,state
```

Report which local and remote steps completed and which command failed. Retry
only after confirming that the retry cannot create a duplicate pull request. If
authentication fails, ask the user to restore it before continuing.

## Update After Review

Inspect the pull request diff, checks, and review feedback. Change only the
requested scope, run relevant validation, and commit the result. Publish with
the repository-required update workflow or push the current branch when no such
workflow exists. Recheck approval and continuous integration status after
publication.
