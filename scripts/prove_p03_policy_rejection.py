#!/usr/bin/env python3
"""Prove the P03 authoritative-money source policy rejects a committed real violation."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_RULES = {
    "[MONEY-BINARY-FLOAT]",
    "[MONEY-UNCHECKED-SUM]",
    "[MONEY-UNCHECKED-ACCUMULATION]",
}
EXPECTED_FIXTURES = {
    "BinaryFloatAmount.kt": "[MONEY-BINARY-FLOAT]",
    "UncheckedFoldAmount.kt": "[MONEY-UNCHECKED-SUM]",
    "UncheckedReduceAmount.kt": "[MONEY-UNCHECKED-SUM]",
    "UncheckedLoopAmount.kt": "[MONEY-UNCHECKED-ACCUMULATION]",
}


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
    missing_fixtures = {
        f"{fixture}:{rule}"
        for fixture, rule in EXPECTED_FIXTURES.items()
        if not any(fixture in line and rule in line for line in output.splitlines())
    }
    if missing or missing_fixtures:
        print(output, file=sys.stderr)
        print(
            "P03 source-policy rejection proof failed: "
            f"rules absent={sorted(missing)}, fixture diagnostics absent={sorted(missing_fixtures)}.",
            file=sys.stderr,
        )
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
