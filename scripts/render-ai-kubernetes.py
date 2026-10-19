#!/usr/bin/env python3
# Builds model preparation inputs from authoritative local sources so deployment copies do not
# drift from pinned model metadata.
"""Render public model preparation inputs from their authoritative sources; no credentials included."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
# Copy only the preparation script and public model lock; local environment credentials do not belong in
# this ConfigMap.
config = {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'ai-model-preparation'},
          'data': {'prepare-ai-models.py': (root / 'scripts/prepare-ai-models.py').read_text(),
                   'models.lock.json': (root / 'infra/ai/models.lock.json').read_text()}}
(root / 'deploy/ai/base/.generated-model-config.yaml').write_text(json.dumps(config, indent=2) + '\n')
