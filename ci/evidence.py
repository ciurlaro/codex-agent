#!/usr/bin/env python3
"""Validate and attach trusted Android evidence to one lane artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path

from receipt import validate_receipt


KIND = "firebase-runtime-evidence"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("check", "attach"))
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--expected-commit")
    parser.add_argument("--expected-artifact")
    parser.add_argument("--expected-plan-artifact")
    parser.add_argument("--expected-repository")
    parser.add_argument("--expected-event")
    parser.add_argument("--replace", action="store_true")
    arguments = parser.parse_args()
    plan = json.loads(arguments.plan.read_text(encoding="utf-8"))
    if plan.get("schemaVersion") != 1 or not plan.get("mergeReady"):
        raise ValueError("Android evidence requires one merge-ready schema-v1 impact plan")
    tree = plan.get("validationTree")
    if not isinstance(tree, str) or len(tree) != 40 or any(character not in "0123456789abcdef" for character in tree):
        raise ValueError("Android evidence plan has an invalid validation tree")
    expected = {
        "repository": arguments.expected_repository,
        "event": arguments.expected_event,
        "validationCommit": arguments.expected_commit,
    }
    for field, value in expected.items():
        if value and plan.get(field) != value:
            raise ValueError(f"Android evidence plan {field} mismatch")
    if arguments.expected_plan_artifact and arguments.expected_plan_artifact != f"codex-agent-ci-plan-{tree}":
        raise ValueError("Android evidence plan artifact name mismatch")
    if arguments.expected_artifact and arguments.expected_artifact != f"codex-agent-ci-android-{tree}":
        raise ValueError("Android evidence artifact does not match the planned tree")
    android = plan.get("lanes", {}).get("android", {})
    if not plan.get("androidEvidenceRequired") or not any(android.get(action) for action in ("build", "test", "metadata")):
        raise ValueError("Android evidence was not required by the impact plan")
    receipt_path = arguments.artifact / "lane-receipt.json"
    receipt = validate_receipt(receipt_path, arguments.plan, arguments.artifact, "android")
    if arguments.expected_commit and receipt["validationCommit"] != arguments.expected_commit:
        raise ValueError("Android evidence commit does not match the trusted workflow input")
    if arguments.expected_artifact and receipt["artifactName"] != arguments.expected_artifact:
        raise ValueError("Android evidence artifact does not match the trusted workflow input")
    reused = any(item["kind"] == KIND for item in receipt["evidence"])
    if arguments.mode == "attach" and arguments.replace:
        replaced_kinds = {KIND, "transport-provenance"}
        for item in receipt["evidence"]:
            if item["kind"] in replaced_kinds:
                (arguments.artifact / item["relativePath"]).unlink()
        receipt["evidence"] = [item for item in receipt["evidence"] if item["kind"] not in replaced_kinds]
        reused = False
    if arguments.mode == "attach" and not reused:
        if arguments.source is None or not arguments.source.is_dir():
            raise ValueError("Android evidence source directory is required")
        files = sorted(path for path in arguments.source.rglob("*") if path.is_file())
        if not files or any(path.is_symlink() for path in arguments.source.rglob("*")):
            raise ValueError("Android evidence must contain regular files and no links")
        for source in files:
            relative = Path("payload/external/android-runtime-evidence") / source.relative_to(arguments.source)
            destination = arguments.artifact / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            receipt["evidence"].append({
                "relativePath": relative.as_posix(),
                "kind": KIND,
                "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
            })
        receipt["evidence"] = sorted(receipt["evidence"], key=lambda item: item["relativePath"])
        receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        validate_receipt(receipt_path, arguments.plan, arguments.artifact, "android")
    if arguments.github_output:
        with arguments.github_output.open("a", encoding="utf-8") as output:
            output.write(f"reused={str(reused).lower()}\n")


if __name__ == "__main__":
    main()
