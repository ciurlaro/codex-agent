#!/usr/bin/env python3
"""Describe the immutable SwiftPM asset and verified publication payloads."""

from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def zip_members(path: Path) -> list[dict]:
    members = []
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            digest = hashlib.sha256()
            with archive.open(info) as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
            members.append(
                {
                    "path": info.filename,
                    "bytes": info.file_size,
                    "compressedBytes": info.compress_size,
                    "sha256": digest.hexdigest(),
                }
            )
    return members


def file_record(path: Path) -> dict:
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": sha256(path)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--swift-asset", required=True, type=Path)
    parser.add_argument("--swift-checksum", required=True, type=Path)
    parser.add_argument("--central-bundle", required=True, type=Path)
    parser.add_argument("--central-report", required=True, type=Path)
    parser.add_argument("--privacy-report", required=True, type=Path)
    parser.add_argument("--approvals", required=True, type=Path)
    parser.add_argument("--package-swift", required=True, type=Path)
    parser.add_argument("--resource-report", action="append", default=[], type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--candidate-manifest", required=True, type=Path)
    arguments = parser.parse_args()

    swift_hash = sha256(arguments.swift_asset)
    recorded_checksum = arguments.swift_checksum.read_text().strip()
    if recorded_checksum != swift_hash:
        raise RuntimeError("SwiftPM asset checksum file does not match the immutable ZIP")
    central = json.loads(arguments.central_report.read_text())
    privacy = json.loads(arguments.privacy_report.read_text())
    approvals = json.loads(arguments.approvals.read_text())
    if central["bundle"]["sha256"] != sha256(arguments.central_bundle):
        raise RuntimeError("Central report does not describe the exact deployment bundle")
    if not central["belowCentralPortalUploadLimit"]:
        raise RuntimeError("Central deployment bundle exceeds the enforced upload limit")
    if not privacy["passed"]:
        raise RuntimeError("static XCFramework privacy audit did not pass")
    if approvals["privacyManifestSha256"] != privacy["manifest"]["sha256"]:
        raise RuntimeError("approval scope does not match the audited privacy manifest")
    if approvals["dataFlowInventorySha256"] != privacy["dataFlowInventory"]["sha256"]:
        raise RuntimeError("approval scope does not match the audited data-flow inventory")

    resource_reports = [
        {**file_record(path), "measurements": json.loads(path.read_text())}
        for path in arguments.resource_report
    ]
    if not resource_reports:
        raise RuntimeError("at least one release resource report is required")
    measurements = [report["measurements"] for report in resource_reports]
    resource_summary = {
        "maximumFilesystemUsedBytes": max(item["disk"]["peakUsedBytes"] for item in measurements),
        "maximumPhaseDiskIncreaseBytes": max(
            item["disk"]["peakIncreaseBytes"] for item in measurements
        ),
        "minimumFilesystemAvailableBytes": min(
            item["disk"]["minimumAvailableBytes"] for item in measurements
        ),
        "minimumSystemAvailableBytes": min(
            item["memory"]["minimumSystemAvailableBytes"] for item in measurements
        ),
        "maximumCommandProcessTreeResidentBytes": max(
            item["memory"]["peakCommandProcessTreeResidentBytes"] for item in measurements
        ),
    }
    report = {
        "schemaVersion": 1,
        "releaseTag": arguments.release_tag,
        "commit": arguments.commit,
        "swiftPackageAsset": {
            **file_record(arguments.swift_asset),
            "members": zip_members(arguments.swift_asset),
        },
        "centralDeployment": central,
        "privacyAudit": privacy,
        "resourceMeasurements": resource_reports,
        "resourceSummary": resource_summary,
    }
    manifest = {
        "schemaVersion": 1,
        "releaseTag": arguments.release_tag,
        "commit": arguments.commit,
        "swiftAsset": file_record(arguments.swift_asset),
        "centralBundle": file_record(arguments.central_bundle),
        "centralReportSha256": sha256(arguments.central_report),
        "privacyReportSha256": sha256(arguments.privacy_report),
        "approvalRecordSha256": sha256(arguments.approvals),
        "privacyManifestSha256": privacy["manifest"]["sha256"],
        "dataFlowInventorySha256": privacy["dataFlowInventory"]["sha256"],
        "packageSwiftSha256": sha256(arguments.package_swift),
        "resourceReportSha256": {
            path.name: sha256(path) for path in arguments.resource_report
        },
    }
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    arguments.candidate_manifest.parent.mkdir(parents=True, exist_ok=True)
    arguments.candidate_manifest.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
