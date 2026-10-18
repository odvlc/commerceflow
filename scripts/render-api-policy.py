#!/usr/bin/env python3
"""Allow Traefik to contact only explicitly supplied private cluster API addresses."""
import ipaddress
import json
import sys
from pathlib import Path

addresses = sys.argv[1:] or ["10.96.0.1"]
networks = []
for value in addresses:
    address = ipaddress.ip_address(value)
    if not address.is_private or address.is_unspecified or address.is_loopback:
        raise ValueError("Expected a specific private kind API address")
    networks.append({"ipBlock": {"cidr": str(address) + ("/32" if address.version == 4 else "/128")}})
policy = {"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy",
          "metadata": {"name": "traefik-api-access"},
          "spec": {"podSelector": {"matchLabels": {"app": "traefik"}}, "policyTypes": ["Egress"],
                   "egress": [{"to": networks, "ports": [{"protocol": "TCP", "port": 443},
                                                          {"protocol": "TCP", "port": 6443}]}]}}
root = Path(__file__).resolve().parent.parent
(root / "infra/.generated-api-policy.yaml").write_text(json.dumps(policy, indent=2) + "\n")
print("Rendered API egress restricted to", ", ".join(addresses))
