import { join, resolve } from "node:path";
import {
	createTaskCardAtomically,
	extractTasksDir,
	loadTaskCards,
	readTaskCard,
	runCli,
	TaskToolError,
	validateTaskCard,
	validateTaskGraph,
	writeTaskCardAtomically,
} from "./task-core.ts";

function requireValidGraph(
	cards: Awaited<ReturnType<typeof loadTaskCards>>,
): void {
	const errors = validateTaskGraph(cards);
	if (errors.length > 0) throw new TaskToolError(errors.join("\n"));
}

runCli(async () => {
	const { tasksDir, rest } = extractTasksDir(process.argv.slice(2));
	const command = rest[0];
	const cards = await loadTaskCards(tasksDir);
	requireValidGraph(cards);

	if (command === "register") {
		const source = rest[1];
		if (!source) {
			throw new TaskToolError(
				"사용법: node ./scripts/task/add-task.ts register <card-file> [--depends-on <task-id>...] [--tasks-dir <path>]",
				2,
			);
		}
		const dependencies: string[] = [];
		for (let index = 2; index < rest.length; index += 1) {
			if (rest[index] !== "--depends-on" || !rest[index + 1]) {
				throw new TaskToolError("--depends-on 뒤에 Task ID가 필요합니다.", 2);
			}
			dependencies.push(rest[index + 1]);
			index += 1;
		}
		const card = await readTaskCard(resolve(source));
		if (cards.has(card.id))
			throw new TaskToolError(`${card.id}: 이미 등록된 Task입니다.`);
		card.depends_on = [...card.depends_on, ...dependencies];
		const cardErrors = validateTaskCard(card, card.id);
		if (cardErrors.length > 0) throw new TaskToolError(cardErrors.join("\n"));
		const candidate = new Map(cards);
		candidate.set(card.id, { card, file: join(tasksDir, `${card.id}.yaml`) });
		requireValidGraph(candidate);
		await createTaskCardAtomically(join(tasksDir, `${card.id}.yaml`), card);
		console.log(`${card.id}: Task를 등록했습니다.`);
		return;
	}

	if (command === "dependency") {
		if (rest.length !== 3) {
			throw new TaskToolError(
				"사용법: node ./scripts/task/add-task.ts dependency <task-id> <dependency-id> [--tasks-dir <path>]",
				2,
			);
		}
		const taskId = rest[1];
		const dependencyId = rest[2];
		const entry = cards.get(taskId);
		if (!entry) throw new TaskToolError(`${taskId}: Task를 찾을 수 없습니다.`);
		if (entry.card.depends_on.includes(dependencyId)) {
			console.log(`${taskId}: dependency가 이미 존재합니다: ${dependencyId}`);
			return;
		}
		const updated = {
			...entry.card,
			depends_on: [...entry.card.depends_on, dependencyId],
		};
		const candidate = new Map(cards);
		candidate.set(taskId, { card: updated, file: entry.file });
		requireValidGraph(candidate);
		await writeTaskCardAtomically(entry.file, updated);
		console.log(`${taskId}: dependency를 추가했습니다: ${dependencyId}`);
		return;
	}

	throw new TaskToolError(
		"사용법: node ./scripts/task/add-task.ts <register|dependency> ...",
		2,
	);
});
