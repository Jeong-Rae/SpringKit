import { describe, expect, it } from "vite-plus/test";
import { runScript, temporaryTasks, validCard } from "./test-helpers.ts";

describe("Task DAG 검증", () => {
  it("선형 DAG와 sibling Task를 승인한다", async () => {
    const linear = await temporaryTasks([
      validCard("sk-1"),
      validCard("sk-2", ["sk-1"]),
      validCard("sk-3", ["sk-2"]),
    ]);
    expect(
      (await runScript("validate-tasks.ts", ["--tasks-dir", linear.tasksDir])).stdout,
    ).toContain("3개 Task");

    const sibling = await temporaryTasks([
      validCard("sk-1"),
      validCard("sk-2", ["sk-1"]),
      validCard("sk-3", ["sk-1"]),
    ]);
    expect((await runScript("validate-tasks.ts", ["--tasks-dir", sibling.tasksDir])).code).toBe(0);
  });

  it("누락 참조와 cycle을 보고한다", async () => {
    const missing = await temporaryTasks([validCard("sk-2", ["sk-1"])]);
    expect(
      (await runScript("validate-tasks.ts", ["--tasks-dir", missing.tasksDir])).stderr,
    ).toContain("선행 Task를 찾을 수 없습니다: sk-1");

    const cycle = await temporaryTasks([
      validCard("sk-1", ["sk-3"]),
      validCard("sk-2", ["sk-1"]),
      validCard("sk-3", ["sk-2"]),
    ]);
    const result = await runScript("validate-tasks.ts", ["--tasks-dir", cycle.tasksDir]);
    expect(result.stderr).toContain("cycle");
    expect(result.stderr).toContain("sk-1");
    expect(result.stderr).toContain("sk-2");
    expect(result.stderr).toContain("sk-3");
  });

  it("전이 dependency를 보고한다", async () => {
    const { tasksDir } = await temporaryTasks([
      validCard("sk-1"),
      validCard("sk-2", ["sk-1"]),
      validCard("sk-3", ["sk-1", "sk-2"]),
    ]);
    expect((await runScript("validate-tasks.ts", ["--tasks-dir", tasksDir])).stderr).toContain(
      "sk-1는 전이적으로 충족",
    );
  });
});
