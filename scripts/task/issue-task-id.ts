import { extractTasksDir, loadTaskCards, runCli, TaskToolError } from "./task-core.ts";

runCli(async () => {
  const { tasksDir, rest } = extractTasksDir(process.argv.slice(2));
  if (rest.length !== 0) {
    throw new TaskToolError("사용법: node ./scripts/task/issue-task-id.ts [--tasks-dir <path>]", 2);
  }
  const cards = await loadTaskCards(tasksDir);
  const maximum = [...cards.keys()].reduce((current, id) => {
    const sequence = Number(id.slice("sk-".length));
    return Math.max(current, sequence);
  }, 0);
  console.log(`sk-${maximum + 1}`);
});
