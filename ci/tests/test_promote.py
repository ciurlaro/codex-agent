from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from argparse import Namespace
from pathlib import Path
from unittest.mock import patch


CI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CI_ROOT))

from impact import LANES  # noqa: E402
from promote import (  # noqa: E402
    already_promoted,
    create_promotion_receipt,
    discover,
    predecessor_promotion_sources,
    selected_validation_run,
    validate_lane,
    wait_for_predecessor_promotion,
)
from receipt import INPUT_NAMES, aggregate, create_receipt, safe_extract  # noqa: E402


REPOSITORY = "codex-agent-labs/codex-agent"
API_URL = "https://api.github.invalid"


class PromotionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.git("init", "-q")
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "user.name", "CI Fixture")
        (self.root / "product.txt").write_text("validated\n", encoding="utf-8")
        self.git("add", "product.txt")
        self.git("commit", "-qm", "merge-group")
        self.validated_commit = self.git("rev-parse", "HEAD")
        self.final_tree = self.git("rev-parse", "HEAD^{tree}")
        self.git("commit", "--allow-empty", "-qm", "main rewrite")
        self.final_commit = self.git("rev-parse", "HEAD")
        self.assertNotEqual(self.validated_commit, self.final_commit)
        self.assertEqual(self.final_tree, self.git("rev-parse", "HEAD^{tree}"))

        self.plan_root = self.root / "source-plan"
        self.plan_path = self.plan_root / "impact-plan.json"
        lanes = {
            lane: {
                "build": True,
                "test": False,
                "metadata": False,
                "reuseAllowed": False,
                "reasons": ["bootstrap"],
            }
            for lane in LANES
        }
        self.plan = {
            "schemaVersion": 1,
            "event": "merge_group",
            "repository": REPOSITORY,
            "pullRequest": 7,
            "baseCommit": self.validated_commit,
            "headCommit": self.validated_commit,
            "validationCommit": self.validated_commit,
            "validationTree": self.final_tree,
            "mergeReady": True,
            "androidEvidenceRequired": False,
            "full": True,
            "unknownPaths": [],
            "changedPaths": ["product.txt"],
            "lanes": lanes,
        }
        self.write_json(self.plan_path, self.plan)
        for lane in LANES:
            for filename in INPUT_NAMES.values():
                path = self.plan_root / "inventories" / lane / filename
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"{lane}:{filename}\n", encoding="utf-8")

        self.lanes_root = self.root / "lanes"
        for lane in LANES:
            lane_root = self.lanes_root / lane
            create_receipt(Namespace(
                plan=self.plan_path,
                lane=lane,
                output=lane_root,
                workflow_path=".github/workflows/ci.yml",
                artifact_name=f"codex-agent-ci-{lane}-{self.final_tree}",
                run_id=41,
                run_attempt=2,
                runner=["os=Linux", "arch=X64"],
                toolchain=["java=25", "validationActions=build"],
                artifact=[],
                evidence=[],
            ))
        self.lane_root = self.lanes_root / "android"
        self.aggregate_path = self.root / "validation-receipt.json"
        aggregate(Namespace(
            plan=self.plan_path,
            receipts=self.lanes_root,
            output=self.aggregate_path,
        ))
        self.plan_zip = self.zip_tree(self.plan_root, self.root / "plan.zip")
        self.aggregate_zip = self.root / "aggregate.zip"
        with zipfile.ZipFile(self.aggregate_zip, "w") as archive:
            archive.write(self.plan_path, "impact-plan.json")
            archive.write(self.aggregate_path, "validation-receipt.json")
        self.lane_zip = self.zip_tree(self.lane_root, self.root / "lane.zip")
        self.run = {
            "id": 71,
            "run_attempt": 3,
            "event": "merge_group",
            "conclusion": "success",
            "path": ".github/workflows/ci.yml",
            "head_sha": self.validated_commit,
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def git(self, *arguments: str) -> str:
        return subprocess.run(
            ["git", *arguments],
            cwd=self.root,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    @staticmethod
    def write_json(path: Path, value: dict[str, object]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    @staticmethod
    def zip_tree(root: Path, destination: Path) -> Path:
        with zipfile.ZipFile(destination, "w") as archive:
            for path in root.rglob("*"):
                if path.is_file():
                    archive.write(path, path.relative_to(root))
        return destination

    @staticmethod
    def digest(path: Path) -> str:
        return f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"

    def source_artifacts(self) -> dict[str, dict[str, object]]:
        result = {
            f"codex-agent-ci-plan-{self.final_tree}": {
                "id": 1,
                "name": f"codex-agent-ci-plan-{self.final_tree}",
                "archive_download_url": "https://example.invalid/plan.zip",
                "digest": self.digest(self.plan_zip),
            },
            f"codex-agent-ci-validation-{self.final_tree}": {
                "id": 2,
                "name": f"codex-agent-ci-validation-{self.final_tree}",
                "archive_download_url": "https://example.invalid/aggregate.zip",
                "digest": self.digest(self.aggregate_zip),
            },
        }
        result.update({
            f"codex-agent-ci-{lane}-{self.final_tree}": {
                "id": index + 3,
                "name": f"codex-agent-ci-{lane}-{self.final_tree}",
                "archive_download_url": "https://example.invalid/lane.zip",
                "digest": self.digest(self.lane_zip),
            }
            for index, lane in enumerate(LANES)
        })
        return result

    def download(self, url: str, _token: str) -> bytes:
        values = {
            "https://example.invalid/plan.zip": self.plan_zip.read_bytes(),
            "https://example.invalid/aggregate.zip": self.aggregate_zip.read_bytes(),
            "https://example.invalid/lane.zip": self.lane_zip.read_bytes(),
        }
        return values[url]

    def discover(self, output: Path) -> dict[str, object]:
        def runs(_api: str, _repository: str, workflow: str, _event: str, _token: str):
            return [] if workflow == "promote.yml" else [self.run]

        with (
            patch("promote.workflow_runs", side_effect=runs),
            patch("promote.api_json", return_value={"tree": {"sha": self.final_tree}}),
            patch("promote.artifacts_for_run", return_value=self.source_artifacts()),
            patch("reuse.api_request", side_effect=self.download),
        ):
            return discover(Namespace(
                token="token",
                repo=self.root,
                final_commit=self.final_commit,
                api_url=API_URL,
                repository=REPOSITORY,
                output=output,
                github_output=None,
            ))

    def test_equal_tree_promotes_without_rebuilding_and_validates_lane(self) -> None:
        output = self.root / "promotion"
        result = self.discover(output)
        self.assertFalse(result["already_promoted"])
        self.assertEqual(71, result["validation_run_id"])
        matrix = json.loads(result["matrix"])
        self.assertEqual(list(LANES), [item["lane"] for item in matrix["include"]])

        downloaded = self.root / "downloaded-lane"
        safe_extract(self.lane_zip, downloaded)
        receipt = validate_lane(Namespace(
            promotion_plan=output / "promotion-plan.json",
            plan=output / "plan/impact-plan.json",
            aggregate=output / "source/validation-receipt.json",
            root=downloaded,
            lane="android",
        ))
        self.assertEqual(41, receipt["runId"])

        promotion_receipt = create_promotion_receipt(Namespace(
            promotion_plan=output / "promotion-plan.json",
            output=output / "source/promotion-receipt.json",
            run_id=81,
            run_attempt=4,
        ))
        self.assertEqual(self.final_commit, promotion_receipt["finalCommit"])
        self.assertEqual(self.final_tree, promotion_receipt["validatedTree"])
        self.assertEqual(71, promotion_receipt["validationRunId"])
        self.assertEqual(41, promotion_receipt["lanes"]["android"]["sourceRunId"])

    def test_full_initial_promotion_does_not_wait_for_a_predecessor(self) -> None:
        with (
            patch("promote.immediate_first_parent") as first_parent,
            patch("promote.wait_for_predecessor_promotion") as wait,
        ):
            self.discover(self.root / "full-initial")
        first_parent.assert_not_called()
        wait.assert_not_called()

    def test_incremental_discovery_carries_only_from_its_immediate_first_parent(self) -> None:
        self.plan["full"] = False
        for lane, state in self.plan["lanes"].items():
            selected = lane == "android"
            state.update(build=selected, test=False, metadata=False)
        self.write_json(self.plan_path, self.plan)
        selected_receipts = self.root / "selected-lanes"
        shutil.copytree(self.lanes_root / "android", selected_receipts / "android")
        aggregate(Namespace(
            plan=self.plan_path,
            receipts=selected_receipts,
            output=self.aggregate_path,
        ))
        self.zip_tree(self.plan_root, self.plan_zip)
        with zipfile.ZipFile(self.aggregate_zip, "w") as archive:
            archive.write(self.plan_path, "impact-plan.json")
            archive.write(self.aggregate_path, "validation-receipt.json")

        predecessor = "b" * 40
        absent = set(LANES) - {"android"}
        carried = {
            lane: {
                "sourceKind": "promotion",
                "sourceRunId": 81,
                "sourceRunAttempt": 1,
                "sourceArtifactName": f"codex-agent-promoted-{lane}-{predecessor}",
                "sourcePromotionRunId": 81,
                "sourcePromotionCommit": predecessor,
                "promotedArtifactName": f"codex-agent-promoted-{lane}-{self.final_commit}",
            }
            for lane in absent
        }
        with (
            patch("promote.immediate_first_parent", return_value=predecessor) as first_parent,
            patch("promote.wait_for_predecessor_promotion", return_value=carried) as wait,
        ):
            self.discover(self.root / "incremental")
        first_parent.assert_called_once_with(API_URL, REPOSITORY, self.final_commit, "token")
        self.assertEqual(predecessor, wait.call_args.args[2])
        self.assertEqual(self.final_commit, wait.call_args.args[3])
        self.assertEqual(absent, wait.call_args.args[5])
        promotion = json.loads(
            (self.root / "incremental/promotion-plan.json").read_text(encoding="utf-8")
        )
        self.assertEqual(predecessor, promotion["lanes"]["portable"]["sourcePromotionCommit"])

    def test_pending_predecessor_does_not_fall_back_to_an_older_promotion(self) -> None:
        older_run = {
            "id": 80,
            "run_attempt": 1,
            "event": "push",
            "conclusion": "success",
            "path": ".github/workflows/promote.yml",
            "head_branch": "main",
            "head_sha": "a" * 40,
        }
        with (
            patch("promote.workflow_runs", return_value=[older_run]),
            patch("promote.artifacts_for_run") as artifacts,
        ):
            result = predecessor_promotion_sources(
                API_URL,
                REPOSITORY,
                "b" * 40,
                "c" * 40,
                self.plan_path,
                {"android"},
                "token",
            )
        self.assertIsNone(result)
        artifacts.assert_not_called()

    def test_delayed_predecessor_success_is_retried_before_the_next_promotion(self) -> None:
        carried = {"android": {"sourceKind": "promotion"}}
        with (
            patch("promote.predecessor_promotion_sources", side_effect=[None, carried]) as source,
            patch("promote.time.monotonic", side_effect=[100.0, 100.0]),
            patch("promote.time.sleep") as sleep,
        ):
            result = wait_for_predecessor_promotion(
                API_URL,
                REPOSITORY,
                "b" * 40,
                "c" * 40,
                self.plan_path,
                {"android"},
                "token",
                timeout_seconds=30,
                poll_seconds=5,
            )
        self.assertEqual(carried, result)
        self.assertEqual(2, source.call_count)
        sleep.assert_called_once_with(5)

    def test_predecessor_wait_times_out_precisely(self) -> None:
        with (
            patch("promote.predecessor_promotion_sources", return_value=None),
            patch("promote.time.monotonic", side_effect=[100.0, 110.0]),
            patch("promote.time.sleep") as sleep,
            self.assertRaisesRegex(
                TimeoutError,
                "Timed out after 10 seconds.*immediate first parent b{40}.*android",
            ),
        ):
            wait_for_predecessor_promotion(
                API_URL,
                REPOSITORY,
                "b" * 40,
                "c" * 40,
                self.plan_path,
                {"android"},
                "token",
                timeout_seconds=10,
                poll_seconds=5,
            )
        sleep.assert_not_called()

    def test_tree_mismatch_and_missing_lane_artifact_fail_closed(self) -> None:
        with (
            patch("promote.workflow_runs", return_value=[self.run]),
            patch("promote.api_json", return_value={"tree": {"sha": "0" * 40}}),
            self.assertRaisesRegex(ValueError, "No successful merge-group validation"),
        ):
            selected_validation_run(API_URL, REPOSITORY, self.final_tree, "token")

        artifacts = self.source_artifacts()
        del artifacts[f"codex-agent-ci-android-{self.final_tree}"]
        with (
            patch("promote.already_promoted", return_value=False),
            patch("promote.selected_validation_run", return_value=self.run),
            patch("promote.artifacts_for_run", return_value=artifacts),
            patch("reuse.api_request", side_effect=self.download),
            self.assertRaisesRegex(ValueError, "source artifacts are missing"),
        ):
            discover(Namespace(
                token="token",
                repo=self.root,
                final_commit=self.final_commit,
                api_url=API_URL,
                repository=REPOSITORY,
                output=self.root / "missing",
                github_output=None,
            ))

    def test_complete_prior_promotion_is_idempotent(self) -> None:
        output = self.root / "promotion"
        self.discover(output)
        prior_run = {
            "id": 81,
            "run_attempt": 4,
            "event": "push",
            "conclusion": "success",
            "path": ".github/workflows/promote.yml",
            "head_branch": "main",
            "head_sha": self.final_commit,
        }
        create_promotion_receipt(Namespace(
            promotion_plan=output / "promotion-plan.json",
            output=output / "source/promotion-receipt.json",
            run_id=81,
            run_attempt=4,
        ))
        promoted_zip = self.zip_tree(output / "source", self.root / "promoted.zip")
        inventory_zip = self.zip_tree(output / "plan", self.root / "promoted-inventories.zip")
        with zipfile.ZipFile(promoted_zip) as archive:
            self.assertEqual(
                {"impact-plan.json", "validation-receipt.json", "promotion-receipt.json"},
                set(archive.namelist()),
            )
        promoted_aggregate = f"codex-agent-promoted-validation-{self.final_commit}"
        promoted_inventories = f"codex-agent-promoted-inventories-{self.final_commit}"
        artifacts = {
            promoted_aggregate: {
                "id": 11,
                "name": promoted_aggregate,
                "archive_download_url": "https://example.invalid/promoted.zip",
                "digest": self.digest(promoted_zip),
            },
            promoted_inventories: {
                "id": 12,
                "name": promoted_inventories,
                "archive_download_url": "https://example.invalid/promoted-inventories.zip",
                "digest": self.digest(inventory_zip),
            },
        }
        artifacts.update({
            f"codex-agent-promoted-{lane}-{self.final_commit}": {
                "id": index + 13,
                "name": f"codex-agent-promoted-{lane}-{self.final_commit}",
                "archive_download_url": "https://example.invalid/promoted-lane.zip",
            }
            for index, lane in enumerate(LANES)
        })
        with (
            patch("promote.workflow_runs", return_value=[prior_run]),
            patch("promote.artifacts_for_run", return_value=artifacts),
            patch(
                "reuse.api_request",
                side_effect=lambda url, _token: (
                    inventory_zip.read_bytes()
                    if url.endswith("promoted-inventories.zip")
                    else promoted_zip.read_bytes()
                ),
            ),
        ):
            self.assertTrue(already_promoted(
                API_URL,
                REPOSITORY,
                self.final_commit,
                self.final_tree,
                "token",
            ))

    def test_missing_lanes_carry_only_from_inventory_compatible_promotion(self) -> None:
        output = self.root / "promotion"
        self.discover(output)
        prior_run = {
            "id": 81,
            "run_attempt": 4,
            "event": "push",
            "conclusion": "success",
            "path": ".github/workflows/promote.yml",
            "head_branch": "main",
            "head_sha": self.final_commit,
        }
        create_promotion_receipt(Namespace(
            promotion_plan=output / "promotion-plan.json",
            output=output / "source/promotion-receipt.json",
            run_id=81,
            run_attempt=4,
        ))
        promoted_zip = self.zip_tree(output / "source", self.root / "prior.zip")
        inventory_zip = self.zip_tree(output / "plan", self.root / "prior-inventories.zip")
        artifacts = {
            f"codex-agent-promoted-validation-{self.final_commit}": {
                "id": 11,
                "name": f"codex-agent-promoted-validation-{self.final_commit}",
                "archive_download_url": "https://example.invalid/prior.zip",
                "digest": self.digest(promoted_zip),
            },
            f"codex-agent-promoted-inventories-{self.final_commit}": {
                "id": 12,
                "name": f"codex-agent-promoted-inventories-{self.final_commit}",
                "archive_download_url": "https://example.invalid/prior-inventories.zip",
                "digest": self.digest(inventory_zip),
            },
        }
        artifacts.update({
            f"codex-agent-promoted-{lane}-{self.final_commit}": {
                "id": index + 13,
                "name": f"codex-agent-promoted-{lane}-{self.final_commit}",
                "archive_download_url": "https://example.invalid/prior-lane.zip",
            }
            for index, lane in enumerate(LANES)
        })
        requested = {"consumer-common", "ios-package"}
        with (
            patch("promote.workflow_runs", return_value=[prior_run]),
            patch("promote.artifacts_for_run", return_value=artifacts),
            patch("promote.api_json", return_value={"tree": {"sha": self.final_tree}}),
            patch(
                "reuse.api_request",
                side_effect=lambda url, _token: (
                    inventory_zip.read_bytes()
                    if url.endswith("prior-inventories.zip")
                    else promoted_zip.read_bytes()
                ),
            ),
        ):
            carried = predecessor_promotion_sources(
                API_URL,
                REPOSITORY,
                self.final_commit,
                "f" * 40,
                output / "plan/impact-plan.json",
                requested,
                "token",
            )
        self.assertEqual(requested, set(carried))
        self.assertTrue(all(item["sourceKind"] == "promotion" for item in carried.values()))
        self.assertTrue(all(item["sourceRunId"] == 81 for item in carried.values()))

        carried_plan_path = output / "carried-plan.json"
        carried_plan = json.loads((output / "promotion-plan.json").read_text(encoding="utf-8"))
        for lane, item in carried.items():
            item["promotedArtifactName"] = f"codex-agent-promoted-{lane}-{self.final_commit}"
        carried_plan["lanes"].update(carried)
        self.write_json(carried_plan_path, carried_plan)
        carried_lane = self.root / "carried-lane"
        shutil.copytree(self.lanes_root / "consumer-common", carried_lane)
        lane_receipt_path = carried_lane / "lane-receipt.json"
        lane_receipt = json.loads(lane_receipt_path.read_text(encoding="utf-8"))
        lane_receipt.update(
            event="pull_request",
            pullRequest=2,
            baseCommit="1" * 40,
            headCommit="2" * 40,
            validationCommit="3" * 40,
            validationTree="4" * 40,
        )
        self.write_json(lane_receipt_path, lane_receipt)
        validated = validate_lane(Namespace(
            promotion_plan=carried_plan_path,
            plan=output / "plan/impact-plan.json",
            aggregate=output / "source/validation-receipt.json",
            root=carried_lane,
            lane="consumer-common",
        ))
        self.assertEqual(2, validated["pullRequest"])

        (output / "plan/inventories/consumer-common/production-inputs.git-tree").write_text(
            "different final-tree inventory\n",
            encoding="utf-8",
        )
        with (
            patch("promote.workflow_runs", return_value=[prior_run]),
            patch("promote.artifacts_for_run", return_value=artifacts),
            patch("promote.api_json", return_value={"tree": {"sha": self.final_tree}}),
            patch(
                "reuse.api_request",
                side_effect=lambda url, _token: (
                    inventory_zip.read_bytes()
                    if url.endswith("prior-inventories.zip")
                    else promoted_zip.read_bytes()
                ),
            ),
            self.assertRaisesRegex(ValueError, "Immediate first-parent promotion.*incompatible"),
        ):
            predecessor_promotion_sources(
                API_URL,
                REPOSITORY,
                self.final_commit,
                "f" * 40,
                output / "plan/impact-plan.json",
                {"consumer-common"},
                "token",
            )


if __name__ == "__main__":
    unittest.main()
