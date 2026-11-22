#!/bin/sh
# Dev and production (docker-compose.*.yml). Runs once, on a fresh pgdata volume (docker-entrypoint-initdb.d).
# Runtime role for the app: it owns nothing, so the grants in the core migrations bind it
# (audit.audit_event is INSERT/SELECT only, RNF04). Flyway still runs as the owner (POSTGRES_USER).
set -eu
# No fallback: this also runs in production, where a missing password must stop initdb, not create a weak role.
# docker-compose.dev.yml passes the dev value. The password goes in as a psql variable, quoted by psql (:'pw'):
# any character is safe. The heredoc is quoted.
psql -v ON_ERROR_STOP=1 -v pw="${ALINHA_APP_DB_PASSWORD:?ALINHA_APP_DB_PASSWORD is not set}" -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'SQL'
CREATE ROLE alinha_app LOGIN PASSWORD :'pw';
SQL
