from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CI_ROOT))

from runner_identity import (  # noqa: E402
    ROLES,
    TOOLCHAIN_KEYS,
    bound_toolchain,
    observe_identity,
    verify_directories,
    verify_manifest,
    write_manifest,
)


class RunnerIdentityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        (self.root / "gradle").mkdir()
        (self.root / "gradle/libs.versions.toml").write_text('kotlin = "2.3.10"\n', encoding="utf-8")
        self.identities = self.root / "identities"
        self.identities.mkdir()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def identity(self, role: str) -> tuple[dict[str, str], dict[str, str]]:
        arch = "ARM64" if role == "linux-arm64-supervisor" else "X64"
        runner = {"os": "Linux", "arch": arch, "image": "ubuntu24", "imageVersion": "20260818.1"}
        toolchain = {key: f"observed-{key}" for key in TOOLCHAIN_KEYS}
        write_manifest(self.identities / ROLES[role][0], role, runner, toolchain)
        return runner, toolchain

    def test_capture_observes_installed_tools_and_normalizes_optional_tools(self) -> None:
        outputs = {
            ("./gradlew", "--version", "--no-daemon"): "Gradle 9.4.1\n",
            ("java", "-XshowSettings:properties", "-version"): (
                "    java.runtime.version = 17.0.15+6\n    java.vendor = Eclipse Adoptium\n"
            ),
            ("node", "--version"): "v24.18.0\n",
        }
        runner, toolchain = observe_identity(
            self.root,
            node_requested="24.18.0",
            environment={"RUNNER_OS": "Linux", "RUNNER_ARCH": "X64"},
            execute=lambda command, _root: outputs[command],
        )
        self.assertEqual({"os": "Linux", "arch": "X64", "image": "unavailable", "imageVersion": "unavailable"}, runner)
        self.assertEqual("9.4.1", toolchain["gradle"])
        self.assertEqual("2.3.10", toolchain["kotlinPlugin"])
        self.assertEqual("v24.18.0", toolchain["node"])
        self.assertEqual("unavailable", toolchain["rustc"])

    def test_bind_requires_both_canonical_role_manifests(self) -> None:
        self.identity("linux-arm64-supervisor")
        with self.assertRaisesRegex(ValueError, "file set mismatch"):
            bound_toolchain(self.identities)
        self.identity("linux-x64-cross-builder")
        bindings = dict(item.split("=", 1) for item in bound_toolchain(self.identities))
        for role, (filename, key, _) in ROLES.items():
            self.assertEqual(hashlib.sha256((self.identities / filename).read_bytes()).hexdigest(), bindings[key], role)

    def test_role_cannot_claim_the_wrong_runner(self) -> None:
        runner = {"os": "Linux", "arch": "X64", "image": "ubuntu24", "imageVersion": "20260818.1"}
        toolchain = {key: f"observed-{key}" for key in TOOLCHAIN_KEYS}
        with self.assertRaisesRegex(ValueError, "wrong or unidentified runner"):
            write_manifest(
                self.identities / ROLES["linux-arm64-supervisor"][0],
                "linux-arm64-supervisor",
                runner,
                toolchain,
            )

    def test_producer_requires_an_identified_hosted_image(self) -> None:
        runner = {"os": "Linux", "arch": "ARM64", "image": "unavailable", "imageVersion": "unavailable"}
        toolchain = {key: f"observed-{key}" for key in TOOLCHAIN_KEYS}
        with self.assertRaisesRegex(ValueError, "wrong or unidentified runner"):
            write_manifest(
                self.identities / ROLES["linux-arm64-supervisor"][0],
                "linux-arm64-supervisor",
                runner,
                toolchain,
            )

    def test_linux_arm_staging_fails_closed_without_producer_identities(self) -> None:
        plan = self.root / "plan.json"
        plan.write_text(json.dumps({
            "lanes": {
                "desktop-linux-arm64": {
                    "build": True,
                    "test": False,
                    "metadata": False,
                },
            },
        }), encoding="utf-8")
        result = subprocess.run(
            [
                sys.executable,
                str(CI_ROOT / "stage.py"),
                "--plan", str(plan),
                "--lane", "desktop-linux-arm64",
                "--output", str(self.root / "output"),
                "--artifact-name", "codex-agent-ci-desktop-linux-arm64-tree",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Linux ARM64 staging requires exact producer identities", result.stdout)

    def test_noncanonical_or_mutated_transport_is_rejected(self) -> None:
        for role in ROLES:
            self.identity(role)
        actual = self.root / "actual"
        actual.mkdir()
        for filename, _, _ in ROLES.values():
            (actual / filename).write_bytes((self.identities / filename).read_bytes())
        verify_directories(self.identities, actual)
        target = actual / ROLES["linux-x64-cross-builder"][0]
        target.write_bytes(target.read_bytes().replace(b"observed-node", b"tampered-node"))
        with self.assertRaisesRegex(ValueError, "Transported producer identity mismatch"):
            verify_directories(self.identities, actual)
        with self.assertRaisesRegex(ValueError, "Current producer identity mismatch"):
            verify_manifest(
                self.identities / ROLES["linux-x64-cross-builder"][0],
                target,
                "linux-x64-cross-builder",
            )


if __name__ == "__main__":
    unittest.main()
