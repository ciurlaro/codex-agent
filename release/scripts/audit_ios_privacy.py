#!/usr/bin/env python3
"""Audit every object member of both static iOS XCFramework slices."""

from __future__ import annotations

import argparse
import json
import plistlib
from pathlib import Path

from privacy_audit_archive import CATEGORY_PATTERNS, REVIEWED_REASONS, inspect_archive, sha256


def manifest_declarations(path: Path) -> dict[str, list[str]]:
    with path.open("rb") as source:
        manifest = plistlib.load(source)
    declarations = {}
    for entry in manifest.get("NSPrivacyAccessedAPITypes", []):
        category = entry["NSPrivacyAccessedAPIType"]
        if category in declarations:
            raise RuntimeError(f"duplicate manifest declaration: {category}")
        declarations[category] = sorted(entry.get("NSPrivacyAccessedAPITypeReasons", []))
    return declarations


def relative_manifests(root: Path) -> list[dict]:
    return [
        {
            "path": str(path.relative_to(root)),
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
        }
        for path in sorted(root.rglob("PrivacyInfo.xcprivacy"))
    ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--xcframework", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--data-flow-inventory", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()

    slices = []
    for slice_name in ("ios-arm64", "ios-arm64-simulator"):
        slices.append(
            inspect_archive(
                slice_name,
                arguments.xcframework / slice_name / "CodexAgent.framework" / "CodexAgent",
            )
        )

    detected = sorted(
        {
            category
            for slice_report in slices
            for category in slice_report["detectedCategories"]
        }
    )
    declarations = manifest_declarations(arguments.manifest)
    declared = sorted(declarations)
    missing = sorted(set(detected) - set(declared))
    unnecessary = sorted(set(declared) - set(detected))
    reason_mismatches = [
        {
            "category": category,
            "declared": declarations.get(category, []),
            "reviewed": REVIEWED_REASONS.get(category, []),
        }
        for category in detected
        if declarations.get(category, []) != REVIEWED_REASONS.get(category, [])
    ]

    framework_manifests = relative_manifests(arguments.xcframework)
    archived_manifests = relative_manifests(arguments.archive / "Products" / "Applications")
    reviewed_manifest_hash = sha256(arguments.manifest)
    placement_errors = []
    if len(framework_manifests) != 2:
        placement_errors.append("XCFramework must contain exactly two privacy manifests")
    if any(item["sha256"] != reviewed_manifest_hash for item in framework_manifests):
        placement_errors.append("XCFramework privacy manifest differs from the reviewed manifest")
    if not archived_manifests:
        placement_errors.append("archived sample application contains no privacy manifest")
    if reviewed_manifest_hash not in {item["sha256"] for item in archived_manifests}:
        placement_errors.append("reviewed privacy manifest is absent from archived sample application")

    passed = not (missing or unnecessary or reason_mismatches or placement_errors)
    report = {
        "schemaVersion": 1,
        "scope": "every static archive object member in both XCFramework slices",
        "passed": passed,
        "manifest": {
            "path": str(arguments.manifest),
            "bytes": arguments.manifest.stat().st_size,
            "sha256": reviewed_manifest_hash,
            "declaredRequiredReasonApis": declarations,
        },
        "dataFlowInventory": {
            "path": str(arguments.data_flow_inventory),
            "bytes": arguments.data_flow_inventory.stat().st_size,
            "sha256": sha256(arguments.data_flow_inventory),
        },
        "auditedRequiredReasonCategories": sorted(CATEGORY_PATTERNS),
        "detectedRequiredReasonCategories": detected,
        "comparison": {
            "missingDeclarations": missing,
            "unnecessaryDeclarations": unnecessary,
            "reasonMismatches": reason_mismatches,
            "placementErrors": placement_errors,
        },
        "frameworkManifests": framework_manifests,
        "archivedSampleApplicationManifests": archived_manifests,
        "slices": slices,
    }
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    if not passed:
        print(json.dumps(report["comparison"], indent=2, sort_keys=True))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
