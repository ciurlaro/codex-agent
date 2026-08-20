#!/usr/bin/env python3
"""Restore the newest compatible successful lane artifact from this PR."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from receipt import INPUT_NAMES, parse_mapping, read_json, safe_extract, validate_receipt


def _origin(url: str) -> tuple[str, str | None, int | None]:
    parsed = urllib.parse.urlsplit(url)
    port = parsed.port
    if port is None:
        port = {"http": 80, "https": 443}.get(parsed.scheme.lower())
    return parsed.scheme.lower(), parsed.hostname, port


class OriginBoundRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        redirected = super().redirect_request(request, fp, code, message, headers, new_url)
        if redirected is not None and _origin(request.full_url) != _origin(new_url):
            redirected.remove_header("Authorization")
        return redirected


def api_request(url: str, token: str) -> bytes:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    opener = urllib.request.build_opener(OriginBoundRedirectHandler())
    with opener.open(request, timeout=60) as response:
        return response.read()


def api_json(url: str, token: str) -> dict[str, object]:
    value = json.loads(api_request(url, token))
    if not isinstance(value, dict):
        raise ValueError(f"GitHub returned a non-object response for {url}")
    return value


def paginated_items(url: str, key: str, token: str) -> list[object]:
    result: list[object] = []
    separator = "&" if "?" in url else "?"
    page = 1
    while True:
        value = api_json(f"{url}{separator}per_page=100&page={page}", token).get(key)
        if not isinstance(value, list):
            raise ValueError(f"GitHub response is missing {key}")
        result.extend(value)
        if len(value) < 100:
            return result
        page += 1


def download_artifact(artifact: dict[str, object], token: str) -> bytes:
    url = artifact.get("archive_download_url")
    digest = artifact.get("digest")
    if not isinstance(url, str) or not isinstance(digest, str):
        raise ValueError("GitHub artifact transport identity is missing")
    algorithm, separator, expected = digest.partition(":")
    if algorithm != "sha256" or not separator or len(expected) != 64 or any(
        character not in "0123456789abcdef" for character in expected
    ):
        raise ValueError("GitHub artifact transport digest is malformed")
    archive = api_request(url, token)
    if hashlib.sha256(archive).hexdigest() != expected:
        raise ValueError("GitHub artifact transport digest mismatch")
    return archive


def github_output(path: Path | None, values: dict[str, object]) -> None:
    if path is None:
        return
    with path.open("a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")


def run_matches_pr(run: dict[str, object], pull_request: int) -> bool:
    requests = run.get("pull_requests")
    return isinstance(requests, list) and any(
        isinstance(item, dict) and item.get("number") == pull_request
        for item in requests
    )


def candidate_artifacts(
    api_url: str,
    repository: str,
    workflow: str,
    lane: str,
    pull_request: int,
    token: str,
    current_run: int | None,
) -> list[dict[str, object]]:
    workflow_id = urllib.parse.quote(workflow, safe="")
    query = urllib.parse.urlencode({"event": "pull_request", "status": "completed"})
    runs_url = f"{api_url}/repos/{repository}/actions/workflows/{workflow_id}/runs?{query}"
    runs = paginated_items(runs_url, "workflow_runs", token)
    result: list[dict[str, object]] = []
    prefix = f"codex-agent-ci-{lane}-"
    for run in runs:
        if (
            not isinstance(run, dict)
            or run.get("id") == current_run
            or not run_matches_pr(run, pull_request)
        ):
            continue
        run_id = run.get("id")
        artifacts_url = f"{api_url}/repos/{repository}/actions/runs/{run_id}/artifacts"
        artifacts = paginated_items(artifacts_url, "artifacts", token)
        for artifact in artifacts:
            if (
                isinstance(artifact, dict)
                and not artifact.get("expired", True)
                and isinstance(artifact.get("name"), str)
                and artifact["name"].startswith(prefix)
                and isinstance(artifact.get("archive_download_url"), str)
            ):
                result.append(artifact)
    return result


def promoted_artifacts(
    api_url: str,
    repository: str,
    lane: str,
    token: str,
) -> list[dict[str, object]]:
    workflow_id = urllib.parse.quote("promote.yml", safe="")
    query = urllib.parse.urlencode({"event": "push", "status": "completed"})
    try:
        runs = paginated_items(
            f"{api_url}/repos/{repository}/actions/workflows/{workflow_id}/runs?{query}",
            "workflow_runs",
            token,
        )
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return []
        raise
    result: list[dict[str, object]] = []
    prefix = f"codex-agent-promoted-{lane}-"
    for run in runs:
        if not isinstance(run, dict) or run.get("conclusion") != "success":
            continue
        artifacts = paginated_items(
            f"{api_url}/repos/{repository}/actions/runs/{run.get('id')}/artifacts",
            "artifacts",
            token,
        )
        for artifact in artifacts:
            if (
                isinstance(artifact, dict)
                and not artifact.get("expired", True)
                and isinstance(artifact.get("name"), str)
                and artifact["name"].startswith(prefix)
                and isinstance(artifact.get("archive_download_url"), str)
            ):
                result.append({**artifact, "_promoted": True})
    return result


def reissue_transport_receipt(
    root: Path,
    receipt: dict[str, object],
    plan: dict[str, object],
    lane: str,
    source_transport: str,
) -> dict[str, object]:
    provenance_path = root / "transport-provenance.json"
    previous = json.loads(provenance_path.read_text(encoding="utf-8")) if provenance_path.is_file() else None
    provenance = {
        "schemaVersion": 1,
        "source": {
            key: receipt[key]
            for key in (
                "event", "runId", "runAttempt", "pullRequest", "validationCommit",
                "validationTree", "artifactName",
            )
        },
        "sourceTransportArtifactName": source_transport,
        "previous": previous,
    }
    provenance_path.write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    provenance_digest = hashlib.sha256(provenance_path.read_bytes()).hexdigest()
    existing = next(
        (item for item in receipt["evidence"] if item["relativePath"] == provenance_path.name),
        None,
    )
    if existing is None:
        receipt["evidence"].append({
            "relativePath": provenance_path.name,
            "kind": "transport-provenance",
            "sha256": provenance_digest,
        })
        receipt["evidence"] = sorted(receipt["evidence"], key=lambda item: item["relativePath"])
    else:
        existing["sha256"] = provenance_digest
    for field in ("event", "pullRequest", "baseCommit", "headCommit", "validationCommit", "validationTree"):
        receipt[field] = plan[field]
    receipt["runId"] = int(os.environ.get("GITHUB_RUN_ID", receipt["runId"]))
    receipt["runAttempt"] = int(os.environ.get("GITHUB_RUN_ATTEMPT", receipt["runAttempt"]))
    receipt["artifactName"] = f"codex-agent-ci-{lane}-{plan['validationTree']}"
    (root / "lane-receipt.json").write_text(
        json.dumps(receipt, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return receipt


def restore(arguments: argparse.Namespace) -> dict[str, object]:
    plan_path = arguments.plan.resolve()
    plan = read_json(plan_path)
    lane_state = plan.get("lanes", {}).get(arguments.lane)
    if not isinstance(lane_state, dict):
        raise ValueError(f"Impact plan does not contain lane {arguments.lane}")
    result: dict[str, object] = {
        "reused": False,
        "mode": arguments.mode,
        "source_run_id": "",
        "source_run_attempt": "",
        "artifact_name": "",
    }
    if not lane_state.get("reuseAllowed", False):
        result["reason"] = "plan-forced-execution"
        return result
    pull_request = plan.get("pullRequest")
    if not isinstance(pull_request, int):
        result["reason"] = "same-pr-scope-unavailable"
        return result
    token = arguments.token or os.environ.get("GITHUB_TOKEN", "")
    if not token:
        result["reason"] = "github-token-unavailable"
        return result
    repository = str(plan["repository"])
    runner = parse_mapping(arguments.runner) or None
    toolchain = parse_mapping(arguments.toolchain) or None
    categories = tuple(INPUT_NAMES) if arguments.mode == "full" else ("production",)
    current_run = int(os.environ["GITHUB_RUN_ID"]) if os.environ.get("GITHUB_RUN_ID") else None
    try:
        candidates = candidate_artifacts(
            arguments.api_url,
            repository,
            arguments.workflow,
            arguments.lane,
            pull_request,
            token,
            current_run,
        )
        candidates.extend(promoted_artifacts(arguments.api_url, repository, arguments.lane, token))
        if arguments.mode == "full":
            partial = f"codex-agent-ci-{arguments.lane}-production-"
            candidates = [artifact for artifact in candidates if not str(artifact.get("name", "")).startswith(partial)]
        for artifact in candidates:
            with tempfile.TemporaryDirectory() as temporary:
                temporary_root = Path(temporary)
                archive = temporary_root / "artifact.zip"
                archive.write_bytes(download_artifact(artifact, token))
                extracted = temporary_root / "extracted"
                try:
                    safe_extract(archive, extracted)
                    receipts = list(extracted.rglob("lane-receipt.json"))
                    if len(receipts) != 1:
                        continue
                    receipt = validate_receipt(
                        receipts[0],
                        plan_path,
                        receipts[0].parent,
                        arguments.lane,
                        allow_compatible=True,
                        allow_cross_pr=bool(artifact.get("_promoted")),
                        runner=runner,
                        toolchain=toolchain,
                        categories=categories,
                    )
                    if not artifact.get("_promoted") and receipt["artifactName"] != artifact["name"]:
                        continue
                except (OSError, ValueError, json.JSONDecodeError, KeyError):
                    continue
                destination = arguments.destination.resolve()
                if destination.exists():
                    if any(destination.iterdir()):
                        raise ValueError(f"Reuse destination must be empty: {destination}")
                    destination.rmdir()
                shutil.copytree(receipts[0].parent, destination)
                if arguments.mode == "full":
                    receipt = reissue_transport_receipt(
                        destination,
                        receipt,
                        plan,
                        arguments.lane,
                        str(artifact["name"]),
                    )
                    validate_receipt(
                        destination / "lane-receipt.json",
                        plan_path,
                        destination,
                        arguments.lane,
                        runner=runner,
                        toolchain=toolchain,
                    )
                result.update(
                    reused=True,
                    source_run_id=receipt["runId"],
                    source_run_attempt=receipt["runAttempt"],
                    artifact_name=receipt["artifactName"],
                    reason="compatible-receipt",
                )
                return result
        result["reason"] = "no-compatible-artifact"
    except (OSError, ValueError, json.JSONDecodeError) as error:
        result["reason"] = f"discovery-unavailable:{type(error).__name__}"
    return result


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--plan", type=Path, required=True)
    result.add_argument("--lane", required=True)
    result.add_argument("--destination", type=Path, required=True)
    result.add_argument("--mode", choices=("full", "production"), default="full")
    result.add_argument("--workflow", default="ci.yml")
    result.add_argument("--runner", action="append", default=[])
    result.add_argument("--toolchain", action="append", default=[])
    result.add_argument("--token")
    result.add_argument("--api-url", default=os.environ.get("GITHUB_API_URL", "https://api.github.com"))
    result.add_argument("--github-output", type=Path)
    return result


def main() -> None:
    arguments = parser().parse_args()
    value = restore(arguments)
    github_output(arguments.github_output, value)
    print(json.dumps(value, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
