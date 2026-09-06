import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { SOURCE_STATUSES, compareWorkItemIds, projectTaskCard } from "./work-item.mjs";

const SOURCE_TYPES = new Set(["feature", "fix", "refactor", "ci", "dep"]);
const SOURCE_STATUS_SET = new Set(SOURCE_STATUSES);
const TASK_ID = /^sk-[1-9]\d*$/;

function requireString(value, path) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${path}: 비어 있지 않은 문자열이어야 합니다.`);
  }
}

function requireStringArray(value, path) {
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) {
    throw new Error(`${path}: 문자열 배열이어야 합니다.`);
  }
}

function validateProjectionInput(card, source) {
  if (typeof card !== "object" || card === null || Array.isArray(card)) {
    throw new Error(`${source}: 최상위 값은 객체여야 합니다.`);
  }
  requireString(card.id, `${source}.id`);
  if (!TASK_ID.test(card.id)) throw new Error(`${source}.id: Task ID 형식이 올바르지 않습니다: ${card.id}`);
  requireString(card.title, `${source}.title`);
  if (!SOURCE_TYPES.has(card.type)) throw new Error(`${source}.type: 지원하지 않는 Task type입니다: ${card.type}`);
  if (!SOURCE_STATUS_SET.has(card.status)) throw new Error(`${source}.status: 지원하지 않는 상태입니다: ${card.status}`);
  requireString(card.goal, `${source}.goal`);
  requireStringArray(card.depends_on, `${source}.depends_on`);
  requireStringArray(card.requirements, `${source}.requirements`);
  if (typeof card.scope !== "object" || card.scope === null || Array.isArray(card.scope)) {
    throw new Error(`${source}.scope: 객체여야 합니다.`);
  }
  requireStringArray(card.scope.in, `${source}.scope.in`);
  requireStringArray(card.scope.out, `${source}.scope.out`);
  requireStringArray(card.verification, `${source}.verification`);
  requireStringArray(card.issues, `${source}.issues`);
  requireStringArray(card.references, `${source}.references`);
  if (!Array.isArray(card.steps)) throw new Error(`${source}.steps: 배열이어야 합니다.`);

  const stepIds = new Set();
  for (const [index, step] of card.steps.entries()) {
    const path = `${source}.steps[${index}]`;
    if (typeof step !== "object" || step === null || Array.isArray(step)) {
      throw new Error(`${path}: 객체여야 합니다.`);
    }
    requireString(step.id, `${path}.id`);
    if (stepIds.has(step.id)) throw new Error(`${path}.id: Step ID가 중복되었습니다: ${step.id}`);
    stepIds.add(step.id);
    requireString(step.title, `${path}.title`);
    if (!SOURCE_STATUS_SET.has(step.status)) throw new Error(`${path}.status: 지원하지 않는 상태입니다: ${step.status}`);
    requireString(step.objective, `${path}.objective`);
    requireStringArray(step.work, `${path}.work`);
    requireStringArray(step.verification, `${path}.verification`);
    requireStringArray(step.issues, `${path}.issues`);
    requireStringArray(step.references, `${path}.references`);
  }
}

async function readTaskCard(file) {
  let text;
  try {
    text = await readFile(file, "utf8");
  } catch (error) {
    const code = error && typeof error === "object" && "code" in error ? ` (${error.code})` : "";
    throw new Error(`${file}: 파일을 읽을 수 없습니다${code}.`);
  }

  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${file}: JSON을 해석할 수 없습니다.`);
  }
}

export async function loadWorkItems(tasksDir) {
  let names;
  try {
    names = (await readdir(tasksDir)).filter((name) => name.endsWith(".json")).sort();
  } catch (error) {
    const code = error && typeof error === "object" && "code" in error ? ` (${error.code})` : "";
    throw new Error(`${tasksDir}: Task 디렉터리를 읽을 수 없습니다${code}.`);
  }

  const cards = [];
  const taskIds = new Set();
  const allItemIds = new Set();

  for (const name of names) {
    const file = join(tasksDir, name);
    const card = await readTaskCard(file);
    validateProjectionInput(card, file);
    if (taskIds.has(card.id)) throw new Error(`${file}: Task ID가 중복되었습니다: ${card.id}`);
    taskIds.add(card.id);
    cards.push(card);
  }

  for (const card of cards) {
    for (const dependency of card.depends_on) {
      if (!taskIds.has(dependency)) {
        throw new Error(`${card.id}: dependency Task를 찾을 수 없습니다: ${dependency}`);
      }
    }
  }

  const workItems = cards.flatMap(projectTaskCard);
  for (const item of workItems) {
    if (allItemIds.has(item.id)) throw new Error(`source ID가 중복되었습니다: ${item.id}`);
    allItemIds.add(item.id);
  }

  return workItems.sort((left, right) => compareWorkItemIds(left.id, right.id));
}
