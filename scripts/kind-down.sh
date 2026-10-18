#!/usr/bin/env bash
set -euo pipefail
# This explicit teardown destroys the local cluster and its PVC data.
kind delete cluster --name commerceflow
