#!/usr/bin/env python3
"""Create and verify strict CI lane and validation receipts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import zipfile
from pathlib import Path, PurePosixPath

from impact import LANES


SCHEMA_VERSION = 1
LANE_RECEIPT_SCHEMA_VERSION = 2
INPUT_NAMES = {
    "production": "production-inputs.git-tree",
    "test": "test-inputs.git-tree",
    "metadata": "metadata-inputs.git-tree",
}
ACTION_BY_CATEGORY = {"production": "build", "test": "test", "metadata": "metadata"}
VALIDATION_ACTIONS_KEY = "validationActions"
VALIDATION_ACTIONS = frozenset(("build", "test", "metadata"))


def read_json(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected JSON object: {path}")
    return value


def write_json(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def parse_mapping(values: list[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for value in values:
        key, separator, item = value.partition("=")
        if not separator or not key or not item or key in result:
            raise ValueError(f"Expected one unique NAME=VALUE entry, got {value!r}")
        result[key] = item
    return result


def parse_validation_actions(toolchain: dict[str, str]) -> frozenset[str]:
    raw = toolchain.get(VALIDATION_ACTIONS_KEY, "")
    actions = raw.split(",")
    if (
        not raw
        or actions != sorted(set(actions))
        or not set(actions).issubset(VALIDATION_ACTIONS)
    ):
        raise ValueError("Lane receipt validation action coverage is missing or malformed")
    return frozenset(actions)


def parse_files(values: list[str], root: Path, include_bytes: bool) -> list[dict[str, object]]:
    result: list[dict[str, object]] = []
    seen: set[str] = set()
    for value in values:
        relative, separator, kind = value.rpartition("=")
        candidate = PurePosixPath(relative)
        if not separator or not kind or candidate.is_absolute() or ".." in candidate.parts or relative in seen:
            raise ValueError(f"Expected one safe unique RELATIVE_PATH=KIND entry, got {value!r}")
        file = root / Path(*candidate.parts)
        if not file.is_file() or file.is_symlink():
            raise ValueError(f"Declared receipt file is missing, non-regular, or a symlink: {relative}")
        seen.add(relative)
        item: dict[str, object] = {"relativePath": relative, "kind": kind}
        if include_bytes:
            item["bytes"] = file.stat().st_size
        item["sha256"] = hashlib.sha256(file.read_bytes()).hexdigest()
        result.append(item)
    return sorted(result, key=lambda item: str(item["relativePath"]))


def required_lanes(plan: dict[str, object]) -> list[str]:
    lanes = plan.get("lanes")
    if not isinstance(lanes, dict) or set(lanes) != set(LANES):
        raise ValueError("Impact plan lane set does not match schema v1")
    return [
        lane
        for lane in LANES
        if any(bool(lanes[lane].get(action)) for action in ("build", "test", "metadata"))
    ]


def create_receipt(arguments: argparse.Namespace) -> None:
    plan_path = arguments.plan.resolve()
    plan = read_json(plan_path)
    if plan.get("schemaVersion") != SCHEMA_VERSION or arguments.lane not in LANES:
        raise ValueError("Unsupported plan schema or lane")
    output_root = arguments.output.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    input_files: dict[str, str] = {}
    for category, filename in INPUT_NAMES.items():
        source = plan_path.parent / "inventories" / arguments.lane / filename
        if not source.is_file():
            raise ValueError(f"Missing planned lane inventory: {source}")
        shutil.copyfile(source, output_root / filename)
        input_files[category] = filename
    runner = parse_mapping(arguments.runner)
    toolchain = parse_mapping(arguments.toolchain)
    parse_validation_actions(toolchain)
    if not runner or not (set(toolchain) - {VALIDATION_ACTIONS_KEY}):
        raise ValueError("Runner and toolchain identities must be explicit and non-empty")
    receipt = {
        "schemaVersion": LANE_RECEIPT_SCHEMA_VERSION,
        "repository": plan["repository"],
        "workflowPath": arguments.workflow_path,
        "event": plan["event"],
        "runId": arguments.run_id,
        "runAttempt": arguments.run_attempt,
        "pullRequest": plan["pullRequest"],
        "baseCommit": plan["baseCommit"],
        "headCommit": plan["headCommit"],
        "validationCommit": plan["validationCommit"],
        "validationTree": plan["validationTree"],
        "lane": arguments.lane,
        "artifactName": arguments.artifact_name,
        "runner": runner,
        "toolchain": toolchain,
        "inputFiles": input_files,
        "artifacts": parse_files(arguments.artifact, output_root, include_bytes=True),
        "evidence": parse_files(arguments.evidence, output_root, include_bytes=False),
        "result": "passed",
    }
    write_json(output_root / "lane-receipt.json", receipt)


def validate_receipt(
    receipt_path: Path,
    plan_path: Path,
    root: Path,
    lane: str | None = None,
    allow_compatible: bool = False,
    allow_cross_pr: bool = False,
    runner: dict[str, str] | None = None,
    toolchain: dict[str, str] | None = None,
    categories: tuple[str, ...] = tuple(INPUT_NAMES),
) -> dict[str, object]:
    receipt = read_json(receipt_path)
    plan = read_json(plan_path)
    expected_keys = {
        "schemaVersion", "repository", "workflowPath", "event", "runId", "runAttempt", "pullRequest",
        "baseCommit", "headCommit", "validationCommit", "validationTree", "lane", "artifactName", "runner",
        "toolchain", "inputFiles", "artifacts", "evidence", "result",
    }
    if set(receipt) != expected_keys or receipt["schemaVersion"] != LANE_RECEIPT_SCHEMA_VERSION:
        raise ValueError("Unsupported or non-exact lane receipt schema")
    receipt_lane = receipt["lane"]
    if receipt_lane not in LANES or (lane is not None and receipt_lane != lane):
        raise ValueError("Lane receipt identity mismatch")
    if receipt["repository"] != plan["repository"]:
        raise ValueError("Lane receipt repository mismatch")
    if allow_cross_pr:
        pass
    elif allow_compatible:
        if plan["pullRequest"] is None or receipt["pullRequest"] != plan["pullRequest"]:
            raise ValueError("Reusable lane receipt is outside this PR scope")
    else:
        for field in (
            "event", "pullRequest", "baseCommit", "headCommit", "validationCommit", "validationTree"
        ):
            if receipt[field] != plan[field]:
                raise ValueError(f"Lane receipt {field} mismatch")
    if receipt["result"] != "passed" or receipt["workflowPath"] != ".github/workflows/ci.yml":
        raise ValueError("Lane was not produced by a passing authoritative CI workflow")
    if (
        not isinstance(receipt["artifactName"], str)
        or not receipt["artifactName"].startswith(f"codex-agent-ci-{receipt_lane}-")
        or any(character in receipt["artifactName"] for character in "/\\")
    ):
        raise ValueError("Lane receipt artifact name mismatch")
    receipt_runner = receipt["runner"]
    if (
        not isinstance(receipt_runner, dict)
        or not receipt_runner
        or not all(isinstance(key, str) and key and isinstance(item, str) and item for key, item in receipt_runner.items())
        or runner is not None and receipt_runner != runner
    ):
        raise ValueError("Lane receipt runner identity mismatch")
    receipt_toolchain = receipt["toolchain"]
    if (
        not isinstance(receipt_toolchain, dict)
        or not receipt_toolchain
        or not all(
            isinstance(key, str)
            and key
            and isinstance(item, str)
            and (item or key == VALIDATION_ACTIONS_KEY)
            for key, item in receipt_toolchain.items()
        )
    ):
        raise ValueError("Lane receipt toolchain identity mismatch")
    actions = parse_validation_actions(receipt_toolchain)
    real_toolchain = {
        key: value for key, value in receipt_toolchain.items()
        if key != VALIDATION_ACTIONS_KEY
    }
    if not real_toolchain or (
        toolchain is not None
        and (VALIDATION_ACTIONS_KEY in toolchain or real_toolchain != toolchain)
    ):
        raise ValueError("Lane receipt toolchain identity mismatch")
    input_files = receipt["inputFiles"]
    if input_files != INPUT_NAMES:
        raise ValueError("Lane receipt inventory set mismatch")
    if not categories or not set(categories).issubset(INPUT_NAMES):
        raise ValueError("Unsupported lane inventory category selection")
    lanes = plan.get("lanes")
    lane_state = lanes.get(receipt_lane) if isinstance(lanes, dict) else None
    if not isinstance(lane_state, dict):
        raise ValueError("Impact plan lane state is malformed")
    if set(categories) == set(INPUT_NAMES):
        required_actions = {
            action for action in VALIDATION_ACTIONS if bool(lane_state.get(action))
        }
    elif set(categories) == {"production"}:
        required_actions = {"build"}
    else:
        required_actions = {
            ACTION_BY_CATEGORY[category]
            for category in categories
            if bool(lane_state.get(ACTION_BY_CATEGORY[category]))
        }
    if not required_actions.issubset(actions):
        raise ValueError(
            f"Lane validation action coverage mismatch: required={sorted(required_actions)} "
            f"actual={sorted(actions)}"
        )
    for category in categories:
        filename = INPUT_NAMES[category]
        actual = root / filename
        expected = plan_path.parent / "inventories" / str(receipt_lane) / filename
        if not actual.is_file() or actual.is_symlink() or actual.read_bytes() != expected.read_bytes():
            raise ValueError(f"Lane {category} inventory mismatch")
    declared: set[str] = {"lane-receipt.json", *INPUT_NAMES.values()}
    for collection, item_keys in (
        ("artifacts", {"relativePath", "kind", "bytes", "sha256"}),
        ("evidence", {"relativePath", "kind", "sha256"}),
    ):
        items = receipt[collection]
        if not isinstance(items, list):
            raise ValueError(f"Lane receipt {collection} is not a list")
        for item in items:
            if not isinstance(item, dict) or set(item) != item_keys:
                raise ValueError(f"Invalid {collection} entry")
            relative = str(item["relativePath"])
            path = PurePosixPath(relative)
            if path.is_absolute() or ".." in path.parts or relative in declared:
                raise ValueError(f"Unsafe or duplicate receipt path: {relative}")
            file = root / Path(*path.parts)
            if (
                not file.is_file()
                or file.is_symlink()
                or "bytes" in item and file.stat().st_size != item["bytes"]
                or hashlib.sha256(file.read_bytes()).hexdigest() != item["sha256"]
            ):
                raise ValueError(f"Missing or integrity-mismatched receipt file: {relative}")
            declared.add(relative)
    entries = list(root.rglob("*"))
    if any(entry.is_symlink() for entry in entries):
        raise ValueError("Lane artifact contains an undeclared symbolic link")
    actual = {file.relative_to(root).as_posix() for file in entries if file.is_file()}
    if actual != declared:
        raise ValueError(f"Lane file set mismatch: expected={sorted(declared)} actual={sorted(actual)}")
    return receipt


def aggregate(arguments: argparse.Namespace) -> None:
    plan = read_json(arguments.plan)
    receipts: dict[str, dict[str, object]] = {}
    for receipt_path in arguments.receipts.rglob("lane-receipt.json"):
        receipt = validate_receipt(
            receipt_path,
            arguments.plan,
            receipt_path.parent,
            allow_compatible=True,
        )
        lane = str(receipt["lane"])
        if lane in receipts:
            raise ValueError(f"Duplicate receipt for {lane}")
        receipts[lane] = receipt
    required = required_lanes(plan)
    if set(receipts) != set(required):
        raise ValueError(f"Validation receipt set mismatch: required={required} actual={sorted(receipts)}")
    if plan.get("androidEvidenceRequired") and "android" in required and not any(
        item["kind"] == "firebase-runtime-evidence" for item in receipts["android"]["evidence"]
    ):
        raise ValueError("Android Firebase runtime evidence is required")
    value = {
        "schemaVersion": SCHEMA_VERSION,
        "repository": plan["repository"],
        "event": plan["event"],
        "validationCommit": plan["validationCommit"],
        "validationTree": plan["validationTree"],
        "impactPlan": arguments.plan.name,
        "lanes": {
            lane: {
                "runId": receipts[lane]["runId"],
                "runAttempt": receipts[lane]["runAttempt"],
                "artifactName": receipts[lane]["artifactName"],
                "validationCommit": receipts[lane]["validationCommit"],
                "validationTree": receipts[lane]["validationTree"],
                "result": "passed",
            }
            for lane in required
        },
        "result": "passed",
    }
    write_json(arguments.output, value)


def safe_extract(archive: Path, destination: Path) -> None:
    if destination.exists() and any(destination.iterdir()):
        raise ValueError(f"Artifact destination must be empty: {destination}")
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        seen: set[str] = set()
        for member in source.infolist():
            path = PurePosixPath(member.filename)
            mode = member.external_attr >> 16
            if (
                not member.filename
                or "\\" in member.filename
                or member.filename in seen
                or path.is_absolute()
                or ".." in path.parts
                or stat.S_ISLNK(mode)
                or stat.S_IFMT(mode) and not (stat.S_ISREG(mode) or stat.S_ISDIR(mode))
            ):
                raise ValueError(f"Unsafe artifact member: {member.filename}")
            seen.add(member.filename)
            resolved = (destination / Path(*path.parts)).resolve()
            if destination.resolve() not in (resolved, *resolved.parents):
                raise ValueError(f"Artifact member escapes destination: {member.filename}")
        source.extractall(destination)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create")
    create.add_argument("--plan", type=Path, required=True)
    create.add_argument("--lane", choices=LANES, required=True)
    create.add_argument("--output", type=Path, required=True)
    create.add_argument("--workflow-path", default=".github/workflows/ci.yml")
    create.add_argument("--artifact-name", required=True)
    create.add_argument("--run-id", type=int, default=int(os.environ.get("GITHUB_RUN_ID", "1")))
    create.add_argument("--run-attempt", type=int, default=int(os.environ.get("GITHUB_RUN_ATTEMPT", "1")))
    create.add_argument("--runner", action="append", default=[])
    create.add_argument("--toolchain", action="append", default=[])
    create.add_argument("--artifact", action="append", default=[])
    create.add_argument("--evidence", action="append", default=[])
    verify = commands.add_parser("validate")
    verify.add_argument("--receipt", type=Path, required=True)
    verify.add_argument("--plan", type=Path, required=True)
    verify.add_argument("--root", type=Path, required=True)
    verify.add_argument("--lane", choices=LANES)
    verify.add_argument("--allow-compatible", action="store_true")
    verify.add_argument("--runner", action="append", default=[])
    verify.add_argument("--toolchain", action="append", default=[])
    verify.add_argument("--category", choices=INPUT_NAMES, action="append")
    combine = commands.add_parser("aggregate")
    combine.add_argument("--plan", type=Path, required=True)
    combine.add_argument("--receipts", type=Path, required=True)
    combine.add_argument("--output", type=Path, required=True)
    extract = commands.add_parser("extract")
    extract.add_argument("--archive", type=Path, required=True)
    extract.add_argument("--destination", type=Path, required=True)
    return result


def main() -> None:
    arguments = parser().parse_args()
    if arguments.command == "create":
        create_receipt(arguments)
    elif arguments.command == "validate":
        validate_receipt(
            arguments.receipt,
            arguments.plan,
            arguments.root,
            arguments.lane,
            arguments.allow_compatible,
            False,
            parse_mapping(arguments.runner) or None,
            parse_mapping(arguments.toolchain) or None,
            tuple(arguments.category or INPUT_NAMES),
        )
    elif arguments.command == "aggregate":
        aggregate(arguments)
    else:
        safe_extract(arguments.archive, arguments.destination)


if __name__ == "__main__":
    main()
