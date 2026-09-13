/**
 * Review threads, direct stacks, and worktree synchronization.
 *
 * This module deliberately has no GitHub, Git, or filesystem dependency.  A
 * WorkflowApplication can provide the small adapter methods documented below;
 * the in-memory store makes the same API useful for command and contract
 * tests.
 */

export class WorkflowError extends Error {
  constructor(code, message, data = {}) {
    super(message);
    this.name = 'WorkflowError';
    this.code = code;
    this.data = data;
  }
}

export const clone = (value) => JSON.parse(JSON.stringify(value));

const now = () => new Date().toISOString();

function fail(code, message, data = {}) {
  throw new WorkflowError(code, message, data);
}

function nextAction(actor, action, command) {
  return { actor, action, ...(command === undefined ? {} : { command }) };
}

function blocked(code, message, target) {
  return { code, message, ...(target === undefined ? {} : { target }) };
}

function listSubtasks(state) {
  if (Array.isArray(state.subtasks)) return state.subtasks;
  if (state.subtasks && typeof state.subtasks === 'object') return Object.values(state.subtasks);
  return [];
}

function findSubtask(state, id) {
  return listSubtasks(state).find((subtask) => subtask.id === id);
}

function replaceSubtask(state, value) {
  if (Array.isArray(state.subtasks)) {
    const index = state.subtasks.findIndex((subtask) => subtask.id === value.id);
    if (index < 0) state.subtasks.push(value);
    else state.subtasks[index] = value;
    return;
  }
  if (!state.subtasks || typeof state.subtasks !== 'object') state.subtasks = {};
  state.subtasks[value.id] = value;
}

function counter(state, name, prefix) {
  state.counters ||= {};
  state.counters[name] = Number(state.counters[name] || 0) + 1;
  return `${prefix}-${state.counters[name]}`;
}

function readActor(adapters, supplied) {
  if (supplied) return supplied;
  const identity = adapters?.identity;
  if (identity?.actor) return typeof identity.actor === 'function' ? identity.actor() : identity.actor;
  return 'agent';
}

function isHuman(adapters, actor) {
  if (adapters?.identity?.isHuman) return Boolean(adapters.identity.isHuman(actor));
  return actor === 'human' || actor?.type === 'human' || actor?.kind === 'human';
}

function actorName(actor) {
  if (typeof actor === 'string') return actor;
  return actor?.id || actor?.name || actor?.type || 'agent';
}

function actorType(adapters, actor) {
  return isHuman(adapters, actor) ? 'human' : 'agent';
}

function agentBody(adapters, actor, body) {
  const text = String(body ?? '');
  return actorType(adapters, actor) === 'agent' && !/^\[Agent\]\s/.test(text)
    ? `[Agent] ${text}`
    : text;
}

function humanResolutionRequired(thread) {
  return Boolean(thread?.requires_human_resolution || thread?.requiresHumanResolution || thread?.humanConfirmationRequired);
}

function currentReview(subtask) {
  if (!subtask) fail('SUBTASK_NOT_FOUND', 'SubTask를 찾을 수 없습니다.');
  if (!subtask.review) fail('REVIEW_NOT_FOUND', '현재 SubTask에 Review가 없습니다.', { target: subtask.id });
  return subtask.review;
}

function requireRevision(review, expected) {
  if (!expected) fail('INVALID_ARGUMENT', 'review_revision이 필요합니다.');
  if (expected !== review.reviewRevision) {
    fail('STALE_REVISION', '전달한 review_revision이 현재 값과 다릅니다.', {
      blocked_by: [blocked('STALE_REVISION', '최신 Review revision을 다시 조회해야 합니다.')],
      next: [nextAction('agent', 'show_review')],
      current_revision: review.reviewRevision,
    });
  }
}

function nextReviewRevision(state, review) {
  review.reviewRevision = counter(state, 'review', 'rv');
  review.updatedAt = now();
}

function unresolvedRequired(review) {
  return (review.threads || []).filter((thread) => thread.level === 'R' && thread.state !== 'RESOLVED');
}

function approvalApplicable(review) {
  const approval = review.approval;
  return Boolean(
    approval &&
    approval.changeRevision === review.changeRevision &&
    approval.diffIdentity === review.diffIdentity &&
    approval.state !== 'INVALIDATED',
  );
}

function reviewBlockers(review, subtaskId) {
  const required = unresolvedRequired(review);
  return required.map((thread) => blocked('OPEN_REQUIRED_THREAD', '해결되지 않은 [R] thread가 있습니다.', thread.id || subtaskId));
}

function reviewNext(review, subtaskId) {
  if (reviewBlockers(review, subtaskId).length) {
    return [nextAction('agent', 'resolve_required_threads')];
  }
  if (!review.ready) return [nextAction('human', 'mark_ready')];
  if (!approvalApplicable(review)) return [nextAction('human', 'approve_change')];
  return [nextAction('workflow', 'enter_merge_queue')];
}

function normalizeReview(review = {}) {
  return {
    reviewRevision: review.reviewRevision ?? review.review_revision ?? null,
    changeRevision: review.changeRevision ?? review.change_revision ?? null,
    diffIdentity: review.diffIdentity ?? review.diff_identity ?? null,
    diff: review.diff ?? null,
    pr: review.pr ?? {
      id: review.id ?? null,
      number: review.number ?? null,
      title: review.title ?? null,
      body: review.body ?? null,
      base: review.base ?? 'main',
      state: review.state ?? 'DRAFT',
    },
    risk: review.risk ?? null,
    exposure: review.exposure ?? null,
    ci: review.ci ?? null,
    aiReview: review.aiReview ?? review.ai_review ?? null,
    ready: review.ready ?? null,
    approval: review.approval ?? null,
    threads: Array.isArray(review.threads) ? review.threads : [],
    ...review,
  };
}

/** A tiny transactional store.  Production callers may inject any store with get/transaction. */
export class InMemoryWorkflowStore {
  constructor(initial = {}) {
    this.state = clone({ counters: {}, subtasks: [], ...initial });
  }

  load() { return clone(this.state); }
  getState() { return this.load(); }
  save(state) { this.state = clone(state); return this.load(); }
  getSubtask(id) { return clone(findSubtask(this.state, id)); }
  transaction(mutator) {
    const candidate = this.load();
    const result = mutator(candidate);
    this.state = candidate;
    return result;
  }
}

function storeLoad(store) {
  if (store.load) return store.load();
  if (store.getState) return store.getState();
  if (store.state) return clone(store.state);
  return { counters: {}, subtasks: [] };
}

function storeTransaction(store, mutator) {
  if (store.transaction) return store.transaction(mutator);
  const candidate = storeLoad(store);
  const result = mutator(candidate);
  if (store.save) store.save(candidate);
  else store.state = candidate;
  return result;
}

function invoke(adapter, names, payload) {
  if (!adapter) return undefined;
  for (const name of names) {
    if (typeof adapter[name] === 'function') return adapter[name](payload);
  }
  return undefined;
}

export function calculateDiffIdentity({ base, diff, fingerprint, git }) {
  const adapterValue = invoke(git, ['diffIdentity', 'getDiffIdentity'], { base, diff, fingerprint });
  if (adapterValue !== undefined) return typeof adapterValue === 'object' ? adapterValue.diffIdentity : adapterValue;
  return [base ?? '', diff ?? '', fingerprint ?? ''].join('\0');
}

/**
 * Review lifecycle service.
 * Adapter hooks: identity.actor/isHuman, review.comment/reply/resolve, and
 * optional review.show.  Hooks are called before the transaction is committed.
 */
export class ReviewService {
  constructor({ store, adapters = {} } = {}) {
    if (!store) fail('STORE_REQUIRED', 'ReviewService에는 store가 필요합니다.');
    this.store = store;
    this.adapters = adapters;
  }

  show({ subtaskId, diff = false, threads = 'open' } = {}) {
    const state = storeLoad(this.store);
    const subtask = findSubtask(state, subtaskId);
    const review = normalizeReview(currentReview(subtask));
    const listedThreads = threads === 'all'
      ? review.threads
      : review.threads.filter((thread) => thread.state !== 'RESOLVED');
    const result = {
      subtask: subtask.id,
      state: subtask.state ?? 'DRAFT',
      pr: review.pr,
      review_revision: review.reviewRevision,
      change_revision: review.changeRevision,
      diff_identity: review.diffIdentity,
      risk: review.risk,
      exposure: review.exposure,
      ci: review.ci,
      ai_review: review.aiReview,
      ready: review.ready,
      approval: review.approval,
      approval_applicable: approvalApplicable(review),
      threads: listedThreads,
      blocked_by: reviewBlockers(review, subtask.id),
      next: reviewNext(review, subtask.id),
    };
    if (diff) result.diff = review.diff;
    return result;
  }

  comment({ subtaskId, revision, reviewRevision, review_revision, level, body, path, line, actor } = {}) {
    const actualActor = readActor(this.adapters, actor);
    if (!['R', 'C', 'A'].includes(level)) fail('INVALID_LEVEL', 'level은 R, C 또는 A여야 합니다.');
    if (body === undefined || String(body).trim() === '') fail('BODY_REQUIRED', 'Review 의견 본문이 필요합니다.');
    if (line !== undefined && path === undefined) fail('INVALID_LOCATION', 'line은 path와 함께 사용해야 합니다.');
    if (line !== undefined && (!Number.isInteger(Number(line)) || Number(line) < 1)) fail('INVALID_LOCATION', 'line은 양의 정수여야 합니다.');
    let created;
    storeTransaction(this.store, (state) => {
      const subtask = findSubtask(state, subtaskId);
      const review = normalizeReview(currentReview(subtask));
      requireRevision(review, revision ?? reviewRevision ?? review_revision);
      created = {
        id: counter(state, 'thread', 'thread'),
        level,
        body: agentBody(this.adapters, actualActor, body),
        actor: actorName(actualActor),
        actor_type: actorType(this.adapters, actualActor),
        path: path ?? null,
        line: line === undefined ? null : Number(line),
        state: 'OPEN',
        requires_human_resolution: actorType(this.adapters, actualActor) === 'human',
        replies: [],
        createdAt: now(),
      };
      review.threads = [...review.threads, created];
      nextReviewRevision(state, review);
      subtask.review = review;
      replaceSubtask(state, subtask);
      invoke(this.adapters.review, ['comment', 'createComment'], { subtask, review, thread: created });
    });
    return { thread: created, review_revision: this.currentRevision(subtaskId) };
  }

  reply({ subtaskId, revision, reviewRevision, review_revision, thread: threadId, body, actor } = {}) {
    const actualActor = readActor(this.adapters, actor);
    if (body === undefined || String(body).trim() === '') fail('BODY_REQUIRED', 'Review 답변 본문이 필요합니다.');
    let reply;
    storeTransaction(this.store, (state) => {
      const subtask = findSubtask(state, subtaskId);
      const review = normalizeReview(currentReview(subtask));
      requireRevision(review, revision ?? reviewRevision ?? review_revision);
      const target = review.threads.find((candidate) => candidate.id === threadId);
      if (!target) fail('THREAD_NOT_FOUND', 'Review thread를 찾을 수 없습니다.', { target: threadId });
      reply = {
        id: counter(state, 'threadReply', 'reply'),
        body: agentBody(this.adapters, actualActor, body),
        actor: actorName(actualActor),
        actor_type: actorType(this.adapters, actualActor),
        createdAt: now(),
      };
      target.replies = [...(target.replies || []), reply];
      nextReviewRevision(state, review);
      subtask.review = review;
      replaceSubtask(state, subtask);
      invoke(this.adapters.review, ['reply', 'createReply'], { subtask, review, thread: target, reply });
    });
    return { thread: threadId, reply, state: 'OPEN', review_revision: this.currentRevision(subtaskId) };
  }

  resolve({ subtaskId, revision, reviewRevision, review_revision, thread: threadId, actor } = {}) {
    const actualActor = readActor(this.adapters, actor);
    let resolved;
    storeTransaction(this.store, (state) => {
      const subtask = findSubtask(state, subtaskId);
      const review = normalizeReview(currentReview(subtask));
      requireRevision(review, revision ?? reviewRevision ?? review_revision);
      const target = review.threads.find((candidate) => candidate.id === threadId);
      if (!target) fail('THREAD_NOT_FOUND', 'Review thread를 찾을 수 없습니다.', { target: threadId });
      if (humanResolutionRequired(target) && !isHuman(this.adapters, actualActor)) {
        fail('HUMAN_REQUIRED', '사람 리뷰어의 확인이 필요합니다.', {
          blocked_by: [blocked('HUMAN_REVIEW_REQUIRED', '사람이 작성한 thread는 사람이 해결해야 합니다.', threadId)],
          next: [nextAction('human', 'resolve_thread')],
        });
      }
      if (target.state === 'RESOLVED') {
        resolved = target;
        return;
      }
      target.state = 'RESOLVED';
      target.resolvedBy = actorName(actualActor);
      target.resolvedAt = now();
      resolved = target;
      nextReviewRevision(state, review);
      subtask.review = review;
      replaceSubtask(state, subtask);
      invoke(this.adapters.review, ['resolve', 'resolveThread'], { subtask, review, thread: target, actor: actualActor });
    });
    return { thread: resolved, review_revision: this.currentRevision(subtaskId), blocked_by: this.blocked(subtaskId) };
  }

  currentRevision(subtaskId) {
    return normalizeReview(currentReview(findSubtask(storeLoad(this.store), subtaskId))).reviewRevision;
  }

  blocked(subtaskId) {
    return reviewBlockers(normalizeReview(currentReview(findSubtask(storeLoad(this.store), subtaskId))), subtaskId);
  }
}

function dependencyOf(subtask) {
  return subtask?.requires ?? subtask?.dependency?.requires ?? subtask?.stack?.requires ?? null;
}

function checkCycle(state, childId, parentId) {
  if (childId === parentId) fail('DEPENDENCY_CYCLE', 'SubTask가 자기 자신을 직접 의존할 수 없습니다.');
  const visited = new Set([childId]);
  let cursor = parentId;
  while (cursor) {
    if (visited.has(cursor)) fail('DEPENDENCY_CYCLE', '순환 dependency는 허용되지 않습니다.');
    visited.add(cursor);
    cursor = dependencyOf(findSubtask(state, cursor));
  }
}

function stackDiffResult(adapters, payload, prior) {
  const result = invoke(adapters.git, ['restack', 'stack'], payload) || {};
  const identity = result.diffIdentity ?? result.diff_identity ?? payload.diffIdentity ?? prior.diffIdentity;
  const changed = result.diffChanged ?? result.changed ?? (identity !== prior.diffIdentity);
  return { ...result, diffIdentity: identity, changed };
}

/** Direct dependency management and sync recovery state machine. */
export class StackSyncService {
  constructor({ store, adapters = {} } = {}) {
    if (!store) fail('STORE_REQUIRED', 'StackSyncService에는 store가 필요합니다.');
    this.store = store;
    this.adapters = adapters;
  }

  stack({ subtaskId, requires, clear = false } = {}) {
    if ((clear && requires) || (!clear && !requires)) fail('INVALID_ARGUMENT', '--requires 또는 --clear 중 하나가 필요합니다.');
    let output;
    storeTransaction(this.store, (state) => {
      const subtask = findSubtask(state, subtaskId);
      if (!subtask) fail('SUBTASK_NOT_FOUND', 'SubTask를 찾을 수 없습니다.', { target: subtaskId });
      const parent = requires ? findSubtask(state, requires) : null;
      if (requires && !parent) fail('DEPENDENCY_NOT_FOUND', '선행 SubTask를 찾을 수 없습니다.', { target: requires });
      if (parent && parent.state === 'MERGED') fail('DEPENDENCY_ALREADY_MERGED', '이미 통합된 SubTask는 Stack 선행 기준이 될 수 없습니다.', { target: requires });
      if (parent) checkCycle(state, subtaskId, requires);
      const oldReview = subtask.review ? normalizeReview(subtask.review) : null;
      const oldIdentity = oldReview?.diffIdentity;
      const base = parent ? parent.branch ?? parent.id : 'main';
      const baseRevision = parent ? parent.baseRevision ?? parent.revision ?? parent.id : state.remote?.mainRevision ?? 'main';
      const result = stackDiffResult(this.adapters, {
        subtask,
        parent,
        requires: requires ?? null,
        base,
        baseRevision,
      }, oldReview || {});
      if (result.conflict || result.code === 'SYNC_CONFLICT') {
        fail('SYNC_CONFLICT', 'Stack restack 중 코드 충돌이 발생했습니다.', {
          conflicts: result.conflicts || [],
          workspace: subtask.workspace,
        });
      }
      subtask.requires = requires ?? null;
      subtask.stack = { requires: requires ?? null, base, baseRevision };
      subtask.base = base;
      subtask.baseRevision = baseRevision;
      if (oldReview) {
        oldReview.diffIdentity = result.diffIdentity;
        oldReview.base = base;
        oldReview.pr = { ...(oldReview.pr || {}), base };
        if (result.diff !== undefined) oldReview.diff = result.diff;
        if (result.changed) {
          oldReview.changeRevision = counter(state, 'change', 'cr');
          oldReview.approval = null;
        }
        nextReviewRevision(state, oldReview);
        subtask.review = oldReview;
      }
      invoke(this.adapters.task, ['setDependency', 'updateDependency'], { subtask, parent, requires: requires ?? null });
      invoke(this.adapters.review, ['updateBase', 'setBase'], { subtask, base, parent });
      replaceSubtask(state, subtask);
      output = {
        subtask: subtask.id,
        requires: requires ?? null,
        base,
        base_revision: baseRevision,
        diff_identity: oldReview?.diffIdentity ?? result.diffIdentity ?? null,
        change_revision: oldReview?.changeRevision ?? null,
        approval_applicable: oldReview ? approvalApplicable(oldReview) : false,
        approval_preserved: Boolean(oldReview && !result.changed && oldReview.approval),
        review_revision: oldReview?.reviewRevision ?? null,
        next: oldReview && result.changed ? [nextAction('agent', 'run_check')] : [],
      };
    });
    return output;
  }

  sync({ subtaskId, mode = 'start' } = {}) {
    if (!['start', 'continue', 'abort'].includes(mode)) fail('INVALID_ARGUMENT', 'sync mode가 올바르지 않습니다.');
    const state = storeLoad(this.store);
    const subtask = findSubtask(state, subtaskId);
    if (!subtask) fail('SUBTASK_NOT_FOUND', 'SubTask를 찾을 수 없습니다.', { target: subtaskId });
    if (mode === 'abort') return this.abortSync(subtaskId, state);
    if (mode === 'continue') return this.continueSync(subtaskId, state);
    if (subtask.sync?.state === 'CONFLICT') fail('SYNC_IN_PROGRESS', '충돌 해결 중인 sync가 있습니다.', { next: [nextAction('agent', 'continue_sync')] });

    const before = clone(subtask);
    const remote = invoke(this.adapters.git, ['remoteState', 'getRemoteState'], { subtask }) || {};
    const parent = dependencyOf(subtask) ? findSubtask(state, dependencyOf(subtask)) : null;
    const base = parent && parent.state !== 'MERGED' ? parent.branch ?? parent.id : 'main';
    const result = invoke(this.adapters.git, ['sync', 'synchronize'], { subtask, parent, base, remote }) || {};
    if (result.conflict || result.code === 'SYNC_CONFLICT') {
      const conflictState = {
        state: 'CONFLICT',
        before,
        conflicts: result.conflicts || [],
        workspace: subtask.workspace ?? null,
        base,
        startedAt: now(),
      };
      storeTransaction(this.store, (nextState) => {
        const target = findSubtask(nextState, subtaskId);
        target.sync = conflictState;
        replaceSubtask(nextState, target);
      });
      return {
        type: 'failure',
        data: {
          code: 'SYNC_CONFLICT',
          message: '자동으로 동기화할 수 없는 코드 충돌이 있습니다.',
          workspace: conflictState.workspace,
          conflicts: conflictState.conflicts,
          next: [nextAction('agent', 'resolve_conflicts'), nextAction('agent', 'continue_sync'), nextAction('agent', 'abort_sync')],
        },
      };
    }
    return this.commitSync(subtaskId, { ...result, base, parent, remote });
  }

  continueSync(subtaskId, state = storeLoad(this.store)) {
    const subtask = findSubtask(state, subtaskId);
    if (!subtask?.sync || subtask.sync.state !== 'CONFLICT') fail('SYNC_NOT_CONFLICT', '--continue은 SYNC_CONFLICT 상태에서만 사용할 수 있습니다.');
    const result = invoke(this.adapters.git, ['continueSync', 'continue'], { subtask, sync: subtask.sync }) || {};
    const unresolved = result.conflict || result.conflicts?.length || invoke(this.adapters.git, ['hasConflicts'], { subtask });
    if (unresolved) {
      fail('SYNC_CONFLICT', '충돌이 아직 해결되지 않았습니다.', {
        conflicts: result.conflicts || subtask.sync.conflicts || [],
        next: [nextAction('agent', 'continue_sync'), nextAction('agent', 'abort_sync')],
      });
    }
    return this.commitSync(subtaskId, result);
  }

  abortSync(subtaskId, state = storeLoad(this.store)) {
    const subtask = findSubtask(state, subtaskId);
    if (!subtask?.sync || subtask.sync.state !== 'CONFLICT') fail('SYNC_NOT_CONFLICT', '--abort은 SYNC_CONFLICT 상태에서만 사용할 수 있습니다.');
    const before = clone(subtask.sync.before);
    invoke(this.adapters.git, ['abortSync', 'abort'], { subtask, sync: subtask.sync });
    storeTransaction(this.store, (nextState) => replaceSubtask(nextState, before));
    return { subtask: subtaskId, state: 'ABORTED', restored: true, next: [nextAction('agent', 'run_check')] };
  }

  commitSync(subtaskId, result = {}) {
    let output;
    storeTransaction(this.store, (state) => {
      const subtask = findSubtask(state, subtaskId);
      const review = subtask.review ? normalizeReview(subtask.review) : null;
      const previousIdentity = review?.diffIdentity;
      const identity = result.diffIdentity ?? result.diff_identity ?? previousIdentity;
      const changed = result.diffChanged ?? result.changed ?? (identity !== previousIdentity);
      if (review) {
        review.diffIdentity = identity;
        if (result.diff !== undefined) review.diff = result.diff;
        if (changed) {
          review.changeRevision = counter(state, 'change', 'cr');
          review.approval = null;
        }
        nextReviewRevision(state, review);
        subtask.review = review;
      }
      subtask.sync = { state: 'SYNCED', completedAt: now(), base: result.base ?? subtask.base ?? 'main' };
      subtask.base = result.base ?? subtask.base ?? 'main';
      if (result.baseRevision) subtask.baseRevision = result.baseRevision;
      replaceSubtask(state, subtask);
      output = {
        subtask: subtaskId,
        state: 'SYNCED',
        base: subtask.base,
        diff_identity: review?.diffIdentity ?? null,
        change_revision: review?.changeRevision ?? null,
        approval_applicable: review ? approvalApplicable(review) : false,
        approval_invalidated: Boolean(review && changed),
        next: changed ? [nextAction('agent', 'run_check')] : [],
      };
    });
    return output;
  }
}

export class WorkflowReviewStack {
  constructor({ store, adapters = {} } = {}) {
    this.store = store || new InMemoryWorkflowStore();
    this.review = new ReviewService({ store: this.store, adapters });
    this.stackSync = new StackSyncService({ store: this.store, adapters });
  }

  show(options) { return this.review.show(options); }
  comment(options) { return this.review.comment(options); }
  reply(options) { return this.review.reply(options); }
  resolve(options) { return this.review.resolve(options); }
  stack(options) { return this.stackSync.stack(options); }
  sync(options) { return this.stackSync.sync(options); }
}

export function createReviewStackApplication(options) {
  return new WorkflowReviewStack(options);
}

export { approvalApplicable, unresolvedRequired, reviewBlockers, normalizeReview };
