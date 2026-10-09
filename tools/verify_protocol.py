#!/usr/bin/env python3
"""Verify the vendored receiver contract before running either application tests."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "protocol" / "v1"
pin = json.loads((root / "upstream.json").read_text())
assert pin["protocolVersion"] == 1
assert len(pin["commit"]) == 40
for name, expected in pin["files"].items():
    actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f"Pinned protocol drift: {name}")
print("Protocol v1: pinned contract and fixtures verified.")
