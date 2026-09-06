import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { describe, expect, it } from "vite-plus/test";
import { runScript, temporaryTasks, validCard, writeCard } from "./test-helpers.ts";

describe("Task Card lint", () => {
  it("canonical YAML Card를 승인한다", async () => {
    const { tasksDir } = await temporaryTasks([validCard("sk-5")]);
    const result = await runScript("lint-task.ts", [join(tasksDir, "sk-5.yaml")]);
    expect(result.code).toBe(0);
    expect(result.stdout).toContain("유효한 Task Card");
  });

  it("필수 검증과 Step 순서 위반을 함께 보고한다", async () => {
    const card = validCard("sk-5");
    card.requirements = [];
    card.verification = [];
    card.steps[0].id = "sk-5-2";
    const { tasksDir } = await temporaryTasks();
    const file = join(tasksDir, "sk-5.yaml");
    await writeCard(file, card);
    const result = await runScript("lint-task.ts", [file]);
    expect(result.code).toBe(1);
    expect(result.stderr).toContain("requirements");
    expect(result.stderr).toContain("verification");
    expect(result.stderr).toContain("sk-5-1");
  });

  it("비정규 YAML과 중복 키를 거부한다", async () => {
    const { tasksDir } = await temporaryTasks();
    const file = join(tasksDir, "sk-5.yaml");
    await writeCard(file, validCard("sk-5"));
    const canonical = await readFile(file, "utf8");
    await writeFile(file, `# 주석\n${canonical}`);
    expect((await runScript("lint-task.ts", [file])).stderr).toContain("canonical YAML");

    await writeFile(file, "id: sk-5\nid: sk-6\n");
    expect((await runScript("lint-task.ts", [file])).stderr).toContain("Map keys must be unique");
  });

  it("JSON 확장자와 잘못된 명령 사용을 거부한다", async () => {
    const { root } = await temporaryTasks();
    const json = join(root, "sk-5.json");
    await writeFile(json, JSON.stringify(validCard("sk-5")));
    const extension = await runScript("lint-task.ts", [json]);
    expect(extension.code).toBe(1);
    expect(extension.stderr).toContain(".yaml");

    const usage = await runScript("lint-task.ts");
    expect(usage.code).toBe(2);
    expect(usage.stderr).toContain("사용법");
  });
});
