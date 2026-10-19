#!/usr/bin/env python3
# Tests real pod/process loss with synthetic orders/carts and rebuild checkpoints while preserving
# existing persistent volumes.
"""Verify real local order/database/cart PVC durability and interrupted AI index rebuilding. Synthetic data only."""
import json
import os
import subprocess
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from local_client import local_url, login

root = Path(__file__).resolve().parent.parent
base = local_url(os.environ.get('BASE_URL', 'http://localhost:3000'))
customer, admin = login('alice'), login('admin')
imported = None
report = {'target': 'local persisted kind', 'complete': False, 'checks': []}
source = 'recovery-' + uuid.uuid4().hex
content = '\n\n'.join('## Rehearsal section ' + str(number) + '\n' +
                      'This is synthetic operational recovery evidence. It grants no shopping entitlement. ' * 8
                      for number in range(80))


def call(path, token=customer, body=None, key=None):
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
    if key:
        headers['Idempotency-Key'] = key
    with urlopen(Request(base + '/api/v1' + path, data=None if body is None else json.dumps(body).encode(),
                         headers=headers), timeout=10) as response:
        return json.load(response)


def kube(*arguments, **kwargs):
    return subprocess.run(['kubectl', '--context', 'kind-commerceflow', '-n', 'commerceflow', *arguments],
                          check=True, text=True, **kwargs)


def wait(action, predicate, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = action()
            if predicate(value):
                return value
        except (HTTPError, URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(.1)
    raise TimeoutError('Durability verification did not reach its required state')


def login_ready(user):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            return login(user)
        except (RuntimeError, HTTPError, URLError):
            time.sleep(1)
    raise TimeoutError('Real Keycloak PKCE did not recover after database restart')


def checked(message):
    report['checks'].append(message)
    print(message, flush=True)


try:
    orders = call('/orders?size=100')['items']
    order = next(value for value in orders if value['status'] in ('CONFIRMED', 'CANCELLED'))
    before = call('/orders/' + order['id'])
    kube('rollout', 'restart', 'deployment/order-service')
    kube('rollout', 'status', 'deployment/order-service', '--timeout=180s')
    after = call('/orders/' + order['id'])
    fields = ('id', 'customerId', 'status', 'totalAmount', 'currency', 'items')
    assert all(before[field] == after[field] for field in fields)
    checked('Terminal order/customer/money/items survived an Order pod rollout')

    conversations = call('/ai/conversations?size=100')['items']
    conversation = next(
        value for value in conversations if call('/ai/conversations/' + value['id'] + '/messages')['items'])
    saved = call('/ai/conversations/' + conversation['id'] + '/messages?size=100')
    cart = call('/cart')
    kube('delete', 'pod', 'postgres-0')
    kube('rollout', 'status', 'statefulset/postgres', '--timeout=180s')
    restored = wait(lambda: call('/ai/conversations/' + conversation['id'] + '/messages?size=100'),
                    lambda value: value == saved)
    assert restored == saved and call('/orders/' + order['id']) == after
    checked('PostgreSQL PVC retained exact order and owned AI messages/citations after database pod replacement')
    kube('delete', 'pod', 'redis-0')
    kube('rollout', 'status', 'statefulset/redis', '--timeout=180s')
    restored_cart = wait(lambda: call('/cart'), lambda value: value['revision'] == cart['revision'])
    assert restored_cart['items'] == cart['items']
    checked('Redis AOF/PVC retained cart items and incarnation/revision after graceful pod replacement')

    admin = login_ready('admin')
    document = {'sourceId': source, 'version': 1, 'title': 'Operational recovery rehearsal fixture',
                'audience': 'SUPPORT', 'content': content, 'active': True}
    imported = call('/admin/ai/knowledge/imports', admin, document, source)
    wait(lambda: call('/admin/ai/knowledge/jobs/' + imported['jobId'], admin),
         lambda value: value['state'] == 'SUCCEEDED')
    rebuilt = call('/admin/ai/knowledge/rebuilds', admin, {}, str(uuid.uuid4()))
    wait(lambda: call('/admin/ai/knowledge/jobs/' + rebuilt['id'], admin), lambda value: value['state'] == 'RUNNING',
         30)
    pod = json.loads(kube('get', 'pods', '-l', 'app=ai-service', '-o', 'json', capture_output=True).stdout)['items'][0]
    container_id = pod['status']['containerStatuses'][0]['containerID'].split('://', 1)[1]
    subprocess.run(['docker', 'exec', 'commerceflow-control-plane', 'crictl', 'stop', '--timeout=0', container_id],
                   check=True)
    kube('delete', 'pod', pod['metadata']['name'], '--grace-period=0', '--force', '--wait=true')
    job_id = str(uuid.UUID(rebuilt['id']))
    sql = ("SELECT pg_try_advisory_lock(hashtextextended('commerceflow-ai-knowledge-index',0));"
           "SELECT state FROM knowledge_rebuilds WHERE id='" + job_id + "';")
    state = wait(lambda: kube('exec', 'postgres-0', '--', 'psql', '-U', 'postgres', '-d', 'ai', '-Atc', sql,
                              capture_output=True).stdout.strip().splitlines(), lambda value: value and value[0] == 't',
                 20)
    # Require both unfinished durable work and a released old session lock before claiming an
    # interrupted rebuild was recovered.
    assert state == ['t', 'RUNNING'], 'Required an interrupted rebuild with its old session lock released'
    report['rebuildStateAfterKill'] = 'RUNNING'
    report['exclusiveIndexLockAvailableAfterKill'] = True
    completed = wait(lambda: call('/admin/ai/knowledge/jobs/' + job_id, admin),
                     lambda value: value['state'] in ('SUCCEEDED', 'FAILED'))
    assert completed['state'] == 'SUCCEEDED'
    customer = login_ready('alice')
    evidence = wait(lambda: call('/ai/knowledge/search?query=return%20window', customer),
                    lambda value: bool(value['items']))
    assert any(value['sourceId'] == 'returns' for value in evidence['items'])
    checked(
        'SIGKILL during RUNNING rebuild recovered the same generation/job and restored cited knowledge without waiting ten minutes')
    report['complete'] = True
finally:
    # Retain an auditable inactive test version; never delete/overwrite a user's source or a PVC.
    try:
        if imported is not None:
            admin = login_ready('admin')
            inactive = {'sourceId': source, 'version': 2, 'title': 'Operational recovery rehearsal fixture',
                        'audience': 'SUPPORT', 'content': content, 'active': False}
            deactivated = wait(lambda: call('/admin/ai/knowledge/imports', admin, inactive, source + '-deactivated'),
                               lambda value: bool(value.get('jobId')))
            wait(lambda: call('/admin/ai/knowledge/jobs/' + deactivated['jobId'], admin),
                 lambda value: value['state'] == 'SUCCEEDED')
    finally:
        (root / '.cache/verification/ai-kind-durability.json').write_text(json.dumps(report, indent=2) + '\n')
print('Verified', len(report['checks']), 'real kind durable recovery boundaries')
