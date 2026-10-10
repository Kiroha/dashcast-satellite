#!/usr/bin/env python3
"""Import reviewed receiver masks and non-location test images without requiring its APK.

Run with --receiver pointing to a clone containing the pinned commits. Only the
reference table and test corpus are generated; MapsManeuverImage remains a reviewed
portable Kotlin adaptation. The original corpus was independently rendered upstream.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

COMMIT = "3565221625ef0b3b0e039d7662abff0f5f39aca6"
FIELD_COMMIT = "a26fe1ac20b8fd8be272332e7a1f4f92e11f80f9"
REPOSITORY = "https://github.com/Kiroha/byd-dashcast"
SOURCE_ROOT = "app/src/main/java/com/byd/dashcast/hud"
FIXTURE_ROOT = "app/src/test/resources/navigation"
# Source identifiers are used only by this import tool. Runtime labels are portable strings.
LABELS = {
    1: "left", 2: "right", 3: "slight_left", 5: "slight_right",
    7: "sharp_left", 8: "sharp_right", 9: "uturn_left", 10: "uturn_right",
    11: "straight", 48: "destination",
    15: "roundabout_ccw_left_shape", 16: "roundabout_ccw_right_shape",
    17: "roundabout_cw_left_shape", 18: "roundabout_cw_right_shape",
    19: "roundabout_ccw_straight_shape", 20: "roundabout_cw_straight_shape",
}
# These labels remain competitors even when the wire protocol cannot express them.
# Never replace merge symbols with a similar turn or remove their masks.


def semantics(name, source_id, clockwise):
    if name.startswith("ic_merge"):
        return "unsupported_" + name, None
    label = LABELS[source_id]
    maneuver = ("roundabout_cw" if clockwise else "roundabout_ccw") if clockwise is not None else label
    return label, maneuver


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receiver", required=True, type=Path)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()

    def source(path, commit=COMMIT):
        return subprocess.check_output(["git", "-C", str(args.receiver), "show", f"{commit}:{path}"])

    raw_manifest = source(FIXTURE_ROOT + "/maps-26.33/manifest.json")
    upstream = json.loads(raw_manifest)
    raw_references = source(SOURCE_ROOT + "/MapsManeuverReferences.kt")
    references = re.findall(
        r'// (ic_\w+)\s+reference\((\d+), (null|true|false), ([\d.]+), "([^"]+)",\s*"([^"]+)"\)',
        raw_references.decode())
    if len(references) != 586:
        raise ValueError("Reviewed reference count changed")
    kotlin = [
        "package io.github.kiroha.dashcast.satellite.navigation", "",
        "// Adapted from DashCast, Copyright (c) 2026 Cedric Carre, MIT.",
        "// Full upstream license: docs/PARSER_UPSTREAM_LICENSE.txt.", "",
        "/** Reviewed Maps 26.33 masks; regenerate with tools/navigation/import_maps_corpus.py.",
        " * Distinct semantic labels preserve ambiguity checks even when portable outputs coincide.",
        " * Unsupported merge glyphs remain rejecting competitors. No APK resource IDs are used.",
        " */", "internal object MapsManeuverReferences {",
        "    data class Reference(val label: String, val maneuver: String?,",
        "                         val aspect: Double, val rows: IntArray, val strongRows: IntArray)", "",
        "    private fun rows(bits: String) = bits.split(',').map { it.toUInt(16).toInt() }.toIntArray()",
        "    private fun reference(label: String, maneuver: String?, aspect: Double, bits: String, strong: String) =",
        "        Reference(label, maneuver, aspect, rows(bits), rows(strong))", "",
        "    val entries: List<Reference> = listOf(",
    ]
    for name, source_id, clockwise, aspect, rows, strong in references:
        direction = None if clockwise == "null" else clockwise == "true"
        label, maneuver = semantics(name, int(source_id), direction)
        value = "null" if maneuver is None else json.dumps(maneuver)
        kotlin += [f"        // {name}",
                   f'        reference("{label}", {value}, {aspect}, "{rows}",',
                   f'            "{strong}"),']
    kotlin += ["    )", "}", ""]
    reference_target = args.repo / "app/src/main/java/io/github/kiroha/dashcast/satellite/navigation/MapsManeuverReferences.kt"
    reference_target.parent.mkdir(parents=True, exist_ok=True)
    reference_target.write_text("\n".join(kotlin))

    fixtures = args.repo / FIXTURE_ROOT
    corpus = fixtures / "maps-26.33"
    corpus.mkdir(parents=True, exist_ok=True)
    entries = []
    for old in upstream["entries"]:
        entry = {k: v for k, v in old.items() if k not in {"iconId", "clockwise"}}
        label, maneuver = semantics(old["name"], old["iconId"], old["clockwise"])
        entry.update({"referenceLabel": label, "maneuver": maneuver, "expectedManeuver": maneuver})
        if maneuver is None:
            entry["unsupportedReason"] = "Protocol v1 has no merge maneuver. Its masks remain rejecting competitors."
        data = source(FIXTURE_ROOT + "/maps-26.33/" + old["name"] + ".png")
        if sha(data) != old["pngSha256"]:
            raise ValueError("Source fixture checksum mismatch")
        (corpus / (old["name"] + ".png")).write_bytes(data)
        entries.append(entry)
    negative = []
    for old in upstream["negative"]:
        entry = {k: v for k, v in old.items() if k != "iconId"}
        entry["expectedManeuver"] = None
        data = source(FIXTURE_ROOT + "/maps-26.33/" + old["name"] + ".png")
        if sha(data) != old["pngSha256"]:
            raise ValueError("Source fixture checksum mismatch")
        (corpus / (old["name"] + ".png")).write_bytes(data)
        negative.append(entry)
    field_path = FIXTURE_ROOT + "/maps-left-seal-20261009.png"
    field = source(field_path, FIELD_COMMIT)
    (fixtures / "maps-left-seal-20261009.png").write_bytes(field)
    pinned_sources = [SOURCE_ROOT + "/MapsManeuverImage.kt", SOURCE_ROOT + "/MapsManeuverReferences.kt",
                      "app/src/test/java/com/byd/dashcast/hud/MapsManeuverImageTest.kt",
                      "app/src/test/java/com/byd/dashcast/hud/MapsManeuverCorpusTest.kt",
                      FIXTURE_ROOT + "/maps-26.33/manifest.json", "tools/navigation/build_maps_corpus.py"]
    manifest = {
        "receiverRepository": REPOSITORY, "receiverCommit": COMMIT,
        "sourceSha256": {path: sha(source(path)) for path in pinned_sources},
        "upstreamCorpus": {k: v for k, v in upstream.items() if k not in {"entries", "negative"}},
        "referenceCount": len(references),
        "fieldCapture": {"receiverCommit": FIELD_COMMIT, "sourcePath": field_path,
                         "pngSha256": sha(field), "expectedManeuver": "left",
                         "provenance": "Unchanged non-location glyph from the driver's October 9, 2026 Maps export. No notification metadata is included.",
                         "provenanceFile": FIXTURE_ROOT + "/README.md",
                         "provenanceFileSha256": sha(source(FIXTURE_ROOT + "/README.md", FIELD_COMMIT))},
        "entries": entries, "negative": negative,
    }
    (corpus / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Imported {len(references)} masks, {len(entries)} labelled fixtures and {len(negative)} negatives from {COMMIT}")


if __name__ == "__main__":
    main()
