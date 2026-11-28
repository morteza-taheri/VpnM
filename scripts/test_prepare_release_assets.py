#!/usr/bin/env python3
"""Tests for the release asset naming convention (`VpnM-<version>-<abi>.apk`).

Run with:
    python -m unittest scripts.test_prepare_release_assets -v
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import prepare_release_assets as prep  # noqa: E402


class NameTest(unittest.TestCase):
    def test_name_is_product_version_abi(self) -> None:
        self.assertEqual(prep.release_asset_name("2.4.22", "arm64-v8a"), "VpnM-2.4.22-arm64-v8a.apk")

    def test_signing_identity_is_not_part_of_the_name(self) -> None:
        name = prep.release_asset_name("2.4.22", "universal")
        self.assertNotIn("SoftEthergai", name)
        self.assertTrue(name.startswith("VpnM-"))


class ClassifyTest(unittest.TestCase):
    def test_every_gradle_slice(self) -> None:
        for abi in ("arm64-v8a", "armeabi-v7a", "x86_64", "x86"):
            self.assertEqual(prep.classify_gradle_apk(f"app-pro-{abi}-release.apk"), abi)

    def test_universal_and_single_apk_outputs(self) -> None:
        self.assertEqual(prep.classify_gradle_apk("app-pro-universal-release.apk"), "universal")
        self.assertEqual(prep.classify_gradle_apk("app-pro-release.apk"), "universal")

    def test_unrelated_outputs_are_ignored(self) -> None:
        self.assertIsNone(prep.classify_gradle_apk("app-pro-debug.apk"))
        self.assertIsNone(prep.classify_gradle_apk("app-pro-arm64-v8a-debug.apk"))


class PrepareTest(unittest.TestCase):
    def _fixture(self, root: Path, version: str = "9.9.9") -> Path:
        apk_dir = root / "apk"
        apk_dir.mkdir(parents=True)
        for name in ("app-pro-arm64-v8a-release.apk", "app-pro-universal-release.apk"):
            (apk_dir / name).write_bytes(b"fake-apk-" + name.encode())
        (apk_dir / "output-metadata.json").write_text(
            json.dumps({"elements": [{"versionName": version}]}), encoding="utf-8"
        )
        return apk_dir

    def test_copies_under_the_release_name_in_release_order(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk_dir = self._fixture(root)
            out = root / "dist"
            results = prep.prepare(apk_dir, out, dry_run=False)
            names = [dest.name for dest, _src, _sha in results]
            self.assertEqual(names, ["VpnM-9.9.9-arm64-v8a.apk", "VpnM-9.9.9-universal.apk"])
            for dest, src, digest in results:
                self.assertTrue(dest.is_file())
                self.assertEqual(dest.read_bytes(), src.read_bytes())
                self.assertEqual(len(digest), 64)

    def test_dry_run_writes_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk_dir = self._fixture(root)
            out = root / "dist"
            results = prep.prepare(apk_dir, out, dry_run=True)
            self.assertEqual(len(results), 2)
            self.assertFalse(out.exists())

    def test_version_override_wins(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk_dir = self._fixture(root)
            results = prep.prepare(apk_dir, root / "dist", version="1.2.3", dry_run=True)
            self.assertEqual(results[0][0].name, "VpnM-1.2.3-arm64-v8a.apk")

    def test_missing_metadata_without_override_is_fatal(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk_dir = root / "apk"
            apk_dir.mkdir()
            (apk_dir / "app-pro-arm64-v8a-release.apk").write_bytes(b"x")
            with self.assertRaises(SystemExit):
                prep.prepare(apk_dir, root / "dist", dry_run=True)


if __name__ == "__main__":
    unittest.main()
