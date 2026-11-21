#!/bin/sh
# Production only (docker-compose.prod.yml). Runs once, on a fresh pgdata volume.
# Keycloak gets its own database and role, so it never touches the platform schemas.
set -eu
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD is required}"
# The password goes in as a psql variable and is quoted by psql (:'pw'), never spliced into the SQL by the shell,
# so any character is safe. The quoted heredoc stops the shell from expanding anything inside it.
psql -v ON_ERROR_STOP=1 -v pw="$KEYCLOAK_DB_PASSWORD" -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'SQL'
CREATE ROLE keycloak LOGIN PASSWORD :'pw';
CREATE DATABASE keycloak OWNER keycloak;
SQL
