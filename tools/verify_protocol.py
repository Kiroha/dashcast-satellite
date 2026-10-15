#!/usr/bin/env python3
"""Verify the vendored guidance, video diagnostics and optional pairing contracts before tests."""
import hashlib
import json
import re
from pathlib import Path

protocol_root = Path(__file__).resolve().parents[1] / "protocol"
required_files = {
    "v1": {"PROTOCOL_V1.md", "navigation-fixtures.json", "diagnostics-video-schema.json", "diagnostics-video-fixtures.json"},
    "pairing/v1": {"PAIRING_V1.md", "pairing-fixtures.json"},
}
for directory, version_key in (("v1", "protocolVersion"), ("pairing/v1", "pairingVersion")):
    root = protocol_root / directory
    pin = json.loads((root / "upstream.json").read_text())
    if pin[version_key] != 1 or not re.fullmatch(r"[0-9a-f]{40}", pin["commit"]):
        raise SystemExit(f"Invalid upstream pin: {directory}")
    if set(pin["files"]) != required_files[directory]:
        raise SystemExit(f"Incomplete pinned contract: {directory}")
    for name, expected in pin["files"].items():
        if not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise SystemExit(f"Invalid fixture hash: {directory}/{name}")
        actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
        if actual != expected:
            raise SystemExit(f"Pinned protocol drift: {directory}/{name}")
    print(f"Protocol {directory}: pinned contract and fixtures verified.")
