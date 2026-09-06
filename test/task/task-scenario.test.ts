import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { describe, expect, it } from "vite-plus/test";
import { runScript, temporaryTasks, validCard, writeCard } from "./test-helpers.ts";

describe("Task 관리 시나리오", () => {
  it("ID 발급부터 전체 DAG 재검증까지 완료한다", async () => {
    const { root, tasksDir } = await temporaryTasks([validCard("sk-5")]);
    expect((await runScript("validate-tasks.ts", ["--tasks-dir", tasksDir])).code).toBe(0);

    const issued = await runScript("issue-task-id.ts", ["--tasks-dir", tasksDir]);
    expect(issued.stdout.trim()).toBe("sk-6");
    const draft = join(root, "new-task.yaml");
    await writeCard(draft, validCard("sk-6"));
    expect(
      (await runScript("add-task.ts", ["register", draft, "--tasks-dir", tasksDir])).code,
    ).toBe(0);
    expect(
      (await runScript("add-task.ts", ["dependency", "sk-6", "sk-5", "--tasks-dir", tasksDir]))
        .code,
    ).toBe(0);
    expect((await runScript("lint-task.ts", [join(tasksDir, "sk-6.yaml")])).code).toBe(0);
    expect((await runScript("validate-tasks.ts", ["--tasks-dir", tasksDir])).code).toBe(0);
  });

  it("잘못된 dependency는 기존 DAG를 보존한다", async () => {
    const { tasksDir } = await temporaryTasks([validCard("sk-5"), validCard("sk-6", ["sk-5"])]);
    const target = join(tasksDir, "sk-5.yaml");
    const before = await readFile(target, "utf8");
    const rejected = await runScript("add-task.ts", [
      "dependency",
      "sk-5",
      "sk-6",
      "--tasks-dir",
      tasksDir,
    ]);
    expect(rejected.stderr).toContain("cycle");
    expect(await readFile(target, "utf8")).toBe(before);
    expect((await runScript("validate-tasks.ts", ["--tasks-dir", tasksDir])).code).toBe(0);
  });
});
