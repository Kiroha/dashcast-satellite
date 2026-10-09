#!/usr/bin/env python3
"""Verify the vendored guidance and optional pairing contracts before tests."""
import hashlib
import json
import re
from pathlib import Path

protocol_root = Path(__file__).resolve().parents[1] / "protocol"
for directory, version_key in (("v1", "protocolVersion"), ("pairing/v1", "pairingVersion")):
    root = protocol_root / directory
    pin = json.loads((root / "upstream.json").read_text())
    if pin[version_key] != 1 or not re.fullmatch(r"[0-9a-f]{40}", pin["commit"]):
        raise SystemExit(f"Invalid upstream pin: {directory}")
    for name, expected in pin["files"].items():
        actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
        if actual != expected:
            raise SystemExit(f"Pinned protocol drift: {directory}/{name}")
    print(f"Protocol {directory}: pinned contract and fixtures verified.")
