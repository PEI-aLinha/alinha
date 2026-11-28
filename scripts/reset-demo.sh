#!/usr/bin/env bash
# Puts the demo organizations (#42) back to their seed state: deletes everything the twelve demo tenants have in the
# plugin and core tables, then makes Flyway run db/demo again on the next start of the app with the `demo` profile.
#
#   ./scripts/reset-demo.sh              # database only; then restart the app (SPRING_PROFILES_ACTIVE=demo)
#   ./scripts/reset-demo.sh --keycloak   # also re-import the realm (every user must set up TOTP again)
#
# Only for local development and the demo server. Organizations registered through the wizard during the demo keep
# their data: only the tenant ids below are touched. audit.audit_event is append-only (RNF04) and is never deleted,
# so the audit history of the demo tenants stays.
#
# COMPOSE_FILE (default deploy/docker-compose.dev.yml) and POSTGRES_SERVICE (default postgres) select the database.
# Any other compose file (the demo server) also needs ALINHA_DEMO_RESET=yes, so it is never pointed at a real
# deployment by accident: the delete below skips foreign-key checks.
set -euo pipefail

cd "$(dirname "$0")/.."
compose_file="${COMPOSE_FILE:-deploy/docker-compose.dev.yml}"
service="${POSTGRES_SERVICE:-postgres}"
keycloak=false
for arg in "$@"; do
  case "$arg" in
    --keycloak) keycloak=true ;;
    # The header comment, up to the first line that is not a comment.
    -h|--help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg (see --help)" >&2; exit 2 ;;
  esac
done

if [ "$compose_file" != "deploy/docker-compose.dev.yml" ] && [ "${ALINHA_DEMO_RESET:-}" != "yes" ]; then
  echo "Refusing to reset $compose_file: set ALINHA_DEMO_RESET=yes if it is a demo environment." >&2
  exit 1
fi
echo "Resetting the demo organizations in $compose_file (service $service)."

# Same ids as deploy/keycloak/alinha-realm.json and backend/app/src/main/resources/db/demo/R__demo_core_tenants.sql.
demo_tenants="'11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222',
  '33333333-3333-4333-8333-333333333333', '44444444-4444-4444-8444-444444444444',
  '55555555-5555-4555-8555-555555555555', '66666666-6666-4666-8666-666666666666',
  '77777777-7777-4777-8777-777777777777', '88888888-8888-4888-8888-888888888888',
  '99999999-9999-4999-8999-999999999999', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'"

# Runs as the database owner (POSTGRES_USER), a superuser in the dev compose: session_replication_role = replica
# skips foreign-key checks, so the tables can be emptied in any order. Every table with a tenant_id column is
# included, so a plugin that adds one is reset too. A table that references one of them (or core.tenant) but has no
# tenant_id of its own would keep orphan rows, so the script stops before deleting anything when there is one.
docker compose -f "$compose_file" exec -T "$service" sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<SQL
begin;
set local session_replication_role = replica;
do \$\$
declare
    demo uuid[] := array[$demo_tenants]::uuid[];
    t record;
    n bigint;
begin
    for t in
        select con.conrelid::regclass as child, con.confrelid::regclass as parent
        from pg_constraint con
        join pg_class parent on parent.oid = con.confrelid
        where con.contype = 'f'
          and parent.relnamespace::regnamespace::text not in ('audit', 'pg_catalog', 'information_schema')
          and (con.confrelid = 'core.tenant'::regclass
               or exists (select 1 from pg_attribute a
                          where a.attrelid = con.confrelid and a.attname = 'tenant_id' and not a.attisdropped))
          and not exists (select 1 from pg_attribute a
                          where a.attrelid = con.conrelid and a.attname = 'tenant_id' and not a.attisdropped)
    loop
        raise exception '% references % but has no tenant_id: its rows would be left behind. Give it a tenant_id or delete it in scripts/reset-demo.sh.', t.child, t.parent;
    end loop;

    for t in
        select c.table_schema, c.table_name
        from information_schema.columns c
        join information_schema.tables tb using (table_schema, table_name)
        where c.column_name = 'tenant_id' and tb.table_type = 'BASE TABLE'
          and c.table_schema not in ('audit', 'pg_catalog', 'information_schema')
        order by 1, 2
    loop
        execute format('delete from %I.%I where tenant_id = any(\$1)', t.table_schema, t.table_name) using demo;
        get diagnostics n = row_count;
        if n > 0 then raise notice '%.%: % rows deleted', t.table_schema, t.table_name, n; end if;
    end loop;
    delete from core.tenant where id = any(demo);
    -- Repeatable scripts run again only when Flyway has no record of them. The history table is found by name, so a
    -- spring.flyway.schemas setting would not leave the old records behind.
    for t in
        select table_schema from information_schema.tables where table_name = 'flyway_schema_history'
    loop
        execute format('delete from %I.flyway_schema_history where script like %L', t.table_schema, 'R\_\_demo\_%');
    end loop;
end
\$\$;
commit;
SQL

if $keycloak; then
  docker compose -f "$compose_file" up -d --force-recreate keycloak
  echo "Keycloak realm re-imported: every demo user sets up TOTP again on the next login."
fi
echo "Demo data deleted. Restart the app with SPRING_PROFILES_ACTIVE=demo to seed it again."
