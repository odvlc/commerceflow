#!/usr/bin/env python3
"""Verify the native model endpoint from inside Docker Desktop, not from the host network."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlparse

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('endpoint', nargs='?')
parser.add_argument('--endpoint-only', action='store_true')
args = parser.parse_args()
configured = 'http://host.docker.internal:11434'
local = root / 'infra/.env'
if local.exists():
    for line in local.read_text().splitlines():
        if line.startswith('AI_HOST_MODEL_URL='):
            configured = line.split('=', 1)[1].strip().strip(chr(34) + chr(39))
endpoint = (args.endpoint or os.environ.get('AI_HOST_MODEL_URL', configured)).rstrip('/')
parsed = urlparse(endpoint)
if parsed.scheme != 'http' or parsed.hostname != 'host.docker.internal' or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
    raise ValueError('Expected local Docker Desktop host endpoint')
if args.endpoint_only:
    print(endpoint)
    sys.exit(0)
response = subprocess.run(['docker', 'run', '--rm',
    'curlimages/curl:8.16.0@sha256:463eaf6072688fe96ac64fa623fe73e1dbe25d8ad6c34404a669ad3ce1f104b6',
    '-fsS', '--connect-timeout', '3', '--max-time', '10', endpoint + '/api/tags'],
    check=True, capture_output=True, text=True)
installed = {model['name']: model['digest'] for model in json.loads(response.stdout)['models']}
lock = json.loads((Path(__file__).resolve().parent.parent / 'infra/ai/models.lock.json').read_text())
for model in lock['models']:
    if installed.get(model['name']) != model['digest']:
        raise RuntimeError('Missing/changed model from inside container: ' + model['name'])
print('Container reached native Ollama and verified both pinned model digests')
