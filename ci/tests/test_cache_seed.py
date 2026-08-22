from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from unittest.mock import patch


CI_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = CI_ROOT.parent
sys.path.insert(0, str(CI_ROOT))

from cache_seed import artifact_name, copy_regular_tree, create, install, policy, source, tree_digest  # noqa: E402


REPO = "codex-agent-labs/codex-agent"
COMMIT = "1" * 40
TREE = "2" * 40


class CacheSeedTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.home = self.root / "producer-home"
        self.plan_path = self.root / "impact-plan.json"
        self.promotion_path = self.root / "promotion-plan.json"
        self.aggregate_path = self.root / "validation-receipt.json"
        self.plan = {
            "schemaVersion": 1,
            "event": "merge_group",
            "repository": REPO,
            "mergeReady": True,
            "validationCommit": COMMIT,
            "validationTree": TREE,
            "lanes": {
                "android": {"build": True, "test": False, "metadata": False},
                "contracts": {"build": True, "test": False, "metadata": False},
                "ios-rust-device": {"build": True, "test": False, "metadata": False},
            },
        }
        self.write_plan()
        self.write_promotion(COMMIT)
        self.write_home(".gradle/caches/modules-2/files-2.1/group/module/artifact.jar", b"jar")
        self.write_home(".konan/dependencies/downloaded/archive.tar.gz", b"konan")
        self.write_home("project/build/classes/forbidden.class", b"project-output")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_plan(self) -> None:
        self.plan_path.write_text(json.dumps(self.plan), encoding="utf-8")

    def write_promotion(self, source_commit: str) -> None:
        summary = {
            "runId": 71,
            "runAttempt": 2,
            "artifactName": f"codex-agent-ci-android-{TREE}",
            "validationCommit": source_commit,
            "validationTree": TREE,
            "result": "passed",
        }
        aggregate = {
            "schemaVersion": 1,
            "repository": REPO,
            "event": "merge_group",
            "validationCommit": COMMIT,
            "validationTree": TREE,
            "impactPlan": "impact-plan.json",
            "lanes": {"android": summary},
            "result": "passed",
        }
        promotion = {
            "schemaVersion": 1,
            "repository": REPO,
            "finalCommit": "4" * 40,
            "finalTree": TREE,
            "validatedCommit": COMMIT,
            "validatedTree": TREE,
            "validationRunId": 80,
            "validationRunAttempt": 1,
            "sourcePlanArtifactName": f"codex-agent-ci-plan-{TREE}",
            "sourceAggregateArtifactName": f"codex-agent-ci-validation-{TREE}",
            "promotedAggregateArtifactName": f"codex-agent-promoted-validation-{'4' * 40}",
            "promotedInventoryArtifactName": f"codex-agent-promoted-inventories-{'4' * 40}",
            "lanes": {
                "android": {
                    "sourceKind": "validation",
                    "sourceRunId": 71,
                    "sourceRunAttempt": 2,
                    "sourceArtifactName": summary["artifactName"],
                    "sourcePromotionRunId": None,
                    "sourcePromotionCommit": None,
                    "promotedArtifactName": f"codex-agent-promoted-android-{'4' * 40}",
                },
            },
        }
        self.aggregate_path.write_text(json.dumps(aggregate), encoding="utf-8")
        self.promotion_path.write_text(json.dumps(promotion), encoding="utf-8")

    def write_home(self, relative: str, data: bytes) -> None:
        path = self.home / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def create_kmp(self, output: Path) -> dict[str, object]:
        return create(Namespace(
            plan=self.plan_path,
            root=output,
            home=self.home,
            kind="kmp",
            artifact_name=artifact_name("kmp", "Linux", "X64", TREE),
            repository=REPO,
            event=self.plan["event"],
            validation_commit=self.plan["validationCommit"],
            validation_tree=TREE,
            run_id="71",
            run_attempt="2",
            lane="android",
            runner_os="Linux",
            runner_arch="X64",
            cache_key=[
                "gradle=gradle-main-dependencies-v1-Linux-X64-abc",
                "konan=konan-main-v2-Linux-X64-none-abc",
            ],
        ))

    def test_elected_seed_contains_only_dependency_paths_and_installs_exact_bytes(self) -> None:
        seed = self.root / "seed"
        manifest = self.create_kmp(seed)
        self.assertEqual({"gradle", "konan"}, set(manifest["caches"]))
        self.assertFalse(any("project" in path.as_posix() for path in seed.rglob("*")))

        destination = self.root / "consumer-home"
        outputs = install(Namespace(
            plan=self.plan_path,
            promotion_plan=self.promotion_path,
            aggregate=self.aggregate_path,
            root=seed,
            home=destination,
            kind="kmp",
            runner_os="Linux",
            runner_arch="X64",
            github_output=None,
        ))
        self.assertTrue(outputs["gradle"])
        self.assertTrue(outputs["konan"])
        self.assertEqual(
            b"jar",
            (destination / ".gradle/caches/modules-2/files-2.1/group/module/artifact.jar").read_bytes(),
        )
        self.assertEqual(
            b"konan",
            (destination / ".konan/dependencies/downloaded/archive.tar.gz").read_bytes(),
        )

    def test_cache_tree_dereferences_safe_konan_symlinks_and_rejects_unsafe_links(self) -> None:
        source = self.root / "konan"
        clang = source / "llvm-19-aarch64-macos-essentials-79/bin"
        clang.mkdir(parents=True)
        (clang / "clang-19").write_bytes(b"clang")
        (clang / "clang-19").chmod(0o740)
        (clang / "clang").symlink_to("clang-19")
        destination = self.root / "payload"
        self.assertTrue(copy_regular_tree(source, destination))
        copied = destination / "llvm-19-aarch64-macos-essentials-79/bin/clang"
        self.assertFalse(copied.is_symlink())
        self.assertEqual(b"clang", copied.read_bytes())
        self.assertEqual(0o100, copied.stat().st_mode & 0o111)
        self.assertEqual(
            b"clang",
            (destination / "llvm-19-aarch64-macos-essentials-79/bin/clang-19").read_bytes(),
        )

        outside = self.root / "outside"
        outside.write_bytes(b"outside")
        for name, target, message in (
            ("absolute", outside, "absolute symlink"),
            ("escape", Path("../outside"), "escapes its root"),
            ("dangling", Path("missing"), "dangling or cyclic symlink"),
        ):
            with self.subTest(name=name):
                unsafe = self.root / f"unsafe-{name}"
                unsafe.mkdir()
                (unsafe / "clang").symlink_to(target)
                with self.assertRaisesRegex(ValueError, message):
                    copy_regular_tree(unsafe, self.root / f"rejected-{name}")

    def test_install_restores_exact_executable_modes_lost_by_artifact_transport(self) -> None:
        clang = self.home / ".konan/dependencies/llvm-19-aarch64-macos-essentials-79/bin"
        clang.mkdir(parents=True)
        (clang / "clang-19").write_bytes(b"clang")
        (clang / "clang-19").chmod(0o740)
        (clang / "clang").symlink_to("clang-19")
        seed = self.root / "seed"
        manifest = self.create_kmp(seed)
        modes = manifest["caches"]["konan"]["executableModes"]
        expected = {
            ".konan/dependencies/llvm-19-aarch64-macos-essentials-79/bin/clang": 0o100,
            ".konan/dependencies/llvm-19-aarch64-macos-essentials-79/bin/clang-19": 0o100,
        }
        self.assertEqual(expected, {path: modes[path] for path in expected})
        for path in (seed / "payload").rglob("*"):
            if path.is_file():
                path.chmod(path.stat().st_mode & ~0o111)

        destination = self.root / "consumer-home"
        install(Namespace(
            plan=self.plan_path,
            promotion_plan=self.promotion_path,
            aggregate=self.aggregate_path,
            root=seed,
            home=destination,
            kind="kmp",
            runner_os="Linux",
            runner_arch="X64",
            github_output=None,
        ))
        for relative, mode in expected.items():
            self.assertEqual(mode, (destination / relative).stat().st_mode & 0o111)

        if hasattr(os, "mkfifo"):
            special = self.root / "unsafe-special"
            special.mkdir()
            os.mkfifo(special / "pipe")
            (special / "clang").symlink_to("pipe")
            with self.assertRaisesRegex(ValueError, "special file"):
                copy_regular_tree(special, self.root / "rejected-special")

    def test_corrupt_or_wrong_tree_seed_is_rejected_without_touching_home(self) -> None:
        seed = self.root / "seed"
        self.create_kmp(seed)
        artifact = seed / "payload/gradle/.gradle/caches/modules-2/files-2.1/group/module/artifact.jar"
        artifact.write_bytes(b"corrupt")
        destination = self.root / "consumer-home"
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            install(Namespace(
                plan=self.plan_path,
                promotion_plan=self.promotion_path,
                aggregate=self.aggregate_path,
                root=seed,
                home=destination,
                kind="kmp",
                runner_os="Linux",
                runner_arch="X64",
                github_output=None,
            ))
        self.assertFalse(destination.exists())

        self.create_kmp(seed)
        self.plan["validationTree"] = "3" * 40
        self.write_plan()
        with self.assertRaisesRegex(ValueError, "validated tree"):
            install(Namespace(
                plan=self.plan_path,
                promotion_plan=self.promotion_path,
                aggregate=self.aggregate_path,
                root=seed,
                home=destination,
                kind="kmp",
                runner_os="Linux",
                runner_arch="X64",
                github_output=None,
            ))

    def test_install_validates_every_payload_before_touching_existing_caches(self) -> None:
        seed = self.root / "seed"
        self.create_kmp(seed)
        konan_payload = seed / "payload/konan"
        llvm = konan_payload / ".konan/dependencies/llvm/lib"
        llvm.mkdir(parents=True)
        (llvm.parent / "lib64").symlink_to("lib", target_is_directory=True)

        destination = self.root / "consumer-home"
        gradle = destination / ".gradle/caches/modules-2/files-2.1/group/module/artifact.jar"
        konan = destination / ".konan/dependencies/downloaded/archive.tar.gz"
        for path, contents in ((gradle, b"existing-gradle"), (konan, b"existing-konan")):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(contents)

        arguments = Namespace(
            plan=self.plan_path,
            promotion_plan=self.promotion_path,
            aggregate=self.aggregate_path,
            root=seed,
            home=destination,
            kind="kmp",
            runner_os="Linux",
            runner_arch="X64",
            github_output=None,
        )
        with self.assertRaisesRegex(ValueError, "symlink"):
            install(arguments)
        self.assertEqual(b"existing-gradle", gradle.read_bytes())
        self.assertEqual(b"existing-konan", konan.read_bytes())

        (llvm.parent / "lib64").unlink()
        manifest_path = seed / "cache-seed.json"
        original_manifest = manifest_path.read_text(encoding="utf-8")
        manifest = json.loads(original_manifest)
        manifest["caches"]["konan"]["executableModes"] = {"../escape": 0o100}
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "executable mode"):
            install(arguments)
        self.assertEqual(b"existing-gradle", gradle.read_bytes())
        self.assertEqual(b"existing-konan", konan.read_bytes())
        manifest_path.write_text(original_manifest, encoding="utf-8")

        outside_root = konan_payload / "outside"
        outside_root.write_bytes(b"executable")
        manifest = json.loads(original_manifest)
        manifest["caches"]["konan"]["sha256"] = tree_digest(konan_payload)
        manifest["caches"]["konan"]["executableModes"] = {"outside": 0o100}
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "executable mode"):
            install(arguments)
        self.assertEqual(b"existing-gradle", gradle.read_bytes())
        self.assertEqual(b"existing-konan", konan.read_bytes())
        outside_root.unlink()
        manifest_path.write_text(original_manifest, encoding="utf-8")
        if hasattr(os, "mkfifo"):
            os.mkfifo(llvm / "pipe")
            with self.assertRaisesRegex(ValueError, "special file"):
                tree_digest(konan_payload)

    def test_policy_elects_one_merge_group_seed_and_one_pr_writer(self) -> None:
        arguments = Namespace(
            plan=self.plan_path,
            lane="android",
            validation_commit=COMMIT,
            runner_os="Linux",
            runner_arch="X64",
            github_output=None,
        )
        merge_environment = {
            "GITHUB_EVENT_NAME": "merge_group",
            "GITHUB_REPOSITORY": REPO,
            "GITHUB_SHA": COMMIT,
            "GITHUB_REF": "refs/heads/gh-readonly-queue/main/pr-7-deadbeef",
            "PR_NUMBER": "",
        }
        with patch.dict(os.environ, merge_environment, clear=True):
            result = policy(arguments)
        self.assertTrue(result["seed"])
        self.assertFalse(result["write"])

        self.plan["event"] = "pull_request"
        self.write_plan()
        pull_environment = {
            "GITHUB_EVENT_NAME": "pull_request",
            "GITHUB_REPOSITORY": REPO,
            "GITHUB_SHA": COMMIT,
            "GITHUB_REF": "refs/pull/7/merge",
            "PR_NUMBER": "7",
        }
        with patch.dict(os.environ, pull_environment, clear=True):
            result = policy(arguments)
        self.assertTrue(result["write"])
        self.assertTrue(result["seed"])

    def test_policy_elects_cargo_writer_from_producer_lanes(self) -> None:
        self.plan["event"] = "pull_request"
        self.plan["lanes"] = {
            "ios-kotlin-tests": {"build": False, "test": True, "metadata": False},
            "ios-rust-simulator": {"build": True, "test": False, "metadata": False},
        }
        self.write_plan()
        environment = {
            "GITHUB_EVENT_NAME": "pull_request",
            "GITHUB_REPOSITORY": REPO,
            "GITHUB_SHA": COMMIT,
            "GITHUB_REF": "refs/pull/7/merge",
            "PR_NUMBER": "7",
        }
        arguments = Namespace(
            plan=self.plan_path,
            lane="ios-rust-simulator",
            validation_commit=COMMIT,
            runner_os="macOS",
            runner_arch="ARM64",
            github_output=None,
        )
        with patch.dict(os.environ, environment, clear=True):
            result = policy(arguments)
        self.assertTrue(result["rust-write"])
        self.assertTrue(result["rust-seed"])

    def test_identical_tree_merge_group_promotes_pr_source_seed_without_product_job(self) -> None:
        pr_commit = "3" * 40
        self.plan["event"] = "pull_request"
        self.plan["validationCommit"] = pr_commit
        self.write_plan()
        seed = self.root / "pr-seed"
        manifest = self.create_kmp(seed)
        self.assertEqual("pull_request", manifest["event"])
        self.assertEqual(pr_commit, manifest["validationCommit"])

        self.plan["event"] = "merge_group"
        self.plan["validationCommit"] = COMMIT
        self.write_plan()
        self.write_promotion(pr_commit)
        selected = source(Namespace(
            plan=self.plan_path,
            promotion_plan=self.promotion_path,
            aggregate=self.aggregate_path,
            kind="kmp",
            runner_os="Linux",
            runner_arch="X64",
            github_output=None,
        ))
        self.assertEqual(71, selected["run-id"])
        self.assertEqual("android", selected["lane"])
        destination = self.root / "promoted-home"
        result = install(Namespace(
            plan=self.plan_path,
            promotion_plan=self.promotion_path,
            aggregate=self.aggregate_path,
            root=seed,
            home=destination,
            kind="kmp",
            runner_os="Linux",
            runner_arch="X64",
            github_output=None,
        ))
        self.assertTrue(result["gradle"])

    def test_workflow_contract_is_main_scoped_dependency_only_and_no_build(self) -> None:
        lane = (REPOSITORY / ".github/actions/run-ci-lane/action.yml").read_text(encoding="utf-8")
        kmp = (REPOSITORY / ".github/actions/setup-kmp/action.yml").read_text(encoding="utf-8")
        sccache = (REPOSITORY / ".github/actions/setup-sccache/action.yml").read_text(encoding="utf-8")
        promotion = (REPOSITORY / ".github/workflows/promote.yml").read_text(encoding="utf-8")
        self.assertIn("ci/cache_seed.py policy", lane)
        self.assertIn("GITHUB_EVENT_NAME", lane)
        self.assertIn("gradle-main-dependencies-v1", kmp)
        self.assertIn("konan-main-v2", kmp)
        self.assertIn("cargo-main-dependencies-v1", sccache)
        self.assertIn("actions/cache/save@", promotion)
        for path in (
            "~/.gradle/caches/modules-2",
            "~/.konan/dependencies",
            "~/.cargo/registry/index\n",
            "~/.cargo/registry/cache\n",
            "~/.cargo/git/db\n",
        ):
            self.assertIn(path, kmp + sccache)
            self.assertIn(path, promotion)
        konan_main = kmp[kmp.index("    - id: konan-main-read"):kmp.index("    - id: konan-pr-read")]
        self.assertIn("path: ~/.konan/dependencies", konan_main)
        self.assertNotIn("path: ~/.konan\n", konan_main)
        cache_job = promotion[promotion.index("  cache-seeds:"):promotion.index("  aggregate:")]
        for forbidden in ("./gradlew", "cargo build", "cargo test", "xcodebuild", "swift test"):
            self.assertNotIn(forbidden, cache_job)
        for forbidden in ("/build", "DerivedData", ".cargo/target"):
            self.assertNotIn(forbidden, kmp + sccache + cache_job)


if __name__ == "__main__":
    unittest.main()
