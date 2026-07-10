#!/bin/sh
# Brings an EXISTING dev database up to the gateway-owned webhook design, IN PLACE,
# without touching orchestrator data. Idempotent — safe to run more than once.
#
#   1. creates the schema-scoped `gateway` role (no access to the orchestrator schema)
#   2. creates the `gateway` schema owned by it (the gateway's Flyway fills in tables)
#   3. removes the orphaned orchestrator.webhook_repo (the registry moved to the gateway)
#
# Run from the repo root:  ./infra/migrate-existing-db.sh
set -e
set -a; . ./.env; set +a

docker exec -i -e PGPASSWORD="$POSTGRES_PASSWORD" spire-postgres \
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${GATEWAY_POSTGRES_USER}') THEN
    CREATE ROLE "${GATEWAY_POSTGRES_USER}" LOGIN PASSWORD '${GATEWAY_POSTGRES_PASSWORD}';
  END IF;
END \$\$;
CREATE SCHEMA IF NOT EXISTS gateway AUTHORIZATION "${GATEWAY_POSTGRES_USER}";
DROP TABLE IF EXISTS orchestrator.webhook_repo;
DELETE FROM orchestrator.flyway_schema_history WHERE version = '14';
SQL

echo "migration complete"
