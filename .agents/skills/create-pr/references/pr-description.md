# PR Description Guidance

Write the pull request body for the reviewer's next decision, not as a record of
completed development steps. The mandatory inclusion, exclusion, and exception
rules are in [pr-reviewer-guide.md](pr-reviewer-guide.md). Read that reference
before drafting or editing the body.

## Explain the Change

Open with the changed behavior, why the change is needed, and its impact. For a
nontrivial implementation, explain the responsibilities of the key components,
their collaboration, and meaningful design trade-offs. Keep the description
consistent with the current diff.

## Direct Review Attention

Point to the specific contract, failure boundary, assumption, or decision that
needs scrutiny. Link the relevant design, specification, or code where it is
being discussed. For a large diff, give the reviewer an efficient reading order
if this materially improves reviewability.

Prefer a Mermaid diagram, table, or screenshot when it communicates the changed
behavior more precisely than prose. Verify that visuals match the implementation.

## Describe Exceptions, Not Routine Completion

Include unusual verification evidence when it establishes a central behavior
that the diff cannot demonstrate, or when a failed or omitted check limits
review confidence. Describe the verified contract, supporting evidence, and
consequence rather than a list of commands or test counts.

Explain migrations, rollout or rollback dependencies, compatibility risks, and
known exclusions when they affect review or deployment. Do not create empty
sections or add generic checklists.

## Default Shape

Write titles and body text in the repository's required language. SpringKit uses
these Korean headings, with sections omitted when they have no meaningful
content:

```markdown
## 변경 내용

<목적, 변경된 동작, 핵심 구현 구조와 설계 근거>

## 검토할 사항

- <검토할 구체적인 계약·경계와 참고 링크>

## 남은 범위와 제약

- <리뷰 판단에 영향을 주는 실제 제약 또는 미검증 사항>
```

Keep the body proportional to the change. The repository's required PR title,
footer, branch, and publication rules continue to take precedence.
