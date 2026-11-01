#!/usr/bin/env python3
# Adds AI database ownership to an existing Compose volume. Refuses mismatches and preserves existing state.
"""Add the AI-owned PostgreSQL role/database to an existing local Compose volume without recreating it."""
import os
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parent.parent
password = "local-ai-only"
local = root / "infra/.env"
if local.exists():
    for line in local.read_text().splitlines():
        if line.startswith("AI_DB_PASSWORD="):
            password = line.split("=", 1)[1].strip().strip("\"'")
password = os.environ.get("AI_DB_PASSWORD", password)
# Validate the value before passing it to psql; do not interpolate it into a shell command.
if not password or any(ord(char) < 32 or ord(char) == 127 for char in password):
    raise ValueError("AI_DB_PASSWORD must be a nonempty single-line value")
literal = password.replace("'", "''")
sql = rf"""
SELECT format('CREATE ROLE ai LOGIN PASSWORD %L', '{literal}')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ai')\gexec
SELECT 'CREATE DATABASE ai OWNER ai'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='ai')\gexec
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_database d JOIN pg_roles r ON r.oid=d.datdba
                WHERE d.datname='ai' AND r.rolname='ai') THEN
  RAISE EXCEPTION 'Existing ai database is not owned by ai; refusing to change ownership';
 END IF;
END $$;
REVOKE ALL ON DATABASE ai FROM PUBLIC;
GRANT CONNECT ON DATABASE ai TO ai;
"""
subprocess.run(["docker", "compose", "-f", "infra/compose.yaml", "exec", "-T", "postgres", "psql",
                "-U", "postgres", "-d", "postgres", "--set=ON_ERROR_STOP=1"],
               cwd=root, input=sql, text=True, check=True)
print("AI database/role verified; existing role passwords and all business data preserved")
