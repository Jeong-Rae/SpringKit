import { compareWorkItemIds } from "./work-item.mjs";

function customFieldValue(task, fieldId) {
  const field = task.custom_fields?.find((candidate) => candidate.id === fieldId);
  return field?.value == null ? null : String(field.value);
}

function remoteParentId(task) {
  if (task.parent == null) return null;
  if (typeof task.parent === "string") return task.parent;
  if (typeof task.parent === "object" && task.parent.id) return String(task.parent.id);
  return String(task.parent);
}

function remoteStatus(task) {
  if (typeof task.status === "string") return task.status;
  return task.status?.status ?? "";
}

function remoteMarkdown(task) {
  return task.markdown_description ?? task.markdown_content ?? task.description ?? "";
}

function statusesEqual(left, right) {
  return String(left).trim().toLowerCase() === String(right).trim().toLowerCase();
}

export function validateConfig(config) {
  for (const key of ["list_id", "source_id_field_id", "source_type_field_id"]) {
    if (typeof config[key] !== "string" || config[key].trim() === "") throw new Error(`config.${key} is required`);
  }
  const statuses = ["pending", "running", "verifying", "blocked", "done", "cancelled"];
  if (typeof config.status_map !== "object" || config.status_map === null) throw new Error("config.status_map is required");
  for (const status of statuses) {
    if (typeof config.status_map[status] !== "string" || config.status_map[status].trim() === "") {
      throw new Error(`config.status_map.${status} is required`);
    }
  }
  return config;
}

export function validateClickUpMetadata(config, list, fields) {
  const availableStatuses = new Set((list.statuses ?? []).map((status) => String(status.status).trim().toLowerCase()));
  for (const [source, target] of Object.entries(config.status_map)) {
    if (!availableStatuses.has(target.trim().toLowerCase())) {
      throw new Error(`ClickUp List does not provide mapped status for ${source}: ${target}`);
    }
  }

  const fieldList = Array.isArray(fields?.fields) ? fields.fields : [];
  for (const [name, id] of [
    ["source_id_field_id", config.source_id_field_id],
    ["source_type_field_id", config.source_type_field_id],
  ]) {
    const field = fieldList.find((candidate) => candidate.id === id);
    if (!field) throw new Error(`ClickUp List does not provide config.${name}: ${id}`);
    if (!new Set(["short_text", "text"]).has(field.type)) {
      throw new Error(`config.${name} must reference a text field, got ${field.type}`);
    }
    if (Array.isArray(field.applied_objects) && field.applied_objects.length > 0) {
      const appliesToStandardTask = field.applied_objects.some(
        (applied) => Number(applied.object_type) === 19 && Number(applied.object_id) === 0,
      );
      if (!appliesToStandardTask) {
        throw new Error(`config.${name} is not applicable to the standard ClickUp Task type`);
      }
    }
  }
}

export function buildRemoteIndex(remoteTasks, sourceIdFieldId) {
  const bySourceId = new Map();
  const byRemoteId = new Map();

  for (const task of remoteTasks) {
    byRemoteId.set(String(task.id), task);
    const sourceId = customFieldValue(task, sourceIdFieldId);
    if (!sourceId) continue;
    if (bySourceId.has(sourceId)) throw new Error(`duplicate ClickUp source identity: ${sourceId}`);
    bySourceId.set(sourceId, task);
  }

  return { bySourceId, byRemoteId };
}

function checkHierarchy(workItems, remoteIndex) {
  const sourceById = new Map(workItems.map((item) => [item.id, item]));
  for (const item of workItems) {
    const remote = remoteIndex.bySourceId.get(item.id);
    if (!remote) continue;
    const parentId = remoteParentId(remote);
    if (item.kind === "task" && parentId !== null) {
      throw new Error(`${item.id}: source Task is a ClickUp subtask`);
    }
    if (item.kind === "step") {
      const remoteParent = remoteIndex.bySourceId.get(item.parentId);
      if (!remoteParent) throw new Error(`${item.id}: existing Step has no managed remote parent ${item.parentId}`);
      if (String(parentId) !== String(remoteParent.id)) {
        throw new Error(`${item.id}: ClickUp parent does not match source parent ${item.parentId}`);
      }
    }
  }

  for (const sourceId of remoteIndex.bySourceId.keys()) {
    if (!sourceById.has(sourceId)) continue;
  }
}

function dependencyTargets(remoteTask) {
  const result = new Set();
  for (const dependency of remoteTask.dependencies ?? []) {
    if (String(dependency.task_id) === String(remoteTask.id) && dependency.depends_on) {
      result.add(String(dependency.depends_on));
    }
  }
  return result;
}

export function createSyncPlan(workItems, remoteTasks, config) {
  const remoteIndex = buildRemoteIndex(remoteTasks, config.source_id_field_id);
  checkHierarchy(workItems, remoteIndex);
  const sourceById = new Map(workItems.map((item) => [item.id, item]));
  const operations = [];

  for (const item of workItems) {
    const remote = remoteIndex.bySourceId.get(item.id);
    const desiredStatus = config.status_map[item.status];
    if (!remote) {
      operations.push({ type: item.kind === "task" ? "CREATE_TASK" : "CREATE_SUBTASK", sourceId: item.id });
      continue;
    }

    const changes = {};
    if (remote.name !== item.displayTitle) changes.name = { before: remote.name, after: item.displayTitle };
    if (!statusesEqual(remoteStatus(remote), desiredStatus)) {
      changes.status = { before: remoteStatus(remote), after: desiredStatus };
    }
    if (remoteMarkdown(remote) !== item.markdown) {
      changes.markdown_content = { before: remoteMarkdown(remote), after: item.markdown };
    }
    if (Object.keys(changes).length > 0) operations.push({ type: "UPDATE_TASK", sourceId: item.id, changes });

    if (item.kind === "task") {
      const remoteType = customFieldValue(remote, config.source_type_field_id);
      if (remoteType !== item.type) {
        operations.push({ type: "SET_TYPE", sourceId: item.id, before: remoteType, after: item.type });
      }
    }
  }

  for (const item of workItems.filter((candidate) => candidate.kind === "task")) {
    const remote = remoteIndex.bySourceId.get(item.id);
    const desired = new Set(item.dependencies);
    if (!remote) {
      for (const dependency of desired) operations.push({ type: "ADD_DEPENDENCY", sourceId: item.id, dependencySourceId: dependency });
      continue;
    }

    const existingRemoteTargets = dependencyTargets(remote);
    const existingManaged = new Map();
    for (const remoteTargetId of existingRemoteTargets) {
      const target = remoteIndex.byRemoteId.get(remoteTargetId);
      if (!target) continue;
      const sourceId = customFieldValue(target, config.source_id_field_id);
      if (sourceId && sourceById.get(sourceId)?.kind === "task") existingManaged.set(sourceId, remoteTargetId);
    }

    for (const dependency of desired) {
      if (!existingManaged.has(dependency)) {
        operations.push({ type: "ADD_DEPENDENCY", sourceId: item.id, dependencySourceId: dependency });
      }
    }
    for (const dependency of existingManaged.keys()) {
      if (!desired.has(dependency)) {
        operations.push({ type: "REMOVE_DEPENDENCY", sourceId: item.id, dependencySourceId: dependency });
      }
    }
  }

  for (const sourceId of remoteIndex.bySourceId.keys()) {
    if (!sourceById.has(sourceId)) operations.push({ type: "REMOTE_ONLY", sourceId });
  }

  const touched = new Set(operations.map((operation) => operation.sourceId));
  for (const item of workItems) {
    if (!touched.has(item.id)) operations.push({ type: "NO_CHANGE", sourceId: item.id });
  }

  const rank = new Map([
    ["CREATE_TASK", 10], ["CREATE_SUBTASK", 20], ["UPDATE_TASK", 30], ["SET_TYPE", 40],
    ["ADD_DEPENDENCY", 50], ["REMOVE_DEPENDENCY", 60], ["REMOTE_ONLY", 70], ["NO_CHANGE", 80],
  ]);
  operations.sort((left, right) => compareWorkItemIds(left.sourceId, right.sourceId) || rank.get(left.type) - rank.get(right.type));
  return { operations, remoteIndex };
}

function createBody(item, config, remoteIndex) {
  const body = {
    name: item.displayTitle,
    status: config.status_map[item.status],
    markdown_content: item.markdown,
    custom_fields: [{ id: config.source_id_field_id, value: item.id }],
  };
  if (item.kind === "task") {
    body.custom_fields.push({ id: config.source_type_field_id, value: item.type });
  } else {
    const parent = remoteIndex.bySourceId.get(item.parentId);
    if (!parent) throw new Error(`${item.id}: remote parent is not available: ${item.parentId}`);
    body.parent = String(parent.id);
  }
  return body;
}

export async function applySyncPlan(plan, workItems, config, client) {
  const sourceById = new Map(workItems.map((item) => [item.id, item]));
  const remoteIndex = plan.remoteIndex;
  const byType = (type) => plan.operations.filter((operation) => operation.type === type);

  for (const operation of byType("CREATE_TASK")) {
    const item = sourceById.get(operation.sourceId);
    const remote = await client.createTask(config.list_id, createBody(item, config, remoteIndex));
    remote.custom_fields ??= [];
    remote.custom_fields.push({ id: config.source_id_field_id, value: item.id });
    remote.custom_fields.push({ id: config.source_type_field_id, value: item.type });
    remoteIndex.bySourceId.set(item.id, remote);
    remoteIndex.byRemoteId.set(String(remote.id), remote);
  }

  for (const operation of byType("UPDATE_TASK").filter((op) => sourceById.get(op.sourceId)?.kind === "task")) {
    await client.updateTask(remoteIndex.bySourceId.get(operation.sourceId).id, Object.fromEntries(Object.entries(operation.changes).map(([key, value]) => [key, value.after])));
  }

  for (const operation of byType("CREATE_SUBTASK")) {
    const item = sourceById.get(operation.sourceId);
    const remote = await client.createTask(config.list_id, createBody(item, config, remoteIndex));
    remote.custom_fields ??= [];
    remote.custom_fields.push({ id: config.source_id_field_id, value: item.id });
    remoteIndex.bySourceId.set(item.id, remote);
    remoteIndex.byRemoteId.set(String(remote.id), remote);
  }

  for (const operation of byType("UPDATE_TASK").filter((op) => sourceById.get(op.sourceId)?.kind === "step")) {
    await client.updateTask(remoteIndex.bySourceId.get(operation.sourceId).id, Object.fromEntries(Object.entries(operation.changes).map(([key, value]) => [key, value.after])));
  }

  for (const operation of byType("SET_TYPE")) {
    const remote = remoteIndex.bySourceId.get(operation.sourceId);
    await client.setCustomField(remote.id, config.source_type_field_id, operation.after);
  }

  for (const operation of [...byType("ADD_DEPENDENCY"), ...byType("REMOVE_DEPENDENCY")]) {
    const remote = remoteIndex.bySourceId.get(operation.sourceId);
    const dependency = remoteIndex.bySourceId.get(operation.dependencySourceId);
    if (!remote || !dependency) throw new Error(`${operation.sourceId}: dependency remote identity is unavailable: ${operation.dependencySourceId}`);
    if (operation.type === "ADD_DEPENDENCY") await client.addDependency(remote.id, dependency.id);
    else await client.removeDependency(remote.id, dependency.id);
  }
}

export function formatPlan(plan) {
  const lines = [];
  for (const operation of plan.operations) {
    lines.push(`${operation.sourceId}  ${operation.type}`);
    if (operation.dependencySourceId) lines.push(`  depends_on: ${operation.dependencySourceId}`);
    for (const [name, value] of Object.entries(operation.changes ?? {})) {
      const before = name === "markdown_content" ? "<markdown>" : JSON.stringify(value.before);
      const after = name === "markdown_content" ? "<markdown>" : JSON.stringify(value.after);
      lines.push(`  ${name}: ${before} -> ${after}`);
    }
    if (operation.type === "SET_TYPE") lines.push(`  type: ${JSON.stringify(operation.before)} -> ${JSON.stringify(operation.after)}`);
  }
  return `${lines.join("\n")}\n`;
}
