#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { loadEnvFile } from "node:process";
import { fileURLToPath } from "node:url";
import { ClickUpClient } from "./clickup-client.mjs";
import {
  applySyncPlan,
  clickUpResultUrl,
  createSyncPlan,
  formatPlan,
  validateClickUpMetadata,
  validateConfig,
} from "./reconciliation.mjs";
import { loadWorkItems } from "./task-source.mjs";

const CONFIG_FILE = fileURLToPath(new URL("../../config/task-sync.config.json", import.meta.url));
const ENV_FILE = fileURLToPath(new URL("../../config/.env.local", import.meta.url));
const TASKS_DIR = fileURLToPath(new URL("../../tasks", import.meta.url));

function usage() {
  return "사용법: node scripts/clickup/sync-task-graph.mjs sync [--dry]";
}

function parseArgs(args) {
  if (args[0] !== "sync") throw new Error(usage());

  let dryRun = false;
  for (const argument of args.slice(1)) {
    if (argument === "--dry") {
      dryRun = true;
    } else {
      throw new Error(`${usage()}\n알 수 없는 인수입니다: ${argument}`);
    }
  }
  return { dryRun };
}

function loadEnvironment() {
  const explicitToken = process.env.CLICKUP_TOKEN;
  try {
    loadEnvFile(ENV_FILE);
  } catch (error) {
    if (error && typeof error === "object" && "code" in error && error.code === "ENOENT") return;
    const code = error && typeof error === "object" && "code" in error ? ` (${error.code})` : "";
    throw new Error(`${ENV_FILE}: 환경 변수 파일을 읽을 수 없습니다${code}.`);
  } finally {
    if (explicitToken !== undefined) process.env.CLICKUP_TOKEN = explicitToken;
  }
}

async function readConfig() {
  let text;
  try {
    text = await readFile(CONFIG_FILE, "utf8");
  } catch (error) {
    const code = error && typeof error === "object" && "code" in error ? ` (${error.code})` : "";
    throw new Error(`${CONFIG_FILE}: ClickUp 설정 파일을 읽을 수 없습니다${code}.`);
  }

  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${CONFIG_FILE}: ClickUp 설정 JSON을 해석할 수 없습니다.`);
  }
}

function readClickUpConfig(config) {
  if (typeof config !== "object" || config === null || Array.isArray(config)) {
    throw new Error(`${CONFIG_FILE}: 설정 객체여야 합니다.`);
  }
  if (typeof config.targets !== "object" || config.targets === null || Array.isArray(config.targets)) {
    throw new Error(`${CONFIG_FILE}: targets 설정이 필요합니다.`);
  }
  if (typeof config.targets.clickup !== "object" || config.targets.clickup === null) {
    throw new Error(`${CONFIG_FILE}: targets.clickup 설정이 필요합니다.`);
  }
  return validateConfig(config.targets.clickup);
}

async function main() {
  const { dryRun } = parseArgs(process.argv.slice(2));
  loadEnvironment();

  const config = readClickUpConfig(await readConfig());
  const workItems = await loadWorkItems(TASKS_DIR);
  const client = new ClickUpClient(process.env.CLICKUP_TOKEN);

  const [list, fields, tasks] = await Promise.all([
    client.getList(config.list_id),
    client.getCustomFields(config.list_id),
    client.listTasks(config.list_id),
  ]);
  validateClickUpMetadata(config, list, fields);

  const sourceIds = new Set(workItems.map((item) => item.id));
  const enriched = await Promise.all(
    tasks.map(async (task) => {
      const sourceId = task.custom_fields?.find(
        (field) => field.id === config.source_id_field_id,
      )?.value;
      if (!sourceId || !sourceIds.has(String(sourceId)) || task.parent) return task;
      return { ...task, ...(await client.getTask(task.id)) };
    }),
  );

  const plan = createSyncPlan(workItems, enriched, config);
  if (dryRun) {
    process.stdout.write(formatPlan(plan));
    return;
  }

  await applySyncPlan(plan, workItems, config, client);
  process.stdout.write(
    `ClickUp 업로드가 완료되었습니다. ${clickUpResultUrl(plan, workItems)}에서 확인 가능합니다.\n`,
  );
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
