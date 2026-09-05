import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import { runScript, temporaryTasks, validCard, writeCard } from "./test-helpers.ts";

test("Card를 canonical 이름으로 등록하고 lint할 수 있다", async () => {
  const { root, tasksDir } = await temporaryTasks([validCard("sk-1")]);
  const draft = join(root, "draft.yaml");
  await writeCard(draft, validCard("sk-2"));
  const added = await runScript("add-task.ts", ["register", draft, "--depends-on", "sk-1", "--tasks-dir", tasksDir]);
  assert.equal(added.code, 0, added.stderr);
  const linted = await runScript("lint-task.ts", [join(tasksDir, "sk-2.yaml")]);
  assert.equal(linted.code, 0, linted.stderr);
  assert.match(await readFile(join(tasksDir, "sk-2.yaml"), "utf8"), /depends_on:\n  - sk-1/);
});

test("등록 충돌은 기존 Card를 변경하지 않는다", async () => {
  const { root, tasksDir } = await temporaryTasks([validCard("sk-1")]);
  const existing = join(tasksDir, "sk-1.yaml");
  const before = await readFile(existing, "utf8");
  const draft = join(root, "draft.yaml");
  const replacement = validCard("sk-1");
  replacement.title = "덮어쓰면 안 되는 제목";
  await writeCard(draft, replacement);
  const result = await runScript("add-task.ts", ["register", draft, "--tasks-dir", tasksDir]);
  assert.equal(result.code, 1);
  assert.equal(await readFile(existing, "utf8"), before);
});

test("기존 Task에 dependency를 추가하고 재실행해도 결과가 같다", async () => {
  const { tasksDir } = await temporaryTasks([validCard("sk-1"), validCard("sk-2")]);
  const args = ["dependency", "sk-2", "sk-1", "--tasks-dir", tasksDir];
  const first = await runScript("add-task.ts", args);
  assert.equal(first.code, 0, first.stderr);
  const file = join(tasksDir, "sk-2.yaml");
  const once = await readFile(file, "utf8");
  const second = await runScript("add-task.ts", args);
  assert.equal(second.code, 0, second.stderr);
  assert.equal(await readFile(file, "utf8"), once);
  assert.match(second.stdout, /이미 존재/);
});

test("cycle을 만드는 dependency는 원본을 변경하지 않는다", async () => {
  const { tasksDir } = await temporaryTasks([validCard("sk-1"), validCard("sk-2", ["sk-1"])]);
  const file = join(tasksDir, "sk-1.yaml");
  const before = await readFile(file, "utf8");
  const result = await runScript("add-task.ts", ["dependency", "sk-1", "sk-2", "--tasks-dir", tasksDir]);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /cycle/);
  assert.equal(await readFile(file, "utf8"), before);
});

test("전이 dependency를 추가하면 원본을 변경하지 않는다", async () => {
  const { tasksDir } = await temporaryTasks([
    validCard("sk-1"),
    validCard("sk-2", ["sk-1"]),
    validCard("sk-3", ["sk-2"]),
  ]);
  const file = join(tasksDir, "sk-3.yaml");
  const before = await readFile(file, "utf8");
  const result = await runScript("add-task.ts", ["dependency", "sk-3", "sk-1", "--tasks-dir", tasksDir]);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /전이적으로 충족/);
  assert.equal(await readFile(file, "utf8"), before);
});
