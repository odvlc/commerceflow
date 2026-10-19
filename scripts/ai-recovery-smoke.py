#!/usr/bin/env python3
# Interrupts real AI execution and withholds a real Cart response; cleanup restores temporary
# fault routing while preserving volumes.
"""Exercise real local Compose AI process loss and an acknowledged-but-lost Cart response. Preserves volumes."""
import argparse
import json
import os
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from local_client import local_url, login

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--target', choices=['compose', 'kind'], default='compose')
args = parser.parse_args()
base = local_url(
    os.environ.get('BASE_URL', 'http://localhost:3000' if args.target == 'kind' else 'http://localhost:8080'))
token = login('alice')
# Keep completion false until every required recovery assertion passes; partial evidence must not look
# fully accepted.
report = {'target': 'local ' + args.target, 'realProcessKill': False, 'complete': False, 'checks': []}


def record_check(message):
    report['checks'].append(message)
    print(message, flush=True)


def call(path, body=None, key=None, method=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
    if key:
        headers['Idempotency-Key'] = key
    with urlopen(Request(base + '/api/v1' + path, data=data, headers=headers,
                         method=method or ('GET' if body is None else 'POST')), timeout=15) as response:
        return json.load(response)


def wait_for(action, predicate, timeout=150):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            value = action()
            if predicate(value):
                return value
        except (HTTPError, URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(.1)
    raise TimeoutError('Local recovery condition did not become true')


def kube(*arguments, **kwargs):
    return subprocess.run(['kubectl', '--context', 'kind-commerceflow', '-n', 'commerceflow', *arguments],
                          check=True, text=True, **kwargs)


def setup_kind_fault_proxy():
    configuration = root / 'infra/ai/cart-recovery-proxy.conf'
    subprocess.run(['docker', 'run', '-d', '--name', 'commerceflow-kind-cart-recovery-proxy', '--network', 'kind',
                    '--user', '101:101', '--read-only', '--tmpfs', '/tmp:rw,nosuid,nodev,size=16m',
                    '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--memory', '64m',
                    '--mount', 'type=bind,source=' + str(configuration) + ',target=/etc/nginx/nginx.conf,readonly',
                    '--entrypoint', 'nginx', 'nginx:1.30-alpine', '-g', 'daemon off;'], check=True, capture_output=True)
    address = subprocess.run(['docker', 'inspect', 'commerceflow-kind-cart-recovery-proxy', '--format',
                              '{{.NetworkSettings.Networks.kind.IPAddress}}'], check=True, capture_output=True,
                             text=True).stdout.strip()
    import ipaddress
    address = str(ipaddress.IPv4Address(address))
    objects = [
        {'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'cart-recovery-proxy'},
         'spec': {'ports': [{'name': 'http', 'port': 18885, 'targetPort': 18885}]}},
        {'apiVersion': 'discovery.k8s.io/v1', 'kind': 'EndpointSlice',
         'metadata': {'name': 'cart-recovery-proxy', 'labels': {'kubernetes.io/service-name': 'cart-recovery-proxy'}},
         'addressType': 'IPv4', 'ports': [{'name': 'http', 'protocol': 'TCP', 'port': 18885}],
         'endpoints': [{'addresses': [address], 'conditions': {'ready': True}}]},
        {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': {'name': 'ai-cart-recovery-proxy'},
         'spec': {'podSelector': {'matchLabels': {'app': 'ai-service'}}, 'policyTypes': ['Egress'],
                  'egress': [{'to': [{'ipBlock': {'cidr': address + '/32'}}],
                              'ports': [{'protocol': 'TCP', 'port': 18885}]}]}},
    ]
    for item in objects:
        kube('apply', '-f', '-', input=json.dumps(item))


def cleanup_kind_fault_proxy():
    kube('delete', 'service/cart-recovery-proxy', 'endpointslice/cart-recovery-proxy',
         'networkpolicy/ai-cart-recovery-proxy', '--ignore-not-found')
    subprocess.run(['docker', 'rm', '-f', 'commerceflow-kind-cart-recovery-proxy'], check=False, capture_output=True)


def compose(*arguments, **overrides):
    if args.target == 'kind':
        if arguments[0] == 'kill':
            pod = \
                json.loads(kube('get', 'pods', '-l', 'app=ai-service', '-o', 'json', capture_output=True).stdout)[
                    'items'][
                    0]
            report['lastKilledPodUid'] = pod['metadata']['uid']
            # Stop the actual container before replacing its Pod; graceful shutdown alone would not
            # exercise crash recovery.
            container_id = pod['status']['containerStatuses'][0]['containerID'].split('://', 1)[1]
            subprocess.run(
                ['docker', 'exec', 'commerceflow-control-plane', 'crictl', 'stop', '--timeout=0', container_id],
                check=True)
            kube('delete', 'pod', pod['metadata']['name'], '--grace-period=0', '--force', '--wait=true')
        elif '--force-recreate' in arguments:
            cart_url = 'http://cart-recovery-proxy:18885' if 'AI_CART_URL' in overrides else 'http://cart-service:8080'
            model_url = 'http://ollama:11439' if 'AI_OLLAMA_URL' in overrides else 'http://ollama:11434'
            kube('set', 'env', 'deployment/ai-service', 'CART_URL=' + cart_url, 'OLLAMA_BASE_URL=' + model_url)
            kube('rollout', 'restart', 'deployment/ai-service')
            kube('rollout', 'status', 'deployment/ai-service', '--timeout=150s')
        else:
            wait_for(lambda:
                     json.loads(kube('get', 'pods', '-l', 'app=ai-service', '-o', 'json', capture_output=True).stdout)[
                         'items'],
                     lambda pods: any(pod['metadata']['uid'] != report.get('lastKilledPodUid') and
                                      any(condition['type'] == 'Ready' and condition['status'] == 'True' for condition
                                          in pod.get('status', {}).get('conditions', [])) for pod in pods))
        return
    environment = dict(os.environ, AI_ENABLED='true', **overrides)
    with (root / '.cache/verification/ai-recovery-compose.log').open('a') as output:
        subprocess.run(
            ['docker', 'compose', '-f', 'infra/compose.yaml', '--profile', 'full', '--profile', 'ai', *arguments],
            cwd=root, env=environment, check=True, stdout=output, stderr=subprocess.STDOUT)


def restart(**overrides):
    compose('up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '150', 'ai-service', **overrides)
    wait_for(lambda: call('/ai/capabilities'), lambda value: value['enabled'])


def start(question):
    c = call('/ai/conversations', {'mode': 'CUSTOMER'}, str(uuid.uuid4()))
    return call('/ai/conversations/' + c['id'] + '/messages', {'content': question}, str(uuid.uuid4()))


def view(run):
    return call('/ai/runs/' + run['id'])


def prepare(product):
    run = start('Prepare an ADD cart proposal for exactly 1 unit of product UUID ' + product + '. Do not confirm.')
    run = wait_for(lambda: view(run), lambda value: value['state'] in ('WAITING_CONFIRMATION', 'FAILED'))
    assert run['state'] == 'WAITING_CONFIRMATION', run.get('errorCode')
    return run, run['proposals'][0]


def confirm(proposal):
    return call('/ai/proposals/' + proposal['id'] + '/confirm',
                {key: proposal[key] for key in ('version', 'argumentsHash')})


def quantity(product):
    return next((item['quantity'] for item in call('/cart')['items'] if item['productId'] == product), 0)


acknowledged, release = threading.Event(), threading.Event()


class CartResponseDelay(BaseHTTPRequestHandler):
    # This test-only loopback proxy delays only the real acknowledgment, never fabricates a response.
    def log_message(self, *_):
        pass

    def forward(self):
        if not self.path.startswith('/api/v1/cart'):
            self.send_error(404)
            return
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            chunks, total = [], 0
            while True:
                size = int(self.rfile.readline(100).split(b';', 1)[0].strip(), 16)
                if size == 0:
                    while self.rfile.readline(1000).strip():
                        pass
                    break
                total += size
                if total > 16384:
                    self.send_error(413)
                    return
                chunks.append(self.rfile.read(size))
                if self.rfile.read(2) != b'\r\n':
                    self.send_error(400)
                    return
            body = b''.join(chunks)
        else:
            size = int(self.headers.get('Content-Length', '0'))
            if size > 16384:
                self.send_error(413)
                return
            body = self.rfile.read(size) if size else None
        headers = {key: value for key, value in self.headers.items()
                   if key.lower() not in ('host', 'connection', 'content-length', 'transfer-encoding', 'expect')}
        request = Request(base + self.path, data=body, method=self.command, headers=headers)
        try:
            response = urlopen(request, timeout=10)
        except HTTPError as failure:
            response = failure
        with response:
            content, status = response.read(), response.code
            returned_headers = dict(response.headers)
        if self.command != 'GET' and 200 <= status < 300:
            acknowledged.set()
            release.wait(30)
        try:
            self.send_response(status)
            for key, value in returned_headers.items():
                if key.lower() not in ('connection', 'content-length', 'transfer-encoding'):
                    self.send_header(key, value)
            self.send_header('Content-Length', str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        except (BrokenPipeError, ConnectionResetError):
            pass

    do_GET = do_POST = do_PATCH = do_DELETE = forward


server = None
try:
    assert call('/ai/capabilities')['modelReady']
    product = next(item['id'] for item in call('/products?size=100')['items'] if item['sku'] == 'BAG-01')
    run, proposal = prepare(product)
    before = quantity(product)
    restart()
    token = login('alice')
    saved = call('/ai/proposals/' + proposal['id'])
    assert all(saved[key] == proposal[key] for key in ('id', 'argumentsHash', 'cartRevision', 'version'))
    assert view(run)['state'] == 'WAITING_CONFIRMATION'
    first_execution = confirm(saved)
    assert wait_for(lambda: view(run), lambda value: value['state'] in ('SUCCEEDED', 'FAILED'))['state'] == 'SUCCEEDED'
    assert quantity(product) == before + 1
    assert confirm(saved)['id'] == first_execution['id']
    record_check(
        'Waiting proposal survived process replacement; fresh confirmation and duplicate confirmation had one effect')

    read_run = start('Search the demo shipping policy and explain the delivery estimate with a citation.')
    wait_for(lambda: view(read_run),
             lambda value: any(step['kind'] == 'MODEL' and step['state'] == 'PENDING' for step in value['steps']), 30)
    compose('kill', '--signal', 'SIGKILL', 'ai-service')
    report['realProcessKill'] = True
    compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '150', 'ai-service')
    token = login('alice')
    paused = wait_for(lambda: view(read_run),
                      lambda value: value['state'] in ('AUTHENTICATION_REQUIRED', 'SUCCEEDED', 'FAILED'))
    assert paused[
               'state'] == 'AUTHENTICATION_REQUIRED', 'Kill must interrupt actual model work, not count an already completed run'
    call('/ai/runs/' + read_run['id'] + '/resume', {})
    assert wait_for(lambda: view(read_run), lambda value: value['state'] in ('SUCCEEDED', 'FAILED'))[
               'state'] == 'SUCCEEDED'
    record_check('SIGKILL during model step required fresh same-owner resume and completed from durable state')

    server = ThreadingHTTPServer(('127.0.0.1', 18885), CartResponseDelay)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    if args.target == 'kind': setup_kind_fault_proxy()
    restart(AI_CART_URL='http://host.docker.internal:18885')
    token = login('alice')
    run, proposal = prepare(product)
    before = quantity(product)
    execution = confirm(proposal)
    assert acknowledged.wait(15), 'Real Cart acknowledgment was not observed'
    compose('kill', '--signal', 'SIGKILL', 'ai-service')
    report['realProcessKill'] = True
    assert quantity(product) == before + 1
    release.set()
    # Restore fixed Cart DNS while inference is unavailable: committed execution must not depend on an LLM.
    restart(AI_OLLAMA_URL='http://host.docker.internal:11439')
    token = login('alice')
    assert not call('/ai/capabilities')['modelReady']
    token = login('alice')
    paused = wait_for(lambda: view(run),
                      lambda value: value['state'] in ('AUTHENTICATION_REQUIRED', 'SUCCEEDED', 'FAILED'))
    assert paused['state'] == 'AUTHENTICATION_REQUIRED'
    call('/ai/runs/' + run['id'] + '/resume', {})
    assert wait_for(lambda: view(run), lambda value: value['state'] in ('SUCCEEDED', 'FAILED'))['state'] == 'SUCCEEDED'
    recovered = call('/ai/proposals/' + proposal['id'] + '/execution')
    assert recovered['requestKey'] == execution['requestKey'] and recovered['id'] == execution['id']
    assert recovered['state'] == 'SUCCEEDED' and quantity(product) == before + 1
    record_check(
        'SIGKILL after real Cart acknowledgment before AI recorded response: same execution/key replay, one added unit while inference unavailable')

    restart(AI_OLLAMA_URL='http://host.docker.internal:11439')
    token = login('alice')
    assert not call('/ai/capabilities')['modelReady']
    conversation = call('/ai/conversations', {'mode': 'CUSTOMER'}, str(uuid.uuid4()))
    try:
        call('/ai/conversations/' + conversation['id'] + '/messages', {'content': 'return window'}, str(uuid.uuid4()))
        raise AssertionError('Unavailable model accepted new inference work')
    except HTTPError as failure:
        assert failure.code == 503 and json.load(failure)['code'] == 'AI_MODEL_UNAVAILABLE'
    assert call('/ai/conversations/' + conversation['id'] + '/messages')['items'] == []
    if args.target == 'kind':
        kube('exec', 'deployment/api-gateway', '--', 'java', '-cp', '/opt/health', 'HealthCheck',
             'http://127.0.0.1:8080/actuator/health/readiness', stdout=subprocess.DEVNULL)
    else:
        with urlopen(base + '/actuator/health/readiness', timeout=5) as response:
            assert json.load(response)['status'] == 'UP'
    assert call('/products?size=1')['items']
    assert view(run)['state'] == 'SUCCEEDED'
    record_check('Model outage rejected message without persistence; history and commerce gateway remained available')
    report['complete'] = True
finally:
    release.set()
    if server:
        server.shutdown()
        server.server_close()
    restart()
    if args.target == 'kind': cleanup_kind_fault_proxy()
    destination = root / ('.cache/verification/ai-' + args.target + '-recovery.json')
    destination.write_text(json.dumps(report, indent=2) + '\n')
print('Verified', len(report['checks']), 'local process/persistence/failure scenarios; volumes preserved')
