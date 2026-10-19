#!/usr/bin/env bash
# Explicit destructive teardown of the named local cluster, including its PVC data; do not use as
# a recovery step.
set -euo pipefail
# This explicit teardown destroys the local cluster and its PVC data.
kind delete cluster --name commerceflow
if docker inspect commerceflow-kind-model-proxy >/dev/null 2>&1; then
  # Remove only the task-owned inference proxy after deleting the named cluster; unrelated Docker
  # containers remain untouched.
  docker rm -f commerceflow-kind-model-proxy >/dev/null
fi
