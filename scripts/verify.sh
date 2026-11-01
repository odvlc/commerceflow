#!/usr/bin/env bash
# Runs backend Maven verification and prepares native test tools when Redis is unavailable.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
if [ -z "${REDIS_SERVER:-}" ]; then bash scripts/bootstrap-test-tools.sh; fi
# Forward Maven arguments unchanged so requested integration profiles run as supplied.
./mvnw -B verify "$@"
