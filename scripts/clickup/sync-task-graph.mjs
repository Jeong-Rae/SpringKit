#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { ClickUpClient } from "./clickup-client.mjs";
import { loadWorkItems } from "./task-source.mjs";
import { applySyncPlan, createSyncPlan, formatPlan, validateClickUpMetadata, validateConfig } from "./reconciliation.mjs";

function usage() {
  return "사용법: node scripts/clickup/sync-task-graph.mjs <plan|apply> --config <file> [--tasks-dir <path>]";
}

function nextArgument(args, index, name) {
  const value = args[index + 1];
  if (!value) throw new Error(`${name} 뒤에 값이 필요합니다.`);
  return value;
}

function parseArgs(args) {
  const command = args[0];
  if (!new Set(["plan", "apply"]).has(command)) throw new Error(usage());
  let configFile;
  let tasksDir = resolve(process.cwd(), "tasks");
  for (let index = 1; index < args.length; index += 1) {
    if (args[index] === "--config") {
      configFile = nextArgument(args, index, "--config");
      index += 1;
    } else if (args[index] === "--tasks-dir") {
      tasksDir = resolve(nextArgument(args, index, "--tasks-dir"));
      index += 1;
    } else {
      throw new Error(`${usage()}\n알 수 없는 인수입니다: ${args[index]}`);
    }
  }
  if (!configFile) throw new Error(`${usage()}\n--config 값이 필요합니다.`);
  return { command, configFile: resolve(configFile), tasksDir };
}

async function readConfig(file) {
  let text;
  try {
    text = await readFile(file, "utf8");
  } catch (error) {
    const code = error && typeof error === "object" && "code" in error ? ` (${error.code})` : "";
    throw new Error(`${file}: config 파일을 읽을 수 없습니다${code}.`);
  }

  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${file}: config JSON을 해석할 수 없습니다.`);
  }
}

async function main() {
  const { command, configFile, tasksDir } = parseArgs(process.argv.slice(2));
  const config = validateConfig(await readConfig(configFile));
  const workItems = await loadWorkItems(tasksDir);
  const client = new ClickUpClient(process.env.CLICKUP_TOKEN);

  const [list, fields, tasks] = await Promise.all([
    client.getList(config.list_id),
    client.getCustomFields(config.list_id),
    client.listTasks(config.list_id),
  ]);
  validateClickUpMetadata(config, list, fields);

  const sourceIds = new Set(workItems.map((item) => item.id));
  const enriched = await Promise.all(tasks.map(async (task) => {
    const sourceId = task.custom_fields?.find((field) => field.id === config.source_id_field_id)?.value;
    if (!sourceId || !sourceIds.has(String(sourceId)) || task.parent) return task;
    return { ...task, ...(await client.getTask(task.id)) };
  }));

  const plan = createSyncPlan(workItems, enriched, config);
  process.stdout.write(formatPlan(plan));
  if (command === "apply") await applySyncPlan(plan, workItems, config, client);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
