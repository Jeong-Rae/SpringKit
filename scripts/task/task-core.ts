import { link, readFile, readdir, rename, unlink, writeFile } from "node:fs/promises";
import { basename, join, resolve } from "node:path";
import { parseDocument, stringify } from "yaml";

export const taskTypes = ["feature", "fix", "refactor", "ci", "dep"] as const;
export const statuses = ["pending", "running", "verifying", "blocked", "done", "cancelled"] as const;

export type TaskType = (typeof taskTypes)[number];
export type Status = (typeof statuses)[number];

export type TaskStep = {
  id: string;
  title: string;
  status: Status;
  objective: string;
  work: string[];
  verification: string[];
  issues: string[];
  references: string[];
};

export type TaskCard = {
  id: string;
  title: string;
  type: TaskType;
  status: Status;
  goal: string;
  depends_on: string[];
  spec_refs: string[];
  scope: { in: string[]; out: string[] };
  verification: string[];
  issues: string[];
  references: string[];
  steps: TaskStep[];
};

export class TaskToolError extends Error {
  exitCode: number;

  constructor(message: string, exitCode = 1) {
    super(message);
    this.exitCode = exitCode;
  }
}

const taskKeys = [
  "id", "title", "type", "status", "goal", "depends_on", "spec_refs", "scope",
  "verification", "issues", "references", "steps",
];
const stepKeys = ["id", "title", "status", "objective", "work", "verification", "issues", "references"];
const taskIdPattern = /^sk-([1-9]\d*)$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function checkKeys(value: Record<string, unknown>, keys: string[], location: string, errors: string[]): void {
  const actual = Object.keys(value);
  if (actual.join("\0") !== keys.join("\0")) {
    errors.push(`${location}: 필드는 다음 순서와 정확히 일치해야 합니다: ${keys.join(", ")}`);
  }
}

function checkString(value: unknown, location: string, errors: string[]): value is string {
  if (typeof value !== "string" || value.trim() === "") {
    errors.push(`${location}: 비어 있지 않은 문자열이어야 합니다.`);
    return false;
  }
  return true;
}

function checkStringArray(
  value: unknown,
  location: string,
  errors: string[],
  requireItem = false,
): value is string[] {
  if (!Array.isArray(value)) {
    errors.push(`${location}: 문자열 배열이어야 합니다.`);
    return false;
  }
  if (requireItem && value.length === 0) {
    errors.push(`${location}: 한 개 이상의 항목이 필요합니다.`);
  }
  value.forEach((item, index) => checkString(item, `${location}[${index}]`, errors));
  return value.every((item) => typeof item === "string" && item.trim() !== "");
}

export function validateTaskCard(value: unknown, source = "Task Card"): string[] {
  const errors: string[] = [];
  if (!isRecord(value)) return [`${source}: YAML 최상위 값은 객체여야 합니다.`];

  checkKeys(value, taskKeys, source, errors);
  const idValid = checkString(value.id, `${source}.id`, errors) && taskIdPattern.test(value.id as string);
  if (typeof value.id === "string" && !taskIdPattern.test(value.id)) {
    errors.push(`${source}.id: sk-<양의 정수> 형식이어야 합니다.`);
  }
  checkString(value.title, `${source}.title`, errors);
  if (!taskTypes.includes(value.type as TaskType)) errors.push(`${source}.type: 허용되지 않은 Task Type입니다.`);
  if (!statuses.includes(value.status as Status)) errors.push(`${source}.status: 허용되지 않은 상태입니다.`);
  checkString(value.goal, `${source}.goal`, errors);
  if (checkStringArray(value.depends_on, `${source}.depends_on`, errors)) {
    const dependencies = value.depends_on as string[];
    dependencies.forEach((dependency, index) => {
      if (!taskIdPattern.test(dependency)) {
        errors.push(`${source}.depends_on[${index}]: sk-<양의 정수> 형식이어야 합니다.`);
      }
    });
    if (new Set(dependencies).size !== dependencies.length) {
      errors.push(`${source}.depends_on: 같은 Task ID를 중복하여 기록할 수 없습니다.`);
    }
  }
  checkStringArray(value.spec_refs, `${source}.spec_refs`, errors);

  if ((value.type === "feature" || value.type === "fix") && Array.isArray(value.spec_refs) && value.spec_refs.length === 0) {
    errors.push(`${source}.spec_refs: feature와 fix Task에는 한 개 이상의 SPEC 참조가 필요합니다.`);
  }

  if (!isRecord(value.scope)) {
    errors.push(`${source}.scope: in과 out을 가진 객체여야 합니다.`);
  } else {
    checkKeys(value.scope, ["in", "out"], `${source}.scope`, errors);
    checkStringArray(value.scope.in, `${source}.scope.in`, errors);
    checkStringArray(value.scope.out, `${source}.scope.out`, errors);
  }
  checkStringArray(value.verification, `${source}.verification`, errors, true);
  checkStringArray(value.issues, `${source}.issues`, errors);
  checkStringArray(value.references, `${source}.references`, errors);

  if (!Array.isArray(value.steps) || value.steps.length === 0) {
    errors.push(`${source}.steps: 한 개 이상의 Step이 필요합니다.`);
  } else {
    value.steps.forEach((step, index) => {
      const location = `${source}.steps[${index}]`;
      if (!isRecord(step)) {
        errors.push(`${location}: 객체여야 합니다.`);
        return;
      }
      checkKeys(step, stepKeys, location, errors);
      const expectedStepId = idValid ? `${value.id}-${index + 1}` : undefined;
      checkString(step.id, `${location}.id`, errors);
      if (expectedStepId && step.id !== expectedStepId) {
        errors.push(`${location}.id: 선언 순서에 따라 ${expectedStepId}이어야 합니다.`);
      }
      checkString(step.title, `${location}.title`, errors);
      if (!statuses.includes(step.status as Status)) errors.push(`${location}.status: 허용되지 않은 상태입니다.`);
      checkString(step.objective, `${location}.objective`, errors);
      checkStringArray(step.work, `${location}.work`, errors, true);
      checkStringArray(step.verification, `${location}.verification`, errors, true);
      checkStringArray(step.issues, `${location}.issues`, errors);
      checkStringArray(step.references, `${location}.references`, errors);

      if (index > 0 && ["running", "verifying", "done"].includes(step.status as string)) {
        const previous = value.steps[index - 1];
        if (!isRecord(previous) || previous.status !== "done") {
          errors.push(`${location}.status: 이전 Step이 done이어야 ${String(step.status)} 상태가 될 수 있습니다.`);
        }
      }
    });
  }

  if (value.status === "done" && Array.isArray(value.steps)) {
    if (value.steps.some((step) => !isRecord(step) || step.status !== "done")) {
      errors.push(`${source}.status: Task가 done이면 모든 Step도 done이어야 합니다.`);
    }
  }
  return errors;
}

export async function readTaskCard(
  file: string,
  requireMatchingFilename = false,
  requireCanonicalFormat = false,
): Promise<TaskCard> {
  let text: string;
  try {
    text = await readFile(file, "utf8");
  } catch (error) {
    throw new TaskToolError(`${file}: 파일을 읽을 수 없습니다. ${error instanceof Error ? error.message : String(error)}`);
  }
  const document = parseDocument(text, { uniqueKeys: true });
  if (document.errors.length > 0) {
    throw new TaskToolError(document.errors.map((error) => `${file}: ${error.message}`).join("\n"));
  }
  const value: unknown = document.toJS();
  const errors = validateTaskCard(value, file);
  if (requireMatchingFilename && isRecord(value) && typeof value.id === "string") {
    const expected = `${value.id}.yaml`;
    if (basename(file) !== expected) errors.push(`${file}: 파일명은 ${expected}이어야 합니다.`);
  }
  if (requireCanonicalFormat && errors.length === 0 && text !== serializeTaskCard(value as TaskCard)) {
    errors.push(`${file}: canonical YAML 형식이 아닙니다. add-task.ts가 생성하는 형식을 사용해야 합니다.`);
  }
  if (errors.length > 0) throw new TaskToolError(errors.join("\n"));
  return value as TaskCard;
}

export async function loadTaskCards(tasksDir: string): Promise<Map<string, { card: TaskCard; file: string }>> {
  let names: string[];
  try {
    names = (await readdir(tasksDir)).filter((name) => name.endsWith(".yaml")).sort();
  } catch (error) {
    throw new TaskToolError(`${tasksDir}: Task 디렉터리를 읽을 수 없습니다. ${error instanceof Error ? error.message : String(error)}`);
  }
  const cards = new Map<string, { card: TaskCard; file: string }>();
  const errors: string[] = [];
  for (const name of names) {
    const file = join(tasksDir, name);
    try {
      const card = await readTaskCard(file, true, true);
      if (cards.has(card.id)) errors.push(`${file}: 중복 Task ID입니다: ${card.id}`);
      else cards.set(card.id, { card, file });
    } catch (error) {
      errors.push(error instanceof Error ? error.message : String(error));
    }
  }
  if (errors.length > 0) throw new TaskToolError(errors.join("\n"));
  return cards;
}

function reachable(from: string, target: string, cards: Map<string, { card: TaskCard }>, visited = new Set<string>()): boolean {
  if (from === target) return true;
  if (visited.has(from)) return false;
  visited.add(from);
  const entry = cards.get(from);
  return entry ? entry.card.depends_on.some((dependency) => reachable(dependency, target, cards, visited)) : false;
}

export function validateTaskGraph(cards: Map<string, { card: TaskCard }>): string[] {
  const errors: string[] = [];
  for (const [id, { card }] of cards) {
    const seen = new Set<string>();
    for (const dependency of card.depends_on) {
      if (dependency === id) errors.push(`${id}: 자기 자신을 dependency로 사용할 수 없습니다.`);
      if (seen.has(dependency)) errors.push(`${id}: dependency가 중복되었습니다: ${dependency}`);
      seen.add(dependency);
      if (!cards.has(dependency)) errors.push(`${id}: 선행 Task를 찾을 수 없습니다: ${dependency}`);
    }
  }

  const visiting = new Set<string>();
  const visited = new Set<string>();
  const visit = (id: string, path: string[]): void => {
    if (visiting.has(id)) {
      const start = path.indexOf(id);
      errors.push(`Task DAG에 cycle이 있습니다: ${[...path.slice(start), id].join(" -> ")}`);
      return;
    }
    if (visited.has(id)) return;
    visiting.add(id);
    const entry = cards.get(id);
    entry?.card.depends_on.forEach((dependency) => {
      if (cards.has(dependency)) visit(dependency, [...path, id]);
    });
    visiting.delete(id);
    visited.add(id);
  };
  cards.forEach((_entry, id) => visit(id, []));

  if (!errors.some((error) => error.includes("cycle"))) {
    for (const [id, { card }] of cards) {
      for (const dependency of card.depends_on) {
        const otherDependencies = card.depends_on.filter((candidate) => candidate !== dependency);
        if (otherDependencies.some((other) => reachable(other, dependency, cards))) {
          errors.push(`${id}: ${dependency}는 전이적으로 충족되므로 직접 dependency에서 제거해야 합니다.`);
        }
      }
    }
  }
  return [...new Set(errors)];
}

export function serializeTaskCard(card: TaskCard): string {
  return stringify(card, { indent: 2, lineWidth: 0 });
}

export async function writeTaskCardAtomically(file: string, card: TaskCard): Promise<void> {
  const temporary = `${file}.${process.pid}.${Date.now()}.tmp`;
  try {
    await writeFile(temporary, serializeTaskCard(card), { encoding: "utf8", flag: "wx" });
    await rename(temporary, file);
  } catch (error) {
    await unlink(temporary).catch(() => undefined);
    throw new TaskToolError(`${file}: Task Card를 기록할 수 없습니다. ${error instanceof Error ? error.message : String(error)}`);
  }
}

export async function createTaskCardAtomically(file: string, card: TaskCard): Promise<void> {
  const temporary = `${file}.${process.pid}.${Date.now()}.tmp`;
  try {
    await writeFile(temporary, serializeTaskCard(card), { encoding: "utf8", flag: "wx" });
    await link(temporary, file);
    await unlink(temporary);
  } catch (error) {
    await unlink(temporary).catch(() => undefined);
    throw new TaskToolError(`${file}: 새 Task Card를 기록할 수 없습니다. ${error instanceof Error ? error.message : String(error)}`);
  }
}

export function extractTasksDir(args: string[]): { tasksDir: string; rest: string[] } {
  const rest: string[] = [];
  let tasksDir = resolve(process.cwd(), "tasks");
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] === "--tasks-dir") {
      const value = args[index + 1];
      if (!value) throw new TaskToolError("--tasks-dir 뒤에 경로가 필요합니다.", 2);
      tasksDir = resolve(value);
      index += 1;
    } else rest.push(args[index]);
  }
  return { tasksDir, rest };
}

export function runCli(main: () => Promise<void>): void {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = error instanceof TaskToolError ? error.exitCode : 1;
  });
}
