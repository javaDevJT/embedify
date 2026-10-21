# Production observability

Embedify sends application logs, traces, and metrics through an app-scoped OpenTelemetry Collector on TrueNAS to `https://openwoof.javadevjt.tech` using OTLP HTTP/protobuf. The Collector is not published on a host port and does not join the Nginx Proxy Manager network. Its only credential is mounted read-only from the protected TrueNAS app directory.

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
| Key custody and delivery | Exact service-specific macOS Keychain item and read-only TrueNAS file | No OpenWoof key has been installed |
| OpenWoof storage/readback | Persisted signal inspection through the owner's authenticated UI | Pending an installed key, deployment, and owner-session readback |
| Other protocols/jobs | No database, queue, mail, scheduled job, or subprocess hook has been established | Unexercised; do not infer coverage from process duration or HTTP spans |

The current public production image remains unchanged until the source passes CI and its immutable digest is deliberately deployed.

## Signal and network path

The application starts with the pinned OpenTelemetry Java agent before Spring Boot loads. It exports OTLP over HTTP/protobuf to `http://otel-collector:4318` on a private Compose network. The agent captures the actual Logback event separately from the existing console output, and instruments real Spring MVC server requests and Apache HttpClient 5 calls. Current source contains no independent database, queue, scheduled-work, or subprocess boundary that can be reported as instrumented.

The Collector reads `OPENWOOF_INGEST_KEY_FILE` from `/run/secrets/openwoof-ingest-key` and sends the three configured signals to the OpenWoof HTTPS OTLP base endpoint. TLS certificate and hostname verification are on. Only the Collector receives the key. The app has no key or exporter authorization header in its environment, and browser bundles do not receive a service credential.

Both services use `service.name=embedify`, `service.namespace=javadevjt`, `deployment.environment.name=production`, and the deployed immutable image digest as `service.version`. The agent and Collector run as UID/GID 10001 with read-only root filesystems, all capabilities dropped, no-new-privileges, and bounded CPU, memory, PIDs, batches, queue, and retries. Collector startup is independent of app startup so an upstream outage does not gate the web app.

## Log preservation and privacy

The Java agent adds its Logback bridge without removing or replacing Spring Boot's console appender. Original application logs therefore continue to stream to TrueNAS container logs while Logback events are exported asynchronously. The bridge carries the event's timestamp, severity, logger scope, message, and exception details; the Collector preserves multiline bodies. Java-agent diagnostics continue on their own stderr path and are not relabeled as application logs. Embedify currently has no application-owned `System.out`/`System.err` calls or separate child process whose raw output needs a second collector receiver.

Calendar feed URLs can contain private userinfo, path segments, and query tokens. Before export, the Collector removes URL-valued path/query attributes from spans, metrics, and logs, and applies a targeted URL pattern to log bodies and exception text. The pattern strips userinfo and path/query/fragment data while preserving the scheme and peer host. Traces retain the observed peer host and port with HTTP method, response status, error type, duration, surrounding log text, exception class, and stack frames. Peer host/port fields are removed from metrics to avoid arbitrary feed domains becoming high-cardinality dimensions; bounded method/status/route dimensions remain. Client span names are reduced to the HTTP operation so a URL cannot leak through the span name. No entire log body or exception is dropped or replaced. Routine `/healthz` server spans and their HTTP request metrics are excluded; health requests do not create synthetic application log events.

The isolated regression checks that a message such as `Calendar fetch failed for https://<userinfo>@calendar.example.test/<private-path>?<token>` remains a failure message with the peer host, while its exported form becomes `Calendar fetch failed for https://calendar.example.test/[redacted]`. This is a fixture example; no production feed is used.

Collector transform errors use `error_mode: propagate`, while its bounded exporter queue retries for up to ten minutes. Export failures must remain visible in Collector diagnostics and must not alter the app's original logs or request behavior. The key is not used for UI queries or readback.

## Credential workflow

The deterministic macOS Keychain service is `openwoof/openwoof.javadevjt.tech/truenas-192.168.0.2/embedify/production`, with account `embedify`. Use an OpenWoof ingest key with `collector:write`, `traces:write`, `metrics:write`, and `logs:write`. It is an ingestion-only, collector-wide credential: the Keychain name does not restrict which service can use it, and it grants no UI, query, or administrative access. A finite expiry is preferred; select Never only intentionally, and keep that key revocable. Do not create the key through this repository or paste it into chat.

After CI qualification, run `swift scripts/PromptOpenWoofKey.swift` on the operator Mac. The native secure text field is used instead of returning hidden dialog text through `osascript`; the helper stores/retrieves only the exact Keychain item and sends the value through private process memory/stdin over verified TLS to the protected TrueNAS file. It prints only nonsecret status. The file is owner/group 10001, mode 0400, has no widening ACL, is mounted read-only into the Collector, and is never placed in Compose source or `.env`.

The app-specific TrueNAS commands are intentionally narrow:

```sh
bun scripts/truenas.mjs secret-status
bun scripts/truenas.mjs capture-baseline-logs
bun scripts/truenas.mjs prepare-openwoof-secret --confirm embedify
swift scripts/PromptOpenWoofKey.swift
bun scripts/truenas.mjs update-observability --confirm embedify
bun scripts/truenas.mjs inspect
bun scripts/truenas.mjs capture-after-logs
```

The secret directory is created only after the existing app and upload path have been verified. The deploy helper accepts only the reviewed Embedify migration and preserves the existing trusted proxy setting. Do not use these commands to replace an unexpected file or unrelated TrueNAS configuration.

Baseline and post-deployment container-log captures use separate write-once files. The post-deployment evidence redacts URL userinfo/path/query/fragment data, Bearer values, and common token/password assignments before writing the ignored mode-0600 artifact; it never prints raw log bodies to the terminal.

## Qualification evidence

The checks are intentionally separate:

1. Maven tests and the source policy check validate application behavior and the intended telemetry, network, and privacy settings.
2. The release Docker build runs the pinned Collector's own `validate` command against the production config.
3. CI must pass the High/Critical vulnerability gate with no ignored findings and the existing strict BuildKit storage gate.
4. A passing source SHA must resolve to the exact immutable GHCR digest before TrueNAS deployment.
5. TrueNAS must report both containers running on that digest, with the app logs still present and the Collector using the protected file.
6. Only persisted OpenWoof observations and owner-authenticated readback prove export. Compare original app/container log records with the baseline, including ordering, severity, timestamps, multiline framing, logger scope, and exception details. Verify actual HTTP client peer, operation, reply/status, duration, error, and corresponding HTTP metric semantics.

Controlled success, failure, untraced-log, redaction, and export-failure-isolation checks belong in isolated fixtures. Do not manufacture production traffic or trigger private feeds to fill charts. Record unavailable production operations as unexercised, and preserve failed attempts separately from later passes. The ignored `target/observability/` directory is for bounded, nonsecret evidence only; never write telemetry credentials or feed contents there.
