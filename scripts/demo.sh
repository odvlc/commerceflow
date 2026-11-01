#!/usr/bin/env bash
# Runs browser workflows, stock checks, and monitoring checks against the local demo stack.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
mkdir -p .cache/verification
python3 scripts/demo.py seed
npm run --prefix frontend test:e2e
# Verify committed and released quantities after each browser workflow.
python3 scripts/demo.py verify
python3 scripts/metrics-snapshot.py
curl -fsS -u local-admin:local-grafana-only http://localhost:3001/api/search > .cache/verification/demo-dashboards.json
python3 - <<'PY'
import json
from pathlib import Path
dashboards=json.loads(Path('.cache/verification/demo-dashboards.json').read_text())
assert {'Service Health','Order Processing'} <= {d['title'] for d in dashboards}
print('Verified purchase, decline compensation, search, cart/cache, live updates and provisioned dashboards')
PY
