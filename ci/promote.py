#!/usr/bin/env python3
"""Promote immutable merge-group CI artifacts to an equal-tree main commit."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import urllib.parse
from pathlib import Path

from impact import LANES
from receipt import (
    INPUT_NAMES,
    SCHEMA_VERSION,
    read_json,
    required_lanes,
    safe_extract,
    validate_receipt,
    write_json,
)
from reuse import api_json, download_artifact, github_output


CI_WORKFLOW = ".github/workflows/ci.yml"
PROMOTION_WORKFLOW = ".github/workflows/promote.yml"
PREDECESSOR_WAIT_SECONDS = 1800
PREDECESSOR_POLL_SECONDS = 15
OID = re.compile(r"[0-9a-f]{40}")
PLAN_KEYS = {
    "schemaVersion", "event", "repository", "pullRequest", "baseCommit", "headCommit",
    "validationCommit", "validationTree", "mergeReady", "androidEvidenceRequired", "full",
    "unknownPaths", "changedPaths", "lanes",
}
LANE_PLAN_KEYS = {"build", "test", "metadata", "reuseAllowed", "reasons"}
AGGREGATE_KEYS = {
    "schemaVersion", "repository", "event", "validationCommit", "validationTree",
    "impactPlan", "lanes", "result",
}
AGGREGATE_LANE_KEYS = {
    "runId", "runAttempt", "artifactName", "validationCommit", "validationTree", "result",
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
PROMOTION_RECEIPT_KEYS = {
    *PROMOTION_PLAN_KEYS,
    "workflowPath", "promotionRunId", "promotionRunAttempt", "result",
}


def api_items(url: str, key: str, token: str) -> list[dict[str, object]]:
    result: list[dict[str, object]] = []
    page = 1
    while True:
        separator = "&" if "?" in url else "?"
        value = api_json(f"{url}{separator}per_page=100&page={page}", token).get(key)
        if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
            raise ValueError(f"GitHub response is missing {key}")
        result.extend(value)
        if len(value) < 100:
            return result
        page += 1


def require_oid(value: object, label: str) -> str:
    if not isinstance(value, str) or not OID.fullmatch(value):
        raise ValueError(f"Invalid {label} Git object ID")
    return value


def positive_int(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise ValueError(f"Invalid {label}")
    return value


def git_oid(root: Path, revision: str, kind: str) -> str:
    value = subprocess.run(
        ["git", "rev-parse", f"{revision}^{{{kind}}}"],
        cwd=root,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    ).stdout.strip()
    return require_oid(value, f"final {kind}")


def workflow_runs(
    api_url: str,
    repository: str,
    workflow: str,
    event: str,
    token: str,
) -> list[dict[str, object]]:
    workflow_id = urllib.parse.quote(workflow, safe="")
    query = urllib.parse.urlencode({"event": event, "status": "completed"})
    url = f"{api_url}/repos/{repository}/actions/workflows/{workflow_id}/runs?{query}"
    return api_items(url, "workflow_runs", token)


def artifacts_for_run(
    api_url: str,
    repository: str,
    run_id: int,
    token: str,
) -> dict[str, dict[str, object]]:
    url = f"{api_url}/repos/{repository}/actions/runs/{run_id}/artifacts"
    result: dict[str, dict[str, object]] = {}
    for artifact in api_items(url, "artifacts", token):
        name = artifact.get("name")
        if artifact.get("expired", True) or not isinstance(name, str):
            continue
        if name in result:
            raise ValueError(f"Duplicate artifact {name!r} in run {run_id}")
        positive_int(artifact.get("id"), f"artifact ID for {name}")
        if not isinstance(artifact.get("archive_download_url"), str):
            raise ValueError(f"Artifact {name!r} has no download URL")
        result[name] = artifact
    return result


def extract_artifact(artifact: dict[str, object], token: str, destination: Path) -> None:
    with tempfile.TemporaryDirectory() as temporary:
        archive = Path(temporary) / "artifact.zip"
        archive.write_bytes(download_artifact(artifact, token))
        safe_extract(archive, destination)


def one_file(root: Path, name: str) -> Path:
    matches = [path for path in root.rglob(name) if path.is_file() and not path.is_symlink()]
    if len(matches) != 1:
        raise ValueError(f"Artifact must contain exactly one {name}")
    return matches[0]


def require_exact_files(root: Path, names: set[str]) -> None:
    actual = {
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file()
    }
    if actual != names:
        raise ValueError(f"Artifact file set mismatch: expected={sorted(names)} actual={sorted(actual)}")


def validate_plan(
    plan_path: Path,
    repository: str,
    validated_commit: str,
    final_tree: str,
    allow_reused_validation: bool = False,
) -> dict[str, object]:
    plan = read_json(plan_path)
    if set(plan) != PLAN_KEYS or plan.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("Unsupported or non-exact impact plan schema")
    if (
        plan.get("repository") != repository
        or plan.get("event") != "merge_group"
        or plan.get("mergeReady") is not True
        or plan.get("validationCommit") != validated_commit
        or plan.get("validationTree") != final_tree
    ):
        raise ValueError("Impact plan does not identify the selected merge-group tree")
    if not isinstance(plan.get("pullRequest"), int) or plan["pullRequest"] < 1:
        raise ValueError("Merge-group impact plan has no pull-request identity")
    for field in ("baseCommit", "headCommit", "validationCommit", "validationTree"):
        require_oid(plan[field], f"impact plan {field}")
    if (
        not isinstance(plan.get("androidEvidenceRequired"), bool)
        or not isinstance(plan.get("full"), bool)
        or not isinstance(plan.get("unknownPaths"), list)
        or not isinstance(plan.get("changedPaths"), list)
    ):
        raise ValueError("Impact plan fields have invalid types")
    lanes = plan.get("lanes")
    if not isinstance(lanes, dict) or set(lanes) != set(LANES):
        raise ValueError("Impact plan lane set does not match schema v1")
    for lane, state in lanes.items():
        if (
            not isinstance(state, dict)
            or set(state) != LANE_PLAN_KEYS
            or any(not isinstance(state[key], bool) for key in ("build", "test", "metadata", "reuseAllowed"))
            or not isinstance(state["reasons"], list)
            or any(not isinstance(reason, str) for reason in state["reasons"])
        ):
            raise ValueError(f"Impact plan lane {lane} has invalid fields")
    expected_files = {"impact-plan.json"}
    expected_files.update(
        f"inventories/{lane}/{filename}"
        for lane in LANES
        for filename in INPUT_NAMES.values()
    )
    actual_files = {
        path.relative_to(plan_path.parent).as_posix()
        for path in plan_path.parent.rglob("*")
        if path.is_file()
    }
    unexpected = actual_files - expected_files
    if expected_files - actual_files or unexpected and not (
        allow_reused_validation
        and all(path.startswith("reused-validation/") for path in unexpected)
    ):
        raise ValueError("Plan artifact has a missing or unexpected file set")
    return plan


def validate_aggregate(
    aggregate_path: Path,
    plan: dict[str, object],
) -> dict[str, object]:
    aggregate = read_json(aggregate_path)
    if set(aggregate) != AGGREGATE_KEYS or aggregate.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("Unsupported or non-exact aggregate receipt schema")
    for field in ("repository", "event", "validationCommit", "validationTree"):
        if aggregate.get(field) != plan.get(field):
            raise ValueError(f"Aggregate receipt {field} mismatch")
    if aggregate.get("impactPlan") != "impact-plan.json" or aggregate.get("result") != "passed":
        raise ValueError("Aggregate receipt is not a passing authoritative validation")
    lanes = aggregate.get("lanes")
    required = required_lanes(plan)
    if not isinstance(lanes, dict) or set(lanes) != set(required):
        raise ValueError("Aggregate receipt lane set does not match the impact plan")
    for lane, value in lanes.items():
        if not isinstance(value, dict) or set(value) != AGGREGATE_LANE_KEYS:
            raise ValueError(f"Aggregate lane {lane} has an invalid schema")
        positive_int(value["runId"], f"{lane} producer run ID")
        positive_int(value["runAttempt"], f"{lane} producer run attempt")
        require_oid(value["validationCommit"], f"{lane} validation commit")
        require_oid(value["validationTree"], f"{lane} validation tree")
        name = value.get("artifactName")
        if (
            value.get("result") != "passed"
            or not isinstance(name, str)
            or not name.startswith(f"codex-agent-ci-{lane}-")
            or any(character in name for character in "/\\")
        ):
            raise ValueError(f"Aggregate lane {lane} is not a passing CI artifact")
    return aggregate


def validate_source_artifacts(
    plan_root: Path,
    aggregate_root: Path,
    repository: str,
    validated_commit: str,
    final_tree: str,
) -> tuple[dict[str, object], dict[str, object], Path, Path]:
    plan_path = plan_root / "impact-plan.json"
    if not plan_path.is_file() or plan_path.is_symlink():
        raise ValueError("Plan artifact has no root impact-plan.json")
    aggregate_plan_path = one_file(aggregate_root, "impact-plan.json")
    aggregate_path = one_file(aggregate_root, "validation-receipt.json")
    aggregate_files = {path for path in aggregate_root.rglob("*") if path.is_file()}
    if aggregate_files != {aggregate_plan_path, aggregate_path}:
        raise ValueError("Aggregate artifact has a missing or unexpected file set")
    if aggregate_plan_path.read_bytes() != plan_path.read_bytes():
        raise ValueError("Aggregate and plan artifacts contain different impact plans")
    plan = validate_plan(plan_path, repository, validated_commit, final_tree, allow_reused_validation=True)
    aggregate = validate_aggregate(aggregate_path, plan)
    return plan, aggregate, plan_path, aggregate_path


def selected_validation_run(
    api_url: str,
    repository: str,
    final_tree: str,
    token: str,
) -> dict[str, object]:
    for run in workflow_runs(api_url, repository, "ci.yml", "merge_group", token):
        if (
            run.get("conclusion") != "success"
            or run.get("event") != "merge_group"
            or run.get("path") != CI_WORKFLOW
        ):
            continue
        commit = require_oid(run.get("head_sha"), "validation commit")
        if commit_tree(api_url, repository, commit, token) == final_tree:
            positive_int(run.get("id"), "validation run ID")
            positive_int(run.get("run_attempt"), "validation run attempt")
            return run
    raise ValueError(f"No successful merge-group validation has Git tree {final_tree}")


def commit_tree(api_url: str, repository: str, commit: str, token: str) -> str:
    value = api_json(f"{api_url}/repos/{repository}/git/commits/{commit}", token)
    tree = value.get("tree")
    if not isinstance(tree, dict):
        raise ValueError(f"GitHub commit {commit} has no tree")
    return require_oid(tree.get("sha"), "commit tree")


def immediate_first_parent(
    api_url: str,
    repository: str,
    commit: str,
    token: str,
) -> str | None:
    value = api_json(f"{api_url}/repos/{repository}/git/commits/{commit}", token)
    parents = value.get("parents")
    if not isinstance(parents, list) or any(not isinstance(parent, dict) for parent in parents):
        raise ValueError(f"GitHub commit {commit} has malformed parents")
    if not parents:
        return None
    return require_oid(parents[0].get("sha"), "immediate first-parent commit")


def validate_promotion_plan(value: dict[str, object]) -> dict[str, object]:
    if set(value) != PROMOTION_PLAN_KEYS or value.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("Unsupported or non-exact promotion plan schema")
    repository = value.get("repository")
    if not isinstance(repository, str) or repository.count("/") != 1:
        raise ValueError("Invalid promotion repository")
    final_commit = require_oid(value.get("finalCommit"), "final commit")
    final_tree = require_oid(value.get("finalTree"), "final tree")
    require_oid(value.get("validatedCommit"), "validated commit")
    if require_oid(value.get("validatedTree"), "validated tree") != final_tree:
        raise ValueError("Validated and final trees differ")
    positive_int(value.get("validationRunId"), "validation run ID")
    positive_int(value.get("validationRunAttempt"), "validation run attempt")
    expected_aggregate = f"codex-agent-promoted-validation-{final_commit}"
    if value.get("promotedAggregateArtifactName") != expected_aggregate:
        raise ValueError("Promoted aggregate artifact name mismatch")
    if value.get("promotedInventoryArtifactName") != f"codex-agent-promoted-inventories-{final_commit}":
        raise ValueError("Promoted inventory artifact name mismatch")
    if value.get("sourcePlanArtifactName") != f"codex-agent-ci-plan-{final_tree}":
        raise ValueError("Source plan artifact name mismatch")
    if value.get("sourceAggregateArtifactName") != f"codex-agent-ci-validation-{final_tree}":
        raise ValueError("Source aggregate artifact name mismatch")
    lanes = value.get("lanes")
    if not isinstance(lanes, dict) or set(lanes) != set(LANES):
        raise ValueError("Promotion must contain the complete lane set")
    for lane, item in lanes.items():
        if not isinstance(item, dict) or set(item) != PROMOTION_LANE_KEYS:
            raise ValueError(f"Invalid promotion lane {lane}")
        positive_int(item["sourceRunId"], f"{lane} source run ID")
        positive_int(item["sourceRunAttempt"], f"{lane} source run attempt")
        if item.get("promotedArtifactName") != f"codex-agent-promoted-{lane}-{final_commit}":
            raise ValueError(f"Promoted lane {lane} artifact name mismatch")
        source = item.get("sourceArtifactName")
        if not isinstance(source, str) or any(character in source for character in "/\\"):
            raise ValueError(f"Source lane {lane} artifact name mismatch")
        if item.get("sourceKind") == "validation":
            if (
                not source.startswith(f"codex-agent-ci-{lane}-")
                or item.get("sourcePromotionRunId") is not None
                or item.get("sourcePromotionCommit") is not None
            ):
                raise ValueError(f"Validation source identity mismatch for {lane}")
        elif item.get("sourceKind") == "promotion":
            positive_int(item.get("sourcePromotionRunId"), f"{lane} source promotion run ID")
            source_commit = require_oid(item.get("sourcePromotionCommit"), f"{lane} source promotion commit")
            if (
                item["sourcePromotionRunId"] != item["sourceRunId"]
                or source != f"codex-agent-promoted-{lane}-{source_commit}"
            ):
                raise ValueError(f"Prior promotion source identity mismatch for {lane}")
        else:
            raise ValueError(f"Invalid source kind for {lane}")
    return value


def validate_promotion_receipt(
    value: dict[str, object],
    repository: str,
    final_commit: str,
    final_tree: str,
    run: dict[str, object],
) -> dict[str, object]:
    if set(value) != PROMOTION_RECEIPT_KEYS or value.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("Unsupported or non-exact promotion receipt schema")
    plan_value = {key: value[key] for key in PROMOTION_PLAN_KEYS}
    validate_promotion_plan(plan_value)
    if (
        value.get("repository") != repository
        or value.get("finalCommit") != final_commit
        or value.get("finalTree") != final_tree
        or value.get("workflowPath") != PROMOTION_WORKFLOW
        or value.get("result") != "passed"
        or value.get("promotionRunId") != run.get("id")
        or value.get("promotionRunAttempt") != run.get("run_attempt")
    ):
        raise ValueError("Promotion receipt does not identify its successful push run")
    positive_int(value["promotionRunId"], "promotion run ID")
    positive_int(value["promotionRunAttempt"], "promotion run attempt")
    return value


def already_promoted(
    api_url: str,
    repository: str,
    final_commit: str,
    final_tree: str,
    token: str,
) -> bool:
    aggregate_name = f"codex-agent-promoted-validation-{final_commit}"
    inventory_name = f"codex-agent-promoted-inventories-{final_commit}"
    for run in workflow_runs(api_url, repository, "promote.yml", "push", token):
        if (
            run.get("conclusion") != "success"
            or run.get("event") != "push"
            or run.get("path") != PROMOTION_WORKFLOW
            or run.get("head_branch") != "main"
            or run.get("head_sha") != final_commit
        ):
            continue
        run_id = positive_int(run.get("id"), "promotion run ID")
        artifacts = artifacts_for_run(api_url, repository, run_id, token)
        aggregate_artifact = artifacts.get(aggregate_name)
        inventory_artifact = artifacts.get(inventory_name)
        if aggregate_artifact is None or inventory_artifact is None:
            continue
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "aggregate"
            inventory_root = Path(temporary) / "inventories"
            extract_artifact(aggregate_artifact, token, root)
            extract_artifact(inventory_artifact, token, inventory_root)
            require_exact_files(
                root,
                {"impact-plan.json", "validation-receipt.json", "promotion-receipt.json"},
            )
            receipt = validate_promotion_receipt(
                read_json(root / "promotion-receipt.json"),
                repository,
                final_commit,
                final_tree,
                run,
            )
            plan_path = inventory_root / "impact-plan.json"
            plan = validate_plan(
                plan_path,
                repository,
                str(receipt["validatedCommit"]),
                str(receipt["validatedTree"]),
            )
            if plan_path.read_bytes() != (root / "impact-plan.json").read_bytes():
                raise ValueError("Promoted aggregate and inventory plans differ")
            validate_aggregate(root / "validation-receipt.json", plan)
        if all(item["promotedArtifactName"] in artifacts for item in receipt["lanes"].values()):
            return True
    return False


def inventories_match(current_plan: Path, prior_plan: Path, lane: str) -> bool:
    return all(
        (current_plan.parent / "inventories" / lane / filename).read_bytes()
        == (prior_plan.parent / "inventories" / lane / filename).read_bytes()
        for filename in INPUT_NAMES.values()
    )


def predecessor_promotion_sources(
    api_url: str,
    repository: str,
    predecessor_commit: str,
    final_commit: str,
    current_plan: Path,
    lanes: set[str],
    token: str,
) -> dict[str, dict[str, object]] | None:
    for run in workflow_runs(api_url, repository, "promote.yml", "push", token):
        prior_commit = run.get("head_sha")
        if (
            run.get("conclusion") != "success"
            or run.get("event") != "push"
            or run.get("path") != PROMOTION_WORKFLOW
            or run.get("head_branch") != "main"
            or prior_commit != predecessor_commit
        ):
            continue
        run_id = positive_int(run.get("id"), "prior promotion run ID")
        run_attempt = positive_int(run.get("run_attempt"), "prior promotion run attempt")
        artifacts = artifacts_for_run(api_url, repository, run_id, token)
        aggregate_name = f"codex-agent-promoted-validation-{prior_commit}"
        inventory_name = f"codex-agent-promoted-inventories-{prior_commit}"
        aggregate_artifact = artifacts.get(aggregate_name)
        inventory_artifact = artifacts.get(inventory_name)
        if aggregate_artifact is None or inventory_artifact is None:
            continue
        prior_tree = commit_tree(api_url, repository, prior_commit, token)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "aggregate"
            inventory_root = Path(temporary) / "inventories"
            extract_artifact(aggregate_artifact, token, root)
            extract_artifact(inventory_artifact, token, inventory_root)
            require_exact_files(
                root,
                {"impact-plan.json", "validation-receipt.json", "promotion-receipt.json"},
            )
            receipt = validate_promotion_receipt(
                read_json(root / "promotion-receipt.json"),
                repository,
                prior_commit,
                prior_tree,
                run,
            )
            prior_plan = inventory_root / "impact-plan.json"
            prior_plan_value = validate_plan(
                prior_plan,
                repository,
                str(receipt["validatedCommit"]),
                str(receipt["validatedTree"]),
            )
            if prior_plan.read_bytes() != (root / "impact-plan.json").read_bytes():
                raise ValueError("Prior promoted aggregate and inventory plans differ")
            validate_aggregate(root / "validation-receipt.json", prior_plan_value)
            source_names = {
                lane: str(receipt["lanes"][lane]["promotedArtifactName"])
                for lane in LANES
            }
            if any(source_name not in artifacts for source_name in source_names.values()):
                continue
            incompatible = sorted(
                lane for lane in lanes if not inventories_match(current_plan, prior_plan, lane)
            )
            if incompatible:
                raise ValueError(
                    f"Immediate first-parent promotion {predecessor_commit} is incompatible "
                    f"with absent lanes {incompatible}"
                )
            return {
                lane: {
                    "sourceKind": "promotion",
                    "sourceRunId": run_id,
                    "sourceRunAttempt": run_attempt,
                    "sourceArtifactName": source_names[lane],
                    "sourcePromotionRunId": run_id,
                    "sourcePromotionCommit": predecessor_commit,
                    "promotedArtifactName": f"codex-agent-promoted-{lane}-{final_commit}",
                }
                for lane in lanes
            }
    return None


def wait_for_predecessor_promotion(
    api_url: str,
    repository: str,
    predecessor_commit: str,
    final_commit: str,
    current_plan: Path,
    lanes: set[str],
    token: str,
    timeout_seconds: int,
    poll_seconds: int,
) -> dict[str, dict[str, object]]:
    if timeout_seconds < 0 or poll_seconds < 1:
        raise ValueError("Predecessor wait timeout must be non-negative and poll interval positive")
    deadline = time.monotonic() + timeout_seconds
    while True:
        result = predecessor_promotion_sources(
            api_url,
            repository,
            predecessor_commit,
            final_commit,
            current_plan,
            lanes,
            token,
        )
        if result is not None:
            return result
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(
                f"Timed out after {timeout_seconds} seconds waiting for the complete successful "
                f"promotion of immediate first parent {predecessor_commit}; "
                f"required carried lanes: {sorted(lanes)}"
            )
        time.sleep(min(poll_seconds, remaining))


def discover(arguments: argparse.Namespace) -> dict[str, object]:
    token = arguments.token or os.environ.get("GITHUB_TOKEN", "")
    if not token:
        raise ValueError("GITHUB_TOKEN is required for artifact promotion")
    root = arguments.repo.resolve()
    final_commit = git_oid(root, arguments.final_commit, "commit")
    if final_commit != arguments.final_commit:
        raise ValueError("Final commit must be the exact checked-out commit ID")
    final_tree = git_oid(root, final_commit, "tree")
    api_url = arguments.api_url.rstrip("/")
    if already_promoted(api_url, arguments.repository, final_commit, final_tree, token):
        result: dict[str, object] = {
            "already_promoted": True,
            "has_lanes": False,
            "matrix": json.dumps({"include": []}, separators=(",", ":")),
            "validation_run_id": "",
            "promoted_aggregate": f"codex-agent-promoted-validation-{final_commit}",
        }
        github_output(arguments.github_output, result)
        return result

    run = selected_validation_run(api_url, arguments.repository, final_tree, token)
    run_id = positive_int(run["id"], "validation run ID")
    run_attempt = positive_int(run["run_attempt"], "validation run attempt")
    validated_commit = require_oid(run["head_sha"], "validated commit")
    artifacts = artifacts_for_run(api_url, arguments.repository, run_id, token)
    plan_name = f"codex-agent-ci-plan-{final_tree}"
    aggregate_name = f"codex-agent-ci-validation-{final_tree}"
    missing = [name for name in (plan_name, aggregate_name) if name not in artifacts]
    if missing:
        raise ValueError(f"Validation run {run_id} is missing artifacts: {missing}")

    with tempfile.TemporaryDirectory() as temporary:
        temporary_root = Path(temporary)
        plan_root = temporary_root / "plan"
        aggregate_root = temporary_root / "aggregate"
        extract_artifact(artifacts[plan_name], token, plan_root)
        extract_artifact(artifacts[aggregate_name], token, aggregate_root)
        plan, aggregate, plan_path, aggregate_path = validate_source_artifacts(
            plan_root,
            aggregate_root,
            arguments.repository,
            validated_commit,
            final_tree,
        )
        current_lanes = required_lanes(plan)
        lanes: dict[str, dict[str, object]] = {
            lane: {
                "sourceKind": "validation",
                "sourceRunId": aggregate["lanes"][lane]["runId"],
                "sourceRunAttempt": aggregate["lanes"][lane]["runAttempt"],
                "sourceArtifactName": aggregate["lanes"][lane]["artifactName"],
                "sourcePromotionRunId": None,
                "sourcePromotionCommit": None,
                "promotedArtifactName": f"codex-agent-promoted-{lane}-{final_commit}",
            }
            for lane in current_lanes
        }
        artifact_sets: dict[int, dict[str, dict[str, object]]] = {run_id: artifacts}
        missing_lanes: list[str] = []
        for lane, item in lanes.items():
            source_run = int(item["sourceRunId"])
            if source_run not in artifact_sets:
                artifact_sets[source_run] = artifacts_for_run(
                    api_url, arguments.repository, source_run, token
                )
            source_artifacts = artifact_sets[source_run]
            if item["sourceArtifactName"] not in source_artifacts:
                missing_lanes.append(f"{lane}:{item['sourceArtifactName']}@{source_run}")
        if missing_lanes:
            raise ValueError(f"Validation receipt source artifacts are missing: {missing_lanes}")
        absent = set(LANES) - set(lanes)
        if absent:
            if plan["full"]:
                raise ValueError(
                    f"Full validation is missing lanes {sorted(absent)}; refusing predecessor reuse"
                )
            predecessor_commit = immediate_first_parent(
                api_url,
                arguments.repository,
                final_commit,
                token,
            )
            if predecessor_commit is None:
                raise ValueError(
                    f"Initial main commit has no predecessor promotion and is missing lanes "
                    f"{sorted(absent)}; bootstrap must validate them"
                )
            lanes.update(wait_for_predecessor_promotion(
                api_url,
                arguments.repository,
                predecessor_commit,
                final_commit,
                plan_path,
                absent,
                token,
                getattr(arguments, "predecessor_timeout_seconds", PREDECESSOR_WAIT_SECONDS),
                getattr(arguments, "predecessor_poll_seconds", PREDECESSOR_POLL_SECONDS),
            ))
        lanes = {lane: lanes[lane] for lane in LANES}
        promotion_plan = validate_promotion_plan({
            "schemaVersion": SCHEMA_VERSION,
            "repository": arguments.repository,
            "finalCommit": final_commit,
            "finalTree": final_tree,
            "validatedCommit": validated_commit,
            "validatedTree": final_tree,
            "validationRunId": run_id,
            "validationRunAttempt": run_attempt,
            "sourcePlanArtifactName": plan_name,
            "sourceAggregateArtifactName": aggregate_name,
            "promotedAggregateArtifactName": f"codex-agent-promoted-validation-{final_commit}",
            "promotedInventoryArtifactName": f"codex-agent-promoted-inventories-{final_commit}",
            "lanes": lanes,
        })
        output = arguments.output.resolve()
        if output.exists() and any(output.iterdir()):
            raise ValueError(f"Promotion output must be empty: {output}")
        output.mkdir(parents=True, exist_ok=True)
        staged_plan = output / "plan"
        staged_plan.mkdir()
        shutil.copyfile(plan_path, staged_plan / "impact-plan.json")
        shutil.copytree(plan_path.parent / "inventories", staged_plan / "inventories")
        source = output / "source"
        source.mkdir()
        shutil.copyfile(plan_path, source / "impact-plan.json")
        shutil.copyfile(aggregate_path, source / "validation-receipt.json")
        write_json(output / "promotion-plan.json", promotion_plan)

    matrix = {
        "include": [
            {
                "lane": lane,
                "sourceRunId": value["sourceRunId"],
                "sourceArtifactName": value["sourceArtifactName"],
                "promotedArtifactName": value["promotedArtifactName"],
            }
            for lane, value in lanes.items()
        ]
    }
    result = {
        "already_promoted": False,
        "has_lanes": bool(lanes),
        "matrix": json.dumps(matrix, separators=(",", ":")),
        "validation_run_id": run_id,
        "promoted_aggregate": promotion_plan["promotedAggregateArtifactName"],
    }
    github_output(arguments.github_output, result)
    return result


def validate_lane(arguments: argparse.Namespace) -> dict[str, object]:
    promotion = validate_promotion_plan(read_json(arguments.promotion_plan))
    plan = read_json(arguments.plan)
    aggregate = validate_aggregate(arguments.aggregate, plan)
    if (
        plan.get("repository") != promotion["repository"]
        or plan.get("validationCommit") != promotion["validatedCommit"]
        or plan.get("validationTree") != promotion["validatedTree"]
    ):
        raise ValueError("Lane plan does not match the promotion identity")
    expected = promotion["lanes"].get(arguments.lane)
    if not isinstance(expected, dict):
        raise ValueError(f"Lane {arguments.lane} is not part of this promotion")
    receipt_path = arguments.root / "lane-receipt.json"
    if expected["sourceKind"] == "validation":
        summary = aggregate["lanes"].get(arguments.lane)
        if not isinstance(summary, dict):
            raise ValueError(f"Lane {arguments.lane} is missing from the validation aggregate")
        receipt = validate_receipt(
            receipt_path,
            arguments.plan,
            arguments.root,
            arguments.lane,
            allow_compatible=True,
        )
        actual_summary = {key: receipt[key] for key in AGGREGATE_LANE_KEYS - {"result"}}
        actual_summary["result"] = "passed"
        if actual_summary != summary or (
            expected["sourceRunId"] != summary["runId"]
            or expected["sourceRunAttempt"] != summary["runAttempt"]
            or expected["sourceArtifactName"] != summary["artifactName"]
        ):
            raise ValueError(f"Lane {arguments.lane} receipt does not match the aggregate receipt")
    else:
        receipt = validate_receipt(
            receipt_path,
            arguments.plan,
            arguments.root,
            arguments.lane,
            allow_compatible=True,
            allow_cross_pr=True,
        )
    if (
        plan.get("androidEvidenceRequired")
        and arguments.lane == "android"
        and not any(item["kind"] == "firebase-runtime-evidence" for item in receipt["evidence"])
    ):
        raise ValueError("Android Firebase runtime evidence is required")
    return receipt


def create_promotion_receipt(arguments: argparse.Namespace) -> dict[str, object]:
    promotion = validate_promotion_plan(read_json(arguments.promotion_plan))
    value = {
        **promotion,
        "workflowPath": PROMOTION_WORKFLOW,
        "promotionRunId": arguments.run_id,
        "promotionRunAttempt": arguments.run_attempt,
        "result": "passed",
    }
    positive_int(arguments.run_id, "promotion run ID")
    positive_int(arguments.run_attempt, "promotion run attempt")
    write_json(arguments.output, value)
    return value


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)
    find = commands.add_parser("discover")
    find.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    find.add_argument(
        "--repository",
        default=os.environ.get("GITHUB_REPOSITORY", ""),
        required=not bool(os.environ.get("GITHUB_REPOSITORY")),
    )
    find.add_argument(
        "--final-commit",
        default=os.environ.get("GITHUB_SHA", ""),
        required=not bool(os.environ.get("GITHUB_SHA")),
    )
    find.add_argument("--output", type=Path, required=True)
    find.add_argument("--token")
    find.add_argument("--api-url", default=os.environ.get("GITHUB_API_URL", "https://api.github.com"))
    find.add_argument("--predecessor-timeout-seconds", type=int, default=PREDECESSOR_WAIT_SECONDS)
    find.add_argument("--predecessor-poll-seconds", type=int, default=PREDECESSOR_POLL_SECONDS)
    find.add_argument("--github-output", type=Path)
    lane = commands.add_parser("validate-lane")
    lane.add_argument("--promotion-plan", type=Path, required=True)
    lane.add_argument("--plan", type=Path, required=True)
    lane.add_argument("--aggregate", type=Path, required=True)
    lane.add_argument("--root", type=Path, required=True)
    lane.add_argument("--lane", choices=LANES, required=True)
    receipt = commands.add_parser("receipt")
    receipt.add_argument("--promotion-plan", type=Path, required=True)
    receipt.add_argument("--output", type=Path, required=True)
    receipt.add_argument("--run-id", type=int, default=int(os.environ.get("GITHUB_RUN_ID", "0")))
    receipt.add_argument("--run-attempt", type=int, default=int(os.environ.get("GITHUB_RUN_ATTEMPT", "0")))
    return result


def main() -> None:
    arguments = parser().parse_args()
    if arguments.command == "discover":
        value = discover(arguments)
    elif arguments.command == "validate-lane":
        value = validate_lane(arguments)
    else:
        value = create_promotion_receipt(arguments)
    print(json.dumps(value, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
