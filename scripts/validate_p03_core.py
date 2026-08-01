#!/usr/bin/env python3
"""Validate P03 exact-money, deterministic-time and pure-Kotlin boundaries.

Only textual implementation contracts are queried. Excluded visual drafts are never opened.
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

from validate_p02_quality import validate_repository


ROOT = Path(__file__).resolve().parents[1]
PRODUCTION_ROOTS = [ROOT / "core/common/src/main", ROOT / "core/money/src/main", ROOT / "core/time/src/main"]
AUTHORITATIVE_MONEY_ROOTS = [ROOT / "core/money/src/main", ROOT / "finance/domain/src/main"]
TARGET_REQUIREMENTS = {"REQ-014", "REQ-015", "REQ-024", "REQ-025", "REQ-030"}
REQUIRED_TYPES = {
    "core/common/src/main/kotlin/app/ledger/core/common/Identifiers.kt": [
        "class StableId",
        "value class InternalId",
        "value class CommandId",
        "value class RevisionId",
    ],
    "core/common/src/main/kotlin/app/ledger/core/common/CheckedArithmetic.kt": [
        "Math.addExact",
        "Math.subtractExact",
        "fun abs",
        "Math.absExact",
    ],
    "core/common/src/main/kotlin/app/ledger/core/common/DomainResult.kt": ["sealed interface DomainResult"],
    "core/money/src/main/kotlin/app/ledger/core/money/Money.kt": ["data class Money", "BigInteger"],
    "core/money/src/main/kotlin/app/ledger/core/money/Fx.kt": ["data class FxEvidence", "MathContext"],
    "core/money/src/main/kotlin/app/ledger/core/money/MoneyExpression.kt": ["class MoneyExpressionEvaluator", "BigDecimal"],
    "core/money/src/main/kotlin/app/ledger/core/money/MoneyFormatting.kt": ["fun interface CurrencyFormatter", "data class MoneyUiModel"],
    "core/time/src/main/kotlin/app/ledger/core/time/LedgerClock.kt": ["fun interface LedgerClock"],
    "core/time/src/main/kotlin/app/ledger/core/time/EffectiveTime.kt": [
        "data class EffectiveTime",
        "ZoneId",
        "gapPolicy: GapPolicy = GapPolicy.REJECT",
        "data class TemporalAdjustment",
    ],
    "core/time/src/main/kotlin/app/ledger/core/time/CalendarPeriods.kt": ["data class BudgetMonthPeriod", "class StatementCycleCalculator"],
}


def files_under(roots: list[Path]) -> list[Path]:
    return sorted(path for root in roots if root.exists() for path in root.rglob("*.kt"))


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def validate() -> dict[str, int]:
    contract_counts = validate_repository()
    if contract_counts["requirements"] != 90 or contract_counts["screens"] != 215:
        raise AssertionError("complete textual contract query did not retain 90 requirements and 215 screens")

    production_files = files_under(PRODUCTION_ROOTS)
    if not production_files:
        raise AssertionError("P03 production sources are missing")
    for path in production_files:
        source = path.read_text(encoding="utf-8")
        if re.search(r"(?m)^import\s+(?:android|androidx|okhttp3|retrofit2|androidx\.room)(?:\.|$)", source):
            raise AssertionError(f"pure Kotlin boundary violation: {path.relative_to(ROOT)}")

    money_files = files_under(AUTHORITATIVE_MONEY_ROOTS)
    for path in money_files:
        source = path.read_text(encoding="utf-8")
        if re.search(r"\b(?:Float|Double)\b|\bto(?:Float|Double)\s*\(", source):
            raise AssertionError(f"binary floating point in authoritative money path: {path.relative_to(ROOT)}")
        if re.search(
            r"(?<!CheckedArithmetic)\.(?:sum|sumOf|fold|foldIndexed|reduce|reduceIndexed|runningFold|runningReduce)\s*(?:\(|\{)",
            source,
        ):
            raise AssertionError(f"unchecked aggregation in authoritative money path: {path.relative_to(ROOT)}")
        accumulators = set(
            re.findall(r"\bvar\s+(\w+)\s*(?::\s*Long)?\s*=\s*[^\n;]*?\b-?\d+L\b", source)
        )
        accumulators.update(re.findall(r"\bvar\s+(\w*(?:total|sum|balance|amount|accumul)\w*)\s*=", source))
        for accumulator in accumulators:
            escaped = re.escape(accumulator)
            unsafe = (
                rf"\b{escaped}\s*\+="
                rf"|\b{escaped}\s*=\s*{escaped}\s*\+"
                rf"|\b{escaped}\s*=\s*[^\n;]+\+\s*{escaped}\b"
                rf"|\b{escaped}(?:\+\+|--)"
            )
            if re.search(unsafe, source):
                raise AssertionError(f"unchecked Long accumulation in authoritative money path: {path.relative_to(ROOT)}")

    for relative, markers in REQUIRED_TYPES.items():
        path = ROOT / relative
        if not path.is_file():
            raise AssertionError(f"required P03 source missing: {relative}")
        source = path.read_text(encoding="utf-8")
        for marker in markers:
            if marker not in source:
                raise AssertionError(f"{relative} is missing required marker {marker!r}")

    requirement_rows = read_csv(ROOT / "docs/implementation/REQUIREMENT_COVERAGE.csv")
    tracked = {row["requirement_id"]: row for row in requirement_rows if row["requirement_id"] in TARGET_REQUIREMENTS}
    if set(tracked) != TARGET_REQUIREMENTS:
        raise AssertionError("P03 requirement tracking rows are incomplete")
    if {row["status"] for row in tracked.values()} != {"IN_PROGRESS"}:
        raise AssertionError("the five P03-supported end-to-end requirements must remain IN_PROGRESS")
    if any(not row["implementation_evidence"] or "P03-E" not in row["verification_evidence"] for row in tracked.values()):
        raise AssertionError("P03 requirement rows must retain implementation and verification evidence")

    screens = read_csv(ROOT / "docs/implementation/SCREEN_COVERAGE.csv")
    if len(screens) != 215 or {row["status"] for row in screens} != {"NOT_STARTED"}:
        raise AssertionError("P03 must not promote page implementation coverage")

    project_state = (ROOT / "docs/implementation/PROJECT_STATE.md").read_text(encoding="utf-8")
    domain_coverage = (ROOT / "docs/implementation/DOMAIN_AND_SCHEMA_COVERAGE.md").read_text(encoding="utf-8")
    test_evidence = (ROOT / "docs/implementation/TEST_EVIDENCE.md").read_text(encoding="utf-8")
    if "| P03 | VERIFIED |" not in project_state or any(f"P03-E{number:03d}" not in test_evidence for number in range(1, 10)):
        raise AssertionError("PROJECT_STATE does not retain P03 as a verified completed stage")
    if "| INV-034 |" not in domain_coverage or "INV-034` `VERIFIED" not in project_state:
        raise AssertionError("INV-034 verification is absent from the domain/project ledgers")
    workflow = (ROOT / ".github/workflows/quality.yml").read_text(encoding="utf-8")
    has_cumulative_aggregate = "./gradlew p03Check" in workflow or "./gradlew p04Check" in workflow
    if not has_cumulative_aggregate or "python3 scripts/prove_p03_policy_rejection.py" not in workflow:
        raise AssertionError("CI does not enforce the P03 aggregate and real-violation proof")

    return {
        "production_files": len(production_files),
        "authoritative_money_files": len(money_files),
        "tracked_requirements": len(tracked),
        "screens_unstarted": len(screens),
    }


def main() -> int:
    try:
        result = validate()
    except (AssertionError, OSError, ValueError) as error:
        print(f"P03 core validation: FAIL: {error}", file=sys.stderr)
        return 1
    print("P03 core validation: PASS")
    for name, value in result.items():
        print(f"{name}={value}")
    print("authoritative_float_double_references=0 unchecked_money_accumulations=0")
    print("visual_inputs=excluded_by_explicit_text-path allowlist")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
