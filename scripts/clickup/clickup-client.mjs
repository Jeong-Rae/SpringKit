const BASE_URL = "https://api.clickup.com/api/v2";

export class ClickUpApiError extends Error {
  constructor(method, path, status, body) {
    super(`ClickUp ${method} ${path} 요청에 실패했습니다 (${status}): ${body}`);
    this.name = "ClickUpApiError";
  }
}

export class ClickUpClient {
  constructor(token, fetchImplementation = globalThis.fetch) {
    if (!token) throw new Error("CLICKUP_TOKEN 환경 변수가 필요합니다.");
    if (typeof fetchImplementation !== "function") throw new Error("fetch 구현이 필요합니다.");
    this.token = token;
    this.fetchImplementation = fetchImplementation;
  }

  async request(method, path, body) {
    const response = await this.fetchImplementation(`${BASE_URL}${path}`, {
      method,
      headers: {
        Authorization: this.token,
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const text = await response.text();
    if (!response.ok) throw new ClickUpApiError(method, path, response.status, text || "응답 본문 없음");
    if (!text) return null;
    return JSON.parse(text);
  }

  getList(listId) {
    return this.request("GET", `/list/${encodeURIComponent(listId)}`);
  }

  getCustomFields(listId) {
    return this.request("GET", `/list/${encodeURIComponent(listId)}/field?include_applied_objects=true`);
  }

  async listTasks(listId) {
    const tasks = [];
    for (let page = 0; ; page += 1) {
      const query = new URLSearchParams({
        page: String(page),
        subtasks: "true",
        include_closed: "true",
        include_markdown_description: "true",
      });
      const response = await this.request("GET", `/list/${encodeURIComponent(listId)}/task?${query}`);
      const pageTasks = Array.isArray(response?.tasks) ? response.tasks : [];
      tasks.push(...pageTasks);
      if (pageTasks.length < 100) break;
    }
    return tasks;
  }

  getTask(taskId) {
    return this.request("GET", `/task/${encodeURIComponent(taskId)}?include_markdown_description=true`);
  }

  createTask(listId, body) {
    return this.request("POST", `/list/${encodeURIComponent(listId)}/task`, body);
  }

  updateTask(taskId, body) {
    return this.request("PUT", `/task/${encodeURIComponent(taskId)}`, body);
  }

  setCustomField(taskId, fieldId, value) {
    return this.request("POST", `/task/${encodeURIComponent(taskId)}/field/${encodeURIComponent(fieldId)}`, { value });
  }

  addDependency(taskId, dependsOnTaskId) {
    return this.request("POST", `/task/${encodeURIComponent(taskId)}/dependency`, { depends_on: dependsOnTaskId });
  }

  removeDependency(taskId, dependsOnTaskId) {
    const query = new URLSearchParams({ depends_on: dependsOnTaskId });
    return this.request("DELETE", `/task/${encodeURIComponent(taskId)}/dependency?${query}`);
  }
}
