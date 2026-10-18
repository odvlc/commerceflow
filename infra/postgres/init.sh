#!/usr/bin/env bash
set -euo pipefail
# Invoked by the official PostgreSQL image only for an empty data volume.
for service in catalog order inventory payment notification keycloak; do
  password_name="${service^^}_DB_PASSWORD"
  psql --username "$POSTGRES_USER" --dbname postgres --set=ON_ERROR_STOP=1 \
    --set=service="$service" --set=password="${!password_name}" <<'SQL'
CREATE ROLE :"service" LOGIN PASSWORD :'password';
CREATE DATABASE :"service" OWNER :"service";
REVOKE ALL ON DATABASE :"service" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"service" TO :"service";
SQL
done
