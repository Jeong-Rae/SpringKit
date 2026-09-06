import { cp, mkdtemp, readdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vite-plus/test";
import {
  repositoryRoot,
  runPackagedScript,
  temporaryTasks,
  validCard,
  writeCard,
} from "./test-helpers.ts";

describe("패키징한 Task Graph Skill", () => {
  it("네 개의 독립 실행형 모듈로 전체 관리 흐름을 완료한다", async () => {
    const isolated = await mkdtemp(join(tmpdir(), "springkit-packaged-skill-"));
    const source = join(repositoryRoot, ".agents/skills/task-graph/scripts");
    const scriptsDir = join(isolated, "scripts");
    await cp(source, scriptsDir, { recursive: true });
    expect((await readdir(scriptsDir)).sort()).toEqual([
      "add-task.mjs",
      "issue-task-id.mjs",
      "lint-task.mjs",
      "validate-tasks.mjs",
    ]);

    const { root, tasksDir } = await temporaryTasks([validCard("sk-5")]);
    expect(
      (await runPackagedScript(scriptsDir, "validate-tasks.mjs", ["--tasks-dir", tasksDir])).code,
    ).toBe(0);
    expect(
      (
        await runPackagedScript(scriptsDir, "issue-task-id.mjs", ["--tasks-dir", tasksDir])
      ).stdout.trim(),
    ).toBe("sk-6");

    const draft = join(root, "sk-6.yaml");
    await writeCard(draft, validCard("sk-6"));
    expect(
      (
        await runPackagedScript(scriptsDir, "add-task.mjs", [
          "register",
          draft,
          "--depends-on",
          "sk-5",
          "--tasks-dir",
          tasksDir,
        ])
      ).code,
    ).toBe(0);
    expect(
      (await runPackagedScript(scriptsDir, "lint-task.mjs", [join(tasksDir, "sk-6.yaml")])).code,
    ).toBe(0);
    expect(
      (await runPackagedScript(scriptsDir, "validate-tasks.mjs", ["--tasks-dir", tasksDir])).code,
    ).toBe(0);
  });
});
