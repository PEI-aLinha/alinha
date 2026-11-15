#!/bin/sh
# Dev only. Runs once, on a fresh pgdata volume (docker-entrypoint-initdb.d).
# Runtime role for the app: it owns nothing, so the grants in the core migrations bind it
# (audit.audit_event is INSERT/SELECT only, RNF04). Flyway still runs as the owner (POSTGRES_USER).
set -eu
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<SQL
CREATE ROLE alinha_app LOGIN PASSWORD '${ALINHA_APP_DB_PASSWORD:-alinha_app}';
SQL
