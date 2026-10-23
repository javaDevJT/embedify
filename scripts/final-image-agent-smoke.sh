#!/usr/bin/env bash
set -euo pipefail

image_ref=${1:?usage: final-image-agent-smoke.sh IMAGE_REF [WAIT_SECONDS]}
wait_seconds=${2:-120}
if [[ ! "$wait_seconds" =~ ^[0-9]+$ ]] || (( wait_seconds < 1 || wait_seconds > 120 )); then
  printf 'WAIT_SECONDS must be an integer from 1 to 120.\n' >&2
  exit 2
fi

image_metadata=$(podman image inspect --format '{{.Os}} {{.Architecture}} {{.Config.User}} {{.Id}}' "$image_ref")
read -r image_os image_arch image_user image_id <<< "$image_metadata"
if [[ "$image_os" != linux || "$image_arch" != amd64 || "$image_user" != 10001:10001 ]]; then
  printf 'Unexpected candidate image profile: os=%s arch=%s user=%s\n' \
    "$image_os" "$image_arch" "$image_user" >&2
  exit 1
fi

printf 'Checking final-image Java agent as UID 10001 in isolated image %s.\n' "$image_id"
if ! output=$(podman run --rm --pull=never --platform linux/amd64 \
  --network=none --read-only --user 10001:10001 --cap-drop=ALL \
  --security-opt=no-new-privileges --pids-limit=128 --memory=512m \
  --mount type=tmpfs,dst=/tmp,tmpfs-size=16777216,tmpfs-mode=1777,U=true,noexec,nosuid,nodev \
  --entrypoint /opt/runtime/bin/java "$image_id" \
  -javaagent:/opt/opentelemetry-javaagent.jar -version 2>&1); then
  printf '%s\n' "$output" >&2
  exit 1
fi
if grep -Eqi 'Error opening zip file|Agent_OnLoad' <<< "$output"; then
  printf 'Java agent failed to load:\n%s\n' "$output" >&2
  exit 1
fi
grep -Eq '^openjdk version "[^"]+"' <<< "$output" || {
  printf 'The isolated Java agent probe did not report the expected runtime:\n%s\n' "$output" >&2
  exit 1
}
printf '%s\n' "$output"

container_name="embedify-final-smoke-$$-${RANDOM}"
container_id=
app_start_seconds=$SECONDS
health_probe_passed=false
cleanup() {
  local result=$?
  trap - EXIT
  if [[ -n "$container_id" ]]; then
    if (( result != 0 )); then
      printf 'Default-entrypoint startup diagnostics for %s:\n' "$container_id" >&2
      printf 'elapsed_seconds=%s direct_health_probe=%s\n' "$((SECONDS - app_start_seconds))" "$health_probe_passed" >&2
      podman inspect --format '{{json .State}}' "$container_id" >&2 || true
      podman inspect --format 'memory_bytes={{.HostConfig.Memory}} pids_limit={{.HostConfig.PidsLimit}} configured_user={{.Config.User}} configured_healthcheck={{json .Config.Healthcheck}}' "$container_id" >&2 || true
      podman stats --no-stream --format 'final_stats memory={{.MemUsage}} cpu={{.CPUPerc}} pids={{.PIDs}}' "$container_id" >&2 || true
      podman logs "$container_id" 2>&1 | tail -c 16000 >&2 || true
    fi
    podman rm --force "$container_id" >/dev/null 2>&1 || true
  fi
  exit "$result"
}
trap cleanup EXIT

printf 'Starting the image default entrypoint with the isolated security profile; startup window=%ss.\n' "$wait_seconds"
app_start_seconds=$SECONDS
container_id=$(podman run -d --pull=never --platform linux/amd64 --name "$container_name" \
  --network=none --read-only --user 10001:10001 --cap-drop=ALL \
  --security-opt=no-new-privileges --pids-limit=128 --memory=512m \
  --mount type=tmpfs,dst=/tmp,tmpfs-size=16777216,tmpfs-mode=1777,U=true,noexec,nosuid,nodev \
  "$image_id")

deadline=$((SECONDS + wait_seconds))
healthy=false
state=created
while (( SECONDS < deadline )); do
  state=$(podman inspect --format '{{.State.Status}}' "$container_id")
  if [[ "$state" == exited || "$state" == stopped || "$state" == dead ]]; then
    printf 'The default-entrypoint container stopped before becoming healthy (status=%s).\n' "$state" >&2
    break
  fi
  if podman exec "$container_id" /opt/runtime/bin/java -cp /opt/healthcheck ContainerHealthCheck >/dev/null 2>&1; then
    healthy=true
    health_probe_passed=true
    break
  fi
  sleep 1
done

if [[ "$healthy" != true ]]; then
  if [[ "$state" == exited || "$state" == stopped || "$state" == dead ]]; then
    printf 'The default-entrypoint application failed before the healthcheck passed.\n' >&2
  else
    printf 'The default-entrypoint application did not become healthy within %ss.\n' "$wait_seconds" >&2
  fi
  exit 1
fi

app_logs=$(podman logs "$container_id" 2>&1)
if ! grep -Fq 'Started EmbedifyApplication' <<< "$app_logs"; then
  printf 'The health endpoint returned 200, but the expected Spring startup marker was absent.\n' >&2
  exit 1
fi
printf 'Default-entrypoint startup passed: container=%s health=200 marker=Started\ EmbedifyApplication.\n' "${container_id:0:12}"
