#!/bin/sh
set -eu

# Runs only during initialization of a fresh PostgreSQL volume, after PostGIS setup.
# psql reads the password through its environment; it is never a command argument.
FLEETFLOW_APP_PASSWORD="$(cat /run/secrets/app_db_password)"
test -n "$FLEETFLOW_APP_PASSWORD"
export FLEETFLOW_APP_PASSWORD
psql --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<'SQL'
\getenv app_user FLEETFLOW_APP_USER
\getenv app_password FLEETFLOW_APP_PASSWORD
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'app_user') \gexec
SELECT format('ALTER SCHEMA public OWNER TO %I', :'app_user') \gexec
SQL
unset FLEETFLOW_APP_PASSWORD
