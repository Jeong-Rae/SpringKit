import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { createRequire } from 'node:module';
import {
  InMemoryWorkflowStore,
  ReviewService,
  WorkflowError,
} from '../workflow-review-stack.mjs';

const require = createRequire(import.meta.url);
const { fixture: cliFixture, appFor, assertSuccess } = require('./test-support.js');

function fixture() {
  return new InMemoryWorkflowStore({
    counters: { review: 8, change: 4 },
    subtasks: [{
      id: 'sk-27-review',
      state: 'DRAFT',
      review: {
        reviewRevision: 'rv-8',
        changeRevision: 'cr-4',
        diffIdentity: 'diff-4',
        diff: 'diff --git a/src/a.js b/src/a.js',
        pr: { id: 'pr-1', title: '[sk-27-review] review', body: 'body', base: 'main', state: 'DRAFT' },
        risk: 'normal',
        exposure: { mode: 'unchanged' },
        ci: { state: 'PASSED' },
        aiReview: { state: 'PASSED' },
        ready: null,
        approval: null,
        threads: [],
      },
    }],
  });
}

function service(store, actor = 'agent') {
  return new ReviewService({
    store,
    adapters: {
      identity: {
        actor: () => actor,
        isHuman: (value) => value === 'human',
      },
    },
  });
}

test('comment creates inline and general threads, and prefixes Agent messages', () => {
  const store = fixture();
  const reviews = service(store);
  const first = reviews.comment({
    subtaskId: 'sk-27-review', revision: 'rv-8', level: 'R', body: 'fallback is unsafe', path: 'src/a.js', line: 42,
  });
  assert.equal(first.thread.body, '[Agent] fallback is unsafe');
  assert.equal(first.thread.path, 'src/a.js');
  assert.equal(first.thread.line, 42);

  const second = reviews.comment({
    subtaskId: 'sk-27-review', revision: first.review_revision, level: 'A', body: 'consider naming this helper',
  });
  assert.equal(second.thread.path, null);
  assert.equal(reviews.show({ subtaskId: 'sk-27-review' }).threads.length, 2);
});

test('stale revisions do not mutate review state', () => {
  const store = fixture();
  const reviews = service(store);
  assert.throws(
    () => reviews.comment({ subtaskId: 'sk-27-review', revision: 'rv-old', level: 'C', body: 'stale' }),
    (error) => error instanceof WorkflowError && error.code === 'STALE_REVISION',
  );
  assert.equal(store.getSubtask('sk-27-review').review.threads.length, 0);
  assert.equal(store.getSubtask('sk-27-review').review.reviewRevision, 'rv-8');
});

test('reply keeps a thread open and prefixes agent replies', () => {
  const store = fixture();
  const reviews = service(store);
  const comment = reviews.comment({ subtaskId: 'sk-27-review', revision: 'rv-8', level: 'C', body: 'please explain' });
  const reply = reviews.reply({ subtaskId: 'sk-27-review', revision: comment.review_revision, thread: comment.thread.id, body: 'explained in the PR' });
  assert.equal(reply.state, 'OPEN');
  assert.equal(reply.reply.body, '[Agent] explained in the PR');
  assert.equal(store.getSubtask('sk-27-review').review.threads[0].state, 'OPEN');
});

test('human-authored threads cannot be resolved by an agent', () => {
  const store = fixture();
  const human = service(store, 'human');
  const comment = human.comment({ subtaskId: 'sk-27-review', revision: 'rv-8', level: 'R', body: 'human concern' });
  const agent = service(store, 'agent');
  assert.throws(
    () => agent.resolve({ subtaskId: 'sk-27-review', revision: comment.review_revision, thread: comment.thread.id }),
    (error) => error instanceof WorkflowError && error.code === 'HUMAN_REQUIRED',
  );
  const resolved = human.resolve({ subtaskId: 'sk-27-review', revision: comment.review_revision, thread: comment.thread.id });
  assert.equal(resolved.thread.state, 'RESOLVED');
});

test('open required threads block approval and queue next action', () => {
  const store = fixture();
  const reviews = service(store);
  const comment = reviews.comment({ subtaskId: 'sk-27-review', revision: 'rv-8', level: 'R', body: 'must fix' });
  const shown = reviews.show({ subtaskId: 'sk-27-review', diff: true, threads: 'all' });
  assert.equal(shown.diff, 'diff --git a/src/a.js b/src/a.js');
  assert.equal(shown.blocked_by[0].code, 'OPEN_REQUIRED_THREAD');
  assert.equal(shown.next[0].action, 'resolve_required_threads');
  const resolved = reviews.resolve({ subtaskId: 'sk-27-review', revision: comment.review_revision, thread: comment.thread.id });
  assert.equal(resolved.blocked_by.length, 0);
  assert.equal(reviews.show({ subtaskId: 'sk-27-review' }).next[0].action, 'mark_ready');
});

test('public review commands expose thread lifecycle', () => {
  const value = cliFixture();
  const root = appFor(value);
  const started = assertSuccess(assert, root.execute([
    'start', '--task', 'TASK-42', '--request-id', 'thread-cli', '--title', 'thread cli',
  ]));
  const app = appFor(value, started.workspace.path);
  fs.writeFileSync(path.join(started.workspace.path, 'pr.md'), 'review body\n');
  assertSuccess(assert, app.execute(['check']));
  const opened = assertSuccess(assert, app.execute([
    'review', 'open', '--body-file', 'pr.md', '--risk', 'normal', '--exposure', 'unchanged',
  ]));
  const comment = assertSuccess(assert, app.execute([
    'review', 'comment', '--revision', opened.review_revision, '--level', 'R', '--body', 'must fix',
  ]));
  assert.equal(comment.thread.body, '[Agent] must fix');
  const shown = assertSuccess(assert, app.execute(['review', 'show', '--diff', '--threads', 'open']));
  assert.equal(shown.threads.length, 1);
  assert.equal(shown.blocked_by[0].code, 'OPEN_REQUIRED_THREAD');
  const resolved = assertSuccess(assert, app.execute([
    'review', 'resolve', '--revision', comment.review_revision, '--thread', comment.thread.id,
  ]));
  assert.equal(resolved.thread.state, 'RESOLVED');
});
