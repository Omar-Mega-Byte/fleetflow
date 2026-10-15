#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${FLEETFLOW_ENV_FILE:-$ROOT/.env.production}"
COMPOSE_FILE="$ROOT/ops/production/compose.production.yaml"

if [[ ! -r "$ENV_FILE" ]]; then
  echo "Production environment file is missing or unreadable" >&2
  exit 2
fi

compose() {
  docker compose --env-file "$ENV_FILE" --profile public --project-name fleetflow-production \
    --file "$COMPOSE_FILE" "$@"
}

check_health() {
  local service="$1" container state deadline
  container="$(compose ps --all --quiet "$service")"
  if [[ -z "$container" ]]; then
    echo "Required service container is missing: $service" >&2
    return 1
  fi
  deadline="$readiness_deadline"
  while (( SECONDS < deadline )); do
    state="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || true)"
    case "$state" in
      healthy) return 0 ;;
      unhealthy|exited|dead)
        echo "Service did not become healthy: $service ($state)" >&2
        return 1
        ;;
    esac
    sleep 5
  done
  echo "Readiness deadline exceeded: $service" >&2
  return 1
}

case "${1:-}" in
  config)
    compose config --quiet
    ;;
  up)
    compose config --quiet
    compose up --detach --no-build
    readiness_deadline=$((SECONDS + 900))
    for service in postgres keycloak-db redis kafka keycloak app edge; do
      check_health "$service"
    done
    echo "FleetFlow containers report healthy. Verify DNS and HTTPS from an external network before opening the demo."
    ;;
  ready)
    readiness_deadline=$((SECONDS + 900))
    for service in postgres keycloak-db redis kafka keycloak app edge; do
      check_health "$service"
    done
    echo "FleetFlow containers report healthy."
    ;;
  status)
    compose ps
    ;;
  stop)
    # Stops services without deleting named data volumes.
    compose stop
    ;;
  *)
    echo "Usage: $0 {config|up|ready|status|stop}" >&2
    exit 2
    ;;
esac
