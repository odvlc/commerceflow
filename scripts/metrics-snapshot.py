#!/usr/bin/env python3
"""Collect authenticated Prometheus snapshots from the local application ports."""
import json
import os
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

output = Path(os.environ.get("METRICS_OUTPUT", ".cache/verification/metrics"))
output.mkdir(parents=True, exist_ok=True)
issuer = os.environ.get("KEYCLOAK_ISSUER", "http://localhost:8180/realms/commerceflow")
form = urlencode({"grant_type": "client_credentials", "client_id": "commerceflow-service",
                  "client_secret": os.environ.get("SERVICE_CLIENT_SECRET", "local-service-only")}).encode()
with urlopen(Request(issuer + "/protocol/openid-connect/token", data=form), timeout=5) as response:
    token = json.load(response)["access_token"]
for name, port in {"gateway": 8080, "catalog": 8081, "order": 8082, "inventory": 8083,
                   "payment": 8084, "cart": 8085, "notification": 8086}.items():
    request = Request(f"http://localhost:{port}/actuator/prometheus", headers={"Authorization": "Bearer " + token})
    with urlopen(request, timeout=5) as response:
        output.joinpath(name + ".prom").write_bytes(response.read())
print(f"Saved seven authenticated metric snapshots to {output}")
