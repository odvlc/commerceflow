#!/usr/bin/env python3
# Imports curated versioned fixtures through the ADMIN API and waits for indexing; it does not
# write Elasticsearch or foreign databases directly.
"""Import versioned synthetic local knowledge through the ADMIN API; never access another service database."""
import json
import os
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

from local_client import login

root = Path(__file__).resolve().parent.parent
base = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
if urlparse(base).hostname not in {"localhost", "127.0.0.1", "::1"}:
    raise ValueError("Fixture import is restricted to loopback development services")
token = os.environ.get("AI_ADMIN_TOKEN") or login("admin")


def call(path, body=None, key=None):
    headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    data = None if body is None else json.dumps(body).encode()
    try:
        with urlopen(Request(base + "/api/v1/admin/ai/knowledge" + path, data=data, headers=headers),
                     timeout=15) as response:
            return json.load(response)
    except HTTPError as error:
        # Report only public ProblemDetail fields, never request headers or the caller's token.
        try:
            problem = json.loads(error.read(16384))
        except (ValueError, UnicodeError):
            problem = {}
        if not isinstance(problem, dict):
            problem = {}
        code = problem.get("code", "REQUEST_REJECTED")
        message = f"Knowledge API HTTP {error.code} ({code}): {problem.get('detail', 'Request rejected')}"
        if error.code == 409 and code in {"IDEMPOTENCY_CONFLICT", "KNOWLEDGE_VERSION_CONFLICT"}:
            # A new key alone cannot authorize different content at an already published version.
            message += (
                "\nIf fixture text or metadata changed, increase that source's version in"
                " knowledge/manifest.json and rerun. Keep its sourceId unchanged; do not delete"
                " existing knowledge or bypass version validation."
            )
        raise SystemExit(message) from None


for entry in json.loads(root.joinpath("knowledge/manifest.json").read_text()):
    relative = entry.pop("file")
    path = (root / "knowledge" / relative).resolve()
    # Resolve fixture paths before reading so the manifest cannot import arbitrary files outside curated
    # knowledge.
    if not path.is_relative_to((root / "knowledge").resolve()):
        raise ValueError("Knowledge fixture escaped its directory")
    entry["content"] = path.read_text()
    result = call("/imports", entry, "ai-knowledge-" + entry["sourceId"] + "-v" + str(entry["version"]))
    for attempt in range(90):
        job = call("/jobs/" + result["jobId"])
        # Import acceptance alone does not prove retrievability; wait for the durable projection job to
        # finish.
        if job["state"] == "SUCCEEDED":
            print("Indexed", entry["sourceId"], "version", entry["version"])
            break
        if job["state"] == "FAILED":
            raise RuntimeError("Index failed: " + str(job.get("errorCode")))
        time.sleep(1)
    else:
        raise TimeoutError("Knowledge remains durably queued; inspect Kafka/outbox/index job state")
