#!/usr/bin/env python3
"""Capture and compare canonical CI producer runner/toolchain identities."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
from typing import Callable, Mapping


SCHEMA_VERSION = 1
ROLES = {
    "linux-arm64-supervisor": (
        "linux-arm64-supervisor.json",
        "producerLinuxArm64Supervisor",
        "arm_digest",
    ),
    "linux-x64-cross-builder": (
        "linux-x64-cross-builder.json",
        "producerLinuxX64CrossBuilder",
        "x64_digest",
    ),
}
ROLE_RUNNERS = {
    "linux-arm64-supervisor": ("Linux", "ARM64"),
    "linux-x64-cross-builder": ("Linux", "X64"),
}
RUNNER_KEYS = ("os", "arch", "image", "imageVersion")
TOOLCHAIN_KEYS = (
    "gradle", "kotlinPlugin", "javaRuntime", "javaVendor",
    "node", "rustc", "cargo", "xcode", "swift",
)
OUTPUT_NAMES = {
    "os": "runner-os",
    "arch": "runner-arch",
    "image": "runner-image",
    "imageVersion": "runner-image-version",
    "gradle": "gradle",
    "kotlinPlugin": "kotlin-plugin",
    "javaRuntime": "java-runtime",
    "javaVendor": "java-vendor",
    "node": "node",
    "rustc": "rustc",
    "cargo": "cargo",
    "xcode": "xcode",
    "swift": "swift",
}
ENVIRONMENT_NAMES = {
    "os": "CODEX_CI_RUNNER_OS",
    "arch": "CODEX_CI_RUNNER_ARCH",
    "image": "CODEX_CI_RUNNER_IMAGE",
    "imageVersion": "CODEX_CI_RUNNER_IMAGE_VERSION",
    "gradle": "CODEX_CI_GRADLE",
    "kotlinPlugin": "CODEX_CI_KOTLIN_PLUGIN",
    "javaRuntime": "CODEX_CI_JAVA_RUNTIME",
    "javaVendor": "CODEX_CI_JAVA_VENDOR",
    "node": "CODEX_CI_NODE",
    "rustc": "CODEX_CI_RUSTC",
    "cargo": "CODEX_CI_CARGO",
    "xcode": "CODEX_CI_XCODE",
    "swift": "CODEX_CI_SWIFT",
}


def run_command(command: tuple[str, ...], root: Path) -> str:
    result = subprocess.run(
        command,
        cwd=root,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    return result.stdout.replace("\r", "")


def one_match(pattern: str, value: str, name: str) -> str:
    match = re.search(pattern, value, re.MULTILINE)
    if match is None:
        raise ValueError(f"Could not observe {name}")
    return match.group(1)


def clean(value: str, name: str) -> str:
    result = value.strip()
    if not result or "\n" in result or "\r" in result:
        raise ValueError(f"Malformed runner identity field: {name}")
    return result


def observe_identity(
    root: Path,
    node_requested: str = "none",
    rust_requested: str = "none",
    xcode_requested: str = "none",
    environment: Mapping[str, str] = os.environ,
    execute: Callable[[tuple[str, ...], Path], str] = run_command,
) -> tuple[dict[str, str], dict[str, str]]:
    gradle_output = execute(("./gradlew", "--version", "--no-daemon"), root)
    java_output = execute(("java", "-XshowSettings:properties", "-version"), root)
    kotlin_text = (root / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    runner = {
        "os": environment.get("RUNNER_OS", ""),
        "arch": environment.get("RUNNER_ARCH", ""),
        "image": environment.get("ImageOS", "unavailable"),
        "imageVersion": environment.get("ImageVersion", "unavailable"),
    }
    toolchain = {
        "gradle": one_match(r"^Gradle ([^\s]+)$", gradle_output, "Gradle version"),
        "kotlinPlugin": one_match(r'^kotlin\s*=\s*"([^"]+)"$', kotlin_text, "Kotlin plugin version"),
        "javaRuntime": one_match(r"^\s*java\.runtime\.version = (.+)$", java_output, "Java runtime"),
        "javaVendor": one_match(r"^\s*java\.vendor = (.+)$", java_output, "Java vendor"),
        "node": "unavailable",
        "rustc": "unavailable",
        "cargo": "unavailable",
        "xcode": "unavailable",
        "swift": "unavailable",
    }
    if node_requested != "none":
        toolchain["node"] = execute(("node", "--version"), root).strip()
    if rust_requested != "none":
        rust_output = execute(("rustc", "-vV"), root)
        toolchain["rustc"] = ";".join(
            f"{output_name}={one_match(rf'^{source_name}: (.+)$', rust_output, f'rustc {source_name}')}"
            for source_name, output_name in (("release", "release"), ("commit-hash", "commit"), ("host", "host"))
        )
        toolchain["cargo"] = execute(("cargo", "--version"), root).strip()
    if xcode_requested != "none":
        toolchain["xcode"] = ";".join(execute(("xcodebuild", "-version"), root).splitlines())
        toolchain["swift"] = " ".join(execute(("xcrun", "swift", "--version"), root).split())
    return (
        {key: clean(value, f"runner.{key}") for key, value in runner.items()},
        {key: clean(value, f"toolchain.{key}") for key, value in toolchain.items()},
    )


def manifest_value(role: str, runner: Mapping[str, str], toolchain: Mapping[str, str]) -> dict[str, object]:
    if role not in ROLES or tuple(runner) != RUNNER_KEYS or tuple(toolchain) != TOOLCHAIN_KEYS:
        raise ValueError("Producer identity shape is invalid")
    if (
        (runner["os"], runner["arch"]) != ROLE_RUNNERS[role]
        or runner["image"] == "unavailable"
        or runner["imageVersion"] == "unavailable"
    ):
        raise ValueError(f"Producer role is on the wrong or unidentified runner: {role}")
    return {
        "schemaVersion": SCHEMA_VERSION,
        "role": role,
        "runner": dict(runner),
        "toolchain": dict(toolchain),
    }


def canonical_bytes(value: dict[str, object]) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def write_manifest(path: Path, role: str, runner: Mapping[str, str], toolchain: Mapping[str, str]) -> None:
    if path.is_symlink():
        raise ValueError(f"Producer identity output may not be a symlink: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical_bytes(manifest_value(role, runner, toolchain)))


def environment_identity(environment: Mapping[str, str] = os.environ) -> tuple[dict[str, str], dict[str, str]]:
    values = {
        key: clean(environment.get(environment_name, ""), environment_name)
        for key, environment_name in ENVIRONMENT_NAMES.items()
    }
    return (
        {key: values[key] for key in RUNNER_KEYS},
        {key: values[key] for key in TOOLCHAIN_KEYS},
    )


def read_manifest(path: Path, role: str) -> dict[str, object]:
    if not path.is_file() or path.is_symlink() or path.stat().st_size not in range(1, 65_537):
        raise ValueError(f"Producer identity manifest is missing or unsafe: {path}")
    raw = path.read_bytes()
    value = json.loads(raw)
    if not isinstance(value, dict) or set(value) != {"schemaVersion", "role", "runner", "toolchain"}:
        raise ValueError(f"Producer identity manifest shape is invalid: {path}")
    runner = value.get("runner")
    toolchain = value.get("toolchain")
    if (
        value.get("schemaVersion") != SCHEMA_VERSION
        or value.get("role") != role
        or not isinstance(runner, dict)
        or tuple(runner) != tuple(sorted(RUNNER_KEYS))
        or not isinstance(toolchain, dict)
        or tuple(toolchain) != tuple(sorted(TOOLCHAIN_KEYS))
        or any(not isinstance(item, str) or not item or "\n" in item or "\r" in item for item in runner.values())
        or any(not isinstance(item, str) or not item or "\n" in item or "\r" in item for item in toolchain.values())
        or (runner.get("os"), runner.get("arch")) != ROLE_RUNNERS[role]
        or runner.get("image") == "unavailable"
        or runner.get("imageVersion") == "unavailable"
        or raw != canonical_bytes(value)
    ):
        raise ValueError(f"Producer identity manifest is not canonical: {path}")
    return value


def identity_files(directory: Path) -> dict[str, Path]:
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError(f"Producer identity directory is missing or unsafe: {directory}")
    expected = {details[0] for details in ROLES.values()}
    actual = {path.name for path in directory.iterdir()}
    if actual != expected:
        raise ValueError(f"Producer identity file set mismatch: expected={sorted(expected)} actual={sorted(actual)}")
    result: dict[str, Path] = {}
    for role, (filename, _, _) in ROLES.items():
        path = directory / filename
        read_manifest(path, role)
        result[role] = path
    return result


def bound_toolchain(directory: Path) -> list[str]:
    files = identity_files(directory)
    return [
        f"{ROLES[role][1]}={hashlib.sha256(files[role].read_bytes()).hexdigest()}"
        for role in ROLES
    ]


def verify_directories(expected: Path, actual: Path) -> None:
    expected_files = identity_files(expected)
    actual_files = identity_files(actual)
    for role in ROLES:
        if expected_files[role].read_bytes() != actual_files[role].read_bytes():
            raise ValueError(f"Transported producer identity mismatch: {role}")


def verify_manifest(expected: Path, actual: Path, role: str) -> None:
    read_manifest(expected, role)
    read_manifest(actual, role)
    if expected.read_bytes() != actual.read_bytes():
        raise ValueError(f"Current producer identity mismatch: {role}")


def append_lines(path: Path | None, values: Mapping[str, str]) -> None:
    if path is None:
        return
    with path.open("a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)
    capture = commands.add_parser("capture")
    capture.add_argument("--root", type=Path, default=Path.cwd())
    capture.add_argument("--node-requested", default="none")
    capture.add_argument("--rust-requested", default="none")
    capture.add_argument("--xcode-requested", default="none")
    capture.add_argument("--role", choices=ROLES)
    capture.add_argument("--output", type=Path)
    capture.add_argument("--github-output", type=Path)
    capture.add_argument("--github-env", type=Path)
    record = commands.add_parser("record")
    record.add_argument("--role", choices=ROLES, required=True)
    record.add_argument("--output", type=Path, required=True)
    bind = commands.add_parser("bind")
    bind.add_argument("--directory", type=Path, required=True)
    bind.add_argument("--github-output", type=Path)
    verify = commands.add_parser("verify")
    verify.add_argument("--expected", type=Path, required=True)
    verify.add_argument("--actual", type=Path, required=True)
    verify_one = commands.add_parser("verify-one")
    verify_one.add_argument("--role", choices=ROLES, required=True)
    verify_one.add_argument("--expected", type=Path, required=True)
    verify_one.add_argument("--actual", type=Path, required=True)
    return result


def main() -> None:
    arguments = parser().parse_args()
    if arguments.command == "capture":
        if (arguments.role is None) != (arguments.output is None):
            raise ValueError("Producer identity role and output must be supplied together")
        runner, toolchain = observe_identity(
            arguments.root.resolve(),
            arguments.node_requested,
            arguments.rust_requested,
            arguments.xcode_requested,
        )
        values = {**runner, **toolchain}
        append_lines(arguments.github_output, {
            OUTPUT_NAMES[key]: value for key, value in values.items()
        })
        append_lines(arguments.github_env, {
            ENVIRONMENT_NAMES[key]: value for key, value in values.items()
        })
        if arguments.output is not None:
            write_manifest(arguments.output, arguments.role, runner, toolchain)
    elif arguments.command == "record":
        runner, toolchain = environment_identity()
        write_manifest(arguments.output, arguments.role, runner, toolchain)
    elif arguments.command == "bind":
        bindings = bound_toolchain(arguments.directory.resolve())
        by_key = dict(value.split("=", 1) for value in bindings)
        append_lines(arguments.github_output, {
            ROLES[role][2]: by_key[ROLES[role][1]] for role in ROLES
        })
        print(json.dumps(by_key, indent=2, sort_keys=True))
    elif arguments.command == "verify":
        verify_directories(arguments.expected.resolve(), arguments.actual.resolve())
    else:
        verify_manifest(arguments.expected.resolve(), arguments.actual.resolve(), arguments.role)


if __name__ == "__main__":
    main()
