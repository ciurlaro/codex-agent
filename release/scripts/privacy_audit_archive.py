"""Static archive inspection used by the iOS privacy audit."""

from __future__ import annotations

import collections
import hashlib
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

REVIEWED_REASONS = {
    "NSPrivacyAccessedAPICategoryFileTimestamp": ["C617.1"],
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _run(command: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(command, check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def _tool(name: str) -> list[str]:
    if shutil.which("xcrun"):
        return ["xcrun", name]
    resolved = shutil.which(name)
    if not resolved:
        raise RuntimeError(f"required audit tool is missing: {name}")
    return [resolved]


def _dependency_name(member: str) -> str:
    stem = member
    for suffix in (".rcgu.o", ".o"):
        if stem.endswith(suffix):
            stem = stem[: -len(suffix)]
    stem = stem.split(".", 1)[0]
    return re.sub(r"-[0-9a-f]{16}$", "", stem)


def _archive_members(archive: Path) -> Iterator[tuple[str, bytes, str]]:
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
            contents = source.read(remaining_size)
            if len(contents) != remaining_size:
                raise RuntimeError(f"truncated ar member at byte {offset}: {archive}")
            if declared_size % 2 and len(source.read(1)) != 1:
                raise RuntimeError(f"missing ar alignment byte after byte {offset}: {archive}")
            if name == "//":
                long_names = contents
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
            yield name, contents, kind


def inspect_archive(slice_name: str, archive: Path) -> dict:
    names = []
    members = []
    dependencies: dict[str, dict] = {}
    detected: dict[str, list[dict]] = collections.defaultdict(list)
    with tempfile.TemporaryDirectory(prefix="codex-privacy-member-") as temporary:
        object_path = Path(temporary, "member.o")
        for index, (name, contents, kind) in enumerate(_archive_members(archive)):
            names.append(name)
            if kind != "object":
                members.append(
                    {
                        "index": index,
                        "name": name,
                        "kind": kind,
                        "bytes": len(contents),
                        "sha256": _sha256_bytes(contents),
                    }
                )
                continue
            object_path.write_bytes(contents)
            undefined = _run(_tool("nm") + ["-u", str(object_path)])
            strings = _run(_tool("strings") + ["-a", str(object_path)])
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
            dependency = _dependency_name(name)
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
