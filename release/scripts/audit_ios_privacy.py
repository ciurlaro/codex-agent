#!/usr/bin/env python3
"""Audit every object member of both static iOS XCFramework slices."""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import plistlib
import re
import shutil
import subprocess
import tempfile
from collections.abc import Iterator
from pathlib import Path


CATEGORY_PATTERNS = {
    "NSPrivacyAccessedAPICategoryFileTimestamp": [
        re.compile(
            rb"_(?:fstat|fstatat|lstat|stat|fgetattrlist|getattrlist|getattrlistat|getattrlistbulk)(?:\s|$)"
        ),
        re.compile(
            rb"(?:creationDate|modificationDate|contentModificationDate|fileModificationDate)(?:\x00|$)"
        ),
        re.compile(rb"NSURL(?:ContentModification|Creation)DateKey"),
        re.compile(rb"attributesOfItemAtPath:"),
    ],
    "NSPrivacyAccessedAPICategorySystemBootTime": [
        re.compile(rb"_mach_absolute_time(?:\s|$)"),
        re.compile(rb"systemUptime(?:\x00|$)"),
    ],
    "NSPrivacyAccessedAPICategoryDiskSpace": [
        re.compile(rb"_(?:fstatfs|statfs|fstatvfs|statvfs)(?:\s|$)"),
        re.compile(rb"volume(?:Available|Total)Capacity(?:ForImportantUsage|ForOpportunisticUsage)?Key"),
        re.compile(rb"system(?:FreeSize|Size)(?:\x00|$)"),
        re.compile(rb"attributesOfFileSystemForPath:"),
    ],
    "NSPrivacyAccessedAPICategoryActiveKeyboards": [
        re.compile(rb"activeInputModes(?:\x00|$)"),
    ],
    "NSPrivacyAccessedAPICategoryUserDefaults": [
        re.compile(rb"(?:NSUserDefaults|CFPreferences|standardUserDefaults)"),
    ],
}

# This is the reviewed reason for the one expected 0.2.0 use. Any newly
# detected category intentionally has no policy and therefore blocks release.
REVIEWED_REASONS = {
    "NSPrivacyAccessedAPICategoryFileTimestamp": ["C617.1"],
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def run(command: list[str], *, stdout=subprocess.PIPE) -> subprocess.CompletedProcess:
    return subprocess.run(command, check=False, stdout=stdout, stderr=subprocess.PIPE)


def tool(name: str) -> list[str]:
    if shutil.which("xcrun"):
        return ["xcrun", name]
    resolved = shutil.which(name)
    if not resolved:
        raise RuntimeError(f"required audit tool is missing: {name}")
    return [resolved]


def dependency_name(member: str) -> str:
    stem = member
    for suffix in (".rcgu.o", ".o"):
        if stem.endswith(suffix):
            stem = stem[: -len(suffix)]
    stem = stem.split(".", 1)[0]
    return re.sub(r"-[0-9a-f]{16}$", "", stem)


def archive_members(archive: Path) -> Iterator[tuple[str, bytes, str]]:
    indexes = {"/", "/SYM64/", "__.SYMDEF", "__.SYMDEF SORTED", "__.SYMDEF_64", "__.SYMDEF_64 SORTED"}
    long_names: bytes | None = None
    with archive.open("rb") as source:
        magic = source.read(8)
        if magic == b"!<thin>\n":
            raise RuntimeError(f"thin archives are not valid XCFramework members: {archive}")
        if magic != b"!<arch>\n":
            raise RuntimeError(f"static framework is not an ar archive: {archive}")
        while True:
            offset = source.tell()
            header = source.read(60)
            if not header:
                break
            if len(header) != 60 or header[58:60] != b"`\n":
                raise RuntimeError(f"invalid ar member header at byte {offset}: {archive}")
            try:
                declared_size = int(header[48:58].decode("ascii").strip())
            except ValueError as error:
                raise RuntimeError(f"invalid ar member size at byte {offset}: {archive}") from error
            name_field = header[:16].decode("utf-8", errors="strict").rstrip()
            remaining_size = declared_size
            if name_field.startswith("#1/"):
                name_size = int(name_field[3:])
                if name_size > remaining_size:
                    raise RuntimeError(f"invalid BSD ar filename at byte {offset}: {archive}")
                name_bytes = source.read(name_size)
                if len(name_bytes) != name_size:
                    raise RuntimeError(f"truncated BSD ar filename at byte {offset}: {archive}")
                name = name_bytes.rstrip(b"\x00").decode("utf-8", errors="strict")
                remaining_size -= name_size
            else:
                name = name_field
            member_contents = source.read(remaining_size)
            if len(member_contents) != remaining_size:
                raise RuntimeError(f"truncated ar member at byte {offset}: {archive}")
            if declared_size % 2 and len(source.read(1)) != 1:
                raise RuntimeError(f"missing ar alignment byte after byte {offset}: {archive}")
            if name == "//":
                long_names = member_contents
            elif re.fullmatch(r"/[0-9]+", name):
                if long_names is None:
                    raise RuntimeError(f"ar member refers to a missing filename table: {archive}")
                name_offset = int(name[1:])
                name_end = long_names.find(b"/\n", name_offset)
                if name_end < 0:
                    name_end = long_names.find(b"\x00", name_offset)
                if name_end < 0:
                    name_end = len(long_names)
                name = long_names[name_offset:name_end].decode("utf-8", errors="strict")
            elif name.endswith("/") and name not in indexes:
                name = name[:-1]
            kind = "archive-index" if name in indexes or name == "//" else "object"
            yield name, member_contents, kind


def inspect_archive(slice_name: str, archive: Path) -> dict:
    names = []
    members = []
    dependencies: dict[str, dict] = {}
    detected: dict[str, list[dict]] = collections.defaultdict(list)
    with tempfile.TemporaryDirectory(prefix="codex-privacy-member-") as temporary:
        object_path = Path(temporary, "member.o")
        for index, (name, object_contents, kind) in enumerate(archive_members(archive)):
            names.append(name)
            if kind != "object":
                members.append(
                    {
                        "index": index,
                        "name": name,
                        "kind": kind,
                        "bytes": len(object_contents),
                        "sha256": sha256_bytes(object_contents),
                    }
                )
                continue
            object_path.write_bytes(object_contents)
            undefined = run(tool("nm") + ["-u", str(object_path)])
            strings = run(tool("strings") + ["-a", str(object_path)])
            if undefined.returncode or strings.returncode:
                raise RuntimeError(f"could not inspect {slice_name}:{name}")
            evidence_bytes = undefined.stdout + b"\n" + strings.stdout
            categories = {}
            for category, patterns in CATEGORY_PATTERNS.items():
                evidence = sorted(
                    {
                        match.group(0).decode(errors="replace").strip("\x00 \t\r\n")
                        for pattern in patterns
                        for match in pattern.finditer(evidence_bytes)
                    }
                )
                if evidence:
                    categories[category] = evidence
                    detected[category].append({"member": name, "evidence": evidence})
            size = object_path.stat().st_size
            dependency = dependency_name(name)
            aggregate = dependencies.setdefault(
                dependency,
                {"memberCount": 0, "bytes": 0, "categories": set()},
            )
            aggregate["memberCount"] += 1
            aggregate["bytes"] += size
            aggregate["categories"].update(categories)
            members.append(
                {
                    "index": index,
                    "name": name,
                    "kind": "object",
                    "dependency": dependency,
                    "bytes": size,
                    "sha256": sha256(object_path),
                    "requiredReasonApis": categories,
                }
            )
    duplicates = sorted(name for name, count in collections.Counter(names).items() if count > 1)
    return {
        "slice": slice_name,
        "archive": str(archive),
        "archiveBytes": archive.stat().st_size,
        "archiveSha256": sha256(archive),
        "archiveMemberCount": len(names),
        "duplicateArchiveMemberNames": duplicates,
        "objectMemberCount": sum(member["kind"] == "object" for member in members),
        "members": members,
        "dependencies": [
            {
                "name": name,
                "memberCount": value["memberCount"],
                "bytes": value["bytes"],
                "requiredReasonCategories": sorted(value["categories"]),
            }
            for name, value in sorted(dependencies.items())
        ],
        "detectedCategories": dict(sorted(detected.items())),
    }


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
