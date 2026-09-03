#!/usr/bin/env python3
"""Generate a deterministic manifest for the P36 release-candidate deliverables."""

from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/reports/release"
ARTIFACTS = (
    "app/build/outputs/bundle/release/app-release.aab",
    "app/src/main/baseline-prof.txt",
    "build/reports/cyclonedx/bom.json",
    "build/reports/dependency-license/licenses.csv",
    "build/reports/dependency-license/index.html",
    "build/reports/dependency-license/THIRD_PARTY_NOTICES.txt",
    "NOTICE",
    "docs/初始开发文件存档/release/ABOUT_AND_OPEN_SOURCE.md",
    "docs/初始开发文件存档/release/PLAY_RELEASE_INPUTS.md",
    "docs/初始开发文件存档/release/PRIVACY_POLICY_en.md",
    "docs/初始开发文件存档/release/PRIVACY_POLICY_ja.md",
    "docs/初始开发文件存档/release/PRIVACY_POLICY_zh-CN.md",
    "docs/初始开发文件存档/release/REPRODUCIBLE_BUILD.md",
    "docs/初始开发文件存档/release/RELEASE_NOTES_v1.0.0.md",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def bundle_is_signed(bundle: Path) -> bool:
    with zipfile.ZipFile(bundle) as archive:
        names = {name.upper() for name in archive.namelist()}
    return any(
        name.startswith("META-INF/") and name.endswith((".RSA", ".DSA", ".EC"))
        for name in names
    )


def main() -> int:
    missing = [relative for relative in ARTIFACTS if not (ROOT / relative).is_file()]
    if missing:
        print("P36 release manifest inputs are missing:", file=sys.stderr)
        for relative in missing:
            print(f"- {relative}", file=sys.stderr)
        return 1

    records = [
        {
            "path": relative,
            "bytes": (ROOT / relative).stat().st_size,
            "sha256": sha256(ROOT / relative),
        }
        for relative in ARTIFACTS
    ]
    bundle = ROOT / ARTIFACTS[0]
    metadata = {
        "schemaVersion": 1,
        "applicationVersion": "1.0.0",
        "versionCode": 1,
        "applicationIdSource": "ledgerApplicationId Gradle property; development default is not store approval",
        "bundleSignedWithExternalKey": bundle_is_signed(bundle),
        "artifacts": records,
    }
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "release-metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (OUTPUT / "p36-artifacts.sha256").write_text(
        "".join(f"{record['sha256']}  {record['path']}\n" for record in records),
        encoding="utf-8",
    )
    signing = "externally signed" if metadata["bundleSignedWithExternalKey"] else "unsigned external-signing candidate"
    print(f"P36 release manifest: PASS artifacts={len(records)} bundle={signing}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
