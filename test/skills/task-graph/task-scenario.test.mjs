import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import { runScript, temporaryTasks, validCard, writeCard } from "./test-helpers.mjs";
test("ID 발급부터 전체 DAG 재검증까지 Task 관리 흐름을 완료한다", async ()=>{
    const { root, tasksDir } = await temporaryTasks([
        validCard("sk-5")
    ]);
    const initialValidation = await runScript("validate-tasks.mjs", [
        "--tasks-dir",
        tasksDir
    ]);
    assert.equal(initialValidation.code, 0, initialValidation.stderr);
    const issued = await runScript("issue-task-id.mjs", [
        "--tasks-dir",
        tasksDir
    ]);
    assert.equal(issued.code, 0, issued.stderr);
    assert.equal(issued.stdout.trim(), "sk-6");
    const draft = join(root, "new-task.json");
    await writeCard(draft, validCard(issued.stdout.trim()));
    const registered = await runScript("add-task.mjs", [
        "register",
        draft,
        "--tasks-dir",
        tasksDir
    ]);
    assert.equal(registered.code, 0, registered.stderr);
    const dependency = await runScript("add-task.mjs", [
        "dependency",
        "sk-6",
        "sk-5",
        "--tasks-dir",
        tasksDir
    ]);
    assert.equal(dependency.code, 0, dependency.stderr);
    const linted = await runScript("lint-task.mjs", [
        join(tasksDir, "sk-6.json")
    ]);
    assert.equal(linted.code, 0, linted.stderr);
    const finalValidation = await runScript("validate-tasks.mjs", [
        "--tasks-dir",
        tasksDir
    ]);
    assert.equal(finalValidation.code, 0, finalValidation.stderr);
});
test("전체 흐름에서 잘못된 dependency는 기존 DAG를 보존한다", async ()=>{
    const { tasksDir } = await temporaryTasks([
        validCard("sk-5"),
        validCard("sk-6", [
            "sk-5"
        ])
    ]);
    const target = join(tasksDir, "sk-5.json");
    const before = await readFile(target, "utf8");
    const rejected = await runScript("add-task.mjs", [
        "dependency",
        "sk-5",
        "sk-6",
        "--tasks-dir",
        tasksDir
    ]);
    assert.equal(rejected.code, 1);
    assert.match(rejected.stderr, /cycle/);
    assert.equal(await readFile(target, "utf8"), before);
    const validation = await runScript("validate-tasks.mjs", [
        "--tasks-dir",
        tasksDir
    ]);
    assert.equal(validation.code, 0, validation.stderr);
});

