#!/usr/bin/env python3
"""Plan codex-agent CI work from Git-owned lane inputs."""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import subprocess
from functools import lru_cache
from pathlib import Path


LANES = (
    "contracts",
    "portable",
    "android",
    "node-js",
    "node-wasm",
    "desktop-macos-arm64",
    "desktop-macos-x64",
    "desktop-linux-arm64",
    "desktop-linux-x64",
    "desktop-windows-x64",
    "ios-native-tests",
    "ios-rust-device",
    "ios-rust-simulator",
    "ios-framework-device",
    "ios-framework-simulator",
    "ios-kotlin-tests",
    "ios-swift-build",
    "ios-swift-tests",
    "ios-package",
    "ios-privacy-metrics",
    "consumer-common",
    "consumer-android",
    "consumer-desktop",
    "consumer-ios-device",
    "consumer-ios-simulator",
    "consumer-node-js",
    "consumer-node-wasm",
)

CATEGORIES = ("production", "test", "metadata")

DEPENDENCIES = {
    "contracts": tuple(lane for lane in LANES if lane != "contracts"),
    "portable": (
        "android",
        "node-js",
        "node-wasm",
        "desktop-macos-arm64",
        "desktop-macos-x64",
        "desktop-linux-arm64",
        "desktop-linux-x64",
        "desktop-windows-x64",
    ),
    "android": ("consumer-android",),
    "node-js": (
        "desktop-macos-arm64", "desktop-macos-x64", "desktop-linux-arm64",
        "desktop-linux-x64", "desktop-windows-x64", "consumer-node-js",
    ),
    "node-wasm": (
        "desktop-macos-arm64", "desktop-macos-x64", "desktop-linux-arm64",
        "desktop-linux-x64", "desktop-windows-x64", "consumer-node-wasm",
    ),
    "desktop-macos-arm64": ("consumer-desktop",),
    "desktop-macos-x64": ("consumer-desktop",),
    "desktop-linux-arm64": ("consumer-desktop",),
    "desktop-linux-x64": ("consumer-desktop",),
    "desktop-windows-x64": ("consumer-desktop",),
    "ios-rust-device": ("ios-framework-device",),
    "ios-rust-simulator": ("ios-framework-simulator",),
    "ios-framework-device": ("ios-package", "consumer-ios-device"),
    "ios-framework-simulator": (
        "ios-kotlin-tests",
        "ios-swift-build",
        "ios-package",
        "consumer-ios-simulator",
    ),
    "ios-swift-build": ("ios-swift-tests",),
    "ios-swift-tests": ("ios-privacy-metrics",),
    "ios-package": ("ios-privacy-metrics",),
    "consumer-common": tuple(lane for lane in LANES if lane.startswith("consumer-") and lane != "consumer-common"),
}

SUPPORT_DEPENDENCIES = {
    **{
        lane: (("portable", "build"),)
        for lane in LANES
        if lane.startswith("desktop-")
    },
    "consumer-desktop": tuple(
        (lane, action)
        for lane in LANES if lane.startswith("desktop-")
        for action in ("build", "test")
    ),
    "consumer-android": (("android", "build"),),
    "consumer-ios-device": (("ios-rust-device", "build"), ("ios-rust-simulator", "build")),
    "consumer-ios-simulator": (("ios-rust-device", "build"), ("ios-rust-simulator", "build")),
    "ios-framework-device": (
        ("ios-rust-device", "build"),
    ),
    "ios-framework-simulator": (
        ("ios-rust-simulator", "build"),
    ),
    "ios-kotlin-tests": (("ios-rust-simulator", "build"),),
    "ios-swift-build": (("ios-framework-simulator", "build"),),
    "ios-swift-tests": (("ios-framework-simulator", "build"), ("ios-swift-build", "build")),
    "ios-package": (("ios-framework-device", "build"), ("ios-framework-simulator", "build")),
    "ios-privacy-metrics": (
        ("ios-kotlin-tests", "test"), ("ios-swift-tests", "test"),
        ("ios-package", "build"), ("ios-package", "metadata"),
    ),
}

SUPPORT_TRIGGER_ACTIONS = {
    **{lane: ("test",) for lane in LANES if lane.startswith("desktop-")},
    "ios-framework-device": ("build",),
    "ios-framework-simulator": ("build",),
    "ios-package": ("build", "test"),
}

HARMLESS_PATHS = ("README.md", "docs/**", ".gitignore", ".editorconfig")
FULL_VALIDATION_PATHS = {
    ".github/workflows/ci.yml",
    ".github/workflows/android-runtime-evidence.yml",
    ".github/workflows/apple-runtime-evidence.yml",
    ".github/workflows/desktop-runtime-evidence.yml",
    "ci/impact.py",
    "ci/receipt.py",
    "ci/reuse.py",
    "ci/validation_reuse.py",
}
FULL_VALIDATION_PREFIXES = (
    "ci/",
    ".github/actions/run-ci-lane/",
    ".github/actions/setup-kmp/",
    ".github/actions/setup-sccache/",
)

PRODUCT_MATRIX_LANES = ("contracts", "portable", "node-js", "node-wasm")
CONSUMER_MATRIX_LANES = (
    "consumer-android", "consumer-desktop", "consumer-node-js", "consumer-node-wasm",
)
DESKTOP_LANES = tuple(lane for lane in LANES if lane.startswith("desktop-")) + ("consumer-desktop",)
APPLE_LANES = tuple(lane for lane in LANES if lane.startswith("ios-")) + (
    "consumer-common", "consumer-ios-device", "consumer-ios-simulator",
)
DESKTOP_RUNNERS = {
    "desktop-macos-arm64": "macos-26",
    "desktop-macos-x64": "macos-26-intel",
    "desktop-linux-x64": "ubuntu-24.04",
    "desktop-windows-x64": "windows-2025",
}


def run_git(root: Path, *arguments: str, binary: bool = False) -> bytes | str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=root,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result.stdout if binary else result.stdout.decode("utf-8")


def read_pathspecs(root: Path, lane: str, category: str) -> tuple[str, ...]:
    path = root / "ci" / "lanes" / f"{lane}.{category}.pathspec"
    if not path.exists():
        return ()
    return tuple(
        line
        for raw in path.read_text(encoding="utf-8").splitlines()
        if (line := raw.strip()) and not line.startswith("#")
    )


def effective_pathspecs(root: Path, lane: str, category: str) -> tuple[str, ...]:
    specs = set(read_pathspecs(root, lane, category))
    if category == "production":
        pending = [upstream for upstream, downstreams in DEPENDENCIES.items() if lane in downstreams]
        seen: set[str] = set()
        while pending:
            upstream = pending.pop()
            if upstream in seen:
                continue
            seen.add(upstream)
            specs.update(read_pathspecs(root, upstream, category))
            pending.extend(
                candidate
                for candidate, downstreams in DEPENDENCIES.items()
                if upstream in downstreams
            )
    return tuple(sorted(specs))


@lru_cache(maxsize=8)
def tree_entries(root: Path, revision: str) -> tuple[tuple[str, str], ...]:
    raw = run_git(root, "ls-tree", "-r", "-z", revision, binary=True)
    entries: list[tuple[str, str]] = []
    for record in raw.split(b"\0"):
        if not record:
            continue
        metadata, path = record.split(b"\t", 1)
        mode, kind, object_id = metadata.decode("ascii").split(" ")
        decoded_path = path.decode("utf-8")
        entries.append((decoded_path, f"{mode}\t{kind}\t{object_id}\t{decoded_path}"))
    return tuple(entries)


def inventory(root: Path, revision: str, pathspecs: tuple[str, ...]) -> str:
    if not pathspecs:
        return ""
    entries = (
        record
        for path, record in tree_entries(root, revision)
        if any(fnmatch.fnmatchcase(path, pathspec) for pathspec in pathspecs)
    )
    return "".join(f"{entry}\n" for entry in sorted(entries))


def inventory_paths(contents: str) -> set[str]:
    return {line.split("\t", 3)[3] for line in contents.splitlines() if line}


def changed_paths(root: Path, base: str, target: str) -> set[str]:
    raw = run_git(root, "diff", "--name-status", "-z", base, target, binary=True)
    tokens = raw.split(b"\0")
    result: set[str] = set()
    index = 0
    while index < len(tokens) and tokens[index]:
        status = tokens[index].decode("ascii")
        index += 1
        count = 2 if status.startswith(("R", "C")) else 1
        for _ in range(count):
            if index >= len(tokens) or not tokens[index]:
                raise ValueError(f"Malformed git diff entry for {status}")
            result.add(tokens[index].decode("utf-8"))
            index += 1
    return result


def lane_action(root: Path, lane: str, category: str) -> bool:
    return bool(read_pathspecs(root, lane, category))


def require_production(root: Path, lanes: dict[str, dict[str, object]], lane: str, reason: str) -> bool:
    state = lanes[lane]
    changed = False
    for key, category in (("build", "production"), ("test", "test"), ("metadata", "metadata")):
        if lane_action(root, lane, category) and not state[key]:
            state[key] = True
            changed = True
    reasons = state["reasons"]
    if reason not in reasons:
        reasons.append(reason)
    return changed


def require_action(
    root: Path,
    lanes: dict[str, dict[str, object]],
    lane: str,
    action: str,
    reason: str,
) -> bool:
    category = {"build": "production", "test": "test", "metadata": "metadata"}[action]
    state = lanes[lane]
    changed = lane_action(root, lane, category) and not state[action]
    if changed:
        state[action] = True
    if reason not in state["reasons"]:
        state["reasons"].append(reason)
    return changed


def plan(
    root: Path,
    base: str,
    target: str,
    head: str,
    event: str,
    pull_request: int | None,
    merge_ready: bool,
    force_full: bool,
    repository: str,
    output: Path,
    require_android_evidence: bool = False,
) -> dict[str, object]:
    base_commit = str(run_git(root, "rev-parse", f"{base}^{{commit}}")).strip()
    target_commit = str(run_git(root, "rev-parse", f"{target}^{{commit}}")).strip()
    head_commit = str(run_git(root, "rev-parse", f"{head}^{{commit}}")).strip()
    validation_tree = str(run_git(root, "rev-parse", f"{target}^{{tree}}")).strip()
    changes = changed_paths(root, base_commit, target_commit)
    lanes: dict[str, dict[str, object]] = {}
    covered: set[str] = set()
    target_inventories: dict[tuple[str, str], str] = {}

    for lane in LANES:
        state = {
            "build": False,
            "test": False,
            "metadata": False,
            "reuseAllowed": True,
            "reasons": [],
        }
        lanes[lane] = state
        for category, action in (("production", "build"), ("test", "test"), ("metadata", "metadata")):
            specs = effective_pathspecs(root, lane, category)
            base_inventory = inventory(root, base_commit, specs)
            target_inventory = inventory(root, target_commit, specs)
            target_inventories[(lane, category)] = target_inventory
            covered.update(inventory_paths(base_inventory))
            covered.update(inventory_paths(target_inventory))
            if base_inventory != target_inventory:
                state[action] = True
                state["reasons"].append(f"{category}-input-changed")
        if state["build"]:
            if lane_action(root, lane, "test"):
                state["test"] = True
            if lane_action(root, lane, "metadata"):
                state["metadata"] = True

    harmless_base = inventory(root, base_commit, HARMLESS_PATHS)
    harmless_target = inventory(root, target_commit, HARMLESS_PATHS)
    covered.update(inventory_paths(harmless_base))
    covered.update(inventory_paths(harmless_target))
    unknown = sorted(changes - covered)
    core_change = any(
        path in FULL_VALIDATION_PATHS or path.startswith(FULL_VALIDATION_PREFIXES)
        for path in changes
    )
    full = force_full or bool(unknown) or core_change

    if full:
        reason = "ci-full-label" if force_full else "planner-core-changed" if core_change else "unknown-path"
        for lane in LANES:
            require_production(root, lanes, lane, reason)
            lanes[lane]["reuseAllowed"] = False
    else:
        propagated = True
        while propagated:
            propagated = False
            for upstream, downstreams in DEPENDENCIES.items():
                if lanes[upstream]["build"] and any(
                    not reason.startswith("required-by:")
                    for reason in lanes[upstream]["reasons"]
                ):
                    for downstream in downstreams:
                        propagated |= require_production(root, lanes, downstream, f"dependency:{upstream}")
            for downstream, upstreams in SUPPORT_DEPENDENCIES.items():
                if any(lanes[downstream][action] for action in SUPPORT_TRIGGER_ACTIONS.get(
                    downstream, ("build", "test", "metadata"),
                )):
                    for upstream, action in upstreams:
                        propagated |= require_action(
                            root, lanes, upstream, action, f"required-by:{downstream}",
                        )

    ready = merge_ready or event == "merge_group"
    if not ready:
        for state in lanes.values():
            state.update(build=False, test=False, metadata=False, reuseAllowed=False)
            state["reasons"] = ["merge-ready-required"]

    result: dict[str, object] = {
        "schemaVersion": 1,
        "event": event,
        "repository": repository,
        "pullRequest": pull_request,
        "baseCommit": base_commit,
        "headCommit": head_commit,
        "validationCommit": target_commit,
        "validationTree": validation_tree,
        "mergeReady": ready,
        "androidEvidenceRequired": require_android_evidence and any(
            lanes["android"][action] for action in ("build", "test", "metadata")
        ),
        "full": full,
        "unknownPaths": unknown,
        "changedPaths": sorted(changes),
        "lanes": lanes,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    inventory_root = output.parent / "inventories"
    for (lane, category), contents in target_inventories.items():
        path = inventory_root / lane / f"{category}-inputs.git-tree"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding="utf-8")
    return result


def write_github_outputs(path: Path, result: dict[str, object]) -> None:
    lines = [
        f"merge_ready={str(result['mergeReady']).lower()}",
        f"full={str(result['full']).lower()}",
        f"validation_tree={result['validationTree']}",
        f"validation_commit={result['validationCommit']}",
        f"android_evidence_required={str(result['androidEvidenceRequired']).lower()}",
    ]
    for lane, state in result["lanes"].items():
        run_lane = state["build"] or state["test"] or state["metadata"]
        output_lane = lane.replace("-", "_")
        lines.append(f"lane_{output_lane}={str(run_lane).lower()}")
        for action in ("build", "test", "metadata"):
            lines.append(f"lane_{output_lane}_{action}={str(state[action]).lower()}")
    for name, selected in (
        ("product_matrix", PRODUCT_MATRIX_LANES),
        ("consumer_matrix", CONSUMER_MATRIX_LANES),
    ):
        matrix = [
            {"lane": lane, **{action: bool(result["lanes"][lane][action]) for action in ("build", "test", "metadata")}}
            for lane in selected
            if any(result["lanes"][lane][action] for action in ("build", "test", "metadata"))
        ]
        lines.append(f"{name}={json.dumps(matrix, separators=(',', ':'))}")
    desktop_matrix = [
        {
            "lane": lane,
            "runner": runner,
            **{action: bool(result["lanes"][lane][action]) for action in ("build", "test", "metadata")},
        }
        for lane, runner in DESKTOP_RUNNERS.items()
        if any(result["lanes"][lane][action] for action in ("build", "test", "metadata"))
    ]
    lines.append(f"desktop_matrix={json.dumps(desktop_matrix, separators=(',', ':'))}")
    lines.append(f"any_desktop={str(any(any(result['lanes'][lane][action] for action in ('build', 'test', 'metadata')) for lane in DESKTOP_LANES)).lower()}")
    lines.append(f"any_apple={str(any(any(result['lanes'][lane][action] for action in ('build', 'test', 'metadata')) for lane in APPLE_LANES)).lower()}")
    with path.open("a", encoding="utf-8") as output:
        output.write("\n".join(lines) + "\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--base", required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--head")
    parser.add_argument("--event", choices=("pull_request", "merge_group"), required=True)
    parser.add_argument("--pull-request", type=int)
    parser.add_argument("--merge-ready", action="store_true")
    parser.add_argument("--full", action="store_true")
    parser.add_argument("--require-android-evidence", action="store_true")
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", "codex-agent-labs/codex-agent"))
    parser.add_argument("--output", type=Path, default=Path("build/ci/impact-plan.json"))
    parser.add_argument("--github-output", type=Path)
    return parser.parse_args()


def main() -> None:
    arguments = parse_args()
    root = arguments.repo.resolve()
    output = arguments.output if arguments.output.is_absolute() else root / arguments.output
    result = plan(
        root=root,
        base=arguments.base,
        target=arguments.target,
        head=arguments.head or arguments.target,
        event=arguments.event,
        pull_request=arguments.pull_request,
        merge_ready=arguments.merge_ready,
        force_full=arguments.full,
        require_android_evidence=arguments.require_android_evidence,
        repository=arguments.repository,
        output=output,
    )
    if arguments.github_output:
        write_github_outputs(arguments.github_output, result)
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
