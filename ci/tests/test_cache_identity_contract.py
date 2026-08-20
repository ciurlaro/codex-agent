from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


CI_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = CI_ROOT.parent
sys.path.insert(0, str(CI_ROOT))

from stage import RUNNER_IDENTITY, TOOLCHAIN_IDENTITY, recorded_identity  # noqa: E402


class CacheIdentityContractTest(unittest.TestCase):
    def test_receipt_identity_has_one_exact_fail_closed_source(self) -> None:
        values = {
            environment: "unavailable" if name in {"node", "rustc", "cargo", "xcode", "swift"} else f"actual-{name}"
            for fields in (RUNNER_IDENTITY, TOOLCHAIN_IDENTITY)
            for name, environment in fields.items()
        }
        with patch.dict(os.environ, values, clear=True):
            self.assertEqual(
                recorded_identity(RUNNER_IDENTITY),
                [f"{name}={values[environment]}" for name, environment in RUNNER_IDENTITY.items()],
            )
            self.assertEqual(
                recorded_identity(TOOLCHAIN_IDENTITY),
                [f"{name}={values[environment]}" for name, environment in TOOLCHAIN_IDENTITY.items()],
            )
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "Missing or malformed CI identity"):
                recorded_identity(RUNNER_IDENTITY)
        malformed = dict(values)
        malformed["CODEX_CI_RUNNER_IMAGE_VERSION"] = "bad\nversion"
        with patch.dict(os.environ, malformed, clear=True):
            with self.assertRaisesRegex(ValueError, "CODEX_CI_RUNNER_IMAGE_VERSION"):
                recorded_identity(RUNNER_IDENTITY)

    def test_only_one_authoritative_pr_merge_ref_lane_can_write(self) -> None:
        lane = (REPOSITORY / ".github/actions/run-ci-lane/action.yml").read_text(encoding="utf-8")
        policy = (REPOSITORY / "ci/cache_seed.py").read_text(encoding="utf-8")
        kmp = (REPOSITORY / ".github/actions/setup-kmp/action.yml").read_text(encoding="utf-8")
        sccache = (REPOSITORY / ".github/actions/setup-sccache/action.yml").read_text(encoding="utf-8")
        self.assertIn("python3 ci/cache_seed.py policy", lane)
        self.assertIn('os.environ.get("GITHUB_REF") == f"refs/pull/{pull}/merge"', policy)
        self.assertIn('os.environ.get("GITHUB_SHA") == arguments.validation_commit', policy)
        self.assertIn("arguments.lane == writers[0]", policy)
        self.assertIn("arguments.lane == rust_writers[0]", policy)
        self.assertIn("write: ${{ steps.cache-policy.outputs.rust-write }}", lane)
        for action in (kmp, sccache):
            self.assertIn('[ "$GITHUB_EVENT_NAME" = pull_request ]', action)
            self.assertIn('[ "$GITHUB_REF" = "refs/pull/$PR_NUMBER/merge" ]', action)

    def test_caches_are_narrow_and_toolchains_are_observed(self) -> None:
        lane = (REPOSITORY / ".github/actions/run-ci-lane/action.yml").read_text(encoding="utf-8")
        kmp = (REPOSITORY / ".github/actions/setup-kmp/action.yml").read_text(encoding="utf-8")
        sccache = (REPOSITORY / ".github/actions/setup-sccache/action.yml").read_text(encoding="utf-8")
        self.assertIn("~/.konan", kmp)
        for path in ("~/.cargo/registry/index", "~/.cargo/registry/cache", "~/.cargo/git/db"):
            self.assertIn(path, sccache)
        for forbidden in ("**/build", "DerivedData", "~/.cargo/target"):
            self.assertNotIn(forbidden, kmp + sccache)
        for command in (
            "./gradlew --version", "java -XshowSettings:properties -version", "node --version",
            "rustc -vV", "cargo --version", "xcodebuild -version", "xcrun swift --version",
        ):
            self.assertIn(command, lane)
        for requested in ("--toolchain gradle=9.4.1", "--toolchain kotlin=2.3.10", "--toolchain java=17"):
            self.assertNotIn(requested, lane)

    def test_failed_validation_preserves_only_successful_production_then_fails(self) -> None:
        lane = (REPOSITORY / ".github/actions/run-ci-lane/action.yml").read_text(encoding="utf-8")
        stage = (REPOSITORY / "ci/stage.py").read_text(encoding="utf-8")
        workflow = (REPOSITORY / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("id: production-execution", lane)
        self.assertIn("id: execution", lane)
        self.assertIn("steps.production-execution.outcome == 'success'", lane)
        self.assertIn("steps.execution.outcome == 'failure'", lane)
        self.assertIn("--production-only", lane)
        self.assertIn("VALIDATION_ACTIONS_KEY", stage)
        self.assertIn("codex-agent-ci-${{ inputs.lane }}-production-", lane)
        self.assertIn("Fail after preserving reusable production and diagnostics", lane)
        self.assertIn("runs-on: ${{ matrix.runner }}", workflow)

    def test_ios_checksum_repair_is_reported_after_preserving_production(self) -> None:
        lane = (REPOSITORY / ".github/actions/run-ci-lane/action.yml").read_text(encoding="utf-8")
        stage = lane.index("Stage reusable production after a later validation failure")
        upload = lane.index("Upload reusable production after a later validation failure")
        repair = lane.index("Report the exact SwiftPM checksum repair after preservation")
        fail = lane.index("Fail after preserving reusable production and diagnostics")
        self.assertLess(stage, upload)
        self.assertLess(upload, repair)
        self.assertLess(repair, fail)
        repair_step = lane[repair:fail]
        self.assertIn("inputs.lane == 'ios-package'", repair_step)
        self.assertIn("steps.production-execution.outcome == 'success'", repair_step)
        self.assertIn("steps.execution.outcome == 'failure'", repair_step)
        self.assertIn("build/ci/production-lane/payload/", repair_step)
        self.assertIn("swift package compute-checksum", repair_step)
        self.assertIn('test "$checksum" = "$sha256"', repair_step)
        self.assertIn(
            "Apply: \\`perl -0pi -e 's/(?<=checksum: \\\")[0-9a-f]{64}(?=\\\")/$checksum/' Package.swift\\`",
            repair_step,
        )
        self.assertEqual(1, repair_step.count('>> "$GITHUB_STEP_SUMMARY"'))


if __name__ == "__main__":
    unittest.main()
