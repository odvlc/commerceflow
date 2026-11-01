#!/usr/bin/env bash
# Runs the selected local deployment demo and evidence checks. Restarts reset in-process metric timers.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
target=compose
for argument in "$@"; do
  case "$argument" in
    --target=compose|--target=kind) target="${argument#--target=}" ;;
    --help|-h) echo 'Usage: bash scripts/ai-demo.sh [--target=compose|kind] (ready stack and seeded synthetic products required)'; exit 0 ;;
    *) echo "Unknown option: $argument" >&2; exit 2 ;;
  esac
done
export BASE_URL=http://localhost:8080
if [ "$target" = kind ]; then export BASE_URL=http://localhost:3000; fi
PYTHONPATH=scripts python3 - <<'PY'
import json,os
from urllib.request import Request,urlopen
from local_client import login
with urlopen(Request(os.environ['BASE_URL']+'/api/v1/ai/capabilities',headers={'Authorization':'Bearer '+login('alice')}),timeout=10) as response:
    assert json.load(response)['modelReady'], 'Prepare the pinned local models before the demonstration'
with urlopen(os.environ['BASE_URL']+'/api/v1/products?size=100',timeout=10) as response:
    assert any(item['sku']=='BAG-01' for item in json.load(response)['items']), 'Run documented demo.py seed on synthetic local data first'
PY
# Wait for the curated index before checking citations; otherwise a missing import could look like a
# model failure.
python3 scripts/import-ai-knowledge.py
AI_ENABLED=true E2E_BASE_URL=http://localhost:3000 npm run --prefix frontend test:e2e -- e2e/copilot.spec.ts
python3 scripts/ai-recovery-smoke.py --target="$target"
PYTHONPATH=scripts AI_DEMO_TARGET="$target" python3 - <<'PY'
import json,os,subprocess,time,uuid
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request,urlopen
from local_client import login

def query(expression):
    suffix='/api/v1/query?'+urlencode({'query':expression})
    if os.environ['AI_DEMO_TARGET']=='kind':
        result=subprocess.run(['kubectl','--context','kind-commerceflow','-n','commerceflow','exec','prometheus-0','--','wget','-q','-O','-','http://127.0.0.1:9090'+suffix],check=True,capture_output=True,text=True)
        return json.loads(result.stdout)['data']['result']
    with urlopen('http://localhost:9090'+suffix,timeout=10) as response:return json.load(response)['data']['result']

# AI process restarts reset timers. Exercise the restored instance before requesting a fresh Prometheus sample.
token=login('alice')
def call(path,body=None):
    headers={'Authorization':'Bearer '+token,'Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())}
    with urlopen(Request(os.environ['BASE_URL']+'/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers=headers),timeout=10) as response:return json.load(response)
conversation=call('/ai/conversations',{'mode':'CUSTOMER'})
run=call('/ai/conversations/'+conversation['id']+'/messages',{'content':'What is the synthetic return window? Cite the policy.'})
deadline=time.monotonic()+130
while time.monotonic()<deadline:
    state=call('/ai/runs/'+run['id'])['state']
    if state in ('SUCCEEDED','FAILED','CANCELLED'):
        assert state=='SUCCEEDED'
        break
    time.sleep(.25)
else:raise TimeoutError('Post-restart investigation did not complete')
deadline=time.monotonic()+45
while time.monotonic()<deadline:
    targets=query('up{job="commerceflow"}')
    first_token=query('sum(commerceflow_ai_model_first_token_seconds_count)')
    if len(targets)==8 and all(item['value'][1]=='1' for item in targets) and first_token and float(first_token[0]['value'][1])>0:break
    time.sleep(1)
else:raise AssertionError('Fresh post-restart authenticated targets/first-token samples did not become available')
Path('.cache/verification/ai-demo-metrics.json').write_text(json.dumps({'targets':targets,'backendFirstTokenCount':first_token},indent=2)+'\n')
print('Cited policy, real product comparison, order explanation, exact/stale/rejected cart proposals, restart/reauthentication and live AI metrics verified')
PY
