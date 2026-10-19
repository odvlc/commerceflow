#!/usr/bin/env bash
# Combines browser workflows, exact stock checks and monitoring evidence against the native local
# demonstration stack.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
mkdir -p .cache/verification
python3 scripts/demo.py seed
npm run --prefix frontend test:e2e
# Verify exact committed/released quantities after browser workflows, not just the visible success
# messages.
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
