#!/usr/bin/env python3
# Builds model preparation inputs from the local pinned model metadata.
"""Render public model preparation inputs from their authoritative sources; no credentials included."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
# Copy the preparation script and public model lock. Keep local credentials out of the ConfigMap.
config = {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'ai-model-preparation'},
          'data': {'prepare-ai-models.py': (root / 'scripts/prepare-ai-models.py').read_text(),
                   'models.lock.json': (root / 'infra/ai/models.lock.json').read_text()}}
(root / 'deploy/ai/base/.generated-model-config.yaml').write_text(json.dumps(config, indent=2) + '\n')
