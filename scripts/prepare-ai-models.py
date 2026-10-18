#!/usr/bin/env python3
"""Explicitly provision, digest-check and optionally warm pinned loopback Ollama models. No cloud inference."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
from urllib.parse import urlparse
from urllib.request import Request, urlopen

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cluster-local', action='store_true', help='allow only the fixed in-cluster Ollama service DNS')
parser.add_argument('--storage-path', type=Path, default=Path.home(), help='model storage filesystem for the free-space check')
parser.add_argument('--check', action='store_true', help='verify presence/digests without downloading')
parser.add_argument('--warmup', action='store_true', help='explicit generation and embedding warmup, outside application startup timeout')
parser.add_argument('--timeout', type=int, default=int(os.environ.get('AI_MODEL_PREPARE_TIMEOUT', '1800')))
parser.add_argument('--container-storage', action='store_true', help='check free space in the Compose Ollama model volume')
args = parser.parse_args()
if not 60 <= args.timeout <= 7200:
    parser.error('timeout must be 60..7200 seconds')
base = os.environ.get('OLLAMA_BASE_URL', 'http://127.0.0.1:11434').rstrip('/')
address = urlparse(base)
if address.scheme != 'http' or address.hostname not in ({'ollama', 'ollama.commerceflow.svc.cluster.local'} if args.cluster_local else {'localhost', '127.0.0.1', '::1'}) or address.username or address.password or address.path or address.query or address.fragment:
    raise ValueError('Model preparation must target a local loopback HTTP Ollama endpoint')
lock = json.loads((root / 'infra/ai/models.lock.json').read_text())


def request(path, data=None):
    body = None if data is None else json.dumps(data).encode()
    return Request(base + path, data=body, headers={'Content-Type': 'application/json'})


def installed():
    with urlopen(request('/api/tags'), timeout=10) as response:
        return {model['name']: model for model in json.load(response)['models']}


present = installed()
missing = [model for model in lock['models'] if model['name'] not in present]
if missing and not args.check:
    if args.container_storage:
        usage = subprocess.run(['docker', 'compose', '-f', 'infra/compose.yaml', 'exec', '-T', 'ollama',
                                'df', '-Pk', '/root/.ollama'], cwd=root, check=True, text=True, capture_output=True)
        free = int(usage.stdout.splitlines()[-1].split()[3]) * 1024
    else:
        free = shutil.disk_usage(args.storage_path).free
    required = int(sum(model['size'] for model in missing) * 1.25) + 1024**3
    if free < required:
        raise RuntimeError(f'Insufficient model storage: require {required // 1024**2} MiB, have {free // 1024**2} MiB')
    print(f'Model storage preflight: {free // 1024**3} GiB free, {required // 1024**2} MiB required', flush=True)

for expected in lock['models']:
    if expected['name'] not in installed() and not args.check:
        print('Downloading', expected['name'], '(explicit preparation; timeout', args.timeout, 'seconds)', flush=True)
        last = None
        with urlopen(request('/api/pull', {'model': expected['name'], 'stream': True}), timeout=args.timeout) as response:
            for line in response:
                status = json.loads(line)
                if 'error' in status:
                    raise RuntimeError(status['error'])
                percent = int(100 * status.get('completed', 0) / max(1, status.get('total', 1)))
                progress = (status.get('status', 'pulling'), percent // 10)
                if progress != last:
                    print(expected['name'], progress[0], str(percent) + '%', flush=True)
                    last = progress
    actual = installed().get(expected['name'])
    if not actual or actual['digest'] != expected['digest']:
        raise RuntimeError('Missing or changed model digest for ' + expected['name'] + '; run explicit model preparation')
    print('Verified', expected['name'], actual['digest'], flush=True)

if args.warmup:
    print('Warming generation and embedding models separately from application readiness', flush=True)
    with urlopen(request('/api/chat', {'model': lock['chat'], 'stream': False, 'keep_alive': '5m',
                 'messages': [{'role': 'user', 'content': 'Say ready.'}],
                 'options': {'num_ctx': 4096, 'num_predict': 8, 'temperature': 0}}), timeout=args.timeout) as response:
        chat = json.load(response)
    if not chat.get('done') or not chat.get('message', {}).get('content'):
        raise RuntimeError('Generation warmup failed')
    with urlopen(request('/api/embed', {'model': lock['embedding'], 'input': ['local readiness check'],
                 'truncate': False, 'keep_alive': '5m'}), timeout=args.timeout) as response:
        vectors = json.load(response).get('embeddings', [])
    if len(vectors) != 1 or len(vectors[0]) != 768:
        raise RuntimeError('Embedding warmup dimension mismatch')
    print('Both actual model warmups passed', flush=True)
