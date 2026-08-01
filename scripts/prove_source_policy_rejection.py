#!/usr/bin/env python3
"""Run a committed violation fixture and prove the Gradle source gate rejects it."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_RULES = {
    "[ARCH-FEATURE-DATA]",
    "[UI-WRAPPER]",
    "[UI-COLOR-LITERAL]",
    "[UI-SPACING-LITERAL]",
    "[UI-LOCAL-THEME]",
    "[ARCH-DOMAIN-FRAMEWORK]",
    "[PRIVACY-TELEMETRY-MAP]",
    "[PRIVACY-LOGGING]",
    "[PRIVACY-ROUTE-STATE]",
    "[PRIVACY-SAVEDSTATE-KEY]",
    "[FINANCE-COORDINATOR]",
    "[DETERMINISM-CLOCK]",
    "[DETERMINISM-ID]",
    "[UI-SCREEN-ID]",
}
EXPECTED_FIXTURES = {
    "DaoAliasBypass.kt": "[FINANCE-COORDINATOR]",
    "DecoyCoordinatorBypass.kt": "[FINANCE-COORDINATOR]",
    "RouteWrapperLeak.kt": "[PRIVACY-ROUTE-STATE]",
    "SavedStateAliasLeak.kt": "[PRIVACY-SAVEDSTATE-KEY]",
    "TelemetryAliasLeak.kt": "[PRIVACY-TELEMETRY-MAP]",
    "LoggingAliasLeak.kt": "[PRIVACY-LOGGING]",
    "DomainFrameworkLeak.kt": "[ARCH-DOMAIN-FRAMEWORK]",
    "UnknownScreenAndNondeterminism.kt": "[UI-SCREEN-ID]",
    "UngovernedUi.kt": "[UI-WRAPPER]",
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
        print("P02 source-policy rejection proof failed: violation fixture was accepted.", file=sys.stderr)
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
            "P02 source-policy rejection proof failed: "
            f"rules absent={sorted(missing)}, fixture diagnostics absent={sorted(missing_fixtures)}.",
            file=sys.stderr,
        )
        return 1

    diagnostics = [
        line.strip()
        for line in output.splitlines()
        if any(rule in line for rule in EXPECTED_RULES)
    ]
    print(
        "P02 source-policy rejection proof passed: "
        f"{len(EXPECTED_RULES)} rule classes and {len(EXPECTED_FIXTURES)} named fixtures rejected."
    )
    for diagnostic in diagnostics:
        print(diagnostic)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
