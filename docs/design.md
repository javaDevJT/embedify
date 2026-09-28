# Design and implementation record

## Scope and decisions

- Spring Boot 4.1.1, Java 25, plain HTML/CSS/JavaScript. One process, no database, no frontend package manager.
- Public HTTPS iCalendar feed URLs, including webcal links normalized to HTTPS. Up to five feeds per embed. No uploads or account credentials.
- Builder and instructions at `/`; iframe calendar at `/embed`. Month and agenda views; URL parameters control colors, typography, spacing, title, timezone, and week start.
- Feed data is fetched on demand through a shared bounded 60-second cache; concurrent misses coalesce. Requests, upstream bytes, fetch concurrency, and recurrence expansion are bounded.
- Outbound requests must resolve to public IPs; pin validated DNS results to the connection, validate every redirect, keep TLS hostname verification, disallow credentials and non-HTTPS ports.
- Style suggestions accept pasted CSS text or one public page/CSS URL. Extract a palette and a safe font category. Never execute supplied CSS/HTML/JavaScript, render a remote page, or fetch assets beyond up to three bounded public stylesheets, including CDN hosts.
- Secret feed URLs are visible to visitors and must not be used on public pages. Logs and errors must omit feed query strings; responses use no-store and no-referrer.
- Jlink runtime and non-root container, read-only filesystem, dropped capabilities, no privilege escalation. Private GitHub repository and private build image until explicit publication approval.

## HTTP contract

`GET /api/events?feed=<https URL>&feed=<optional URL>&from=2026-09-01&to=2026-10-13&tz=America/Detroit`

Dates are ISO local dates; `to` is exclusive. Maximum 93 days. Response: `{title, events, fetchedAt, refreshSeconds:60, truncated}`. Events: `{id,title,start,end,allDay,location,description,url,feedIndex}`; start/end are ISO dates for all-day events and ISO instants for timed events. All-day end is exclusive.

`POST /api/style` with JSON `{css:"..."}` or `{url:"https://..."}`. Response: `{background,surface,text,accent,font,notes:[]}`; colors are six-digit hex strings; font is `sans`, `serif`, or `mono`. Limit 64 KiB pasted CSS. Extractive suggestions, no external AI service.

`GET /api/config` returns `{coffeeUrl,refreshSeconds:60}`. `GET /healthz` returns status and build version only.

Errors are JSON `{message}` and appropriate HTTP status (400 invalid input, 413 oversize, 429 quota/concurrency with Retry-After, 502 upstream failure).

## Ownership and checks

Verified 2026-09-27: native callable catalog lists `gpt-6-luna` with highest effort `max`; app catalog labels 5.6 Luna older. All workers use `gpt-6-luna` / `max`, partial context. Priority control is not exposed by spawn.

| Work | Owner | Scope | Check | State |
|---|---|---|---|---|
| Calendar backend and request security | `/root/calendar` | Java/resources/tests except style package and static assets | SSRF, cache concurrency/TTL, recurrence/timezones, HTTP limits | accepted; complete/released |
| Builder and embed UI | `/root/frontend` | `src/main/resources/static/` and UI checks | browser flows, keyboard/mobile, safe URL/text handling | accepted; complete/released |
| Container and delivery | `/root/delivery`, finalized by primary | Dockerfile, compose, Actions, deployment docs, scripts | jlink/container health/non-root; read-only infrastructure discovery | accepted after primary corrections; complete/released |
| Style extraction, integration, release | primary | style Java/tests, pom, index, integration | extraction, full test/build, browser, private repo/CI/runtime proof | complete; release evidence in deployment document |
| Independent public-input review | `/root/security_review` | Review of outbound fetch boundary; iCal4j config and focused assertion | SSRF, implicit timezone fetches, recurrence limits | accepted; complete/released |

Workers consume the HTTP contract above; backend exports `tech.javadevjt.embedify.net.SafeFetcher.fetch(String url, int maxBytes)` returning a result with `body()`, `contentType()`, and `url()` strings for the style service. Worker changes to that contract must be coordinated. Base revision is an empty repository. No worker may commit/push, create repositories, mutate infrastructure, or spawn children. Completion returns worker capacity; primary reviews before acceptance.

## Acceptance

- Build a working embed URL and iframe markup, preview actual chosen options, and render real public calendar feeds.
- Month and agenda navigation; recurring, all-day, multi-day, cancelled, and timezone-aware events covered by runnable checks.
- CSS/page style suggestion applied only after user chooses it; no upload inputs.
- Repeated viewers share upstream pulls; distinct URLs and expensive recurrence cannot bypass resource caps.
- Private repository and GitHub Actions build, inspectable immutable image; TrueNAS and Nginx Proxy Manager deployment verified separately from local success.

## Confirmed inputs

- Buy Me a Coffee: `https://buymeacoffee.com/javadevjt`.
- Public HTTPS feeds only, confirmed by the user.

- The user rejected the initial visual design and requested a Stitch redesign. Stitch project `396617582496486596` is private; generated screen `2aece2e815c8419190d6da888de11d33`, design system `assets/b1643b6912294fbba20c52ac46543bf6` (Precision Slate Embed). Source exports and request are retained under ignored `artifacts/stitch/`; the implemented static UI is the reviewable deliverable. Keep the generated slate/blue visual direction, adapt it to existing working features, and omit mock controls and unsupported marketing claims.

## Event title follow-up, September 28, 2026

- Primary owns cell containment, palette-aware overflow tooltips, removal of the loaded-feed success banner, browser checks, and release. The event list now uses a zero-minimum grid column so a long title cannot enlarge its day cell. Month titles ellipsize and show their complete bounded text in a native popover; agenda titles wrap. No dependency was added.
- Tooltips inherit the selected calendar surface, text, accent, font, and corner style. They open only for clipped titles on hover or focus, stay available while hovered, fit within the viewport, and close on Escape without moving focus. The successful-load message is empty in both builder and embed.
- Read-only reviewer `/root/tooltip_accessibility` completed at base `61afe2c`; primary accepted and addressed its findings on unlinked focus semantics, horizontal offscreen anchors, and resize while focused. Requested worker configuration: `gpt-6-luna` / `max`, resolved against the September 28 native catalog (one callable Luna, maximum supported effort). Actual backend model fields and priority are unexposed. Handoff consumed; native completion released the worker.
- The synthetic browser check reproduced cell overflow on the previous UI and passed on the corrected UI at desktop and 320/390 px widths. It checks title containment, clipped-only display, light/dark colors, hover persistence, keyboard dismissal, resize, safe text, render cleanup, and removal of both success banners. No private feed is used. Run against an already opened target with `playwright-cli --session <session> run-code --filename scripts/event-title-check.js`; the existing Playwright CLI supplies the browser, without adding a frontend package install or build step to the app.
- Release verification is tracked in [deployment.md](deployment.md).
- Read-only infrastructure reviewer `/root/tooltip_ci` completed after two publication runner disconnects. Local controller source uses a 10-minute idle reaper and a 2-hour busy limit; a missed JobStarted event could leave an active worker classified as idle, but current controller logs were unavailable and the local source was uncommitted, so this is an unverified hypothesis. No fleet changes were made. Attempt 3 subsequently succeeded, and the public release passed the same browser regression. Requested worker: `gpt-6-luna` / `max` from the same live catalog; actual backend fields and priority were unexposed. Handoff consumed; native completion released the worker.

## Follow-up investigation, September 27, 2026

- Primary owns reproduction and correction of public-page style matching for the Atlassian Confluence URL, including any application changes and release.
- Ads research worker: read-only investigation of current Google AdSense publisher rules for an unobtrusive ad in a third-party calendar iframe. Deliver a sourced recommendation and constraints for the primary's design decision; no account, ad, or infrastructure changes.
- Worker selection rechecked against the live native catalog: `gpt-6-luna` / `max`; older Luna entries are explicitly marked older. No priority selector is exposed. The worker completes/releases after its bounded handoff.
- Fetch-prefix worker owns `SafeFetcher` and its network tests only: add a bounded HTML/CSS prefix fetch for style discovery while preserving strict complete-feed fetches, DNS pinning, redirect checks, timeout and concurrency budgets. Primary consumes the new method in style extraction. Worker uses the same verified model policy and completes/releases after its focused tests.

## Initial release integration evidence, September 27, 2026

The style-import follow-up reproduced the Atlassian page exceeding the old 256 KiB whole-page limit, with useful stylesheet links on separate public CDN hosts. The importer now reads a bounded 1 MiB page prefix plus up to three guarded 64 KiB stylesheets. Each CSS sample is parsed separately so truncation cannot hide the next sheet. Calendar fetches still require complete bounded responses. Optional stylesheet failures no longer discard usable page styles; capacity limits still propagate.

Follow-up checks: `mvn -B -ntp verify` passed all 32 tests, UI checks and the TrueNAS helper self-test passed, and the actual Atlassian URL returned a white/slate/blue sans-serif palette locally. The network worker completed; primary reviewed cancellation-before-close and cache isolation. AdSense research is complete in [monetization.md](monetization.md); no ads or tracking were enabled. Public release evidence is recorded separately in [deployment.md](deployment.md).

- `mvn -B -ntp verify`: 27 tests, zero failures/errors. `node scripts/ui-check.mjs` passed.
- Primary browser checks exercised CalendarLabs US holidays, rendered Labor Day, CSS palette suggestion/application, public-page style extraction from `https://example.com/`, and copying the embed URL. The mobile builder had a 390 px viewport and 390 px document width; the desktop and dark calendar were inspected visually.
- Copied builder URLs omit `month`, so saved embeds roll forward naturally. Explicit standalone `month=YYYY-MM` links remain supported.
- Feed fetches retain their shared 60-second cache. An additional 8 MiB parsed-result cache keys on body digest, range, timezone, feed index, and limit; changed fetched content triggers a fresh parse without a second freshness delay. At most two uncached parses run concurrently.
- CalendarLabs all-day events with missing or same-day `DTEND` are normalized to a next-day exclusive end and covered by regression fixtures.
- Review of the resolved iCal4j 4.3.0 sources identified automatic timezone updates using unguarded URL connections. Classpath `ical4j.properties` disables updates; a regression asserts both the resolved setting and the updater's disabled state. No feed-supplied timezone update requests are required.
- Missing static resources retain HTTP 404 instead of becoming HTTP 500. A local SVG favicon prevents unnecessary missing-resource requests.
- Build, private registry publication, TrueNAS runtime, and public HTTPS checks are tracked separately in the deployment document.
- The release is deployed and public HTTPS is verified. NPM routes directly to the app over its dedicated network; the trusted subnet is verified, and edge analytics injection is disabled for this hostname. Repository and image visibility remain private. All workers are completed/released.
