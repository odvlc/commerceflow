#!/usr/bin/env bash
# Runs backend Maven verification and bootstraps native test dependencies when no Redis executable
# is supplied.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
if [ -z "${REDIS_SERVER:-}" ]; then bash scripts/bootstrap-test-tools.sh; fi
# Forward explicit Maven arguments unchanged so requested integration profiles remain visible in the
# verification command.
./mvnw -B verify "$@"
