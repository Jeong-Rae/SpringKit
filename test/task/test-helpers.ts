import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawn } from "node:child_process";
import { stringify } from "yaml";

export const repositoryRoot = resolve(import.meta.dirname, "../..");

export function validCard(id = "sk-1", dependsOn: string[] = []) {
  return {
    id,
    title: "테스트 Task",
    type: "feature",
    status: "pending",
    goal: "사용자는 Task 동작을 검증할 수 있다.",
    depends_on: dependsOn,
    spec_refs: ["SPEC"],
    scope: { in: ["검증 대상"], out: [] },
    verification: ["전체 Task 결과를 확인할 수 있다."],
    issues: [],
    references: [],
    steps: [{
      id: `${id}-1`,
      title: "동작 구현",
      status: "pending",
      objective: "검증 가능한 동작을 제공한다.",
      work: ["동작을 구현한다."],
      verification: ["예상한 결과가 반환된다."],
      issues: [],
      references: [],
    }],
  };
}

export async function temporaryTasks(cards: ReturnType<typeof validCard>[] = []) {
  const root = await mkdtemp(join(tmpdir(), "springkit-task-test-"));
  const tasksDir = join(root, "tasks");
  await mkdir(tasksDir);
  for (const card of cards) await writeFile(join(tasksDir, `${card.id}.yaml`), stringify(card, { indent: 2, lineWidth: 0 }));
  return { root, tasksDir };
}

export async function writeCard(file: string, card: unknown): Promise<void> {
  await writeFile(file, stringify(card, { indent: 2, lineWidth: 0 }));
}

export function runScript(script: string, args: string[] = []): Promise<{ code: number | null; stdout: string; stderr: string }> {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(process.execPath, [join(repositoryRoot, "scripts/task", script), ...args], { cwd: repositoryRoot });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("error", reject);
    child.on("close", (code) => resolvePromise({ code, stdout, stderr }));
  });
}
