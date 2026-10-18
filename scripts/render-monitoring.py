#!/usr/bin/env python3
"""Render local monitoring configuration without committing credentials."""
import json
import os
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
native = len(sys.argv) > 1 and sys.argv[1] == "native"
settings = {}
dotenv = root / "infra/.env"
if dotenv.exists():
    for line in dotenv.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            settings[key.strip()] = value.strip().strip('"').strip("'")
settings.update(os.environ)
secret = settings.get("SERVICE_CLIENT_SECRET", "local-service-only")
source = (root / "infra/prometheus/prometheus.yaml").read_text()
source = source.replace("client_secret: local-service-only", "client_secret: " + json.dumps(secret))
if not native:
    (root / "infra/.generated-prometheus.yaml").write_text(source)
else:
    directory = root / ".cache/monitoring"
    directory.mkdir(parents=True, exist_ok=True)
    source = source.replace("/etc/prometheus/alerts.yaml", str(root / "infra/prometheus/alerts.yaml"))
    source = source.replace("http://keycloak:8080", "http://localhost:8180")
    for service, port in {"catalog-service": 8081, "cart-service": 8085, "order-service": 8082,
                          "inventory-service": 8083, "payment-service": 8084,
                          "notification-service": 8086, "api-gateway": 8080}.items():
        source = source.replace(service + ":8080", f"localhost:{port}")
    (directory / "prometheus.yaml").write_text(source)
    provisioning = directory / "provisioning"
    (provisioning / "datasources").mkdir(parents=True, exist_ok=True)
    (provisioning / "dashboards").mkdir(parents=True, exist_ok=True)
    datasource = (root / "infra/grafana/provisioning/datasources/prometheus.yaml").read_text().replace("http://prometheus:9090", "http://localhost:9090")
    (provisioning / "datasources/prometheus.yaml").write_text(datasource)
    provider = (root / "infra/grafana/provisioning/dashboards/dashboards.yaml").read_text().replace("/var/lib/grafana/dashboards", str(root / "infra/grafana/dashboards"))
    (provisioning / "dashboards/dashboards.yaml").write_text(provider)
    (directory / "grafana.ini").write_text(f"""[server]
http_addr = 127.0.0.1
http_port = 3001
static_root_path = {root / ".cache/tools/grafana-13.2.4/public"}
[paths]
data = {directory / 'grafana-data'}
logs = {directory / 'grafana-logs'}
provisioning = {provisioning}
[security]
admin_user = local-admin
admin_password = local-grafana-only
[analytics]
reporting_enabled = false
check_for_updates = false
""")
print("Rendered native monitoring" if native else "Rendered development monitoring configuration")
