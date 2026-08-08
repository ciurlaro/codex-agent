#!/usr/bin/env python3
"""Verify a downloaded release candidate before any public mutation."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--payload", required=True, type=Path)
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--expected-commit", required=True)
    arguments = parser.parse_args()

    manifest_path = arguments.payload / "candidate-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    if manifest["commit"] != arguments.expected_commit:
        raise RuntimeError("candidate commit does not match the successful workflow run")
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", manifest["releaseTag"]):
        raise RuntimeError("candidate release tag is invalid")

    for key in ("swiftAsset", "centralBundle"):
        record = manifest[key]
        path = arguments.payload / Path(record["path"]).name
        if path.stat().st_size != record["bytes"] or sha256(path) != record["sha256"]:
            raise RuntimeError(f"candidate {key} differs from its verified bytes")

    checks = {
        "centralReportSha256": arguments.payload / "central-bundle-report.json",
        "privacyReportSha256": arguments.payload / "privacy-audit.json",
        "approvalRecordSha256": arguments.repository / "release/0.2.0-approvals.json",
        "packageSwiftSha256": arguments.repository / "Package.swift",
    }
    for key, path in checks.items():
        if sha256(path) != manifest[key]:
            raise RuntimeError(f"candidate binding mismatch: {key}")
    for name, expected_hash in manifest["resourceReportSha256"].items():
        if sha256(arguments.payload / "resources" / name) != expected_hash:
            raise RuntimeError(f"candidate resource report differs from verified bytes: {name}")
    approvals = json.loads((arguments.repository / "release/0.2.0-approvals.json").read_text())
    if approvals["privacyManifestSha256"] != manifest["privacyManifestSha256"]:
        raise RuntimeError("candidate privacy manifest is outside the approved scope")
    if approvals["dataFlowInventorySha256"] != manifest["dataFlowInventorySha256"]:
        raise RuntimeError("candidate data-flow inventory is outside the approved scope")
    print(manifest["releaseTag"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
