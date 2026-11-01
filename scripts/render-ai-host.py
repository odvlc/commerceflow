#!/usr/bin/env python3
# Generates the private inference endpoint and egress rule. Address validation does not test connectivity.
"""Resolve the inference-only proxy on the kind Docker network and generate an exact Service endpoint/policy."""
import argparse
import ipaddress
import json
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--host-ip', help='explicit IPv4 address for render/schema validation; not reachability proof')
args = parser.parse_args()
address = args.host_ip
if not address:
    result = subprocess.run(['docker', 'inspect', 'commerceflow-kind-model-proxy', '--format',
                             '{{.NetworkSettings.Networks.kind.IPAddress}}'], check=True, capture_output=True,
                            text=True)
    address = result.stdout.strip()
ip = ipaddress.IPv4Address(address)
if ip.is_loopback or ip.is_multicast or ip.is_unspecified or ip.is_link_local:
    raise ValueError('Host endpoint must be an actual routable Docker Desktop IPv4 address')
    # A selectorless Service and EndpointSlice route to the fixed private proxy.
service = {'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'ollama'},
           'spec': {'ports': [{'name': 'http', 'port': 11434, 'targetPort': 11434}]}}
endpoint = {'apiVersion': 'discovery.k8s.io/v1', 'kind': 'EndpointSlice',
            'metadata': {'name': 'ollama-native', 'labels': {'kubernetes.io/service-name': 'ollama',
                                                             'endpointslice.kubernetes.io/managed-by': 'commerceflow-local-tools'}},
            'addressType': 'IPv4', 'ports': [{'name': 'http', 'protocol': 'TCP', 'port': 11434}],
            'endpoints': [{'addresses': [str(ip)], 'conditions': {'ready': True}}]}
policy = {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
          'metadata': {'name': 'ai-native-model-endpoint'},
          'spec': {'podSelector': {'matchLabels': {'app': 'ai-service'}}, 'policyTypes': ['Egress'],
                   'egress': [{'to': [{'ipBlock': {'cidr': str(ip) + '/32'}}],
                               'ports': [{'protocol': 'TCP', 'port': 11434}]}]}}
root = Path(__file__).resolve().parent.parent
(root / 'deploy/ai/host/.generated-endpoint.yaml').write_text(
    '\n---\n'.join(json.dumps(item, indent=2) for item in (service, endpoint, policy)) + '\n')
print('Rendered native model endpoint at', str(ip), '(actual pod reachability remains a runtime check)')
