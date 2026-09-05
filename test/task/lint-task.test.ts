import assert from "node:assert/strict";
import { join } from "node:path";
import test from "node:test";
import { runScript, temporaryTasks, validCard, writeCard } from "./test-helpers.ts";

test("유효한 Task Card를 승인한다", async () => {
  const { tasksDir } = await temporaryTasks([validCard("sk-5")]);
  const result = await runScript("lint-task.ts", [join(tasksDir, "sk-5.yaml")]);
  assert.equal(result.code, 0);
  assert.match(result.stdout, /유효한 Task Card/);
});

test("필수 검증과 Step 순서 위반을 함께 보고한다", async () => {
  const card = validCard("sk-5");
  card.spec_refs = [];
  card.verification = [];
  card.steps[0].id = "sk-5-2";
  const { tasksDir } = await temporaryTasks();
  const file = join(tasksDir, "sk-5.yaml");
  await writeCard(file, card);
  const result = await runScript("lint-task.ts", [file]);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /spec_refs/);
  assert.match(result.stderr, /verification/);
  assert.match(result.stderr, /sk-5-1/);
});

test("Task가 done이면 모든 Step도 done이어야 한다", async () => {
  const card = validCard("sk-5");
  card.status = "done";
  const { tasksDir } = await temporaryTasks();
  const file = join(tasksDir, "sk-5.yaml");
  await writeCard(file, card);
  const result = await runScript("lint-task.ts", [file]);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /모든 Step도 done/);
});

test("잘못된 명령 사용은 종료 코드 2를 반환한다", async () => {
  const result = await runScript("lint-task.ts");
  assert.equal(result.code, 2);
  assert.match(result.stderr, /사용법/);
});

test("canonical YAML 형식이 아니면 거부한다", async () => {
  const { tasksDir } = await temporaryTasks();
  const file = join(tasksDir, "sk-5.yaml");
  const card = validCard("sk-5");
  await writeCard(file, card);
  const { readFile, writeFile } = await import("node:fs/promises");
  const canonical = await readFile(file, "utf8");
  await writeFile(file, `# canonical 형식을 깨는 주석\n${canonical}`);
  const result = await runScript("lint-task.ts", [file]);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /canonical YAML/);
});
