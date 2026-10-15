#!/usr/bin/env bash
set -euo pipefail

marker="${FLEETFLOW_BACKUP_SUCCESS_MARKER:-/var/backups/fleetflow/last-success.utc}"
maximum_age_hours="${FLEETFLOW_BACKUP_MAX_AGE_HOURS:-24}"
if [[ ! -r "$marker" ]]; then
  echo "FleetFlow backup success marker is missing" >&2
  exit 1
fi
if [[ ! "$maximum_age_hours" =~ ^[1-9][0-9]?$ ]] || (( maximum_age_hours > 24 )); then
  echo "FLEETFLOW_BACKUP_MAX_AGE_HOURS must be between 1 and 24" >&2
  exit 2
fi
marker_value="$(cat -- "$marker")"
if [[ ! "$marker_value" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]; then
  echo "FleetFlow backup success marker is malformed" >&2
  exit 2
fi
marker_epoch="$(date -u -d "$marker_value" +%s)"
now_epoch="$(date -u +%s)"
age_seconds=$((now_epoch - marker_epoch))
if (( age_seconds < 0 || age_seconds > maximum_age_hours * 3600 )); then
  echo "FleetFlow off-host backup is older than ${maximum_age_hours} hours" >&2
  exit 1
fi
echo "FleetFlow off-host backup age is within ${maximum_age_hours} hours"
