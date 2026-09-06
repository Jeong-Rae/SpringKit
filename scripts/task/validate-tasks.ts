import {
  extractTasksDir,
  loadTaskCards,
  runCli,
  TaskToolError,
  validateTaskGraph,
} from "./task-core.ts";

runCli(async () => {
  const { tasksDir, rest } = extractTasksDir(process.argv.slice(2));
  if (rest.length !== 0) {
    throw new TaskToolError(
      "사용법: node <skill>/scripts/validate-tasks.mjs [--tasks-dir <path>]",
      2,
    );
  }
  const cards = await loadTaskCards(tasksDir);
  const errors = validateTaskGraph(cards);
  if (errors.length > 0) throw new TaskToolError(errors.join("\n"));
  console.log(`${cards.size}개 Task로 구성된 DAG가 유효합니다.`);
});
