#!/usr/bin/env bash
# Validates and starts the selected local profiles without resetting volumes. Prepare models separately.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
mode=off
for argument in "$@"; do
  case "$argument" in
    --ai=off|--ai=host|--ai=container) mode="${argument#--ai=}" ;;
    --help|-h)
      echo 'Usage: bash scripts/compose-up.sh [--ai=off|host|container]'
      echo 'Models must be prepared explicitly first; see docs/ai-operations.md.'
      exit 0 ;;
    *) echo "Unknown option: $argument" >&2; exit 2 ;;
  esac
done
if [ ! -f infra/.env ]; then cp infra/.env.example infra/.env; fi
# Share the feature flag with gateway routing and the optional service profile.
export AI_ENABLED=false
profiles=(--profile full)
if [ "$mode" != off ]; then
  export AI_ENABLED=true
  # Enable the AI application profile. Host mode uses native Ollama, so it needs no model profile.
  profiles+=(--profile ai)
  if [ "$mode" = host ]; then
    # Read the configured endpoint without sourcing arbitrary shell from dotenv.
    AI_OLLAMA_URL="$(python3 scripts/check-container-models.py --endpoint-only)"
    export AI_OLLAMA_URL
    python3 scripts/check-container-models.py "$AI_OLLAMA_URL"
  else
    export AI_OLLAMA_URL=http://ollama:11434
    profiles+=(--profile ai-model)
    docker compose -f infra/compose.yaml --profile ai-model up -d --wait --wait-timeout 180 ollama
    # Startup checks pinned models and warms them. Download models during preparation.
    OLLAMA_BASE_URL=http://127.0.0.1:11435 python3 scripts/prepare-ai-models.py --check --warmup
  fi
fi
python3 scripts/render-monitoring.py
docker compose -f infra/compose.yaml "${profiles[@]}" config --quiet
if [ "$mode" != off ]; then
  docker compose -f infra/compose.yaml up -d --wait --wait-timeout 120 postgres
  # Init scripts skip existing volumes, so add the optional database separately.
  python3 scripts/provision-ai-database.py
else
  # Turning AI off removes gateway routes and stops optional workers while retaining data.
  docker compose -f infra/compose.yaml --profile full --profile ai --profile ai-model stop ai-service ollama
fi
COMPOSE_PARALLEL_LIMIT=1 docker compose -f infra/compose.yaml "${profiles[@]}" up --build -d --wait --wait-timeout 300
# Reload Prometheus after changing its bind-mounted configuration.
docker compose -f infra/compose.yaml --profile observability up -d --no-deps --force-recreate --wait --wait-timeout 120 prometheus
