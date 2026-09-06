import { renderStepMarkdown, renderTaskMarkdown } from "./markdown.mjs";

export const SOURCE_STATUSES = [
  "pending",
  "running",
  "verifying",
  "blocked",
  "done",
  "cancelled",
];

function displayTitle(id, title) {
  return `[${id}] ${title}`;
}

export function projectTaskCard(card) {
  const task = {
    id: card.id,
    kind: "task",
    title: card.title,
    displayTitle: displayTitle(card.id, card.title),
    status: card.status,
    type: card.type,
    parentId: null,
    dependencies: [...card.depends_on],
    markdown: renderTaskMarkdown({
      goal: card.goal,
      requirements: card.requirements,
      scope: card.scope,
      verification: card.verification,
      issues: card.issues,
      references: card.references,
    }),
  };

  const steps = card.steps.map((step) => ({
    id: step.id,
    kind: "step",
    title: step.title,
    displayTitle: displayTitle(step.id, step.title),
    status: step.status,
    type: null,
    parentId: card.id,
    dependencies: [],
    markdown: renderStepMarkdown({
      objective: step.objective,
      work: step.work,
      verification: step.verification,
      issues: step.issues,
      references: step.references,
    }),
  }));

  return [task, ...steps];
}

function numericIdParts(id) {
  const match = /^sk-(\d+)(?:-(\d+))?$/.exec(id);
  if (!match) return [Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER, id];
  return [Number(match[1]), match[2] ? Number(match[2]) : 0, id];
}

export function compareWorkItemIds(left, right) {
  const a = numericIdParts(left);
  const b = numericIdParts(right);
  return a[0] - b[0] || a[1] - b[1] || String(a[2]).localeCompare(String(b[2]));
}
