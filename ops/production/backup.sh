#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${FLEETFLOW_ENV_FILE:-$ROOT/.env.production}"
COMPOSE_FILE="$ROOT/ops/production/compose.production.yaml"
BACKUP_DIR="${FLEETFLOW_BACKUP_DIR:-/var/backups/fleetflow}"

fail() { echo "$1" >&2; exit 1; }
[[ -r "$ENV_FILE" ]] || fail "Production environment file is missing or unreadable"
command -v docker >/dev/null || fail "Docker Compose is required"
command -v age >/dev/null || fail "age is required for encryption"
command -v rclone >/dev/null || fail "rclone is required for the off-host copy"
command -v flock >/dev/null || fail "flock is required to serialize production backups"
command -v python3 >/dev/null || fail "Python 3 is required to validate release metadata"

# Parse only expected, non-secret release values. Do not source the Compose env file as shell code.
read_value() {
  local name="$1" value
  value="$(awk -F= -v name="$name" '$1 == name { sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit }' "$ENV_FILE")"
  printf '%s' "$value"
}

recipient="$(read_value BACKUP_AGE_RECIPIENT)"
remote="$(read_value BACKUP_REMOTE_DESTINATION)"
source_commit="$(read_value FLEETFLOW_SOURCE_COMMIT)"
app_image="$(read_value FLEETFLOW_APP_IMAGE)"
identity_image="$(read_value FLEETFLOW_KEYCLOAK_RUNTIME)"
edge_image="$(read_value FLEETFLOW_EDGE_IMAGE)"
node_image="$(read_value FLEETFLOW_NODE_BUILD_IMAGE)"
nginx_image="$(read_value FLEETFLOW_NGINX_RUNTIME_IMAGE)"
postgres_image="$(read_value FLEETFLOW_POSTGIS_IMAGE)"
redis_image="$(read_value FLEETFLOW_REDIS_IMAGE)"
kafka_image="$(read_value FLEETFLOW_KAFKA_IMAGE)"
metadata_dir="$(read_value FLEETFLOW_RELEASE_METADATA_DIR)"
demo_host="$(read_value DEMO_HOST)"
identity_host="$(read_value IDENTITY_HOST)"
for value in "$app_image" "$identity_image" "$edge_image" "$node_image" "$nginx_image" "$postgres_image" "$redis_image" "$kafka_image"; do
  [[ "$value" =~ @sha256:[0-9a-f]{64}$ ]] || fail "All recorded application images must be immutable SHA-256 digests"
  [[ "$value" != *@sha256:0000000000000000000000000000000000000000000000000000000000000000 ]] || fail "Placeholder image digests cannot be backed up as a release"
done
[[ "$source_commit" =~ ^[0-9a-f]{40}$ ]] || fail "FLEETFLOW_SOURCE_COMMIT must be a full Git commit"
[[ "$recipient" =~ ^age1[0-9a-z]+$ ]] || fail "BACKUP_AGE_RECIPIENT must be an age public recipient"
[[ "$remote" =~ ^[A-Za-z0-9._-]+:[A-Za-z0-9._/-]+$ && "$remote" != *..* ]] || fail "BACKUP_REMOTE_DESTINATION must be a dedicated rclone remote path"
[[ -n "$demo_host" && -n "$identity_host" ]] || fail "Stable demo and identity hosts are required"
[[ -n "$metadata_dir" && -d "$metadata_dir" ]] || fail "Release metadata directory is missing"
[[ -r "$metadata_dir/candidate.json" ]] || fail "Release metadata must include candidate.json"
compgen -G "$metadata_dir/*sbom*.json" >/dev/null || fail "Release metadata must include a CycloneDX or SPDX SBOM"
python3 - "$metadata_dir/candidate.json" "$source_commit" "$app_image" "$edge_image" "$identity_image" "$postgres_image" "$redis_image" "$kafka_image" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as source:
    candidate = json.load(source)
if candidate.get("sourceCommit") != sys.argv[2] or any(candidate.get("images", {}).get(key) != value
        for key, value in zip(("app", "edge", "keycloakRuntime", "postgres", "redis", "kafka"), sys.argv[3:])):
    raise SystemExit("Candidate metadata does not match the deployed release")
if not candidate.get("ciRun") or not candidate.get("scanReports") or not candidate.get("sbom"):
    raise SystemExit("Candidate metadata is missing CI, scanner or SBOM provenance")
PY

root_real="$(realpath -m "$ROOT")"
backup_real="$(realpath -m "$BACKUP_DIR")"
case "$backup_real" in
  "$root_real"|"$root_real"/*) fail "Backup destination must be outside the repository" ;;
esac
mkdir -p -- "$BACKUP_DIR"
chmod 0700 "$BACKUP_DIR"
exec 9>"$BACKUP_DIR/backup.lock"
flock -n 9 || fail "A production backup is already active"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/fleetflow-backup.XXXXXXXX")"
stopped=0
verified_backup_at=""
cleanup() {
  local result=$?
  set +e
  trap - EXIT INT TERM
  if (( stopped )); then
    if ! compose start keycloak app edge >/dev/null; then
      echo "WARNING: service restart needs operator attention" >&2
      if [[ "$result" -eq 0 ]]; then result=1; fi
    else
      local deadline=$((SECONDS + 900))
      local service container state="missing"
      for service in keycloak app edge; do
        state="missing"
        container="$(compose ps --all --quiet "$service" 2>/dev/null || true)"
        while [[ -n "$container" ]] && (( SECONDS < deadline )); do
          state="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || true)"
          [[ "$state" == healthy ]] && break
          if [[ "$state" == unhealthy || "$state" == exited || "$state" == dead ]]; then break; fi
          sleep 5
        done
        if [[ -z "$container" || "$state" != healthy ]]; then
          echo "WARNING: service restart needs operator attention" >&2
          if [[ "$result" -eq 0 ]]; then result=1; fi
          break
        fi
      done
    fi
  fi
  if [[ "$result" -eq 0 && -n "$verified_backup_at" ]]; then
    if ! printf '%s\n' "$verified_backup_at" > "$BACKUP_DIR/last-success.utc.tmp" \
        || ! mv -- "$BACKUP_DIR/last-success.utc.tmp" "$BACKUP_DIR/last-success.utc"; then
      echo "Backup verified but success marker could not be recorded" >&2
      result=1
    fi
  fi
  if [[ -n "$scratch" && -d "$scratch" && "$scratch" == "${TMPDIR:-/tmp}"/fleetflow-backup.* ]]; then
    rm -rf -- "$scratch"
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

compose() {
  docker compose --env-file "$ENV_FILE" --profile public --project-name fleetflow-production \
    --file "$COMPOSE_FILE" "$@"
}

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
payload="$scratch/payload"
mkdir -m 0700 "$payload"
for service in app edge keycloak postgres keycloak-db redis kafka; do
  case "$service" in app) expected="$app_image" ;; edge) expected="$edge_image" ;; keycloak) expected="$identity_image" ;;
    postgres|keycloak-db) expected="$postgres_image" ;; redis) expected="$redis_image" ;; kafka) expected="$kafka_image" ;; esac
  container="$(compose ps --quiet "$service")"
  [[ -n "$container" ]] || fail "Required release service is not running: $service"
  [[ "$(docker inspect --format '{{.Config.Image}}' "$container")" == "$expected" ]] \
    || fail "Running service image differs from candidate metadata: $service"
done
stopped=1
compose stop edge app keycloak >/dev/null

compose exec -T postgres sh -ec 'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  | age --recipient "$recipient" --output "$payload/postgres.dump.age"
compose exec -T keycloak-db sh -ec 'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  | age --recipient "$recipient" --output "$payload/keycloak.dump.age"

{
  printf 'format=FleetFlow production recovery set v1\n'
  printf 'created_utc=%s\nsource_commit=%s\n' "$timestamp" "$source_commit"
  printf 'demo_host=%s\nidentity_host=%s\n' "$demo_host" "$identity_host"
  printf 'app_image=%s\nkeycloak_runtime_image=%s\nedge_image=%s\n' "$app_image" "$identity_image" "$edge_image"
  printf 'node_build_image=%s\nnginx_runtime_image=%s\n' "$node_image" "$nginx_image"
  printf 'postgres_image=%s\nredis_image=%s\nkafka_image=%s\n' "$postgres_image" "$redis_image" "$kafka_image"
  for service in postgres keycloak-db app keycloak edge redis kafka; do
    container="$(compose ps --all --quiet "$service")"
    [[ -n "$container" ]] || fail "Recovery set is missing service container: $service"
    printf '%s_image_id=%s\n' "$service" "$(docker inspect --format '{{.Image}}' "$container")"
  done
} > "$payload/manifest.txt"

install -m 0644 "$COMPOSE_FILE" "$payload/compose.production.yaml"
install -m 0644 "$ROOT/web/nginx/fleetflow.conf.template" "$payload/fleetflow.conf.template"
install -m 0644 "$ROOT/web/nginx/proxy-headers.conf" "$payload/proxy-headers.conf"
install -m 0644 "$ROOT/ops/production/postgres-app-role.sh" "$payload/postgres-app-role.sh"
install -m 0644 "$ROOT/Dockerfile" "$payload/Dockerfile.app"
for service in keycloak postgis redis kafka; do
  install -m 0644 "$ROOT/ops/production/$service.Dockerfile" "$payload/$service.Dockerfile"
done
install -m 0644 "$ROOT/ops/production/app-entrypoint.sh" "$payload/app-entrypoint.sh"
install -m 0644 "$ROOT/ops/production/keycloak-entrypoint.sh" "$payload/keycloak-entrypoint.sh"
install -m 0644 "$ROOT/web/Dockerfile.release" "$payload/Dockerfile.edge"
install -m 0644 "$ROOT/.env.production.example" "$payload/env.production.example"
for report in "$metadata_dir"/candidate.json "$metadata_dir"/*sbom*.json \
  "$metadata_dir"/trivy-*.sarif "$metadata_dir"/codeql-*.sarif; do
  [[ -f "$report" && ! -L "$report" ]] || continue
  install -m 0644 "$report" "$payload/$(basename -- "$report")"
done

archive="$BACKUP_DIR/fleetflow-production-$timestamp.tar.gz.age"
[[ ! -e "$archive" ]] || fail "Refusing to overwrite an existing recovery set"
tar -C "$payload" -czf - . | age --recipient "$recipient" --output "$archive"
(cd "$BACKUP_DIR" && sha256sum -- "$(basename -- "$archive")") > "$archive.sha256"

remote_file="${remote%/}/$(basename -- "$archive")"
rclone copyto "$archive" "$remote_file" --no-traverse --retries 2
retrieved="$scratch/remote-retrieved.tar.gz.age"
rclone copyto "$remote_file" "$retrieved" --retries 2
rclone copyto "$archive.sha256" "$remote_file.sha256" --no-traverse --retries 2
local_hash="$(sha256sum "$archive" | awk '{print $1}')"
remote_hash="$(sha256sum "$retrieved" | awk '{print $1}')"
[[ "$local_hash" == "$remote_hash" ]] || fail "Off-host backup retrieval hash mismatch"
verified_backup_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'Encrypted production recovery set: %s\nOff-host SHA-256 verified: %s\n' "$archive" "$local_hash"
