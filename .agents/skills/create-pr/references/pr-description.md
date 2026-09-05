# PR Description Guidance

Use this guidance when preparing or updating the pull request body in the
`create-pr` workflow. A pull request description should help a reviewer spend
attention on the parts most likely to hide mistakes. The repository's
`AGENTS.md`, pull request template, and authorization rules take precedence.

## 1. Open With What and Why

Use the opening paragraph to state what changed, why it changed, and what it
affects. A reviewer should be able to identify the purpose and impact without
opening another file.

- Keep the opening to one or two sentences when that is enough.
- Omit preambles and the history of the investigation.
- Link a relevant issue when useful, but do not use the link as a substitute
  for explaining the problem.
- State breaking behavior or a wide impact early instead of burying it in a
  later section.

Completion check: the opening identifies the purpose and affected behavior or
scope.

## 2. Direct Review Attention to Risk

Identify the parts that deserve close review. Include risks, uncertainties,
non-obvious design decisions, and assumptions that the diff cannot explain on
its own.

- Name the relevant file, component, behavior, or decision precisely.
- Explain why the part is risky or uncertain.
- Say which changes are safe to skim when a large mechanical change could hide
  the meaningful change.
- Do not hide uncertainty to make the change appear more complete.

Completion check: the reviewer knows where to concentrate and what can be
reviewed quickly.

## 3. State How the Change Was Verified

Describe verification specifically and honestly. Separate observed results
from assumptions so the reviewer can decide what still needs attention.

- State which behavior or condition each check verified.
- Include a command only when a reviewer can run it exactly as written.
- Omit environment-specific commands and absolute paths.
- State relevant tests or paths that were not run and explain why.
- Do not imply coverage beyond the checks that actually ran.

Completion check: the reviewer can distinguish verified behavior, unverified
behavior, and remaining assumptions.

## 4. Keep the Change Reviewable

A description cannot compensate for a change that is too broad to review.
When the diff is large, explain how to review it.

- Separate mechanical and meaningful commits when the Task permits it.
- Suggest a reading order when dependencies between changes are not obvious.
- Explain why the change must remain together when it cannot be divided safely.
- If separate pull requests would require another Task or Task identifier, stop
  and ask the user instead of creating or renaming work on their behalf.

Completion check: the diff is reviewable as one Task, or the user has been told
why it should be divided and which decision is required.

## 5. Include What the Diff Cannot Show

Include supporting information only when it affects review, use, deployment,
or rollback.

- Add before-and-after screenshots or a short recording for visual changes.
- State migration, backfill, feature flag, rollout, deployment-order, and
  rollback requirements.
- State breaking and compatibility changes plainly.
- Link deliberate follow-up work so an omission is recognizable as a scoped
  decision.

Completion check: the reviewer does not need to discover important operational
or user-visible information by reading the code.

## Body Shape

Use `## 요약` and `## 검증` as the `create-pr` default sections. If the
repository's `AGENTS.md` or pull request template requires another shape, use
the repository's shape instead. Add optional sections only when they contain
information that changes how the pull request should be reviewed or deployed.

```markdown
## 요약

<변경 내용, 이유, 영향 범위>

## 검토 사항

- <주의 깊게 검토할 위험, 불확실성 또는 비자명한 설계 결정>
- <안전하게 훑어봐도 되는 기계적 변경>

## 검증

- `<검증한 동작 또는 조건>`
- `<리뷰어가 그대로 실행할 수 있는 command>`
- 실행하지 않음: `<command>` (`<사유>`)

## 배포 메모

- <시각 자료, 마이그레이션, 배포 순서, 호환성 변경 또는 후속 작업>
```

Omit `## 검토 사항` or `## 배포 메모` when the section would be empty. Keep
the body proportional to the change instead of filling every section by
default.

## Anti-patterns

- Do not paste the commit log.
- Do not describe every changed file when the diff already shows it.
- Do not oversell the change or minimize its size and risk.
- Do not leave the body empty on the assumption that the code explains the
  reason for the change.
- Do not include investigation history that does not affect the review.
- Do not claim that a behavior was tested when the corresponding check did not
  run.
- Do not add generated-by footers unless the repository explicitly requires
  them.

## Source

This guidance adapts the review principles from
[`arjunprabhulal/agent-skills`' `pr-descriptions` at `5223b745`](https://github.com/arjunprabhulal/agent-skills/blob/5223b745/skills/docs/pr-descriptions/SKILL.md),
which is published under the MIT License. The original copyright and permission
notice are preserved in
[`LICENSE-pr-descriptions`](../LICENSE-pr-descriptions). Repository-specific
workflow, template, and authorization requirements remain authoritative.
