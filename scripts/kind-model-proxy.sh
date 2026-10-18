#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
# No host port is published. This fixed inference-only proxy lives on the local kind Docker network.
# It also handles existing Calico 192.168/16 pools that overlap Docker Desktop's host gateway address.
if docker inspect commerceflow-kind-model-proxy >/dev/null 2>&1; then
  docker start commerceflow-kind-model-proxy >/dev/null
else
  docker run -d --name commerceflow-kind-model-proxy --network kind --user 101:101 \
    --read-only --tmpfs /tmp:rw,nosuid,nodev,size=16m --cap-drop ALL \
    --security-opt no-new-privileges --memory 64m --cpus 0.25 --pids-limit 64 \
    --mount "type=bind,source=$PROJECT_ROOT/infra/ai/host-proxy.conf,target=/etc/nginx/nginx.conf,readonly" \
    --entrypoint nginx nginx:1.30-alpine -g 'daemon off;' >/dev/null
fi
for attempt in $(seq 1 30); do
  if docker exec commerceflow-kind-model-proxy wget -q -O /dev/null http://127.0.0.1:11434/api/tags; then
    docker exec commerceflow-kind-model-proxy nginx -s reload
    exit 0
  fi
  sleep 1
done
docker logs commerceflow-kind-model-proxy --tail=10 >&2
exit 1
