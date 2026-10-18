#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
if [ ! -f infra/.env ]; then cp infra/.env.example infra/.env; fi
python3 scripts/render-monitoring.py
docker compose -f infra/compose.yaml --profile full config --quiet
docker compose -f infra/compose.yaml --profile full up --build -d --wait --wait-timeout 300
