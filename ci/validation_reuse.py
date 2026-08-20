#!/usr/bin/env python3
"""Reuse a successful PR aggregate when a merge group has the identical Git tree."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import tempfile
from pathlib import Path

from receipt import required_lanes, safe_extract
from reuse import download_artifact, paginated_items, run_matches_pr


def one(root: Path, name: str) -> Path:
    matches = list(root.rglob(name))
    if len(matches) != 1 or not matches[0].is_file() or matches[0].is_symlink():
        raise ValueError(f"Expected exactly one safe {name}")
    return matches[0]


def validate(root: Path, current_plan: Path) -> dict[str, object]:
    plan = json.loads(current_plan.read_text(encoding="utf-8"))
    source_plan = json.loads(one(root, "impact-plan.json").read_text(encoding="utf-8"))
    receipt = json.loads(one(root, "validation-receipt.json").read_text(encoding="utf-8"))
    expected = {
        "schemaVersion", "repository", "event", "validationCommit", "validationTree",
        "impactPlan", "lanes", "result",
    }
    if set(receipt) != expected or receipt["schemaVersion"] != 1 or receipt["result"] != "passed":
        raise ValueError("Unsupported aggregate validation receipt")
    plan_keys = {
        "schemaVersion", "event", "repository", "pullRequest", "baseCommit", "headCommit",
        "validationCommit", "validationTree", "mergeReady", "androidEvidenceRequired", "full",
        "unknownPaths", "changedPaths", "lanes",
    }
    if set(source_plan) != plan_keys or source_plan.get("schemaVersion") != 1:
        raise ValueError("Reusable PR impact plan schema mismatch")
    if receipt["event"] != "pull_request" or source_plan.get("event") != "pull_request":
        raise ValueError("Only authoritative PR validation may satisfy an identical merge group")
    if (
        not source_plan.get("mergeReady")
        or receipt["repository"] != plan["repository"]
        or source_plan["repository"] != plan["repository"]
        or source_plan["pullRequest"] != plan.get("pullRequest")
    ):
        raise ValueError("Reusable PR validation identity mismatch")
    if (
        receipt["validationCommit"] != source_plan["validationCommit"]
        or receipt["validationTree"] != plan["validationTree"]
        or source_plan.get("validationTree") != plan["validationTree"]
    ):
        raise ValueError("Reusable PR validation tree mismatch")
    lanes = receipt["lanes"]
    if not isinstance(lanes, dict) or any(
        not isinstance(value, dict)
        or set(value) != {"runId", "runAttempt", "artifactName", "validationCommit", "validationTree", "result"}
        or value["result"] != "passed"
        for value in lanes.values()
    ):
        raise ValueError("Reusable PR validation lane set is malformed")
    required = required_lanes(source_plan)
    if set(lanes) != set(required):
        raise ValueError("Reusable PR validation lane set does not match its impact plan")
    if plan.get("androidEvidenceRequired") and not source_plan["androidEvidenceRequired"]:
        raise ValueError("Reusable PR validation lacks required Android evidence")
    for lane, state in plan.get("lanes", {}).items():
        if any(state.get(action) and not source_plan["lanes"].get(lane, {}).get(action) for action in ("build", "test", "metadata")):
            raise ValueError(f"Reusable PR validation does not cover merge-group work: {lane}")
    for lane, value in lanes.items():
        if (
            value["validationCommit"] != source_plan["validationCommit"]
            or value["validationTree"] != source_plan["validationTree"]
            or value["artifactName"] != f"codex-agent-ci-{lane}-{source_plan['validationTree']}"
        ):
            raise ValueError(f"Reusable PR lane provenance mismatch: {lane}")
    return receipt


def materialize(root: Path, current_plan: Path, output: Path) -> None:
    receipt = validate(root, current_plan)
    plan = json.loads(current_plan.read_text(encoding="utf-8"))
    receipt.update(
        event="merge_group",
        validationCommit=plan["validationCommit"],
        validationTree=plan["validationTree"],
        impactPlan=current_plan.name,
        lanes={lane: receipt["lanes"][lane] for lane in required_lanes(plan)},
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def discover(plan_path: Path, destination: Path, token: str, api_url: str) -> dict[str, object]:
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    repository = plan["repository"]
    wanted = f"codex-agent-ci-validation-{plan['validationTree']}"
    candidate = destination.with_name(f"{destination.name}.candidate")
    try:
        runs = paginated_items(
            f"{api_url}/repos/{repository}/actions/workflows/ci.yml/runs?event=pull_request&status=completed",
            "workflow_runs",
            token,
        )
        for run in runs:
            if not isinstance(run, dict):
                raise ValueError("GitHub workflow run response is malformed")
            if run.get("conclusion") != "success" or not run_matches_pr(run, plan["pullRequest"]):
                continue
            artifacts = paginated_items(
                f"{api_url}/repos/{repository}/actions/runs/{run['id']}/artifacts",
                "artifacts",
                token,
            )
            if any(not isinstance(item, dict) for item in artifacts):
                raise ValueError("GitHub artifact response is malformed")
            artifact = next(
                (item for item in artifacts if item.get("name") == wanted and not item.get("expired")),
                None,
            )
            if artifact is None:
                continue
            try:
                archive_bytes = download_artifact(artifact, token)
                with tempfile.NamedTemporaryFile(suffix=".zip") as archive:
                    archive.write(archive_bytes)
                    archive.flush()
                    if candidate.exists():
                        shutil.rmtree(candidate)
                    safe_extract(Path(archive.name), candidate)
                validate(candidate, plan_path)
                if destination.exists():
                    shutil.rmtree(destination)
                candidate.rename(destination)
                return {
                    "reused": True,
                    "sourceRunId": int(run["id"]),
                    "sourceRunAttempt": int(run.get("run_attempt", 1)),
                    "artifactName": wanted,
                }
            except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError):
                continue
    except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError):
        pass
    if candidate.exists():
        shutil.rmtree(candidate)
    return {"reused": False}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("discover", "validate", "materialize"))
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    if arguments.mode == "discover":
        result = discover(
            arguments.plan,
            arguments.destination,
            os.environ.get("GITHUB_TOKEN", ""),
            os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
    elif arguments.mode == "validate":
        validate(arguments.destination, arguments.plan)
        result = {"reused": True}
    else:
        if arguments.output is None:
            raise ValueError("Materialized validation receipt output is required")
        materialize(arguments.destination, arguments.plan, arguments.output)
        result = {"reused": True}
    if arguments.github_output:
        with arguments.github_output.open("a", encoding="utf-8") as output:
            for key, value in result.items():
                name = {"sourceRunId": "source_run_id", "sourceRunAttempt": "source_run_attempt", "artifactName": "artifact_name"}.get(key, key)
                output.write(f"{name}={str(value).lower() if isinstance(value, bool) else value}\n")


if __name__ == "__main__":
    main()
