#!/usr/bin/env python3
"""Prove the P03 authoritative-money source policy rejects a committed real violation."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_RULES = {"[MONEY-BINARY-FLOAT]", "[MONEY-UNCHECKED-SUM]"}


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
        print("P03 source-policy rejection proof failed: binary floating-point fixture was accepted.", file=sys.stderr)
        return 1
    missing = {rule for rule in EXPECTED_RULES if rule not in output}
    if missing:
        print(output, file=sys.stderr)
        print(f"P03 source-policy rejection proof failed: expected diagnostics absent: {sorted(missing)}.", file=sys.stderr)
        return 1
    diagnostics = [
        line.strip()
        for line in output.splitlines()
        if any(rule in line for rule in EXPECTED_RULES)
    ]
    print("P03 source-policy rejection proof passed:")
    for diagnostic in diagnostics:
        print(diagnostic)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
