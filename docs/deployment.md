# Deployment

Embedify is live at https://embedify.javadevjt.tech. The repository `javaDevJT/embedify` and GHCR package remain **private** until the owner explicitly approves publication.

## Verified release — September 27, 2026

- Application source: `24ab6eb99bcb1c782a86afa3a03be8601e125713`.
- Successful GitHub Actions run: `36334056651`; all 27 backend tests and the frontend checks passed.
- Image: `ghcr.io/javadevjt/embedify@sha256:2a532784098b55208438fef9cfe05eb9336630e226a11cddd845cac58d7f2381`. The registry digest matches the successful CI publication log.
- TrueNAS app `embedify` is RUNNING using that digest. Saved runtime configuration specifies UID/GID 10001, a read-only root, all capabilities dropped, no privilege escalation, 512 MiB memory, one CPU, 128 PIDs, and a 16 MiB `/tmp` tmpfs. There are no persistent volumes.
- The actual jlink image passed a local container health check under those restrictions and fetched a real HTTPS feed. The TrueNAS health endpoint and feed API also passed.
- The public builder, embed HTML, JavaScript, and CSS match the source bytes. NPM's `no-transform` response header prevents Cloudflare from injecting its analytics script. The live browser has no console errors.
- Browser checks covered the real CalendarLabs feed, month and agenda views, next-month navigation, mobile width, copy URL, CSS suggestions, public-page style suggestions, and the public embed inside an iframe on another origin.
- The initial CI attempt exposed a YAML quoting error; commit `24ab6eb` corrected it. The successful run above is the release evidence. Later deployment/documentation changes do not change the image inputs.

## Runtime and proxy

`compose.yaml` targets TrueNAS at `192.168.0.2`. Port `30024` remains bound only to that LAN address for direct health checks; the container listens on 8080.

NPM host #24 serves `embedify.javadevjt.tech` and forwards directly to `http://embedify:8080` through the existing external Docker network `ix-nginx-proxy-manager_default`. It uses the existing wildcard certificate, Force SSL, and HTTP/2. Other proxy hosts and certificates were preserved.

The exact-name TrueNAS network query verified proxy subnets `172.16.2.0/24` and `fdd0:0:0:2::/64`. Only those subnets are configured in `EMBEDIFY_TRUSTED_PROXIES`. Direct container routing keeps NPM within that trust boundary without depending on host-port NAT. Recheck these ranges after changing Docker networking.

[NPM advanced configuration](nginx-proxy-manager.conf) restores visitor addresses only from published Cloudflare ranges, disables access logging for this hostname, bounds request sizes/timeouts, and preserves application HTML. The builder denies external framing; only `/embed` permits it.

## Build and publish

Pushes to `main` and manual runs execute Maven verification and UI checks before publishing the SHA-tagged private GHCR image. Actions and Docker bases are pinned. Buildx attaches provenance and an SBOM; the job summary records the immutable digest. Publication does not deploy automatically.

Use the digest from a successful run, never a mutable tag, in the ignored local `.env`. The existing TrueNAS GHCR credential is used without copying credentials into Compose, the repository, or logs. The service has no persistent state to migrate; a restart clears its bounded caches.

## Scoped TrueNAS helper

The helper uses Bun, the existing macOS Keychain credential `codex-truenas-mcp`, and the pinned control-plane certificate. It never prints the credential.

- `bun scripts/truenas.mjs self-test` checks its guards.
- `bun scripts/truenas.mjs inspect` reads only Embedify.
- `bun scripts/truenas.mjs proxy-network` reads only the NPM network and prints its subnets.
- `bun scripts/truenas.mjs create --confirm embedify` creates only this app from the reviewed Compose configuration. It refuses an existing app or occupied port.
- `bun scripts/truenas.mjs use-proxy-network --confirm embedify` is the narrowly scoped migration used for the initial deployment. It preserves the existing app configuration and image while connecting only Embedify to NPM's network. It is not a general update or removal command.

For a later image release, use TrueNAS's existing-app configuration to replace only the image digest while preserving the proxy network, environment, port, and container restrictions. Recheck the running digest, health, served assets, and public iframe flow after any update. Revalidate the pinned control-plane certificate if TrueNAS renews it.

## Cache and operating limits

Feed pulls share a bounded 60-second cache and coalesce concurrent misses. Parsed results share a separate bounded 8 MiB cache with no extra freshness TTL; two uncached parses can run concurrently. Per-client request quotas, global outbound concurrency, response-size limits, and recurrence bounds limit abuse. Both caches are in memory; no calendar database exists.

Style suggestions extract literal CSS colors and a local font category. They do not execute CSS or page scripts. File uploads, credentials, private-network URLs, and non-HTTPS feed requests are rejected. Public embeds expose their feed URLs, so never use secret calendar links.

Cloudflare may reject generic automation user agents; ordinary browser access and the public user flows above were verified. This does not require weakening the app's content security policy.
