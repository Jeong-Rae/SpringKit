import assert from "node:assert/strict";
import test from "node:test";
import { runScript, temporaryTasks, validCard } from "./test-helpers.ts";

test("선형 DAG를 승인한다", async () => {
  const { tasksDir } = await temporaryTasks([
    validCard("sk-1"),
    validCard("sk-2", ["sk-1"]),
    validCard("sk-3", ["sk-2"]),
  ]);
  const result = await runScript("validate-tasks.ts", ["--tasks-dir", tasksDir]);
  assert.equal(result.code, 0, result.stderr);
  assert.match(result.stdout, /3개 Task/);
});

test("서로 독립적인 sibling Task를 승인한다", async () => {
  const { tasksDir } = await temporaryTasks([
    validCard("sk-1"),
    validCard("sk-2", ["sk-1"]),
    validCard("sk-3", ["sk-1"]),
  ]);
  const result = await runScript("validate-tasks.ts", ["--tasks-dir", tasksDir]);
  assert.equal(result.code, 0, result.stderr);
});

test("누락된 선행 Task를 보고한다", async () => {
  const { tasksDir } = await temporaryTasks([validCard("sk-2", ["sk-1"])]);
  const result = await runScript("validate-tasks.ts", ["--tasks-dir", tasksDir]);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /선행 Task를 찾을 수 없습니다: sk-1/);
});

test("cycle 경로를 보고한다", async () => {
  const { tasksDir } = await temporaryTasks([
    validCard("sk-1", ["sk-3"]),
    validCard("sk-2", ["sk-1"]),
    validCard("sk-3", ["sk-2"]),
  ]);
  const result = await runScript("validate-tasks.ts", ["--tasks-dir", tasksDir]);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /cycle/);
  assert.match(result.stderr, /sk-1/);
  assert.match(result.stderr, /sk-2/);
  assert.match(result.stderr, /sk-3/);
});

test("전이 dependency를 보고한다", async () => {
  const { tasksDir } = await temporaryTasks([
    validCard("sk-1"),
    validCard("sk-2", ["sk-1"]),
    validCard("sk-3", ["sk-1", "sk-2"]),
  ]);
  const result = await runScript("validate-tasks.ts", ["--tasks-dir", tasksDir]);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /sk-1는 전이적으로 충족/);
});

test("여러 그래프 오류를 함께 보고한다", async () => {
  const { tasksDir } = await temporaryTasks([
    validCard("sk-1", ["sk-9"]),
    validCard("sk-2", ["sk-8"]),
  ]);
  const result = await runScript("validate-tasks.ts", ["--tasks-dir", tasksDir]);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /sk-9/);
  assert.match(result.stderr, /sk-8/);
});
