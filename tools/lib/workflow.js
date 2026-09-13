#!/usr/bin/env node
'use strict';

/**
 * Workflow CLI application core.
 *
 * The provider interfaces in this file intentionally contain no GitHub or
 * provider-specific concepts.  A deployment can replace any adapter by
 * setting WORKFLOW_ADAPTER_MODULE to a CommonJS module that exports either an
 * adapter object or createAdapters(context).
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const os = require('node:os');
const { execFileSync } = require('node:child_process');

const WORKFLOW_VERSION = 1;

class WorkflowError extends Error {
  constructor(code, message, blockedBy = [], next = [], data = {}) {
    super(message);
    this.name = 'WorkflowError';
    this.code = code;
    this.blockedBy = blockedBy;
    this.next = next;
    this.data = data;
  }
}

const nextAction = (actor, action, command = undefined) => {
  const value = { actor, action };
  if (command !== undefined) value.command = command;
  return value;
};

const blocked = (code, message, target) => ({ code, message, ...(target ? { target } : {}) });

function emptyState() {
  return {
    version: WORKFLOW_VERSION,
    counters: { subtask: 100, review: 0, change: 0, thread: 0, pr: 0 },
    tasks: [],
    subtasks: [],
    validations: [],
    prs: [],
    events: []
  };
}

function normalizeState(value) {
  const state = value && typeof value === 'object' ? value : emptyState();
  const base = emptyState();
  for (const key of Object.keys(base)) {
    if (state[key] === undefined) state[key] = base[key];
  }
  state.version = WORKFLOW_VERSION;
  state.counters = { ...base.counters, ...(state.counters || {}) };
  for (const collection of ['tasks', 'subtasks', 'validations', 'prs', 'events']) {
    if (!Array.isArray(state[collection])) state[collection] = [];
  }
  return state;
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

class WorkflowStore {
  constructor(filePath) {
    this.filePath = path.resolve(filePath);
  }

  load() {
    try {
      return normalizeState(JSON.parse(fs.readFileSync(this.filePath, 'utf8')));
    } catch (error) {
      if (error.code === 'ENOENT') return emptyState();
      if (error instanceof SyntaxError) {
        throw new WorkflowError('STORE_CORRUPT', `Workflow Store JSON을 읽을 수 없습니다: ${this.filePath}`);
      }
      throw error;
    }
  }

  save(state) {
    const normalized = normalizeState(clone(state));
    const directory = path.dirname(this.filePath);
    fs.mkdirSync(directory, { recursive: true });
    const temporary = path.join(directory, `.${path.basename(this.filePath)}.${process.pid}.${Date.now()}.tmp`);
    fs.writeFileSync(temporary, `${JSON.stringify(normalized, null, 2)}\n`, { mode: 0o600 });
    fs.renameSync(temporary, this.filePath);
    return normalized;
  }

  read(reader) {
    return reader(this.load());
  }

  /** Mutations are committed only after the callback returns successfully. */
  transaction(mutator) {
    const current = this.load();
    const candidate = clone(current);
    const result = mutator(candidate);
    this.save(candidate);
    return result;
  }
}

function runGit(args, cwd, options = {}) {
  try {
    return execFileSync('git', args, {
      cwd,
      encoding: 'utf8',
      stdio: options.stdio || ['ignore', 'pipe', 'pipe']
    }).trim();
  } catch (error) {
    const detail = String(error.stderr || error.message || '').trim();
    throw new WorkflowError('GIT_COMMAND_FAILED', detail || `git ${args.join(' ')} 실행에 실패했습니다.`);
  }
}

function gitRoot(cwd) {
  try {
    return runGit(['rev-parse', '--show-toplevel'], cwd);
  } catch (_) {
    return null;
  }
}

function repositoryRoot(cwd) {
  const configured = process.env.WORKFLOW_REPO_ROOT;
  if (configured) return path.resolve(configured);
  try {
    const commonDir = runGit(['rev-parse', '--git-common-dir'], cwd);
    const absolute = path.isAbsolute(commonDir) ? commonDir : path.resolve(cwd, commonDir);
    return path.dirname(absolute);
  } catch (_) {
    return gitRoot(cwd) || path.resolve(cwd);
  }
}

function defaultStorePath(cwd) {
  return process.env.WORKFLOW_STATE_FILE || path.join(repositoryRoot(cwd), '.workflow', 'state.json');
}

function canonical(value) {
  try {
    return fs.realpathSync(value);
  } catch (_) {
    return path.resolve(value);
  }
}

function isWithin(parent, child) {
  const relative = path.relative(canonical(parent), canonical(child));
  return relative === '' || (relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}

function fingerprintDirectory(directory, { excludePaths = [] } = {}) {
  const root = canonical(directory);
  const exclusions = new Set(excludePaths.map((value) => path.normalize(value).split(path.sep).join('/')));
  const entries = [];
  const walk = (current, relative) => {
    let names;
    try {
      names = fs.readdirSync(current, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name));
    } catch (error) {
      throw new WorkflowError('FINGERPRINT_FAILED', `파일 목록을 읽을 수 없습니다: ${current}`, [], [], { cause: error.message });
    }
    for (const entry of names) {
      if (entry.name === '.git' || entry.name === '.workflow') continue;
      const absolute = path.join(current, entry.name);
      const rel = path.join(relative, entry.name).split(path.sep).join('/');
      if (exclusions.has(rel)) continue;
      if (entry.isDirectory()) {
        walk(absolute, rel);
      } else if (entry.isSymbolicLink()) {
        entries.push(`${rel}\0symlink\0${fs.readlinkSync(absolute)}`);
      } else if (entry.isFile()) {
        const content = fs.readFileSync(absolute);
        entries.push(`${rel}\0file\0${content.toString('base64')}`);
      }
    }
  };
  walk(root, '');
  return crypto.createHash('sha256').update(entries.join('\n')).digest('hex');
}

function fileContent(cwd, supplied) {
  if (!supplied) return null;
  const candidate = path.resolve(cwd, supplied);
  if (!isWithin(cwd, candidate)) {
    throw new WorkflowError('PATH_OUTSIDE_WORKTREE', '지정한 파일은 현재 Worktree 안에 있어야 합니다.');
  }
  try {
    return fs.readFileSync(candidate, 'utf8');
  } catch (error) {
    throw new WorkflowError('FILE_NOT_FOUND', `파일을 읽을 수 없습니다: ${supplied}`, [], [], { cause: error.message });
  }
}

function id(state, type, prefix) {
  state.counters[type] = Number(state.counters[type] || 0) + 1;
  return `${prefix}-${state.counters[type]}`;
}

class LocalTaskAdapter {
  getTask(taskId) {
    return { id: taskId, state: 'OPEN' };
  }

  linkSubtask() {
    return undefined;
  }
}

class LocalIdentityAdapter {
  actor() { return process.env.WORKFLOW_ACTOR || 'agent'; }
  isHuman() { return this.actor() === 'human'; }
}

class LocalCIAdapter {
  start({ subtask }) {
    return { state: 'QUEUED', id: `ci-${subtask}` };
  }
}

class LocalReviewAdapter {
  createDraft({ title, body, base, subtask }) {
    return { id: `pr-${subtask}`, number: null, title, body, base, state: 'DRAFT' };
  }
  update() { return undefined; }
}

class LocalGitAdapter {
  constructor(repoRoot) { this.repoRoot = repoRoot; }

  fetchMain() {
    try { runGit(['fetch', 'origin', 'main'], this.repoRoot); } catch (_) { /* offline repositories are valid test repositories */ }
    for (const ref of ['refs/remotes/origin/main', 'refs/heads/main', 'HEAD']) {
      try { return runGit(['rev-parse', ref], this.repoRoot); } catch (_) { /* try next */ }
    }
    throw new WorkflowError('BASE_REVISION_UNAVAILABLE', '기준 원격 main revision을 확인할 수 없습니다.');
  }

  createWorktree({ branch, workspace, base }) {
    fs.mkdirSync(path.dirname(workspace), { recursive: true });
    runGit(['worktree', 'add', '-b', branch, workspace, base], this.repoRoot);
    return { path: workspace, branch, base };
  }

  removeWorktree({ workspace, branch }) {
    try { runGit(['worktree', 'remove', '--force', workspace], this.repoRoot); } catch (_) { /* best effort rollback */ }
    try { runGit(['branch', '-D', branch], this.repoRoot); } catch (_) { /* best effort rollback */ }
  }

  currentRevision(cwd) {
    try { return runGit(['rev-parse', 'HEAD'], cwd); } catch (_) { return null; }
  }

  fingerprint(cwd, options) { return fingerprintDirectory(cwd, options); }

  identifyWorktree(cwd) {
    const top = gitRoot(cwd);
    if (!top) return null;
    let branch = null;
    try { branch = runGit(['symbolic-ref', '--short', 'HEAD'], cwd); } catch (_) { /* detached */ }
    return { path: canonical(top), branch };
  }

  publishBranch({ branch }) {
    runGit(['push', '--set-upstream', 'origin', branch], this.repoRoot);
    return { branch, published: true };
  }

  diffIdentity({ cwd, base }) {
    let diff = '';
    try { diff = runGit(['diff', '--binary', `${base}...HEAD`], cwd); } catch (_) { /* initial repository */ }
    return crypto.createHash('sha256').update(`${base}\0${diff}\0${fingerprintDirectory(cwd)}`).digest('hex');
  }
}

function loadAdapters(context) {
  const defaults = {
    task: new LocalTaskAdapter(),
    git: new LocalGitAdapter(context.repoRoot),
    review: new LocalReviewAdapter(),
    ci: new LocalCIAdapter(),
    identity: new LocalIdentityAdapter()
  };
  const modulePath = process.env.WORKFLOW_ADAPTER_MODULE;
  if (!modulePath) return defaults;
  let loaded;
  try {
    loaded = require(path.resolve(modulePath));
  } catch (error) {
    throw new WorkflowError('ADAPTER_LOAD_FAILED', `Adapter 모듈을 읽을 수 없습니다: ${modulePath}`, [], [], { cause: error.message });
  }
  const injected = typeof loaded.createAdapters === 'function' ? loaded.createAdapters(context) : loaded;
  const mergeAdapter = (base, override) => {
    if (!override) return base;
    return Object.assign(Object.create(Object.getPrototypeOf(base)), base, override);
  };
  return {
    ...defaults,
    ...(injected || {}),
    task: mergeAdapter(defaults.task, injected && injected.task),
    git: mergeAdapter(defaults.git, injected && injected.git),
    review: mergeAdapter(defaults.review, injected && injected.review),
    ci: mergeAdapter(defaults.ci, injected && injected.ci),
    identity: mergeAdapter(defaults.identity, injected && injected.identity)
  };
}

function managedSubtask(state, cwd, gitAdapter) {
  const found = state.subtasks.find((item) => item.workspace && isWithin(item.workspace.path, cwd));
  if (!found) {
    throw new WorkflowError(
      'WORKTREE_REQUIRED',
      '이 작업은 관리되는 SubTask Worktree에서 실행해야 합니다.',
      [blocked('WORKTREE_REQUIRED', '관리되는 SubTask Worktree가 필요합니다.')],
      [nextAction('agent', 'locate_workspace', './tools/workflow status --subtask <id> --json')]
    );
  }
  if (gitAdapter && typeof gitAdapter.identifyWorktree === 'function') {
    const identity = gitAdapter.identifyWorktree(cwd);
    if (identity && (canonical(identity.path) !== canonical(found.workspace.path) || (identity.branch && identity.branch !== found.branch))) {
      throw new WorkflowError(
        'WORKTREE_REQUIRED',
        '현재 경로가 SubTask의 관리 Worktree 또는 Branch와 일치하지 않습니다.',
        [blocked('WORKTREE_REQUIRED', '관리되는 SubTask Worktree와 Branch가 필요합니다.', found.id)],
        [nextAction('agent', 'locate_workspace', `./tools/workflow status --subtask ${found.id} --json`)]
      );
    }
  }
  return found;
}

function requireOption(args, name) {
  const key = name.startsWith('--') ? name.slice(2) : name;
  const value = args.options[key];
  if (!value || (Array.isArray(value) && value.length === 0)) {
    throw new WorkflowError('INVALID_ARGUMENT', `${name} 인자가 필요합니다.`);
  }
  return Array.isArray(value) ? value[value.length - 1] : value;
}

function parseArgs(argv) {
  const positional = [];
  const options = {};
  let json = false;
  for (let i = 0; i < argv.length; i += 1) {
    const value = argv[i];
    if (value === '--json') { json = true; continue; }
    if (value.startsWith('--')) {
      const key = value.slice(2);
      const next = argv[i + 1];
      if (!next || next.startsWith('--')) {
        options[key] = true;
      } else {
        options[key] = options[key] === undefined ? next : [].concat(options[key], next);
        i += 1;
      }
    } else positional.push(value);
  }
  return { positional, options, json };
}

function success(data) { return { type: 'success', data }; }
function failure(error) {
  const workflowError = error instanceof WorkflowError
    ? error
    : new WorkflowError('INTERNAL_ERROR', error.message || String(error));
  return {
    type: 'failure',
    data: {
      code: workflowError.code,
      message: workflowError.message,
      blocked_by: workflowError.blockedBy || [],
      next: workflowError.next || [],
      ...workflowError.data
    }
  };
}

class WorkflowApplication {
  constructor({ cwd = process.cwd(), store, adapters, repoRoot } = {}) {
    this.cwd = path.resolve(cwd);
    this.repoRoot = path.resolve(repoRoot || repositoryRoot(this.cwd));
    this.store = store || new WorkflowStore(defaultStorePath(this.cwd));
    this.adapters = adapters || loadAdapters({ cwd: this.cwd, repoRoot: this.repoRoot, store: this.store });
    // Later command families (stack, sync, status and gate) can be attached
    // without changing the parser or result contract.
    this.commands = new Map([
      ['start', this.start.bind(this)],
      ['check', this.check.bind(this)],
      ['review', (args) => this.review(args.positional[1] || 'show', args)],
      ['stack', this.stack ? this.stack.bind(this) : null],
      ['sync', this.sync ? this.sync.bind(this) : null],
      ['status', this.status ? this.status.bind(this) : null],
      ['gate', this.gate ? this.gate.bind(this) : null]
    ]);
  }

  /**
   * Extension point for later command families. A plugin can register a
   * handler and keep the same parsed argument/result contract.
   */
  registerCommand(name, handler) {
    if (!name || typeof handler !== 'function') throw new TypeError('command handler가 필요합니다.');
    this.commands.set(name, handler.bind(this));
    return this;
  }

  execute(argv) {
    try {
      const args = parseArgs(argv);
      if (args.positional.length === 0 || args.positional[0] === 'help') {
        return success({ commands: ['start', 'check', 'review', 'stack', 'sync', 'status', 'gate'] });
      }
      const [command, subcommand] = args.positional;
      const handler = this.commands.get(command);
      if (handler) return handler(args);
      throw new WorkflowError('UNKNOWN_COMMAND', `알 수 없는 명령입니다: ${command}`);
    } catch (error) {
      return failure(error);
    }
  }

  start(args) {
    const taskId = requireOption(args, '--task');
    const requestId = requireOption(args, '--request-id');
    const title = requireOption(args, '--title');
    const requires = args.options.requires || null;
    const task = this.adapters.task.getTask(taskId);
    if (task && task.state === 'CLOSED') throw new WorkflowError('TASK_CLOSED', '종료된 외부 Task에는 SubTask를 시작할 수 없습니다.');
    const current = this.store.load();
    const previous = current.subtasks.find((item) => item.task === taskId && item.requestId === requestId);
    if (previous) {
      if (previous.title !== title || previous.requires !== requires) {
        throw new WorkflowError('IDEMPOTENCY_CONFLICT', '같은 시작 요청에 다른 제목 또는 dependency가 전달되었습니다.');
      }
      return success(this.startData(previous));
    }
    let parent = null;
    if (requires) {
      parent = current.subtasks.find((item) => item.id === requires);
      if (!parent) throw new WorkflowError('DEPENDENCY_NOT_FOUND', `선행 SubTask를 찾을 수 없습니다: ${requires}`);
      if (parent.state === 'MERGED') {
        throw new WorkflowError('DEPENDENCY_ALREADY_MERGED', '이미 통합된 SubTask는 새 작업의 미통합 기준이 될 수 없습니다.');
      }
    }
    const project = process.env.WORKFLOW_PROJECT_PREFIX || 'sk';
    const prospective = clone(current);
    const subtaskId = id(prospective, 'subtask', project);
    const base = parent ? parent.branch : this.adapters.git.fetchMain();
    const root = this.repoRoot;
    const worktreeRoot = path.resolve(process.env.WORKFLOW_WORKTREE_ROOT || path.join(root, '.worktrees'));
    const workspace = path.join(worktreeRoot, subtaskId);
    let created;
    try {
      created = this.adapters.git.createWorktree({ branch: subtaskId, workspace, base, parent });
    } catch (error) {
      throw error;
    }
    const item = {
      id: subtaskId,
      task: taskId,
      requestId,
      title,
      branch: subtaskId,
      workspace: { path: path.resolve((created && created.path) || workspace) },
      requires,
      base: parent ? parent.branch : 'main',
      baseRevision: base,
      state: 'DEVELOPMENT',
      createdAt: new Date().toISOString(),
      check: null,
      review: null,
      risk: null,
      exposure: null
    };
    try {
      this.store.transaction((state) => {
        // The identifier was reserved against the snapshot used to create
        // the worktree. Carry the reservation into the atomic commit.
        state.counters = prospective.counters;
        state.subtasks.push(item);
        if (!state.tasks.some((candidate) => candidate.id === taskId)) state.tasks.push({ id: taskId, state: task && task.state });
        this.adapters.task.linkSubtask({ task, subtask: item });
        state.events.push({ type: 'subtask_started', subtask: subtaskId, at: item.createdAt });
      });
    } catch (error) {
      if (this.adapters.git.removeWorktree) this.adapters.git.removeWorktree({ workspace, branch: subtaskId });
      throw error;
    }
    return success(this.startData(item));
  }

  startData(item) {
    return {
      task: item.task,
      subtask: item.id,
      branch: item.branch,
      workspace: item.workspace,
      state: item.state,
      ...(item.requires ? { requires: item.requires } : {}),
      next: [nextAction('agent', 'implement_change', null)]
    };
  }

  check() {
    const state = this.store.load();
    const item = managedSubtask(state, this.cwd, this.adapters.git);
    const workspace = item.workspace.path;
    const excludePaths = item.review && item.review.bodySource ? [item.review.bodySource] : [];
    const fingerprint = this.adapters.git.fingerprint(workspace, { excludePaths });
    const codeRevision = this.adapters.git.currentRevision(workspace);
    const validations = this.adapters.validation && this.adapters.validation.run
      ? this.adapters.validation.run({ cwd: workspace, subtask: item })
      : (process.env.WORKFLOW_VALIDATION_COMMAND ? this.runConfiguredValidation(workspace) : [{ name: 'repository', state: 'PASSED' }]);
    const normalized = (Array.isArray(validations) ? validations : [validations]).map((entry) => ({
      name: entry.name || 'repository', state: entry.state || (entry.ok === false ? 'FAILED' : 'PASSED'), ...entry
    }));
    const failed = normalized.some((entry) => entry.state !== 'PASSED' && entry.state !== 'SUCCESS');
    const parent = item.requires ? state.subtasks.find((candidate) => candidate.id === item.requires) : null;
    const parentBlocked = parent && parent.state !== 'MERGED' && !parent.workspace;
    this.store.transaction((nextState) => {
      const target = nextState.subtasks.find((candidate) => candidate.id === item.id);
      target.check = { fingerprint, codeRevision, validations: normalized, passed: !failed && !parentBlocked, checkedAt: new Date().toISOString() };
      nextState.validations = nextState.validations.filter((entry) => !(entry.subtask === item.id && entry.fingerprint === fingerprint));
      nextState.validations.push({ subtask: item.id, fingerprint, codeRevision, validations: normalized, passed: !failed && !parentBlocked, at: target.check.checkedAt });
    });
    const pass = !failed && !parentBlocked;
    return success({
      subtask: item.id,
      code_revision: codeRevision,
      fingerprint,
      validations: normalized,
      publishable: pass,
      next: pass ? [nextAction('agent', 'open_review', './tools/workflow review open --body-file pr.md --risk normal --exposure unchanged --json')] : [],
      ...(failed ? { blocked_by: [blocked('VALIDATION_FAILED', '필수 Repository validation이 실패했습니다.', item.id)] } : {})
    });
  }

  runConfiguredValidation(cwd) {
    const command = process.env.WORKFLOW_VALIDATION_COMMAND;
    const tokens = command.match(/(?:[^\s"']+|"[^"]*"|'[^']*')+/g) || [];
    if (tokens.length === 0) return [{ name: 'repository', state: 'PASSED' }];
    try {
      execFileSync(tokens[0], tokens.slice(1).map((token) => token.replace(/^['"]|['"]$/g, '')), { cwd, stdio: 'ignore' });
      return [{ name: 'repository', state: 'PASSED' }];
    } catch (error) {
      return [{ name: 'repository', state: 'FAILED', exitCode: error.status || 1 }];
    }
  }

  review(subcommand, args) {
    if (subcommand === 'open') return this.reviewOpen(args);
    if (subcommand === 'update') return this.reviewUpdate(args);
    if (subcommand === 'show') return this.reviewShow(args);
    throw new WorkflowError('UNKNOWN_COMMAND', `알 수 없는 review 명령입니다: ${subcommand}`);
  }

  reviewContext() {
    const state = this.store.load();
    const item = managedSubtask(state, this.cwd, this.adapters.git);
    if (!item.review) throw new WorkflowError('REVIEW_NOT_FOUND', '현재 SubTask에 열린 Review가 없습니다.');
    return { state, item };
  }

  reviewOpen(args) {
    const bodyFile = requireOption(args, '--body-file');
    const risk = requireOption(args, '--risk');
    const exposure = requireOption(args, '--exposure');
    if (!['normal', 'high'].includes(risk)) throw new WorkflowError('INVALID_ARGUMENT', '--risk는 normal 또는 high여야 합니다.');
    if (!['unchanged', 'feature-flag'].includes(exposure)) throw new WorkflowError('INVALID_ARGUMENT', '--exposure는 unchanged 또는 feature-flag여야 합니다.');
    const featureFlag = args.options['feature-flag'] || null;
    if (exposure === 'feature-flag' && !featureFlag) throw new WorkflowError('FEATURE_FLAG_REQUIRED', 'feature-flag exposure에는 Feature Flag ID가 필요합니다.');
    if (exposure === 'unchanged' && featureFlag) throw new WorkflowError('INVALID_ARGUMENT', 'unchanged exposure에는 Feature Flag를 지정할 수 없습니다.');
    const body = fileContent(this.cwd, bodyFile);
    const state = this.store.load();
    const item = managedSubtask(state, this.cwd, this.adapters.git);
    if (item.review) throw new WorkflowError('REVIEW_ALREADY_OPEN', '현재 SubTask에는 이미 Review가 열려 있습니다.');
    const checkedFingerprint = this.adapters.git.fingerprint(item.workspace.path);
    if (!item.check || item.check.fingerprint !== checkedFingerprint || !item.check.passed) {
      throw new WorkflowError('CHECK_REQUIRED', '현재 fingerprint에 대한 성공한 check 결과가 필요합니다.', [blocked('CHECK_REQUIRED', '현재 코드 상태를 먼저 검증해야 합니다.', item.id)], [nextAction('agent', 'check', './tools/workflow check --json')]);
    }
    const bodySource = path.relative(item.workspace.path, path.resolve(this.cwd, bodyFile)).split(path.sep).join('/');
    const fingerprint = this.adapters.git.fingerprint(item.workspace.path, { excludePaths: [bodySource] });
    const base = item.requires ? item.requires : 'main';
    const title = `[${item.id}] ${item.title}`;
    const published = this.adapters.git.publishBranch({ branch: item.branch, cwd: item.workspace.path, subtask: item });
    const pr = this.adapters.review.createDraft({ title, body, base, subtask: item, risk, exposure, featureFlag, published });
    const reserved = clone(state);
    const reviewRevision = id(reserved, 'review', 'rv');
    const changeRevision = id(reserved, 'change', 'cr');
    const diffIdentity = this.adapters.git.diffIdentity
      ? this.adapters.git.diffIdentity({ cwd: item.workspace.path, base: item.baseRevision || base })
      : crypto.createHash('sha256').update(`${item.baseRevision || base}\0${fingerprint}`).digest('hex');
    const ci = this.adapters.ci.start({ subtask: item, pr, changeRevision, fingerprint });
    const ai = this.adapters.ai && this.adapters.ai.start ? this.adapters.ai.start({ subtask: item, pr, changeRevision }) : { state: 'QUEUED' };
    const review = {
      id: pr.id || `pr-${item.id}`,
      number: pr.number || null,
      title,
      body,
      bodySource,
      base,
      risk,
      exposure,
      featureFlag,
      reviewRevision,
      changeRevision,
      diffIdentity,
      fingerprint,
      codeRevision: item.check.codeRevision,
      ci: ci || { state: 'QUEUED' },
      aiReview: ai || { state: 'QUEUED' },
      approval: null,
      threads: [],
      openedAt: new Date().toISOString()
    };
    this.store.transaction((nextState) => {
      nextState.counters = reserved.counters;
      const target = nextState.subtasks.find((candidate) => candidate.id === item.id);
      target.review = review;
      target.risk = risk;
      target.exposure = { mode: exposure, featureFlag };
      target.state = 'DRAFT';
      nextState.prs.push({ subtask: item.id, ...review });
      nextState.events.push({ type: 'review_opened', subtask: item.id, reviewRevision, at: review.openedAt });
    });
    return success(this.reviewData(item, review));
  }

  reviewUpdate(args) {
    const expected = requireOption(args, '--revision');
    const bodyFile = args.options['body-file'];
    const { state, item } = this.reviewContext();
    const review = item.review;
    if (expected !== review.reviewRevision) {
      throw new WorkflowError('STALE_REVISION', '전달한 review_revision이 현재 값과 다릅니다.', [blocked('STALE_REVISION', '최신 Review revision을 다시 조회해야 합니다.', item.id)], [nextAction('agent', 'show_review', './tools/workflow review show --json')]);
    }
    const nextBody = bodyFile ? fileContent(this.cwd, bodyFile) : review.body;
    const fingerprint = this.adapters.git.fingerprint(item.workspace.path, {
      excludePaths: review.bodySource ? [review.bodySource] : []
    });
    const changed = fingerprint !== review.fingerprint;
    let nextReview;
    if (changed) {
      const targetCheck = item.check;
      if (!targetCheck || targetCheck.fingerprint !== fingerprint || !targetCheck.passed) {
        throw new WorkflowError('CHECK_REQUIRED', '코드가 변경되어 현재 fingerprint의 성공한 check 결과가 필요합니다.');
      }
      if (this.adapters.git.publishBranch) this.adapters.git.publishBranch({ branch: item.branch, cwd: item.workspace.path, subtask: item });
      if (this.adapters.review.update) this.adapters.review.update({ subtask: item, review, body: nextBody, codeChanged: true });
      const reserved = clone(state);
      const newReviewRevision = id(reserved, 'review', 'rv');
      const newChangeRevision = id(reserved, 'change', 'cr');
      const diffIdentity = this.adapters.git.diffIdentity
        ? this.adapters.git.diffIdentity({ cwd: item.workspace.path, base: item.baseRevision || review.base })
        : crypto.createHash('sha256').update(`${item.baseRevision || review.base}\0${fingerprint}`).digest('hex');
      const ci = this.adapters.ci.start({ subtask: item, pr: review, changeRevision: newChangeRevision, fingerprint });
      const ai = this.adapters.ai && this.adapters.ai.start ? this.adapters.ai.start({ subtask: item, pr: review, changeRevision: newChangeRevision }) : review.aiReview;
      nextReview = { ...review, body: nextBody, reviewRevision: newReviewRevision, changeRevision: newChangeRevision, diffIdentity, fingerprint, codeRevision: targetCheck.codeRevision, ci: ci || { state: 'QUEUED' }, aiReview: ai || { state: 'QUEUED' }, approval: null, updatedAt: new Date().toISOString(), _reservedCounters: reserved.counters };
    } else if (nextBody !== review.body) {
      if (this.adapters.review.update) this.adapters.review.update({ subtask: item, review, body: nextBody, codeChanged: false });
      const reserved = clone(state);
      nextReview = { ...review, body: nextBody, reviewRevision: id(reserved, 'review', 'rv'), updatedAt: new Date().toISOString(), _reservedCounters: reserved.counters };
    } else {
      return success(this.reviewData(item, review));
    }
    this.store.transaction((nextState) => {
      if (nextReview._reservedCounters) {
        nextState.counters = nextReview._reservedCounters;
        delete nextReview._reservedCounters;
      }
      const target = nextState.subtasks.find((candidate) => candidate.id === item.id);
      target.review = nextReview;
      target.state = 'DRAFT';
      const pr = nextState.prs.find((candidate) => candidate.subtask === item.id);
      if (pr) Object.assign(pr, nextReview);
      nextState.events.push({ type: 'review_updated', subtask: item.id, reviewRevision: nextReview.reviewRevision, changed, at: nextReview.updatedAt });
    });
    return success(this.reviewData(item, nextReview));
  }

  reviewShow() {
    const { item } = this.reviewContext();
    return success(this.reviewData(item, item.review));
  }

  reviewData(item, review) {
    return {
      subtask: item.id,
      state: item.state,
      pr: { id: review.id, number: review.number, title: review.title, body: review.body, base: review.base, state: 'DRAFT' },
      review_revision: review.reviewRevision,
      change_revision: review.changeRevision,
      diff_identity: review.diffIdentity,
      fingerprint: review.fingerprint,
      risk: review.risk,
      exposure: review.exposure,
      ci: review.ci,
      ai_review: review.aiReview,
      threads: review.threads || [],
      next: [nextAction('human', 'review_draft')]
    };
  }
}

function resultOutput(result, json) {
  if (json) return `${JSON.stringify(result)}\n`;
  return `${JSON.stringify(result, null, 2)}\n`;
}

function main(argv = process.argv.slice(2), io = process) {
  const parsed = parseArgs(argv);
  let result;
  try {
    const app = new WorkflowApplication({ cwd: process.cwd() });
    result = app.execute(argv);
  } catch (error) {
    result = failure(error);
  }
  io.stdout.write(resultOutput(result, parsed.json));
  return result.type === 'success' ? 0 : 1;
}

module.exports = {
  WorkflowError,
  WorkflowStore,
  WorkflowApplication,
  LocalGitAdapter,
  LocalTaskAdapter,
  LocalReviewAdapter,
  LocalCIAdapter,
  LocalIdentityAdapter,
  createAdapters: loadAdapters,
  emptyState,
  fingerprintDirectory,
  parseArgs,
  success,
  failure,
  repositoryRoot,
  defaultStorePath,
  isWithin,
  main
};

if (require.main === module) process.exitCode = main();
