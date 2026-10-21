#!/usr/bin/env bash
set -euo pipefail
umask 077

collector_bin=$1
production_config=$2
java_agent=$3
app_jar=$4
fixture_classpath=$5
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
  stop_process "$app_pid"
  stop_process "$collector_pid"
  stop_process "$capture_pid"
  rm -rf "$work"
}
trap cleanup EXIT

printf '%s' 'fixture-only-not-an-openwoof-key' > "$work/ingest-key"

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
    if {
      if ! exec 3<>/dev/tcp/127.0.0.1/18080 2>/dev/null; then
        return 1
      fi
      printf 'GET /healthz HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' >&3
      IFS= read -r response <&3
      exec 3>&-
      [[ "$response" == 'HTTP/1.1 200'* || "$response" == 'HTTP/1.0 200'* ]]
    } 2>/dev/null; then
      return 0
    fi
    sleep 0.2
  done
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

java -javaagent:"$java_agent" -jar "$app_jar" --server.address=127.0.0.1 --server.port=18080 > "$work/app.log" 2>&1 &
app_pid=$!
wait_for_app_health || { printf '%s\n' 'Instrumented Embedify did not start.' >&2; exit 1; }

java -javaagent:"$java_agent" -cp "$fixture_classpath" OtelSmoke healthy > "$work/fixture.log" 2>&1
sleep 5

grep -F 'fixture-untraced-log-healthy' "$work/fixture.log" >/dev/null
grep -F 'fixture-multiline-first' "$work/fixture.log" >/dev/null
grep -F 'fixture-multiline-second' "$work/fixture.log" >/dev/null
grep -F 'fixture-untraced-log-healthy' "$work/capture.log" >/dev/null
grep -F 'fixture-multiline-first' "$work/capture.log" >/dev/null
grep -F 'fixture-multiline-second' "$work/capture.log" >/dev/null
grep -F 'fixture-redaction-message https://calendar.example.test/[redacted]' "$work/capture.log" >/dev/null
grep -F 'service.name: Str(embedify)' "$work/capture.log" >/dev/null
grep -F 'http.client.request.duration' "$work/capture.log" >/dev/null
grep -F 'http.server.request.duration' "$work/capture.log" >/dev/null
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
wait "$app_pid"
app_pid=
grep -E 'Started .*Application' "$work/app.log" >/dev/null

printf '%s\n' 'OpenTelemetry isolated smoke passed: original console logs, untraced Logback export, multiline and exception detail, redaction, MVC/Apache HTTP spans and metrics, HTTP success/failure, downstream Collector outage, and direct exporter-failure isolation.'
