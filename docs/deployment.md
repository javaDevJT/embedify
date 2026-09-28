# Deployment

Embedify is live at https://embedify.javadevjt.tech. The repository `javaDevJT/embedify` and GHCR package remain **private** until the owner explicitly approves publication.

## Webcal and folded-feed fixes — September 28, 2026

- Application source: `a44061f294348f9803460df22307d57fc72dadd7`, including the earlier frontend fix `0781d16484110e202a707d70a7879f0aa1a798f8`. Successful GitHub Actions run `36455139040` passed 34 backend tests and the builder/embed URL checks, then published the private image.
- Image: `ghcr.io/javadevjt/embedify@sha256:53c5980bbe05e8d36ca3023d7eed02406debe696026f102d0f169f325bf00cdc`. The registry version carries the application commit's SHA tag. TrueNAS update job `211657` succeeded, verified that only the image changed, and the app subsequently reported RUNNING on this digest.
- The backend already normalized `webcal://` to HTTPS, but frontend validation rejected it before fetching. Feed inputs and embed query parameters now share calendar-specific normalization. Event links and stylesheet URLs retain their strict HTTPS validation.
- Regression checks cover mixed-case schemes, generated HTTPS URLs, credentials, non-443 ports, malformed URLs, and direct embed parameters. The deployed public builder loaded the CalendarLabs sample through a `webcal://` input, displayed Labor Day, confirmed input validity, and generated an HTTPS embed URL. The public embed also rendered that sample from a direct `webcal://` query parameter, without a calendar error, retaining both attribution and coffee links.
- At the owner's explicit request, the exact private PagerDuty feed was subsequently tested without publishing an embed or storing its URL/content in fixtures. Its HTTPS endpoint returned HTTP 200, `text/calendar`, 9,971 bytes, and 32 events. The old local API returned HTTP 502 because LF-only folded lines caused ical4j to report excessive component nesting. Changing the scheme alone does not fix this feed.
- The parser now supplies an `UnfoldingReader` that recognizes LF as well as CRLF folding. A synthetic regression covers 16 sibling events with both newline formats. Size, component, recurrence, concurrency, and URL safety limits are unchanged. Local Maven attempts timed out; isolated compilation and the full-service diagnostic succeeded, and CI supplied the full passing test suite.
- The exact authorized feed passed through the fixed local service and deployed LAN API with both `webcal://` and HTTPS. The live API returned HTTP 200 and nine events for September 1 through November 1, 2026 (exclusive), in `America/Detroit`, with no truncation and a 60-second refresh interval. Only counts/status were printed; no private feed URL or event contents were stored in the repository or used in the public browser.
- LAN and public health endpoints returned HTTP 200. Public builder HTML, embed HTML, `config.js`, `app.js`, and `app.css` matched source bytes. Repository and GHCR package visibility remain private. Transient TrueNAS CI worker settings and the existing runtime restrictions were preserved. Temporary diagnostic files and the old loopback test server were removed.

## Hosted-use license release — September 27, 2026

- Application source: `e4bd24e3e1e9fd89dc560347bbbe62b1c4fe60d3`; successful GitHub Actions run `36347867057` passed 33 backend tests and UI checks and published the private image.
- Image: `ghcr.io/javadevjt/embedify@sha256:2b940ebd8472015475cdffb929ec4613382d7bc4be5bf40b2ad7495a9f9dacb0`.
- TrueNAS update job `209032` succeeded. The helper verified the entire saved configuration against the requested image-only change; the app subsequently reported RUNNING on the new digest.
- The canonical root `LICENSE` is packaged into the application and served at `/license`; the builder footer links to it. Hosted-site use and calendar embeds are allowed, including commercial use. Separate copying, modification, redistribution, and self-hosting require permission, subject to the license's stated exceptions. Both existing calendar-footer links remain intact.
- Public `/license` returned HTTP 200 with `text/plain;charset=UTF-8` and exactly matched the repository file (SHA-256 `67548378824f9b1efe8e6a854cc1d9ffb28729bf6ea229ed3512161d555d806e`). The public builder also matched source bytes and included its License link; LAN and public health checks returned HTTP 200.
- Browser verification followed the public footer link and displayed the license and its permission/restriction sections. The plaintext page triggered a cosmetic `/favicon.ico` 404; license navigation and content worked. Repository and GHCR package visibility were rechecked as private.

## Calendar support footer release — September 27, 2026

- Application source: `73a8229b1735c1c5c602abd9a0bedc325dc5e9eb`; successful GitHub Actions run `36346069929` passed backend and UI checks and published the private image.
- Image: `ghcr.io/javadevjt/embedify@sha256:9ccd31d9ba3506e7a644a31d025459477c6487b1dbc5a52b728c3aebe26a409c`.
- TrueNAS update job `208957` succeeded. The complete saved configuration matched the requested image-only change, and the app subsequently reported RUNNING on that digest.
- The embed now retains **Calendar by Embedify** and adds a plain **Buy me a coffee** link to the owner's profile beside it. The footer can wrap on narrow screens. No ad code or third-party scripts were added.
- Browser inspection confirmed both link destinations and new-tab protections. At 390 px, both links fit without horizontal page overflow. The public embed HTML and CSS match source bytes, health returns HTTP 200, and `no-transform` remains present. Repository and image remain private.

## Style-import release — September 27, 2026

- Application source: `25383d1919cadde33121172ea5d2e0f688213c72`.
- GitHub Actions run `36339911043` passed all 32 backend tests and UI checks, then published the private image.
- Image: `ghcr.io/javadevjt/embedify@sha256:d6481823ea92a81b7a674cba33adf640ed50a714ee497c17eaec8a4f9737a394`.
- TrueNAS update job `208752` succeeded; the app subsequently reported RUNNING on this digest. The helper compared the complete saved configuration to the requested configuration: only the image changed, preserving networking, environment, ports, limits, user, mounts, and container restrictions.
- LAN and public health checks passed. The actual Atlassian Confluence URL returned HTTP 200 with white background/surface, slate text (`#292a2e`), blue accent (`#357de8`), and sans-serif font category. The public browser showed the same palette without console errors.
- Applying the Atlassian suggestion updated the browser's color controls, preview, and generated embed URL. The CalendarLabs feed returned Labor Day and a 60-second refresh interval. Public builder/embed HTML, `app.js`, and `app.css` matched source bytes; `no-transform` remains present.
- Repository and package visibility were rechecked as private. AdSense exploration is documented in [monetization.md](monetization.md); no advertising or tracking was enabled.

## Initial release — September 27, 2026

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

For a later image release, update the ignored `.env` to the successful CI image digest, then run `bun scripts/truenas.mjs update-image --confirm embedify`. This validates the existing app and replaces only its image, preserving the proxy network, environment, port, and container restrictions. Recheck the running digest, health, served assets, and public iframe flow after the update. Revalidate the pinned control-plane certificate if TrueNAS renews it.

## Cache and operating limits

Feed pulls share a bounded 60-second cache and coalesce concurrent misses. Parsed results share a separate bounded 8 MiB cache with no extra freshness TTL; two uncached parses can run concurrently. Per-client request quotas, global outbound concurrency, response-size limits, and recurrence bounds limit abuse. Both caches are in memory; no calendar database exists.

Style suggestions sample up to 1 MiB from a public page and up to three linked public CSS files (64 KiB each), including CDN stylesheets. Inline CSS samples and pasted CSS are bounded. Fetches retain public-address checks and time limits; scripts, CSS imports, and fonts are not fetched. Suggestions extract literal colors and a local font category without executing CSS. File uploads, credentials, private-network URLs, and non-HTTPS feed requests are rejected. Public embeds expose their feed URLs, so never use secret calendar links.

Cloudflare may reject generic automation user agents; ordinary browser access and the public user flows above were verified. This does not require weakening the app's content security policy.
