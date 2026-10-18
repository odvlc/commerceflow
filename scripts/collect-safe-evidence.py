#!/usr/bin/env python3
"""Export an allowlist of synthetic evidence; never upload raw traces, logs, credentials or rendered Secrets."""
import json
import os
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
output = root / '.cache/verification/ci-safe'
output.mkdir(parents=True, exist_ok=True)
previous = output / 'manifest.json'
# Remove only this exporter's previous outputs; an unexpected file must never become an artifact.
existing = {str(path.relative_to(output)) for path in output.rglob('*') if path.is_file()}
if existing:
    if not previous.exists():
        raise ValueError('Nonempty export directory has no ownership manifest; refusing to touch/upload it')
    owned = set(json.loads(previous.read_text())['files']) | {'manifest.json'}
    if existing - owned:
        raise ValueError('Unexpected files in generated evidence directory; refusing to touch/upload it')
    for name in owned:
        path = (output / name).resolve()
        if not path.is_relative_to(output.resolve()):
            raise ValueError('Invalid prior evidence manifest path')
        if path.is_file():
            path.unlink()

credential = re.compile(r'(?i)(?:bearer\s+[A-Za-z0-9_.-]{30,}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|"(?:access_token|refresh_token|id_token|client_secret|authorization|cookie)"\s*:)')
manifest = []


def export_text(source, destination, content):
    if credential.search(content):
        raise ValueError('Potential credential in allowlisted evidence; refusing export: ' + str(source.relative_to(root)))
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(content)
    manifest.append(str(destination.relative_to(output)))


# JUnit assertion dumps/system-out may contain HTTP headers. Export results and test identity only.
for pattern in ('*/target/surefire-reports/TEST-*.xml', '*/target/failsafe-reports/TEST-*.xml'):
    for source in root.glob(pattern):
        suite = ET.parse(source).getroot()
        clean = ET.Element('testsuite', {key: value for key, value in suite.attrib.items()
                                        if key in {'name', 'tests', 'failures', 'errors', 'skipped', 'time'}})
        for case in suite.findall('testcase'):
            result = ET.SubElement(clean, 'testcase', {key: value for key, value in case.attrib.items()
                                                      if key in {'name', 'classname', 'time'}})
            for status in ('failure', 'error', 'skipped'):
                if case.find(status) is not None:
                    ET.SubElement(result, status).text = 'Details retained in the job log; HTTP dumps are excluded from artifacts.'
        export_text(source, output / 'junit' / (source.parts[-4] + '-' + source.name), ET.tostring(clean, encoding='unicode'))

for name in ('ai-evaluation-all.json', 'ai-load-retrieval-warm-hybrid.json',
             'ai-load-investigation-warm-hybrid.json', 'ai-kind-network.json',
             'ai-compose-recovery.json', 'ai-kind-recovery.json'):
    source = root / '.cache/verification' / name
    if source.exists():
        data = json.loads(source.read_text())
        if name == 'ai-evaluation-all.json':
            keys = {(case['repeat'], case['id']) for case in data['cases']}
            data['allThreeRepeatsPresent'] = len(keys) == 240 and len(data['cases']) == 240
        export_text(source, output / name, json.dumps(data, indent=2) + '\n')
source = root / 'infra/ai/models.lock.json'
export_text(source, output / 'models.lock.json', source.read_text())
# The storefront screenshot is taken before authentication in the public catalog test.
source = root / '.cache/verification/storefront.png'
if source.exists():
    shutil.copyfile(source, output / 'public-storefront.png')
    manifest.append('public-storefront.png')
(output / 'manifest.json').write_text(json.dumps({'files': sorted(manifest),
    'excluded': ['raw logs', 'Playwright trace.zip', 'browser storage', 'rendered Kubernetes Secret YAML',
                 'authenticated Copilot screenshots', 'JUnit HTTP dumps/system-out']}, indent=2) + '\n')
print('Exported', len(manifest), 'allowlisted evidence files to', output.relative_to(root))

# CI uploads only after every allowlisted export passes; partial failures never publish readiness.
if os.environ.get('GITHUB_OUTPUT'):
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as github_output:
        github_output.write('ready=true\n')
