#!/usr/bin/env python3
"""Build and inventory the exact deterministic Central Portal bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import zipfile
import xml.etree.ElementTree as ElementTree
from pathlib import Path


CHECKSUM_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")
CENTRAL_UPLOAD_LIMIT_BYTES = 1_000_000_000


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def exclusion(path: Path) -> str | None:
    name = path.name
    if name == "maven-metadata.xml" or name.startswith("maven-metadata.xml."):
        return "repository metadata is not part of a Central deployment"
    if name.endswith(tuple(f".asc{suffix}" for suffix in CHECKSUM_SUFFIXES)):
        return "signature checksum is not part of a Central deployment"
    return None


def is_primary_artifact(path: Path) -> bool:
    name = path.name
    return not name.endswith(CHECKSUM_SUFFIXES) and not name.endswith(".asc")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--max-bytes", type=int, default=CENTRAL_UPLOAD_LIMIT_BYTES)
    parser.add_argument("--require-signatures", action="store_true")
    parser.add_argument("--consume-repository", action="store_true")
    parser.add_argument("--required-coordinate", action="append", default=[])
    arguments = parser.parse_args()

    files = sorted(path for path in arguments.repository.rglob("*") if path.is_file())
    if not files:
        raise RuntimeError("Maven staging repository is empty")
    inventory = []
    included = []
    unsigned = []
    for path in files:
        relative = path.relative_to(arguments.repository)
        excluded = exclusion(path)
        entry = {
            "path": relative.as_posix(),
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
            "included": excluded is None,
        }
        if excluded:
            entry["exclusionReason"] = excluded
        else:
            included.append((relative, path))
            if is_primary_artifact(path) and not Path(f"{path}.asc").is_file():
                unsigned.append(relative.as_posix())
        inventory.append(entry)

    missing_coordinates = []
    for coordinate in arguments.required_coordinate:
        group, artifact, version = coordinate.split(":", 2)
        pom = (
            arguments.repository
            / Path(*group.split("."))
            / artifact
            / version
            / f"{artifact}-{version}.pom"
        )
        if not pom.is_file():
            missing_coordinates.append(coordinate)

    invalid_license_poms = []
    for pom in (path for path in files if path.suffix == ".pom"):
        root = ElementTree.parse(pom).getroot()
        namespace = {"m": root.tag.partition("}")[0].lstrip("{")} if "}" in root.tag else {}
        prefix = "m:" if namespace else ""
        licenses = root.findall(f"{prefix}licenses/{prefix}license", namespace)
        valid = any(
            license_node.findtext(f"{prefix}name", default="", namespaces=namespace)
            == "GNU General Public License v3.0 or later"
            and license_node.findtext(f"{prefix}url", default="", namespaces=namespace)
            == "https://www.gnu.org/licenses/gpl-3.0.txt"
            and license_node.findtext(f"{prefix}distribution", default="", namespaces=namespace)
            == "repo"
            for license_node in licenses
        )
        if not valid:
            invalid_license_poms.append(pom.relative_to(arguments.repository).as_posix())

    if arguments.require_signatures and unsigned:
        print("Unsigned Central artifacts:\n" + "\n".join(unsigned))
        return 1
    if missing_coordinates:
        print("Missing required Maven coordinates:\n" + "\n".join(missing_coordinates))
        return 1
    if invalid_license_poms:
        print("Maven POMs with missing or changed licence metadata:\n" + "\n".join(invalid_license_poms))
        return 1
    if arguments.consume_repository:
        try:
            arguments.output.resolve().relative_to(arguments.repository.resolve())
        except ValueError:
            pass
        else:
            raise RuntimeError("consumed repository must not contain the Central output bundle")

    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(
        arguments.output,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
        allowZip64=True,
    ) as bundle:
        for relative, path in included:
            info = zipfile.ZipInfo(relative.as_posix(), (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            with path.open("rb") as source, bundle.open(info, "w", force_zip64=True) as target:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    target.write(chunk)
            if arguments.consume_repository:
                path.unlink()

    with zipfile.ZipFile(arguments.output) as bundle:
        entries = [
            {
                "path": info.filename,
                "bytes": info.file_size,
                "compressedBytes": info.compress_size,
                "crc32": f"{info.CRC:08x}",
            }
            for info in bundle.infolist()
        ]
    bundle_size = arguments.output.stat().st_size
    report = {
        "schemaVersion": 1,
        "repository": str(arguments.repository),
        "artifactCount": len(inventory),
        "includedArtifactCount": len(included),
        "artifacts": inventory,
        "unsignedPrimaryArtifacts": unsigned,
        "requiredCoordinates": arguments.required_coordinate,
        "missingRequiredCoordinates": missing_coordinates,
        "invalidLicensePoms": invalid_license_poms,
        "bundle": {
            "path": str(arguments.output),
            "bytes": bundle_size,
            "sha256": sha256(arguments.output),
            "entryCount": len(entries),
            "entries": entries,
        },
        "centralPortalUploadLimitBytes": arguments.max_bytes,
        "belowCentralPortalUploadLimit": bundle_size < arguments.max_bytes,
        "repositoryConsumedWhileBundling": arguments.consume_repository,
    }
    arguments.report.parent.mkdir(parents=True, exist_ok=True)
    arguments.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    if bundle_size >= arguments.max_bytes:
        print(f"Central bundle is {bundle_size} bytes; limit is {arguments.max_bytes} bytes")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
