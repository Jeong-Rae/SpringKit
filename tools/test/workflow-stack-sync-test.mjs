import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequire } from 'node:module';
import {
  InMemoryWorkflowStore,
  StackSyncService,
  WorkflowError,
} from '../workflow-review-stack.mjs';

const require = createRequire(import.meta.url);
const { fixture: cliFixture, appFor, assertSuccess } = require('./test-support.js');

function fixture() {
  return new InMemoryWorkflowStore({
    counters: { review: 3, change: 4 },
    remote: { mainRevision: 'main-2' },
    subtasks: [
      {
        id: 'sk-parent', branch: 'sk-parent', state: 'DRAFT', baseRevision: 'main-1',
        workspace: { path: '/repo/sk-parent' },
      },
      {
        id: 'sk-child', branch: 'sk-child', state: 'DRAFT', base: 'main', baseRevision: 'main-1',
        workspace: { path: '/repo/sk-child' },
        review: {
          reviewRevision: 'rv-3', changeRevision: 'cr-4', diffIdentity: 'same-diff',
          approval: { state: 'APPROVED', changeRevision: 'cr-4', diffIdentity: 'same-diff', actor: 'human' },
          threads: [],
        },
      },
    ],
  });
}

test('stack accepts one direct dependency, updates base, and rejects cycles', () => {
  const store = fixture();
  const stacks = new StackSyncService({
    store,
    adapters: { git: { restack: () => ({ diffIdentity: 'same-diff' }) } },
  });
  const result = stacks.stack({ subtaskId: 'sk-child', requires: 'sk-parent' });
  assert.equal(result.requires, 'sk-parent');
  assert.equal(result.base, 'sk-parent');
  assert.equal(result.approval_preserved, true);
  assert.equal(store.getSubtask('sk-child').review.changeRevision, 'cr-4');
  assert.throws(
    () => stacks.stack({ subtaskId: 'sk-parent', requires: 'sk-child' }),
    (error) => error instanceof WorkflowError && error.code === 'DEPENDENCY_CYCLE',
  );
});

test('changed diff identity invalidates approval and change revision', () => {
  const store = fixture();
  const stacks = new StackSyncService({
    store,
    adapters: { git: { restack: () => ({ diffIdentity: 'changed-diff' }) } },
  });
  const result = stacks.stack({ subtaskId: 'sk-child', requires: 'sk-parent' });
  assert.equal(result.approval_preserved, false);
  assert.equal(result.approval_applicable, false);
  const child = store.getSubtask('sk-child');
  assert.equal(child.review.approval, null);
  assert.equal(child.review.changeRevision, 'cr-5');
});

test('clear removes the direct dependency without creating a cycle', () => {
  const store = fixture();
  store.transaction((state) => { state.subtasks[1].requires = 'sk-parent'; });
  const stacks = new StackSyncService({ store, adapters: { git: { restack: () => ({ diffIdentity: 'same-diff' }) } } });
  const result = stacks.stack({ subtaskId: 'sk-child', clear: true });
  assert.equal(result.requires, null);
  assert.equal(store.getSubtask('sk-child').requires, null);
});

test('sync conflict enters recoverable state, continue completes, and abort restores snapshot', () => {
  const store = fixture();
  let phase = 'conflict';
  const stacks = new StackSyncService({
    store,
    adapters: {
      git: {
        sync: () => phase === 'conflict' ? { conflict: true, conflicts: [{ path: 'src/a.js', kind: 'content' }] } : { diffIdentity: 'same-diff' },
        continueSync: () => ({ diffIdentity: 'same-diff' }),
      },
    },
  });
  const conflict = stacks.sync({ subtaskId: 'sk-child' });
  assert.equal(conflict.type, 'failure');
  assert.equal(conflict.data.code, 'SYNC_CONFLICT');
  assert.equal(store.getSubtask('sk-child').sync.state, 'CONFLICT');
  phase = 'resolved';
  const completed = stacks.sync({ subtaskId: 'sk-child', mode: 'continue' });
  assert.equal(completed.state, 'SYNCED');
  assert.equal(store.getSubtask('sk-child').sync.state, 'SYNCED');

  const secondStore = fixture();
  const aborting = new StackSyncService({ store: secondStore, adapters: { git: { sync: () => ({ conflict: true, conflicts: [{ path: 'x' }] }) } } });
  aborting.sync({ subtaskId: 'sk-child' });
  const aborted = aborting.sync({ subtaskId: 'sk-child', mode: 'abort' });
  assert.equal(aborted.restored, true);
  assert.equal(secondStore.getSubtask('sk-child').sync, undefined);
  assert.equal(secondStore.getSubtask('sk-child').base, 'main');
});

test('public stack and sync commands operate on the managed worktree', () => {
  const value = cliFixture();
  const root = appFor(value);
  const parent = assertSuccess(assert, root.execute([
    'start', '--task', 'TASK-42', '--request-id', 'parent', '--title', 'parent',
  ]));
  const child = assertSuccess(assert, root.execute([
    'start', '--task', 'TASK-42', '--request-id', 'child', '--title', 'child',
  ]));
  const childApp = appFor(value, child.workspace.path);
  const stacked = assertSuccess(assert, childApp.execute(['stack', '--requires', parent.subtask]));
  assert.equal(stacked.requires, parent.subtask);
  const synced = assertSuccess(assert, childApp.execute(['sync']));
  assert.equal(synced.state, 'SYNCED');
});
