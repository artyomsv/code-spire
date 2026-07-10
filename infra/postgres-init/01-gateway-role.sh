#!/bin/sh
# Creates a NON-superuser role for the gateway that owns ONLY the `gateway` schema
# (its per-repo webhook registry). It is never granted access to the `orchestrator`
# schema, so a compromised internet-facing gateway cannot read the encrypted SCM/LLM
# API-token registry or the event store — it can only reach its own webhook secrets.
#
# Runs once, on a FRESH data volume (docker-entrypoint-initdb.d). For an EXISTING
# volume, run the same two statements manually (see .env.example / SMOKE-TEST.md).
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  CREATE ROLE "${GATEWAY_POSTGRES_USER}" LOGIN PASSWORD '${GATEWAY_POSTGRES_PASSWORD}';
  CREATE SCHEMA IF NOT EXISTS gateway AUTHORIZATION "${GATEWAY_POSTGRES_USER}";
EOSQL
