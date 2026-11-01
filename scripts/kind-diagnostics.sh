#!/usr/bin/env bash
# Collects workload status and recent logs from the named local context. It does not change resources.
set -euo pipefail
kubectl --context kind-commerceflow -n commerceflow get pods,services,ingress,pvc,hpa
kubectl --context kind-commerceflow -n commerceflow get events --sort-by=.lastTimestamp
for service in catalog-service cart-service order-service inventory-service payment-service notification-service api-gateway ai-service; do
  # Skip missing optional AI deployments and collect diagnostics for installed services.
  if ! kubectl --context kind-commerceflow -n commerceflow get "deployment/$service" >/dev/null 2>&1; then continue; fi
  kubectl --context kind-commerceflow -n commerceflow logs "deployment/$service" --tail=50
done

kubectl --context kind-commerceflow -n commerceflow get jobs,networkpolicies,endpointslices
