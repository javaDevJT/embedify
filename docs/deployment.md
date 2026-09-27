# Deployment

Embedify is a single stateless Spring Boot process. It listens on container port 8080, has no database or persistent application volume, and fetches only public HTTPS calendar feeds. The container runs as UID/GID 10001 with a jlink runtime, a read-only root filesystem, dropped Linux capabilities, no privilege escalation, a bounded `/tmp`, and a JVM HTTP healthcheck against `/healthz`.

## Build and publish

`.github/workflows/ci.yml` runs Maven verification and the UI checks for pull requests. A push to `main` or a manual `workflow_dispatch` runs the same checks and then publishes `ghcr.io/javadevjt/embedify:sha-<full-commit-sha>` as a private GHCR package. The publisher has only `contents: read` and `packages: write`. Buildx attaches maximum-detail provenance and an SBOM to the pushed image; the job summary records the resulting registry digest. The workflow pins its Actions to full commit SHAs, and Dependabot checks Actions and Docker base-image pins weekly.

BuildKit provenance and SBOM attestations travel with the private registry image. No attestation is published to a public transparency service. To inspect the pushed digest and attestations, use `docker buildx imagetools inspect ghcr.io/javadevjt/embedify:sha-<full-commit-sha>` from a machine authenticated to GHCR.

## TrueNAS and Nginx Proxy Manager

Read-only discovery on September 27, 2026 found the TrueNAS `nginx-proxy-manager` app running (catalog version 1.3.9, NPM API version 2.15.1). Its host ports are 30020 for the management UI, 30021 for HTTP proxy traffic, 30022 for HTTPS proxy traffic, and 30023 mapped to container port 4443. The read-only `app.used_ports` result did not include 30024, so this deployment reserves `192.168.0.2:30024` for Embedify, forwarding to container port 8080. Recheck that port immediately before creating the app.

The existing TrueNAS `ghcr` registry credential record is available for the private image pull. Reuse the registry record through the TrueNAS app configuration; do not copy its username or token into Compose, `.env`, this repository, logs, or deployment output. If the app creation form requires explicit registry selection, select the existing `ghcr` entry.

The NPM management API requires authentication. The owner supplied a signed-in browser session; inspection confirmed the existing wildcard certificate covers `embedify.javadevjt.tech`. Configure only the new Embedify host and preserve other proxy hosts and certificates.

Configure the new proxy host to forward HTTPS traffic to `http://192.168.0.2:30024`, select the already-issued certificate for the chosen hostname, and enable Websockets only if the app later needs them. Keep the host public because the builder and iframe are designed for public calendar embeds. Embedify accepts public feed URLs only; never put private calendar feeds on a public page.

## Deploy a published image

1. Copy `.env.example` to `.env` and replace the zero-filled SHA-256 digest with the registry digest from a successful main-branch or manual publish run.
2. Confirm the `ghcr` registry credential is selected for this app in TrueNAS and that `192.168.0.2:30024` remains unused.
3. Create the `embedify` custom app from `compose.yaml`, using the selected private image and the existing GHCR registry credential.
4. Verify the container runs as UID 10001, `/healthz` is healthy, the bind is only on `192.168.0.2:30024`, the root filesystem is read-only, and the only writable mount is the bounded `/tmp` tmpfs.
5. Add the one NPM proxy host after confirming the chosen hostname and certificate. Smoke-test the HTTPS builder and iframe using a public calendar feed; do not call the deployment complete from a container health result alone.

Use the immutable tag and the digest reported by Actions when recording the deployed version. A successful local build does not prove the GHCR package, TrueNAS app, NPM proxy, public DNS, or served HTTPS page.

### Scoped deployment helper

Run `bun scripts/truenas.mjs self-test` to check its guards, `bun scripts/truenas.mjs inspect` to inspect only Embedify, and `bun scripts/truenas.mjs proxy-network` to read only the existing NPM network. The helper reads the existing TrueNAS credential from macOS Keychain without printing it and pins the control-plane TLS certificate. With the digest set in `.env`, `bun scripts/truenas.mjs create --confirm embedify` creates only this app, refuses an existing app or occupied port, and verifies non-root/read-only settings. It cannot replace or remove any app.

On September 27, 2026, the exact-name `docker.network.query` check found `ix-nginx-proxy-manager_default` uses `172.16.2.0/24` and `fdd0:0:0:2::/64`. Set `EMBEDIFY_TRUSTED_PROXIES` to only those verified NPM subnets. Recheck after changing NPM's Docker networking. Embedify otherwise ignores forwarded client-IP headers and rate-limits by its direct peer.

The owner has signed in to NPM. The new `embedify.javadevjt.tech` proxy is staged with upstream `192.168.0.2:30024`, the existing wildcard certificate, Force SSL, HTTP/2, and [the scoped advanced configuration](nginx-proxy-manager.conf). Save it only after the app is healthy. The config trusts Cloudflare IP headers only from published Cloudflare ranges and disables request logging for this host.
