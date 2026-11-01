#!/usr/bin/env bash
# Destructively removes the named local cluster and its PVC data. Use recovery scripts to restart workloads.
set -euo pipefail
kind delete cluster --name commerceflow
if docker inspect commerceflow-kind-model-proxy >/dev/null 2>&1; then
  # Remove the task-owned inference proxy. Leave unrelated Docker containers untouched.
  docker rm -f commerceflow-kind-model-proxy >/dev/null
fi
