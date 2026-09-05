import { resolve } from "node:path";
import { readTaskCard, runCli, TaskToolError } from "./task-core.mjs";
runCli(async ()=>{
    const args = process.argv.slice(2);
    if (args.length !== 1) throw new TaskToolError("사용법: node <skill>/scripts/lint-task.mjs <card-file>", 2);
    const file = resolve(args[0]);
    await readTaskCard(file, file.includes(`${process.platform === "win32" ? "\\" : "/"}tasks${process.platform === "win32" ? "\\" : "/"}`), true);
    console.log(`${file}: 유효한 Task Card입니다.`);
});

