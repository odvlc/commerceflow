#!/usr/bin/env python3
# Runs explicit CPU model preparation/check jobs and removes temporary download egress afterward.
"""Explicit digest-checked CPU model provisioning/warmup in kind; --check never downloads."""
import argparse
import json
import subprocess
import time
from pathlib import Path

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check', action='store_true')
args = parser.parse_args()
command = ['kubectl', '--context', 'kind-commerceflow', '-n', 'commerceflow']


def run(*arguments, **kwargs):
    return subprocess.run(command + list(arguments), check=True, text=True, **kwargs)


run('get', 'statefulset/ollama', stdout=subprocess.DEVNULL)
template = run('create', '--dry-run=client', '-f', str(root / 'deploy/ai/cpu/model-job.yaml'),
               '-o', 'json', capture_output=True)
job = json.loads(template.stdout)
name = 'ai-model-check-v1' if args.check else 'ai-model-prepare-v1'
job['metadata']['name'] = name
if args.check:
    job['spec']['template']['metadata']['labels']['app'] = 'ai-model-check'
    job['spec']['template']['spec']['containers'][0]['command'].append('--check')
existing = subprocess.run(command + ['get', 'job', name, '--ignore-not-found', '-o', 'json'], capture_output=True,
                          text=True, check=True)
# A second invocation must not replace a running preparation job and interrupt an in-progress model
# download.
if existing.stdout and json.loads(existing.stdout).get('status', {}).get('active', 0):
    raise RuntimeError('Model job is already running; inspect its progress rather than interrupt it')
run('delete', 'job', name, '--ignore-not-found')
policy = str(root / 'deploy/ai/cpu/download-policy.yaml')
try:
    if not args.check:
        run('apply', '-f', policy)
    run('create', '-f', '-', input=json.dumps(job))
    deadline = time.monotonic() + 3600
    while time.monotonic() < deadline:
        status = json.loads(run('get', 'job', name, '-o', 'json', capture_output=True).stdout).get('status', {})
        if status.get('succeeded', 0):
            break
        if status.get('failed', 0):
            raise RuntimeError('Real model job failed; no skipped inference acceptance')
        time.sleep(5)
    else:
        raise TimeoutError('Model provisioning deadline exceeded')
# Withdraw temporary download access even when provisioning fails; inference does not require general
# internet egress.
finally:
    subprocess.run(command + ['logs', 'job/' + name], check=False)
    if not args.check:
        run('delete', '-f', policy, '--ignore-not-found')
print('Actual CPU model digest checks and warmup passed')
