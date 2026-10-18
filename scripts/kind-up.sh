#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
for tool in docker kind kubectl; do command -v "$tool" >/dev/null; done
if [ ! -f infra/.env ]; then cp infra/.env.example infra/.env; fi
python3 scripts/render-monitoring.py
if ! kind get clusters | grep -qx commerceflow; then kind create cluster --config infra/kind.yaml; fi
docker exec commerceflow-control-plane sysctl -w vm.max_map_count=1048576
for module in catalog-service cart-service order-service inventory-service payment-service notification-service api-gateway; do
  docker build -f infra/Dockerfile --build-arg APP_MODULE="$module" -t "commerceflow/$module:local" .
  kind load docker-image "commerceflow/$module:local" --name commerceflow
done
docker build -f frontend/Dockerfile -t commerceflow/frontend:local .
kind load docker-image commerceflow/frontend:local --name commerceflow
KIND_API_SERVICE_IP="$(kubectl --context kind-commerceflow -n default get service kubernetes -o jsonpath='{.spec.clusterIP}')"
KIND_API_ENDPOINTS="$(kubectl --context kind-commerceflow -n default get endpointslices -l kubernetes.io/service-name=kubernetes -o jsonpath='{.items[*].endpoints[*].addresses[*]}')"
# Addresses come from this cluster's API discovery, never a broad network CIDR.
read -r -a KIND_API_ADDRESSES <<< "$KIND_API_SERVICE_IP $KIND_API_ENDPOINTS"
python3 scripts/render-api-policy.py "${KIND_API_ADDRESSES[@]}"
kubectl --context kind-commerceflow apply -k infra
kubectl --context kind-commerceflow apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.9.0/components.yaml
kubectl --context kind-commerceflow -n kube-system patch deployment metrics-server --type=json -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
for stateful in postgres kafka redis elasticsearch prometheus grafana; do
  kubectl --context kind-commerceflow -n commerceflow rollout status "statefulset/$stateful" --timeout=300s
done
kubectl --context kind-commerceflow -n commerceflow wait --for=condition=Available deployment --all --timeout=300s
kubectl --context kind-commerceflow -n commerceflow get pods,services,ingress,pvc
