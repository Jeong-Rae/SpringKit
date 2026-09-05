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

Use `## 요약` and `## 검증` by default. Add `## 검토 사항` or `## 배포 메모`
only when they contain information that changes review or deployment.

```markdown
## 요약

<변경 내용, 이유, 영향 범위>

## 검토 사항

- <위험, 불확실성, 주의 깊게 볼 결정>
- <안전하게 훑어봐도 되는 기계적 변경>

## 검증

- `<검증한 동작 또는 조건>`
- `<그대로 실행할 수 있는 명령>`
- 실행하지 않음: `<명령>` (`<사유>`)

## 배포 메모

- <마이그레이션, 배포 순서, 호환성 변경, 후속 작업>
```

Omit empty optional sections and keep the body proportional to the change.

## Avoid

- Do not paste the commit log or describe every changed file.
- Do not oversell the change or minimize its risk.
- Do not omit the reason on the assumption that the code explains it.
- Do not claim that an unrun check passed.
