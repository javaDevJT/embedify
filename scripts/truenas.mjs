#!/usr/bin/env bun

// Embedify-only TrueNAS helper. Scoped updates change its image or connect it to
// NPM; it cannot delete the app or target any other app.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve } from 'node:path';

const ROOT = resolve(import.meta.dir, '..');
const APP = 'embedify';
const TRUENAS = '192.168.0.2:8443';
const HOST_PORT = 30024;
const CONTAINER_PORT = 8080;
const IMAGE_REPOSITORY = 'ghcr.io/javadevjt/embedify';
const PROXY_NETWORK = 'ix-nginx-proxy-manager_default';
const CONTROL_CA = resolve(import.meta.dir, 'truenas-control-plane.pem');
const RPC_METHODS = new Set([
  'auth.login_with_api_key', 'app.query', 'app.used_ports', 'docker.status', 'docker.network.query', 'app.create', 'app.config', 'app.update', 'core.get_jobs',
]);

function validateCompose(config) {
  assert(config?.services && Object.keys(config.services).length === 1, 'Compose must contain only the Embedify service.');
  const service = config.services[APP];
  assert(service, 'Compose must define the embedify service.');
  const imagePrefix = `${IMAGE_REPOSITORY}@sha256:`;
  assert(service.image?.startsWith(imagePrefix) && /^[a-f0-9]{64}$/.test(service.image.slice(imagePrefix.length)),
    'Embedify must use its private GHCR image pinned by digest.');
  assert.equal(service.read_only, true, 'Embedify must keep its root filesystem read-only.');
  assert.equal(service.user, '10001:10001', 'Embedify must run as UID 10001.');
  assert(service.cap_drop?.includes('ALL'), 'Embedify must drop all capabilities.');
  assert(service.security_opt?.includes('no-new-privileges:true'), 'Embedify must disable privilege escalation.');
  assert(!service.privileged && !service.network_mode && !service.pid && !service.devices,
    'Compose must not request host access.');
  assert(!(service.volumes?.length), 'Embedify must not mount host or persistent volumes.');
  assert.equal(service.ports?.length, 1, 'Embedify must expose only its configured service port.');
  const [port] = service.ports;
  assert.equal(port.host_ip, '192.168.0.2', 'Embedify must bind only on the TrueNAS LAN address.');
  assert.equal(String(port.published), String(HOST_PORT), 'Embedify must use host port 30024.');
  assert.equal(Number(port.target), CONTAINER_PORT, 'Embedify must target container port 8080.');
  assert.equal(port.protocol ?? 'tcp', 'tcp', 'Embedify must expose only TCP.');
  return config;
}

function readCompose() {
  let output;
  try {
    output = execFileSync('docker', ['compose', '-f', resolve(ROOT, 'compose.yaml'), 'config', '--format', 'json'], {
      cwd: ROOT,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    });
  } catch {
    throw new Error('Could not resolve compose.yaml. Configure EMBEDIFY_IMAGE with the published GHCR digest first.');
  }
  let config;
  try { config = JSON.parse(output); } catch { throw new Error('Docker Compose returned invalid configuration JSON.'); }
  return validateCompose(config);
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
  let sequence = 0;
  ws.onmessage = (event) => {
    let message;
    try { message = JSON.parse(String(event.data)); } catch { return; }
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    clearTimeout(request.timer);
    if (message.error) request.reject(new Error(`TrueNAS RPC ${request.method} failed (${message.error.code}).`));
    else request.resolve(message.result);
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
    throw error;
  } finally {
    apiKey = '';
  }
  return { rpc, close: () => ws.close() };
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
  const valid = {
    services: {
      [APP]: {
        image: `${IMAGE_REPOSITORY}@sha256:${'a'.repeat(64)}`,
        read_only: true,
        user: '10001:10001',
        cap_drop: ['ALL'],
        security_opt: ['no-new-privileges:true'],
        ports: [{ host_ip: '192.168.0.2', published: String(HOST_PORT), target: CONTAINER_PORT, protocol: 'tcp' }],
      },
    },
  };
  assert.equal(validateCompose(valid), valid);
  const connected = connectProxyNetwork(structuredClone(valid));
  assert.equal(connected.services[APP].image, valid.services[APP].image);
  assert.deepEqual(connected.services[APP].networks, { proxy: null });
  assert.deepEqual(connected.networks, { proxy: { name: PROXY_NETWORK, external: true } });
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
  assert.deepEqual(summarizeProxyNetwork({
    name: PROXY_NETWORK,
    ipam: { config: [{ Subnet: '192.168.9.0/24', Gateway: '192.168.9.1' }] },
  }), { name: PROXY_NETWORK, subnets: ['192.168.9.0/24'] });
  assert.throws(() => summarizeProxyNetwork({ name: 'unexpected-network', ipam: { config: [] } }));
  console.log('Embedify TrueNAS helper self-test passed.');
}

function connectProxyNetwork(config) {
  validateCompose(config);
  assert(!config.networks || Object.keys(config.networks).every(name => ['default', 'proxy'].includes(name)),
    'Refusing to replace an unexpected application network.');
  config.services[APP].networks = { proxy: null };
  config.networks = { proxy: { name: PROXY_NETWORK, external: true } };
  return config;
}

async function main() {
  const mode = process.argv[2];
  assert(['inspect', 'create', 'update-image', 'use-proxy-network', 'proxy-network', 'self-test'].includes(mode), 'Usage: truenas.mjs inspect|create|update-image|use-proxy-network|proxy-network|self-test');
  if (mode === 'self-test') return selfTest();
  const config = ['create', 'update-image'].includes(mode) ? readCompose() : undefined;
  const { rpc, close } = await connectTrueNAS();
  try {
    if (mode === 'proxy-network') {
      const networks = await rpc('docker.network.query', [
        [['name', '=', PROXY_NETWORK]],
        { select: ['name', 'ipam'] },
      ]);
      assert(Array.isArray(networks) && networks.length === 1, 'TrueNAS did not return exactly one NPM proxy network.');
      console.log(JSON.stringify(summarizeProxyNetwork(networks[0]), null, 2));
      return;
    }
    const rows = await appRows(rpc);
    assert(rows.length <= 1, 'TrueNAS returned multiple apps named Embedify.');
    if (mode === 'inspect') {
      console.log(JSON.stringify(summarizeApp(rows[0]), null, 2));
      return;
    }
    assert.equal(process.argv[3], '--confirm', 'Changing the app requires an exact confirmation.');
    assert.equal(process.argv[4], APP, 'Exact TrueNAS app confirmation required.');
    if (mode === 'use-proxy-network' || mode === 'update-image') {
      assert.equal(rows.length, 1, 'Embedify must already exist.');
      const current = await rpc('app.config', [APP]);
      const updated = mode === 'use-proxy-network' ? connectProxyNetwork(current) : validateCompose(current);
      if (mode === 'update-image') updated.services[APP].image = config.services[APP].image;
      const jobId = await rpc('app.update', [APP, { custom_compose_config: updated }]);
      assert(Number.isInteger(jobId), 'TrueNAS did not return a valid update job id.');
      await waitJob(rpc, jobId);
      const saved = await rpc('app.config', [APP]);
      assert.deepEqual(saved, updated, 'Saved Embedify configuration differs from the requested scoped update.');
      console.log('Saved configuration verified; only the requested scoped change was applied.');
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
