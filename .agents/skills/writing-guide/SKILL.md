---
name: writing-guide
description: >
  Apply consistent Korean writing conventions to responses and authored text,
  including technical documents, commit messages, and pull request
  descriptions. Use whenever writing, editing, or reviewing Korean prose.
metadata:
  internal: true
---

# Writing Guide

Write for the reader's immediate goal. State the result or main point first,
then add only the context needed to understand or act on it.

## Shared Principles

- Develop one main idea per paragraph. Keep sentences short enough to read once.
- Keep the subjects, objects, particles, and endings needed to convey meaning.
- Avoid strings of nouns. Use particles and predicates to make relationships
  explicit.
- Prefer literal wording for ordinary ideas instead of replacing it with
  metaphors.
- Do not connect separate judgments with commas or em dashes. State the
  relationship explicitly or split the sentence.
- Do not overstate conclusions beyond what was verified. Distinguish results,
  limitations, and unresolved issues.
- Remove words, sentences, and conclusions that do not change the meaning.
- Prefer familiar Korean words. Keep proper nouns and code identifiers in their
  original form when translation would reduce precision.
- Explain unfamiliar domain terms at first use instead of assuming shared
  context.
- Use a respectful, neutral tone. Do not pressure the reader or create fear to
  induce an action.
- Make the next action and its expected result clear when the reader must make a
  decision or perform a procedure.
- Use headings, lists, tables, and emphasis only when they make the relationship
  or sequence easier to understand.

The user's requested tone and deliverable format take precedence over these
defaults. Preserve mandatory formats and authorization boundaries defined in
`AGENTS.md`.

## Deliverables

### Responses

Lead with the outcome. Use concise paragraphs and the minimum formatting needed
for comprehension. Report blockers with the evidence and the next required
decision.

### Technical Documents

Before writing, editing, or reviewing a technical document, read
[references/document-style.md](references/document-style.md). Apply its format
and content checks to the finished document.

### Commits and Pull Requests

Follow the exact title, footer, branch, and authorization contracts in
`AGENTS.md`. Write title descriptions and bodies in Korean. In a pull request
body, focus on what changed, why it changed, and how the change was verified.
