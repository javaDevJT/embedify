#!/usr/bin/env bash
set -euo pipefail
umask 077

collector_bin=$1
production_config=$2
java_agent=$3
app_jar=$4
fixture_classpath=$5
# BuildKit supplies OTLP settings for its own tracing, including Unix sockets.
# Use only explicitly configured OTLP endpoints and credentials for fixtures.
for inherited_otlp_setting in "${!OTEL_EXPORTER_OTLP_@}"; do
  unset "$inherited_otlp_setting"
done
work=$(mktemp -d /tmp/embedify-otel-smoke.XXXXXX)
capture_pid=
collector_pid=
app_pid=

stop_process() {
  local pid=${1:-}
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null || true
    local attempt
    for attempt in {1..50}; do
      if ! kill -0 "$pid" 2>/dev/null; then
        wait "$pid" 2>/dev/null || true
        return
      fi
      sleep 0.2
    done
    kill -KILL "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
}

cleanup() {
  local result=$?
  set +e
  if (( result != 0 )); then
    local log
    for log in capture-validate collector-validate collector capture app fixture failure-fixture local-export-failure; do
      if [[ -s "$work/$log.log" ]]; then
        printf 'Isolated telemetry fixture diagnostic: %s\n' "$log" >&2
        tail -n 40 "$work/$log.log" | tail -c 16000 >&2
      fi
    done
  fi
  stop_process "$app_pid"
  stop_process "$collector_pid"
  stop_process "$capture_pid"
  if [[ -n "${EMBEDIFY_OTEL_SMOKE_ARTIFACT_DIR:-}" ]]; then
    mkdir -p "$EMBEDIFY_OTEL_SMOKE_ARTIFACT_DIR"
    cp "$work"/*.log "$EMBEDIFY_OTEL_SMOKE_ARTIFACT_DIR/"
  fi
  rm -rf "$work"
  return "$result"
}

assert_ordered() {
  local file=$1 first=$2 second=$3
  local first_offset second_offset
  first_offset=$(grep -b -o -F -m 1 -- "$first" "$file" | cut -d: -f1 || true)
  second_offset=$(grep -b -o -F -m 1 -- "$second" "$file" | cut -d: -f1 || true)
  if [[ -z "$first_offset" || -z "$second_offset" ]] || (( 10#$first_offset >= 10#$second_offset )); then
    printf 'Expected ordered log markers in %s.\n' "$file" >&2
    return 1
  fi
}

trap cleanup EXIT
trap 'printf "Isolated telemetry smoke failed at line %s (status %s).\n" "$LINENO" "$?" >&2' ERR

printf '%s' 'fixture-only-not-an-openwoof-key' > "$work/ingest-key"
export OPENWOOF_INGEST_KEY_FILE="$work/ingest-key"

cat > "$work/capture.yaml" <<'YAML'
receivers:
  otlp:
    protocols:
      http:
        endpoint: 127.0.0.1:14319
exporters:
  debug:
    verbosity: detailed
service:
  telemetry:
    metrics:
      level: none
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [debug]
    metrics:
      receivers: [otlp]
      exporters: [debug]
    logs:
      receivers: [otlp]
      exporters: [debug]
YAML

sed \
  -e 's|endpoint: 0.0.0.0:4318|endpoint: 127.0.0.1:14318|' \
  -e 's|endpoint: https://openwoof.javadevjt.tech|endpoint: http://127.0.0.1:14319|' \
  "$production_config" > "$work/collector.yaml"

if ! awk '
  /^exporters:$/ { in_exporters = 1; next }
  in_exporters && /^[^[:space:]]/ { in_exporters = 0 }
  in_exporters && /^[[:space:]]+[[:alnum:]_]*endpoints?:/ {
    if ($0 != "    endpoint: http://127.0.0.1:14319") exit 1
    endpoint_count++
  }
  END { if (endpoint_count != 1) exit 1 }
' "$work/collector.yaml"; then
  printf '%s\n' 'Isolated smoke refuses an unexpected or non-loopback exporter endpoint.' >&2
  exit 1
fi

"$collector_bin" validate --config="$work/capture.yaml" > "$work/capture-validate.log" 2>&1
"$collector_bin" validate --config="$work/collector.yaml" > "$work/collector-validate.log" 2>&1
"$collector_bin" --config="$work/capture.yaml" > "$work/capture.log" 2>&1 &
capture_pid=$!
"$collector_bin" --config="$work/collector.yaml" > "$work/collector.log" 2>&1 &
collector_pid=$!

wait_for_port() {
  local port=$1
  local attempt
  for attempt in {1..60}; do
    if (exec 3<>"/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.2
  done
  return 1
}

wait_for_app_health() {
  local attempt
  local response
  for attempt in {1..60}; do
    if (
      if ! exec 3<>/dev/tcp/127.0.0.1/18080 2>/dev/null; then
        exit 1
      fi
      printf 'GET /healthz HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' >&3
      IFS= read -r response <&3
      exec 3>&-
      [[ "$response" == 'HTTP/1.1 200'* || "$response" == 'HTTP/1.0 200'* ]]
    ) 2>/dev/null; then
      return 0
    fi
    sleep 0.2
  done
  return 1
}

wait_for_capture() {
  local marker ready
  local deadline=$((SECONDS + 30))
  local markers=(
    'fixture-untraced-log-healthy'
    'fixture-multiline-first'
    'fixture-multiline-second'
    'SeverityText: INFO'
    'SeverityText: WARN'
    'SeverityText: ERROR'
    'fixture-redaction-message https://calendar.example.test/[redacted]'
    'java.lang.IllegalStateException'
    'Started EmbedifyApplication'
    'service.name: Str(embedify)'
    'http.client.request.duration'
    'http.server.request.duration'
    'Exemplar #0'
    'server.address: Str(127.0.0.1)'
    'http.response.status_code'
    '503'
  )
  while (( SECONDS < deadline )); do
    ready=true
    for marker in "${markers[@]}"; do
      if ! grep -Fq -- "$marker" "$work/capture.log"; then
        ready=false
        break
      fi
    done
    if [[ "$ready" == true ]]; then
      return 0
    fi
    sleep 0.2
  done
  printf '%s\n' 'Required telemetry did not reach the capture within 30 seconds.' >&2
  return 1
}

wait_for_port 14318 || { printf '%s\n' 'Collector receiver did not start.' >&2; exit 1; }
wait_for_port 14319 || { printf '%s\n' 'Capture receiver did not start.' >&2; exit 1; }

export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:14318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_TRACES_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=otlp
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_TIMEOUT=2000
export OTEL_BSP_SCHEDULE_DELAY=1000
export OTEL_BSP_EXPORT_TIMEOUT=2000
export OTEL_BSP_MAX_QUEUE_SIZE=128
export OTEL_BSP_MAX_EXPORT_BATCH_SIZE=32
export OTEL_METRICS_EXPORT_INTERVAL=1000
export OTEL_BLRP_SCHEDULE_DELAY=1000
export OTEL_BLRP_EXPORT_TIMEOUT=2000
export OTEL_BLRP_MAX_QUEUE_SIZE=128
export OTEL_BLRP_MAX_EXPORT_BATCH_SIZE=32
export OTEL_SERVICE_NAME=embedify
export OTEL_RESOURCE_ATTRIBUTES=service.namespace=javadevjt,deployment.environment.name=production,service.version=collector-smoke

"${EMBEDIFY_OTEL_SMOKE_APP_JAVA:-java}" -javaagent:"$java_agent" -jar "$app_jar" --server.address=127.0.0.1 --server.port=18080 > "$work/app.log" 2>&1 &
app_pid=$!
wait_for_app_health || { printf '%s\n' 'Instrumented Embedify did not start.' >&2; exit 1; }

java -javaagent:"$java_agent" -cp "$fixture_classpath" OtelSmoke healthy > "$work/fixture.log" 2>&1

grep -F 'fixture-untraced-log-healthy' "$work/fixture.log" >/dev/null
grep -F 'fixture-multiline-first' "$work/fixture.log" >/dev/null
grep -F 'fixture-multiline-second' "$work/fixture.log" >/dev/null
grep -E 'INFO .*fixture-untraced-log-healthy' "$work/fixture.log" >/dev/null
grep -E 'WARN .*fixture-multiline-first' "$work/fixture.log" >/dev/null
grep -E 'ERROR .*fixture-redaction-message' "$work/fixture.log" >/dev/null
grep -F 'https://fixture-user:fixture-pass@calendar.example.test/SMOKE_PATH?token=SMOKE_QUERY' "$work/fixture.log" >/dev/null
wait_for_capture
grep -F 'fixture-untraced-log-healthy' "$work/capture.log" >/dev/null
grep -F 'fixture-multiline-first' "$work/capture.log" >/dev/null
grep -F 'fixture-multiline-second' "$work/capture.log" >/dev/null
grep -F 'SeverityText: INFO' "$work/capture.log" >/dev/null
grep -F 'SeverityText: WARN' "$work/capture.log" >/dev/null
grep -F 'SeverityText: ERROR' "$work/capture.log" >/dev/null
assert_ordered "$work/fixture.log" 'fixture-untraced-log-healthy' 'fixture-multiline-first'
assert_ordered "$work/fixture.log" 'fixture-multiline-first' 'fixture-redaction-message'
assert_ordered "$work/capture.log" 'fixture-untraced-log-healthy' 'fixture-multiline-first'
assert_ordered "$work/capture.log" 'fixture-multiline-first' 'fixture-redaction-message'
grep -F 'fixture-redaction-message https://calendar.example.test/[redacted]' "$work/capture.log" >/dev/null
grep -E 'Started .*Application' "$work/capture.log" >/dev/null
grep -F 'service.name: Str(embedify)' "$work/capture.log" >/dev/null
grep -F 'http.client.request.duration' "$work/capture.log" >/dev/null
grep -F 'http.server.request.duration' "$work/capture.log" >/dev/null
grep -F 'Exemplar #0' "$work/capture.log" >/dev/null
grep -F 'server.address: Str(127.0.0.1)' "$work/capture.log" >/dev/null
grep -F 'http.response.status_code' "$work/capture.log" >/dev/null
grep -F '503' "$work/capture.log" >/dev/null
grep -F 'java.lang.IllegalStateException' "$work/capture.log" >/dev/null
if grep -E 'SMOKE_PATH|SMOKE_QUERY|fixture-user|fixture-pass' "$work/capture.log" >/dev/null; then
  printf '%s\n' 'Collector output retained a fixture URL path, query, or userinfo secret.' >&2
  exit 1
fi

stop_process "$capture_pid"
capture_pid=
java -javaagent:"$java_agent" -cp "$fixture_classpath" OtelSmoke export-failure > "$work/failure-fixture.log" 2>&1
sleep 5
kill -0 "$app_pid"
grep -F 'fixture-export-failure-isolation-export-failure' "$work/failure-fixture.log" >/dev/null
grep -Eiq '(error.*export|export.*error|failed.*export|export.*failed|connection refused)' "$work/collector.log"

env OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:14399 \
  java -javaagent:"$java_agent" -cp "$fixture_classpath" OtelSmoke local-agent-export-failure \
  > "$work/local-export-failure.log" 2>&1
kill -0 "$app_pid"
grep -F 'fixture-export-failure-isolation-local-agent-export-failure' "$work/local-export-failure.log" >/dev/null
grep -Eiq '(error.*export|export.*error|failed.*export|export.*failed|connection refused)' "$work/local-export-failure.log"

kill -TERM "$app_pid"
app_exit=0
wait "$app_pid" || app_exit=$?
app_pid=
if [[ "$app_exit" != 0 && "$app_exit" != 143 ]]; then
  printf 'Instrumented Embedify exited unexpectedly after SIGTERM: %s\n' "$app_exit" >&2
  exit 1
fi
grep -E 'Started .*Application' "$work/app.log" >/dev/null

printf '%s\n' 'OpenTelemetry isolated smoke passed: original console logs, untraced Logback export, multiline and exception detail, redaction, MVC/Apache HTTP spans and metrics, HTTP success/failure, downstream Collector outage, and direct exporter-failure isolation.'
