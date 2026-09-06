import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { SOURCE_STATUSES, compareWorkItemIds, projectTaskCard } from "./work-item.mjs";

const SOURCE_TYPES = new Set(["feature", "fix", "refactor", "ci", "dep"]);
const SOURCE_STATUS_SET = new Set(SOURCE_STATUSES);
const TASK_ID = /^sk-[1-9]\d*$/;

function requireString(value, path) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${path}: non-empty string is required`);
  }
}

function requireStringArray(value, path) {
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) {
    throw new Error(`${path}: string array is required`);
  }
}

function validateProjectionInput(card, source) {
  if (typeof card !== "object" || card === null || Array.isArray(card)) {
    throw new Error(`${source}: root object is required`);
  }
  requireString(card.id, `${source}.id`);
  if (!TASK_ID.test(card.id)) throw new Error(`${source}.id: invalid Task ID: ${card.id}`);
  requireString(card.title, `${source}.title`);
  if (!SOURCE_TYPES.has(card.type)) throw new Error(`${source}.type: unsupported type: ${card.type}`);
  if (!SOURCE_STATUS_SET.has(card.status)) throw new Error(`${source}.status: unsupported status: ${card.status}`);
  requireString(card.goal, `${source}.goal`);
  requireStringArray(card.depends_on, `${source}.depends_on`);
  requireStringArray(card.requirements, `${source}.requirements`);
  if (typeof card.scope !== "object" || card.scope === null || Array.isArray(card.scope)) {
    throw new Error(`${source}.scope: object is required`);
  }
  requireStringArray(card.scope.in, `${source}.scope.in`);
  requireStringArray(card.scope.out, `${source}.scope.out`);
  requireStringArray(card.verification, `${source}.verification`);
  requireStringArray(card.issues, `${source}.issues`);
  requireStringArray(card.references, `${source}.references`);
  if (!Array.isArray(card.steps)) throw new Error(`${source}.steps: array is required`);

  const stepIds = new Set();
  for (const [index, step] of card.steps.entries()) {
    const path = `${source}.steps[${index}]`;
    if (typeof step !== "object" || step === null || Array.isArray(step)) {
      throw new Error(`${path}: object is required`);
    }
    requireString(step.id, `${path}.id`);
    if (stepIds.has(step.id)) throw new Error(`${path}.id: duplicate Step ID: ${step.id}`);
    stepIds.add(step.id);
    requireString(step.title, `${path}.title`);
    if (!SOURCE_STATUS_SET.has(step.status)) throw new Error(`${path}.status: unsupported status: ${step.status}`);
    requireString(step.objective, `${path}.objective`);
    requireStringArray(step.work, `${path}.work`);
    requireStringArray(step.verification, `${path}.verification`);
    requireStringArray(step.issues, `${path}.issues`);
    requireStringArray(step.references, `${path}.references`);
  }
}

export async function loadWorkItems(tasksDir) {
  const names = (await readdir(tasksDir)).filter((name) => name.endsWith(".json")).sort();
  const cards = [];
  const taskIds = new Set();
  const allItemIds = new Set();

  for (const name of names) {
    const file = join(tasksDir, name);
    const card = JSON.parse(await readFile(file, "utf8"));
    validateProjectionInput(card, file);
    if (taskIds.has(card.id)) throw new Error(`${file}: duplicate Task ID: ${card.id}`);
    taskIds.add(card.id);
    cards.push(card);
  }

  for (const card of cards) {
    for (const dependency of card.depends_on) {
      if (!taskIds.has(dependency)) {
        throw new Error(`${card.id}: dependency source Task does not exist: ${dependency}`);
      }
    }
  }

  const workItems = cards.flatMap(projectTaskCard);
  for (const item of workItems) {
    if (allItemIds.has(item.id)) throw new Error(`duplicate source ID: ${item.id}`);
    allItemIds.add(item.id);
  }

  return workItems.sort((left, right) => compareWorkItemIds(left.id, right.id));
}
