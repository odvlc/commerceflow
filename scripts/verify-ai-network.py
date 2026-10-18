#!/usr/bin/env python3
"""Prove allowed and denied connections in the local Calico kind deployment, using actual workload identities."""
import json
import subprocess
from pathlib import Path

prefix = ['kubectl', '--context', 'kind-commerceflow', '-n', 'commerceflow']
checks = []


def probe(source, target, allowed):
    result = subprocess.run(prefix + ['exec', 'deployment/' + source, '--', 'java', '-cp', '/opt/health',
                                      'HealthCheck', target], capture_output=True, text=True, timeout=20)
    if allowed:
        assert result.returncode == 0, source + ' could not reach its allowed dependency: ' + target
    else:
        assert result.returncode != 0 and 'HttpConnectTimeoutException' in result.stderr, \
            'Expected actual network timeout, not an HTTP authorization error or missing service: ' + target
    checks.append({'source': source, 'target': target, 'expectedAllowed': allowed, 'passed': True})


subprocess.run(['kubectl', '--context', 'kind-commerceflow', '-n', 'kube-system', 'rollout', 'status',
                'daemonset/calico-node', '--timeout=60s'], check=True)
probe('ai-service', 'http://ollama:11434/api/tags', True)
for name in ('catalog-service', 'cart-service', 'order-service'):
    probe('ai-service', 'http://' + name + ':8080/actuator/health/readiness', True)
for name in ('payment-service', 'inventory-service', 'notification-service'):
    target = 'http://' + name + ':8080/actuator/health/readiness'
    probe('api-gateway', target, True)  # Prove the denied target is actually serving.
    probe('ai-service', target, False)
probe('api-gateway', 'http://ai-service:8080/actuator/health/readiness', True)
probe('catalog-service', 'http://ai-service:8080/actuator/health/readiness', False)
root = Path(__file__).resolve().parent.parent
(root / '.cache/verification/ai-kind-network.json').write_text(
    json.dumps({'cni': 'Calico 3.33.0', 'checks': checks}, indent=2) + '\n')
print('Verified', len(checks), 'actual allowed/denied workload connections')
