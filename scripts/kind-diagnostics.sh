#!/usr/bin/env bash
set -euo pipefail
kubectl --context kind-commerceflow -n commerceflow get pods,services,ingress,pvc,hpa
kubectl --context kind-commerceflow -n commerceflow get events --sort-by=.lastTimestamp
for service in catalog-service cart-service order-service inventory-service payment-service notification-service api-gateway ai-service; do
  if ! kubectl --context kind-commerceflow -n commerceflow get "deployment/$service" >/dev/null 2>&1; then continue; fi
  kubectl --context kind-commerceflow -n commerceflow logs "deployment/$service" --tail=50
done

kubectl --context kind-commerceflow -n commerceflow get jobs,networkpolicies,endpointslices
