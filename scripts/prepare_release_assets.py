#!/usr/bin/env python3
"""Release asset naming — one place, one convention.

Operator decision (2026-11-28): the published APKs are named after the product,
not after the signing identity:

    VpnM-<versionName>-<abi>.apk          e.g. VpnM-2.4.22-arm64-v8a.apk

The previous convention used the `SoftEthergai-` prefix, which is the *keystore*
identity and not the app name; users downloading a build saw a brand that does
not appear anywhere in the app. Gradle itself emits
`app-pro-<abi>-release.apk`, so the rename has to happen somewhere — this script
is that somewhere, and `scripts/test_prepare_release_assets.py` pins the rules.

Usage:
    # after `./gradlew :app:assembleProRelease -PabiSplits`
    python scripts/prepare_release_assets.py

    # explicit paths / version
    python scripts/prepare_release_assets.py --apk-dir app/build/outputs/apk/pro/release --out-dir dist

Nothing is uploaded: the files land in `dist/` (git-ignored) and the operator
attaches them to the GitHub release.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path
from typing import Iterable, List, Optional, Tuple

PRODUCT = "VpnM"

# Gradle's per-ABI outputs, in the order the release page lists them.
ABI_ORDER: Tuple[str, ...] = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal")

_SPLIT_RE = re.compile(r"^app-pro-(?P<abi>arm64-v8a|armeabi-v7a|x86_64|x86)-release\.apk$")
_UNIVERSAL_NAMES = ("app-pro-universal-release.apk", "app-pro-release.apk")


def release_asset_name(version: str, abi: str) -> str:
    """`VpnM-2.4.22-arm64-v8a.apk` — the only naming rule in the project."""
    return f"{PRODUCT}-{version}-{abi}.apk"


def classify_gradle_apk(name: str) -> Optional[str]:
    """Map a Gradle output file name to its release ABI kind, or None."""
    if name in _UNIVERSAL_NAMES:
        return "universal"
    match = _SPLIT_RE.match(name)
    return match.group("abi") if match else None


def read_version(apk_dir: Path, override: Optional[str]) -> Optional[str]:
    """versionName from `output-metadata.json`, or the explicit override."""
    if override:
        return override
    meta = apk_dir / "output-metadata.json"
    if not meta.is_file():
        return None
    try:
        data = json.loads(meta.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    for element in data.get("elements", []):
        version = element.get("versionName")
        if version:
            return str(version)
    return None


def collect(apk_dir: Path) -> List[Tuple[str, Path]]:
    """(abi, path) for every recognised release APK, in release-page order."""
    found: List[Tuple[str, Path]] = []
    for path in sorted(apk_dir.glob("*.apk")):
        abi = classify_gradle_apk(path.name)
        if abi:
            found.append((abi, path))
    found.sort(key=lambda item: ABI_ORDER.index(item[0]) if item[0] in ABI_ORDER else 99)
    return found


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def prepare(
    apk_dir: Path,
    out_dir: Path,
    version: Optional[str] = None,
    dry_run: bool = False,
) -> List[Tuple[Path, Path, str]]:
    """Copy the APKs under the release name. Returns (destination, source, sha256)."""
    resolved_version = read_version(apk_dir, version)
    if not resolved_version:
        raise SystemExit(
            f"cannot determine versionName: pass --version or provide {apk_dir}/output-metadata.json"
        )
    entries = collect(apk_dir)
    if not entries:
        raise SystemExit(f"no release APKs found in {apk_dir}")

    results: List[Tuple[Path, Path, str]] = []
    if not dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)
    for abi, source in entries:
        destination = out_dir / release_asset_name(resolved_version, abi)
        if not dry_run:
            shutil.copyfile(source, destination)
        results.append((destination, source, sha256(source)))
    return results


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--apk-dir",
        default="app/build/outputs/apk/pro/release",
        help="directory holding the Gradle release APKs",
    )
    parser.add_argument("--out-dir", default="dist", help="where the renamed APKs are written")
    parser.add_argument("--version", default=None, help="override the versionName")
    parser.add_argument("--dry-run", action="store_true", help="print the names, copy nothing")
    return parser


def main(argv: Optional[Iterable[str]] = None) -> int:
    args = build_parser().parse_args(list(argv) if argv is not None else None)
    apk_dir = Path(args.apk_dir)
    out_dir = Path(args.out_dir)
    results = prepare(apk_dir, out_dir, args.version, args.dry_run)

    print(f"{'asset':<34} {'size':>12}  sha256")
    print("-" * 34 + " " + "-" * 12 + "  " + "-" * 16)
    for destination, source, digest in results:
        print(f"{destination.name:<34} {source.stat().st_size:>12,}  {digest[:16]}…")
    if args.dry_run:
        print("\n--dry-run: nothing was copied")
    else:
        print(f"\n{len(results)} asset(s) written to {out_dir.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
