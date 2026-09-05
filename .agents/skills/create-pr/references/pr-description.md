# PR Description Guidance

A pull request description should direct review attention to likely mistakes,
not repeat the diff. Repository rules and pull request templates take
precedence.

## State What and Why

Open with one or two sentences that identify the change, its reason, and its
impact. Link an issue when useful, but do not replace the explanation with a
link. State breaking or broad effects early.

## Point to Risk

Name the files, behavior, assumptions, or decisions that need close review and
explain why. Also identify large mechanical changes that are safe to skim. Do
not hide uncertainty.

## Report Verification

Separate verified behavior from assumptions and omitted checks. State what each
check proved. Include commands only when a reviewer can run them unchanged, and
do not imply broader coverage than the checks provide.

## Keep the Change Reviewable

For a large diff, separate mechanical and meaningful commits or suggest a
reading order. Explain why inseparable changes belong together. If splitting
requires another Task identifier, ask the user instead of creating one.

## Add What the Diff Cannot Show

Include screenshots, migrations, rollout order, compatibility changes,
rollback requirements, and deliberate follow-ups only when they affect review
or deployment.

## Default Shape

Use the repository's template and the language requested by the user. If
neither specifies a format, use `## Summary` and `## Verification`. Add review
or deployment sections only when they change how the pull request is assessed.

```markdown
## Summary

<what changed, why, and affected scope>

## Review Focus

- <risk, uncertainty, or decision that needs close review>
- <mechanical change that is safe to skim>

## Verification

- `<verified behavior or condition>`
- `<command that can be run unchanged>`
- Not run: `<command>` (`<reason>`)

## Deployment Notes

- <migration, rollout order, compatibility change, or follow-up>
```

Omit empty optional sections and keep the body proportional to the change.

## Avoid

- Do not paste the commit log or describe every changed file.
- Do not oversell the change or minimize its risk.
- Do not omit the reason on the assumption that the code explains it.
- Do not claim that an unrun check passed.
