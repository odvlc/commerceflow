#!/usr/bin/env python3
"""Collect authenticated Prometheus snapshots from the local application ports."""
import json
import os
import sys
from urllib.parse import urlparse
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

output = Path(os.environ.get("METRICS_OUTPUT", ".cache/verification/metrics"))
output.mkdir(parents=True, exist_ok=True)
# Container services intentionally have no host management ports. Read the samples that Prometheus
# obtained using its service-account OAuth2 credentials instead of publishing those ports.
if os.environ.get("METRICS_MODE", "native") == "compose":
    base = os.environ.get("PROMETHEUS_URL", "http://localhost:9090").rstrip("/")
    if urlparse(base).hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise ValueError("Metric collection must target local development Prometheus")
    def query(expression):
        with urlopen(base + "/api/v1/query?" + urlencode({"query": expression}), timeout=10) as response:
            data = json.load(response)
        if data["status"] != "success":
            raise RuntimeError("Prometheus query failed")
        return data["data"]["result"]
    targets = query('up{job="commerceflow"}')
    expected = 8 if os.environ.get("AI_ENABLED", "false").lower() == "true" else 7
    if len(targets) != expected or any(item["value"][1] != "1" for item in targets):
        raise RuntimeError("Expected all authenticated CommerceFlow scrape targets to be healthy")
    output.joinpath("compose-targets.json").write_text(json.dumps(targets, indent=2))
    output.joinpath("compose-samples.json").write_text(json.dumps(query('{job="commerceflow"}'), indent=2))
    print(f"Saved samples for {len(targets)} authenticated container targets to {output}")
    sys.exit(0)
if os.environ.get("METRICS_MODE", "native") != "native":
    raise ValueError("METRICS_MODE must be native or compose")
issuer = os.environ.get("KEYCLOAK_ISSUER", "http://localhost:8180/realms/commerceflow")
form = urlencode({"grant_type": "client_credentials", "client_id": "commerceflow-service",
                  "client_secret": os.environ.get("SERVICE_CLIENT_SECRET", "local-service-only")}).encode()
with urlopen(Request(issuer + "/protocol/openid-connect/token", data=form), timeout=5) as response:
    token = json.load(response)["access_token"]
services = {"gateway": 8080, "catalog": 8081, "order": 8082, "inventory": 8083,
                   "payment": 8084, "cart": 8085, "notification": 8086}
if os.environ.get("AI_ENABLED", "false").lower() == "true": services["ai"] = 8087
for name, port in services.items():
    request = Request(f"http://localhost:{port}/actuator/prometheus", headers={"Authorization": "Bearer " + token})
    with urlopen(request, timeout=5) as response:
        output.joinpath(name + ".prom").write_bytes(response.read())
print(f"Saved {len(services)} authenticated metric snapshots to {output}")
