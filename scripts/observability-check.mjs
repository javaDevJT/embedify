import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';

const read = path => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');
const dockerfile = read('Dockerfile');
const compose = read('compose.yaml');
const collector = read('deploy/otel/collector.yaml');
const smoke = read('scripts/otel-smoke.sh');
const keyPrompt = read('scripts/PromptOpenWoofKey.js');
const truenas = read('scripts/truenas.mjs');
const envExample = read('.env.example');
assert(!existsSync(new URL('../scripts/PromptOpenWoofKey.swift', import.meta.url)),
  'The removed Swift key prompt must not return to the repository.');
assert.match(compose, /host_ip: "\$\{EMBEDIFY_BIND_ADDRESS:\?/);
assert.match(compose, /name: \$\{EMBEDIFY_PROXY_NETWORK:\?/);
assert.match(compose, /source: \$\{OPENWOOF_INGEST_KEY_PATH:\?/);
assert.match(envExample, /^EMBEDIFY_BIND_ADDRESS=$/m);
assert.match(envExample, /^EMBEDIFY_PROXY_NETWORK=$/m);
assert.match(envExample, /^OPENWOOF_INGEST_KEY_PATH=$/m);
assert.match(truenas, /keychain-identity/,
  'The operator-side prompt must derive its target from local ignored configuration.');
assert.doesNotMatch(`${compose}\n${envExample}\n${truenas}\n${keyPrompt}`,
  /\b192\.168\.\d{1,3}\.\d{1,3}\b|\/mnt\//,
  'Public deployment sources must not expose machine-specific hosts or host mount paths.');

assert.match(dockerfile, /^FROM otel\/opentelemetry-collector-contrib:0\.163\.2@sha256:[a-f0-9]{64} AS collector$/m,
  'Collector build input must be version and digest pinned.');
assert.match(dockerfile, /ADD --checksum=sha256:[a-f0-9]{64}[\s\\\n]*https:\/\/github\.com\/open-telemetry\/opentelemetry-java-instrumentation\/releases\/download\/v2\.32\.0\/opentelemetry-javaagent\.jar/m,
  'Java agent must be downloaded from the official release with a pinned checksum.');
assert.match(dockerfile, /ENTRYPOINT \["\/opt\/runtime\/bin\/java", "-javaagent:\/opt\/opentelemetry-javaagent\.jar"/,
  'The application must start with the pinned Java agent.');
assert.match(dockerfile, /modules="\$\{modules\},jdk\.crypto\.ec,java\.instrument"/,
  'The reduced runtime must retain the agent module even if app dependencies stop referencing it.');
assert.match(dockerfile, /otelcol-contrib validate --config=\/tmp\/collector\.yaml/,
  'The release build must validate the actual Collector configuration.');
assert.match(dockerfile, /COPY scripts\/OtelSmoke\.java/);
assert.match(dockerfile, /COPY scripts\/otel-smoke\.sh/);
assert.match(dockerfile, /bash \/tmp\/otel-smoke\.sh/,
  'The release build must run isolated runtime telemetry regressions.');
assert.match(dockerfile, /RUN EMBEDIFY_OTEL_SMOKE_APP_JAVA=\/opt\/runtime\/bin\/java/,
  'The isolated smoke must start the app with the actual generated reduced runtime.');
assert(dockerfile.indexOf('jlink \\') < dockerfile.indexOf('RUN EMBEDIFY_OTEL_SMOKE_APP_JAVA='),
  'The app runtime must be generated before its telemetry regression runs.');
assert(smoke.includes('"${EMBEDIFY_OTEL_SMOKE_APP_JAVA:-java}" -javaagent:'),
  'Fixture-only JDK modules must not be required by the shipped app runtime.');
for (const path of [
  '/opt/otelcol-contrib',
  '/etc/embedify-otel/collector.yaml',
  '/etc/ssl/certs/ca-certificates.crt',
]) assert(dockerfile.includes(path), `The final image is missing ${path}.`);

assert.match(compose, /OTEL_EXPORTER_OTLP_ENDPOINT: http:\/\/otel-collector:4318/);
assert.match(compose, /OTEL_EXPORTER_OTLP_PROTOCOL: http\/protobuf/);
assert.match(compose, /OTEL_SERVICE_NAME: embedify/);
assert.match(compose, /service\.namespace=javadevjt,service\.version=\$\{EMBEDIFY_IMAGE[^\n]+deployment\.environment\.name=production/);
assert.doesNotMatch(compose, /OTEL_EXPORTER_OTLP_HEADERS|OPENWOOF_INGEST_KEY\s*[:=]/,
  'The OpenWoof credential must not be provided through Compose environment variables.');
const collectorService = compose.split(/^  otel-collector:\s*$/m)[1]?.split(/^networks:\s*$/m)[0];
assert(collectorService, 'Compose must define the app-scoped Collector.');
assert.doesNotMatch(collectorService, /^\s+ports:/m, 'Collector receiver ports must remain unpublished.');
assert.match(collectorService, /source: \$\{OPENWOOF_INGEST_KEY_PATH/);
assert.match(collectorService, /target: \/run\/secrets\/openwoof-ingest-key/);
assert.match(collectorService, /read_only: true/);
assert.match(collectorService, /telemetry: null/);
assert.doesNotMatch(collectorService, /proxy:/, 'Collector must not join the public reverse-proxy network.');

assert.match(collector, /scheme: Bearer\n\s+filename: \$\{env:OPENWOOF_INGEST_KEY_FILE\}/,
  'Collector authentication must read the single Bearer credential from the mounted file.');
assert.match(collector, /endpoint: 0\.0\.0\.0:4318/);
assert.match(collector, /endpoint: https:\/\/openwoof\.javadevjt\.tech/);
assert.match(collector, /insecure: false/);
assert.doesNotMatch(collector, /insecure_skip_verify:\s*true|endpoint:\s*http:\/\//i);
const pipelines = collector.split(/^  pipelines:\s*$/m)[1];
assert(pipelines, 'Collector pipelines must be defined.');
for (const signal of ['traces', 'metrics', 'logs']) {
  const pipeline = pipelines.split(new RegExp(`^    ${signal}:\\s*$`, 'm'))[1]?.split(/^    \w+:\s*$/m)[0];
  assert(pipeline, `Collector ${signal} pipeline is required.`);
  assert.match(pipeline, /receivers: \[otlp\]/);
  assert.match(pipeline, /exporters: \[otlphttp\/openwoof\]/);
  assert.match(pipeline, /processors: \[memory_limiter,/);
}
for (const attribute of ['url.full', 'url.original', 'url.query', 'url.path', 'http.url', 'http.target']) {
  assert(collector.includes(`delete_key(attributes, "${attribute}")`), `Collector must remove sensitive ${attribute} fields.`);
}
const metricPolicy = collector.split(/metric_statements:\s*\n/)[1]?.split(/log_statements:\s*\n/)[0];
assert(metricPolicy, 'Collector metric privacy policy must be present.');
for (const attribute of ['server.address', 'server.port', 'network.peer.address', 'network.peer.port', 'net.peer.name', 'net.peer.ip']) {
  assert(metricPolicy.includes(`delete_key(attributes, "${attribute}")`),
    `High-cardinality peer field ${attribute} must not be a metric dimension.`);
}
assert.match(metricPolicy, /context: exemplar/,
  'Metric exemplar filtered attributes need the same targeted privacy policy as datapoints.');
for (const attribute of ['url.full', 'url.original', 'url.query', 'url.path', 'http.url', 'http.target',
  'server.address', 'server.port', 'network.peer.address', 'network.peer.port', 'net.peer.name', 'net.peer.ip']) {
  assert(metricPolicy.includes(`delete_key(exemplar.filtered_attributes, "${attribute}")`),
    `Metric exemplars must remove sensitive ${attribute} fields without dropping the exemplar.`);
}
assert.match(collector, /replace_pattern\(body,/,
  'Log messages must be redacted by targeted URL patterns while retaining their surrounding detail.');
assert.match(collector, /replace_pattern\(attributes\["exception\.message"\]/);
assert.match(collector, /replace_pattern\(attributes\["exception\.stacktrace"\]/);
assert.doesNotMatch(collector, /\(\?:/, 'Collector regular expressions must use RE2-compatible grouping.');
assert.match(collector, /error_mode: propagate/,
  'Collector transformation errors must be visible rather than silently dropping malformed telemetry.');
assert.match(collector, /queue_size: 256/);
assert.match(collector, /max_elapsed_time: 10m/);
assert.doesNotMatch(collector, /delete_key\(.*body|set\(body|"redacted"\) where/,
  'Collector must not delete or replace original log bodies wholesale.');
for (const evidence of [
  'fixture-untraced-log-healthy',
  'fixture-multiline-first',
  'fixture-multiline-second',
  'fixture-redaction-message',
  'http.client.request.duration',
  'http.server.request.duration',
  'Exemplar #0',
  'SMOKE_QUERY',
  'local-agent-export-failure',
  'connection refused',
]) assert(smoke.includes(evidence), `The isolated smoke test must cover ${evidence}.`);

assert.match(keyPrompt, /hiddenAnswer: true/,
  'The OpenWoof key must be entered only through a secure hidden field.');
assert.match(keyPrompt, /--store-keychain/);
assert.match(keyPrompt, /--install-keychain/);
assert.doesNotMatch(keyPrompt, /" -U"|Replace and Install/,
  'The credential helper must preserve existing keys rather than providing an implicit rotation path.');
assert.match(keyPrompt, /throw new Error\("OpenWoof key setup stopped;/,
  'Credential failures must use a fixed nonsecret error and a failing process exit.');
assert.match(keyPrompt, /task\.setStandardInput\(input\)/,
  'Secret handoff to child processes must use a private stdin pipe.');
assert.match(keyPrompt, /task\.setStandardOutput\(output\)/);
assert.match(keyPrompt, /task\.setStandardError\(errorOutput\)/);
assert.match(keyPrompt, /SecItemAdd\(/,
  'New Keychain entries must be created through the native API.');
assert.match(keyPrompt, /SecItemCopyMatching\(/,
  'Keychain reads must address the exact item through the native API.');
assert.doesNotMatch(keyPrompt, /find-generic-password|SecItemUpdate|SecItemDelete/,
  'The ingest helper must not retrieve credentials through subprocess output or change existing entries.');
assert.match(keyPrompt, /install-openwoof-key[\s\S]*?keyBytes/,
  'The retrieved key must be forwarded to TrueNAS only through process stdin.');
assert.doesNotMatch(keyPrompt, /console\.log|console\.write|print\(/,
  'The osascript helper must not print or log the key.');
assert.match(keyPrompt, /return "[^"]*(?:status|installed|cancelled|preserved|stopped)[^"]*"/i,
  'The osascript result must be nonsecret status only.');

console.log('OpenTelemetry build, network, credential, and privacy policy checks passed.');
