#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
if [ -z "${REDIS_SERVER:-}" ]; then bash scripts/bootstrap-test-tools.sh; fi
./mvnw -B verify "$@"
