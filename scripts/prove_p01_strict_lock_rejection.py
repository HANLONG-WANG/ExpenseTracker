#!/usr/bin/env python3
"""Prove strict dependency locking rejects a resolvable configuration with no lock state."""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    environment = os.environ.copy()
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    completed = subprocess.run(
        [
            str(ROOT / "gradlew"),
            "resolveAuditUnlocked",
            "-I",
            "quality/fixtures/rejected/locking/missing-lock.init.gradle.kts",
            "--dependency-verification=strict",
            "--no-configuration-cache",
            "--no-parallel",
            "--console=plain",
        ],
        cwd=ROOT,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    lock_diagnostic = re.search(r"(?im)^.*(?:lock state|dependency lock).*$", completed.stdout)
    if completed.returncode == 0 or lock_diagnostic is None:
        print(completed.stdout, file=sys.stderr)
        print("P01 strict-lock rejection proof failed.", file=sys.stderr)
        return 1
    print(f"P01 strict-lock rejection proof passed: {lock_diagnostic.group(0).strip()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
