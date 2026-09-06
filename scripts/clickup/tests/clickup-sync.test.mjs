import assert from "node:assert/strict";
import { resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { renderStepMarkdown, renderTaskMarkdown } from "../markdown.mjs";
import {
  applySyncPlan,
  clickUpResultUrl,
  createSyncPlan,
  validateClickUpMetadata,
} from "../reconciliation.mjs";
import { loadWorkItems } from "../task-source.mjs";
import { projectTaskCard } from "../work-item.mjs";

const card = {
  id: "sk-5",
  title: "Task DAG 관리 스크립트 제공",
  type: "feature",
  status: "done",
  goal: "작업자는 Skill의 ECMAScript 스크립트를 Node.js로 직접 실행하여 JSON Task Card와 Task DAG를 일관되게 관리할 수 있다.",
  depends_on: [],
  requirements: ["Task Graph Skill과 의존성 없는 Node.js 관리 스크립트를 제공한다."],
  scope: { in: ["Task ID 발급과 JSON Task Card 등록"], out: ["사용자 인터페이스"] },
  verification: ["단일 Card와 전체 DAG 검증 테스트가 모두 통과한다."],
  issues: [],
  references: [".agents/skills/task-graph/references/task-card.example.json"],
  steps: [
    {
      id: "sk-5-1",
      title: "단일 Task Card 검증 구현",
      status: "done",
      objective: "Task Card 한 개가 명세의 구조와 상태 규칙을 충족하는지 판정할 수 있다.",
      work: ["공통 Task Card 모델과 lint 스크립트를 구현한다."],
      verification: ["유효한 Card는 성공한다."],
      issues: [],
      references: [],
    },
  ],
};

const config = {
  list_id: "list-1",
  source_id_field_id: "source-id",
  source_type_field_id: "source-type",
  status_map: {
    pending: "TO DO",
    running: "IN PROGRESS",
    verifying: "VERIFYING",
    blocked: "BLOCKED",
    done: "COMPLETE",
    cancelled: "CANCELLED",
  },
};

function remoteTask(
  id,
  sourceId,
  {
    name,
    status = "COMPLETE",
    parent = null,
    markdown = "",
    type = null,
    dependencies = [],
    url,
  } = {},
) {
  const custom_fields = [{ id: "source-id", value: sourceId }];
  if (type !== null) custom_fields.push({ id: "source-type", value: type });
  return {
    id,
    name,
    status: { status },
    parent,
    markdown_description: markdown,
    custom_fields,
    dependencies,
    ...(url ? { url } : {}),
  };
}

test("Task와 Step title 및 Markdown heading을 결정적으로 투영한다", () => {
  const [task, step] = projectTaskCard(card);
  assert.equal(task.displayTitle, "[sk-5] Task DAG 관리 스크립트 제공");
  assert.equal(step.displayTitle, "[sk-5-1] 단일 Task Card 검증 구현");
  assert.equal(step.parentId, "sk-5");
  assert.deepEqual(step.dependencies, []);

  const taskHeadings = task.markdown.split("\n").filter((line) => line.startsWith("#"));
  assert.deepEqual(taskHeadings, [
    "# 목표",
    "# 요구사항",
    "# 범위",
    "## 입력",
    "## 출력",
    "# 검증",
    "# 이슈",
    "# 참고자료",
  ]);
  const stepHeadings = step.markdown.split("\n").filter((line) => line.startsWith("#"));
  assert.deepEqual(stepHeadings, ["# 목표", "# 작업", "# 검증", "# 이슈", "# 참고자료"]);
  for (const metadata of ["# ID", "# Title", "# Type", "# Status", "# Depends On", "# Steps"]) {
    assert.equal(task.markdown.includes(metadata), false);
  }
  assert.equal(task.markdown.endsWith("\n"), true);
  assert.equal(task.markdown.endsWith("\n\n"), false);
});

test("빈 배열도 heading은 유지하고 placeholder는 만들지 않는다", () => {
  const markdown = renderTaskMarkdown({
    goal: "g",
    requirements: [],
    scope: { in: [], out: [] },
    verification: [],
    issues: [],
    references: [],
  });
  assert.match(markdown, /# 이슈\n\n# 참고자료\n$/);
  assert.equal(markdown.includes("없음"), false);
  const step = renderStepMarkdown({
    objective: "o",
    work: [],
    verification: [],
    issues: [],
    references: [],
  });
  assert.match(step, /# 이슈\n\n# 참고자료\n$/);
});

test("신규 Task와 Step을 parent 순서로 생성하고 source identity를 포함한다", async () => {
  const workItems = projectTaskCard(card);
  const plan = createSyncPlan(workItems, [], config);
  assert.deepEqual(
    plan.operations.map((operation) => operation.type),
    ["CREATE_TASK", "CREATE_SUBTASK"],
  );

  const calls = [];
  let sequence = 0;
  const client = {
    async createTask(_listId, body) {
      calls.push(["createTask", body]);
      sequence += 1;
      return {
        id: `remote-${sequence}`,
        url: `https://app.clickup.com/t/remote-${sequence}`,
        name: body.name,
        parent: body.parent ?? null,
        custom_fields: [],
      };
    },
    updateTask: async () => assert.fail("예상하지 않은 Task 수정입니다."),
    setCustomField: async () => assert.fail("예상하지 않은 Custom Field 수정입니다."),
    addDependency: async () => assert.fail("예상하지 않은 dependency 추가입니다."),
    removeDependency: async () => assert.fail("예상하지 않은 dependency 제거입니다."),
  };

  await applySyncPlan(plan, workItems, config, client);
  assert.equal(calls.length, 2);
  assert.equal(calls[0][1].name, "[sk-5] Task DAG 관리 스크립트 제공");
  assert.deepEqual(calls[0][1].custom_fields, [
    { id: "source-id", value: "sk-5" },
    { id: "source-type", value: "feature" },
  ]);
  assert.equal(calls[1][1].parent, "remote-1");
  assert.deepEqual(calls[1][1].custom_fields, [{ id: "source-id", value: "sk-5-1" }]);
  assert.equal(clickUpResultUrl(plan, workItems), "https://app.clickup.com/t/remote-1");
});

test("ClickUp 응답 URL이 없으면 Task ID 기반 확인 URL을 사용한다", () => {
  const [task] = projectTaskCard({ ...card, steps: [] });
  const remote = remoteTask("r1", "sk-5", {
    name: task.displayTitle,
    markdown: task.markdown,
    type: "feature",
  });
  const plan = createSyncPlan([task], [remote], config);
  assert.equal(clickUpResultUrl(plan, [task]), "https://app.clickup.com/t/r1");
});

test("동일 remote state는 NO_CHANGE로 수렴한다", () => {
  const [task, step] = projectTaskCard(card);
  const remote = [
    remoteTask("r1", "sk-5", {
      name: task.displayTitle,
      markdown: task.markdown,
      type: "feature",
    }),
    remoteTask("r2", "sk-5-1", {
      name: step.displayTitle,
      markdown: step.markdown,
      parent: "r1",
    }),
  ];
  const plan = createSyncPlan([task, step], remote, config);
  assert.deepEqual(plan.operations, [
    { type: "NO_CHANGE", sourceId: "sk-5" },
    { type: "NO_CHANGE", sourceId: "sk-5-1" },
  ]);
});

test("ClickUp이 정규화한 Markdown 목록은 변경으로 처리하지 않는다", () => {
  const [task] = projectTaskCard(card);
  const clickUpMarkdown = task.markdown.replace(/^- /gm, "*   ").trimEnd();
  const plan = createSyncPlan(
    [task],
    [
      remoteTask("r1", "sk-5", {
        name: task.displayTitle,
        markdown: clickUpMarkdown,
        type: "feature",
      }),
    ],
    config,
  );

  assert.deepEqual(plan.operations, [{ type: "NO_CHANGE", sourceId: "sk-5" }]);
});

test("title 변경은 CREATE가 아니라 UPDATE가 된다", () => {
  const [task] = projectTaskCard(card);
  const remote = [
    remoteTask("r1", "sk-5", {
      name: "[sk-5] old",
      markdown: task.markdown,
      type: "feature",
    }),
  ];
  const plan = createSyncPlan([task], remote, config);
  assert.equal(plan.operations.some((operation) => operation.type === "CREATE_TASK"), false);
  const update = plan.operations.find((operation) => operation.type === "UPDATE_TASK");
  assert.deepEqual(update.changes.name, { before: "[sk-5] old", after: task.displayTitle });
});

test("managed dependency만 reconcile하고 unmanaged dependency는 보존한다", () => {
  const dependencyCard = { ...card, id: "sk-4", title: "선행 작업", steps: [], depends_on: [] };
  const [dependency] = projectTaskCard(dependencyCard);
  const [task] = projectTaskCard({ ...card, depends_on: ["sk-4"], steps: [] });
  const remoteDependency = remoteTask("r4", "sk-4", {
    name: dependency.displayTitle,
    markdown: dependency.markdown,
    type: "feature",
  });
  const remoteCurrent = remoteTask("r5", "sk-5", {
    name: task.displayTitle,
    markdown: task.markdown,
    type: "feature",
    dependencies: [{ task_id: "r5", depends_on: "external" }],
  });
  const plan = createSyncPlan(
    [dependency, task],
    [remoteDependency, remoteCurrent, { id: "external", custom_fields: [] }],
    config,
  );
  assert.equal(
    plan.operations.filter((operation) => operation.type === "ADD_DEPENDENCY").length,
    1,
  );
  assert.equal(
    plan.operations.filter((operation) => operation.type === "REMOVE_DEPENDENCY").length,
    0,
  );
});

test("duplicate source identity와 잘못된 hierarchy는 preflight에서 실패한다", () => {
  const [task, step] = projectTaskCard(card);
  assert.throws(
    () =>
      createSyncPlan(
        [task],
        [
          remoteTask("r1", "sk-5", { name: task.displayTitle }),
          remoteTask("r2", "sk-5", { name: task.displayTitle }),
        ],
        config,
      ),
    /ClickUp source ID가 중복되었습니다/,
  );

  assert.throws(
    () =>
      createSyncPlan(
        [task, step],
        [remoteTask("r1", "sk-5", { name: task.displayTitle, parent: "unexpected" })],
        config,
      ),
    /source Task가 ClickUp Subtask로 존재합니다/,
  );
});

test("repository Task Card 전체를 1 Task + 5 Steps로 읽는다", async () => {
  const tasksDir = resolve(fileURLToPath(new URL("../../../tasks", import.meta.url)));
  const items = await loadWorkItems(tasksDir);
  assert.equal(items.length, 6);
  assert.equal(items.filter((item) => item.kind === "task").length, 1);
  assert.equal(items.filter((item) => item.kind === "step").length, 5);
});

test("Custom Field가 기본 Task type에 적용되지 않으면 preflight가 실패한다", () => {
  const list = { statuses: Object.values(config.status_map).map((status) => ({ status })) };
  const fields = {
    fields: [
      { id: "source-id", type: "short_text", applied_objects: [{ object_type: 19, object_id: 99 }] },
      { id: "source-type", type: "short_text" },
    ],
  };
  assert.throws(
    () => validateClickUpMetadata(config, list, fields),
    /기본 ClickUp Task type에 적용되지 않습니다/,
  );
});
