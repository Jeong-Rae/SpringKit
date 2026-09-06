import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { describe, expect, it } from "vite-plus/test";
import { runScript, temporaryTasks, validCard, writeCard } from "./test-helpers.ts";

describe("Task 등록과 dependency 관리", () => {
  it("Card를 canonical 이름으로 등록한다", async () => {
    const { root, tasksDir } = await temporaryTasks([validCard("sk-1")]);
    const draft = join(root, "draft.yaml");
    await writeCard(draft, validCard("sk-2"));
    const result = await runScript("add-task.ts", [
      "register",
      draft,
      "--depends-on",
      "sk-1",
      "--tasks-dir",
      tasksDir,
    ]);
    expect(result.code).toBe(0);
    expect(await readFile(join(tasksDir, "sk-2.yaml"), "utf8")).toContain("depends_on:\n  - sk-1");
  });

  it("등록 충돌은 기존 Card를 변경하지 않는다", async () => {
    const { root, tasksDir } = await temporaryTasks([validCard("sk-1")]);
    const existing = join(tasksDir, "sk-1.yaml");
    const before = await readFile(existing, "utf8");
    const draft = join(root, "draft.yaml");
    await writeCard(draft, validCard("sk-1"));
    expect(
      (await runScript("add-task.ts", ["register", draft, "--tasks-dir", tasksDir])).code,
    ).toBe(1);
    expect(await readFile(existing, "utf8")).toBe(before);
  });

  it("dependency 추가는 멱등성을 보장한다", async () => {
    const { tasksDir } = await temporaryTasks([validCard("sk-1"), validCard("sk-2")]);
    const args = ["dependency", "sk-2", "sk-1", "--tasks-dir", tasksDir];
    expect((await runScript("add-task.ts", args)).code).toBe(0);
    const file = join(tasksDir, "sk-2.yaml");
    const once = await readFile(file, "utf8");
    const repeated = await runScript("add-task.ts", args);
    expect(repeated.code).toBe(0);
    expect(repeated.stdout).toContain("이미 존재");
    expect(await readFile(file, "utf8")).toBe(once);
  });

  it("cycle과 전이 dependency는 원본을 보존한다", async () => {
    const { tasksDir } = await temporaryTasks([
      validCard("sk-1"),
      validCard("sk-2", ["sk-1"]),
      validCard("sk-3", ["sk-2"]),
    ]);
    const first = join(tasksDir, "sk-1.yaml");
    const third = join(tasksDir, "sk-3.yaml");
    const firstBefore = await readFile(first, "utf8");
    const thirdBefore = await readFile(third, "utf8");

    expect(
      (await runScript("add-task.ts", ["dependency", "sk-1", "sk-3", "--tasks-dir", tasksDir]))
        .stderr,
    ).toContain("cycle");
    expect(
      (await runScript("add-task.ts", ["dependency", "sk-3", "sk-1", "--tasks-dir", tasksDir]))
        .stderr,
    ).toContain("전이적으로 충족");
    expect(await readFile(first, "utf8")).toBe(firstBefore);
    expect(await readFile(third, "utf8")).toBe(thirdBefore);
  });
});
