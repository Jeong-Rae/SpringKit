import assert from "node:assert/strict";
import { join } from "node:path";
import test from "node:test";
import { runScript, temporaryTasks, validCard, writeCard } from "./test-helpers.mjs";
test("빈 Task 디렉터리에서 sk-1을 발급한다", async ()=>{
    const { tasksDir } = await temporaryTasks();
    const result = await runScript("issue-task-id.mjs", [
        "--tasks-dir",
        tasksDir
    ]);
    assert.equal(result.code, 0);
    assert.equal(result.stdout, "sk-1\n");
});
test("순번의 공백을 채우지 않고 최대 순번 다음 ID를 발급한다", async ()=>{
    const { tasksDir } = await temporaryTasks([
        validCard("sk-1"),
        validCard("sk-3"),
        validCard("sk-5")
    ]);
    const result = await runScript("issue-task-id.mjs", [
        "--tasks-dir",
        tasksDir
    ]);
    assert.equal(result.code, 0);
    assert.equal(result.stdout, "sk-6\n");
});
test("잘못된 Card가 있으면 ID를 발급하지 않는다", async ()=>{
    const { tasksDir } = await temporaryTasks();
    const invalid = validCard("sk-2");
    invalid.verification = [];
    await writeCard(join(tasksDir, "sk-2.json"), invalid);
    const result = await runScript("issue-task-id.mjs", [
        "--tasks-dir",
        tasksDir
    ]);
    assert.equal(result.code, 1);
    assert.equal(result.stdout, "");
    assert.match(result.stderr, /verification/);
});
test("알 수 없는 인자는 종료 코드 2로 거부한다", async ()=>{
    const { tasksDir } = await temporaryTasks();
    const result = await runScript("issue-task-id.mjs", [
        "--tasks-dir",
        tasksDir,
        "extra"
    ]);
    assert.equal(result.code, 2);
});

