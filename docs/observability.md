# Production observability

Embedify sends application logs, traces, and metrics through an app-scoped OpenTelemetry Collector on TrueNAS to `https://openwoof.javadevjt.tech` using OTLP HTTP/protobuf. The Collector is not published on a host port and does not join the Nginx Proxy Manager network. Its only credential is mounted read-only from an operator-configured protected path.

## Coverage and release state

This document describes the implementation being qualified. It does not claim production telemetry until the release chain and persisted OpenWoof readback are verified.

| Component | Hook or evidence | State |
| --- | --- | --- |
| Existing container logs | TrueNAS baseline capture at `target/observability/baseline-logs.jsonl` | Captured before the change; ignored by Git |
| Spring Boot / Logback | OpenTelemetry Java agent Logback appender; original Logback console appender remains enabled | Source configured; deployed forwarding and before/after content comparison pending |
| Inbound HTTP | Java agent instrumentation of the Spring MVC/Tomcat request path | Source configured; deployed spans and metric names pending |
| Outbound HTTP | Java agent instrumentation of the app's Apache HttpClient 5 calls in `SafeFetcher` | Source configured; real successful and failed peer operations pending |
| Application metrics | Java agent HTTP/JVM instruments, when emitted by the actual runtime | Source configured; metric kind, unit, temporality, and persisted observations pending |
| Collector privacy and transport | Collector build validates the production config; TLS verification remains enabled | CI qualification pending |
| Key custody and delivery | Exact service-specific macOS Keychain item and protected TrueNAS file | Installed directly; metadata verified on October 21, 2026 at 20:47:58 UTC: UID/GID 10001, mode 0400, no widening ACL; runtime mount pending release |
| OpenWoof storage/readback | Persisted signal inspection through the owner's authenticated UI | Key installed; deployment and owner-session readback pending |
| Other protocols/jobs | No database, queue, mail, scheduled job, or subprocess hook has been established | Unexercised; do not infer coverage from process duration or HTTP spans |

The current public production image remains unchanged until the source passes CI and its immutable digest is deliberately deployed.

Local qualification on October 21 passed all 34 Maven tests, URL/UI checks, CI trust policy checks, telemetry policy checks, the TrueNAS helper self-test, and the Mac secure-field/native-query/private-stdin self-test. Independent source review accepted the separate local storage and direct delivery paths. These checks do not establish container startup, upstream authorization, or persisted OpenWoof export; those remain release checks below.

The isolated localhost pipeline also passed with the checksum-verified Java agent 2.32.0 and Collector 0.163.2 on macOS/JDK 27: original and untraced Logback messages, multiline/exception detail, actual HTTP success/failure spans, typed metrics, targeted redaction, downstream outage, and direct exporter-failure isolation. `http.client.request.duration` and `http.server.request.duration` were cumulative histograms measured in seconds. The corrected capture contained five exemplars and zero synthetic URL-secret markers; their values and trace links remain available. The capture Collector's own metrics are disabled only in the fixture to avoid listener collisions. Startup retries and normal SIGTERM handling are covered. The reduced runtime explicitly includes `java.instrument`; isolated module checks and the full pipeline with the app on that reduced runtime passed. This preparation does not replace the Linux/Temurin 25 image build or deployed readback.

The same pipeline passed on isolated Linux/JDK 25 with the pinned build images on ARM64 and on AMD64 through existing emulation. The emulated run required a longer local startup bound. Main CI run `39677747214` reached its reduced-runtime test on attempt 3 but failed to find the exported fixture log; its cause is not yet established. Capture now waits up to 30 seconds for the required original logs, HTTP histograms, and exemplar instead of sleeping for a fixed five seconds. All content, ordering, privacy, and failure-isolation assertions remain. Controlled delayed-capture and missing-capture checks verify both success and bounded failure; cleanup preserves the original failure status and prints bounded diagnostics before teardown. These isolated results do not establish a passing release image or persisted production telemetry.

## Signal and network path

The application starts with the pinned OpenTelemetry Java agent before Spring Boot loads. It exports OTLP over HTTP/protobuf to `http://otel-collector:4318` on a private Compose network. The agent captures the actual Logback event separately from the existing console output, and instruments real Spring MVC server requests and Apache HttpClient 5 calls. Current source contains no independent database, queue, scheduled-work, or subprocess boundary that can be reported as instrumented.

The Collector reads `OPENWOOF_INGEST_KEY_FILE` from `/run/secrets/openwoof-ingest-key` and sends the three configured signals to the OpenWoof HTTPS OTLP base endpoint. TLS certificate and hostname verification are on. Only the Collector receives the key. The app has no key or exporter authorization header in its environment, and browser bundles do not receive a service credential.

Both services use `service.name=embedify`, `service.namespace=javadevjt`, `deployment.environment.name=production`, and the deployed immutable image digest as `service.version`. The agent and Collector run as UID/GID 10001 with read-only root filesystems, all capabilities dropped, no-new-privileges, and bounded CPU, memory, PIDs, batches, queue, and retries. Collector startup is independent of app startup so an upstream outage does not gate the web app.

## Log preservation and privacy

The Java agent adds its Logback bridge without removing or replacing Spring Boot's console appender. Original application logs therefore continue to stream to TrueNAS container logs while Logback events are exported asynchronously. The bridge carries the event's timestamp, severity, logger scope, message, and exception details; the Collector preserves multiline bodies. Java-agent diagnostics continue on their own stderr path and are not relabeled as application logs. Embedify currently has no application-owned `System.out`/`System.err` calls or separate child process whose raw output needs a second collector receiver.

Calendar feed URLs can contain private userinfo, path segments, and query tokens. Before export, the Collector removes URL-valued path/query attributes from spans, metrics, and logs, and applies a targeted URL pattern to log bodies and exception text. The pattern strips userinfo and path/query/fragment data while preserving the scheme and peer host. Traces retain the observed peer host and port with HTTP method, response status, error type, duration, surrounding log text, exception class, and stack frames. Peer host/port fields are removed from metrics to avoid arbitrary feed domains becoming high-cardinality dimensions; bounded method/status/route dimensions remain. Metric exemplar filtered attributes receive the same targeted URL/peer cleanup; exemplar values, timestamps, and trace/span IDs are preserved. Client span names are reduced to the HTTP operation so a URL cannot leak through the span name. No entire log body or exception is dropped or replaced. Routine `/healthz` server spans and their HTTP request metrics are excluded; health requests do not create synthetic application log events.

The isolated regression checks that a message such as `Calendar fetch failed for https://<userinfo>@calendar.example.test/<private-path>?<token>` remains a failure message with the peer host, while its exported form becomes `Calendar fetch failed for https://calendar.example.test/[redacted]`. This is a fixture example; no production feed is used.

Collector transform errors use `error_mode: propagate`, while its bounded exporter queue retries for up to ten minutes. Export failures must remain visible in Collector diagnostics and must not alter the app's original logs or request behavior. The key is not used for UI queries or readback.

## Credential workflow

The OpenWoof ingest credential is operator-provisioned and stays outside source control, GitHub Actions, Compose text, and repository environment files. The runtime receives only the configured secret-file path; the app container never receives the credential. Use a collector-wide ingestion key with only the required write grants, prefer a finite expiry, and keep it revocable. Do not create the key through this repository or paste it into chat.

Run the operator helper from the repository root in two separately scoped steps:

```sh
osascript -l JavaScript scripts/PromptOpenWoofKey.js --store-keychain
osascript -l JavaScript scripts/PromptOpenWoofKey.js --install-keychain
```

The first step opens the standard macOS hidden-answer dialog and saves a new exact app-specific entry through the native Keychain API. It makes no network requests, preserves an existing entry without reading its value, and never replaces credentials. The second step checks the configured TrueNAS target and protected file, retrieves the existing Keychain item into private memory through the native API, and sends it through a private process pipe over verified TLS. An existing protected NAS file is preserved. Both steps return only nonsecret status; failures exit unsuccessfully with a fixed message. Running without a mode only prints usage. `--keychain-status` checks only the exact entry's existence. The nonsecret `--self-test` checks secure-field construction, native query construction, private process-stdin handoff, and configured target identity without accessing or changing Keychain entries. Child processes use the fixed operator-home Bun executable and a minimal environment.

Baseline and post-deployment container-log captures use separate write-once files. The post-deployment evidence redacts URL userinfo/path/query/fragment data, Bearer values, and common token/password assignments before writing the ignored mode-0600 artifact; it never prints raw log bodies to the terminal.

## Qualification evidence

### Release-clone source and local runtime evidence (2026-11-08)

The source patch is based on deployed source commit `630f9a6e39ee17840fcab09c893ec816bbb5e59b`. It selectively ports the read-only donor's URL-free operation spans, bounded outcome/cache/feed metrics, OpenTelemetry API/test dependencies, W3C trace-context configuration, and offline telemetry/package-smoke tests. The target's later business behavior remains in place. `mvn -B test` passed **38 tests, 0 failures, 0 errors, 0 skips**; `mvn -B -DskipTests package` produced `target/embedify-0.1.0.jar` (SHA-256 `8a33d465e1368e4d51edc491dfb42ac51c3e9c30cf49ff2beccf36e78e411793`).

For local ARM64 runtime coverage, that exact-source JAR was mounted read-only at `/app/embedify.jar` in the existing corrected runtime image and its healthcheck metadata derivative. The image runs as UID/GID 10001 and its inherited healthcheck command remained `/opt/runtime/bin/java -cp /opt/healthcheck ContainerHealthCheck` (30-second interval, 4-second timeout, 45-second start period, 3 retries); manual execution exited 0. Temurin 25.0.4.1 loaded the Java agent as UID 10001 and reported agent version 2.32.0. A synthetic W3C-parented request to the loopback-only `/api/events` endpoint returned 400 for `http://127.0.0.1`; the fake OTLP receiver saw the requested trace ID and accepted 3 trace, 13 metric, and 3 log batches. `podman stop` delivered SIGTERM and Spring/Tomcat logged graceful shutdown completion (the Java process exited 143, the signal status).

The runtime check reused local ARM64 image IDs `ee04564f07748c4f30b95f49f45a786b96de4f550413b4eb061b6c96f59857f3` and healthcheck derivative `11d9971155195a272ae8fe37afad2f5bcd6463f17cc6da0852036b13abc5336b`; these images do not carry a source-revision label. The read-only JAR mount ties the observed app runtime to the tested source package, but does not qualify a self-contained image built from this worktree. Failed attempts remain separate: `embedify-healthcheck-smoke-20261108` on the earlier metadata image logged `Error opening zip file or JAR manifest missing: /opt/opentelemetry-javaagent.jar`; the first fake receiver in `embedify-target-source-qualification-20261108` raised `TypeError: a bytes-like object is required, not 'str'` and caused exporter errors. The corrected receiver was used for the successful pass. No provider or production collector was contacted, and no credential was mounted. This is local ARM64 evidence only; CI, a self-contained exact-source image, NAS AMD64, deployment, and persisted OpenWoof readback remain pending.

The checks are intentionally separate:

1. Maven tests and the source policy check validate application behavior and the intended telemetry, network, and privacy settings.
2. The release Docker build runs the pinned Collector's own `validate` command against the production config.
3. CI must pass the High/Critical vulnerability gate with no ignored findings and the existing strict BuildKit storage gate.
4. A passing source SHA must resolve to the exact immutable GHCR digest before TrueNAS deployment.
5. TrueNAS must report both containers running on that digest, with the app logs still present and the Collector using the protected file.
6. Only persisted OpenWoof observations and owner-authenticated readback prove export. Compare original app/container log records with the baseline, including ordering, severity, timestamps, multiline framing, logger scope, and exception details. Verify actual HTTP client peer, operation, reply/status, duration, error, and corresponding HTTP metric semantics.

Controlled success, failure, untraced-log, redaction, and export-failure-isolation checks belong in isolated fixtures. The build-time smoke starts the packaged app with the generated jlink runtime after it is built; the separate HTTP fixture runs on the build JDK because its test server module is not shipped. It checks the same fixture markers in original console output and exported Logback events, including INFO/WARN/ERROR severity and message order; it also checks that the packaged app's startup log reaches both destinations. Do not manufacture production traffic or trigger private feeds to fill charts. Record unavailable production operations as unexercised, and preserve failed attempts separately from later passes. The ignored `target/observability/` directory is for bounded, nonsecret evidence only; never write telemetry credentials or feed contents there.
