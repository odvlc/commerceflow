#!/usr/bin/env bash
set -euo pipefail
# This explicit teardown destroys the local cluster and its PVC data.
kind delete cluster --name commerceflow
if docker inspect commerceflow-kind-model-proxy >/dev/null 2>&1; then
  docker rm -f commerceflow-kind-model-proxy >/dev/null
fi
