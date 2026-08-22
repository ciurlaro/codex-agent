#!/usr/bin/env python3
"""Create and install narrow dependency-cache seed artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
from pathlib import Path, PurePosixPath


OID = re.compile(r"[0-9a-f]{40}")
KEY = re.compile(r"[A-Za-z0-9_.-]+")
RUST_LANES = {
    "ios-native-tests", "ios-rust-device", "ios-rust-simulator", "ios-framework-device",
    "ios-framework-simulator", "ios-kotlin-tests", "ios-package", "ios-privacy-metrics",
    "consumer-common", "consumer-ios-device", "consumer-ios-simulator",
}
CACHE_PATHS = {
    "kmp": {
        "gradle": (".gradle/caches/modules-2",),
        "konan": (".konan/dependencies",),
    },
    "cargo": {
        "cargo": (
            ".cargo/registry/index",
            ".cargo/registry/cache",
            ".cargo/git/db",
        ),
    },
}
MANIFEST_KEYS = {
    "schemaVersion", "artifactName", "repository", "event", "validationCommit",
    "validationTree", "runId", "runAttempt", "lane", "runner", "kind", "caches",
}
PROMOTION_PLAN_KEYS = {
    "schemaVersion", "repository", "finalCommit", "finalTree", "validatedCommit",
    "validatedTree", "validationRunId", "validationRunAttempt", "sourcePlanArtifactName",
    "sourceAggregateArtifactName", "promotedAggregateArtifactName",
    "promotedInventoryArtifactName", "lanes",
}
PROMOTION_LANE_KEYS = {
    "sourceKind", "sourceRunId", "sourceRunAttempt", "sourceArtifactName",
    "sourcePromotionRunId", "sourcePromotionCommit", "promotedArtifactName",
}
AGGREGATE_KEYS = {
    "schemaVersion", "repository", "event", "validationCommit", "validationTree",
    "impactPlan", "lanes", "result",
}
AGGREGATE_LANE_KEYS = {
    "runId", "runAttempt", "artifactName", "validationCommit", "validationTree", "result",
}


def platform(lane: str) -> str:
    if lane.startswith(("ios-", "desktop-macos-")) or lane in {
        "consumer-common", "consumer-ios-device", "consumer-ios-simulator",
    }:
        return "macOS"
    if lane == "desktop-windows-x64":
        return "Windows"
    return "Linux"


def runner_arch(lane: str) -> str:
    return "X64" if lane == "desktop-macos-x64" or platform(lane) != "macOS" else "ARM64"


def active_lanes(
    plan: dict[str, object], runner_os: str, expected_arch: str, kind: str
) -> list[str]:
    lanes = plan.get("lanes")
    if not isinstance(lanes, dict):
        raise ValueError("Impact plan has no lane map")
    active = [
        lane for lane, state in lanes.items()
        if isinstance(state, dict)
        and platform(lane) == runner_os
        and runner_arch(lane) == expected_arch
        and any(state.get(action) is True for action in ("build", "test", "metadata"))
        and (kind != "cargo" or lane in RUST_LANES)
    ]
    return sorted(active)


def require_oid(value: object, label: str) -> str:
    if not isinstance(value, str) or not OID.fullmatch(value):
        raise ValueError(f"Invalid {label}")
    return value


def positive_int(value: object, label: str) -> int:
    if isinstance(value, bool):
        raise ValueError(f"Invalid {label}")
    try:
        result = int(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"Invalid {label}") from error
    if result < 1:
        raise ValueError(f"Invalid {label}")
    return result


def slug(value: str) -> str:
    result = value.lower().replace("_", "-")
    if not re.fullmatch(r"[a-z0-9-]+", result):
        raise ValueError(f"Invalid artifact identity component: {value!r}")
    return result


def artifact_name(kind: str, runner_os: str, runner_arch: str, tree: str) -> str:
    return f"codex-agent-ci-cache-seed-{kind}-{slug(runner_os)}-{slug(runner_arch)}-{tree}"


def read_plan(path: Path, merge_group_only: bool = False) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if (
        not isinstance(value, dict)
        or value.get("schemaVersion") != 1
        or value.get("mergeReady") is not True
        or value.get("event") not in {"pull_request", "merge_group"}
        or merge_group_only and value.get("event") != "merge_group"
    ):
        raise ValueError("Cache seeds require an authoritative merge-group impact plan")
    require_oid(value.get("validationCommit"), "plan validation commit")
    require_oid(value.get("validationTree"), "plan validation tree")
    if not isinstance(value.get("repository"), str) or "/" not in value["repository"]:
        raise ValueError("Invalid plan repository")
    return value


def write_outputs(path: Path | None, values: dict[str, object]) -> None:
    lines = "".join(f"{name}={str(value).lower() if isinstance(value, bool) else value}\n" for name, value in values.items())
    if path is not None:
        with path.open("a", encoding="utf-8") as output:
            output.write(lines)


def policy(arguments: argparse.Namespace) -> dict[str, object]:
    plan = read_plan(arguments.plan)
    if arguments.lane not in plan["lanes"]:
        raise ValueError(f"Unknown lane: {arguments.lane}")
    if plan["validationCommit"] != arguments.validation_commit:
        raise ValueError("Validation commit does not match the impact plan")
    writers = active_lanes(plan, arguments.runner_os, arguments.runner_arch, "kmp")
    rust_writers = active_lanes(plan, arguments.runner_os, arguments.runner_arch, "cargo")
    event = os.environ.get("GITHUB_EVENT_NAME", "")
    pull = os.environ.get("PR_NUMBER", "")
    sha_matches = os.environ.get("GITHUB_SHA") == arguments.validation_commit
    identity_matches = (
        plan.get("event") == event
        and os.environ.get("GITHUB_REPOSITORY") == plan.get("repository")
        and sha_matches
    )
    authoritative_pr = (
        event == "pull_request"
        and bool(pull)
        and os.environ.get("GITHUB_REF") == f"refs/pull/{pull}/merge"
        and identity_matches
    )
    authoritative_merge_group = event == "merge_group" and identity_matches
    tree = str(plan["validationTree"])
    result: dict[str, object] = {
        "write": bool(authoritative_pr and writers and arguments.lane == writers[0]),
        "rust-write": bool(authoritative_pr and rust_writers and arguments.lane == rust_writers[0]),
        "seed": bool(
            (authoritative_pr or authoritative_merge_group)
            and writers
            and arguments.lane == writers[0]
        ),
        "rust-seed": bool(
            (authoritative_pr or authoritative_merge_group)
            and rust_writers
            and arguments.lane == rust_writers[0]
        ),
        "seed-artifact": artifact_name("kmp", arguments.runner_os, arguments.runner_arch, tree),
        "rust-seed-artifact": artifact_name(
            "cargo", arguments.runner_os, arguments.runner_arch, tree
        ),
    }
    write_outputs(arguments.github_output, result)
    return result


def copy_regular_tree(source: Path, destination: Path) -> bool:
    if source.is_symlink():
        raise ValueError(f"Cache source is not a regular directory: {source}")
    if not source.exists():
        return False
    if not source.is_dir():
        raise ValueError(f"Cache source is not a regular directory: {source}")
    root = source.resolve(strict=True)
    for directory, directories, files in os.walk(root, followlinks=False):
        for name in sorted(directories + files):
            path = Path(directory, name)
            if not path.is_symlink():
                continue
            link = os.readlink(path)
            if os.path.isabs(link):
                raise ValueError(f"Cache source contains an absolute symlink: {path}")
            lexical_target = Path(os.path.abspath(path.parent / link))
            if not lexical_target.is_relative_to(root):
                raise ValueError(f"Cache source symlink escapes its root: {path}")
            try:
                resolved = path.resolve(strict=True)
            except (OSError, RuntimeError) as error:
                raise ValueError(f"Cache source contains a dangling or cyclic symlink: {path}") from error
            if not resolved.is_relative_to(root):
                raise ValueError(f"Cache source symlink escapes its root: {path}")
            if not resolved.is_file() and not resolved.is_dir():
                raise ValueError(f"Cache source symlink targets a special file: {path}")

    def copy(path: Path, target: Path, ancestors: frozenset[Path]) -> bool:
        resolved = path.resolve(strict=True) if path.is_symlink() else path
        if resolved.is_dir():
            if resolved in ancestors:
                raise ValueError(f"Cache source contains a cyclic symlink: {path}")
            target.mkdir(parents=True, exist_ok=True)
            copied = False
            for child in sorted(resolved.iterdir()):
                copied = copy(child, target / child.name, ancestors | {resolved}) or copied
            return copied
        if resolved.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(resolved, target)
            return True
        raise ValueError(f"Cache source contains a special file: {path}")

    return copy(root, destination, frozenset())


def tree_digest(root: Path) -> str:
    if root.is_symlink() or not root.is_dir():
        raise ValueError(f"Cache payload root is not a regular directory: {root}")
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"Cache payload contains a symlink: {path}")
        if path.is_dir():
            continue
        if not path.is_file():
            raise ValueError(f"Cache payload contains a special file: {path}")
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        size = path.stat().st_size
        digest.update(size.to_bytes(8, "big"))
        with path.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
    return digest.hexdigest()


def executable_modes(root: Path) -> dict[str, int]:
    return {
        path.relative_to(root).as_posix(): path.stat().st_mode & 0o111
        for path in sorted(root.rglob("*"))
        if path.is_file() and not path.is_symlink() and path.stat().st_mode & 0o111
    }


def parse_keys(values: list[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for value in values:
        name, separator, key = value.partition("=")
        if not separator or name in result or not KEY.fullmatch(key):
            raise ValueError(f"Invalid cache key: {value!r}")
        result[name] = key
    return result


def create(arguments: argparse.Namespace) -> dict[str, object]:
    plan = read_plan(arguments.plan)
    commit = require_oid(arguments.validation_commit, "validation commit")
    tree = require_oid(arguments.validation_tree, "validation tree")
    if (
        plan["repository"] != arguments.repository
        or plan["validationCommit"] != commit
        or plan["validationTree"] != tree
        or arguments.event != plan.get("event")
        or arguments.event not in {"pull_request", "merge_group"}
    ):
        raise ValueError("Cache seed identity does not match the impact plan")
    elected = active_lanes(plan, arguments.runner_os, arguments.runner_arch, arguments.kind)
    if not elected or elected[0] != arguments.lane:
        raise ValueError("Cache seed was not produced by the elected lane")
    expected_name = artifact_name(arguments.kind, arguments.runner_os, arguments.runner_arch, tree)
    if arguments.artifact_name != expected_name:
        raise ValueError("Cache seed artifact name does not match its identity")
    keys = parse_keys(arguments.cache_key)
    allowed = CACHE_PATHS[arguments.kind]
    if set(keys) != set(allowed):
        raise ValueError("Cache seed keys do not match the requested seed kind")

    root = arguments.root.resolve()
    if root.exists():
        shutil.rmtree(root)
    payload = root / "payload"
    caches: dict[str, dict[str, object]] = {}
    for name, paths in allowed.items():
        cache_root = payload / name
        copied_paths: list[str] = []
        for relative in paths:
            if copy_regular_tree(arguments.home / relative, cache_root / relative):
                copied_paths.append(relative)
        if copied_paths:
            caches[name] = {
                "key": keys[name],
                "paths": copied_paths,
                "sha256": tree_digest(cache_root),
                "executableModes": executable_modes(cache_root),
            }
        elif cache_root.exists():
            shutil.rmtree(cache_root)
    if not caches:
        raise ValueError("No dependency cache files exist for the elected seed")

    manifest: dict[str, object] = {
        "schemaVersion": 2,
        "artifactName": expected_name,
        "repository": arguments.repository,
        "event": arguments.event,
        "validationCommit": commit,
        "validationTree": tree,
        "runId": positive_int(arguments.run_id, "run ID"),
        "runAttempt": positive_int(arguments.run_attempt, "run attempt"),
        "lane": arguments.lane,
        "runner": {"os": arguments.runner_os, "arch": arguments.runner_arch},
        "kind": arguments.kind,
        "caches": caches,
    }
    root.mkdir(parents=True, exist_ok=True)
    (root / "cache-seed.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest


def seed_source(
    plan: dict[str, object],
    promotion: dict[str, object],
    aggregate: dict[str, object],
    kind: str,
    runner_os: str,
    runner_arch: str,
) -> dict[str, object]:
    if set(promotion) != PROMOTION_PLAN_KEYS or promotion.get("schemaVersion") != 1:
        raise ValueError("Unsupported or non-exact promotion plan")
    if set(aggregate) != AGGREGATE_KEYS or aggregate.get("schemaVersion") != 1:
        raise ValueError("Unsupported or non-exact validation aggregate")
    if (
        promotion.get("repository") != plan.get("repository")
        or promotion.get("validatedCommit") != plan.get("validationCommit")
        or promotion.get("validatedTree") != plan.get("validationTree")
        or promotion.get("finalTree") != plan.get("validationTree")
        or aggregate.get("repository") != plan.get("repository")
        or aggregate.get("event") != "merge_group"
        or aggregate.get("validationCommit") != plan.get("validationCommit")
        or aggregate.get("validationTree") != plan.get("validationTree")
        or aggregate.get("result") != "passed"
    ):
        raise ValueError("Cache seed source metadata does not match the validated tree")
    lanes = active_lanes(plan, runner_os, runner_arch, kind)
    if not lanes:
        return {"available": False}
    lane = lanes[0]
    promotion_lanes = promotion.get("lanes")
    aggregate_lanes = aggregate.get("lanes")
    if not isinstance(promotion_lanes, dict) or not isinstance(aggregate_lanes, dict):
        raise ValueError("Cache seed source has no lane maps")
    promoted = promotion_lanes.get(lane)
    summary = aggregate_lanes.get(lane)
    if (
        not isinstance(promoted, dict)
        or set(promoted) != PROMOTION_LANE_KEYS
        or promoted.get("sourceKind") != "validation"
    ):
        return {"available": False}
    if not isinstance(summary, dict) or set(summary) != AGGREGATE_LANE_KEYS:
        raise ValueError(f"Cache seed source lane {lane} is absent from the aggregate")
    run_id = positive_int(summary.get("runId"), "seed source run ID")
    run_attempt = positive_int(summary.get("runAttempt"), "seed source run attempt")
    commit = require_oid(summary.get("validationCommit"), "seed source commit")
    tree = require_oid(summary.get("validationTree"), "seed source tree")
    if (
        promoted.get("sourceRunId") != run_id
        or promoted.get("sourceRunAttempt") != run_attempt
        or promoted.get("sourceArtifactName") != summary.get("artifactName")
        or summary.get("result") != "passed"
    ):
        raise ValueError("Cache seed source lane does not match the promotion and aggregate")
    return {
        "available": True,
        "lane": lane,
        "run-id": run_id,
        "run-attempt": run_attempt,
        "commit": commit,
        "tree": tree,
        "artifact-name": artifact_name(kind, runner_os, runner_arch, tree),
    }


def source(arguments: argparse.Namespace) -> dict[str, object]:
    plan = read_plan(arguments.plan, merge_group_only=True)
    promotion = json.loads(arguments.promotion_plan.read_text(encoding="utf-8"))
    aggregate = json.loads(arguments.aggregate.read_text(encoding="utf-8"))
    result = seed_source(
        plan, promotion, aggregate, arguments.kind, arguments.runner_os, arguments.runner_arch
    )
    write_outputs(arguments.github_output, result)
    return result


def install(arguments: argparse.Namespace) -> dict[str, object]:
    plan = read_plan(arguments.plan, merge_group_only=True)
    promotion = json.loads(arguments.promotion_plan.read_text(encoding="utf-8"))
    aggregate = json.loads(arguments.aggregate.read_text(encoding="utf-8"))
    expected = seed_source(
        plan, promotion, aggregate, arguments.kind, arguments.runner_os, arguments.runner_arch
    )
    if expected.get("available") is not True:
        raise ValueError("No validated cache seed source is available")
    manifest_path = arguments.root / "cache-seed.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or set(manifest) != MANIFEST_KEYS:
        raise ValueError("Unsupported or non-exact cache seed manifest")
    kind = manifest.get("kind")
    runner = manifest.get("runner")
    if (
        kind != arguments.kind
        or kind not in CACHE_PATHS
        or not isinstance(runner, dict)
        or set(runner) != {"os", "arch"}
    ):
        raise ValueError("Invalid cache seed kind or runner identity")
    tree = require_oid(expected.get("tree"), "seed source tree")
    elected = active_lanes(plan, arguments.runner_os, arguments.runner_arch, str(kind))
    if (
        manifest.get("schemaVersion") != 2
        or manifest.get("artifactName") != expected.get("artifact-name")
        or manifest.get("repository") != plan.get("repository")
        or manifest.get("event") not in {"pull_request", "merge_group"}
        or manifest.get("validationCommit") != expected.get("commit")
        or manifest.get("validationTree") != tree
        or manifest.get("runId") != expected.get("run-id")
        or manifest.get("runAttempt") != expected.get("run-attempt")
        or not elected
        or manifest.get("lane") != expected.get("lane")
        or runner != {"os": arguments.runner_os, "arch": arguments.runner_arch}
    ):
        raise ValueError("Cache seed does not match the validated merge-group identity")
    positive_int(manifest.get("runAttempt"), "seed run attempt")
    caches = manifest.get("caches")
    if not isinstance(caches, dict) or not caches or not set(caches) <= set(CACHE_PATHS[str(kind)]):
        raise ValueError("Cache seed has an invalid cache set")

    output_names = {"gradle": "gradle", "konan": "konan", "cargo": "rust-dependencies"}
    outputs: dict[str, object] = {name: False for name in output_names.values()}
    validated: list[tuple[str, dict[str, object], Path, dict[str, int]]] = []
    for name, value in caches.items():
        allowed_paths = CACHE_PATHS[str(kind)][name]
        if (
            not isinstance(value, dict)
            or set(value) != {"key", "paths", "sha256", "executableModes"}
            or not isinstance(value["paths"], list)
            or not value["paths"]
            or not set(value["paths"]) <= set(allowed_paths)
            or not isinstance(value["key"], str)
            or not KEY.fullmatch(value["key"])
            or not value["key"].startswith(f"{name}-main-")
            or f"-{arguments.runner_os}-{arguments.runner_arch}-" not in value["key"]
            or not isinstance(value["sha256"], str)
            or not re.fullmatch(r"[0-9a-f]{64}", value["sha256"])
        ):
            raise ValueError(f"Invalid {name} cache seed record")
        source_root = arguments.root / "payload" / name
        if tree_digest(source_root) != value["sha256"]:
            raise ValueError(f"{name} cache seed payload digest mismatch")
        for relative in value["paths"]:
            source = source_root / relative
            if not source.is_dir() or source.is_symlink():
                raise ValueError(f"Missing {name} cache seed path: {relative}")
        modes = value["executableModes"]
        if not isinstance(modes, dict):
            raise ValueError(f"Invalid {name} cache seed executable modes")
        roots = [PurePosixPath(relative) for relative in value["paths"]]
        for relative, mode in modes.items():
            path = PurePosixPath(relative) if isinstance(relative, str) else PurePosixPath()
            source = source_root.joinpath(*path.parts)
            if (
                not isinstance(relative, str)
                or path.is_absolute()
                or path.as_posix() != relative
                or ".." in path.parts
                or not any(path == root or root in path.parents for root in roots)
                or not isinstance(mode, int)
                or isinstance(mode, bool)
                or mode == 0
                or mode & ~0o111
                or source.is_symlink()
                or not source.is_file()
            ):
                raise ValueError(f"Invalid {name} cache seed executable mode: {relative!r}")
        validated.append((name, value, source_root, modes))

    for name, value, source_root, modes in validated:
        for relative in value["paths"]:
            source = source_root / relative
            destination = arguments.home / relative
            if destination.exists():
                shutil.rmtree(destination)
            copy_regular_tree(source, destination)
        for relative, mode in modes.items():
            destination = arguments.home.joinpath(*PurePosixPath(relative).parts)
            destination.chmod((destination.stat().st_mode & ~0o111) | mode)
        outputs[output_names[name]] = True
        outputs[f"{name}-key"] = value["key"]
    write_outputs(arguments.github_output, outputs)
    return outputs


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)

    policy_parser = commands.add_parser("policy")
    policy_parser.add_argument("--plan", type=Path, required=True)
    policy_parser.add_argument("--lane", required=True)
    policy_parser.add_argument("--validation-commit", required=True)
    policy_parser.add_argument("--runner-os", required=True)
    policy_parser.add_argument("--runner-arch", required=True)
    policy_parser.add_argument("--github-output", type=Path)
    policy_parser.set_defaults(handler=policy)

    create_parser = commands.add_parser("create")
    create_parser.add_argument("--plan", type=Path, required=True)
    create_parser.add_argument("--root", type=Path, required=True)
    create_parser.add_argument("--home", type=Path, default=Path.home())
    create_parser.add_argument("--kind", choices=tuple(CACHE_PATHS), required=True)
    create_parser.add_argument("--artifact-name", required=True)
    create_parser.add_argument("--repository", required=True)
    create_parser.add_argument("--event", required=True)
    create_parser.add_argument("--validation-commit", required=True)
    create_parser.add_argument("--validation-tree", required=True)
    create_parser.add_argument("--run-id", required=True)
    create_parser.add_argument("--run-attempt", required=True)
    create_parser.add_argument("--lane", required=True)
    create_parser.add_argument("--runner-os", required=True)
    create_parser.add_argument("--runner-arch", required=True)
    create_parser.add_argument("--cache-key", action="append", default=[])
    create_parser.set_defaults(handler=create)

    source_parser = commands.add_parser("source")
    source_parser.add_argument("--plan", type=Path, required=True)
    source_parser.add_argument("--promotion-plan", type=Path, required=True)
    source_parser.add_argument("--aggregate", type=Path, required=True)
    source_parser.add_argument("--kind", choices=tuple(CACHE_PATHS), required=True)
    source_parser.add_argument("--runner-os", required=True)
    source_parser.add_argument("--runner-arch", required=True)
    source_parser.add_argument("--github-output", type=Path)
    source_parser.set_defaults(handler=source)

    install_parser = commands.add_parser("install")
    install_parser.add_argument("--plan", type=Path, required=True)
    install_parser.add_argument("--promotion-plan", type=Path, required=True)
    install_parser.add_argument("--aggregate", type=Path, required=True)
    install_parser.add_argument("--root", type=Path, required=True)
    install_parser.add_argument("--home", type=Path, default=Path.home())
    install_parser.add_argument("--kind", choices=tuple(CACHE_PATHS), required=True)
    install_parser.add_argument("--runner-os", required=True)
    install_parser.add_argument("--runner-arch", required=True)
    install_parser.add_argument("--github-output", type=Path)
    install_parser.set_defaults(handler=install)
    return result


def main() -> None:
    arguments = parser().parse_args()
    arguments.handler(arguments)


if __name__ == "__main__":
    main()
