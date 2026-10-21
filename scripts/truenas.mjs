#!/usr/bin/env bun

// Embedify-only TrueNAS helper. Scoped updates change its image or connect it to
// NPM; it cannot delete the app or target any other app.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import https from 'node:https';
import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve } from 'node:path';

const ROOT = resolve(import.meta.dir, '..');
const APP = 'embedify';
const TRUENAS = '192.168.0.2:8443';
const HOST_PORT = 30024;
const CONTAINER_PORT = 8080;
const COLLECTOR = 'otel-collector';
const IMAGE_REPOSITORY = 'ghcr.io/javadevjt/embedify';
const PROXY_NETWORK = 'ix-nginx-proxy-manager_default';
const OPENWOOF_SECRET_DIRECTORY = '/mnt/.ix-apps/app_mounts/embedify-observability';
const OPENWOOF_SECRET_PATH = `${OPENWOOF_SECRET_DIRECTORY}/openwoof-ingest-key`;
const OPENWOOF_SECRET_CONTAINER_PATH = '/run/secrets/openwoof-ingest-key';
const APP_TMPFS = '/tmp:rw,noexec,nosuid,nodev,size=16m,uid=10001,gid=10001,mode=1777';
const CONTROL_CA = resolve(import.meta.dir, 'truenas-control-plane.pem');
const RPC_METHODS = new Set([
  'auth.login_with_api_key', 'app.query', 'app.used_ports', 'docker.status', 'docker.network.query', 'app.create', 'app.config', 'app.update', 'core.get_jobs', 'core.subscribe', 'core.unsubscribe',
  'filesystem.stat', 'filesystem.mkdir', 'filesystem.chown',
]);

function imageDigest(service) {
  const imagePrefix = `${IMAGE_REPOSITORY}@sha256:`;
  assert(service.image?.startsWith(imagePrefix) && /^[a-f0-9]{64}$/.test(service.image.slice(imagePrefix.length)),
    'Embedify must use its private GHCR image pinned by digest.');
  return service.image;
}

function validateHardenedService(service, name) {
  assert(service, `Compose must define the ${name} service.`);
  assert.equal(service.read_only, true, `${name} must keep its root filesystem read-only.`);
  assert.equal(service.user, '10001:10001', `${name} must run as UID 10001.`);
  assert(service.cap_drop?.includes('ALL'), `${name} must drop all capabilities.`);
  assert(service.security_opt?.includes('no-new-privileges:true'), `${name} must disable privilege escalation.`);
  assert(!service.privileged && !service.network_mode && !service.pid && !service.ipc && !service.devices && !service.cap_add,
    'Compose must not request host access.');
  return service;
}

function memoryBytes(value) {
  if (typeof value === 'number') return value;
  const match = String(value ?? '').match(/^(\d+(?:\.\d+)?)(b|k|kb|m|mb|g|gb)?$/i);
  assert(match, 'Compose memory limit is not a recognized fixed size.');
  const factor = ({ b: 1, k: 1024, kb: 1024, m: 1024 ** 2, mb: 1024 ** 2, g: 1024 ** 3, gb: 1024 ** 3 })[(match[2] ?? 'b').toLowerCase()];
  return Math.round(Number(match[1]) * factor);
}

function validateLegacyCompose(config) {
  assert(config?.services && Object.keys(config.services).length === 1, 'Existing Embedify must have exactly one service before migration.');
  const service = validateHardenedService(config.services[APP], APP);
  imageDigest(service);
  assert(!(service.volumes?.length), 'Existing Embedify must not have host or persistent mounts before migration.');
  assert.deepEqual(Object.keys(environmentObject(service)).sort(), ['EMBEDIFY_TRUSTED_PROXIES'],
    'Existing Embedify must have only its already-known proxy environment setting.');
  assert.equal(service.container_name, APP);
  assert.equal(service.restart, 'unless-stopped');
  assert.equal(memoryBytes(service.mem_limit), 512 * 1024 * 1024, 'Existing memory limit must be preserved.');
  assert.equal(Number(service.cpus), 1, 'Existing CPU limit must be preserved.');
  assert.equal(Number(service.pids_limit), 128, 'Existing PID limit must be preserved.');
  assert.equal(service.stop_grace_period, '30s', 'Existing stop grace period must be preserved.');
  assert.deepEqual(service.tmpfs, [APP_TMPFS], 'Existing app temporary filesystem must be preserved.');
  assert.equal(service.ports?.length, 1, 'Existing Embedify must expose only its configured service port.');
  const [port] = service.ports;
  assert.equal(port.host_ip, '192.168.0.2', 'Embedify must bind only on the TrueNAS LAN address.');
  assert.equal(String(port.published), String(HOST_PORT), 'Embedify must use host port 30024.');
  assert.equal(Number(port.target), CONTAINER_PORT, 'Embedify must target container port 8080.');
  assert.equal(port.protocol ?? 'tcp', 'tcp', 'Embedify must expose only TCP.');
  assert.equal(memoryBytes(service.mem_limit), 512 * 1024 * 1024);
  assert.equal(Number(service.cpus), 1);
  assert.equal(Number(service.pids_limit), 128);
  assert.equal(service.stop_grace_period, '30s');
  assert.deepEqual(service.tmpfs, [APP_TMPFS]);
  assert.deepEqual(Object.keys(service.networks ?? {}).sort(), ['proxy']);
  assert.equal(config.networks?.proxy?.external, true, 'Existing NPM network must be external.');
  assert.equal(config.networks?.proxy?.name, PROXY_NETWORK, 'Existing Embedify must use the exact NPM network.');
  return config;
}

function validateCompose(config) {
  assert(config?.services, 'Compose must define Embedify services.');
  assert.deepEqual(Object.keys(config.services).sort(), [APP, COLLECTOR].sort(), 'Compose must define only Embedify and its private Collector.');
  const app = validateHardenedService(config.services[APP], APP);
  const collector = validateHardenedService(config.services[COLLECTOR], COLLECTOR);
  const image = imageDigest(app);
  assert.equal(imageDigest(collector), image, 'Embedify and Collector must use the same scanned image digest.');
  assert(!(app.volumes?.length), 'Embedify must not mount host or persistent volumes.');
  assert.equal(app.ports?.length, 1, 'Embedify must expose only its configured service port.');
  const [port] = app.ports;
  assert.equal(port.host_ip, '192.168.0.2', 'Embedify must bind only on the TrueNAS LAN address.');
  assert.equal(String(port.published), String(HOST_PORT), 'Embedify must use host port 30024.');
  assert.equal(Number(port.target), CONTAINER_PORT, 'Embedify must target container port 8080.');
  assert.equal(port.protocol ?? 'tcp', 'tcp', 'Embedify must expose only TCP.');
  assert(!(collector.ports?.length), 'Collector receiver ports must not be published on the host.');
  assert.equal(collector.entrypoint?.[0], '/opt/otelcol-contrib', 'Collector must use the scanned Collector binary.');
  assert.deepEqual(collector.command, ['--config=/etc/embedify-otel/collector.yaml']);
  assert.deepEqual(Object.keys(app.networks ?? {}).sort(), ['proxy', 'telemetry']);
  assert.deepEqual(Object.keys(collector.networks ?? {}).sort(), ['telemetry'], 'Collector must not join the NPM proxy network.');
  assert.equal(config.networks?.proxy?.external, true, 'NPM network must remain external.');
  assert.equal(config.networks?.proxy?.name, PROXY_NETWORK, 'Embedify must use the exact NPM network.');
  assert(!config.networks?.telemetry?.external && !config.networks?.telemetry?.internal,
    'Telemetry network must be private to the Compose project and allow Collector egress.');
  assert(!collector.depends_on && !app.depends_on, 'Collector availability must not gate app startup.');
  const mount = collector.volumes?.length === 1 ? collector.volumes[0] : null;
  assert.equal(mount?.type, 'bind', 'Collector must receive the key through one exact host file bind.');
  assert.equal(mount?.source, OPENWOOF_SECRET_PATH, 'Collector key bind source must use the protected app-specific path.');
  assert.equal(mount?.target, OPENWOOF_SECRET_CONTAINER_PATH);
  assert.equal(mount?.read_only, true, 'Collector key file must be mounted read-only.');
  const appEnv = app.environment ?? {};
  const collectorEnv = collector.environment ?? {};
  assert.equal(appEnv.OTEL_SERVICE_NAME, APP);
  assert.equal(appEnv.OTEL_EXPORTER_OTLP_ENDPOINT, `http://${COLLECTOR}:4318`);
  assert.equal(appEnv.OTEL_EXPORTER_OTLP_PROTOCOL, 'http/protobuf');
  assert.equal(appEnv.OTEL_TRACES_EXPORTER, 'otlp');
  assert.equal(appEnv.OTEL_METRICS_EXPORTER, 'otlp');
  assert.equal(appEnv.OTEL_LOGS_EXPORTER, 'otlp');
  assert(appEnv.OTEL_RESOURCE_ATTRIBUTES?.includes(`service.version=${image}`), 'Service version must identify the immutable image.');
  assert.equal(collectorEnv.OPENWOOF_INGEST_KEY_FILE, OPENWOOF_SECRET_CONTAINER_PATH);
  assert.equal(collectorEnv.EMBEDIFY_HEALTHCHECK_URL, 'http://127.0.0.1:13133/');
  assert.equal(memoryBytes(collector.mem_limit), 128 * 1024 * 1024);
  assert.equal(Number(collector.cpus), 0.5);
  assert.equal(Number(collector.pids_limit), 64);
  assert.equal(collector.stop_grace_period, '20s');
  assert.deepEqual(collector.tmpfs, ['/tmp:rw,noexec,nosuid,nodev,size=8m,uid=10001,gid=10001,mode=1777']);
  assert(!Object.keys(appEnv).some((key) => /OTEL_EXPORTER_OTLP_HEADERS|OPENWOOF_INGEST_KEY$/.test(key)),
    'OpenWoof credentials must not appear in the application environment.');
  return config;
}

function parseDotEnv(source) {
  const values = {};
  for (const line of source.split(/\r?\n/)) {
    const match = line.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
    if (!match) continue;
    let value = match[2].trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    } else {
      value = value.replace(/\s+#.*$/, '').trim();
    }
    values[match[1]] = value;
  }
  return values;
}

function interpolateComposeString(value, values) {
  return value.replace(/\$\$|\$\{([A-Za-z_][A-Za-z0-9_]*)(?:(:-|:\?)([^}]*))?\}/g,
    (whole, name, operator, fallback) => {
      if (whole === '$$') return '\u0000DOLLAR\u0000';
      const current = values[name];
      if (operator === ':?' && (current == null || current === '')) {
        throw new Error(`Required Compose setting ${name} is missing.`);
      }
      if (operator === ':-' && (current == null || current === '')) return fallback;
      return current ?? '';
    }).replaceAll('\u0000DOLLAR\u0000', '$');
}

function readCompose() {
  let dotenv = '';
  try { dotenv = readFileSync(resolve(ROOT, '.env'), 'utf8'); } catch (error) {
    if (error?.code !== 'ENOENT') throw new Error('Could not read the ignored Compose environment file.');
  }
  const values = { ...parseDotEnv(dotenv), ...process.env };
  const interpolate = (value) => interpolateComposeString(value, values);
  const visit = (value) => {
    if (typeof value === 'string') return interpolate(value);
    if (Array.isArray(value)) return value.map(visit);
    if (value && typeof value === 'object') {
      return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, visit(item)]));
    }
    return value;
  };
  let parsed;
  try { parsed = Bun.YAML.parse(readFileSync(resolve(ROOT, 'compose.yaml'), 'utf8')); } catch {
    throw new Error('Could not parse compose.yaml with the built-in YAML parser.');
  }
  return validateCompose(visit(parsed));
}

function controlTls() {
  return {
    ca: readFileSync(CONTROL_CA, 'utf8'),
    serverName: 'localhost',
    rejectUnauthorized: true,
  };
}

async function connectTrueNAS() {
  let apiKey;
  try {
    apiKey = execFileSync('/usr/bin/security', [
      'find-generic-password', '-a', 'joshuaterk', '-s', 'codex-truenas-mcp', '-w',
      resolve(homedir(), 'Library/Keychains/login.keychain-db'),
    ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch {
    throw new Error('The existing TrueNAS API credential is unavailable in the macOS Keychain.');
  }
  assert(apiKey, 'The existing TrueNAS API credential is empty.');

  const ws = new WebSocket(`wss://${TRUENAS}/api/current`, { tls: controlTls() });
  const pending = new Map();
  const notificationHandlers = new Set();
  let sequence = 0;
  ws.onmessage = (event) => {
    let message;
    try { message = JSON.parse(String(event.data)); } catch { return; }
    if (message.method === 'collection_update') {
      for (const handler of notificationHandlers) handler(message.params);
      return;
    }
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    clearTimeout(request.timer);
    if (message.error) {
      const error = new Error(`TrueNAS RPC ${request.method} failed (${message.error.code}).`);
      error.trueNASCode = message.error.code;
      error.trueNASData = message.error.data;
      request.reject(error);
    } else request.resolve(message.result);
  };
  await new Promise((resolveOpen, rejectOpen) => {
    ws.onopen = resolveOpen;
    ws.onerror = () => rejectOpen(new Error('TrueNAS WebSocket connection failed.'));
  });

  function rpc(method, params = []) {
    assert(RPC_METHODS.has(method), 'TrueNAS RPC method is outside this helper’s allowlist.');
    return new Promise((resolveResult, rejectResult) => {
      const id = ++sequence;
      const timer = setTimeout(() => {
        pending.delete(id);
        rejectResult(new Error(`TrueNAS RPC ${method} timed out.`));
      }, 30000);
      pending.set(id, { resolve: resolveResult, reject: rejectResult, timer, method });
      ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }));
    });
  }

  try {
    assert.equal(await rpc('auth.login_with_api_key', [apiKey]), true, 'TrueNAS authentication failed.');
  } catch (error) {
    ws.close();
    apiKey = '';
    throw error;
  }
  return {
    rpc,
    uploadCredential: () => apiKey,
    onNotification(handler) {
      notificationHandlers.add(handler);
      return () => notificationHandlers.delete(handler);
    },
    close() {
      apiKey = '';
      ws.close();
    },
  };
}

function isMissingPathError(error) {
  const fields = [error?.trueNASCode, error?.trueNASData?.errno, error?.trueNASData?.code,
    error?.trueNASData?.extra?.errno, error?.trueNASData?.reason];
  return fields.some((value) => value === 2 || value === '2' || /ENOENT|not found|no such file/i.test(String(value ?? '')));
}

async function statIfPresent(rpc, path) {
  try {
    return await rpc('filesystem.stat', [path]);
  } catch (error) {
    if (isMissingPathError(error)) return null;
    throw error;
  }
}

function permissions(stat) {
  return Number(stat.mode) & 0o777;
}

function assertSecretDirectory(stat, path) {
  assert(stat, `Required protected directory is missing: ${path}`);
  assert.equal(stat.realpath, path, 'Secret directory must not resolve through a symlink.');
  assert.equal(stat.type, 'DIRECTORY', 'Secret path must be a directory.');
  assert.equal(stat.uid, 10001, 'Secret directory owner must be UID 10001.');
  assert.equal(stat.gid, 10001, 'Secret directory group must be GID 10001.');
  assert.equal(permissions(stat), 0o700, 'Secret directory permissions must be exactly 0700.');
  assert.equal(stat.acl, false, 'Secret directory must not have an ACL that widens access.');
}

function summarizeSecretFile(stat) {
  if (!stat) return { exists: false, ready: false };
  return {
    exists: true,
    ready: stat.type === 'FILE' && stat.realpath === OPENWOOF_SECRET_PATH
      && stat.uid === 10001 && stat.gid === 10001 && permissions(stat) === 0o400
      && stat.acl === false && stat.size > 0 && stat.size <= 1024,
    type: stat.type,
    uid: stat.uid,
    gid: stat.gid,
    mode: `0${permissions(stat).toString(8)}`,
    acl: stat.acl,
    sizeBytes: stat.size,
    path: OPENWOOF_SECRET_PATH,
  };
}

async function inspectSecretPath(rpc) {
  const base = await rpc('filesystem.stat', ['/mnt/.ix-apps/app_mounts']);
  assert.equal(base.type, 'DIRECTORY', 'TrueNAS app_mounts path is not a directory.');
  assert.equal(base.realpath, '/mnt/.ix-apps/app_mounts', 'TrueNAS app_mounts path resolves unexpectedly.');
  const directory = await statIfPresent(rpc, OPENWOOF_SECRET_DIRECTORY);
  const file = directory?.type === 'DIRECTORY' ? await statIfPresent(rpc, OPENWOOF_SECRET_PATH) : null;
  return { base, directory, file };
}

async function waitChownJob(rpc, path) {
  const jobId = await rpc('filesystem.chown', [{
    path, uid: 10001, gid: 10001, options: { recursive: false, traverse: false },
  }]);
  assert(Number.isInteger(jobId), 'TrueNAS did not return a valid secret-permission job id.');
  await waitJob(rpc, jobId);
}

async function prepareSecretDirectory(rpc) {
  const paths = await inspectSecretPath(rpc);
  if (paths.directory) {
    assertSecretDirectory(paths.directory, OPENWOOF_SECRET_DIRECTORY);
  } else {
    await rpc('filesystem.mkdir', [{
      path: OPENWOOF_SECRET_DIRECTORY,
      options: { mode: '700', raise_chmod_error: true },
    }]);
    await waitChownJob(rpc, OPENWOOF_SECRET_DIRECTORY);
  }
  const directory = await rpc('filesystem.stat', [OPENWOOF_SECRET_DIRECTORY]);
  assertSecretDirectory(directory, OPENWOOF_SECRET_DIRECTORY);
  const file = await statIfPresent(rpc, OPENWOOF_SECRET_PATH);
  const summary = summarizeSecretFile(file);
  if (file) assert(summary.ready, 'Existing OpenWoof key file has unexpected ownership or permissions; preserving it for review.');
  return {
    directory: { path: OPENWOOF_SECRET_DIRECTORY, uid: directory.uid, gid: directory.gid, mode: `0${permissions(directory).toString(8)}`, acl: directory.acl },
    file: summary,
  };
}

async function readStdinSecret() {
  const chunks = [];
  let length = 0;
  try {
    for await (const chunk of Bun.stdin.stream()) {
      length += chunk.byteLength;
      assert(length <= 1024, 'Secret input exceeded the permitted size.');
      chunks.push(Buffer.from(chunk));
    }
    const value = Buffer.concat(chunks, length);
    if (value.length < 16 || !value.every((byte) => byte >= 0x21 && byte <= 0x7e)) {
      value.fill(0);
      throw new Error('Secret input was empty or contained unsupported bytes.');
    }
    return value;
  } finally {
    for (const chunk of chunks) chunk.fill(0);
  }
}

function postTrueNASUpload(apiKey, fileBytes, boundary, includeFile = true) {
  const data = JSON.stringify({ method: 'filesystem.put', params: [OPENWOOF_SECRET_PATH, { append: false, mode: 0o400 }] });
  const parts = [
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="data"\r\nContent-Type: application/json\r\n\r\n${data}\r\n`),
  ];
  if (includeFile) {
    parts.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="openwoof-ingest-key"\r\nContent-Type: application/octet-stream\r\n\r\n`));
    parts.push(fileBytes);
    parts.push(Buffer.from('\r\n'));
  }
  parts.push(Buffer.from(`--${boundary}--\r\n`));
  const body = Buffer.concat(parts);

  return new Promise((resolveResult, rejectResult) => {
    const request = https.request({
      hostname: TRUENAS.split(':')[0],
      port: Number(TRUENAS.split(':')[1]),
      path: '/_upload/',
      method: 'POST',
      servername: 'localhost',
      ca: readFileSync(CONTROL_CA),
      rejectUnauthorized: true,
      headers: {
        authorization: `Bearer ${apiKey}`,
        'content-type': `multipart/form-data; boundary=${boundary}`,
        'content-length': body.length,
      },
    }, (response) => {
      const responseChunks = [];
      let responseLength = 0;
      response.on('data', (chunk) => {
        responseLength += chunk.length;
        if (responseLength <= 8192) responseChunks.push(Buffer.from(chunk));
      });
      response.on('end', () => {
        body.fill(0);
        const responseBody = Buffer.concat(responseChunks, Math.min(responseLength, 8192));
        if (response.statusCode < 200 || response.statusCode >= 300) {
          responseBody.fill(0);
          rejectResult(new Error(`TrueNAS upload endpoint returned HTTP ${response.statusCode}.`));
          return;
        }
        try {
          resolveResult(JSON.parse(responseBody.toString('utf8')));
        } catch {
          rejectResult(new Error('TrueNAS upload endpoint returned an invalid response.'));
        } finally {
          responseBody.fill(0);
        }
      });
    });
    request.setTimeout(30000, () => request.destroy(new Error('TrueNAS upload timed out.')));
    request.on('error', () => {
      body.fill(0);
      rejectResult(new Error('TrueNAS upload request failed.'));
    });
    request.end(body);
  });
}

async function verifyUploadAuthentication(apiKey) {
  const boundary = `embedify-${randomUUID()}`;
  try {
    await postTrueNASUpload(apiKey, Buffer.alloc(0), boundary, false);
    throw new Error('TrueNAS upload preflight unexpectedly accepted an empty file request.');
  } catch (error) {
    if (/HTTP (401|403)\b/.test(error.message)) throw new Error('TrueNAS rejected the Keychain API credential for its upload endpoint.');
    if (!/HTTP (400|405|411|415|422)\b/.test(error.message)) throw error;
  }
  return { authenticated: true, endpoint: '/_upload/', tls: 'verified', request: 'empty multipart preflight; no file written' };
}

async function installOpenWoofKey(rpc, apiKey) {
  const key = await readStdinSecret();
  try {
    const paths = await inspectSecretPath(rpc);
    assertSecretDirectory(paths.directory, OPENWOOF_SECRET_DIRECTORY);
    assert(!paths.file, 'An OpenWoof key file already exists; refusing to replace it.');
    const response = await postTrueNASUpload(apiKey, key, `embedify-${randomUUID()}`);
    assert(Number.isInteger(response.job_id), 'TrueNAS upload did not return a valid job id.');
    await waitJob(rpc, response.job_id);
    await waitChownJob(rpc, OPENWOOF_SECRET_PATH);
    const stat = await rpc('filesystem.stat', [OPENWOOF_SECRET_PATH]);
    const status = summarizeSecretFile(stat);
    assert(status.ready, 'OpenWoof key file permissions or ownership do not meet the runtime policy.');
    console.log(JSON.stringify({ app: APP, secret: { ...status, path: OPENWOOF_SECRET_CONTAINER_PATH }, delivery: 'TLS verified, single-use TrueNAS token, read-only Collector mount' }));
  } finally {
    key.fill(0);
  }
}

async function appRows(rpc) {
  return rpc('app.query', [[['id', '=', APP]]]);
}

function summarizeProxyNetwork(network) {
  assert.equal(network?.name, PROXY_NETWORK, 'TrueNAS did not return the exact NPM proxy network.');
  const subnets = [];
  const visit = (value) => {
    if (Array.isArray(value)) {
      value.forEach(visit);
      return;
    }
    if (!value || typeof value !== 'object') return;
    for (const [key, child] of Object.entries(value)) {
      if (key.toLowerCase() === 'subnet' && typeof child === 'string') subnets.push(child);
      else visit(child);
    }
  };
  visit(network.ipam);
  const uniqueSubnets = [...new Set(subnets)].sort();
  assert(uniqueSubnets.length > 0, 'NPM proxy network did not provide an IPAM subnet.');
  return { name: PROXY_NETWORK, subnets: uniqueSubnets };
}

function summarizeApp(app) {
  if (!app) return { app: APP, found: false };
  return {
    app: APP,
    found: true,
    state: app.state,
    workloads: (app.active_workloads?.container_details ?? []).map((container) => ({
      service: container.service_name,
      image: container.image,
      state: container.state,
      health: container.health,
      ports: container.ports,
    })),
  };
}

function summarizeCompose(config) {
  if (!config) return null;
  const services = Object.entries(config.services ?? {}).map(([name, service]) => {
    const environment = service.environment ?? {};
    const environmentKeys = Array.isArray(environment)
      ? environment.map((entry) => String(entry).split('=', 1)[0]).sort()
      : Object.keys(environment).sort();
    const mounts = (service.volumes ?? []).map((mount) => {
      if (typeof mount === 'string') {
        const parts = mount.split(':');
        return {
          type: parts.length > 2 ? parts[2] : 'bind-or-volume',
          target: parts.length > 1 ? parts[1] : parts[0],
          readOnly: parts.at(-1) === 'ro',
          sourceKind: parts[0]?.startsWith('/') ? 'bind' : 'named-volume',
        };
      }
      return {
        type: mount.type ?? 'unknown',
        target: mount.target,
        readOnly: mount.read_only === true,
        sourceKind: mount.type === 'bind' ? 'bind' : 'named-volume',
      };
    });
    return {
      name,
      image: service.image,
      containerName: service.container_name,
      restart: service.restart,
      user: service.user,
      readOnly: service.read_only === true,
      capDrop: service.cap_drop ?? [],
      securityOptions: service.security_opt ?? [],
      privileged: service.privileged === true,
      networks: Object.keys(service.networks ?? {}).sort(),
      ports: (service.ports ?? []).map(({ host_ip, published, target, protocol }) => ({
        host_ip, published, target, protocol,
      })),
      environmentKeys,
      mounts,
      memLimit: service.mem_limit,
      cpuLimit: service.cpus,
      pidLimit: service.pids_limit,
      stopGracePeriod: service.stop_grace_period,
      tmpfs: service.tmpfs ?? [],
    };
  });
  return {
    services,
    networks: Object.keys(config.networks ?? {}).sort(),
    secrets: Object.keys(config.secrets ?? {}).sort(),
  };
}

function environmentObject(service) {
  if (Array.isArray(service.environment)) {
    return Object.fromEntries(service.environment.map((entry) => {
      const text = String(entry);
      const separator = text.indexOf('=');
      return separator < 0 ? [text, ''] : [text.slice(0, separator), text.slice(separator + 1)];
    }));
  }
  return { ...(service.environment ?? {}) };
}

function setEnvironmentValue(service, key, value) {
  if (Array.isArray(service.environment)) {
    const rows = service.environment.filter((entry) => !String(entry).startsWith(`${key}=`));
    rows.push(`${key}=${value}`);
    service.environment = rows;
  } else {
    service.environment ??= {};
    service.environment[key] = value;
  }
}

function redactLogEvidence(data) {
  return data
    .replace(/(https?:\/\/)(?:[^/@\s]+@)?([^/?#\s]+)(?:\/[^?#\s]*)?(?:\?[^#\s]*)?(?:#[^\s]*)?/gi, '$1$2/[redacted]')
    .replace(/\bBearer\s+[A-Za-z0-9._~+/-]+=*/gi, 'Bearer [redacted]')
    .replace(/\b(token|secret|api[_-]?key|ingest[_-]?key|password|authorization)\s*([=:])\s*[^\s,;]+/gi, '$1$2[redacted]');
}

async function captureApplicationLogs(rpc, onNotification, phase) {
  const [app] = await appRows(rpc);
  assert(app?.state === 'RUNNING', 'Embedify must be running before capturing its container logs.');
  const container = (app.active_workloads?.container_details ?? []).find(({ service_name }) => service_name === APP);
  assert(container?.id, 'TrueNAS did not provide the running Embedify container ID.');

  const event = `app.container_log_follow:${JSON.stringify({
    app_name: APP,
    container_id: container.id,
    tail_lines: 100,
  })}`;
  const output = [];
  let changed;
  let quietTimer;
  const changedPromise = new Promise((resolveChanged) => { changed = resolveChanged; });
  const removeHandler = onNotification((notification) => {
    if (!notification?.collection?.startsWith('app.container_log_follow:')) return;
    const { data, timestamp } = notification.fields ?? {};
    if (typeof data !== 'string') return;
    output.push({ timestamp: timestamp ?? null, data });
    clearTimeout(quietTimer);
    quietTimer = setTimeout(() => changed?.(), 1200);
  });
  const subscriptionId = await rpc('core.subscribe', [event]);
  try {
    await Promise.race([
      changedPromise,
      Bun.sleep(8000),
    ]);
  } finally {
    clearTimeout(quietTimer);
    removeHandler();
    await rpc('core.unsubscribe', [subscriptionId]);
  }

  const directory = resolve(ROOT, 'target/observability');
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const path = resolve(directory, `${phase}-logs.jsonl`);
  assert(!existsSync(path), `The ${phase} log evidence already exists; preserving it instead of overwriting.`);
  const safeOutput = output.map((record) => ({ ...record, data: redactLogEvidence(record.data) }));
  writeFileSync(path, safeOutput.map((record) => JSON.stringify(record)).join('\n') + (safeOutput.length ? '\n' : ''), { mode: 0o600, flag: 'wx' });
  return { path, container: container.id.slice(0, 12), records: output.length };
}

async function waitJob(rpc, jobId) {
  const deadline = Date.now() + 15 * 60 * 1000;
  let previous;
  while (Date.now() < deadline) {
    const [job] = await rpc('core.get_jobs', [[['id', '=', jobId]], { select: ['id', 'state'] }]);
    assert(job, 'TrueNAS job disappeared; inspect Embedify before retrying.');
    if (job.state !== previous) {
      console.log(JSON.stringify({ app: APP, jobId, state: job.state }));
      previous = job.state;
    }
    if (job.state === 'SUCCESS') return;
    if (['FAILED', 'ABORTED'].includes(job.state)) {
      throw new Error(`TrueNAS job ${jobId} ended in ${job.state}; inspect the app before retrying.`);
    }
    await Bun.sleep(4000);
  }
  throw new Error(`TrueNAS job ${jobId} is still running; inspect Embedify before retrying.`);
}

function selfTest() {
  const image = `${IMAGE_REPOSITORY}@sha256:${'a'.repeat(64)}`;
  const appService = {
    image,
    container_name: APP,
    restart: 'unless-stopped',
    read_only: true,
    user: '10001:10001',
    cap_drop: ['ALL'],
    security_opt: ['no-new-privileges:true'],
    ports: [{ host_ip: '192.168.0.2', published: String(HOST_PORT), target: CONTAINER_PORT, protocol: 'tcp' }],
    networks: { proxy: null, telemetry: null },
    mem_limit: '512m',
    cpus: 1,
    pids_limit: 128,
    stop_grace_period: '30s',
    tmpfs: [APP_TMPFS],
    environment: {
      OTEL_SERVICE_NAME: APP,
      OTEL_RESOURCE_ATTRIBUTES: `service.version=${image}`,
      OTEL_EXPORTER_OTLP_ENDPOINT: `http://${COLLECTOR}:4318`,
      OTEL_EXPORTER_OTLP_PROTOCOL: 'http/protobuf',
      OTEL_TRACES_EXPORTER: 'otlp',
      OTEL_METRICS_EXPORTER: 'otlp',
      OTEL_LOGS_EXPORTER: 'otlp',
    },
  };
  const collectorService = {
    image,
    container_name: 'embedify-otel-collector',
    restart: 'unless-stopped',
    read_only: true,
    user: '10001:10001',
    cap_drop: ['ALL'],
    security_opt: ['no-new-privileges:true'],
    entrypoint: ['/opt/otelcol-contrib'],
    command: ['--config=/etc/embedify-otel/collector.yaml'],
    networks: { telemetry: null },
    mem_limit: '128m',
    cpus: 0.5,
    pids_limit: 64,
    stop_grace_period: '20s',
    tmpfs: ['/tmp:rw,noexec,nosuid,nodev,size=8m,uid=10001,gid=10001,mode=1777'],
    environment: {
      OPENWOOF_INGEST_KEY_FILE: OPENWOOF_SECRET_CONTAINER_PATH,
      EMBEDIFY_HEALTHCHECK_URL: 'http://127.0.0.1:13133/',
    },
    volumes: [{ type: 'bind', source: OPENWOOF_SECRET_PATH, target: OPENWOOF_SECRET_CONTAINER_PATH, read_only: true }],
  };
  const valid = {
    services: { [APP]: appService, [COLLECTOR]: collectorService },
    networks: { proxy: { external: true, name: PROXY_NETWORK }, telemetry: {} },
  };
  assert.equal(validateCompose(valid), valid);
  const legacy = {
    services: {
      [APP]: {
        ...structuredClone(appService),
        networks: { proxy: null },
        environment: { EMBEDIFY_TRUSTED_PROXIES: '192.168.9.0/24' },
      },
    },
    networks: { proxy: { external: true, name: PROXY_NETWORK } },
  };
  assert.equal(validateLegacyCompose(legacy), legacy);
  const unexpectedLegacyMount = structuredClone(legacy);
  unexpectedLegacyMount.services[APP].volumes = ['/host/path:/data'];
  assert.throws(() => validateLegacyCompose(unexpectedLegacyMount));
  const unexpectedLegacyEnvironment = structuredClone(legacy);
  unexpectedLegacyEnvironment.services[APP].environment.OPENWOOF_INGEST_KEY = 'must-not-be-present';
  assert.throws(() => validateLegacyCompose(unexpectedLegacyEnvironment));
  const wrongImage = structuredClone(valid);
  wrongImage.services[APP].image = `${IMAGE_REPOSITORY}:sha-${'a'.repeat(40)}`;
  assert.throws(() => validateCompose(wrongImage));
  const wrongBind = structuredClone(valid);
  wrongBind.services[APP].ports[0].host_ip = '0.0.0.0';
  assert.throws(() => validateCompose(wrongBind));
  const extraService = structuredClone(valid);
  extraService.services.other = { image: 'busybox@sha256:' + 'b'.repeat(64) };
  assert.throws(() => validateCompose(extraService));
  const hostMount = structuredClone(valid);
  hostMount.services[APP].volumes = ['/var/run/docker.sock:/var/run/docker.sock'];
  assert.throws(() => validateCompose(hostMount));
  const publicCollector = structuredClone(valid);
  publicCollector.services[COLLECTOR].networks.proxy = null;
  assert.throws(() => validateCompose(publicCollector));
  const publishedReceiver = structuredClone(valid);
  publishedReceiver.services[COLLECTOR].ports = [{ target: 4318, published: '4318' }];
  assert.throws(() => validateCompose(publishedReceiver));
  const writableKey = structuredClone(valid);
  writableKey.services[COLLECTOR].volumes[0].read_only = false;
  assert.throws(() => validateCompose(writableKey));
  const leakedHeaders = structuredClone(valid);
  leakedHeaders.services[APP].environment.OTEL_EXPORTER_OTLP_HEADERS = 'Bearer secret';
  assert.throws(() => validateCompose(leakedHeaders));
  assert.deepEqual(summarizeProxyNetwork({
    name: PROXY_NETWORK,
    ipam: { config: [{ Subnet: '192.168.9.0/24', Gateway: '192.168.9.1' }] },
  }), { name: PROXY_NETWORK, subnets: ['192.168.9.0/24'] });
  assert.throws(() => summarizeProxyNetwork({ name: 'unexpected-network', ipam: { config: [] } }));
  const summary = summarizeCompose({
    services: { embedify: { image: 'private@sha256:abc', environment: { OTEL_HEADERS: 'secret-value' },
      volumes: [{ type: 'bind', source: '/private/key', target: '/run/key', read_only: true }] } },
  });
  assert.deepEqual(summary.services[0].environmentKeys, ['OTEL_HEADERS']);
  assert.deepEqual(summary.services[0].mounts, [{ type: 'bind', target: '/run/key', readOnly: true, sourceKind: 'bind' }]);
  assert.equal(JSON.stringify(summary).includes('secret-value'), false);
  assert.equal(JSON.stringify(summary).includes('/private/key'), false);
  assert.deepEqual(parseDotEnv("A='quoted value'\nB=value # comment\n# C=ignored"), {
    A: 'quoted value', B: 'value',
  });
  assert.equal(interpolateComposeString('${A:-fallback}', { A: '' }), 'fallback');
  assert.equal(interpolateComposeString('${A:?required}', { A: 'present' }), 'present');
  assert.throws(() => interpolateComposeString('${A:?required}', { A: '' }));
  assert.equal(interpolateComposeString('$$A ${A}', { A: 'present' }), '$A present');
  assert.equal(redactLogEvidence('fetch https://user:pass@feed.example/private/token?key=value status=503'),
    'fetch https://feed.example/[redacted] status=503');
  assert.equal(redactLogEvidence('authorization:Bearer abc.def.ghi'), 'authorization:[redacted] [redacted]');
  const logNotification = { collection: 'app.container_log_follow:{}', fields: { data: 'startup', timestamp: '2026-10-21T00:00:00Z' } };
  assert.equal(logNotification.fields.data, 'startup');
  console.log('Embedify TrueNAS helper self-test passed.');
}

function connectProxyNetwork(config) {
  validateCompose(config);
  return config;
}

async function main() {
  const mode = process.argv[2];
  assert(['inspect', 'compose-summary', 'capture-baseline-logs', 'capture-after-logs', 'create', 'update-image', 'update-observability', 'use-proxy-network', 'proxy-network', 'secret-status', 'prepare-openwoof-secret', 'verify-upload-auth', 'install-openwoof-key', 'self-test'].includes(mode), 'Usage: truenas.mjs inspect|compose-summary|capture-baseline-logs|capture-after-logs|create|update-image|update-observability|proxy-network|secret-status|prepare-openwoof-secret|verify-upload-auth|install-openwoof-key|self-test');
  if (mode === 'self-test') return selfTest();
  if (mode === 'compose-summary') {
    console.log(JSON.stringify(summarizeCompose(readCompose()), null, 2));
    return;
  }
  const config = ['create', 'update-image', 'update-observability'].includes(mode) ? readCompose() : undefined;
  const { rpc, uploadCredential, onNotification, close } = await connectTrueNAS();
  try {
    if (mode === 'secret-status') {
      const paths = await inspectSecretPath(rpc);
      const directory = paths.directory
        ? { exists: true, path: OPENWOOF_SECRET_DIRECTORY, type: paths.directory.type, uid: paths.directory.uid, gid: paths.directory.gid, mode: `0${permissions(paths.directory).toString(8)}`, acl: paths.directory.acl }
        : { exists: false, path: OPENWOOF_SECRET_DIRECTORY };
      console.log(JSON.stringify({ app: APP, directory, file: summarizeSecretFile(paths.file) }));
      return;
    }
    if (mode === 'prepare-openwoof-secret') {
      assert.equal(process.argv[3], '--confirm', 'Preparing the app secret directory requires exact confirmation.');
      assert.equal(process.argv[4], APP, 'Exact TrueNAS app confirmation required.');
      const rows = await appRows(rpc);
      assert.equal(rows.length, 1, 'Embedify must exist before preparing its secret directory.');
      assert(rows[0].state === 'RUNNING', 'Embedify must be running before preparing its secret directory.');
      validateLegacyCompose(await rpc('app.config', [APP]));
      console.log(JSON.stringify({ app: APP, secretDirectory: await prepareSecretDirectory(rpc) }));
      return;
    }
    if (mode === 'verify-upload-auth') {
      console.log(JSON.stringify({ app: APP, uploadPreflight: await verifyUploadAuthentication(uploadCredential()) }));
      return;
    }
    if (mode === 'install-openwoof-key') {
      assert.equal(process.argv[3], '--confirm', 'Key installation requires exact confirmation.');
      assert.equal(process.argv[4], APP, 'Exact TrueNAS app confirmation required.');
      await installOpenWoofKey(rpc, uploadCredential());
      return;
    }
    if (mode === 'proxy-network') {
      const networks = await rpc('docker.network.query', [
        [['name', '=', PROXY_NETWORK]],
        { select: ['name', 'ipam'] },
      ]);
      assert(Array.isArray(networks) && networks.length === 1, 'TrueNAS did not return exactly one NPM proxy network.');
      console.log(JSON.stringify(summarizeProxyNetwork(networks[0]), null, 2));
      return;
    }
    if (mode === 'capture-baseline-logs' || mode === 'capture-after-logs') {
      const phase = mode === 'capture-baseline-logs' ? 'baseline' : 'after';
      const result = await captureApplicationLogs(rpc, onNotification, phase);
      console.log(JSON.stringify({ app: APP, [phase]: result, evidenceRedaction: 'URL credentials, paths, queries, fragments, and common token assignments' }));
      return;
    }
    const rows = await appRows(rpc);
    assert(rows.length <= 1, 'TrueNAS returned multiple apps named Embedify.');
    if (mode === 'inspect') {
      const app = rows[0];
      const configuration = app ? summarizeCompose(await rpc('app.config', [APP])) : null;
      console.log(JSON.stringify({ ...summarizeApp(app), configuration }, null, 2));
      return;
    }
    assert.equal(process.argv[3], '--confirm', 'Changing the app requires an exact confirmation.');
    assert.equal(process.argv[4], APP, 'Exact TrueNAS app confirmation required.');
    if (mode === 'use-proxy-network' || mode === 'update-image' || mode === 'update-observability') {
      assert.equal(rows.length, 1, 'Embedify must already exist.');
      const current = await rpc('app.config', [APP]);
      let updated;
      if (mode === 'use-proxy-network') {
        updated = connectProxyNetwork(current);
      } else if (mode === 'update-image') {
        updated = validateCompose(current);
        for (const service of Object.values(updated.services)) service.image = config.services[APP].image;
        setEnvironmentValue(updated.services[APP], 'OTEL_RESOURCE_ATTRIBUTES', config.services[APP].environment.OTEL_RESOURCE_ATTRIBUTES);
      } else {
        validateLegacyCompose(current);
        const legacyEnvironment = environmentObject(current.services[APP]);
        assert.deepEqual(Object.keys(legacyEnvironment).sort(), ['EMBEDIFY_TRUSTED_PROXIES'],
          'Migration must preserve all existing app environment settings.');
        const currentTrusted = legacyEnvironment.EMBEDIFY_TRUSTED_PROXIES ?? '';
        const candidateTrusted = config.services[APP].environment?.EMBEDIFY_TRUSTED_PROXIES ?? '';
        assert.equal(candidateTrusted, currentTrusted, 'Migration must preserve the existing trusted proxy setting.');
        updated = config;
      }
      const jobId = await rpc('app.update', [APP, { custom_compose_config: updated }]);
      assert(Number.isInteger(jobId), 'TrueNAS did not return a valid update job id.');
      await waitJob(rpc, jobId);
      const saved = await rpc('app.config', [APP]);
      assert.deepEqual(saved, updated, 'Saved Embedify configuration differs from the requested scoped update.');
      console.log(mode === 'update-observability'
        ? 'Saved two-service observability configuration verified for Embedify.'
        : 'Saved configuration verified; only the requested scoped change was applied.');
      console.log(JSON.stringify(summarizeApp((await appRows(rpc))[0]), null, 2));
      return;
    }
    assert.equal(rows.length, 0, 'Embedify already exists; this helper never updates or replaces it.');
    assert.equal((await rpc('docker.status')).status, 'RUNNING', 'TrueNAS Docker service must be running.');
    const usedPorts = await rpc('app.used_ports');
    assert(Array.isArray(usedPorts) && !usedPorts.includes(HOST_PORT),
      `TrueNAS port ${HOST_PORT} is already allocated or could not be verified.`);
    const jobId = await rpc('app.create', [{ app_name: APP, custom_app: true, custom_compose_config: config }]);
    assert(Number.isInteger(jobId), 'TrueNAS did not return a valid create job id.');
    await waitJob(rpc, jobId);
    const [created] = await appRows(rpc);
    console.log(JSON.stringify(summarizeApp(created), null, 2));
  } finally {
    close();
  }
}

if (import.meta.main) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : 'Embedify helper failed.');
    process.exitCode = 1;
  });
}
