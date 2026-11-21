#!/bin/sh
# Production only (docker-compose.prod.yml). Runs once, on a fresh pgdata volume.
# Keycloak gets its own database and role, so it never touches the platform schemas.
set -eu
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD is required}"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<SQL
CREATE ROLE keycloak LOGIN PASSWORD '${KEYCLOAK_DB_PASSWORD}';
CREATE DATABASE keycloak OWNER keycloak;
SQL
