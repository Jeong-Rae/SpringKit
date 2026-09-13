'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { WorkflowStore, LocalIdentityAdapter, emptyState } = require('../lib/workflow');

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'workflow-test-'));
  const workspace = path.join(root, 'workspace');
  fs.mkdirSync(workspace, { recursive: true });
  fs.writeFileSync(path.join(workspace, 'change.txt'), 'initial\n');
  const stateFile = path.join(root, 'state.json');
  const store = new WorkflowStore(stateFile);
  store.save(emptyState());
  const calls = [];
  const adapters = {
    task: {
      getTask: (id) => ({ id, state: 'OPEN' }),
      linkSubtask: ({ subtask }) => calls.push(['task.linkSubtask', subtask.id])
    },
    git: {
      fetchMain: () => 'main-revision',
      createWorktree: ({ workspace: target, branch, base }) => {
        fs.mkdirSync(target, { recursive: true });
        fs.writeFileSync(path.join(target, 'change.txt'), 'initial\n');
        calls.push(['git.createWorktree', branch, base]);
        return { path: target, branch, base };
      },
      removeWorktree: () => calls.push(['git.removeWorktree']),
      currentRevision: () => 'head-revision',
      fingerprint: (target, options) => require('../lib/workflow').fingerprintDirectory(target, options),
      publishBranch: ({ branch }) => calls.push(['git.publishBranch', branch]),
      diffIdentity: ({ base, cwd: target }) => `diff:${base}:${require('../lib/workflow').fingerprintDirectory(target)}`
    },
    review: {
      createDraft: (input) => {
        calls.push(['review.createDraft', input.title, input.base]);
        return { id: 'pr-1', number: 1, state: 'DRAFT' };
      },
      update: (input) => calls.push(['review.update', input.codeChanged])
    },
    ci: { start: ({ changeRevision }) => ({ id: `ci-${changeRevision}`, state: 'QUEUED' }) },
    identity: new LocalIdentityAdapter()
  };
  return { root, workspace, stateFile, store, adapters, calls };
}

function appFor(fixtureValue, cwd = fixtureValue.root) {
  const { WorkflowApplication } = require('../lib/workflow');
  return new WorkflowApplication({ cwd, repoRoot: fixtureValue.root, store: fixtureValue.store, adapters: fixtureValue.adapters });
}

function assertSuccess(assert, result) {
  assert.equal(result.type, 'success', JSON.stringify(result));
  return result.data;
}

module.exports = { fixture, appFor, assertSuccess };
