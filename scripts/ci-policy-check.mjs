import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const workflow = readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8');
const securityAction = readFileSync(new URL('../.github/actions/container-security/action.yml', import.meta.url), 'utf8');
const securityScript = readFileSync(new URL('../.github/actions/container-security/container-security.sh', import.meta.url), 'utf8');

assert.match(workflow, /^  pull_request_target:\s*$/m);
assert.doesNotMatch(workflow, /^  pull_request:\s*$/m);
assert.match(workflow, /^permissions:\n  contents: read\s*$/m);
assert.doesNotMatch(workflow, /actions\/cache@/, 'CI must not use broad hosted GitHub cache storage');
assert.match(workflow, /run: mvn -B -ntp verify/,
  'Maven must be allowed to retrieve uncached or updated dependencies online');
assert.doesNotMatch(workflow, /mvn[^\n]*(?:--offline|\s-o(?:\s|$))/,
  'the dependency verification must not be forced offline');

const jobBlock = (job) => {
  const header = new RegExp('^  ' + job.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ':[ \\t]*$', 'm').exec(workflow);
  assert.ok(header, `workflow must contain the ${job} job`);
  const remainder = workflow.slice(header.index + header[0].length);
  const nextJob = remainder.search(/^  [A-Za-z_][A-Za-z0-9_-]*:[ \t]*$/m);
  return nextJob < 0 ? remainder : remainder.slice(0, nextJob);
};

const verifyJob = jobBlock('verify');
const publishJob = jobBlock('publish');
const cleanupJob = jobBlock('cleanup-artifacts');
const guard = (job, block) => {
  const rawExpression = block.match(/^    if: (.+)$/m)?.[1];
  assert.ok(rawExpression, `${job} must have a job-level trust guard`);
  const expression = rawExpression.replace(/^\$\{\{\s*|\s*\}\}$/g, '');
  return github => runInNewContext(expression, { github, always: () => true }, { timeout: 100 });
};
const verify = guard('verify', verifyJob);
const publish = guard('publish', publishJob);
const cleanup = guard('cleanup-artifacts', cleanupJob);
const context = (event, head = 'javaDevJT/embedify', ref = 'refs/heads/main', identity = { actor: 'javaDevJT', login: 'javaDevJT', id: 1 }) => ({
  event_name: event,
  ref,
  repository: 'javaDevJT/embedify',
  actor: identity.actor,
  event: { pull_request: { head: { repo: { full_name: head }, sha: 'trusted-head' }, user: { login: identity.login, id: identity.id } } },
});

// Exercise the workflow expressions, including the cleanup finalizer, for both
// PR event types so fork-triggered token and artifact cleanup are rejected.
// The workflow only subscribes to pull_request_target; pull_request is included
// here to pin the trust behavior if that trigger is ever added later.
for (const event of ['pull_request', 'pull_request_target']) {
  for (const head of ['outsider/embedify', 'javaDevJT/other-fork']) {
    const fork = context(event, head);
    if (event === 'pull_request_target') {
      assert.equal(verify(fork), false, 'Fork pull_request_target events must not allocate a runner');
    }
    assert.equal(publish(fork), false, 'Fork PRs must not publish');
    assert.equal(cleanup(fork), false, 'Fork PRs must not receive a cleanup job with actions:write');
  }
  const sameRepository = context(event);
  assert.equal(cleanup(sameRepository), true, 'Cleanup may run for a same-repository PR');
  assert.equal(publish(sameRepository), false, 'PR events must not publish');
  if (event === 'pull_request_target') {
    assert.equal(verify(sameRepository), true, 'Same-repository pull_request_target events may run source checks');
  }
}
const dependabot = { actor: 'dependabot[bot]', login: 'dependabot[bot]', id: 49699333 };
for (const identity of [dependabot, { actor: 'quarterback-ci[bot]', login: 'quarterback-ci[bot]', id: 341165314 }]) {
  const trusted = context('pull_request_target', undefined, undefined, identity);
  assert.equal(verify(trusted), true, 'Verified same-repository automation may run read-only source checks');
  assert.equal(publish(trusted), false, 'Bot PR validation must not receive publication authority');
}
for (const identity of [
  { ...dependabot, id: 49699334 },
  { ...dependabot, login: 'other-user' },
  { ...dependabot, actor: 'unapproved-user' },
]) {
  assert.equal(verify(context('pull_request_target', undefined, undefined, identity)), false,
    'Actor and author identity mismatches must not allocate a validation runner');
}
assert.equal(verify(context('pull_request_target', 'outsider/embedify', undefined, dependabot)), false);
for (const event of ['push', 'workflow_dispatch', 'schedule']) {
  assert.equal(verify(context(event)), true);
  assert.equal(publish(context(event)), true);
  assert.equal(cleanup(context(event)), true);
  assert.equal(verify(context(event, undefined, 'refs/heads/untrusted')), false);
  assert.equal(publish(context(event, undefined, 'refs/heads/untrusted')), false);
}

const checkoutSteps = [...workflow.matchAll(/^      - name:[^\n]*\n([\s\S]*?)(?=^      - name:|^  [A-Za-z_][A-Za-z0-9_-]*:)/gm)]
  .map((match) => match[1])
  .filter((step) => step.includes('uses: actions/checkout@'));
assert.equal(checkoutSteps.length, 3, 'verify, publish, and cleanup should each have one checkout');
for (const step of checkoutSteps) {
  assert.match(step, /persist-credentials: false/,
    'no checkout may persist a GitHub token in local Git configuration');
}
assert.match(verifyJob, /ref: \$\{\{ github\.event\.pull_request\.head\.sha \|\| github\.sha \}\}/,
  'the verify job must test the pull-request source revision');
assert.match(cleanupJob, /ref: \$\{\{ github\.event\.repository\.default_branch \}\}/,
  'cleanup code must come from the trusted default branch');
assert.match(cleanupJob, /actions: write\s+contents: read/,
  'only the cleanup job should receive artifact deletion authority');
assert.doesNotMatch(verifyJob, /packages: write|actions: write/,
  'verification must not receive publication or artifact deletion permissions');
assert.match(publishJob, /packages: write/);

const workflowUploads = [...workflow.matchAll(/uses: actions\/upload-artifact@/g)].map((match) => {
  const remainder = workflow.slice(match.index);
  const nextStep = remainder.search(/^      - name:/m);
  return nextStep < 0 ? remainder : remainder.slice(0, nextStep);
});
assert.equal(workflowUploads.length, 1, 'the workflow may retain only its bounded CI diagnostic');
assert.match(workflowUploads[0], /name: ci-log-/);
assert.match(workflowUploads[0], /path: \$\{\{ runner\.temp \}\}\/ci-storage-evidence\.log/);
assert.match(workflowUploads[0], /retention-days: 1/);
assert.doesNotMatch(workflowUploads[0], /\.json|\.sarif|\*|\*\*/,
  'workflow diagnostics must not include scan reports, SARIF, or broad globs');

const securityUploadAt = securityAction.indexOf('uses: actions/upload-artifact@');
assert.ok(securityUploadAt > securityAction.indexOf('id: minimize'),
  'only minimized scan results may be uploaded, after report reduction');
const securityUpload = securityAction.slice(securityUploadAt);
assert.match(securityUpload, /name: security-high-critical-/);
assert.match(securityUpload, /path: \$\{\{ runner\.temp \}\}\/security-high-critical\.json/);
assert.match(securityUpload, /retention-days: 1/);
assert.doesNotMatch(securityUpload, /\.grype\.json|\.syft\.json|\.sarif|\*|\*\*/,
  'complete raw scan reports must stay job-local');
assert.match(securityAction, /minimize-security-reports\.py/);

// Preserve the full source -> immutable digest -> security gate -> tag flow.
assert.match(publishJob, /SECURITY_PUBLISH: "true"/);
assert.match(publishJob, /outputs: \$\{\{ steps\.container_security_1\.outputs\.exporter \}\}/);
assert.match(publishJob, /push: false # Push-by-digest is configured in the exporter/);
assert.match(publishJob,
  /image: registry:\$\{\{ steps\.container_security_1\.outputs\.repository \}\}@\$\{\{ steps\.build\.outputs\.digest \}\}/,
  'the scan must inspect the built immutable digest');
assert.ok(publishJob.indexOf('Build and publish image with provenance') < publishJob.indexOf('Generate SBOM and enforce High/Critical vulnerability gate'));
assert.ok(publishJob.indexOf('Generate SBOM and enforce High/Critical vulnerability gate') < publishJob.indexOf('Publish release tags only after the vulnerability gate passes'));
assert.match(securityScript, /push-by-digest=true,name-canonical=true,push=true/);
assert.match(securityScript, /--fail-on high/);
assert.match(securityScript, /for platform in "\$\{platforms\[@\]\}"/);
assert.match(securityScript, /\[\[ "\$failed" == 0 \]\] \|\| exit 1/);
assert.match(securityScript, /SECURITY_IMAGE" =~ \^ghcr/,
  'release tagging requires an immutable GHCR digest');
assert.match(securityScript, /Missing successful security gate evidence/);
assert.match(securityScript, /Gate evidence belongs to a different image/);
assert.match(securityScript, /docker buildx imagetools inspect "\$tag"/);
assert.match(securityScript, /\[\[ "\$actual" == "\$digest" \]\]/,
  'each published release tag must resolve to the scanned digest');

console.log('CI trust and release policy checks passed.');
