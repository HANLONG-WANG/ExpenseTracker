#!/usr/bin/env python3
"""Run a committed violation fixture and prove the Gradle source gate rejects it."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_RULE = "[FINANCE-COORDINATOR]"


def main() -> int:
    completed = subprocess.run(
        [
            str(ROOT / "gradlew"),
            "verifySourcePolicies",
            "-PqualityPolicyFixture=quality/fixtures/rejected",
            "--no-configuration-cache",
            "--no-parallel",
            "--console=plain",
        ],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    output = completed.stdout
    if completed.returncode == 0:
        print("P02 source-policy rejection proof failed: violation fixture was accepted.", file=sys.stderr)
        return 1
    if EXPECTED_RULE not in output:
        print(output, file=sys.stderr)
        print(
            f"P02 source-policy rejection proof failed: expected {EXPECTED_RULE} diagnostic was absent.",
            file=sys.stderr,
        )
        return 1

    diagnostic = next(line.strip() for line in output.splitlines() if EXPECTED_RULE in line)
    print(f"P02 source-policy rejection proof passed: {diagnostic}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
