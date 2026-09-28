import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const workflow = readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8');
assert.match(workflow, /^  pull_request_target:\s*$/m);
assert.doesNotMatch(workflow, /^  pull_request:\s*$/m);
assert.match(workflow, /ref: \$\{\{ github\.event\.pull_request\.head\.sha \|\| github\.sha \}\}/);
assert.match(workflow, /persist-credentials: false/);
assert.match(workflow, /^permissions:\n  contents: read\s*$/m);

// Read the actual job guards so removing or weakening one fails this check.
const guard = job => {
  const block = workflow.split(new RegExp(`^  ${job}:\\n`, 'm'))[1]?.split(/^  \w+:\n/m)[0];
  const expression = block?.match(/^    if: (.+)$/m)?.[1];
  assert.ok(expression, `${job} must have a job-level trust guard`);
  return github => runInNewContext(expression, { github }, { timeout: 100 });
};
const verify = guard('verify');
const publish = guard('publish');
const context = (event, head = 'javaDevJT/embedify', ref = 'refs/heads/main') => ({
  event_name: event, ref, repository: 'javaDevJT/embedify',
  event: { pull_request: { head: { repo: { full_name: head }, sha: 'trusted-head' } } },
});

for (const head of ['outsider/embedify', 'javaDevJT/other-fork']) {
  const fork = context('pull_request_target', head);
  assert.equal(verify(fork), false, 'Fork PRs must not allocate a runner');
  assert.equal(publish(fork), false, 'Fork PRs must not publish');
}
assert.equal(verify(context('pull_request_target')), true, 'Same-repository PRs should be checked');
assert.equal(publish(context('pull_request_target')), false, 'PR checks must not publish');
for (const event of ['push', 'workflow_dispatch']) {
  assert.equal(verify(context(event)), true);
  assert.equal(publish(context(event)), true);
  assert.equal(verify(context(event, undefined, 'refs/heads/untrusted')), false);
  assert.equal(publish(context(event, undefined, 'refs/heads/untrusted')), false);
}
console.log('CI trust policy checks passed.');
