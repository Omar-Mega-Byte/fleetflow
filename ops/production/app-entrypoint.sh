#!/bin/sh
set -eu

if [ -n "${DB_PASSWORD_FILE:-}" ]; then
  if [ ! -r "$DB_PASSWORD_FILE" ]; then
    echo "DB password file is missing or unreadable" >&2
    exit 1
  fi
  DB_PASSWORD="$(cat "$DB_PASSWORD_FILE")"
  if [ -z "$DB_PASSWORD" ]; then
    echo "DB password file is empty" >&2
    exit 1
  fi
  export DB_PASSWORD
  unset DB_PASSWORD_FILE
fi

exec java -jar /app/app.jar "$@"
