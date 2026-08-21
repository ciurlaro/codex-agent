from __future__ import annotations

import hashlib
import fnmatch
import json
import os
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
import urllib.error
import urllib.request
import zipfile
from argparse import Namespace
from pathlib import Path
from unittest.mock import patch


CI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CI_ROOT))

from impact import LANES, effective_pathspecs, plan, write_github_outputs  # noqa: E402
from evidence import main as evidence_main  # noqa: E402
from receipt import (  # noqa: E402
    aggregate,
    create_receipt,
    safe_extract,
    validate_receipt,
)
from reuse import (  # noqa: E402
    OriginBoundRedirectHandler,
    candidate_artifacts,
    promoted_artifacts,
    restore,
)
from stage import OUTPUTS, archive_tree, restore_production_files, safe_extract_tar  # noqa: E402
from validation_reuse import (  # noqa: E402
    discover as discover_validation,
    materialize,
    validate as validate_aggregate_reuse,
)


class GitFixture(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.git("init", "-q")
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "user.name", "CI Fixture")
        self.write("README.md", "baseline\n")
        self.write("ci/impact.py", "# planner\n")
        for lane in LANES:
            self.write(f"configured/{lane}.txt", "configured\n")
            self.write(f"ci/lanes/{lane}.production.pathspec", f"configured/{lane}.txt\n")
        inventories = {
            "shared.production": "common/**\n",
            "android.production": "android/**\nconfigured/android.txt\n",
            "android.test": "android-tests/**\n",
            "android.metadata": "android-metadata/**\n",
            "node-js.production": "js/**\nconfigured/node-js.txt\n",
            "node-wasm.production": "wasm/**\nconfigured/node-wasm.txt\n",
            "portable.production": (
                "desktop-runtime/**\njs/**\nwasm/**\n"
                "codex-agent-client/src/jvmMain/**\nconfigured/portable.txt\n"
            ),
            "contracts.production": "configured/contracts.txt\n",
            "contracts.metadata": "Package.swift\n",
            "consumer-common.production": (
                "codex-agent-client/src/jvmMain/**\nconfigured/consumer-common.txt\n"
            ),
            "consumer-desktop.production": (
                "codex-agent-client/src/jvmMain/**\nconfigured/consumer-desktop.txt\n"
            ),
            "ios-package.metadata": "Package.swift\n",
            "ios-privacy-metrics.metadata": "privacy-policy/**\n",
            "ios-kotlin-tests.test": "ios-sim-tests/**\n",
            "ios-swift-tests.test": "ios-swift-auth-tests/**\n",
        }
        for name, contents in inventories.items():
            self.write(f"ci/lanes/{name}.pathspec", contents)
        for lane in (name for name in LANES if name.startswith("desktop-")):
            self.write(f"ci/lanes/{lane}.test.pathspec", "desktop-tests/**\njs/**\nwasm/**\n")
        self.git("add", ".")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD")

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

    def write(self, relative: str, contents: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding="utf-8")

    def commit(self, relative: str, contents: str) -> str:
        self.write(relative, contents)
        self.git("add", relative)
        self.git("commit", "-qm", relative)
        return self.git("rev-parse", "HEAD")

    def make_plan(
        self,
        relative: str,
        contents: str = "changed\n",
        *,
        merge_ready: bool = True,
        force_full: bool = False,
        require_android_evidence: bool = False,
        pull_request: int = 7,
        base: str | None = None,
    ) -> tuple[dict[str, object], Path, str]:
        target = self.commit(relative, contents)
        output = self.root / "build/ci/impact-plan.json"
        result = plan(
            root=self.root,
            base=base or self.base,
            target=target,
            head=target,
            event="pull_request",
            pull_request=pull_request,
            merge_ready=merge_ready,
            force_full=force_full,
            require_android_evidence=require_android_evidence,
            repository="codex-agent-labs/codex-agent",
            output=output,
        )
        return result, output, target


class ImpactPlanTest(GitFixture):
    def test_docs_only_runs_no_product_lane(self) -> None:
        result, _, _ = self.make_plan("README.md", "docs\n")
        self.assertEqual([], result["unknownPaths"])
        self.assertFalse(any(
            state[action]
            for state in result["lanes"].values()
            for action in ("build", "test", "metadata")
        ))

    def test_android_change_excludes_unrelated_platforms(self) -> None:
        result, _, _ = self.make_plan("android/Main.kt")
        self.assertTrue(result["lanes"]["android"]["build"])
        self.assertTrue(result["lanes"]["consumer-android"]["build"])
        for lane in ("node-js", "node-wasm", "desktop-macos-arm64", "ios-framework-device"):
            self.assertFalse(result["lanes"][lane]["build"])

    def test_queue_disabled_bootstrap_full_plan_requires_android_evidence(self) -> None:
        result, _, _ = self.make_plan(
            "README.md",
            "bootstrap\n",
            force_full=True,
            require_android_evidence=True,
        )
        self.assertTrue(result["lanes"]["android"]["build"])
        self.assertTrue(result["androidEvidenceRequired"])

    def test_js_and_wasm_are_independent(self) -> None:
        result, plan_path, _ = self.make_plan("js/Main.kt")
        self.assertTrue(result["lanes"]["portable"]["build"])
        self.assertTrue(result["lanes"]["node-js"]["build"])
        self.assertFalse(result["lanes"]["node-wasm"]["build"])
        for lane in (name for name in LANES if name.startswith("desktop-")):
            self.assertFalse(result["lanes"][lane]["build"])
            self.assertTrue(result["lanes"][lane]["test"])
        output = self.root / "selection.out"
        subprocess.run([
            sys.executable, str(CI_ROOT / "lane_selection.py"), "--plan", str(plan_path),
            "--lane", "desktop-linux-x64", "--github-output", str(output),
        ], check=True)
        selection = dict(line.split("=", 1) for line in output.read_text().splitlines())
        self.assertEqual("true", selection["node_js"])
        self.assertEqual("true", selection["node_wasm"])

    def test_wasm_change_keeps_js_product_independent_but_desktop_evidence_complete(self) -> None:
        result, plan_path, _ = self.make_plan("wasm/Main.kt")
        self.assertTrue(result["lanes"]["portable"]["build"])
        self.assertFalse(result["lanes"]["node-js"]["build"])
        self.assertTrue(result["lanes"]["node-wasm"]["build"])
        for lane in (name for name in LANES if name.startswith("desktop-")):
            self.assertFalse(result["lanes"][lane]["build"])
            self.assertTrue(result["lanes"][lane]["test"])
        output = self.root / "selection.out"
        subprocess.run([
            sys.executable, str(CI_ROOT / "lane_selection.py"), "--plan", str(plan_path),
            "--lane", "desktop-linux-x64", "--github-output", str(output),
        ], check=True)
        selection = dict(line.split("=", 1) for line in output.read_text().splitlines())
        self.assertEqual("true", selection["node_js"])
        self.assertEqual("true", selection["node_wasm"])

    def test_real_runner_pathspecs_keep_js_and_wasm_product_inputs_independent(self) -> None:
        root = CI_ROOT.parent
        js = "codex-agent-runtime-node/src/jsMain/kotlin/OnlyJs.kt"
        wasm = "codex-agent-runtime-node/src/wasmJsMain/kotlin/OnlyWasm.kt"

        def matches(lane: str, category: str, path: str) -> bool:
            return any(
                fnmatch.fnmatchcase(path, spec)
                for spec in effective_pathspecs(root, lane, category)
            )

        self.assertTrue(matches("portable", "production", js))
        self.assertTrue(matches("portable", "production", wasm))
        self.assertTrue(matches("node-js", "production", js))
        self.assertFalse(matches("node-js", "production", wasm))
        self.assertFalse(matches("node-wasm", "production", js))
        self.assertTrue(matches("node-wasm", "production", wasm))
        for lane in (name for name in LANES if name.startswith("desktop-")):
            self.assertFalse(matches(lane, "production", js))
            self.assertFalse(matches(lane, "production", wasm))
            self.assertTrue(matches(lane, "test", js))
            self.assertTrue(matches(lane, "test", wasm))

    def test_runtime_source_change_invalidates_portable_runner(self) -> None:
        result, _, _ = self.make_plan("desktop-runtime/Main.kt")
        self.assertTrue(result["lanes"]["portable"]["build"])

    def test_test_only_does_not_build_or_propagate(self) -> None:
        result, _, _ = self.make_plan("android-tests/Test.kt")
        self.assertTrue(result["lanes"]["android"]["test"])
        self.assertFalse(result["lanes"]["android"]["build"])
        self.assertFalse(result["lanes"]["consumer-android"]["build"])

    def test_simulator_test_does_not_select_device(self) -> None:
        result, _, _ = self.make_plan("ios-sim-tests/Test.kt")
        self.assertTrue(result["lanes"]["ios-kotlin-tests"]["test"])
        self.assertFalse(result["lanes"]["ios-framework-device"]["build"])

    def test_swift_test_selects_both_frameworks(self) -> None:
        result, _, _ = self.make_plan("ios-swift-auth-tests/Test.swift")
        self.assertTrue(result["lanes"]["ios-swift-tests"]["test"])
        for lane in ("ios-framework-device", "ios-framework-simulator", "ios-swift-build"):
            self.assertTrue(result["lanes"][lane]["build"])

    def test_device_framework_change_invalidates_swift_tests(self) -> None:
        result, plan_path, _ = self.make_plan("configured/ios-framework-device.txt", "device changed\n")
        self.assertTrue(result["lanes"]["ios-framework-device"]["build"])
        self.assertTrue(result["lanes"]["ios-swift-tests"]["test"])
        inventory = plan_path.parent / "inventories/ios-swift-tests/production-inputs.git-tree"
        self.assertIn("\tconfigured/ios-framework-device.txt\n", inventory.read_text(encoding="utf-8"))

    def test_checksum_only_change_selects_metadata_without_product_builds(self) -> None:
        result, _, _ = self.make_plan("Package.swift", "// checksum only\n")
        self.assertTrue(result["lanes"]["ios-package"]["metadata"])
        self.assertFalse(any(
            state[action]
            for lane, state in result["lanes"].items()
            for action in ("build", "test")
            if lane.startswith("ios-")
        ))

    def test_candidate_support_fallbacks_produce_complete_desktop_and_package_lanes(self) -> None:
        desktop, _, _ = self.make_plan("configured/consumer-desktop.txt", "consumer changed\n")
        for lane in (name for name in LANES if name.startswith("desktop-")):
            self.assertTrue(desktop["lanes"][lane]["build"])
            self.assertTrue(desktop["lanes"][lane]["test"])

        privacy, _, _ = self.make_plan("privacy-policy/review.json", "{}\n")
        self.assertTrue(privacy["lanes"]["ios-package"]["build"])
        self.assertTrue(privacy["lanes"]["ios-package"]["metadata"])

    def test_unknown_and_planner_changes_force_full(self) -> None:
        unknown, _, unknown_target = self.make_plan("unclassified/input.txt")
        self.assertTrue(unknown["full"])
        self.assertEqual(["unclassified/input.txt"], unknown["unknownPaths"])
        self.assertTrue(all(state["build"] for state in unknown["lanes"].values()))
        self.assertTrue(all(not state["reuseAllowed"] for state in unknown["lanes"].values()))

        planner, planner_path, planner_target = self.make_plan(
            "ci/impact.py", "# changed planner\n", base=unknown_target,
        )
        self.assertTrue(planner["full"])
        self.assertEqual([], planner["unknownPaths"])
        self.assertTrue(all(state["reuseAllowed"] for state in planner["lanes"].values()))
        for lane in LANES:
            inventory = planner_path.parent / "inventories" / lane / "production-inputs.git-tree"
            self.assertIn("\tci/impact.py\n", inventory.read_text(encoding="utf-8"))

        forced, _, _ = self.make_plan(
            "README.md", "forced full\n", base=planner_target, force_full=True,
        )
        self.assertTrue(forced["full"])
        self.assertTrue(all(state["reuseAllowed"] for state in forced["lanes"].values()))

    def test_consumer_matrix_selects_the_required_host(self) -> None:
        result, _, desktop_target = self.make_plan("configured/consumer-desktop.txt", "consumer changed\n")
        output = self.root / "github-output"
        write_github_outputs(output, result)
        values = dict(line.split("=", 1) for line in output.read_text(encoding="utf-8").splitlines())
        matrix = json.loads(values["consumer_matrix"])
        self.assertEqual(["macos-26"], [item["runner"] for item in matrix if item["lane"] == "consumer-desktop"])

        result, _, _ = self.make_plan("android/Main.kt", base=desktop_target)
        output = self.root / "github-output-android"
        write_github_outputs(output, result)
        values = dict(line.split("=", 1) for line in output.read_text(encoding="utf-8").splitlines())
        matrix = json.loads(values["consumer_matrix"])
        self.assertTrue(matrix)
        self.assertTrue(all(item["runner"] == "ubuntu-24.04" for item in matrix))

    def test_shared_production_change_selects_every_lane(self) -> None:
        result, _, _ = self.make_plan("common/Api.kt")
        self.assertTrue(all(state["build"] for state in result["lanes"].values()))

    def test_contract_only_change_does_not_propagate(self) -> None:
        result, _, _ = self.make_plan("configured/contracts.txt")
        self.assertTrue(result["lanes"]["contracts"]["build"])
        self.assertFalse(any(
            state[action]
            for lane, state in result["lanes"].items()
            if lane != "contracts"
            for action in ("build", "test", "metadata")
        ))

    def test_jvm_only_change_selects_only_common_and_desktop_consumers(self) -> None:
        result, _, _ = self.make_plan("codex-agent-client/src/jvmMain/kotlin/JvmOnly.kt")
        selected_consumers = {
            lane
            for lane, state in result["lanes"].items()
            if lane.startswith("consumer-") and state["build"]
        }
        self.assertEqual({"consumer-common", "consumer-desktop"}, selected_consumers)

    def test_unlabeled_pr_spends_no_product_ci(self) -> None:
        result, _, _ = self.make_plan("android/Main.kt", merge_ready=False)
        self.assertFalse(any(
            state[action]
            for state in result["lanes"].values()
            for action in ("build", "test", "metadata")
        ))
        self.assertTrue(all(state["reasons"] == ["merge-ready-required"] for state in result["lanes"].values()))


class RealImpactPlanTest(unittest.TestCase):
    def test_build_logic_sources_have_explicit_narrow_owners(self) -> None:
        root = CI_ROOT.parent

        def matching_lanes(category: str, path: str) -> set[str]:
            return {
                lane
                for lane in LANES
                if any(
                    fnmatch.fnmatchcase(path, spec)
                    for spec in effective_pathspecs(root, lane, category)
                )
            }

        release_only_sources = (
            "CandidateCiProvenance.kt",
            "CandidateManifestValidation.kt",
            "CandidateModel.kt",
            "CandidatePayloadTasks.kt",
            "CandidateRuntimeEvidence.kt",
            "CentralPortalTask.kt",
            "PromotedCandidateTasks.kt",
            "ReleaseToolingCli.kt",
        )
        prefix = "gradle/build-logic/src/main/kotlin/"
        for filename in release_only_sources:
            with self.subTest(filename=filename):
                self.assertEqual({"contracts"}, matching_lanes("production", prefix + filename))

        self.assertEqual(
            {
                "contracts", "ios-rust-device", "ios-rust-simulator",
                "ios-framework-device", "ios-framework-simulator",
                "ios-kotlin-tests", "ios-swift-build", "ios-swift-tests",
                "ios-package", "ios-privacy-metrics",
                "consumer-ios-device", "consumer-ios-simulator",
            },
            matching_lanes("production", prefix + "AppleReleaseCheckTasks.kt"),
        )
        self.assertEqual(
            {"contracts", "consumer-common"},
            matching_lanes("production", prefix + "MavenRepositoryTasks.kt"),
        )
        self.assertEqual(
            set(LANES),
            matching_lanes("production", "gradle/libs.versions.toml"),
        )
        self.assertEqual(
            {"portable", "consumer-common", "consumer-desktop"},
            matching_lanes(
                "production",
                "codex-agent-client/src/jvmMain/kotlin/io/github/codex_agent_labs/ClientJvm.kt",
            ),
        )

        imported = prefix + "ImportedAppleFrameworkTasks.kt"
        self.assertEqual(
            {"ios-swift-build", "ios-swift-tests", "ios-package", "ios-privacy-metrics"},
            matching_lanes("production", imported),
        )
        self.assertEqual({"contracts"}, matching_lanes("test", imported))

        gradle_tasks = prefix + "ReleaseToolingGradleTasks.kt"
        self.assertEqual(
            {
                "ios-rust-device", "ios-rust-simulator",
                "ios-framework-device", "ios-framework-simulator",
                "ios-kotlin-tests", "ios-swift-build", "ios-swift-tests",
                "ios-package", "ios-privacy-metrics",
                "consumer-common", "consumer-android", "consumer-desktop",
                "consumer-ios-device", "consumer-ios-simulator",
                "consumer-node-js", "consumer-node-wasm",
            },
            matching_lanes("production", gradle_tasks),
        )
        self.assertEqual(
            {
                "contracts", "desktop-macos-arm64", "desktop-macos-x64",
                "desktop-linux-x64", "desktop-windows-x64",
            },
            matching_lanes("test", gradle_tasks),
        )

        registration = prefix + "ProtectedRuntimeCandidateRegistration.kt"
        self.assertEqual(set(), matching_lanes("production", registration))
        self.assertEqual({"contracts"}, matching_lanes("test", registration))

        future_source = prefix + "FutureProductionTask.kt"
        for category in ("production", "test", "metadata"):
            self.assertEqual(set(), matching_lanes(category, future_source))

    def test_real_base_to_fixed_head_allows_same_pr_repair_reuse(self) -> None:
        repository = CI_ROOT.parent

        def git(root: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
            return subprocess.run(
                ["git", *arguments],
                cwd=root,
                check=check,
                capture_output=True,
                text=True,
            )

        base_result = git(repository, "merge-base", "HEAD", "origin/main", check=False)
        if base_result.returncode:
            self.skipTest("origin/main history is unavailable in this checkout")
        base = base_result.stdout.strip()
        target = git(repository, "rev-parse", "HEAD").stdout.strip()

        with tempfile.TemporaryDirectory() as temporary:
            temporary_root = Path(temporary)
            clone = temporary_root / "repository"
            subprocess.run(
                ["git", "clone", "--shared", "--quiet", str(repository), str(clone)],
                check=True,
                capture_output=True,
                text=True,
            )
            git(clone, "checkout", "--quiet", "--detach", target)
            git(clone, "config", "user.email", "ci@example.invalid")
            git(clone, "config", "user.name", "CI Fixture")

            for source in (CI_ROOT / "lanes").glob("*.pathspec"):
                shutil.copy2(source, clone / "ci/lanes" / source.name)
            shutil.copy2(CI_ROOT / "impact.py", clone / "ci/impact.py")
            shutil.copy2(CI_ROOT / "tests/test_ci.py", clone / "ci/tests/test_ci.py")
            if git(clone, "status", "--porcelain", "--", "ci/impact.py", "ci/lanes", "ci/tests/test_ci.py").stdout:
                git(clone, "add", "--", "ci/impact.py", "ci/lanes", "ci/tests/test_ci.py")
                git(clone, "commit", "--quiet", "-m", "Classify build logic inputs")
            fixed_target = git(clone, "rev-parse", "HEAD").stdout.strip()

            prior_path = clone / "build/test-plans/prior/impact-plan.json"
            prior = plan(
                root=clone,
                base=base,
                target=fixed_target,
                head=fixed_target,
                event="pull_request",
                pull_request=13,
                merge_ready=True,
                force_full=True,
                require_android_evidence=True,
                repository="codex-agent-labs/codex-agent",
                output=prior_path,
            )
            self.assertEqual([], prior["unknownPaths"])
            self.assertTrue(all(state["reuseAllowed"] for state in prior["lanes"].values()))

            package_swift = clone / "Package.swift"
            package_swift.write_text(
                package_swift.read_text(encoding="utf-8") + "\n// same-PR checksum repair\n",
                encoding="utf-8",
            )
            git(clone, "add", "--", "Package.swift")
            git(clone, "commit", "--quiet", "-m", "Repair Swift checksum metadata")
            repair_target = git(clone, "rev-parse", "HEAD").stdout.strip()
            repair_path = clone / "build/test-plans/repair/impact-plan.json"
            repair = plan(
                root=clone,
                base=base,
                target=repair_target,
                head=repair_target,
                event="pull_request",
                pull_request=13,
                merge_ready=True,
                force_full=True,
                require_android_evidence=True,
                repository="codex-agent-labs/codex-agent",
                output=repair_path,
            )
            self.assertEqual([], repair["unknownPaths"])
            self.assertTrue(all(state["reuseAllowed"] for state in repair["lanes"].values()))

            receipt_root = temporary_root / "successful-android"
            receipt_root.mkdir()
            (receipt_root / "product.bin").write_text("product\n", encoding="utf-8")
            create_receipt(Namespace(
                plan=prior_path,
                lane="android",
                output=receipt_root,
                workflow_path=".github/workflows/ci.yml",
                artifact_name=f"codex-agent-ci-android-{prior['validationTree']}",
                run_id=101,
                run_attempt=1,
                runner=["os=Linux", "arch=X64"],
                toolchain=["java=25", "gradle=9.4.1", "validationActions=build,metadata,test"],
                artifact=["product.bin=binary"],
                evidence=[],
            ))
            validate_receipt(
                receipt_root / "lane-receipt.json",
                repair_path,
                receipt_root,
                "android",
                allow_compatible=True,
                runner={"os": "Linux", "arch": "X64"},
                toolchain={"java": "25", "gradle": "9.4.1"},
            )

            future_relative = "gradle/build-logic/src/main/kotlin/FutureProductionTask.kt"
            future = clone / future_relative
            future.write_text("class FutureProductionTask\n", encoding="utf-8")
            git(clone, "add", "--", future_relative)
            git(clone, "commit", "--quiet", "-m", "Add unmapped production build logic")
            unknown_target = git(clone, "rev-parse", "HEAD").stdout.strip()
            unknown = plan(
                root=clone,
                base=base,
                target=unknown_target,
                head=unknown_target,
                event="pull_request",
                pull_request=13,
                merge_ready=True,
                force_full=False,
                require_android_evidence=True,
                repository="codex-agent-labs/codex-agent",
                output=clone / "build/test-plans/unknown/impact-plan.json",
            )
            self.assertEqual([future_relative], unknown["unknownPaths"])
            self.assertTrue(unknown["full"])
            self.assertTrue(all(not state["reuseAllowed"] for state in unknown["lanes"].values()))


class StageArchiveTest(unittest.TestCase):
    def test_recursive_output_globs_are_python_312_compatible(self) -> None:
        self.assertFalse([
            pattern
            for outputs in OUTPUTS.values()
            for _, pattern, _ in outputs
            if pattern.endswith("/**")
        ])

    def test_swift_products_archive_preserves_modes_and_rejects_unsafe_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            products = root / "codex-agent-runtime-ios/build/swift-simulator-compilation-products"
            executable = products / "Build/Products/Debug/TestBundle.xctest/Contents/MacOS/TestBundle"
            executable.parent.mkdir(parents=True)
            executable.write_text("binary\n", encoding="utf-8")
            executable.chmod(0o755)
            resource = products / "Build/Products/Debug/TestBundle.xctest/Contents/Info.plist"
            resource.write_text("plist\n", encoding="utf-8")
            resource.chmod(0o640)
            link = resource.parent / "TestBundleLink"
            link.symlink_to("MacOS/TestBundle")

            staged = root / "staged"
            relative = archive_tree(
                root,
                staged,
                "codex-agent-runtime-ios/build/swift-simulator-compilation-products",
            )[0]
            extracted = root / "extracted"
            subprocess.run([
                sys.executable,
                str(CI_ROOT / "stage.py"),
                "extract-tar",
                "--archive", str(staged / relative),
                "--output", str(extracted),
            ], check=True)
            restored = extracted / "swift-simulator-compilation-products/Build/Products/Debug/TestBundle.xctest/Contents"
            self.assertEqual(0o755, stat.S_IMODE((restored / "MacOS/TestBundle").stat().st_mode))
            self.assertEqual(0o640, stat.S_IMODE((restored / "Info.plist").stat().st_mode))
            self.assertTrue((restored / "TestBundleLink").is_symlink())
            self.assertEqual("MacOS/TestBundle", os.readlink(restored / "TestBundleLink"))

            traversal = root / "traversal.tar"
            with tarfile.open(traversal, "w") as archive:
                archive.addfile(tarfile.TarInfo("../escape"))
            with self.assertRaisesRegex(ValueError, "Unsafe archive member"):
                safe_extract_tar(traversal, root / "unsafe-extract")
            self.assertFalse((root / "escape").exists())

            unsafe_link = root / "unsafe-link.tar"
            member = tarfile.TarInfo("products/link")
            member.type = tarfile.SYMTYPE
            member.linkname = "../../escape"
            with tarfile.open(unsafe_link, "w") as archive:
                archive.addfile(member)
            with self.assertRaisesRegex(ValueError, "Unsafe archive link target"):
                safe_extract_tar(unsafe_link, root / "unsafe-link-extract")


class StageProductionRestoreTest(unittest.TestCase):
    @staticmethod
    def source(root: Path, artifacts: list[tuple[str, str]], evidence: list[tuple[str, str]]) -> Path:
        source = root / "source"
        source.mkdir()
        for relative, _ in (*artifacts, *evidence):
            file = source / relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(f"source:{relative}\n", encoding="utf-8")
        (source / "lane-receipt.json").write_text(json.dumps({
            "artifacts": [
                {"relativePath": relative, "kind": kind}
                for relative, kind in artifacts
            ],
            "evidence": [
                {"relativePath": relative, "kind": kind}
                for relative, kind in evidence
            ],
        }), encoding="utf-8")
        return source

    def test_restored_production_drops_test_owned_privacy_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = self.source(root, [
                ("payload/privacy/policy.json", "privacy-runtime-input"),
                ("payload/privacy/evidence.json", "privacy-runtime-input"),
            ], [])
            artifacts, evidence = restore_production_files(
                source, root / "output", "ios-privacy-metrics",
            )
            self.assertEqual({}, artifacts)
            self.assertEqual({}, evidence)
            self.assertFalse((root / "output/payload/privacy/policy.json").exists())

    def test_restored_production_drops_metadata_owned_swift_checksum(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = self.source(root, [
                ("payload/CodexAgent.xcframework.zip", "swift-package-binary"),
                ("payload/CodexAgent.xcframework.zip.sha256", "swiftpm-checksum"),
            ], [])
            artifacts, _ = restore_production_files(source, root / "output", "ios-package")
            self.assertEqual(
                {"payload/CodexAgent.xcframework.zip": "swift-package-binary"},
                artifacts,
            )
            self.assertFalse((root / "output/payload/CodexAgent.xcframework.zip.sha256").exists())

    def test_restored_production_keeps_build_owned_evidence(self) -> None:
        cases = (
            ("ios-rust-device", "payload/rust-proof.json", "rust-proof"),
            ("ios-swift-build", "payload/swift-build.json", "swift-build-report"),
        )
        for lane, relative, kind in cases:
            with self.subTest(lane=lane), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = self.source(root, [], [(relative, kind)])
                _, evidence = restore_production_files(source, root / "output", lane)
                self.assertEqual({relative: kind}, evidence)
                self.assertTrue((root / "output" / relative).is_file())

    def test_restored_production_keeps_firebase_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            relative = "payload/external/android-runtime-evidence/evidence.json"
            source = self.source(root, [], [
                (relative, "firebase-runtime-evidence"),
                ("payload/android-test-report.xml", "test-report"),
            ])
            _, evidence = restore_production_files(source, root / "output", "android")
            self.assertEqual({relative: "firebase-runtime-evidence"}, evidence)
            self.assertTrue((root / "output" / relative).is_file())
            self.assertFalse((root / "output/payload/android-test-report.xml").exists())


class ReceiptTest(GitFixture):
    def setUp(self) -> None:
        super().setUp()
        self.first_plan, self.plan_path, self.first_target = self.make_plan("android-tests/Test.kt")
        self.first_tree = str(self.first_plan["validationTree"])
        self.receipt_root = self.root / "artifacts/android"
        self.receipt_root.mkdir(parents=True)
        self.write("artifacts/android/product.bin", "product\n")
        evidence_path = "data.0~token=="
        self.write(f"artifacts/android/{evidence_path}", "{}\n")
        create_receipt(Namespace(
            plan=self.plan_path,
            lane="android",
            output=self.receipt_root,
            workflow_path=".github/workflows/ci.yml",
            artifact_name=f"codex-agent-ci-android-{self.first_tree}",
            run_id=101,
            run_attempt=2,
            runner=["os=Linux", "arch=X64"],
            toolchain=["java=25", "gradle=9.4.1", "validationActions=build,test"],
            artifact=["product.bin=binary"],
            evidence=[f"{evidence_path}=xctest-result"],
        ))

    def test_exact_receipt_and_aggregate(self) -> None:
        receipt = validate_receipt(
            self.receipt_root / "lane-receipt.json",
            self.plan_path,
            self.receipt_root,
            "android",
        )
        self.assertNotIn("bytes", receipt["evidence"][0])
        self.assertEqual("data.0~token==", receipt["evidence"][0]["relativePath"])
        self.assertEqual(64, len(receipt["evidence"][0]["sha256"]))
        output = self.root / "build/ci/validation-receipt.json"
        aggregate(Namespace(plan=self.plan_path, receipts=self.receipt_root.parent, output=output))
        combined = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(["android"], list(combined["lanes"]))
        self.assertEqual(101, combined["lanes"]["android"]["runId"])

    def test_required_android_evidence_is_attached_fail_closed(self) -> None:
        plan_value = json.loads(self.plan_path.read_text(encoding="utf-8"))
        plan_value["androidEvidenceRequired"] = True
        self.plan_path.write_text(json.dumps(plan_value), encoding="utf-8")
        output = self.root / "build/ci/validation-receipt.json"
        with self.assertRaisesRegex(ValueError, "Firebase runtime evidence"):
            aggregate(Namespace(plan=self.plan_path, receipts=self.receipt_root.parent, output=output))
        with self.assertRaisesRegex(ValueError, "plan artifact name mismatch"):
            with patch.object(sys, "argv", [
                "evidence.py", "check", "--artifact", str(self.receipt_root),
                "--plan", str(self.plan_path), "--expected-plan-artifact", "codex-agent-ci-plan-wrong",
            ]):
                evidence_main()
        source = self.root / "firebase"
        self.write("firebase/result.xml", "<testsuite/>\n")
        with patch.object(sys, "argv", [
            "evidence.py", "attach", "--artifact", str(self.receipt_root),
            "--plan", str(self.plan_path), "--source", str(source),
            "--expected-commit", plan_value["validationCommit"],
            "--expected-artifact", f"codex-agent-ci-android-{self.first_tree}",
        ]):
            evidence_main()
        aggregate(Namespace(plan=self.plan_path, receipts=self.receipt_root.parent, output=output))
        receipt = json.loads((self.receipt_root / "lane-receipt.json").read_text(encoding="utf-8"))
        self.assertIn("firebase-runtime-evidence", {item["kind"] for item in receipt["evidence"]})
        provenance = self.receipt_root / "transport-provenance.json"
        provenance.write_text("{}\n", encoding="utf-8")
        import hashlib
        receipt["evidence"].append({
            "relativePath": provenance.name,
            "kind": "transport-provenance",
            "sha256": hashlib.sha256(provenance.read_bytes()).hexdigest(),
        })
        (self.receipt_root / "lane-receipt.json").write_text(json.dumps(receipt), encoding="utf-8")
        replacement = self.root / "firebase-replacement"
        self.write("firebase-replacement/trusted.xml", "<testsuite tests='2'/>")
        with patch.object(sys, "argv", [
            "evidence.py", "attach", "--artifact", str(self.receipt_root),
            "--plan", str(self.plan_path), "--source", str(replacement), "--replace",
        ]):
            evidence_main()
        self.assertFalse((self.receipt_root / "payload/external/android-runtime-evidence/result.xml").exists())
        self.assertFalse(provenance.exists())
        self.assertTrue((self.receipt_root / "payload/external/android-runtime-evidence/trusted.xml").is_file())

    def test_identical_tree_reuses_pr_aggregate_only(self) -> None:
        reusable = self.root / "reusable-validation"
        reusable.mkdir()
        (reusable / "impact-plan.json").write_bytes(self.plan_path.read_bytes())
        aggregate(Namespace(
            plan=self.plan_path,
            receipts=self.receipt_root.parent,
            output=reusable / "validation-receipt.json",
        ))
        merge_plan = json.loads(self.plan_path.read_text(encoding="utf-8"))
        merge_plan["event"] = "merge_group"
        current = self.root / "merge-plan.json"
        current.write_text(json.dumps(merge_plan), encoding="utf-8")
        validate_aggregate_reuse(reusable, current)
        materialized = self.root / "queue-validation.json"
        materialize(reusable, current, materialized)
        queue_receipt = json.loads(materialized.read_text(encoding="utf-8"))
        self.assertEqual("merge_group", queue_receipt["event"])
        self.assertEqual(merge_plan["validationCommit"], queue_receipt["validationCommit"])

        source_plan = json.loads((reusable / "impact-plan.json").read_text(encoding="utf-8"))
        source_plan["lanes"]["portable"].update(build=True, reasons=["ci-full-label"])
        (reusable / "impact-plan.json").write_text(json.dumps(source_plan), encoding="utf-8")
        source_receipt = json.loads((reusable / "validation-receipt.json").read_text(encoding="utf-8"))
        portable = dict(source_receipt["lanes"]["android"])
        portable["artifactName"] = f"codex-agent-ci-portable-{self.first_tree}"
        source_receipt["lanes"]["portable"] = portable
        (reusable / "validation-receipt.json").write_text(json.dumps(source_receipt), encoding="utf-8")
        materialize(reusable, current, materialized)
        pruned = json.loads(materialized.read_text(encoding="utf-8"))
        self.assertEqual({"android"}, set(pruned["lanes"]))

        merge_plan["validationTree"] = "0" * 40
        current.write_text(json.dumps(merge_plan), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "tree mismatch"):
            validate_aggregate_reuse(reusable, current)
        merge_plan["validationTree"] = self.first_tree
        merge_plan["androidEvidenceRequired"] = True
        current.write_text(json.dumps(merge_plan), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "lacks required Android evidence"):
            validate_aggregate_reuse(reusable, current)

    def test_same_pr_compatible_inventory_reuses_prior_lane(self) -> None:
        current_plan, current_path, _ = self.make_plan(
            "README.md",
            "later docs\n",
            base=self.first_target,
        )
        self.assertEqual([], [lane for lane, state in current_plan["lanes"].items() if state["build"]])
        with self.assertRaisesRegex(ValueError, "mismatch"):
            validate_receipt(
                self.receipt_root / "lane-receipt.json",
                current_path,
                self.receipt_root,
                "android",
            )
        validate_receipt(
            self.receipt_root / "lane-receipt.json",
            current_path,
            self.receipt_root,
            "android",
            allow_compatible=True,
            runner={"os": "Linux", "arch": "X64"},
            toolchain={"java": "25", "gradle": "9.4.1"},
        )

    def test_size_mismatch_and_extra_file_fail_closed(self) -> None:
        self.write("artifacts/android/product.bin", "different-size\n")
        with self.assertRaisesRegex(ValueError, "integrity-mismatched"):
            validate_receipt(
                self.receipt_root / "lane-receipt.json",
                self.plan_path,
                self.receipt_root,
            )

    def test_reuse_requires_complete_canonical_action_coverage(self) -> None:
        forced_path = self.root / "build/forced/impact-plan.json"
        forced = plan(
            root=self.root,
            base=self.base,
            target=self.first_target,
            head=self.first_target,
            event="pull_request",
            pull_request=7,
            merge_ready=True,
            force_full=True,
            require_android_evidence=False,
            repository="codex-agent-labs/codex-agent",
            output=forced_path,
        )
        self.assertEqual(
            {"build": True, "test": True, "metadata": True},
            {action: forced["lanes"]["android"][action] for action in ("build", "test", "metadata")},
        )
        receipt_path = self.receipt_root / "lane-receipt.json"
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        with self.assertRaisesRegex(ValueError, "action coverage mismatch"):
            validate_receipt(
                receipt_path,
                forced_path,
                self.receipt_root,
                "android",
                allow_compatible=True,
            )

        receipt["toolchain"]["validationActions"] = "build,metadata,test"
        receipt_path.write_text(json.dumps(receipt), encoding="utf-8")
        validate_receipt(
            receipt_path,
            forced_path,
            self.receipt_root,
            "android",
            allow_compatible=True,
        )

        receipt["toolchain"]["validationActions"] = "test"
        receipt_path.write_text(json.dumps(receipt), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "action coverage mismatch"):
            validate_receipt(
                receipt_path,
                self.plan_path,
                self.receipt_root,
                "android",
                allow_compatible=True,
                categories=("production",),
            )
        receipt["toolchain"]["validationActions"] = "build"
        receipt_path.write_text(json.dumps(receipt), encoding="utf-8")
        validate_receipt(
            receipt_path,
            self.plan_path,
            self.receipt_root,
            "android",
            allow_compatible=True,
            categories=("production",),
        )

    def test_receipt_rejects_missing_or_malformed_action_coverage(self) -> None:
        receipt_path = self.receipt_root / "lane-receipt.json"
        original = json.loads(receipt_path.read_text(encoding="utf-8"))
        for value in (None, "", "test,build", "build,unknown", "build,build"):
            with self.subTest(value=value):
                receipt = json.loads(json.dumps(original))
                if value is None:
                    receipt["toolchain"].pop("validationActions")
                else:
                    receipt["toolchain"]["validationActions"] = value
                receipt_path.write_text(json.dumps(receipt), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "action coverage"):
                    validate_receipt(receipt_path, self.plan_path, self.receipt_root, "android")

    def test_reuse_selects_full_or_production_compatible_receipt(self) -> None:
        archive = self.root / "lane.zip"
        with zipfile.ZipFile(archive, "w") as output:
            for file in self.receipt_root.iterdir():
                output.write(file, file.name)
        artifact = {
            "name": f"codex-agent-ci-android-{self.first_tree}",
            "archive_download_url": "https://example.invalid/lane.zip",
            "digest": f"sha256:{hashlib.sha256(archive.read_bytes()).hexdigest()}",
        }

        def arguments(plan_path: Path, destination: str, mode: str) -> Namespace:
            return Namespace(
                plan=plan_path,
                lane="android",
                destination=self.root / destination,
                mode=mode,
                workflow="ci.yml",
                runner=["os=Linux", "arch=X64"],
                toolchain=["java=25", "gradle=9.4.1"],
                token="token",
                api_url="https://api.github.invalid",
            )

        with (
            patch("reuse.candidate_artifacts", return_value=[artifact]),
            patch("reuse.promoted_artifacts", return_value=[]),
            patch("reuse.api_request", return_value=archive.read_bytes()),
        ):
            exact = restore(arguments(self.plan_path, "restored-full", "full"))
            self.assertTrue(exact["reused"])

            with (
                urllib.error.HTTPError(
                    "https://api.github.invalid/promote.yml", 404, "Not Found", {}, None,
                ) as not_found,
                patch("reuse.promoted_artifacts", side_effect=promoted_artifacts),
                patch("reuse.api_json", side_effect=not_found),
            ):
                missing_promotion = restore(arguments(
                    self.plan_path, "restored-without-promotion-workflow", "full",
                ))
            self.assertTrue(missing_promotion["reused"], missing_promotion)

            with (
                urllib.error.HTTPError(
                    "https://api.github.invalid/promote.yml", 500, "Server Error", {}, None,
                ) as server_error,
                patch("reuse.promoted_artifacts", side_effect=promoted_artifacts),
                patch("reuse.api_json", side_effect=server_error),
            ):
                promotion_failure = restore(arguments(
                    self.plan_path, "rejected-promotion-failure", "full",
                ))
            self.assertFalse(promotion_failure["reused"])
            self.assertEqual("discovery-unavailable:HTTPError", promotion_failure["reason"])

            valid_digest = artifact["digest"]
            artifact["digest"] = f"sha256:{'0' * 64}"
            corrupt = restore(arguments(self.plan_path, "rejected-corrupt-transport", "full"))
            self.assertFalse(corrupt["reused"])
            self.assertIn("discovery-unavailable", corrupt["reason"])
            artifact["digest"] = valid_digest

            current_plan, current_path, _ = self.make_plan(
                "android-tests/Test.kt",
                "later test\n",
                base=self.first_target,
            )
            self.assertTrue(current_plan["lanes"]["android"]["test"])
            incompatible = restore(arguments(current_path, "not-restored", "full"))
            self.assertFalse(incompatible["reused"])
            production = restore(arguments(current_path, "restored-production", "production"))
            self.assertTrue(production["reused"])

    def test_failed_lane_production_artifact_is_never_full_reuse(self) -> None:
        partial_root = self.root / "partial"
        shutil.copytree(self.receipt_root, partial_root)
        partial_name = f"codex-agent-ci-android-production-{self.first_tree}"
        receipt = json.loads((partial_root / "lane-receipt.json").read_text(encoding="utf-8"))
        receipt["artifactName"] = partial_name
        (partial_root / "lane-receipt.json").write_text(json.dumps(receipt), encoding="utf-8")
        archive = self.root / "partial.zip"
        with zipfile.ZipFile(archive, "w") as output:
            for file in partial_root.rglob("*"):
                if file.is_file():
                    output.write(file, file.relative_to(partial_root))
        artifact = {
            "name": partial_name,
            "archive_download_url": "https://example.invalid/partial.zip",
            "digest": f"sha256:{hashlib.sha256(archive.read_bytes()).hexdigest()}",
        }

        def arguments(destination: str, mode: str) -> Namespace:
            return Namespace(
                plan=self.plan_path,
                lane="android",
                destination=self.root / destination,
                mode=mode,
                workflow="ci.yml",
                runner=["os=Linux", "arch=X64"],
                toolchain=["java=25", "gradle=9.4.1"],
                token="token",
                api_url="https://api.github.invalid",
            )

        with (
            patch("reuse.candidate_artifacts", return_value=[artifact]),
            patch("reuse.promoted_artifacts", return_value=[]),
            patch("reuse.api_request", return_value=archive.read_bytes()),
        ):
            self.assertFalse(restore(arguments("partial-full", "full"))["reused"])
            self.assertTrue(restore(arguments("partial-production", "production"))["reused"])

    def test_promoted_main_artifact_reuses_across_prs(self) -> None:
        archive = self.root / "promoted.zip"
        with zipfile.ZipFile(archive, "w") as output:
            for file in self.receipt_root.iterdir():
                output.write(file, file.name)
        promoted = {
            "name": f"codex-agent-promoted-android-{self.first_target}",
            "archive_download_url": "https://example.invalid/promoted.zip",
            "digest": f"sha256:{hashlib.sha256(archive.read_bytes()).hexdigest()}",
            "_promoted": True,
        }
        _, current_path, _ = self.make_plan(
            "README.md", "next PR\n", base=self.first_target, pull_request=8,
        )
        arguments = Namespace(
            plan=current_path,
            lane="android",
            destination=self.root / "restored-promoted",
            mode="full",
            workflow="ci.yml",
            runner=["os=Linux", "arch=X64"],
            toolchain=["java=25", "gradle=9.4.1"],
            token="token",
            api_url="https://api.github.invalid",
        )
        with (
            patch("reuse.candidate_artifacts", return_value=[]),
            patch("reuse.promoted_artifacts", return_value=[promoted]),
            patch("reuse.api_request", return_value=archive.read_bytes()),
        ):
            restored = restore(arguments)
            self.assertTrue(restored["reused"])
        transported = json.loads((arguments.destination / "lane-receipt.json").read_text(encoding="utf-8"))
        self.assertEqual(8, transported["pullRequest"])
        self.assertTrue((arguments.destination / "transport-provenance.json").is_file())

    def test_unsafe_zip_members_are_rejected(self) -> None:
        traversal = self.root / "traversal.zip"
        with zipfile.ZipFile(traversal, "w") as archive:
            archive.writestr("../escape", "bad")
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            safe_extract(traversal, self.root / "extract-traversal")

        symlink = self.root / "symlink.zip"
        member = zipfile.ZipInfo("link")
        member.create_system = 3
        member.external_attr = (stat.S_IFLNK | 0o777) << 16
        with zipfile.ZipFile(symlink, "w") as archive:
            archive.writestr(member, "target")
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            safe_extract(symlink, self.root / "extract-symlink")

    def test_validation_discovery_paginates_and_api_failures_rebuild(self) -> None:
        reusable = self.root / "reusable-validation"
        reusable.mkdir()
        (reusable / "impact-plan.json").write_bytes(self.plan_path.read_bytes())
        aggregate(Namespace(
            plan=self.plan_path,
            receipts=self.receipt_root.parent,
            output=reusable / "validation-receipt.json",
        ))
        archive = self.root / "validation.zip"
        with zipfile.ZipFile(archive, "w") as output:
            for file in reusable.rglob("*"):
                if file.is_file():
                    output.write(file, file.relative_to(reusable))
        current = json.loads(self.plan_path.read_text(encoding="utf-8"))
        current["event"] = "merge_group"
        current_path = self.root / "merge-plan.json"
        current_path.write_text(json.dumps(current), encoding="utf-8")
        artifact = {
            "name": f"codex-agent-ci-validation-{self.first_tree}",
            "expired": False,
        }
        calls: list[str] = []

        def api(url: str, _token: str) -> dict[str, object]:
            calls.append(url)
            if "/workflows/" in url:
                if "&page=1" in url:
                    return {"workflow_runs": [
                        {"id": index, "conclusion": "success", "pull_requests": [{"number": 99}]}
                        for index in range(100)
                    ]}
                return {"workflow_runs": [
                    {"id": 999, "run_attempt": 3, "conclusion": "success", "pull_requests": [{"number": 7}]},
                ]}
            return {"artifacts": [artifact]}

        with (
            patch("reuse.api_json", side_effect=api),
            patch("validation_reuse.download_artifact", return_value=archive.read_bytes()),
        ):
            result = discover_validation(current_path, self.root / "validation-destination", "token", "https://api.invalid")
        self.assertTrue(result["reused"])
        self.assertEqual(999, result["sourceRunId"])
        self.assertTrue(any("&page=2" in url for url in calls))

        with patch("reuse.api_json", side_effect=OSError("offline")):
            self.assertEqual(
                {"reused": False},
                discover_validation(current_path, self.root / "offline", "token", "https://api.invalid"),
            )
        with patch("reuse.api_json", return_value={"workflow_runs": "malformed"}):
            self.assertEqual(
                {"reused": False},
                discover_validation(current_path, self.root / "malformed", "token", "https://api.invalid"),
            )


class DiscoveryPaginationTest(unittest.TestCase):
    def test_artifact_redirect_auth_is_bound_to_the_request_origin(self) -> None:
        handler = OriginBoundRedirectHandler()
        request = urllib.request.Request(
            "https://api.github.com/repos/example/actions/artifacts/1/zip",
            headers={"Accept": "application/zip", "Authorization": "Bearer token"},
        )
        same_origin = handler.redirect_request(
            request, None, 302, "Found", {}, "https://api.github.com:443/download",
        )
        self.assertEqual("Bearer token", same_origin.get_header("Authorization"))

        for url in (
            "http://api.github.com/download",
            "https://productionresults.blob.core.windows.net/download",
            "https://api.github.com:444/download",
        ):
            with self.subTest(url=url):
                redirected = handler.redirect_request(request, None, 302, "Found", {}, url)
                self.assertIsNone(redirected.get_header("Authorization"))
                self.assertEqual("application/zip", redirected.get_header("Accept"))

    def test_lane_discovery_finds_same_pr_beyond_first_page(self) -> None:
        calls: list[str] = []
        artifact = {
            "name": "codex-agent-ci-android-tree",
            "archive_download_url": "https://api.invalid/artifact.zip",
            "expired": False,
        }

        def api(url: str, _token: str) -> dict[str, object]:
            calls.append(url)
            if "/workflows/" in url:
                if "&page=1" in url:
                    return {"workflow_runs": [
                        {"id": index, "pull_requests": [{"number": 99}]}
                        for index in range(100)
                    ]}
                return {"workflow_runs": [{"id": 999, "pull_requests": [{"number": 7}]}]}
            return {"artifacts": [artifact]}

        with patch("reuse.api_json", side_effect=api):
            result = candidate_artifacts(
                "https://api.invalid", "codex-agent-labs/codex-agent", "ci.yml",
                "android", 7, "token", None,
            )
        self.assertEqual([artifact], result)
        self.assertTrue(any("&page=2" in url for url in calls))


if __name__ == "__main__":
    unittest.main()
