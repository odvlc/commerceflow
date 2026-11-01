#!/usr/bin/env bash
# Restarts selected local workloads and checks an existing terminal order. Requires a customer token.
set -euo pipefail
: "${ACCESS_TOKEN:?Set a local CUSTOMER access token}"
: "${ORDER_ID:?Set an existing terminal order UUID}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$PROJECT_ROOT/.cache/verification"
BASE_URL="${BASE_URL:-http://localhost:3000}"
curl -fsS -H "Authorization: Bearer $ACCESS_TOKEN" "$BASE_URL/api/v1/orders/$ORDER_ID" > "$PROJECT_ROOT/.cache/verification/order-before.json"
# Restart the application without deleting PostgreSQL/PVC state, then compare order fields after readiness returns.
kubectl --context kind-commerceflow -n commerceflow rollout restart deployment/order-service
kubectl --context kind-commerceflow -n commerceflow rollout status deployment/order-service --timeout=180s
curl -fsS -H "Authorization: Bearer $ACCESS_TOKEN" "$BASE_URL/api/v1/orders/$ORDER_ID" > "$PROJECT_ROOT/.cache/verification/order-after.json"
python3 - "$PROJECT_ROOT" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1])/'.cache/verification'
before=json.loads((p/'order-before.json').read_text());after=json.loads((p/'order-after.json').read_text())
assert before['status'] in ['CONFIRMED','CANCELLED'], 'Use a terminal order for this durability check'
for field in ['id','customerId','status','totalAmount','currency','items']:
    assert before[field]==after[field], f'Durable field changed: {field}'
print('Verified durable order after Kubernetes service restart')
PY
