#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { ClickUpClient } from "./clickup-client.mjs";
import { loadWorkItems } from "./task-source.mjs";
import { applySyncPlan, createSyncPlan, formatPlan, validateClickUpMetadata, validateConfig } from "./reconciliation.mjs";

function usage() {
  return "usage: node scripts/clickup/sync-task-graph.mjs <plan|apply> --config <file> [--tasks-dir <path>]";
}

function parseArgs(args) {
  const command = args[0];
  if (!new Set(["plan", "apply"]).has(command)) throw new Error(usage());
  let configFile;
  let tasksDir = resolve(process.cwd(), "tasks");
  for (let index = 1; index < args.length; index += 1) {
    if (args[index] === "--config") configFile = args[++index];
    else if (args[index] === "--tasks-dir") tasksDir = resolve(args[++index]);
    else throw new Error(`${usage()}\nunknown argument: ${args[index]}`);
  }
  if (!configFile) throw new Error(`${usage()}\n--config is required`);
  return { command, configFile: resolve(configFile), tasksDir };
}

async function main() {
  const { command, configFile, tasksDir } = parseArgs(process.argv.slice(2));
  const config = validateConfig(JSON.parse(await readFile(configFile, "utf8")));
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
