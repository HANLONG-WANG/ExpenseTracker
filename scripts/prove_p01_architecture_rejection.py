#!/usr/bin/env python3
"""Prove the Gradle architecture gate rejects a domain framework dependency."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_RULE = "[ARCH-DOMAIN-FRAMEWORK]"


def main() -> int:
    environment = os.environ.copy()
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    completed = subprocess.run(
        [
            str(ROOT / "gradlew"),
            "verifyArchitecture",
            "-I",
            "quality/fixtures/rejected/architecture/domain-framework.init.gradle.kts",
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
    if completed.returncode == 0 or EXPECTED_RULE not in completed.stdout:
        print(completed.stdout, file=sys.stderr)
        print("P01 architecture rejection proof failed.", file=sys.stderr)
        return 1
    diagnostic = next(line.strip() for line in completed.stdout.splitlines() if EXPECTED_RULE in line)
    print(f"P01 architecture rejection proof passed: {diagnostic}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
