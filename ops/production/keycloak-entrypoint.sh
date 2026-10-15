#!/bin/sh
set -eu

secret_file=/run/secrets/keycloak_db_password
if [ ! -r "$secret_file" ]; then
  echo "Keycloak database password file is missing or unreadable" >&2
  exit 1
fi
KC_DB_PASSWORD="$(cat "$secret_file")"
if [ -z "$KC_DB_PASSWORD" ]; then
  echo "Keycloak database password file is empty" >&2
  exit 1
fi
export KC_DB_PASSWORD
admin_secret_file=/run/secrets/keycloak_admin_password
if [ ! -r "$admin_secret_file" ]; then
  echo "Keycloak bootstrap administrator password file is missing or unreadable" >&2
  exit 1
fi
KC_BOOTSTRAP_ADMIN_PASSWORD="$(cat "$admin_secret_file")"
if [ -z "$KC_BOOTSTRAP_ADMIN_PASSWORD" ]; then
  echo "Keycloak bootstrap administrator password file is empty" >&2
  exit 1
fi
export KC_BOOTSTRAP_ADMIN_PASSWORD
exec /opt/keycloak/bin/kc.sh "$@"
