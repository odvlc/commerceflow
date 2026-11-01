#!/usr/bin/env bash
# Builds or loads local images and applies the selected overlay. Existing networking and data remain in place.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
mode=off
build=true
for argument in "$@"; do
  case "$argument" in
    --ai=off|--ai=host|--ai=container) mode="${argument#--ai=}" ;;
    --skip-build) build=false ;;
    --help|-h)
      echo 'Usage: bash scripts/kind-up.sh [--ai=off|host|container] [--skip-build]'
      echo 'Local only; CPU model downloads require explicit python3 scripts/kind-ai-models.py.'
      exit 0 ;;
    *) echo "Unknown option: $argument" >&2; exit 2 ;;
  esac
done
for tool in docker kind kubectl; do command -v "$tool" >/dev/null; done
mkdir -p .cache/verification .cache/tools
if [ ! -f infra/.env ]; then cp infra/.env.example infra/.env; fi
if [ "$mode" != off ]; then
  python3 - <<'PY'
from pathlib import Path
local=Path('infra/.env')
if not any(line.startswith('AI_DB_PASSWORD=') for line in local.read_text().splitlines()):
    with local.open('a') as output: output.write('\nAI_DB_PASSWORD=local-ai-only\n')
PY
fi
export AI_ENABLED=false
if [ "$mode" != off ]; then export AI_ENABLED=true; fi
python3 scripts/render-monitoring.py
if ! kind get clusters | grep -qx commerceflow; then
  kind create cluster --config infra/kind.yaml
fi
# An existing kindnet cluster does not enforce these policies. A cluster with another CNI needs an explicit
# migration; replacing it would invalidate policy evidence.
if kubectl --context kind-commerceflow -n kube-system get daemonset kindnet >/dev/null 2>&1; then
  echo 'Existing cluster uses kindnet; use a separately reviewed migration to Calico. No cluster/data erased.' >&2
  exit 1
fi
if ! kubectl --context kind-commerceflow -n kube-system get daemonset calico-node >/dev/null 2>&1; then
  curl -fsSL https://raw.githubusercontent.com/projectcalico/calico/v3.33.0/manifests/calico.yaml -o .cache/tools/calico-v3.33.0.yaml
  kubectl --context kind-commerceflow apply --server-side -f .cache/tools/calico-v3.33.0.yaml
fi
kubectl --context kind-commerceflow -n kube-system rollout status daemonset/calico-node --timeout=300s
kubectl --context kind-commerceflow wait --for=condition=Ready node --all --timeout=300s
docker exec commerceflow-control-plane sysctl -w vm.max_map_count=1048576
modules=(catalog-service cart-service order-service inventory-service payment-service notification-service api-gateway)
if [ "$mode" != off ]; then modules+=(ai-service); fi
for module in "${modules[@]}"; do
  if [ "$build" = true ]; then docker build -f infra/Dockerfile --build-arg APP_MODULE="$module" -t "commerceflow/$module:local" .; fi
  # Check for the image even when build is skipped. Do not rely on a registry fallback for a missing local tag.
  docker image inspect "commerceflow/$module:local" >/dev/null
  kind load docker-image "commerceflow/$module:local" --name commerceflow
done
if [ "$build" = true ]; then docker build -f frontend/Dockerfile -t commerceflow/frontend:local .; fi
kind load docker-image commerceflow/frontend:local --name commerceflow
KIND_API_SERVICE_IP="$(kubectl --context kind-commerceflow -n default get service kubernetes -o jsonpath='{.spec.clusterIP}')"
KIND_API_ENDPOINTS="$(kubectl --context kind-commerceflow -n default get endpointslices -l kubernetes.io/service-name=kubernetes -o jsonpath='{.items[*].endpoints[*].addresses[*]}')"
read -r -a KIND_API_ADDRESSES <<< "$KIND_API_SERVICE_IP $KIND_API_ENDPOINTS"
python3 scripts/render-api-policy.py "${KIND_API_ADDRESSES[@]}"
target=infra
if [ "$mode" != host ] && docker inspect commerceflow-kind-model-proxy >/dev/null 2>&1; then
  docker stop commerceflow-kind-model-proxy >/dev/null
fi
if [ "$mode" != off ]; then
  python3 scripts/render-ai-kubernetes.py
  if [ "$mode" = host ]; then
    bash scripts/kind-model-proxy.sh
    python3 scripts/render-ai-host.py
    target=deploy/ai/host
    if kubectl --context kind-commerceflow -n commerceflow get statefulset ollama >/dev/null 2>&1; then
      kubectl --context kind-commerceflow -n commerceflow scale statefulset/ollama --replicas=0
    fi
  else
    target=deploy/ai/cpu
    kubectl --context kind-commerceflow -n commerceflow delete endpointslice/ollama-native networkpolicy/ai-native-model-endpoint --ignore-not-found
  fi
fi
if [ "$mode" != off ]; then
  # This schema-owner Job is disposable. It does not remove database or PVC data.
  kubectl --context kind-commerceflow -n commerceflow delete job/ai-db-provision-v1 --ignore-not-found --wait=true
fi
kubectl --context kind-commerceflow apply -k "$target"
if [ "$mode" = off ]; then
  for workload in deployment/ai-service statefulset/ollama; do
    if kubectl --context kind-commerceflow -n commerceflow get "$workload" >/dev/null 2>&1; then
      kubectl --context kind-commerceflow -n commerceflow scale "$workload" --replicas=0
    fi
  done
fi
kubectl --context kind-commerceflow apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.9.0/components.yaml
if ! kubectl --context kind-commerceflow -n kube-system get deployment metrics-server -o jsonpath='{.spec.template.spec.containers[0].args}' | grep -q -- --kubelet-insecure-tls; then
  kubectl --context kind-commerceflow -n kube-system patch deployment metrics-server --type=json -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
fi
for stateful in postgres kafka redis elasticsearch prometheus grafana; do
  kubectl --context kind-commerceflow -n commerceflow rollout status "statefulset/$stateful" --timeout=300s
done
if [ "$mode" != off ]; then
  kubectl --context kind-commerceflow -n commerceflow wait --for=condition=complete job/ai-db-provision-v1 --timeout=180s
fi
# CPU startup checks prepared models. Run the separate preparation job to install weights.
if [ "$mode" = container ]; then
  kubectl --context kind-commerceflow -n commerceflow rollout status statefulset/ollama --timeout=300s
  python3 scripts/kind-ai-models.py --check
fi
kubectl --context kind-commerceflow -n commerceflow wait --for=condition=Available deployment --all --timeout=300s
kubectl --context kind-commerceflow -n commerceflow rollout restart statefulset/prometheus
kubectl --context kind-commerceflow -n commerceflow rollout status statefulset/prometheus --timeout=180s
kubectl --context kind-commerceflow -n commerceflow get pods,services,ingress,pvc
