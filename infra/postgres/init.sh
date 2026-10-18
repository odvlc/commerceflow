#!/usr/bin/env bash
set -euo pipefail
POSTGRES_USER="${POSTGRES_USER:-postgres}"
# Invoked by the official image for a new volume; safe to rerun explicitly after interrupted initialization.
for service in catalog order inventory payment notification keycloak ai; do
  # The AI database is optional in the Kubernetes base; its overlay supplies owned credentials.
  if [ "$service" = ai ] && [ -z "${AI_DB_PASSWORD:-}" ]; then continue; fi
  password_name="${service^^}_DB_PASSWORD"
  psql --username "$POSTGRES_USER" --dbname postgres --set=ON_ERROR_STOP=1 \
    --set=service="$service" --set=password="${!password_name}" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'service', :'password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=:'service')\gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'service', :'service')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname=:'service')\gexec
SELECT EXISTS (SELECT 1 FROM pg_database d JOIN pg_roles r ON r.oid=d.datdba
               WHERE d.datname=:'service' AND r.rolname=:'service') AS owned\gset
\if :owned
\else
\echo Existing service database has a different owner; refusing ownership changes
\quit 1
\endif
REVOKE ALL ON DATABASE :"service" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"service" TO :"service";
SQL
done
