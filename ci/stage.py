#!/usr/bin/env python3
"""Stage one lane's declared files and create its strict receipt."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import tarfile
from pathlib import Path
from pathlib import PurePosixPath

from receipt import VALIDATION_ACTIONS_KEY, create_receipt
from runner_identity import bound_toolchain


OUTPUTS: dict[str, tuple[tuple[str, str, str], ...]] = {
    "contracts": (
        ("build", "gradle/build-logic/build/libs/codex-agent-release-tooling.jar", "release-tooling"),
    ),
    "portable": (
        ("build", "codex-agent-runtime-desktop/build/distributions/codex-agent-jvm-runtime-evidence-runner.zip", "jvm-runner"),
        ("build", "codex-agent-runtime-node/build/distributions/codex-agent-node-runtime-evidence-runner.zip", "node-js-runner"),
        ("build", "codex-agent-runtime-node/build/distributions/codex-agent-node-wasm-runtime-evidence-runner.zip", "node-wasm-runner"),
    ),
    "android": (
        ("build", "codex-agent-runtime-android/build/outputs/aar/codex-agent-runtime-android-release.aar", "aar"),
        ("build", "tooling/android-runtime-evidence/build/outputs/apk/debug/android-runtime-evidence-debug.apk", "application-apk"),
        ("build", "tooling/android-runtime-evidence/build/outputs/apk/androidTest/debug/android-runtime-evidence-debug-androidTest.apk", "test-apk"),
        ("test", "codex-agent-runtime-android/build/test-results/**/*.xml", "test-report"),
        ("metadata", "codex-agent-runtime-android/build/reports/lint-results-release.xml", "lint-report"),
    ),
    "desktop-macos-arm64": (
        ("build", "codex-agent-runtime-desktop/build/distributions/*-app-server-macos-arm64.zip", "classifier"),
        ("test", "codex-agent-runtime-desktop/build/reports/desktop-runtime-evidence/desktop-runtime-macosArm64.json", "runtime-evidence"),
        ("test", "codex-agent-runtime-desktop/build/reports/jvm-runtime-evidence/jvm-runtime-macosArm64.json", "jvm-runtime-evidence"),
        ("test", "codex-agent-runtime-node/build/reports/node-runtime-evidence/node-runtime-macosArm64.json", "node-runtime-evidence"),
        ("test", "codex-agent-runtime-node/build/reports/node-runtime-evidence/node-wasm-runtime-macosArm64.json", "node-wasm-runtime-evidence"),
    ),
    "desktop-macos-x64": (
        ("build", "codex-agent-runtime-desktop/build/distributions/*-app-server-macos-x64.zip", "classifier"),
        ("test", "codex-agent-runtime-desktop/build/reports/desktop-runtime-evidence/desktop-runtime-macosX64.json", "runtime-evidence"),
        ("test", "codex-agent-runtime-desktop/build/reports/jvm-runtime-evidence/jvm-runtime-macosX64.json", "jvm-runtime-evidence"),
        ("test", "codex-agent-runtime-node/build/reports/node-runtime-evidence/node-runtime-macosX64.json", "node-runtime-evidence"),
        ("test", "codex-agent-runtime-node/build/reports/node-runtime-evidence/node-wasm-runtime-macosX64.json", "node-wasm-runtime-evidence"),
    ),
    "desktop-linux-arm64": (
        ("build", "codex-agent-runtime-desktop/build/distributions/*-app-server-linux-arm64.zip", "classifier"),
        ("build", "build/ci/linux-arm64-producer-identities/linux-arm64-supervisor.json", "arm-supervisor-identity"),
        ("build", "build/ci/linux-arm64-producer-identities/linux-x64-cross-builder.json", "x64-cross-builder-identity"),
        ("test", "codex-agent-runtime-desktop/build/reports/desktop-runtime-evidence/desktop-runtime-linuxArm64.json", "runtime-evidence"),
        ("test", "codex-agent-runtime-desktop/build/reports/jvm-runtime-evidence/jvm-runtime-linuxArm64.json", "jvm-runtime-evidence"),
        ("test", "codex-agent-runtime-node/build/reports/node-runtime-evidence/node-runtime-linuxArm64.json", "node-runtime-evidence"),
        ("test", "codex-agent-runtime-node/build/reports/node-runtime-evidence/node-wasm-runtime-linuxArm64.json", "node-wasm-runtime-evidence"),
    ),
    "desktop-linux-x64": (
        ("build", "codex-agent-runtime-desktop/build/distributions/*-app-server-linux-x64.zip", "classifier"),
        ("test", "codex-agent-runtime-desktop/build/reports/desktop-runtime-evidence/desktop-runtime-linuxX64.json", "runtime-evidence"),
        ("test", "codex-agent-runtime-desktop/build/reports/jvm-runtime-evidence/jvm-runtime-linuxX64.json", "jvm-runtime-evidence"),
        ("test", "codex-agent-runtime-node/build/reports/node-runtime-evidence/node-runtime-linuxX64.json", "node-runtime-evidence"),
        ("test", "codex-agent-runtime-node/build/reports/node-runtime-evidence/node-wasm-runtime-linuxX64.json", "node-wasm-runtime-evidence"),
    ),
    "desktop-windows-x64": (
        ("build", "codex-agent-runtime-desktop/build/distributions/*-app-server-windows-x64.zip", "classifier"),
        ("test", "codex-agent-runtime-desktop/build/reports/desktop-runtime-evidence/desktop-runtime-mingwX64.json", "runtime-evidence"),
        ("test", "codex-agent-runtime-desktop/build/reports/jvm-runtime-evidence/jvm-runtime-mingwX64.json", "jvm-runtime-evidence"),
        ("test", "codex-agent-runtime-node/build/reports/node-runtime-evidence/node-runtime-mingwX64.json", "node-runtime-evidence"),
        ("test", "codex-agent-runtime-node/build/reports/node-runtime-evidence/node-wasm-runtime-mingwX64.json", "node-wasm-runtime-evidence"),
    ),
    "ios-native-tests": (("test", "codex-agent-runtime-ios/build/apple-slice-exports/native-tests/native-tests-proof.json", "native-test-proof"),),
    "ios-rust-device": (
        ("build", "codex-agent-runtime-ios/build/apple-slice-exports/codex-agent-ios-arm64.a", "rust-archive"),
        ("build", "codex-agent-runtime-ios/build/apple-slice-exports/codex-agent-ios-arm64-proof.json", "rust-proof"),
    ),
    "ios-rust-simulator": (
        ("build", "codex-agent-runtime-ios/build/apple-slice-exports/codex-agent-ios-simulator-arm64.a", "rust-archive"),
        ("build", "codex-agent-runtime-ios/build/apple-slice-exports/codex-agent-ios-simulator-arm64-proof.json", "rust-proof"),
    ),
    "ios-framework-device": (("build", "codex-agent-runtime-ios/build/bin/iosArm64/releaseFramework/CodexAgent.framework/**/*", "framework-member"),),
    "ios-framework-simulator": (("build", "codex-agent-runtime-ios/build/bin/iosSimulatorArm64/releaseFramework/CodexAgent.framework/**/*", "framework-member"),),
    "ios-kotlin-tests": (
        ("test", "codex-agent-runtime-ios/build/test-results/**/*.xml", "test-report"),
        ("test", "codex-agent-runtime-ios/build/reports/ios-release/runtime-metrics.json", "runtime-metrics-evidence"),
    ),
    "ios-swift-build": (
        ("build", "codex-agent-runtime-ios/build/reports/ios-development/swift-simulator-compilation.json", "swift-build-report"),
        ("build", "codex-agent-runtime-ios/build/swift-simulator-compilation-products", "swift-compilation-products-archive"),
    ),
    "ios-swift-tests": (
        ("test", "codex-agent-runtime-ios/build/swift-authentication-tests-summary.json", "xctest-summary"),
        ("test", "codex-agent-runtime-ios/build/swift-authentication-tests.xcresult/**/*", "xctest-result"),
    ),
    "ios-package": (
        ("build", "codex-agent-runtime-ios/build/distributions/CodexAgent-*.xcframework.zip", "swift-package-binary"),
        ("build", "codex-agent-runtime-ios/build/reports/ios-release/artifact-metrics.json", "ios-package-metrics-input"),
        ("metadata", "codex-agent-runtime-ios/build/distributions/CodexAgent-*.xcframework.zip.sha256", "swiftpm-checksum"),
    ),
    "ios-privacy-metrics": (
        ("test", "codex-agent-runtime-ios/build/reports/ios-release/privacy/policy.json", "privacy-runtime-input"),
        ("test", "codex-agent-runtime-ios/build/reports/ios-release/privacy/evidence.json", "privacy-runtime-input"),
        ("metadata", "codex-agent-runtime-ios/build/reports/ios-release/privacy/audit.json", "privacy-audit-report"),
        ("metadata", "codex-agent-runtime-ios/build/reports/ios-release/privacy/privacy-required-reason-review.json", "privacy-review-report"),
    ),
    "consumer-common": (
        ("build", "build/protected-candidate/*/reports/kmp-consumer-common.json", "consumer-report"),
        ("build", "build/protected-candidate/*/reports/maven-inventory-common.json", "maven-inventory"),
        ("build", "build/protected-candidate/*/payload/maven/**/*", "maven-primary"),
    ),
    **{
        f"consumer-{target}": (
            ("build", f"build/protected-candidate/*/reports/kmp-consumer-{target}.json", "consumer-report"),
            ("build", f"build/protected-candidate/*/reports/maven-inventory-{target}.json", "maven-inventory"),
            ("build", f"build/protected-candidate/*/consumer-maven/{target}/**/*", "maven-primary"),
        )
        for target in ("android", "desktop", "ios-device", "ios-simulator", "node-js", "node-wasm")
    },
}

RUNNER_IDENTITY = {
    "os": "CODEX_CI_RUNNER_OS",
    "arch": "CODEX_CI_RUNNER_ARCH",
    "image": "CODEX_CI_RUNNER_IMAGE",
    "imageVersion": "CODEX_CI_RUNNER_IMAGE_VERSION",
}
TOOLCHAIN_IDENTITY = {
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


def recorded_identity(fields: dict[str, str]) -> list[str]:
    result: list[str] = []
    for name, environment_name in fields.items():
        value = os.environ.get(environment_name, "")
        if not value or "\n" in value or "\r" in value:
            raise ValueError(f"Missing or malformed CI identity: {environment_name}")
        result.append(f"{name}={value}")
    return result


def copy_matches(root: Path, output: Path, pattern: str) -> list[str]:
    matches = sorted(path for path in root.glob(pattern) if path.is_file())
    if not matches:
        raise ValueError(f"Required lane output did not match any regular file: {pattern}")
    copied: list[str] = []
    for source in matches:
        if source.is_symlink():
            raise ValueError(f"Lane output may not be a symbolic link: {source}")
        relative = source.relative_to(root)
        destination = output / "payload" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        copied.append(destination.relative_to(output).as_posix())
    return copied


def copy_maven_repository(root: Path, output: Path, pattern: str) -> list[str]:
    matches = sorted(path for path in root.glob(pattern) if path.is_file())
    if not matches:
        raise ValueError(f"Required Maven repository did not match any regular file: {pattern}")
    copied: list[str] = []
    for source in matches:
        if source.is_symlink():
            raise ValueError(f"Maven repository may not contain a symbolic link: {source}")
        parts = source.relative_to(root).parts
        if "maven-repository" in parts:
            start = parts.index("maven-repository") + 1
        elif "consumer-maven" in parts:
            start = parts.index("consumer-maven") + 2
        else:
            start = next(
                index + 2
                for index in range(len(parts) - 1)
                if parts[index:index + 2] == ("payload", "maven")
            )
        destination = output / "payload" / "maven" / Path(*parts[start:])
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        copied.append(destination.relative_to(output).as_posix())
    return copied


def archive_tree(root: Path, output: Path, relative: str) -> list[str]:
    source = root / relative
    if not source.is_dir() or source.is_symlink():
        raise ValueError(f"Required archive tree is missing, non-directory, or a symlink: {relative}")
    entries = [source, *source.rglob("*")]
    if not any(entry.is_file() for entry in entries):
        raise ValueError(f"Required archive tree contains no regular files: {relative}")
    for entry in entries:
        if entry.is_symlink():
            link_text = os.readlink(entry)
            link = PurePosixPath(link_text)
            member = PurePosixPath(entry.relative_to(source).as_posix())
            resolved = entry.resolve()
            if (
                link.is_absolute()
                or "\\" in link_text
                or normalize_relative((*member.parent.parts, *link.parts)) is None
                or source.resolve() not in (resolved, *resolved.parents)
            ):
                raise ValueError(f"Archive tree contains an unsafe symbolic link: {entry}")
        elif not (entry.is_file() or entry.is_dir()):
            raise ValueError(f"Archive tree may contain only regular files and directories: {entry}")
    destination = output / "payload" / source.relative_to(root).with_name(f"{source.name}.tar")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(destination, "w") as archive:
        archive.dereference = False
        archive.add(source, arcname=source.name)
    return [destination.relative_to(output).as_posix()]


def normalize_relative(parts: tuple[str, ...]) -> tuple[str, ...] | None:
    result: list[str] = []
    for part in parts:
        if part in ("", "."):
            continue
        if part == "..":
            if not result:
                return None
            result.pop()
        else:
            result.append(part)
    return tuple(result)


def safe_extract_tar(archive: Path, destination: Path) -> None:
    if destination.exists() and any(destination.iterdir()):
        raise ValueError(f"Archive destination must be empty: {destination}")
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:*") as source:
        members = source.getmembers()
        seen: set[str] = set()
        for member in members:
            path = PurePosixPath(member.name)
            normalized = path.as_posix()
            resolved = (destination / Path(*path.parts)).resolve()
            if (
                not member.name
                or "\\" in member.name
                or normalized in seen
                or path.is_absolute()
                or ".." in path.parts
                or normalized == "."
                or not (member.isfile() or member.isdir() or member.issym())
                or destination.resolve() not in resolved.parents
            ):
                raise ValueError(f"Unsafe archive member: {member.name}")
            if member.issym():
                link = PurePosixPath(member.linkname)
                if (
                    not member.linkname
                    or "\\" in member.linkname
                    or link.is_absolute()
                    or normalize_relative((*path.parent.parts, *link.parts)) is None
                ):
                    raise ValueError(f"Unsafe archive link target: {member.name}")
            seen.add(normalized)
        source.extractall(destination, members=members)
        for member in members:
            if member.isfile():
                os.chmod(destination / Path(*PurePosixPath(member.name).parts), member.mode & 0o777)
        for member in reversed(members):
            if member.isdir():
                os.chmod(destination / Path(*PurePosixPath(member.name).parts), member.mode & 0o777)


def restore_production_files(
    restored: Path,
    output: Path,
    lane: str,
) -> tuple[dict[str, str], dict[str, str]]:
    receipt = json.loads((restored / "lane-receipt.json").read_text(encoding="utf-8"))
    build_kinds = {
        kind for action, _, kind in OUTPUTS.get(lane, ()) if action == "build"
    }
    artifacts: dict[str, str] = {}
    evidence: dict[str, str] = {}
    for collection, selected, allow_firebase in (
        ("artifacts", artifacts, False),
        ("evidence", evidence, True),
    ):
        for item in receipt[collection]:
            kind = item["kind"]
            if kind not in build_kinds and not (allow_firebase and kind == "firebase-runtime-evidence"):
                continue
            relative = item["relativePath"]
            source = restored / relative
            destination = output / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            selected[relative] = kind
    return artifacts, evidence


def main() -> None:
    if sys.argv[1:2] == ["extract-tar"]:
        parser = argparse.ArgumentParser()
        parser.add_argument("command")
        parser.add_argument("--archive", type=Path, required=True)
        parser.add_argument("--output", type=Path, required=True)
        extract_arguments = parser.parse_args()
        safe_extract_tar(extract_arguments.archive, extract_arguments.output)
        return
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--lane", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--artifact-name", required=True)
    parser.add_argument("--restored-production", type=Path)
    parser.add_argument("--producer-identities", type=Path)
    parser.add_argument("--force-build", action="store_true")
    parser.add_argument("--force-production", action="store_true")
    parser.add_argument("--production-only", action="store_true")
    arguments = parser.parse_args()
    if arguments.force_build and arguments.force_production:
        raise ValueError("Force build and force production are mutually exclusive")
    root = Path(__file__).resolve().parents[1]
    output = arguments.output.resolve()
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    plan = json.loads(arguments.plan.read_text(encoding="utf-8"))
    state = dict(plan["lanes"][arguments.lane])
    producer_toolchain: list[str] = []
    if arguments.lane == "desktop-linux-arm64" and not arguments.producer_identities:
        raise ValueError("Linux ARM64 staging requires exact producer identities")
    if arguments.producer_identities:
        expected = root / "build/ci/linux-arm64-producer-identities"
        if arguments.lane != "desktop-linux-arm64" or arguments.producer_identities.resolve() != expected.resolve():
            raise ValueError("Producer identities are supported only at the strict Linux ARM64 identity path")
        producer_toolchain = bound_toolchain(expected)
    if arguments.force_build:
        state["build"] = True
        state["test"] = True
    if arguments.force_production:
        state["build"] = True
    if arguments.production_only:
        state.update(build=True, test=False, metadata=False)
    artifact_map: dict[str, str] = {}
    evidence_map: dict[str, str] = {}
    if arguments.restored_production:
        restored = arguments.restored_production.resolve()
        artifact_map, evidence_map = restore_production_files(restored, output, arguments.lane)
    for action, pattern, kind in OUTPUTS.get(arguments.lane, ()):
        selected = state[action] or (
            arguments.lane == "ios-swift-build"
            and action == "build"
            and state["test"]
        ) or (
            arguments.lane == "ios-privacy-metrics"
            and action == "metadata"
            and state["test"]
        ) or (
            arguments.lane.startswith("consumer-")
            and any(state[name] for name in ("build", "test", "metadata"))
        ) or (
            arguments.lane == "ios-privacy-metrics"
            and action == "test"
            and state["metadata"]
        )
        if selected:
            if kind == "node-runtime-evidence" and os.environ.get("CI_NODE_JS_REQUIRED", "true") != "true":
                continue
            if kind == "node-wasm-runtime-evidence" and os.environ.get("CI_NODE_WASM_REQUIRED", "true") != "true":
                continue
            if kind == "maven-primary":
                copied = copy_maven_repository(root, output, pattern)
            elif kind == "swift-compilation-products-archive":
                copied = archive_tree(root, output, pattern)
            else:
                copied = copy_matches(root, output, pattern)
            if kind.endswith(("report", "proof", "summary", "evidence", "result")):
                evidence_map.update((relative, kind) for relative in copied)
            else:
                artifact_map.update((relative, kind) for relative in copied)
    marker = output / "lane-result.txt"
    marker.write_text(f"lane={arguments.lane}\nresult=passed\n", encoding="utf-8")
    evidence_map["lane-result.txt"] = "lane-result"
    validation_actions = {
        action for action in ("build", "test", "metadata") if state[action]
    }
    if arguments.restored_production:
        validation_actions.add("build")
    create_receipt(argparse.Namespace(
        plan=arguments.plan,
        lane=arguments.lane,
        output=output,
        workflow_path=".github/workflows/ci.yml",
        artifact_name=arguments.artifact_name,
        run_id=int(os.environ.get("GITHUB_RUN_ID", "1")),
        run_attempt=int(os.environ.get("GITHUB_RUN_ATTEMPT", "1")),
        runner=recorded_identity(RUNNER_IDENTITY),
        toolchain=[
            *recorded_identity(TOOLCHAIN_IDENTITY),
            *producer_toolchain,
            f"{VALIDATION_ACTIONS_KEY}={','.join(sorted(validation_actions))}",
        ],
        artifact=[f"{relative}={kind}" for relative, kind in sorted(artifact_map.items())],
        evidence=[f"{relative}={kind}" for relative, kind in sorted(evidence_map.items())],
    ))


if __name__ == "__main__":
    main()
