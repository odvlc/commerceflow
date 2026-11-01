#!/usr/bin/env python3
# Supplies credentials to local k6 scenarios through the environment, not command-line arguments.
"""Run reproducible local k6 scenarios without passing a credential on the command line."""
import argparse
import json
import os
import subprocess
from pathlib import Path
from urllib.request import Request, urlopen

from local_client import local_url, login

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument("scenario", choices=["commerce", "retrieval", "generation", "tool", "investigation", "mixed"])
parser.add_argument("--iterations", type=int, default=10)
parser.add_argument("--label", default="", help="Optional lowercase run label to retain repeated measurements")
parser.add_argument("--cold", action="store_true",
                    help="Explicitly unload Llama before a single generation measurement")
args = parser.parse_args()
import re

if args.label and not re.fullmatch(r"[a-z0-9-]{1,30}", args.label): parser.error(
    "label must be lowercase letters/digits/hyphens")
if not 1 <= args.iterations <= 100:
    parser.error("iterations must be 1..100")
if args.cold and (args.scenario != "generation" or args.iterations != 1):
    parser.error("cold requires generation --iterations=1")
settings = os.environ.copy()
settings.update(AI_SCENARIO=args.scenario, AI_ITERATIONS=str(args.iterations))
if args.scenario in ("retrieval", "tool", "investigation"):
    settings["AI_LOAD_TOKEN"] = login("alice")
if args.cold:
    model_url = local_url(settings.get("OLLAMA_BASE_URL", "http://127.0.0.1:11434"))
    data = json.dumps(dict(model="llama3.1:8b", keep_alive=0)).encode()
    with urlopen(Request(model_url + "/api/generate", data=data, headers={"Content-Type": "application/json"}),
                 timeout=30) as response:
        response.read()
output = root / ".cache/verification" / ("ai-load-" + args.scenario + ("-cold" if args.cold else "-warm") + (
    "-" + args.label if args.label else "") + ".json")
output.parent.mkdir(parents=True, exist_ok=True)
k6 = settings.get("K6", str(root / ".cache/tools/k6-v2.3.0-macos-arm64/k6"))
# Pass credentials through the child environment so process listings do not expose bearer tokens.
subprocess.run([k6, "run", "--summary-export", str(output), str(root / "performance/ai/load.js")], env=settings,
               check=True)
print("Sanitized local load summary:", output)
